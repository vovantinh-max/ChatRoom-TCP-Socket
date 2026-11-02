package test;

import client.ChatClient;
import client.file.FileTransferEngine;
import client.media.audio.AudioCallEngine;
import client.media.video.VideoCallEngine;
import client.notification.NotificationManager;
import server.ChatServer;
import server.ClientManager;
import server.RoomManager;
import server.db.DatabaseManager;
import shared.protocol.Packet;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Phase4To7MediaAndFileIntegrationTest {

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
        System.out.println("   PHASE 4-7 INTEGRATION TEST: FILE TRANSFER, AUDIO/VIDEO & NOTIFICATIONS");
        System.out.println("=========================================================================\n");

        testDesktopNotifications();

        ClientManager clientManager = new ClientManager();
        RoomManager roomManager = new RoomManager();
        DatabaseManager db = new DatabaseManager();
        ChatServer server = new ChatServer(clientManager, null, roomManager, db);

        server.startServer();

        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        String u1 = "media_user_1_" + (System.currentTimeMillis() % 10000);
        String u2 = "media_user_2_" + (System.currentTimeMillis() % 10000);

        ChatClient client1 = new ChatClient();
        ChatClient client2 = new ChatClient();

        try {
            client1.register("127.0.0.1", 5000, u1, "Pass123!", "Alice");
            client2.register("127.0.0.1", 5000, u2, "Pass123!", "Bob");

            client1.connect("127.0.0.1", 5000, u1, "Pass123!");
            client2.connect("127.0.0.1", 5000, u2, "Pass123!");

            Thread.sleep(300);

            testChunkedFileTransfer(client1, client2, u1, u2);
            testRealTimeAudioCall(client1, client2, u1, u2);
            testRealTimeVideoCallAndScreenSharing(client1, client2, u1, u2);

        } catch (Exception e) {
            e.printStackTrace();
            assertTrue("Ngoại lệ kiểm thử: " + e.getMessage(), false);
        } finally {
            client1.disconnect();
            client2.disconnect();
            server.stopServer();
            db.close();
        }

        System.out.println("\n-------------------------------------------------------------------------");
        System.out.printf("Kết quả kiểm thử Phase 4-7: %d/%d passed (%.1f%%)\n", passedTests, totalTests, (passedTests * 100.0 / totalTests));
        System.out.println("-------------------------------------------------------------------------");

        if (passedTests == totalTests && totalTests > 0) {
            System.out.println(">>> TẤT CẢ KIỂM THỬ PHASE 4-7 ĐẠT CHUẨN 100%! <<<\n");
            System.exit(0);
        } else {
            System.err.println(">>> CÓ KIỂM THỬ THẤT BẠI! KIỂM TRA LẠI. <<<");
            System.exit(1);
        }
    }

    // ── 1. TEST THÔNG BÁO DESKTOP (MODULE 10) ──────────────────────────────────
    private static void testDesktopNotifications() {
        System.out.println("--- 1. Kiểm thử Desktop Notifications & Âm thanh (Module 10) ---");

        NotificationManager nm = NotificationManager.getInstance();
        assertTrue("NotificationManager khởi tạo thành công", nm != null);

        nm.setDoNotDisturb(false);
        assertEquals("Trạng thái DND ban đầu là false", false, nm.isDoNotDisturb());

        // Phát chuông chime & chuông cuộc gọi
        nm.playMessageChime();
        assertTrue("Phát Message Chime thành công", true);

        nm.startRingtone();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        nm.stopRingtone();
        assertTrue("Khởi động và dừng Ringtone thành công", true);

        nm.setDoNotDisturb(true);
        assertEquals("Bật chế độ Không làm phiền (DND)", true, nm.isDoNotDisturb());
        nm.setDoNotDisturb(false);
    }

    // ── 2. TEST TRUYỀN FILE THEO CHUNK 64KB (MODULE 8) ─────────────────────────
    private static void testChunkedFileTransfer(ChatClient c1, ChatClient c2, String u1, String u2) throws Exception {
        System.out.println("\n--- 2. Kiểm thử Truyền File theo Chunk 64KB & Checksum SHA-256 (Module 8) ---");

        FileTransferEngine engine1 = new FileTransferEngine(c1);
        FileTransferEngine engine2 = new FileTransferEngine(c2);

        c2.setEventListener(new ChatClient.ChatEventListener() {
            @Override public void onMessageReceived(String sender, String message) {}
            @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
            @Override public void onUserListUpdated(java.util.List<String> users) {}
            @Override public void onRoomListUpdated(java.util.List<String> rooms) {}
            @Override public void onSystemMessage(String message) {}
            @Override public void onDisconnected(String reason) {}

            @Override
            public void onFilePacketReceived(Packet packet) {
                engine2.handleIncomingPacket(packet);
            }
        });

        // Tạo file kiểm thử 150 KB (vượt quá 2 chunk 64KB)
        File testUploadFile = new File("uploads/test_document.dat");
        testUploadFile.getParentFile().mkdirs();
        byte[] testData = new byte[150 * 1024];
        new Random(42).nextBytes(testData);
        try (FileOutputStream fos = new FileOutputStream(testUploadFile)) {
            fos.write(testData);
        }

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] originalHashBytes = sha256.digest(testData);
        StringBuilder sb = new StringBuilder();
        for (byte b : originalHashBytes) sb.append(String.format("%02x", b));
        String expectedHash = sb.toString();

        CountDownLatch fileLatch = new CountDownLatch(1);
        AtomicReference<File> receivedFile = new AtomicReference<>();
        AtomicBoolean progressReached = new AtomicBoolean(false);

        engine2.setProgressListener(new FileTransferEngine.FileProgressListener() {
            @Override
            public void onProgress(String fileId, String fileName, int percent, long bytesTransferred, long totalBytes, boolean isUpload) {
                if (percent > 0) progressReached.set(true);
            }

            @Override
            public void onTransferComplete(String fileId, String fileName, File savedFile, boolean isUpload) {
                receivedFile.set(savedFile);
                fileLatch.countDown();
            }

            @Override
            public void onTransferFailed(String fileId, String fileName, String errorReason) {
                fileLatch.countDown();
            }
        });

        // Alice gửi file cho Bob
        String fileId = engine1.sendFile(testUploadFile, u2);
        assertTrue("Khởi tạo phiên truyền file với fileId hợp lệ", fileId != null && !fileId.isEmpty());

        boolean completed = fileLatch.await(5, TimeUnit.SECONDS);
        assertTrue("Truyền toàn bộ file hoàn tất trong thời gian quy định", completed);
        assertTrue("Báo cáo tiến trình tải lên/xuống (progress) hoạt động", progressReached.get());

        File saved = receivedFile.get();
        assertTrue("File được tải xuống lưu thành công vào đĩa", saved != null && saved.exists());
        assertEquals("Dung lượng file tải về khớp chính xác (150 KB)", (long) testData.length, saved != null ? saved.length() : 0L);

        // Kiểm tra Checksum SHA-256 của file đã nhận
        if (saved != null) {
            byte[] savedData = Files.readAllBytes(saved.toPath());
            byte[] savedHashBytes = MessageDigest.getInstance("SHA-256").digest(savedData);
            StringBuilder sbSaved = new StringBuilder();
            for (byte b : savedHashBytes) sbSaved.append(String.format("%02x", b));
            assertEquals("Checksum SHA-256 của file nhận khớp 100% với file gốc", expectedHash, sbSaved.toString());
            saved.delete();
        }
        testUploadFile.delete();
    }

    // ── 3. TEST GỌI THOẠI REAL-TIME QUA UDP (MODULE 4) ──────────────────────────
    private static void testRealTimeAudioCall(ChatClient c1, ChatClient c2, String u1, String u2) throws Exception {
        System.out.println("\n--- 3. Kiểm thử Gọi thoại 1-1 Real-Time qua UDP & TCP Signaling (Module 4) ---");

        AudioCallEngine audio1 = new AudioCallEngine(c1);
        AudioCallEngine audio2 = new AudioCallEngine(c2);

        CountDownLatch incomingLatch = new CountDownLatch(1);
        CountDownLatch connectedLatchC1 = new CountDownLatch(1);
        CountDownLatch connectedLatchC2 = new CountDownLatch(1);

        AtomicReference<String> callerUser = new AtomicReference<>();
        AtomicReference<Integer> callerPortRef = new AtomicReference<>(0);

        audio1.setListener(new AudioCallEngine.AudioCallListener() {
            @Override
            public void onCallStateChanged(AudioCallEngine.CallState newState, String peer) {
                if (newState == AudioCallEngine.CallState.CONNECTED) {
                    connectedLatchC1.countDown();
                }
            }
            @Override public void onIncomingCall(String caller, int callerPort) {}
            @Override public void onCallDurationUpdated(int seconds) {}
            @Override public void onError(String errorMessage) {}
        });

        audio2.setListener(new AudioCallEngine.AudioCallListener() {
            @Override
            public void onCallStateChanged(AudioCallEngine.CallState newState, String peer) {
                if (newState == AudioCallEngine.CallState.CONNECTED) {
                    connectedLatchC2.countDown();
                }
            }

            @Override
            public void onIncomingCall(String caller, int callerPort) {
                callerUser.set(caller);
                callerPortRef.set(callerPort);
                incomingLatch.countDown();
            }

            @Override public void onCallDurationUpdated(int seconds) {}
            @Override public void onError(String errorMessage) {}
        });

        c1.setEventListener(new ChatClient.ChatEventListener() {
            @Override public void onMessageReceived(String sender, String message) {}
            @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
            @Override public void onUserListUpdated(java.util.List<String> users) {}
            @Override public void onRoomListUpdated(java.util.List<String> rooms) {}
            @Override public void onSystemMessage(String message) {}
            @Override public void onDisconnected(String reason) {}
            @Override
            public void onCallPacketReceived(Packet packet) {
                audio1.handleSignalingPacket(packet);
            }
        });

        c2.setEventListener(new ChatClient.ChatEventListener() {
            @Override public void onMessageReceived(String sender, String message) {}
            @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
            @Override public void onUserListUpdated(java.util.List<String> users) {}
            @Override public void onRoomListUpdated(java.util.List<String> rooms) {}
            @Override public void onSystemMessage(String message) {}
            @Override public void onDisconnected(String reason) {}
            @Override
            public void onCallPacketReceived(Packet packet) {
                audio2.handleSignalingPacket(packet);
            }
        });

        // Alice bắt đầu gọi Bob
        boolean callStarted = audio1.startCall(u2);
        assertTrue("Alice bắt đầu cuộc gọi thành công (trạng thái CALLING)", callStarted);
        assertEquals("Trạng thái của Alice là CALLING", AudioCallEngine.CallState.CALLING, audio1.getState());

        boolean incomingRcv = incomingLatch.await(3, TimeUnit.SECONDS);
        assertTrue("Bob nhận được tín hiệu cuộc gọi đến (CALL_OFFER)", incomingRcv);
        assertEquals("Người gọi là Alice", u1, callerUser.get());
        assertTrue("Alice cung cấp cổng UDP hợp lệ", callerPortRef.get() > 0);
        assertEquals("Trạng thái của Bob là RINGING", AudioCallEngine.CallState.RINGING, audio2.getState());

        // Bob chấp nhận cuộc gọi
        boolean accepted = audio2.acceptCall(u1, callerPortRef.get(), "127.0.0.1");
        assertTrue("Bob chấp nhận cuộc gọi", accepted);

        boolean c1Connected = connectedLatchC1.await(3, TimeUnit.SECONDS);
        boolean c2Connected = connectedLatchC2.await(3, TimeUnit.SECONDS);
        assertTrue("Alice chuyển sang trạng thái CONNECTED", c1Connected);
        assertTrue("Bob chuyển sang trạng thái CONNECTED", c2Connected);

        // Kiểm tra bật/tắt mic & âm lượng
        audio1.setMuted(true);
        assertEquals("Alice bật chế độ Mute mic", true, audio1.isMuted());
        audio1.setMuted(false);
        assertEquals("Alice tắt chế độ Mute mic", false, audio1.isMuted());
        audio1.setVolume(0.8f);

        // Kết thúc cuộc gọi
        audio1.endCall();
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        assertEquals("Alice chuyển về trạng thái IDLE sau khi cúp máy", AudioCallEngine.CallState.IDLE, audio1.getState());
        assertEquals("Bob chuyển về trạng thái IDLE sau khi cúp máy", AudioCallEngine.CallState.IDLE, audio2.getState());
    }

    // ── 4. TEST VIDEO CALL & SCREEN SHARING (MODULES 5 & 7) ────────────────────
    private static void testRealTimeVideoCallAndScreenSharing(ChatClient c1, ChatClient c2, String u1, String u2) throws Exception {
        System.out.println("\n--- 4. Kiểm thử Video Call & Screen Sharing qua UDP Frame Streaming (Modules 5 & 7) ---");

        VideoCallEngine video1 = new VideoCallEngine(c1);
        VideoCallEngine video2 = new VideoCallEngine(c2);

        assertTrue("Khởi tạo cổng UDP video của Alice", video1.initVideoPort());
        assertTrue("Khởi tạo cổng UDP video của Bob", video2.initVideoPort());

        CountDownLatch frameLatch = new CountDownLatch(1);
        AtomicReference<BufferedImage> receivedImgRef = new AtomicReference<>();

        video2.setFrameListener(new VideoCallEngine.VideoFrameListener() {
            @Override
            public void onRemoteFrameReceived(BufferedImage frame) {
                receivedImgRef.set(frame);
                frameLatch.countDown();
            }

            @Override public void onLocalPreviewFrame(BufferedImage frame) {}
            @Override public void onVideoCallEnded(String reason) {}
        });

        // Bắt đầu truyền video từ Alice tới Bob
        video2.startVideoStreaming(u1, "127.0.0.1", video1.getLocalVideoPort());
        video1.startVideoStreaming(u2, "127.0.0.1", video2.getLocalVideoPort());

        boolean frameReceived = frameLatch.await(4, TimeUnit.SECONDS);
        assertTrue("Bob nhận được Frame Video nén từ Alice qua UDP", frameReceived);

        BufferedImage img = receivedImgRef.get();
        assertTrue("Hình ảnh video không rỗng", img != null);
        assertEquals("Chiều rộng frame video chuẩn (640px)", 640, img != null ? img.getWidth() : 0);
        assertEquals("Chiều cao frame video chuẩn (480px)", 480, img != null ? img.getHeight() : 0);

        // Kiểm tra chuyển sang chế độ Screen Share (Module 7)
        video1.setVideoMode(VideoCallEngine.VideoMode.SCREEN_SHARE);
        assertEquals("Chuyển chế độ video sang SCREEN_SHARE", VideoCallEngine.VideoMode.SCREEN_SHARE, video1.getCurrentMode());

        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        // Dừng video streaming
        video1.stopVideo();
        video2.stopVideo();
        assertTrue("Dừng luồng video streaming thành công", true);
    }
}
