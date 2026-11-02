package test;

import client.ChatClient;
import client.ChatClient.FriendItem;
import client.ChatClient.SearchUserItem;
import server.*;
import server.db.DatabaseManager;
import shared.protocol.Packet;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Verifies actual authorization, persistence and server-confirmed contact states. */
public final class FriendshipWorkflowIntegrationTest {
    private static int checks;
    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
        System.out.println("[PASS] " + description);
    }
    private record Result(String action, String target, boolean success) {}
    private record Search(String id, List<SearchUserItem> users) {}
    private static final class Probe implements ChatClient.ChatEventListener {
        final BlockingQueue<Result> actions = new LinkedBlockingQueue<>();
        final BlockingQueue<Search> searches = new LinkedBlockingQueue<>();
        final BlockingQueue<List<String>> pending = new LinkedBlockingQueue<>();
        final BlockingQueue<List<FriendItem>> friends = new LinkedBlockingQueue<>();
        final BlockingQueue<String> privateMessages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> edits = new LinkedBlockingQueue<>();
        final BlockingQueue<Packet> packets = new LinkedBlockingQueue<>();
        final BlockingQueue<String> systemMessages = new LinkedBlockingQueue<>();
        public void onMessageReceived(String sender, String content) {}
        public void onPrivateMessageReceived(String sender, String receiver, String content) { privateMessages.add(content); }
        public void onUserListUpdated(List<String> users) {}
        public void onRoomListUpdated(List<String> rooms) {}
        public void onSystemMessage(String message) { systemMessages.add(message); }
        public void onPacketReceived(Packet packet) { packets.add(packet); }
        public void onMessageEdited(String id, String content) { edits.add(id + ":" + content); }
        public void onDisconnected(String reason) {}
        public void onFriendActionResult(String action, String target, boolean success, String message) { actions.add(new Result(action, target, success)); }
        public void onUserSearchResult(String id, List<SearchUserItem> users) { searches.add(new Search(id, List.copyOf(users))); }
        public void onPendingRequestsUpdated(List<String> senders) { pending.add(List.copyOf(senders)); }
        public void onFriendListUpdated(List<FriendItem> users) { friends.add(List.copyOf(users)); }
    }
    private static <T> T await(BlockingQueue<T> queue, Predicate<T> match) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            T item = queue.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (item == null) throw new AssertionError("Timed out waiting for server event");
            if (match.test(item)) return item;
        }
    }
    private static void action(Probe probe, String action, boolean success) throws Exception {
        Result result = await(probe.actions, event -> event.action().equals(action));
        check(result.success() == success, action + " returns confirmed " + (success ? "success" : "failure"));
    }
    private static SearchUserItem search(ChatClient client, Probe probe, String target) throws Exception {
        String id = client.searchUsers(target);
        Search response = await(probe.searches, result -> result.id().equals(id));
        check(response.users().size() == 1, "Search returns exactly the target account");
        return response.users().getFirst();
    }
    public static void main(String[] args) throws Exception {
        DatabaseManager db = new DatabaseManager();
        ChatServer server = new ChatServer(new ClientManager(), null, new RoomManager(), db);
        ChatClient alice = new ChatClient();
        ChatClient bob = new ChatClient();
        Probe a = new Probe();
        Probe b = new Probe();
        alice.setEventListener(a);
        bob.setEventListener(b);
        try {
            check(db.registerUser("wf_alice", "Test123!", "An Nguyễn"), "Register Alice fixture");
            check(db.registerUser("wf_bob", "Test123!", "Bảo, QA; Team"), "Register Bob with delimiter characters in display name");
            check(!db.acceptFriendRequest("wf_bob", "wf_alice"), "Cannot accept a nonexistent invitation");
            check(!db.isFriend("wf_alice", "wf_bob"), "Forged acceptance does not create a friendship");
            check(!db.sendFriendRequest("wf_alice", "wf_alice"), "Cannot invite yourself");
            check(!db.sendFriendRequest("wf_alice", "missing_user"), "Cannot invite an unknown user");
            check(server.startServer(), "Start isolated workflow server");
            alice.connect("127.0.0.1", TestSupport.PORT, "wf_alice", "Test123!");
            bob.connect("127.0.0.1", TestSupport.PORT, "wf_bob", "Test123!");
            await(a.pending, List::isEmpty);
            await(b.pending, List::isEmpty);
            SearchUserItem stranger = search(alice, a, "wf_bob");
            check(stranger.displayName().equals("Bảo, QA; Team"), "Search preserves Vietnamese names, commas and semicolons");
            check(stranger.relationship().equals("STRANGER"), "Initial relationship is stranger");
            alice.sendFriendRequest("wf_bob");
            action(a, "REQUEST", true);
            check(await(b.pending, list -> list.contains("wf_alice")).size() == 1, "Receiver gets exactly one live pending invitation");
            alice.sendFriendRequest("WF_BOB");
            action(a, "REQUEST", false);
            bob.sendFriendRequest("wf_alice");
            action(b, "REQUEST", false);
            check(db.getPendingRequests("wf_bob").size() == 1, "Duplicate and reciprocal invitations do not add entries");
            check(search(alice, a, "wf_bob").relationship().equals("OUTGOING"), "Sender sees outgoing invitation");
            check(search(bob, b, "wf_alice").relationship().equals("INCOMING"), "Receiver sees incoming invitation");
            alice.acceptFriendRequest("wf_bob");
            action(a, "ACCEPT", false);
            check(!db.isFriend("wf_alice", "wf_bob"), "Sender cannot accept their own invitation");
            bob.acceptFriendRequest("wf_alice");
            action(b, "ACCEPT", true);
            check(await(b.pending, List::isEmpty).isEmpty(), "Acceptance clears receiver pending list");
            List<FriendItem> aliceFriends = await(a.friends, list -> !list.isEmpty());
            check(aliceFriends.getFirst().displayName().equals("Bảo, QA; Team"), "Friend list preserves display-name delimiters");
            check(aliceFriends.getFirst().isOnline(), "Friend presence is online");
            check(await(b.friends, list -> !list.isEmpty()).getFirst().username().equals("wf_alice"), "Both clients receive updated friends");
            check(search(alice, a, "wf_bob").relationship().equals("FRIEND"), "Search reflects accepted friendship");
            Probe lateListener = new Probe();
            bob.setEventListener(lateListener);
            check(await(lateListener.friends, list -> !list.isEmpty()).getFirst().username().equals("wf_alice"),
                    "A newly opened chat window receives the existing friend snapshot");
            check(await(lateListener.pending, List::isEmpty).isEmpty(), "A newly opened window receives the pending snapshot");
            bob.setEventListener(b);
            bob.acceptFriendRequest("wf_alice");
            action(b, "ACCEPT", false);
            check(db.getFriends("wf_alice").size() == 1, "Repeated acceptance cannot duplicate friendship");
            String messageId = alice.sendPrivateMessage("wf_bob", "Xin chào sau khi kết bạn!", null);
            check(await(b.privateMessages, message -> message.equals("Xin chào sau khi kết bạn!")) != null, "Friends can exchange a real private message");
            check(messageId != null && db.getMessageHistory("PRIVATE", DatabaseManager.getPrivateConversationTarget("wf_alice", "wf_bob"), 50, 0)
                    .stream().anyMatch(message -> message.uuid().equals(messageId)), "Outgoing private bubble uses the persisted message ID");
            alice.sendPacket(new Packet(Packet.Type.PING, "wf_alice", ""));
            await(a.packets, packet -> packet.getType() == Packet.Type.PONG);
            check(a.systemMessages.stream().noneMatch(messageId::equals), "Delivery ACK identifiers never become system messages");
            alice.sendEditMessage(messageId, "Đã sửa sau khi kết bạn", "wf_bob");
            check(await(b.edits, edit -> edit.equals(messageId + ":Đã sửa sau khi kết bạn")) != null, "A sent private message can be edited by its real ID");
            bob.disconnect();
            List<FriendItem> offline = await(a.friends, list -> !list.isEmpty() && !list.getFirst().isOnline());
            check(offline.getFirst().username().equals("wf_bob"), "Offline friends remain in the contact list");
            alice.removeFriend("wf_bob");
            action(a, "REMOVE", true);
            check(await(a.friends, List::isEmpty).isEmpty(), "Removal updates the contact list");
            alice.sendFriendRequest("wf_bob");
            action(a, "REQUEST", true);
            check(db.getPendingRequests("wf_bob").contains("wf_alice"), "Invitation persists for an offline recipient");
            bob.connect("127.0.0.1", TestSupport.PORT, "wf_bob", "Test123!");
            check(await(b.pending, list -> list.contains("wf_alice")).size() == 1, "Invitation is delivered after reconnect");
            bob.rejectFriendRequest("wf_alice");
            action(b, "REJECT", true);
            await(b.pending, List::isEmpty);
            check(search(alice, a, "wf_bob").relationship().equals("STRANGER"), "Rejection restores stranger status");
            bob.rejectFriendRequest("wf_alice");
            action(b, "REJECT", false);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> first = executor.submit(() -> { start.await(); return db.sendFriendRequest("wf_alice", "wf_bob"); });
                Future<Boolean> second = executor.submit(() -> { start.await(); return db.sendFriendRequest("wf_bob", "wf_alice"); });
                start.countDown();
                check(first.get() ^ second.get(), "Simultaneous reciprocal sends create exactly one invitation");
            }
            System.out.println("Friendship workflow: " + checks + " checks passed (" + (db.isUsingMemoryFallback() ? "memory" : "SQLite") + ").");
        } finally {
            alice.disconnect();
            bob.disconnect();
            server.stopServer();
            db.close();
        }
    }
}
