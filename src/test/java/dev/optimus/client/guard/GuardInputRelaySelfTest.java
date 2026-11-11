package dev.optimus.client.guard;

import com.google.gson.*;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardAssist;
import dev.optimus.core.guard.GuardCombatWindow;
import dev.optimus.core.guard.GuardFollow;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.NearbySteering;
import java.io.File;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Real loopback transport plus tracking and cooldown invariants; no game connection. */
public final class GuardInputRelaySelfTest {
    private static int checks;

    private static void expect(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    private static JsonObject snapshot() {
        JsonObject data = new JsonObject();
        data.addProperty("server", "mc.hypixel.net");
        data.addProperty("lobby", "duelslobby9");
        data.addProperty("instance", "dynamiclobbyTEST");
        data.addProperty("x", 11.432);
        data.addProperty("y", 63);
        data.addProperty("z", -115.715);
        data.addProperty("mx", .1);
        data.addProperty("mz", 0);
        data.addProperty("yaw", 90);
        data.addProperty("pitch", 0);
        data.addProperty("input", "FORWARD_LEFT");
        data.addProperty("jump", true);
        data.addProperty("sneak", false);
        data.addProperty("sprint", true);
        data.addProperty("sword", true);
        data.addProperty("active", true);
        data.addProperty("swing", 7);
        data.addProperty("victim", UUID.randomUUID().toString());
        return data;
    }

    private static GuardInputRelay.Frame current(GuardInputRelay relay, String uuid) {
        return relay.latest(uuid, "mc.hypixel.net", "duelslobby9", "dynamiclobbyTEST");
    }

    private static void send(DatagramSocket socket, JsonObject data, int port) throws Exception {
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        socket.send(
                new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }

    public static void main(String[] arguments) throws Exception {
        GuardAssist queue = new GuardAssist();
        queue.observe("first", "victim", 10);
        expect(queue.pending(10) && queue.victim().equals("victim"), "fresh swing is queued");
        queue.observe("first", "victim", 20);
        expect(!queue.pending(23), "repeated snapshots cannot extend an expired swing");
        queue.observe("second", "other", 24);
        expect(
                queue.pending(24) && queue.victim().equals("other"),
                "new swing replaces old intent");
        queue.cancel();
        queue.observe("second", "other", 25);
        expect(!queue.pending(25), "completed swing cannot replay");
        expect(
                !GuardAssist.hitWindow(10, 0) && GuardAssist.hitWindow(0, 0),
                "zero-ping hit window waits for observed cooldown");
        expect(
                GuardAssist.hitWindow(2, 100) && !GuardAssist.hitWindow(3, 100),
                "measured round trip adjusts expected arrival by two ticks");
        expect(
                GuardAssist.hitWindow(5, 250) && !GuardAssist.hitWindow(6, 250),
                "250-ms round trip is not truncated to three ticks");
        expect(
                !GuardAssist.hitWindow(9, 1000) && GuardAssist.hitWindow(8, 1000),
                "latency compensation is bounded");
        GuardAssist followup = new GuardAssist();
        followup.observe("one_click", "victim", 0);
        followup.attackSent(0);
        expect(
                !followup.pending(1) && followup.live(6),
                "a sent request waits for damage evidence instead of immediately repeating");
        followup.damageObserved(6);
        expect(followup.pending(11), "observed damage authorizes one bounded follow-up");
        followup.attackSent(11);
        followup.damageObserved(13);
        expect(
                !followup.pending(13) && !followup.live(13),
                "a second request consumes the follow-up and later damage cannot restart it");
        GuardCombatWindow window = new GuardCombatWindow();
        expect(
                !window.observe("victim", 0, 20, 0),
                "first victim sample is not invented hit evidence");
        window.attackSent(0, 250);
        expect(
                !window.ready(4, 0, 250),
                "a new click cannot duplicate an unacknowledged request before the round trip");
        expect(
                !window.ready(6, 0, 250),
                "END-phase logical ticks cannot release an unacknowledged retry before the network update");
        expect(
                window.observe("victim", 10, 14, 6) && !window.ready(6, 10, 250),
                "damage evidence retains the actual immunity wait");
        expect(
                window.ready(11, 5, 250),
                "250-ms arrival estimate admits the next modeled server window");
        GuardAssist retry = new GuardAssist();
        retry.observe("click", "victim", 0);
        retry.attackSent(0, 250);
        expect(
                !retry.pending(6) && retry.pending(7),
                "unacknowledged request permits one retry after round-trip feedback margin");
        retry.attackSent(7, 250);
        expect(!retry.pending(14), "timeout retry is consumed once without creating a backlog");
        GuardAssist retargeted = new GuardAssist();
        retargeted.observe("physical", "", 20);
        retargeted.retarget("actual");
        expect(
                retargeted.pending(25)
                        && retargeted.victim().equals("actual")
                        && !retargeted.pending(33),
                "corrected victim retains the original intent expiry");
        expect(
                !window.observe("victim", 4, 14, 12),
                "ordinary hurt countdown is not another damage event");
        Vec3Point zero = new Vec3Point(0, 0, 0), leader = new Vec3Point(11.432, 63, -115.715);
        for (int yaw = -180; yaw < 180; yaw += 5) {
            Vec3Point anchor = GuardFollow.anchor(leader, yaw);
            expect(
                    Math.abs(Math.sqrt(anchor.horizontalDistanceSq(leader)) - GuardFollow.OFFSET)
                            < 1e-9,
                    "constant close offset at every facing");
            expect(
                    GuardFollow.choose(
                                    anchor, zero, leader, zero, yaw, yaw, .1, null, input -> true)
                            == NearbySteering.Input.NONE,
                    "still leader has no follow jitter");
        }
        Vec3Point anchor = GuardFollow.anchor(leader, 90);
        expect(
                Math.abs(anchor.x - (11.432 + GuardFollow.OFFSET)) < 1e-9
                        && Math.abs(anchor.z + 115.715) < 1e-9,
                "closer rear offset follows the reported direction");
        expect(
                GuardFollow.choose(
                                        new Vec3Point(0, 1, .4),
                                        zero,
                                        new Vec3Point(0, 1, 0),
                                        zero,
                                        0,
                                        0,
                                        .1,
                                        null,
                                        input -> true)
                                .forward
                        < 0,
                "bot in front retreats behind view");
        expect(
                GuardFollow.choose(
                                zero,
                                zero,
                                new Vec3Point(0, 0, 1),
                                zero,
                                0,
                                0,
                                .1,
                                null,
                                input -> input == NearbySteering.Input.NONE)
                        == NearbySteering.Input.NONE,
                "unsafe directions never selected");
        // Follow a moving/stopping/reversing trace with retained native-style ground momentum.
        Vec3Point self = new Vec3Point(0, 1, -GuardFollow.OFFSET),
                person = new Vec3Point(0, 1, 0),
                motion = zero;
        double maxError = 0;
        for (int tick = 0; tick < 200; tick++) {
            double dz = tick < 60 ? .20 : tick < 100 ? 0 : tick < 140 ? -.20 : 0;
            Vec3Point velocity = new Vec3Point(0, 0, dz);
            NearbySteering.Input input =
                    GuardFollow.choose(
                            self, motion, person, velocity, 0, 0, .1, null, candidate -> true);
            Vec3Point delta =
                    GroundMotion.delta(motion.x, motion.z, 0, input.forward, input.side, .1, 1);
            self = new Vec3Point(self.x + delta.x, 1, self.z + delta.z);
            person = new Vec3Point(person.x, 1, person.z + dz);
            motion = new Vec3Point(delta.x * .546, 0, delta.z * .546);
            maxError =
                    Math.max(
                            maxError,
                            Math.sqrt(self.horizontalDistanceSq(GuardFollow.anchor(person, 0))));
        }
        expect(
                maxError < .7 && self.horizontalDistanceSq(GuardFollow.anchor(person, 0)) < .02,
                "follow corrects starts, stops, reversal and inertia without lingering drift");
        String uuid = UUID.randomUUID().toString();
        File directory = Files.createTempDirectory("guard-relay").toFile();
        GuardInputRelay receiver = new GuardInputRelay(directory),
                publisher = new GuardInputRelay(directory);
        try (DatagramSocket socket =
                new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            receiver.follow(uuid);
            JsonObject data = snapshot();
            publisher.publish(data, uuid);
            long deadline = System.nanoTime() + 1000000000L;
            GuardInputRelay.Frame frame;
            while ((frame = current(receiver, uuid)) == null && System.nanoTime() < deadline)
                Thread.sleep(2);
            expect(
                    frame != null
                            && frame.input == NearbySteering.Input.FORWARD_LEFT
                            && frame.jump
                            && frame.sprint
                            && frame.sword
                            && frame.swing == 7,
                    "native UDP transports input, equipment and swing in one snapshot");
            expect(
                    !frame.fresh(
                            uuid,
                            "mc.hypixel.net",
                            "duelslobby9",
                            "dynamiclobbyOTHER",
                            frame.received),
                    "instance mismatch cannot supply keys");
            expect(
                    !frame.fresh(
                            uuid,
                            "mc.hypixel.net",
                            "duelslobby9",
                            "dynamiclobbyTEST",
                            frame.received + 151),
                    "stale keys expire after 150 milliseconds");
            JsonObject descriptor =
                    new JsonParser()
                            .parse(
                                    new String(
                                            Files.readAllBytes(directory.listFiles()[0].toPath()),
                                            StandardCharsets.UTF_8))
                            .getAsJsonObject();
            int port = descriptor.get("port").getAsInt();
            String token = descriptor.get("token").getAsString();
            data.addProperty("token", "wrong");
            data.addProperty("sequence", 100);
            data.addProperty("sent", System.currentTimeMillis());
            send(socket, data, port);
            Thread.sleep(10);
            expect(
                    current(receiver, uuid).sequence == frame.sequence,
                    "wrong token cannot replace active keys");
            data.addProperty("token", token);
            data.addProperty("uuid", UUID.randomUUID().toString());
            send(socket, data, port);
            Thread.sleep(10);
            expect(
                    current(receiver, uuid).sequence == frame.sequence,
                    "wrong player UUID cannot supply keys");
            data.addProperty("uuid", uuid);
            data.addProperty("sequence", 0);
            send(socket, data, port);
            Thread.sleep(10);
            expect(
                    current(receiver, uuid).sequence == frame.sequence,
                    "reordered snapshots cannot revive old movement");
            data.addProperty("sequence", 100);
            data.addProperty("sent", System.currentTimeMillis() - 500);
            send(socket, data, port);
            Thread.sleep(10);
            expect(
                    current(receiver, uuid).sequence == frame.sequence,
                    "delayed datagrams are discarded");
            data.addProperty("sent", System.currentTimeMillis());
            data.addProperty("active", false);
            send(socket, data, port);
            deadline = System.nanoTime() + 1000000000L;
            while (current(receiver, uuid) != null && System.nanoTime() < deadline) Thread.sleep(2);
            expect(
                    current(receiver, uuid) == null,
                    "focus/menu inactive snapshot releases local input feed immediately");
        } finally {
            receiver.close();
            publisher.close();
        }
        expect(directory.listFiles().length == 0, "relay descriptors removed on stop");
        expect(
                dev.optimus.core.control.RemoteCommand.parse("!o guard rocket770", "GuardBot")[0]
                        .equals("guard"),
                "paired owner can choose guard target");
        boolean localOnly = false;
        try {
            dev.optimus.core.control.RemoteCommand.parse("!o guard share on", "GuardBot");
        } catch (IllegalArgumentException expected) {
            localOnly = true;
        }
        expect(localOnly, "keyboard publication requires a local opt-in on the main client");
        expect(
                dev.optimus.core.control.RemoteCommand.parse(
                        "!o guard rocket770", "GuardBot", true)[0]
                        .equals("guard"),
                "operators can select the guarded player");
        boolean limited = false;
        try {
            dev.optimus.core.control.RemoteCommand.parse("!o guard keys on", "GuardBot", true);
        } catch (IllegalArgumentException expected) {
            limited = true;
        }
        expect(limited, "operators cannot change keyboard input mode");
        System.out.println(
                "PASS: "
                        + checks
                        + " guard tracking, cooldown, relay authentication and freshness checks");
    }
}