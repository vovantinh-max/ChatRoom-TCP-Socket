package test;

import client.ChatClient;
import client.ChatClient.FriendItem;
import client.ChatClient.SearchUserItem;
import server.ChatServer;
import server.ClientManager;
import server.RoomManager;
import server.db.DatabaseManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Phase2DatabaseAndFriendsIntegrationTest
 * Kiểm thử tự động toàn diện:
 * 1. SQLite Database Schema & Password Hashing (PBKDF2)
 * 2. Đăng ký tài khoản mới qua Socket
 * 3. Đăng nhập đúng mật khẩu & từ chối sai mật khẩu
 * 4. Tìm kiếm người dùng
 * 5. Gửi lời mời kết bạn & Lời mời đang chờ
 * 6. Chấp nhận kết bạn & Đồng bộ danh sách bạn bè thời gian thực (Online/Offline)
 * 7. Hủy kết bạn
 */
public class Phase2DatabaseAndFriendsIntegrationTest {

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
        System.out.println("===============================================================");
        System.out.println("   PHASE 2 INTEGRATION TEST - DATABASE, ACCOUNT & FRIENDS      ");
        System.out.println("===============================================================");

        testDatabaseAndPasswordHashing();
        testSocketRegistrationAndAuthentication();
        testFriendsSystemRealtime();

        System.out.println("\n---------------------------------------------------------------");
        System.out.printf("Kết quả kiểm thử: %d/%d passed (%.1f%%)\n",
                testsPassed, testsTotal, (testsPassed * 100.0 / testsTotal));
        System.out.println("---------------------------------------------------------------");

        if (testsPassed == testsTotal) {
            System.out.println(">>> TẤT CẢ KIỂM THỬ PHASE 2 ĐẠT CHUẨN 100%! <<<");
            System.exit(0);
        } else {
            System.err.println(">>> CÓ BÀI TEST THẤT BẠI! <<<");
            System.exit(1);
        }
    }

    private static void testDatabaseAndPasswordHashing() {
        System.out.println("\n--- 1. Kiểm tra SQLite Engine & PBKDF2 Password Hashing ---");

        DatabaseManager db = new DatabaseManager();

        // Test PBKDF2 hash & salt
        String salt = DatabaseManager.generateSalt();
        String pass = "SecureP@ssw0rd2026!";
        String hash = DatabaseManager.hashPassword(pass, salt);

        assertTrue("Salt ngẫu nhiên được sinh ra", salt != null && salt.length() > 10);
        assertTrue("Hash mật khẩu được sinh ra", hash != null && hash.length() > 20);
        assertTrue("Xác minh đúng mật khẩu", DatabaseManager.verifyPassword(pass, salt, hash));
        assertTrue("Từ chối sai mật khẩu", !DatabaseManager.verifyPassword("WrongPassword", salt, hash));

        // Test đăng ký trực tiếp qua DB
        String testUser = "db_user_" + System.currentTimeMillis();
        boolean reg = db.registerUser(testUser, "Secret123", "Người Dùng Thử Nghiệm");
        assertTrue("Đăng ký user mới vào SQLite", reg);

        // Test không cho đăng ký trùng username
        boolean dup = db.registerUser(testUser, "Secret123", "Trùng Tên");
        assertTrue("Từ chối username bị trùng", !dup);

        // Test authenticate DB
        DatabaseManager.UserRecord u = db.authenticate(testUser, "Secret123");
        assertTrue("Xác thực đúng mật khẩu", u != null);
        assertEquals("Username khớp", testUser, u.username());
        assertEquals("Display name khớp", "Người Dùng Thử Nghiệm", u.displayName());

        DatabaseManager.UserRecord fail = db.authenticate(testUser, "WrongPassword");
        assertTrue("Xác thực sai mật khẩu trả về null", fail == null);
    }

    private static void testSocketRegistrationAndAuthentication() {
        System.out.println("\n--- 2. Kiểm thử Đăng ký & Đăng nhập qua Socket TCP ---");

        ClientManager clientManager = new ClientManager();
        RoomManager roomManager = new RoomManager();
        DatabaseManager db = new DatabaseManager();
        ChatServer server = new ChatServer(clientManager, null, roomManager, db);

        boolean started = server.startServer();
        assertTrue("Server khởi động trên cổng kiểm thử", started);

        ChatClient client = new ChatClient();
        String newUser = "client_user_" + (System.currentTimeMillis() % 100000);
        String newPass = "Pass#9876";

        try {
            // Đăng ký qua Socket
            String regResult = client.register("127.0.0.1", TestSupport.PORT, newUser, newPass, "Nguyễn Văn Client");
            assertEquals("Đăng ký qua Socket thành công", "SUCCESS", regResult);

            // Đăng nhập sai pass
            boolean loginFailedThrown = false;
            try {
                ChatClient clientFail = new ChatClient();
                clientFail.connect("127.0.0.1", TestSupport.PORT, newUser, "SaiPass");
            } catch (Exception e) {
                loginFailedThrown = true;
            }
            assertTrue("Chặn đăng nhập sai mật khẩu qua Socket", loginFailedThrown);

            // Đăng nhập đúng pass
            ChatClient clientOk = new ChatClient();
            boolean ok = clientOk.connect("127.0.0.1", TestSupport.PORT, newUser, newPass);
            assertTrue("Đăng nhập thành công đúng mật khẩu qua Socket", ok);

            clientOk.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
            assertTrue("Lỗi trong quá trình test Socket Auth: " + e.getMessage(), false);
        } finally {
            server.stopServer();
        }
    }

    private static void testFriendsSystemRealtime() {
        System.out.println("\n--- 3. Kiểm thử Hệ thống Bạn bè thời gian thực (Module 2) ---");

        ClientManager clientManager = new ClientManager();
        RoomManager roomManager = new RoomManager();
        DatabaseManager db = new DatabaseManager();
        ChatServer server = new ChatServer(clientManager, null, roomManager, db);

        server.startServer();

        long ts = System.currentTimeMillis();
        String userA = "friend_a_" + (ts % 10000);
        String userB = "friend_b_" + (ts % 10000);

        ChatClient clientA = new ChatClient();
        ChatClient clientB = new ChatClient();

        CountDownLatch friendLatchB = new CountDownLatch(1);
        CountDownLatch requestLatchB = new CountDownLatch(1);
        CountDownLatch friendLatchA = new CountDownLatch(1);

        List<FriendItem> friendsOfB = new ArrayList<>();
        List<String> requestsOfB = new ArrayList<>();
        List<FriendItem> friendsOfA = new ArrayList<>();

        try {
            // Tạo 2 tài khoản
            clientA.register("127.0.0.1", TestSupport.PORT, userA, "Pass123", "Alice Nguyễn");
            clientB.register("127.0.0.1", TestSupport.PORT, userB, "Pass123", "Bob Trần");

            clientB.setEventListener(new ChatClient.ChatEventListener() {
                @Override public void onMessageReceived(String sender, String message) {}
                @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
                @Override public void onUserListUpdated(List<String> users) {}
                @Override public void onRoomListUpdated(List<String> rooms) {}
                @Override public void onSystemMessage(String message) {}
                @Override public void onDisconnected(String reason) {}

                @Override
                public void onPendingRequestsUpdated(List<String> senders) {
                    if (senders.contains(userA)) {
                        requestsOfB.clear();
                        requestsOfB.addAll(senders);
                        requestLatchB.countDown();
                    }
                }

                @Override
                public void onFriendListUpdated(List<FriendItem> friends) {
                    if (!friends.isEmpty()) {
                        friendsOfB.clear();
                        friendsOfB.addAll(friends);
                        friendLatchB.countDown();
                    }
                }
            });

            clientA.setEventListener(new ChatClient.ChatEventListener() {
                @Override public void onMessageReceived(String sender, String message) {}
                @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
                @Override public void onUserListUpdated(List<String> users) {}
                @Override public void onRoomListUpdated(List<String> rooms) {}
                @Override public void onSystemMessage(String message) {}
                @Override public void onDisconnected(String reason) {}

                @Override
                public void onFriendListUpdated(List<FriendItem> friends) {
                    if (!friends.isEmpty()) {
                        friendsOfA.clear();
                        friendsOfA.addAll(friends);
                        friendLatchA.countDown();
                    }
                }
            });

            // Cả 2 cùng kết nối online
            clientA.connect("127.0.0.1", TestSupport.PORT, userA, "Pass123");
            clientB.connect("127.0.0.1", TestSupport.PORT, userB, "Pass123");
            Thread.sleep(300); // Chờ handshake ban đầu hoàn tất

            // A gửi lời mời kết bạn cho B
            clientA.sendFriendRequest(userB);

            // B phải nhận được lời mời kết bạn đang chờ thời gian thực
            boolean reqReceived = requestLatchB.await(3, TimeUnit.SECONDS);
            assertTrue("Bob nhận được lời mời kết bạn từ Alice thời gian thực", reqReceived);
            assertTrue("Danh sách lời mời của Bob chứa Alice", requestsOfB.contains(userA));

            // B chấp nhận kết bạn
            clientB.acceptFriendRequest(userA);

            // Cả A và B đều nhận được danh sách bạn bè mới cập nhật
            boolean fOkB = friendLatchB.await(3, TimeUnit.SECONDS);
            boolean fOkA = friendLatchA.await(3, TimeUnit.SECONDS);

            assertTrue("Bob nhận được danh sách bạn bè cập nhật", fOkB);
            assertTrue("Alice nhận được danh sách bạn bè cập nhật", fOkA);

            assertEquals("Alice có 1 bạn bè", 1, friendsOfA.size());
            assertEquals("Bạn bè của Alice là Bob", userB, friendsOfA.get(0).username());
            assertTrue("Bob đang hiển thị Online trong danh sách của Alice", friendsOfA.get(0).isOnline());

            assertEquals("Bob có 1 bạn bè", 1, friendsOfB.size());
            assertEquals("Bạn bè của Bob là Alice", userA, friendsOfB.get(0).username());
            assertTrue("Alice đang hiển thị Online trong danh sách của Bob", friendsOfB.get(0).isOnline());

            // A hủy kết bạn với B
            clientA.removeFriend(userB);
            Thread.sleep(300);

            assertTrue("Đã hủy quan hệ bạn bè trong DB", !db.isFriend(userA, userB));

            clientA.disconnect();
            clientB.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
            assertTrue("Lỗi trong quá trình test Friends: " + e.getMessage(), false);
        } finally {
            server.stopServer();
        }
    }
}
