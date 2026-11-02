package server.db;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DatabaseManager - Quản trị Cơ sở dữ liệu SQLite & In-Memory Fallback.
 * Tự động thích ứng nếu thiếu driver SQLite để không bao giờ bị crash NullPointerException.
 */
public class DatabaseManager {

    private static final String DB_DIR = "data";
    private static final String DB_PATH = System.getProperty("chat.db.path", DB_DIR + "/messenger.db");
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH;

    private static final int HASH_ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Connection connection;
    private final Object dbLock = new Object();

    // ─── IN-MEMORY FALLBACK STORE ───────────────────────────────────────────────
    private static class InMemUser {
        int id;
        String username;
        String passwordHash;
        String salt;
        String displayName;
        String status;
        long lastSeen;

        InMemUser(int id, String username, String passwordHash, String salt, String displayName, String status, long lastSeen) {
            this.id = id;
            this.username = username;
            this.passwordHash = passwordHash;
            this.salt = salt;
            this.displayName = displayName;
            this.status = status;
            this.lastSeen = lastSeen;
        }
    }

    private volatile boolean useMemoryFallback = false;
    private final Map<String, InMemUser> inMemUsers = new ConcurrentHashMap<>();
    private final AtomicInteger inMemUserIdCounter = new AtomicInteger(1);
    private final Set<String> inMemFriendRequests = ConcurrentHashMap.newKeySet();
    private final Set<String> inMemFriendships = ConcurrentHashMap.newKeySet();
    private final List<MessageRecord> inMemMessages = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Set<String>> inMemGroups = new ConcurrentHashMap<>();
    private final Map<String, String> inMemGroupOwners = new ConcurrentHashMap<>();

    public boolean isUsingMemoryFallback() { return useMemoryFallback; }

    public boolean isAvailable() {
        if (useMemoryFallback) return true;
        try { return connection != null && !connection.isClosed(); }
        catch (SQLException e) { return false; }
    }

    public record UserRecord(
        int id,
        String username,
        String displayName,
        String avatarColor,
        String status,
        long lastSeen
    ) {}

    public record FriendRecord(
        String username,
        String displayName,
        String avatarColor,
        String status,
        boolean isOnline
    ) {}

    public record MessageRecord(
        String uuid,
        String convType,
        String convTarget,
        String sender,
        String recipient,
        String content,
        String replyToId,
        boolean isEdited,
        boolean isRevoked,
        String reactions,
        String status,
        long timestamp
    ) {}

    public DatabaseManager() {
        initDatabase();
    }

    private void initDatabase() {
        try {
            File dir = new File(DB_PATH).getAbsoluteFile().getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // Nạp SQLite JDBC Driver
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(DB_URL);

            // Bật Write-Ahead Logging (WAL) để tối ưu đọc/ghi đồng thời
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL;");
                s.execute("PRAGMA synchronous=NORMAL;");
                s.execute("PRAGMA foreign_keys=ON;");
            }

            createTables();
            useMemoryFallback = false;
        } catch (Exception | LinkageError e) {
            try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
            connection = null;
            if (!Boolean.getBoolean("chat.db.allowMemoryFallback")) {
                throw new IllegalStateException("Không mở được SQLite. Kiểm tra thư viện lib và quyền ghi thư mục data; dữ liệu chưa được chuyển sang bộ nhớ tạm.", e);
            }
            System.err.println("[DatabaseManager] Explicit in-memory demo mode: data will not survive restart.");
            useMemoryFallback = true;
        }
    }

    public enum MessageWriteResult { CREATED, DUPLICATE, CONFLICT, FAILED }

    public MessageWriteResult persistMessage(String uuid, String type, String target, String sender,
            String recipient, String content, String replyToId, long timestamp) {
        synchronized (dbLock) {
            if (!isAvailable()) return MessageWriteResult.FAILED;
            MessageRecord existing = getMessage(uuid);
            if (existing != null) {
                boolean same = existing.sender().equalsIgnoreCase(sender) && existing.convType().equals(type)
                        && existing.convTarget().equalsIgnoreCase(target) && existing.content().equals(content)
                        && Objects.equals(emptyToNull(existing.recipient()), emptyToNull(recipient))
                        && Objects.equals(emptyToNull(existing.replyToId()), emptyToNull(replyToId));
                return same ? MessageWriteResult.DUPLICATE : MessageWriteResult.CONFLICT;
            }
            return saveMessage(uuid, type, target, sender, recipient, content, replyToId, timestamp)
                    ? MessageWriteResult.CREATED : MessageWriteResult.FAILED;
        }
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    public boolean isGroupMember(String group, String user) {
        if (group == null || user == null) return false;
        return getGroupMembers(group).stream().anyMatch(member -> member.equalsIgnoreCase(user));
    }

    private void createTables() throws SQLException {
        if (connection == null) return;
        synchronized (dbLock) {
            try (Statement stmt = connection.createStatement()) {
                // 1. Users
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT UNIQUE NOT NULL COLLATE NOCASE,
                        password_hash TEXT NOT NULL,
                        salt TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        avatar_color TEXT,
                        status TEXT DEFAULT 'OFFLINE',
                        created_at INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL
                    );
                """);

                // 2. Friendships
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS friendships (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user1_id INTEGER NOT NULL,
                        user2_id INTEGER NOT NULL,
                        status TEXT DEFAULT 'ACCEPTED',
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY (user1_id) REFERENCES users(id) ON DELETE CASCADE,
                        FOREIGN KEY (user2_id) REFERENCES users(id) ON DELETE CASCADE,
                        UNIQUE(user1_id, user2_id)
                    );
                """);

                // 3. Friend Requests
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS friend_requests (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        sender_id INTEGER NOT NULL,
                        receiver_id INTEGER NOT NULL,
                        status TEXT DEFAULT 'PENDING',
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
                        FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE,
                        UNIQUE(sender_id, receiver_id)
                    );
                """);

                // 4. Conversations & Messages
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT,
                        is_group INTEGER DEFAULT 0,
                        created_at INTEGER NOT NULL
                    );
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT UNIQUE NOT NULL,
                        conv_type TEXT NOT NULL,
                        conv_target TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        recipient TEXT,
                        content TEXT NOT NULL,
                        reply_to_id TEXT,
                        is_edited INTEGER DEFAULT 0,
                        is_revoked INTEGER DEFAULT 0,
                        reactions TEXT DEFAULT '',
                        status TEXT DEFAULT 'SENT',
                        timestamp INTEGER NOT NULL
                    );
                """);

                // Indexes
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_target ON chat_messages(conv_type, conv_target);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_timestamp ON chat_messages(timestamp);");

                // 5. Chat Groups & Members (Module 9)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_groups (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT UNIQUE NOT NULL COLLATE NOCASE,
                        owner TEXT NOT NULL,
                        avatar_color TEXT,
                        created_at INTEGER NOT NULL
                    );
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS group_members (
                        group_name TEXT NOT NULL COLLATE NOCASE,
                        username TEXT NOT NULL COLLATE NOCASE,
                        role TEXT DEFAULT 'MEMBER',
                        joined_at INTEGER NOT NULL,
                        PRIMARY KEY (group_name, username)
                    );
                """);
            }
        }
    }

    // ─── XÁC THỰC & BĂM MẬT KHẨU (PBKDF2WithHmacSHA256) ──────────────────────────
    public static String generateSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hashPassword(String password, String saltBase64) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Lỗi thuật toán mã hóa mật khẩu", e);
        }
    }

    public static boolean verifyPassword(String password, String saltBase64, String expectedHashBase64) {
        String computedHash = hashPassword(password, saltBase64);
        return MessageDigest.isEqual(
            Base64.getDecoder().decode(computedHash),
            Base64.getDecoder().decode(expectedHashBase64)
        );
    }

    // ─── QUẢN LÝ TÀI KHOẢN (MODULE 1) ───────────────────────────────────────────
    public boolean registerUser(String username, String password, String displayName) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            return false;
        }
        username = username.trim();
        displayName = (displayName == null || displayName.trim().isEmpty()) ? username : displayName.trim();

        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        long now = System.currentTimeMillis();

        if (isUsingMemoryFallback()) {
            String key = username.toLowerCase(Locale.ROOT);
            return inMemUsers.putIfAbsent(key, new InMemUser(inMemUserIdCounter.getAndIncrement(), username, hash, salt, displayName, "OFFLINE", now)) == null;
        }

        String sql = "INSERT INTO users (username, password_hash, salt, display_name, avatar_color, status, created_at, last_seen) VALUES (?, ?, ?, ?, ?, 'OFFLINE', ?, ?)";

        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, username);
                pstmt.setString(2, hash);
                pstmt.setString(3, salt);
                pstmt.setString(4, displayName);
                pstmt.setString(5, null);
                pstmt.setLong(6, now);
                pstmt.setLong(7, now);
                pstmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public UserRecord authenticate(String username, String password) {
        if (username == null || password == null) return null;
        username = username.trim();

        if (isUsingMemoryFallback()) {
            InMemUser u = inMemUsers.get(username.toLowerCase(Locale.ROOT));
            if (u != null && verifyPassword(password, u.salt, u.passwordHash)) {
                return new UserRecord(u.id, u.username, u.displayName, null, u.status, u.lastSeen);
            }
            return null;
        }

        String sql = "SELECT id, username, password_hash, salt, display_name, avatar_color, status, last_seen FROM users WHERE username = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, username);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String salt = rs.getString("salt");
                        String expectedHash = rs.getString("password_hash");

                        if (verifyPassword(password, salt, expectedHash)) {
                            return new UserRecord(
                                rs.getInt("id"),
                                rs.getString("username"),
                                rs.getString("display_name"),
                                rs.getString("avatar_color"),
                                rs.getString("status"),
                                rs.getLong("last_seen")
                            );
                        }
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public boolean userExists(String username) {
        if (username == null) return false;
        if (isUsingMemoryFallback()) {
            return inMemUsers.containsKey(username.trim().toLowerCase(Locale.ROOT));
        }

        String sql = "SELECT 1 FROM users WHERE username = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, username.trim());
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public UserRecord getUser(String username) {
        if (username == null) return null;
        if (isUsingMemoryFallback()) {
            InMemUser u = inMemUsers.get(username.trim().toLowerCase(Locale.ROOT));
            return u != null ? new UserRecord(u.id, u.username, u.displayName, null, u.status, u.lastSeen) : null;
        }

        String sql = "SELECT id, username, display_name, avatar_color, status, last_seen FROM users WHERE username = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, username.trim());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return new UserRecord(
                            rs.getInt("id"),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("avatar_color"),
                            rs.getString("status"),
                            rs.getLong("last_seen")
                        );
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public void updateUserStatus(String username, String status) {
        if (username == null) return;
        if (isUsingMemoryFallback()) {
            InMemUser u = inMemUsers.get(username.trim().toLowerCase(Locale.ROOT));
            if (u != null) {
                u.status = status;
                u.lastSeen = System.currentTimeMillis();
            }
            return;
        }

        String sql = "UPDATE users SET status = ?, last_seen = ? WHERE username = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, status);
                pstmt.setLong(2, System.currentTimeMillis());
                pstmt.setString(3, username.trim());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public List<UserRecord> searchUsers(String query, String excludeUsername) {
        List<UserRecord> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return results;
        String q = query.trim().toLowerCase(Locale.ROOT);

        if (isUsingMemoryFallback()) {
            for (InMemUser u : inMemUsers.values()) {
                if (excludeUsername != null && excludeUsername.equalsIgnoreCase(u.username)) continue;
                if (u.username.toLowerCase(Locale.ROOT).contains(q) || u.displayName.toLowerCase(Locale.ROOT).contains(q)) {
                    results.add(new UserRecord(u.id, u.username, u.displayName, null, u.status, u.lastSeen));
                    if (results.size() >= 20) break;
                }
            }
            return results;
        }

        String sqlQuery = "%" + q + "%";
        String sql = "SELECT id, username, display_name, avatar_color, status, last_seen FROM users WHERE (LOWER(username) LIKE ? OR LOWER(display_name) LIKE ?) AND username != ? LIMIT 20";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, sqlQuery);
                pstmt.setString(2, sqlQuery);
                pstmt.setString(3, excludeUsername != null ? excludeUsername.trim() : "");
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(new UserRecord(
                            rs.getInt("id"),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("avatar_color"),
                            rs.getString("status"),
                            rs.getLong("last_seen")
                        ));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return results;
    }

    // ─── HỆ THỐNG BẠN BÈ (MODULE 2) ─────────────────────────────────────────────
    public String getFriendRelationship(String viewer, String target) {
        synchronized (dbLock) {
            if (isFriend(viewer, target)) return "FRIEND";
            if (hasPendingFriendRequest(viewer, target)) return "OUTGOING";
            if (hasPendingFriendRequest(target, viewer)) return "INCOMING";
            return "STRANGER";
        }
    }

    private boolean hasPendingFriendRequest(String senderName, String receiverName) {
        if (senderName == null || receiverName == null) return false;
        if (isUsingMemoryFallback()) {
            return inMemFriendRequests.contains(senderName.toLowerCase(Locale.ROOT) + "->" + receiverName.toLowerCase(Locale.ROOT));
        }
        UserRecord sender = getUser(senderName);
        UserRecord receiver = getUser(receiverName);
        if (sender == null || receiver == null) return false;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM friend_requests WHERE sender_id = ? AND receiver_id = ? AND status = 'PENDING'")) {
            query.setInt(1, sender.id());
            query.setInt(2, receiver.id());
            try (ResultSet result = query.executeQuery()) { return result.next(); }
        } catch (SQLException e) { return false; }
    }

    public boolean sendFriendRequest(String senderUsername, String receiverUsername) {
        synchronized (dbLock) {
            return sendFriendRequestLocked(senderUsername, receiverUsername);
        }
    }

    private boolean sendFriendRequestLocked(String senderUsername, String receiverUsername) {
        if (senderUsername == null || receiverUsername == null) return false;
        senderUsername = senderUsername.trim();
        receiverUsername = receiverUsername.trim();
        if (senderUsername.equalsIgnoreCase(receiverUsername)) return false;

        UserRecord sender = getUser(senderUsername);
        UserRecord receiver = getUser(receiverUsername);
        if (sender == null || receiver == null) return false;
        if (!"STRANGER".equals(getFriendRelationship(senderUsername, receiverUsername))) return false;

        if (isUsingMemoryFallback()) {
            return inMemFriendRequests.add(senderUsername.toLowerCase(Locale.ROOT) + "->" + receiverUsername.toLowerCase(Locale.ROOT));
        }

        String sql = "INSERT OR IGNORE INTO friend_requests (sender_id, receiver_id, status, created_at) VALUES (?, ?, 'PENDING', ?)";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, sender.id());
                pstmt.setInt(2, receiver.id());
                pstmt.setLong(3, System.currentTimeMillis());
                return pstmt.executeUpdate() == 1;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    public boolean acceptFriendRequest(String receiverUsername, String senderUsername) {
        synchronized (dbLock) {
            return acceptFriendRequestLocked(receiverUsername, senderUsername);
        }
    }

    private boolean acceptFriendRequestLocked(String receiverUsername, String senderUsername) {
        if (!hasPendingFriendRequest(senderUsername, receiverUsername)) return false;
        UserRecord receiver = getUser(receiverUsername);
        UserRecord sender = getUser(senderUsername);
        if (receiver == null || sender == null) return false;

        if (isUsingMemoryFallback()) {
            inMemFriendRequests.remove(senderUsername.toLowerCase(Locale.ROOT) + "->" + receiverUsername.toLowerCase(Locale.ROOT));
            inMemFriendships.add(getPrivateConversationTarget(receiverUsername, senderUsername));
            return true;
        }

        synchronized (dbLock) {
            try {
                connection.setAutoCommit(false);
                String delReq = "DELETE FROM friend_requests WHERE sender_id = ? AND receiver_id = ? AND status = 'PENDING'";
                try (PreparedStatement p = connection.prepareStatement(delReq)) {
                    p.setInt(1, sender.id());
                    p.setInt(2, receiver.id());
                    if (p.executeUpdate() != 1) {
                        connection.rollback();
                        connection.setAutoCommit(true);
                        return false;
                    }
                }

                int u1 = Math.min(sender.id(), receiver.id());
                int u2 = Math.max(sender.id(), receiver.id());
                String addFriend = "INSERT OR IGNORE INTO friendships (user1_id, user2_id, status, created_at) VALUES (?, ?, 'ACCEPTED', ?)";
                try (PreparedStatement p = connection.prepareStatement(addFriend)) {
                    p.setInt(1, u1);
                    p.setInt(2, u2);
                    p.setLong(3, System.currentTimeMillis());
                    p.executeUpdate();
                }

                connection.commit();
                connection.setAutoCommit(true);
                return true;
            } catch (SQLException e) {
                try { connection.rollback(); connection.setAutoCommit(true); } catch (SQLException ignored) {}
                e.printStackTrace();
                return false;
            }
        }
    }

    public boolean rejectFriendRequest(String receiverUsername, String senderUsername) {
        synchronized (dbLock) {
            return rejectFriendRequestLocked(receiverUsername, senderUsername);
        }
    }

    private boolean rejectFriendRequestLocked(String receiverUsername, String senderUsername) {
        UserRecord receiver = getUser(receiverUsername);
        UserRecord sender = getUser(senderUsername);
        if (receiver == null || sender == null) return false;

        if (isUsingMemoryFallback()) {
            return inMemFriendRequests.remove(senderUsername.toLowerCase(Locale.ROOT) + "->" + receiverUsername.toLowerCase(Locale.ROOT));
        }

        String sql = "DELETE FROM friend_requests WHERE sender_id = ? AND receiver_id = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, sender.id());
                pstmt.setInt(2, receiver.id());
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public boolean removeFriend(String username1, String username2) {
        UserRecord u1 = getUser(username1);
        UserRecord u2 = getUser(username2);
        if (u1 == null || u2 == null) return false;

        if (isUsingMemoryFallback()) {
            return inMemFriendships.remove(getPrivateConversationTarget(username1, username2));
        }

        int id1 = Math.min(u1.id(), u2.id());
        int id2 = Math.max(u1.id(), u2.id());

        String sql = "DELETE FROM friendships WHERE user1_id = ? AND user2_id = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, id1);
                pstmt.setInt(2, id2);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public boolean isFriend(String username1, String username2) {
        UserRecord u1 = getUser(username1);
        UserRecord u2 = getUser(username2);
        if (u1 == null || u2 == null) return false;

        if (isUsingMemoryFallback()) {
            return inMemFriendships.contains(getPrivateConversationTarget(username1, username2));
        }

        int id1 = Math.min(u1.id(), u2.id());
        int id2 = Math.max(u1.id(), u2.id());

        String sql = "SELECT 1 FROM friendships WHERE user1_id = ? AND user2_id = ? AND status = 'ACCEPTED'";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, id1);
                pstmt.setInt(2, id2);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public List<FriendRecord> getFriends(String username) {
        List<FriendRecord> friends = new ArrayList<>();
        UserRecord user = getUser(username);
        if (user == null) return friends;

        if (isUsingMemoryFallback()) {
            String uLower = username.toLowerCase(Locale.ROOT);
            for (String pair : inMemFriendships) {
                String[] parts = pair.split(":");
                if (parts.length == 2) {
                    String friendUser = parts[0].equalsIgnoreCase(uLower) ? parts[1] : (parts[1].equalsIgnoreCase(uLower) ? parts[0] : null);
                    if (friendUser != null) {
                        InMemUser fu = inMemUsers.get(friendUser.toLowerCase(Locale.ROOT));
                        String dName = fu != null ? fu.displayName : friendUser;
                        String st = fu != null ? fu.status : "OFFLINE";
                        friends.add(new FriendRecord(friendUser, dName, null, st, "ONLINE".equalsIgnoreCase(st)));
                    }
                }
            }
            return friends;
        }

        String sql = """
            SELECT u.username, u.display_name, u.avatar_color, u.status
            FROM friendships f
            JOIN users u ON (u.id = CASE WHEN f.user1_id = ? THEN f.user2_id ELSE f.user1_id END)
            WHERE (f.user1_id = ? OR f.user2_id = ?) AND f.status = 'ACCEPTED'
            ORDER BY u.display_name COLLATE NOCASE ASC
        """;

        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, user.id());
                pstmt.setInt(2, user.id());
                pstmt.setInt(3, user.id());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String st = rs.getString("status");
                        boolean isOnline = "ONLINE".equalsIgnoreCase(st);
                        friends.add(new FriendRecord(
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("avatar_color"),
                            st,
                            isOnline
                        ));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return friends;
    }

    public List<String> getPendingRequests(String receiverUsername) {
        List<String> list = new ArrayList<>();
        UserRecord receiver = getUser(receiverUsername);
        if (receiver == null) return list;

        if (isUsingMemoryFallback()) {
            String suffix = "->" + receiverUsername.toLowerCase(Locale.ROOT);
            for (String req : inMemFriendRequests) {
                if (req.endsWith(suffix)) {
                    list.add(req.substring(0, req.indexOf("->")));
                }
            }
            return list;
        }

        String sql = """
            SELECT u.username FROM friend_requests fr
            JOIN users u ON u.id = fr.sender_id
            WHERE fr.receiver_id = ? AND fr.status = 'PENDING'
            ORDER BY fr.created_at DESC
        """;

        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, receiver.id());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString("username"));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return list;
    }

    // ─── NHẮN TIN THỜI GIAN THỰC & LỊCH SỬ TIN NHẮN (MODULE 3) ──────────────────
    public static String getPrivateConversationTarget(String user1, String user2) {
        if (user1 == null || user2 == null) return "";
        user1 = user1.trim().toLowerCase(Locale.ROOT);
        user2 = user2.trim().toLowerCase(Locale.ROOT);
        return user1.compareTo(user2) < 0 ? user1 + ":" + user2 : user2 + ":" + user1;
    }

    public boolean saveMessage(String uuid, String convType, String convTarget, String sender,
                               String recipient, String content, String replyToId, long timestamp) {
        if (uuid == null || sender == null || content == null) return false;
        long time = timestamp > 0 ? timestamp : System.currentTimeMillis();

        if (isUsingMemoryFallback()) {
            inMemMessages.add(new MessageRecord(uuid, convType != null ? convType : "PUBLIC", convTarget != null ? convTarget : "", sender, recipient, content, replyToId, false, false, "", "SENT", time));
            return true;
        }

        String sql = """
            INSERT INTO chat_messages (uuid, conv_type, conv_target, sender, recipient, content, reply_to_id, is_edited, is_revoked, reactions, status, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, '', 'SENT', ?)
        """;
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid);
                pstmt.setString(2, convType != null ? convType : "PUBLIC");
                pstmt.setString(3, convTarget != null ? convTarget : "");
                pstmt.setString(4, sender);
                pstmt.setString(5, recipient);
                pstmt.setString(6, content);
                pstmt.setString(7, replyToId);
                pstmt.setLong(8, time);
                pstmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    public List<MessageRecord> getMessageHistory(String convType, String convTarget, int limit, int offset) {
        List<MessageRecord> list = new ArrayList<>();
        if (convType == null || convTarget == null) return list;
        int max = limit > 0 ? Math.min(limit, 100) : 50;

        if (isUsingMemoryFallback()) {
            synchronized (inMemMessages) {
                for (MessageRecord m : inMemMessages) {
                    if (convType.equalsIgnoreCase(m.convType()) && convTarget.equalsIgnoreCase(m.convTarget())) {
                        list.add(m);
                    }
                }
            }
            list.sort(Comparator.comparingLong(MessageRecord::timestamp).reversed().thenComparing(MessageRecord::uuid));
            int from = Math.min(Math.max(0, offset), list.size());
            int to = Math.min(from + max, list.size());
            List<MessageRecord> page = new ArrayList<>(list.subList(from, to));
            Collections.reverse(page);
            return page;
        }

        int off = Math.max(0, offset);
        String sql = """
            SELECT uuid, conv_type, conv_target, sender, recipient, content, reply_to_id, is_edited, is_revoked, reactions, status, timestamp
            FROM chat_messages
            WHERE conv_type = ? AND conv_target = ?
            ORDER BY timestamp DESC, uuid ASC
            LIMIT ? OFFSET ?
        """;

        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, convType);
                pstmt.setString(2, convTarget);
                pstmt.setInt(3, max);
                pstmt.setInt(4, off);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new MessageRecord(
                            rs.getString("uuid"),
                            rs.getString("conv_type"),
                            rs.getString("conv_target"),
                            rs.getString("sender"),
                            rs.getString("recipient"),
                            rs.getString("content"),
                            rs.getString("reply_to_id"),
                            rs.getInt("is_edited") == 1,
                            rs.getInt("is_revoked") == 1,
                            rs.getString("reactions"),
                            rs.getString("status"),
                            rs.getLong("timestamp")
                        ));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        Collections.reverse(list);
        return list;
    }

    public MessageRecord getMessage(String uuid) {
        if (uuid == null) return null;
        if (isUsingMemoryFallback()) {
            synchronized (inMemMessages) {
                for (MessageRecord m : inMemMessages) {
                    if (uuid.equals(m.uuid())) return m;
                }
            }
            return null;
        }

        String sql = "SELECT uuid, conv_type, conv_target, sender, recipient, content, reply_to_id, is_edited, is_revoked, reactions, status, timestamp FROM chat_messages WHERE uuid = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return new MessageRecord(
                            rs.getString("uuid"),
                            rs.getString("conv_type"),
                            rs.getString("conv_target"),
                            rs.getString("sender"),
                            rs.getString("recipient"),
                            rs.getString("content"),
                            rs.getString("reply_to_id"),
                            rs.getInt("is_edited") == 1,
                            rs.getInt("is_revoked") == 1,
                            rs.getString("reactions"),
                            rs.getString("status"),
                            rs.getLong("timestamp")
                        );
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public boolean editMessage(String uuid, String sender, String newContent) {
        if (uuid == null || sender == null || newContent == null) return false;
        if (isUsingMemoryFallback()) {
            synchronized (inMemMessages) {
                for (int i = 0; i < inMemMessages.size(); i++) {
                    MessageRecord m = inMemMessages.get(i);
                    if (uuid.equals(m.uuid()) && sender.equalsIgnoreCase(m.sender()) && !m.isRevoked()) {
                        inMemMessages.set(i, new MessageRecord(m.uuid(), m.convType(), m.convTarget(), m.sender(), m.recipient(), newContent, m.replyToId(), true, m.isRevoked(), m.reactions(), m.status(), m.timestamp()));
                        return true;
                    }
                }
            }
            return false;
        }

        String sql = "UPDATE chat_messages SET content = ?, is_edited = 1 WHERE uuid = ? AND sender = ? AND is_revoked = 0";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, newContent);
                pstmt.setString(2, uuid);
                pstmt.setString(3, sender);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public boolean revokeMessage(String uuid, String sender) {
        if (uuid == null || sender == null) return false;
        if (isUsingMemoryFallback()) {
            synchronized (inMemMessages) {
                for (int i = 0; i < inMemMessages.size(); i++) {
                    MessageRecord m = inMemMessages.get(i);
                    if (uuid.equals(m.uuid()) && sender.equalsIgnoreCase(m.sender())) {
                        inMemMessages.set(i, new MessageRecord(m.uuid(), m.convType(), m.convTarget(), m.sender(), m.recipient(), "Tin nhắn đã bị thu hồi", m.replyToId(), m.isEdited(), true, "", m.status(), m.timestamp()));
                        return true;
                    }
                }
            }
            return false;
        }

        String sql = "UPDATE chat_messages SET content = 'Tin nhắn đã bị thu hồi', is_revoked = 1 WHERE uuid = ? AND sender = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid);
                pstmt.setString(2, sender);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public String toggleReaction(String uuid, String username, String emoji) {
        synchronized (dbLock) { return toggleReactionLocked(uuid, username, emoji); }
    }

    private String toggleReactionLocked(String uuid, String username, String emoji) {
        MessageRecord msg = getMessage(uuid);
        if (msg == null || username == null || emoji == null) return "";

        String current = msg.reactions() != null ? msg.reactions() : "";
        String updated = updateReactionString(current, username, emoji);

        if (isUsingMemoryFallback()) {
            synchronized (inMemMessages) {
                for (int i = 0; i < inMemMessages.size(); i++) {
                    if (uuid.equals(inMemMessages.get(i).uuid())) {
                        MessageRecord old = inMemMessages.get(i);
                        inMemMessages.set(i, new MessageRecord(old.uuid(), old.convType(), old.convTarget(), old.sender(), old.recipient(), old.content(), old.replyToId(), old.isEdited(), old.isRevoked(), updated, old.status(), old.timestamp()));
                        break;
                    }
                }
            }
            return updated;
        }

        String sql = "UPDATE chat_messages SET reactions = ? WHERE uuid = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, updated);
                pstmt.setString(2, uuid);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return updated;
    }

    private static String updateReactionString(String current, String username, String emoji) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        if (!current.trim().isEmpty()) {
            for (String block : current.split("\\|")) {
                int colon = block.indexOf(":");
                if (colon > 0) {
                    String em = block.substring(0, colon);
                    String usersStr = block.substring(colon + 1);
                    Set<String> set = new LinkedHashSet<>();
                    for (String u : usersStr.split(",")) {
                        if (!u.trim().isEmpty()) set.add(u.trim());
                    }
                    map.put(em, set);
                }
            }
        }

        Set<String> users = map.computeIfAbsent(emoji, k -> new LinkedHashSet<>());
        if (users.contains(username)) {
            users.remove(username);
            if (users.isEmpty()) {
                map.remove(emoji);
            }
        } else {
            users.add(username);
        }

        StringBuilder sb = new StringBuilder();
        for (var entry : map.entrySet()) {
            if (sb.length() > 0) sb.append("|");
            sb.append(entry.getKey()).append(":").append(String.join(",", entry.getValue()));
        }
        return sb.toString();
    }

    // ─── QUẢN LÝ NHÓM CHAT NÂNG CAO (MODULE 9) ────────────────────────────────
    public boolean createChatGroup(String groupName, String owner) {
        if (groupName == null || owner == null) return false;
        groupName = groupName.trim();
        owner = owner.trim();
        if (groupName.isEmpty() || owner.isEmpty()) return false;

        if (isUsingMemoryFallback()) {
            String key = groupName.toLowerCase(Locale.ROOT);
            if (inMemGroups.containsKey(key)) return false;
            inMemGroupOwners.put(key, owner);
            Set<String> set = ConcurrentHashMap.newKeySet();
            set.add(owner);
            inMemGroups.put(key, set);
            return true;
        }

        String sqlGroup = "INSERT INTO chat_groups (name, owner, avatar_color, created_at) VALUES (?, ?, ?, ?)";
        String sqlMember = "INSERT INTO group_members (group_name, username, role, joined_at) VALUES (?, ?, 'ADMIN', ?)";

        synchronized (dbLock) {
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement pstmt = connection.prepareStatement(sqlGroup)) {
                    pstmt.setString(1, groupName);
                    pstmt.setString(2, owner);
                    pstmt.setString(3, "#3B82F6");
                    pstmt.setLong(4, System.currentTimeMillis());
                    pstmt.executeUpdate();
                }

                try (PreparedStatement pstmt2 = connection.prepareStatement(sqlMember)) {
                    pstmt2.setString(1, groupName);
                    pstmt2.setString(2, owner);
                    pstmt2.setLong(3, System.currentTimeMillis());
                    pstmt2.executeUpdate();
                }

                connection.commit();
                connection.setAutoCommit(true);
                return true;
            } catch (SQLException e) {
                try { connection.rollback(); connection.setAutoCommit(true); } catch (SQLException ignored) {}
                return false;
            }
        }
    }

    public boolean addGroupMember(String groupName, String username, String role) {
        if (groupName == null || username == null) return false;
        if (isUsingMemoryFallback()) {
            Set<String> set = inMemGroups.computeIfAbsent(groupName.trim().toLowerCase(Locale.ROOT), k -> ConcurrentHashMap.newKeySet());
            return set.add(username.trim());
        }

        String sql = "INSERT OR IGNORE INTO group_members (group_name, username, role, joined_at) VALUES (?, ?, ?, ?)";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, groupName.trim());
                pstmt.setString(2, username.trim());
                pstmt.setString(3, role != null ? role : "MEMBER");
                pstmt.setLong(4, System.currentTimeMillis());
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public boolean removeGroupMember(String groupName, String username) {
        if (groupName == null || username == null) return false;
        if (isUsingMemoryFallback()) {
            Set<String> set = inMemGroups.get(groupName.trim().toLowerCase(Locale.ROOT));
            return set != null && set.remove(username.trim());
        }

        String sql = "DELETE FROM group_members WHERE group_name = ? AND username = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, groupName.trim());
                pstmt.setString(2, username.trim());
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public List<String> getGroupMembers(String groupName) {
        List<String> list = new ArrayList<>();
        if (groupName == null) return list;
        if (isUsingMemoryFallback()) {
            Set<String> set = inMemGroups.get(groupName.trim().toLowerCase(Locale.ROOT));
            return set != null ? new ArrayList<>(set) : list;
        }

        String sql = "SELECT username FROM group_members WHERE group_name = ? ORDER BY joined_at ASC";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, groupName.trim());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString("username"));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return list;
    }

    public boolean isGroupAdmin(String groupName, String username) {
        if (groupName == null || username == null) return false;
        if (isUsingMemoryFallback()) {
            String owner = inMemGroupOwners.get(groupName.trim().toLowerCase(Locale.ROOT));
            return owner != null && owner.equalsIgnoreCase(username.trim());
        }

        String sql = "SELECT 1 FROM group_members WHERE group_name = ? AND username = ? AND role = 'ADMIN'";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, groupName.trim());
                pstmt.setString(2, username.trim());
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                return false;
            }
        }
    }

    public void close() {
        synchronized (dbLock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {}
        }
    }
}
