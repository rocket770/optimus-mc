package dev.optimus.client;

import com.google.gson.*;
import dev.optimus.core.control.CommandInput;
import dev.optimus.core.control.RemoteCommand;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Local, per-client control transport. Only pump() may execute a command on the client thread. */
final class TerminalBridge implements Closeable {
    interface Handler {
        List<String> execute(String[] args);
    }

    private final ServerSocket listener;
    private final String bot, token;
    private final File descriptor;
    private final ArrayBlockingQueue<Request> pending = new ArrayBlockingQueue<>(16);
    private final ThreadPoolExecutor workers;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile long startsCancelledThrough = Long.MIN_VALUE;
    private final Thread acceptor;

    TerminalBridge(File directory, String bot, File gameDirectory, String version)
            throws IOException {
        this.bot = bot;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        StringBuilder hex = new StringBuilder();
        for (byte value : secret) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        token = hex.toString();
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        workers =
                new ThreadPoolExecutor(
                        4,
                        4,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<Runnable>(8),
                        runnable -> {
                            Thread thread = new Thread(runnable, "Optimus terminal connection");
                            thread.setDaemon(true);
                            return thread;
                        });
        descriptor = new File(directory, UUID.randomUUID().toString() + ".json");
        try {
            Files.createDirectories(directory.toPath());
            JsonObject info = new JsonObject();
            info.addProperty("protocol", 1);
            info.addProperty("bot", bot);
            info.addProperty("host", "127.0.0.1");
            info.addProperty("port", listener.getLocalPort());
            info.addProperty("token", token);
            info.addProperty("gameDirectory", gameDirectory.getAbsolutePath());
            info.addProperty("version", version);
            Path temporary = Files.createTempFile(directory.toPath(), "session-", ".tmp");
            try {
                Files.write(temporary, info.toString().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temporary, descriptor.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temporary, descriptor.toPath());
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            listener.close();
            workers.shutdownNow();
            throw error;
        }
        descriptor.deleteOnExit();
        acceptor = new Thread(this::accept, "Optimus terminal listener");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    static String[] parse(String command, String bot) {
        if (command == null || command.length() > 256)
            throw new IllegalArgumentException("Command is missing or too long.");
        for (int i = 0; i < command.length(); i++)
            if (command.charAt(i) < 32 || command.charAt(i) == 127 || command.charAt(i) == '\u00a7')
                throw new IllegalArgumentException("Command contains unsupported characters.");
        String text = command.trim();
        if (text.equalsIgnoreCase("/o") || text.regionMatches(true, 0, "/o ", 0, 3))
            text = "!" + text.substring(1);
        else if (text.equalsIgnoreCase("o") || text.regionMatches(true, 0, "o ", 0, 2))
            text = "!" + text;
        else if (!CommandInput.isChatCommand(text)) text = "!o " + text;
        String[] args = RemoteCommand.parse(text, bot);
        if (args == null) throw new IllegalArgumentException("Command addresses a different bot.");
        return args;
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                connections.add(socket);
                if (closed) {
                    connections.remove(socket);
                    socket.close();
                    continue;
                }
                long acceptedAt = System.nanoTime();
                try {
                    workers.execute(() -> receive(socket, acceptedAt));
                } catch (RejectedExecutionException error) {
                    connections.remove(socket);
                    socket.close();
                }
            } catch (IOException error) {
                if (!closed) closeQuietly();
            }
        }
    }

    private void receive(Socket socket, long acceptedAt) {
        Request request = null;
        try (Socket connection = socket) {
            try {
                connection.setSoTimeout(1000);
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        connection.getInputStream(), StandardCharsets.UTF_8));
                String line = boundedLine(reader);
                JsonObject input = new JsonParser().parse(line).getAsJsonObject();
                if (!input.has("token") || !token.equals(input.get("token").getAsString())) {
                    respond(connection, false, Collections.singletonList("Authentication failed."));
                    return;
                }
                String[] args =
                        parse(
                                input.has("command") ? input.get("command").getAsString() : null,
                                bot);
                request = new Request(args, acceptedAt);
                if (closed || !pending.offer(request)) {
                    respond(
                            connection,
                            false,
                            Collections.singletonList(
                                    "Terminal command queue is unavailable or full."));
                    return;
                }
                if (!request.done.await(3500, TimeUnit.MILLISECONDS)) {
                    request.cancel();
                    respond(
                            connection,
                            false,
                            Collections.singletonList(
                                    "Client did not respond in time. Check status before retrying."));
                    return;
                }
                respond(connection, request.ok, request.messages);
            } catch (RuntimeException error) {
                respond(
                        connection,
                        false,
                        Collections.singletonList(
                                "Invalid terminal request: " + error.getMessage()));
            }
        } catch (IOException ignored) {
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            connections.remove(socket);
            if (request != null) {
                request.cancel();
                pending.remove(request);
            }
        }
    }

    private static String boundedLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int value; (value = reader.read()) != -1; ) {
            if (value == '\n') return line.toString();
            if (value == '\r') {
                if (reader.read() == '\n') return line.toString();
                throw new IOException("Invalid request line.");
            }
            if (line.length() >= 2048) throw new IOException("Oversized request.");
            line.append((char) value);
        }
        throw new EOFException("Incomplete request.");
    }

    private void respond(Socket socket, boolean ok, List<String> messages) throws IOException {
        JsonObject output = new JsonObject();
        output.addProperty("ok", ok);
        output.addProperty("bot", bot);
        JsonArray values = new JsonArray();
        for (String message : messages) values.add(new JsonPrimitive(message));
        output.add("messages", values);
        Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        writer.write(output.toString());
        writer.write('\n');
        writer.flush();
    }

    /**
     * Called only on a START client tick, after emergency-key handling. Never blocks for network
     * I/O.
     */
    void pump(Handler handler) {
        for (int budget = 0; budget < 4; budget++) {
            Request request = pending.poll();
            if (request == null) return;
            if ("start".equals(request.args[0]) && request.acceptedAt <= startsCancelledThrough) {
                request.cancel();
                request.complete(false, Collections.singletonList("Start cancelled by F9."));
                continue;
            }
            if (!request.claim()) {
                request.complete(
                        false, Collections.singletonList("Command expired without executing."));
                continue;
            }
            try {
                request.complete(true, handler.execute(request.args));
            } catch (RuntimeException error) {
                request.complete(
                        false, Collections.singletonList("Command failed: " + error.getMessage()));
            }
        }
    }

    void emergencyStop() {
        startsCancelledThrough = System.nanoTime();
    }

    int pendingCount() {
        return pending.size();
    }

    File descriptor() {
        return descriptor;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException failure = closeResource(listener, null);
        workers.shutdownNow();
        // shutdownNow discards queued workers, so their sockets need explicit cleanup too.
        for (Socket connection : connections) failure = closeResource(connection, failure);
        connections.clear();
        Request request;
        while ((request = pending.poll()) != null) {
            request.cancel();
            request.complete(false, Collections.singletonList("Client closed."));
        }
        try {
            Files.deleteIfExists(descriptor.toPath());
        } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private static IOException closeResource(Closeable resource, IOException failure) {
        try {
            resource.close();
        } catch (IOException error) {
            if (failure == null) return error;
            failure.addSuppressed(error);
        }
        return failure;
    }

    private void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {
        }
    }

    private static final class Request {
        final String[] args;
        final long acceptedAt, deadline;
        final CountDownLatch done = new CountDownLatch(1);
        boolean cancelled, claimed;
        volatile boolean ok;
        volatile List<String> messages = Collections.emptyList();

        Request(String[] args, long acceptedAt) {
            this.args = args;
            this.acceptedAt = acceptedAt;
            deadline = acceptedAt + TimeUnit.SECONDS.toNanos(2);
        }

        synchronized boolean claim() {
            if (cancelled || claimed || System.nanoTime() > deadline) return false;
            claimed = true;
            return true;
        }

        synchronized void cancel() {
            cancelled = true;
        }

        void complete(boolean success, List<String> response) {
            ok = success;
            messages = new ArrayList<>(response);
            done.countDown();
        }
    }
}