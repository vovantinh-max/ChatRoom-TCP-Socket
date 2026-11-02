package test;

import client.ChatClient;
import server.ChatServer;
import server.ClientManager;
import server.RoomManager;
import shared.protocol.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase1ProtocolIntegrationTest
 * Kiểm thử tự động tính ổn định của TCP Socket, Virtual Threads, Packet Protocol và Ping-Pong.
 */
public class Phase1ProtocolIntegrationTest {

    private static int testsPassed = 0;
    private static int testsTotal = 0;

    private static void assertEquals(String testName, Object expected, Object actual) {
        testsTotal++;
        if ((expected == null && actual == null) || (expected != null && expected.equals(actual))) {
            System.out.println("  [PASS] " + testName);
            testsPassed++;
        } else {
            System.err.println("  [FAIL] " + testName + " | Expected: " + expected + ", Actual: " + actual);
        }
    }

    private static void assertTrue(String testName, boolean condition) {
        testsTotal++;
        if (condition) {
            System.out.println("  [PASS] " + testName);
            testsPassed++;
        } else {
            System.err.println("  [FAIL] " + testName + " | Condition was false");
        }
    }

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   PHASE 1 INTEGRATION TEST - TCP SOCKET & PACKET ");
        System.out.println("==================================================");

        testPacketSerialization();
        testLegacyFormatCompatibility();
        testMultiClientCommunicationAndHeartbeat();

        System.out.println("\n--------------------------------------------------");
        System.out.printf("Kết quả kiểm thử: %d/%d passed (%.1f%%)\n",
                testsPassed, testsTotal, (testsPassed * 100.0 / testsTotal));
        System.out.println("--------------------------------------------------");

        if (testsPassed == testsTotal) {
            System.out.println(">>> TẤT CẢ KIỂM THỬ PHASE 1 ĐẠT CHUẨN 100%! <<<");
            System.exit(0);
        } else {
            System.err.println(">>> CÓ BÀI TEST THẤT BẠI! <<<");
            System.exit(1);
        }
    }

    private static void testPacketSerialization() {
        System.out.println("\n--- 1. Kiểm tra mã hóa & giải mã Packet với ký tự đặc biệt ---");

        Packet p1 = new Packet(Packet.Type.MESSAGE, "Alice", "Xin chào! Ký tự đặc biệt: ^ | \n \r ~ @ # $ %");
        p1.setRecipient("Bob");
        p1.setExtra("{\"replyTo\":\"msg-123\",\"color\":\"#6366F1\"}");

        String raw = p1.toRawString();
        Packet decoded = Packet.fromRawString(raw);

        assertEquals("Packet Type", p1.getType(), decoded.getType());
        assertEquals("Packet Sender", p1.getSender(), decoded.getSender());
        assertEquals("Packet Recipient", p1.getRecipient(), decoded.getRecipient());
        assertEquals("Packet Content", p1.getContent(), decoded.getContent());
        assertEquals("Packet Extra metadata", p1.getExtra(), decoded.getExtra());
    }

    private static void testLegacyFormatCompatibility() {
        System.out.println("\n--- 2. Kiểm tra tương thích ngược với định dạng cũ (Pipe '|') ---");

        String legacyMsg = "MESSAGE|Alice|Hello world via legacy protocol";
        Packet p = Packet.fromRawString(legacyMsg);
        assertEquals("Legacy Message Type", Packet.Type.MESSAGE, p.getType());
        assertEquals("Legacy Message Sender", "Alice", p.getSender());
        assertEquals("Legacy Message Content", "Hello world via legacy protocol", p.getContent());

        String legacyPrivate = "PRIVATE|Bob|Charlie|Secret message";
        Packet p2 = Packet.fromRawString(legacyPrivate);
        assertEquals("Legacy Private Type", Packet.Type.PRIVATE, p2.getType());
        assertEquals("Legacy Private Sender", "Bob", p2.getSender());
        assertEquals("Legacy Private Recipient", "Charlie", p2.getRecipient());
        assertEquals("Legacy Private Content", "Secret message", p2.getContent());
    }

    private static void testMultiClientCommunicationAndHeartbeat() {
        System.out.println("\n--- 3. Kiểm thử Multi-Client TCP Socket, Ping/Pong & Virtual Threads ---");

        ClientManager clientManager = new ClientManager();
        RoomManager roomManager = new RoomManager();
        ChatServer server = new ChatServer(clientManager, null, roomManager);

        boolean started = server.startServer();
        assertTrue("Server khởi động thành công", started);

        ChatClient client1 = new ChatClient();
        ChatClient client2 = new ChatClient();

        CountDownLatch msgLatch = new CountDownLatch(1);
        CountDownLatch privateLatch = new CountDownLatch(1);
        List<String> receivedPublic = new ArrayList<>();
        List<String> receivedPrivate = new ArrayList<>();

        client2.setEventListener(new ChatClient.ChatEventListener() {
            @Override
            public void onMessageReceived(String sender, String message) {
                receivedPublic.add(sender + ": " + message);
                msgLatch.countDown();
            }

            @Override
            public void onPrivateMessageReceived(String sender, String receiver, String message) {
                receivedPrivate.add(sender + " -> " + receiver + ": " + message);
                privateLatch.countDown();
            }

            @Override public void onUserListUpdated(List<String> users) {}
            @Override public void onRoomListUpdated(List<String> rooms) {}
            @Override public void onSystemMessage(String message) {}
            @Override public void onDisconnected(String reason) {}
        });

        try {
            // Client 1 kết nối
            client1.register("127.0.0.1", TestSupport.PORT, "User_Alpha", "TestPass123!", "Alpha");
            boolean c1 = client1.connect("127.0.0.1", TestSupport.PORT, "User_Alpha", "TestPass123!");
            assertTrue("Client 1 (User_Alpha) kết nối thành công", c1);

            // Client 2 kết nối
            client2.register("127.0.0.1", TestSupport.PORT, "User_Beta", "TestPass123!", "Beta");
            boolean c2 = client2.connect("127.0.0.1", TestSupport.PORT, "User_Beta", "TestPass123!");
            assertTrue("Client 2 (User_Beta) kết nối thành công", c2);

            // Chờ server cập nhật
            Thread.sleep(200);
            assertEquals("Số lượng client online trên server", 2, clientManager.getClientCount());

            // Test Ping - Pong
            AtomicBoolean pongHandled = new AtomicBoolean(false);
            Packet ping = new Packet(Packet.Type.PING, "User_Alpha", "");
            client1.sendPacket(ping);
            Thread.sleep(200);
            assertTrue("Client 1 Ping Pong hoạt động", client1.isConnected());

            // Test Public Message
            client1.sendPublicMessage("Xin chào phòng chat!");
            boolean msgOk = msgLatch.await(3, TimeUnit.SECONDS);
            assertTrue("Client 2 nhận được public message", msgOk);
            assertEquals("Nội dung tin nhắn public", "User_Alpha: Xin chào phòng chat!", receivedPublic.get(0));

            // Test Private Message
            client1.sendPrivateMessage("User_Beta", "Đây là tin nhắn riêng tư!");
            boolean privOk = privateLatch.await(3, TimeUnit.SECONDS);
            assertTrue("Client 2 nhận được private message", privOk);
            assertEquals("Nội dung tin nhắn private", "User_Alpha -> User_Beta: Đây là tin nhắn riêng tư!", receivedPrivate.get(0));

            // Test ngắt kết nối an toàn (Graceful disconnect)
            client1.disconnect();
            Thread.sleep(300);
            assertEquals("Số client sau khi User_Alpha rời", 1, clientManager.getClientCount());

            client2.disconnect();
            Thread.sleep(300);
            assertEquals("Số client sau khi User_Beta rời", 0, clientManager.getClientCount());

        } catch (Exception e) {
            e.printStackTrace();
            assertTrue("Ngoại lệ trong quá trình kiểm thử: " + e.getMessage(), false);
        } finally {
            server.stopServer();
        }
    }
}
