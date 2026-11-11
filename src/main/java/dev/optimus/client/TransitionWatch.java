package dev.optimus.client;

import dev.optimus.core.safety.LobbySafety;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S40PacketDisconnect;

/** Observes vanilla traffic without cancelling or modifying any packet. */
final class TransitionWatch extends ChannelDuplexHandler {
    private static final String NAME = "optimus_transition_watch";
    private final LobbySafety safety;
    private final NetworkManager manager;
    private volatile boolean ready;
    private final AtomicLong serial = new AtomicLong(),
            hardSerial = new AtomicLong(),
            fatalSerial = new AtomicLong();
    private final AtomicReference<String> authorizedCommand = new AtomicReference<>();

    long serial() {
        return serial.get();
    }

    long hardSerial() {
        return hardSerial.get();
    }

    long fatalSerial() {
        return fatalSerial.get();
    }

    boolean authorizeLobbyCommand(String command) {
        return ("/lobby duels".equals(command) || "/swaplobby 1".equals(command))
                && authorizedCommand.compareAndSet(null, command);
    }

    private void transition(boolean hard, boolean fatal) {
        safety.invalidate();
        if (hard) hardSerial.incrementAndGet();
        if (fatal) fatalSerial.incrementAndGet();
        serial.incrementAndGet();
    }

    TransitionWatch(LobbySafety safety, NetworkManager manager) {
        this.safety = safety;
        this.manager = manager;
    }

    static TransitionWatch install(LobbySafety safety, NetworkManager manager) {
        TransitionWatch watch = new TransitionWatch(safety, manager);
        manager.channel().pipeline().addBefore("packet_handler", NAME, watch);
        return watch;
    }

    boolean ready(NetworkManager current) {
        return ready && current != null && manager == current && manager.channel().isActive();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ready = true;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        ready = false;
        transition(true, true);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object packet) throws Exception {
        // Position/look packets also carry ordinary server corrections. They
        // do not identify a lobby transition and must not interrupt combat.
        if (packet instanceof S01PacketJoinGame
                || packet instanceof S07PacketRespawn
                || packet instanceof S40PacketDisconnect) {
            transition(
                    packet instanceof S01PacketJoinGame || packet instanceof S40PacketDisconnect,
                    packet instanceof S40PacketDisconnect);
        }
        super.channelRead(ctx, packet);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise)
            throws Exception {
        if (packet instanceof C01PacketChatMessage) {
            String message = ((C01PacketChatMessage) packet).getMessage().trim();
            if (message.startsWith("/") && !message.equalsIgnoreCase("/locraw")) {
                String permitted = authorizedCommand.get();
                boolean owned =
                        message.equals(permitted)
                                && authorizedCommand.compareAndSet(permitted, null);
                transition(false, !owned);
            }
        }
        super.write(ctx, packet, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ready = false;
        transition(true, true);
        super.channelInactive(ctx);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        ready = false;
        transition(true, true); // do not wait for the asynchronous inactive event
        super.close(ctx, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ready = false;
        transition(true, true);
        super.exceptionCaught(ctx, cause);
    }
}