package dev.optimus.client.guard;

import com.google.gson.*;
import dev.optimus.core.navigation.NearbySteering;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Opt-in, same-computer input snapshots. Network threads never touch Minecraft. */
final class GuardInputRelay implements Closeable {
    static final class Frame {
        final String session, uuid, server, lobby, instance, victim;
        final long sequence, swing, sent, received;
        final double x, y, z, mx, mz;
        final float yaw, pitch;
        final NearbySteering.Input input;
        final boolean active, jump, sneak, sprint, sword;

        Frame(JsonObject data, long received) {
            session = data.get("session").getAsString();
            uuid = data.get("uuid").getAsString();
            server = data.get("server").getAsString();
            lobby = data.get("lobby").getAsString();
            instance = data.get("instance").getAsString();
            victim = data.get("victim").getAsString();
            sequence = data.get("sequence").getAsLong();
            swing = data.get("swing").getAsLong();
            sent = data.get("sent").getAsLong();
            this.received = received;
            x = data.get("x").getAsDouble();
            y = data.get("y").getAsDouble();
            z = data.get("z").getAsDouble();
            mx = data.get("mx").getAsDouble();
            mz = data.get("mz").getAsDouble();
            yaw = data.get("yaw").getAsFloat();
            pitch = data.get("pitch").getAsFloat();
            input = NearbySteering.Input.valueOf(data.get("input").getAsString());
            active = data.get("active").getAsBoolean();
            jump = data.get("jump").getAsBoolean();
            sneak = data.get("sneak").getAsBoolean();
            sprint = data.get("sprint").getAsBoolean();
            sword = data.get("sword").getAsBoolean();
            for (double number : new double[] {x, y, z, mx, mz, yaw, pitch})
                if (!Double.isFinite(number))
                    throw new IllegalArgumentException("Non-finite input");
            if (Math.abs(x) > 30000000
                    || Math.abs(z) > 30000000
                    || y < 0
                    || y > 256
                    || Math.abs(mx) > 4
                    || Math.abs(mz) > 4
                    || Math.abs(pitch) > 90
                    || sequence < 0
                    || swing < 0
                    || session.length() > 64
                    || victim.length() > 36)
                throw new IllegalArgumentException("Invalid input snapshot");
        }

        boolean fresh(String uuid, String server, String lobby, String instance, long now) {
            long age = now - received;
            return active
                    && age >= 0
                    && age <= 150
                    && this.uuid.equals(uuid)
                    && this.server.equalsIgnoreCase(server)
                    && this.lobby.equals(lobby)
                    && this.instance.equals(instance);
        }
    }

    private static final class Destination {
        final int port;
        final String token;

        Destination(int port, String token) {
            this.port = port;
            this.token = token;
        }
    }

    private final File directory;
    private final String session = UUID.randomUUID().toString();
    private final List<Destination> destinations = new ArrayList<>();
    private DatagramSocket receiver, sender;
    private File descriptor;
    private String receivingUuid = "", receivingToken = "";
    private volatile Frame latest;
    private long sequence, discoveredAt;
    private String discoveredUuid = "";

    GuardInputRelay(File directory) {
        this.directory = directory;
    }

    synchronized void follow(String uuid) throws IOException {
        stopFollowing();
        receivingUuid = UUID.fromString(uuid).toString();
        receivingToken = UUID.randomUUID().toString() + UUID.randomUUID();
        DatagramSocket socket =
                new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        receiver = socket;
        try {
            Files.createDirectories(directory.toPath());
            JsonObject data = new JsonObject();
            data.addProperty("uuid", receivingUuid);
            data.addProperty("port", socket.getLocalPort());
            data.addProperty("token", receivingToken);
            descriptor = new File(directory, session + ".json");
            Files.write(descriptor.toPath(), data.toString().getBytes(StandardCharsets.UTF_8));
            descriptor.deleteOnExit();
        } catch (IOException error) {
            stopFollowing();
            throw error;
        }
        final String uuidFilter = receivingUuid, tokenFilter = receivingToken;
        Thread thread =
                new Thread(
                        () -> receive(socket, uuidFilter, tokenFilter),
                        "Optimus guard input receiver");
        thread.setDaemon(true);
        thread.start();
    }

    private void receive(DatagramSocket socket, String uuid, String token) {
        byte[] buffer = new byte[4096];
        while (!socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (!packet.getAddress().isLoopbackAddress() || packet.getLength() > 2048) continue;
                JsonObject data =
                        new JsonParser()
                                .parse(
                                        new String(
                                                packet.getData(),
                                                packet.getOffset(),
                                                packet.getLength(),
                                                StandardCharsets.UTF_8))
                                .getAsJsonObject();
                if (!token.equals(data.get("token").getAsString())) continue;
                long now = System.currentTimeMillis();
                Frame frame = new Frame(data, now);
                if (!frame.uuid.equals(uuid) || now - frame.sent < 0 || now - frame.sent > 150)
                    continue;
                synchronized (this) {
                    if (receiver != socket) return;
                    Frame previous = latest;
                    if (previous != null
                            && (previous.session.equals(frame.session)
                                    ? frame.sequence <= previous.sequence
                                    : now - previous.received <= 150)) continue;
                    latest = frame;
                }
            } catch (IOException error) {
                if (!socket.isClosed()) socket.close();
            } catch (RuntimeException ignored) {
                /* An invalid datagram never supplies input. */
            }
        }
    }

    Frame latest(String uuid, String server, String lobby, String instance) {
        Frame frame = latest;
        return frame != null
                        && frame.fresh(uuid, server, lobby, instance, System.currentTimeMillis())
                ? frame
                : null;
    }

    void publish(JsonObject data, String uuid) throws IOException {
        long now = System.currentTimeMillis();
        if (now - discoveredAt >= 1000 || !uuid.equals(discoveredUuid)) {
            discoveredAt = now;
            discoveredUuid = uuid;
            destinations.clear();
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null)
                for (int i = 0; i < Math.min(64, files.length); i++) {
                    try {
                        if (files[i].length() > 1024) continue;
                        JsonObject entry =
                                new JsonParser()
                                        .parse(
                                                new String(
                                                        Files.readAllBytes(files[i].toPath()),
                                                        StandardCharsets.UTF_8))
                                        .getAsJsonObject();
                        if (!uuid.equals(entry.get("uuid").getAsString())) continue;
                        int port = entry.get("port").getAsInt();
                        String token = entry.get("token").getAsString();
                        if (port > 0 && port < 65536 && token.length() == 72)
                            destinations.add(new Destination(port, token));
                    } catch (IOException | RuntimeException ignored) {
                    }
                }
        }
        if (destinations.isEmpty()) return;
        if (sender == null)
            sender = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        data.addProperty("uuid", uuid);
        data.addProperty("session", session);
        data.addProperty("sequence", ++sequence);
        data.addProperty("sent", now);
        for (Destination destination : destinations) {
            data.addProperty("token", destination.token);
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= 2048)
                sender.send(
                        new DatagramPacket(
                                bytes,
                                bytes.length,
                                InetAddress.getLoopbackAddress(),
                                destination.port));
        }
    }

    synchronized void stopFollowing() {
        if (receiver != null) receiver.close();
        receiver = null;
        latest = null;
        if (descriptor != null) {
            try {
                Files.deleteIfExists(descriptor.toPath());
            } catch (IOException ignored) {
            }
            descriptor = null;
        }
    }

    synchronized void stopPublishing() {
        if (sender != null) sender.close();
        sender = null;
        destinations.clear();
        discoveredAt = 0;
    }

    @Override
    public synchronized void close() {
        stopFollowing();
        stopPublishing();
    }
}