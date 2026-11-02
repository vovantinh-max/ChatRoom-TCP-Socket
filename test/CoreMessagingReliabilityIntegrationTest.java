package test;

import client.ChatClient;
import client.messaging.DeliveryTracker.State;
import server.*;
import server.db.DatabaseManager;
import shared.protocol.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Real TCP tests for persistence acknowledgements, idempotence, authorization and history encoding. */
public final class CoreMessagingReliabilityIntegrationTest {
    private static int checks;
    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++; System.out.println("[PASS] " + description);
    }
    private record Delivery(String id, State state) {}
    private record History(String target, List<ChatClient.ChatMessageItem> items) {}
    private static final class Probe implements ChatClient.ChatEventListener {
        final BlockingQueue<Packet> packets = new LinkedBlockingQueue<>();
        final BlockingQueue<Delivery> delivery = new LinkedBlockingQueue<>();
        final BlockingQueue<History> history = new LinkedBlockingQueue<>();
        final List<ChatClient.ChatMessageItem> messages = new CopyOnWriteArrayList<>();
        final List<Packet> observed = new CopyOnWriteArrayList<>();
        public void onMessageReceived(String sender, String content) {}
        public void onPrivateMessageReceived(String sender, String recipient, String content) {}
        public void onUserListUpdated(List<String> users) {}
        public void onRoomListUpdated(List<String> rooms) {}
        public void onSystemMessage(String message) {}
        public void onDisconnected(String reason) {}
        public void onPacketReceived(Packet packet) { observed.add(packet); packets.add(packet); }
        public void onMessageDeliveryChanged(String id, State state, String detail) { delivery.add(new Delivery(id, state)); }
        public void onHistoryLoaded(String target, List<ChatClient.ChatMessageItem> items) { history.add(new History(target, items)); }
        public void onChatMessageReceived(ChatClient.ChatMessageItem item) { messages.add(item); }
        public void onPrivateChatMessageReceived(ChatClient.ChatMessageItem item) { messages.add(item); }
    }
    private static final class FaultDatabase extends DatabaseManager {
        final AtomicBoolean failOnce = new AtomicBoolean(true);
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public MessageWriteResult persistMessage(String id, String type, String target, String sender,
                String recipient, String content, String reply, long timestamp) {
            if (content.equals("fail-once") && failOnce.compareAndSet(true, false)) return MessageWriteResult.FAILED;
            if (content.equals("wait-for-storage")) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) return MessageWriteResult.FAILED; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return MessageWriteResult.FAILED; }
            }
            return super.persistMessage(id, type, target, sender, recipient, content, reply, timestamp);
        }
    }
    private static <T> T await(BlockingQueue<T> queue, Predicate<T> predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            T item = queue.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (item == null) throw new AssertionError("Expected server event did not arrive");
            if (predicate.test(item)) return item;
        }
    }
    private static void barrier(ChatClient client, Probe probe) throws Exception {
        Packet ping = new Packet(Packet.Type.PING, "", "");
        client.sendPacket(ping);
        await(probe.packets, packet -> packet.getType() == Packet.Type.PONG);
    }
    private static Packet packet(Packet.Type type, String id, String target, String text) {
        Packet packet = new Packet(type, "forged-sender", target, text); packet.setId(id); return packet;
    }
    private static void stored(Probe probe, String id) throws Exception {
        await(probe.delivery, result -> result.id().equals(id) && result.state() == State.STORED);
    }
    private static void denied(ChatClient client, Probe probe, Packet packet) throws Exception {
        client.sendPacket(packet);
        await(probe.packets, result -> result.getType() == Packet.Type.ERROR);
    }
    public static void main(String[] args) throws Exception {
        FaultDatabase db = new FaultDatabase();
        ClientManager manager = new ClientManager();
        ChatServer server = new ChatServer(manager, null, new RoomManager(), db);
        ChatClient alice = new ChatClient(), bob = new ChatClient(), outsider = new ChatClient();
        Probe a = new Probe(), b = new Probe(), o = new Probe();
        alice.setEventListener(a); bob.setEventListener(b); outsider.setEventListener(o);
        try {
            db.registerUser("core_alice", "Test123!", "Alice");
            db.registerUser("core_bob", "Test123!", "Bob");
            db.registerUser("core_outsider", "Test123!", "Outsider");
            check(server.startServer(), "Start isolated TCP server");
            alice.connect("127.0.0.1", TestSupport.PORT, "core_alice", "Test123!");
            bob.connect("127.0.0.1", TestSupport.PORT, "core_bob", "Test123!");
            outsider.connect("127.0.0.1", TestSupport.PORT, "core_outsider", "Test123!");
            barrier(alice, a); barrier(bob, b); barrier(outsider, o);

            String blocked = alice.sendPrivateMessage("core_bob", "wait-for-storage", null);
            check(db.entered.await(3, TimeUnit.SECONDS), "Persistence operation is reached");
            check(alice.getMessageDeliveryState(blocked) == State.SENDING, "Message stays SENDING while persistence is blocked");
            check(db.getMessage(blocked) == null, "No stored record exists before persistence completes");
            check(a.observed.stream().noneMatch(p -> p.getType() == Packet.Type.MESSAGE_ACK && blocked.equals(p.getContent())), "No success ACK before storage completes");
            db.release.countDown(); stored(a, blocked);
            barrier(bob, b);
            check(b.messages.stream().filter(item -> item.uuid().equals(blocked)).count() == 1, "Stored message reaches its recipient exactly once");

            String retry = alice.sendPrivateMessage("core_bob", "fail-once", null);
            await(a.delivery, result -> result.id().equals(retry) && result.state() == State.FAILED);
            check(db.getMessage(retry) == null, "Database failure never creates a fake stored message");
            check(a.observed.stream().noneMatch(p -> p.getType() == Packet.Type.MESSAGE_ACK && retry.equals(p.getContent())), "Database failure does not receive a success ACK");
            barrier(bob, b);
            check(b.messages.stream().noneMatch(item -> item.uuid().equals(retry)), "Failed storage is never forwarded");
            check(alice.retryMessage(retry), "A failed message can be retried"); stored(a, retry); barrier(bob, b);
            check(b.messages.stream().filter(item -> item.uuid().equals(retry)).count() == 1, "Retry forwards exactly one message");
            check(db.getMessage(retry) != null, "Retry persists the original message UUID");
            a.packets.clear();
            alice.sendPacket(packet(Packet.Type.PRIVATE, retry, "core_bob", "fail-once"));
            await(a.packets, p -> p.getType() == Packet.Type.MESSAGE_ACK && retry.equals(p.getContent()));
            barrier(alice, a); barrier(bob, b);
            check(b.messages.stream().filter(item -> item.uuid().equals(retry)).count() == 1, "Lost-ACK resend is idempotent");
            check(!alice.retryMessage(retry), "Stored message cannot be queued for another manual retry");
            bob.sendPacket(packet(Packet.Type.PRIVATE, retry, "core_outsider", "replace another user's ID"));
            await(b.packets, p -> p.getType() == Packet.Type.MESSAGE_FAILED && retry.equals(p.getId()));
            check(db.getMessage(retry).sender().equals("core_alice"), "Another user cannot reuse a stored message ID");

            String special = "Tiếng Việt 😀 :: ;; , ^ | \\\\ đường dẫn\\nDòng hai\\r\\n<xml> & \\\"quote\\\"".replace("\\n", "\n").replace("\\r", "\r");
            String specialId = alice.sendPrivateMessage("core_bob", special, null); stored(a, specialId);
            bob.requestHistory("core_alice", false);
            History loaded = await(b.history, page -> page.target().equals("core_alice"));
            check(loaded.items().stream().anyMatch(item -> item.uuid().equals(specialId) && item.content().equals(special)), "History round-trips separators, UTF-8, line breaks, HTML and backslashes");
            denied(outsider, o, packet(Packet.Type.REACTION, specialId, "core_alice", "👍"));
            check(db.getMessage(specialId).reactions().isEmpty(), "An outsider cannot react to a private message");
            denied(bob, b, packet(Packet.Type.EDIT_MESSAGE, specialId, "core_outsider", "not the owner"));
            check(db.getMessage(specialId).content().equals(special), "Recipient cannot edit the sender's message");
            denied(outsider, o, packet(Packet.Type.REVOKE_MESSAGE, specialId, "core_alice", ""));
            check(!db.getMessage(specialId).isRevoked(), "An outsider cannot revoke a private message");
            int outsiderEvents = o.observed.size();
            alice.sendPacket(packet(Packet.Type.EDIT_MESSAGE, specialId, "core_outsider", "edit with forged recipient :: ;;"));
            await(b.packets, p -> p.getType() == Packet.Type.EDIT_MESSAGE && p.getId().equals(specialId));
            barrier(outsider, o);
            check(o.observed.subList(outsiderEvents, o.observed.size()).stream().noneMatch(p -> p.getType() == Packet.Type.EDIT_MESSAGE), "Edit routes to the stored conversation, never the forged target");

            alice.sendCreateRoom("CoreRoom"); barrier(alice, a);
            bob.sendJoinRoom("coreroom"); barrier(bob, b);
            check(manager.getClient("core_bob").getCurrentRoom().equals("CoreRoom"), "Room names resolve to their canonical spelling");
            String roomId = alice.sendPublicMessage("room :: ;; history", null, "CoreRoom"); stored(a, roomId);
            denied(outsider, o, packet(Packet.Type.HISTORY_REQUEST, UUID.randomUUID().toString(), "#CoreRoom", ""));
            check(o.observed.stream().noneMatch(p -> p.getType() == Packet.Type.HISTORY_RESPONSE && "#CoreRoom".equals(p.getRecipient())), "Non-members never receive room history");
            int roomEvents = a.observed.size();
            denied(outsider, o, packet(Packet.Type.TYPING, UUID.randomUUID().toString(), "#CoreRoom", "START"));
            denied(outsider, o, packet(Packet.Type.FILE_DATA, UUID.randomUUID().toString(), "#CoreRoom", "forged data"));
            denied(outsider, o, packet(Packet.Type.REACTION, roomId, "#CoreRoom", "👍"));
            barrier(alice, a);
            check(a.observed.subList(roomEvents, a.observed.size()).stream().noneMatch(p -> p.getType() == Packet.Type.FILE_DATA || p.getType() == Packet.Type.TYPING), "Outsiders cannot inject typing or file data into a room");
            bob.requestHistory("#CoreRoom", true);
            check(await(b.history, page -> page.target().equals("#CoreRoom")).items().stream().anyMatch(item -> item.uuid().equals(roomId)), "Members can read their room's history");
            String wrongRoom = alice.sendPublicMessage("must stay in original room", null, "General");
            await(a.delivery, result -> result.id().equals(wrongRoom) && result.state() == State.FAILED);
            check(db.getMessage(wrongRoom) == null, "A room retry cannot silently move a message into the current room");
            outsider.sendJoinRoom("CoreRoom"); barrier(outsider, o);
            int publicObservers = o.observed.size();
            alice.sendPacket(packet(Packet.Type.EDIT_MESSAGE, specialId, "#CoreRoom", "private content must not become a room event"));
            await(b.packets, p -> p.getType() == Packet.Type.EDIT_MESSAGE && specialId.equals(p.getId()));
            barrier(alice, a); barrier(outsider, o);
            check(o.observed.subList(publicObservers, o.observed.size()).stream().noneMatch(p -> p.getType() == Packet.Type.EDIT_MESSAGE && specialId.equals(p.getId())),
                    "Even a valid room member cannot receive a private edit routed to a forged room");
            outsider.sendPacket(packet(Packet.Type.REACTION, roomId, "core_alice", "👍"));
            await(a.packets, p -> p.getType() == Packet.Type.REACTION && roomId.equals(p.getId()));
            check(db.getMessage(roomId).reactions().contains("core_outsider"), "Authorized room members can react through the stored room target");
            outsider.sendPacket(new Packet(Packet.Type.LEAVE_ROOM, "", "")); barrier(outsider, o);
            denied(outsider, o, packet(Packet.Type.HISTORY_REQUEST, UUID.randomUUID().toString(), "#CoreRoom", ""));
            check(o.observed.stream().noneMatch(p -> p.getType() == Packet.Type.HISTORY_RESPONSE && "#CoreRoom".equals(p.getRecipient())),
                    "Leaving a room removes permission to read its history until rejoining");
            String badReply = outsider.sendPrivateMessage("core_bob", "secret reply reference", specialId);
            await(o.delivery, result -> result.id().equals(badReply) && result.state() == State.FAILED);
            check(db.getMessage(badReply) == null, "Reply references cannot point into another private conversation");
            String oversized = alice.sendPrivateMessage("core_bob", "x".repeat(ProtocolLimits.MAX_MESSAGE_CHARS + 1), null);
            await(a.delivery, result -> result.id().equals(oversized) && result.state() == State.FAILED);
            check(db.getMessage(oversized) == null, "Oversized message is rejected before storage");
            check(Packet.fromRawString("BOGUS^id^1^sender^recipient^^content") == null, "Unknown modern commands are rejected, not treated as chat");
            for (int i = 0; i < 55; i++) db.saveMessage("page-" + i, "PUBLIC", "PaginationRoom", "core_alice", null, "page " + i, null, 1000 + i);
            List<DatabaseManager.MessageRecord> newest = db.getMessageHistory("PUBLIC", "PaginationRoom", 50, 0);
            check(newest.size() == 50 && newest.getFirst().uuid().equals("page-5") && newest.getLast().uuid().equals("page-54"), "History opens the latest 50 messages in chronological order");
            check(db.getMessageHistory("PUBLIC", "PaginationRoom", 50, 50).getFirst().uuid().equals("page-0"), "An older page can be retrieved without overlap");
            try (BufferedReader reader = BoundedLineReader.create(new ByteArrayInputStream(("x".repeat(ProtocolLimits.MAX_FRAME_CHARS + 1) + "\n").getBytes(StandardCharsets.UTF_8)))) {
                boolean rejected = false;
                try { reader.readLine(); } catch (IOException expected) { rejected = true; }
                check(rejected, "Frame reader rejects an oversized line before unbounded allocation");
            }
            alice.disconnect(); bob.disconnect(); outsider.disconnect(); server.stopServer(); db.close();
            check(db.persistMessage("closed-db", "PRIVATE", "a:b", "a", "b", "not saved", null, 1) == DatabaseManager.MessageWriteResult.FAILED,
                    "A closed database reports failure rather than silently acknowledging memory storage");
            check(!db.isUsingMemoryFallback(), "Closing SQLite never silently switches to an empty memory database");
            System.out.println("Core messaging: " + checks + " checks passed.");
        } finally {
            db.release.countDown(); alice.disconnect(); bob.disconnect(); outsider.disconnect(); server.stopServer(); db.close();
        }
    }
}
