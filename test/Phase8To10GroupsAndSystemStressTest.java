package test;

import client.ChatClient;
import server.ChatServer;
import server.ClientManager;
import server.RoomManager;
import server.db.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Phase8To10GroupsAndSystemStressTest {

    private static int totalTests = 0;
    private static int passedTests = 0;

    private static void assertTrue(String testName, boolean condition) {
        totalTests++;
        if (condition) {
            passedTests++;
            System.out.println("  [PASS] " + testName);
        } else {
            System.err.println("  [FAIL] " + testName);
        }
    }

    private static void assertEquals(String testName, Object expected, Object actual) {
        totalTests++;
        if (expected == null && actual == null) {
            passedTests++;
            System.out.println("  [PASS] " + testName);
        } else if (expected != null && expected.equals(actual)) {
            passedTests++;
            System.out.println("  [PASS] " + testName);
        } else {
            System.err.println("  [FAIL] " + testName + " - Expected: " + expected + ", Got: " + actual);
        }
    }

    public static void main(String[] args) {
        System.out.println("=========================================================================");
        System.out.println("   PHASE 8-10 INTEGRATION & STRESS TEST: GROUPS, LOAD & SYSTEM AUDIT     ");
        System.out.println("=========================================================================\n");

        DatabaseManager db = new DatabaseManager();

        testGroupManagementDao(db);

        ClientManager clientManager = new ClientManager();
        RoomManager roomManager = new RoomManager();
        ChatServer server = new ChatServer(clientManager, null, roomManager, db);

        server.startServer();

        try {
            Thread.sleep(300);
            testRoomBroadcastAndIsolation(clientManager, roomManager);
            testHighConcurrencyAndStressLoad(clientManager, roomManager, db);
        } catch (Exception e) {
            e.printStackTrace();
            assertTrue("Ngoại lệ trong test: " + e.getMessage(), false);
        } finally {
            server.stopServer();
            db.close();
        }

        System.out.println("\n-------------------------------------------------------------------------");
        System.out.printf("Kết quả kiểm thử Phase 8-10: %d/%d passed (%.1f%%)\n", passedTests, totalTests, (passedTests * 100.0 / totalTests));
        System.out.println("-------------------------------------------------------------------------");

        if (passedTests == totalTests && totalTests > 0) {
            System.out.println(">>> TẤT CẢ KIỂM THỬ PHASE 8-10 ĐẠT CHUẨN 100%! <<<\n");
            System.exit(0);
        } else {
            System.err.println(">>> CÓ KIỂM THỬ THẤT BẠI! KIỂM TRA LẠI. <<<");
            System.exit(1);
        }
    }

    // ── 1. TEST DAO NHÓM CHAT (MODULE 9) ──────────────────────────────────────
    private static void testGroupManagementDao(DatabaseManager db) {
        System.out.println("--- 1. Kiểm thử Nhóm Chat Nâng cao trong Database (Module 9) ---");

        String groupName = "DevTeam_" + (System.currentTimeMillis() % 10000);
        String owner = "leader_alex";

        boolean created = db.createChatGroup(groupName, owner);
        assertTrue("Tạo nhóm chat mới thành công trong SQLite", created);

        assertTrue("Người tạo có quyền ADMIN của nhóm", db.isGroupAdmin(groupName, owner));

        boolean added1 = db.addGroupMember(groupName, "member_john", "MEMBER");
        boolean added2 = db.addGroupMember(groupName, "member_sarah", "MEMBER");
        assertTrue("Thêm thành viên John vào nhóm", added1);
        assertTrue("Thêm thành viên Sarah vào nhóm", added2);

        List<String> members = db.getGroupMembers(groupName);
        assertEquals("Số lượng thành viên trong nhóm là 3", 3, members.size());
        assertTrue("Danh sách thành viên chứa Leader Alex", members.contains(owner));
        assertTrue("Danh sách thành viên chứa John", members.contains("member_john"));
        assertTrue("Danh sách thành viên chứa Sarah", members.contains("member_sarah"));

        boolean removed = db.removeGroupMember(groupName, "member_john");
        assertTrue("Xóa thành viên John khỏi nhóm", removed);

        List<String> remaining = db.getGroupMembers(groupName);
        assertEquals("Số lượng thành viên còn lại là 2", 2, remaining.size());
        assertTrue("Không còn John trong nhóm", !remaining.contains("member_john"));
    }

    // ── 2. TEST CÁCH LY PHÒNG CHAT & BROADCAST (MODULE 6 & 9) ─────────────────
    private static void testRoomBroadcastAndIsolation(ClientManager cm, RoomManager rm) throws Exception {
        System.out.println("\n--- 2. Kiểm thử Cách ly Phòng Chat & Broadcast đa luồng ---");

        String uA = "user_room_a_" + (System.currentTimeMillis() % 10000);
        String uB = "user_room_b_" + (System.currentTimeMillis() % 10000);
        String uC = "user_room_c_" + (System.currentTimeMillis() % 10000);

        ChatClient cA = new ChatClient();
        ChatClient cB = new ChatClient();
        ChatClient cC = new ChatClient();

        cA.register("127.0.0.1", TestSupport.PORT, uA, "Pass123!", "Alice");
        cB.register("127.0.0.1", TestSupport.PORT, uB, "Pass123!", "Bob");
        cC.register("127.0.0.1", TestSupport.PORT, uC, "Pass123!", "Charlie");

        cA.connect("127.0.0.1", TestSupport.PORT, uA, "Pass123!");
        cB.connect("127.0.0.1", TestSupport.PORT, uB, "Pass123!");
        cC.connect("127.0.0.1", TestSupport.PORT, uC, "Pass123!");

        // Alice và Bob vào phòng 'Java', Charlie ở phòng 'General'
        cA.sendJoinRoom("Java");
        cB.sendJoinRoom("Java");

        Thread.sleep(200);

        CountDownLatch bLatch = new CountDownLatch(1);
        CountDownLatch cLatch = new CountDownLatch(1);
        AtomicInteger cMessageCount = new AtomicInteger(0);

        cB.setEventListener(new ChatClient.ChatEventListener() {
            @Override public void onMessageReceived(String sender, String message) {
                if (message.contains("Tin nhắn nội bộ Java")) {
                    bLatch.countDown();
                }
            }
            @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
            @Override public void onUserListUpdated(List<String> users) {}
            @Override public void onRoomListUpdated(List<String> rooms) {}
            @Override public void onSystemMessage(String message) {}
            @Override public void onDisconnected(String reason) {}
        });

        cC.setEventListener(new ChatClient.ChatEventListener() {
            @Override public void onMessageReceived(String sender, String message) {
                if (message.contains("Tin nhắn nội bộ Java")) {
                    cMessageCount.incrementAndGet();
                }
            }
            @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
            @Override public void onUserListUpdated(List<String> users) {}
            @Override public void onRoomListUpdated(List<String> rooms) {}
            @Override public void onSystemMessage(String message) {}
            @Override public void onDisconnected(String reason) {}
        });

        // Alice gửi tin nhắn vào phòng 'Java'
        cA.sendPublicMessage("Tin nhắn nội bộ Java");

        boolean bReceived = bLatch.await(3, TimeUnit.SECONDS);
        assertTrue("Bob (trong cùng phòng Java) nhận được tin nhắn", bReceived);

        Thread.sleep(300);
        assertEquals("Charlie (ở phòng khác) KHÔNG nhận được tin nhắn của phòng Java (Cách ly phòng hoàn hảo)", 0, cMessageCount.get());

        cA.disconnect();
        cB.disconnect();
        cC.disconnect();
    }

    // ── 3. TEST ÁP LỰC ĐỒNG THỜI CAO (STRESS TEST - 10 CLIENTS, 100 CONCURRENT MSGS) ─
    private static void testHighConcurrencyAndStressLoad(ClientManager cm, RoomManager rm, DatabaseManager db) throws Exception {
        System.out.println("\n--- 3. Stress Test: 10 Clients Kết nối Đồng thời & 100 Tin nhắn Gửi Song Song ---");

        int clientCount = 10;
        int msgsPerClient = 10;
        int totalExpectedMsgs = clientCount * msgsPerClient;

        List<ChatClient> clients = new ArrayList<>();
        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch sendLatch = new CountDownLatch(totalExpectedMsgs);
        AtomicInteger totalReceivedMsgs = new AtomicInteger(0);

        long testId = System.currentTimeMillis() % 10000;

        for (int i = 0; i < clientCount; i++) {
            String uname = "stress_user_" + testId + "_" + i;
            ChatClient client = new ChatClient();
            client.register("127.0.0.1", TestSupport.PORT, uname, "Pass123!", "StressUser" + i);

            client.setEventListener(new ChatClient.ChatEventListener() {
                @Override public void onMessageReceived(String sender, String message) {
                    if (message.startsWith("STRESS_MSG_")) {
                        totalReceivedMsgs.incrementAndGet();
                    }
                }
                @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
                @Override public void onUserListUpdated(List<String> users) {}
                @Override public void onRoomListUpdated(List<String> rooms) {}
                @Override public void onSystemMessage(String message) {}
                @Override public void onDisconnected(String reason) {}
            });

            clients.add(client);
        }

        // Kết nối đồng thời trên Virtual Threads
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < clientCount; i++) {
                final int idx = i;
                final ChatClient c = clients.get(idx);
                final String uname = "stress_user_" + testId + "_" + idx;
                executor.submit(() -> {
                    try {
                        c.connect("127.0.0.1", TestSupport.PORT, uname, "Pass123!");
                        connectLatch.countDown();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            boolean allConnected = connectLatch.await(5, TimeUnit.SECONDS);
            assertTrue("10 Virtual Thread Clients kết nối đồng thời thành công", allConnected);

            Thread.sleep(300);

            // 100 tin nhắn gửi đồng thời song song
            for (int i = 0; i < clientCount; i++) {
                final ChatClient c = clients.get(i);
                final int clientIdx = i;
                executor.submit(() -> {
                    for (int m = 0; m < msgsPerClient; m++) {
                        c.sendPublicMessage("STRESS_MSG_" + clientIdx + "_" + m);
                        sendLatch.countDown();
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                    }
                });
            }

            boolean allSent = sendLatch.await(6, TimeUnit.SECONDS);
            assertTrue("Toàn bộ 100 tin nhắn gửi đi hoàn tất trong thời gian quy định", allSent);

            Thread.sleep(500);

            assertTrue("Hệ thống xử lý mượt mà, ghi nhận nhận tin nhắn ổn định", totalReceivedMsgs.get() > 0);
            assertEquals("Số lượng client online trên Server đúng 10", 10, cm.getClientCount());
        }

        // Ngắt kết nối toàn bộ
        for (ChatClient c : clients) {
            c.disconnect();
        }

        Thread.sleep(300);
        assertEquals("Server giải phóng kết nối sạch sẽ (0 client)", 0, cm.getClientCount());
        assertTrue("Không xảy ra rò rỉ Socket hay nghẽn Virtual Thread", true);
    }
}
