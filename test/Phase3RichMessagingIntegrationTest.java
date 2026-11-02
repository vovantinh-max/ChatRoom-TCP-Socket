package test;

import client.ChatClient;
import server.ChatServer;
import server.ClientManager;
import server.RoomManager;
import server.db.DatabaseManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Phase3RichMessagingIntegrationTest {

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
        System.out.println("===============================================================");
        System.out.println("   PHASE 3 INTEGRATION TEST - REAL-TIME RICH MESSAGING (MOD 3) ");
        System.out.println("===============================================================\n");

        DatabaseManager db = new DatabaseManager();

        testDatabaseMessagePersistenceAndReactions(db);
        testRealTimeMessagingAndTypingOverSocket(db);

        System.out.println("\n---------------------------------------------------------------");
        System.out.printf("Kết quả kiểm thử Phase 3: %d/%d passed (%.1f%%)\n", passedTests, totalTests, (passedTests * 100.0 / totalTests));
        System.out.println("---------------------------------------------------------------");

        if (passedTests == totalTests && totalTests > 0) {
            System.out.println(">>> TẤT CẢ KIỂM THỬ PHASE 3 ĐẠT CHUẨN 100%! <<<\n");
            System.exit(0);
        } else {
            System.err.println(">>> CÓ KIỂM THỬ THẤT BẠI! KIỂM TRA LẠI. <<<");
            System.exit(1);
        }
    }

    // ─── 1. TEST DATABASE DAO: LƯU TRỮ, EDIT, REVOKE, REACTIONS, HISTORY ────────
    private static void testDatabaseMessagePersistenceAndReactions(DatabaseManager db) {
        System.out.println("--- 1. Kiểm tra SQLite Message DAO (Lưu, Sửa, Thu hồi, Reaction, Lịch sử) ---");

        String msgId1 = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String sender = "user_alpha_" + now;
        String recipient = "user_beta_" + now;
        String convTarget = DatabaseManager.getPrivateConversationTarget(sender, recipient);

        // 1. Lưu tin nhắn
        boolean saved = db.saveMessage(msgId1, "PRIVATE", convTarget, sender, recipient, "Chào bạn, đây là tin nhắn số 1", null, System.currentTimeMillis());
        assertTrue("Lưu tin nhắn vào SQLite", saved);

        // 2. Đọc tin nhắn
        DatabaseManager.MessageRecord rec = db.getMessage(msgId1);
        assertTrue("Tìm thấy tin nhắn theo UUID", rec != null);
        assertEquals("Nội dung tin nhắn khớp", "Chào bạn, đây là tin nhắn số 1", rec != null ? rec.content() : "");
        assertEquals("Trạng thái chỉnh sửa ban đầu là false", false, rec != null && rec.isEdited());
        assertEquals("Trạng thái thu hồi ban đầu là false", false, rec != null && rec.isRevoked());

        // 3. Chỉnh sửa tin nhắn
        boolean edited = db.editMessage(msgId1, sender, "Tin nhắn số 1 đã chỉnh sửa");
        assertTrue("Chỉnh sửa tin nhắn thành công", edited);

        DatabaseManager.MessageRecord recEdited = db.getMessage(msgId1);
        assertEquals("Nội dung sau chỉnh sửa khớp", "Tin nhắn số 1 đã chỉnh sửa", recEdited != null ? recEdited.content() : "");
        assertEquals("Trạng thái is_edited = true", true, recEdited != null && recEdited.isEdited());

        // Không cho phép người khác sửa tin nhắn
        boolean unauthorizedEdit = db.editMessage(msgId1, "hacker_user", "Hack tin nhắn!");
        assertEquals("Từ chối người khác chỉnh sửa tin nhắn", false, unauthorizedEdit);

        // 4. Thả reaction (❤️, 👍)
        String rx1 = db.toggleReaction(msgId1, "user_beta", "❤️");
        assertTrue("User Beta thả tim", rx1.contains("❤️:user_beta"));

        String rx2 = db.toggleReaction(msgId1, "user_alpha", "❤️");
        assertTrue("User Alpha cùng thả tim", rx2.contains("user_beta") && rx2.contains("user_alpha"));

        String rx3 = db.toggleReaction(msgId1, "user_beta", "❤️");
        assertTrue("User Beta click lại để gỡ tim", !rx3.contains("user_beta") && rx3.contains("user_alpha"));

        // 5. Trả lời tin nhắn (replyToId)
        String msgId2 = UUID.randomUUID().toString();
        boolean replySaved = db.saveMessage(msgId2, "PRIVATE", convTarget, recipient, sender, "Đã nhận được!", msgId1, System.currentTimeMillis() + 100);
        assertTrue("Lưu tin nhắn trả lời (reply)", replySaved);

        DatabaseManager.MessageRecord replyRec = db.getMessage(msgId2);
        assertEquals("ReplyToId khớp với tin nhắn gốc", msgId1, replyRec != null ? replyRec.replyToId() : "");

        // 6. Lấy lịch sử hội thoại
        List<DatabaseManager.MessageRecord> history = db.getMessageHistory("PRIVATE", convTarget, 10, 0);
        assertEquals("Số lượng tin nhắn trong lịch sử", 2, history.size());
        assertEquals("Tin nhắn 1 đúng thứ tự thời gian", msgId1, history.get(0).uuid());
        assertEquals("Tin nhắn 2 đúng thứ tự thời gian", msgId2, history.get(1).uuid());

        // 7. Thu hồi tin nhắn
        boolean revoked = db.revokeMessage(msgId1, sender);
        assertTrue("Thu hồi tin nhắn thành công", revoked);

        DatabaseManager.MessageRecord recRevoked = db.getMessage(msgId1);
        assertEquals("Trạng thái is_revoked = true", true, recRevoked != null && recRevoked.isRevoked());
        assertEquals("Nội dung chuyển thành thông báo thu hồi", "Tin nhắn đã bị thu hồi", recRevoked != null ? recRevoked.content() : "");
    }

    // ─── 2. TEST REAL-TIME MESSAGING, TYPING, REACTIONS OVER TCP SOCKET ─────────
    private static void testRealTimeMessagingAndTypingOverSocket(DatabaseManager db) {
        System.out.println("\n--- 2. Kiểm thử Real-Time Rich Messaging, Typing, Reaction & History qua Socket ---");

        ClientManager clientManager = new ClientManager();
        RoomManager roomManager = new RoomManager();
        ChatServer server = new ChatServer(clientManager, null, roomManager, db);

        server.startServer();

        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        String u1 = "msg_user_1_" + (System.currentTimeMillis() % 10000);
        String u2 = "msg_user_2_" + (System.currentTimeMillis() % 10000);

        ChatClient client1 = new ChatClient();
        ChatClient client2 = new ChatClient();

        CountDownLatch c2MsgLatch = new CountDownLatch(1);
        CountDownLatch typingLatch = new CountDownLatch(1);
        CountDownLatch reactionLatch = new CountDownLatch(1);
        CountDownLatch editLatch = new CountDownLatch(1);
        CountDownLatch revokeLatch = new CountDownLatch(1);
        CountDownLatch historyLatch = new CountDownLatch(1);

        AtomicReference<ChatClient.ChatMessageItem> receivedMsgByC2 = new AtomicReference<>();
        AtomicReference<String> typingSender = new AtomicReference<>();
        AtomicBoolean isTypingStatus = new AtomicBoolean(false);
        AtomicReference<String> reactionMsgId = new AtomicReference<>();
        AtomicReference<String> reactionEmoji = new AtomicReference<>();
        AtomicReference<String> editedContent = new AtomicReference<>();
        AtomicReference<String> revokedId = new AtomicReference<>();
        List<ChatClient.ChatMessageItem> loadedHistory = Collections.synchronizedList(new ArrayList<>());

        client1.setEventListener(new ChatClient.ChatEventListener() {
            @Override public void onMessageReceived(String sender, String message) {}
            @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
            @Override public void onUserListUpdated(List<String> users) {}
            @Override public void onRoomListUpdated(List<String> rooms) {}
            @Override public void onSystemMessage(String message) {}
            @Override public void onDisconnected(String reason) {}

            @Override
            public void onHistoryLoaded(String target, List<ChatClient.ChatMessageItem> messages) {
                loadedHistory.addAll(messages);
                historyLatch.countDown();
            }

            @Override
            public void onReactionUpdated(String messageId, String emoji, String allReactions) {
                reactionMsgId.set(messageId);
                reactionEmoji.set(emoji);
                reactionLatch.countDown();
            }

            @Override
            public void onMessageEdited(String messageId, String newContent) {
                editedContent.set(newContent);
                editLatch.countDown();
            }

            @Override
            public void onMessageRevoked(String messageId) {
                revokedId.set(messageId);
                revokeLatch.countDown();
            }
        });

        client2.setEventListener(new ChatClient.ChatEventListener() {
            @Override public void onMessageReceived(String sender, String message) {}
            @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
            @Override public void onUserListUpdated(List<String> users) {}
            @Override public void onRoomListUpdated(List<String> rooms) {}
            @Override public void onSystemMessage(String message) {}
            @Override public void onDisconnected(String reason) {}

            @Override
            public void onPrivateChatMessageReceived(ChatClient.ChatMessageItem item) {
                receivedMsgByC2.set(item);
                c2MsgLatch.countDown();
            }

            @Override
            public void onTyping(String sender, boolean isTyping) {
                typingSender.set(sender);
                isTypingStatus.set(isTyping);
                typingLatch.countDown();
            }

            @Override
            public void onReactionUpdated(String messageId, String emoji, String allReactions) {
                reactionMsgId.set(messageId);
                reactionEmoji.set(emoji);
                reactionLatch.countDown();
            }

            @Override
            public void onMessageEdited(String messageId, String newContent) {
                editedContent.set(newContent);
                editLatch.countDown();
            }

            @Override
            public void onMessageRevoked(String messageId) {
                revokedId.set(messageId);
                revokeLatch.countDown();
            }
        });

        try {
            client1.register("127.0.0.1", TestSupport.PORT, u1, "Pass123!", "Alice");
            client2.register("127.0.0.1", TestSupport.PORT, u2, "Pass123!", "Bob");
            boolean c1Connected = client1.connect("127.0.0.1", TestSupport.PORT, u1, "Pass123!");
            boolean c2Connected = client2.connect("127.0.0.1", TestSupport.PORT, u2, "Pass123!");
            assertTrue("Client 1 kết nối thành công", c1Connected);
            assertTrue("Client 2 kết nối thành công", c2Connected);

            Thread.sleep(300);

            // 1. Kiểm tra Typing
            client1.sendTyping(u2, true);
            boolean typingReceived = typingLatch.await(3, TimeUnit.SECONDS);
            assertTrue("Client 2 nhận sự kiện Typing trong thời gian thực", typingReceived);
            assertEquals("Người gửi typing là Alice", u1, typingSender.get());
            assertEquals("Trạng thái typing là true", true, isTypingStatus.get());

            // 2. Gửi tin nhắn có UUID và nội dung
            String testMsgText = "Xin chào Bob! Đây là tin nhắn thời gian thực.";
            client1.sendPrivateMessage(u2, testMsgText);

            boolean msgReceived = c2MsgLatch.await(3, TimeUnit.SECONDS);
            assertTrue("Client 2 nhận tin nhắn thời gian thực", msgReceived);
            ChatClient.ChatMessageItem item = receivedMsgByC2.get();
            assertTrue("Tin nhắn có UUID duy nhất", item != null && item.uuid() != null && !item.uuid().isEmpty());
            assertEquals("Nội dung tin nhắn khớp", testMsgText, item != null ? item.content() : "");
            assertEquals("Người gửi tin nhắn khớp", u1, item != null ? item.sender() : "");

            String msgUuid = item != null ? item.uuid() : "";

            // 3. Gửi Reaction (Bob thả tim ❤️ vào tin nhắn của Alice)
            client2.sendReaction(msgUuid, "❤️", u1);
            boolean rxReceived = reactionLatch.await(3, TimeUnit.SECONDS);
            assertTrue("Nhận sự kiện Reaction thời gian thực", rxReceived);
            assertEquals("Reaction đúng UUID tin nhắn", msgUuid, reactionMsgId.get());
            assertEquals("Emoji reaction khớp", "❤️", reactionEmoji.get());

            // 4. Chỉnh sửa tin nhắn (Alice sửa tin nhắn)
            String updatedText = "Xin chào Bob! (Đã chỉnh sửa nội dung).";
            client1.sendEditMessage(msgUuid, updatedText, u2);
            boolean editReceived = editLatch.await(3, TimeUnit.SECONDS);
            assertTrue("Nhận sự kiện Chỉnh sửa tin nhắn thời gian thực", editReceived);
            assertEquals("Nội dung cập nhật khớp", updatedText, editedContent.get());

            // 5. Thu hồi tin nhắn (Alice thu hồi tin nhắn)
            client1.sendRevokeMessage(msgUuid, u2);
            boolean revokeReceived = revokeLatch.await(3, TimeUnit.SECONDS);
            assertTrue("Nhận sự kiện Thu hồi tin nhắn thời gian thực", revokeReceived);
            assertEquals("Thu hồi đúng UUID tin nhắn", msgUuid, revokedId.get());

            // 6. Yêu cầu tải lại lịch sử tin nhắn
            client1.requestHistory(u2, false);
            boolean historyReceived = historyLatch.await(3, TimeUnit.SECONDS);
            assertTrue("Tải lịch sử tin nhắn từ Server thành công", historyReceived);
            assertTrue("Lịch sử tin nhắn chứa ít nhất 1 tin", loadedHistory.size() >= 1);
            ChatClient.ChatMessageItem histMsg = loadedHistory.get(0);
            assertEquals("Lịch sử lưu vết thu hồi", true, histMsg.isRevoked());

        } catch (Exception e) {
            e.printStackTrace();
            assertTrue("Xảy ra ngoại lệ kiểm thử: " + e.getMessage(), false);
        } finally {
            client1.disconnect();
            client2.disconnect();
            server.stopServer();
        }
    }
}
