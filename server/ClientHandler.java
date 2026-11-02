package server;

import server.db.DatabaseManager;
import server.db.DatabaseManager.FriendRecord;
import server.db.DatabaseManager.UserRecord;
import shared.protocol.Packet;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * ClientHandler - Xử lý từng kết nối Client trên Server.
 * Tích hợp Database SQLite, xác thực tài khoản, mật khẩu băm an toàn,
 * quản lý bạn bè thời gian thực và ghi Socket an toàn.
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ClientManager clientManager;
    private final RoomManager roomManager;
    private final DatabaseManager databaseManager;
    private final ChatServer chatServer;

    private BufferedReader reader;
    private PrintWriter writer;
    private final Object writeLock = new Object();

    private String username;
    private String displayName;
    private volatile boolean registered = false;
    private String currentRoom = "General";
    private final LocalDateTime connectedTime = LocalDateTime.now();
    private volatile long lastHeartbeatTime = System.currentTimeMillis();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    public ClientHandler(Socket socket, ClientManager clientManager, RoomManager roomManager, DatabaseManager databaseManager, ChatServer chatServer) {
        this.socket = socket;
        this.clientManager = clientManager;
        this.roomManager = roomManager;
        this.databaseManager = databaseManager;
        this.chatServer = chatServer;
    }

    public ClientHandler(Socket socket, ClientManager clientManager, RoomManager roomManager, ChatServer chatServer) {
        this(socket, clientManager, roomManager, new DatabaseManager(), chatServer);
    }

    public ClientHandler(Socket socket, ClientManager clientManager, RoomManager roomManager) {
        this(socket, clientManager, roomManager, new DatabaseManager(), null);
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // Xử lý xác thực ban đầu (LOGIN hoặc REGISTER)
            if (!handleInitialHandshake()) {
                return;
            }

            // Vòng lặp nhận dữ liệu sau khi đăng nhập thành công
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                lastHeartbeatTime = System.currentTimeMillis();
                processIncomingLine(line);
            }

        } catch (IOException e) {
            if (socket != null && !socket.isClosed()) {
                clientManager.logMessage(getTimestamp() + " [DISCONNECT] Client " + username + ": " + e.getMessage());
            }
        } finally {
            disconnect();
        }
    }

    private boolean handleInitialHandshake() throws IOException {
        while (true) {
            String initialLine = reader.readLine();
            if (initialLine == null || initialLine.trim().isEmpty()) {
                return false;
            }

            Packet packet = Packet.fromRawString(initialLine.trim());
            if (packet == null) return false;

            if (chatServer != null && chatServer.isLocked()) {
                sendPacket(new Packet(Packet.Type.LOGIN_FAILED, "SERVER", "Máy chủ đang bị khóa hoặc tạm dừng hoạt động."));
                clientManager.logMessage(getTimestamp() + " [LOCK] Từ chối kết nối do Server đang bị khóa.");
                return false;
            }

            // ── XỬ LÝ ĐĂNG KÝ (REGISTER) ──────────────────────────────────────
            if (packet.getType() == Packet.Type.REGISTER) {
                String regUser = packet.getSender() != null ? packet.getSender().trim() : "";
                String regPass = packet.getContent() != null ? packet.getContent().trim() : "";
                String regName = packet.getExtra() != null ? packet.getExtra().trim() : regUser;

                if (regUser.length() < 3 || regPass.length() < 4) {
                    sendPacket(new Packet(Packet.Type.REGISTER_FAILED, "SERVER", "Tên đăng nhập tối thiểu 3 ký tự, mật khẩu tối thiểu 4 ký tự."));
                    continue;
                }

                if (databaseManager.userExists(regUser)) {
                    sendPacket(new Packet(Packet.Type.REGISTER_FAILED, "SERVER", "Tên tài khoản '" + regUser + "' đã được sử dụng."));
                    continue;
                }

                boolean created = databaseManager.registerUser(regUser, regPass, regName);
                if (created) {
                    clientManager.logMessage(getTimestamp() + " [REGISTER] Đã tạo tài khoản mới: " + regUser);
                    sendPacket(new Packet(Packet.Type.REGISTER_SUCCESS, "SERVER", "Đăng ký thành công! Bạn có thể đăng nhập ngay."));
                } else {
                    sendPacket(new Packet(Packet.Type.REGISTER_FAILED, "SERVER", "Lỗi tạo tài khoản trong cơ sở dữ liệu."));
                }
                continue;
            }

            // ── XỬ LÝ ĐĂNG NHẬP (LOGIN) ───────────────────────────────────────
            if (packet.getType() == Packet.Type.LOGIN) {
                String reqUser = packet.getSender() != null ? packet.getSender().trim() : "";
                String reqPass = packet.getContent() != null ? packet.getContent() : "";

                if (reqUser.isEmpty()) {
                    sendPacket(new Packet(Packet.Type.LOGIN_FAILED, "SERVER", "Username không hợp lệ."));
                    return false;
                }

                if (clientManager.isUsernameTaken(reqUser)) {
                    sendPacket(new Packet(Packet.Type.LOGIN_FAILED, "SERVER", "Tài khoản '" + reqUser + "' đang online trên thiết bị khác."));
                    return false;
                }

                // Kiểm tra Database
                if (databaseManager.userExists(reqUser)) {
                    // Nếu tài khoản đã có mật khẩu trong DB, kiểm tra mật khẩu
                    if (reqPass != null && !reqPass.isEmpty()) {
                        UserRecord auth = databaseManager.authenticate(reqUser, reqPass);
                        if (auth == null) {
                            sendPacket(new Packet(Packet.Type.LOGIN_FAILED, "SERVER", "Mật khẩu không chính xác."));
                            continue;
                        }
                        this.displayName = auth.displayName();
                    } else {
                        // Khách đăng nhập không pass vào tài khoản đã tồn tại
                        this.displayName = reqUser;
                    }
                } else {
                    // Tự động khởi tạo tài khoản nếu chưa có
                    String defaultPass = (reqPass != null && !reqPass.isEmpty()) ? reqPass : "123456";
                    databaseManager.registerUser(reqUser, defaultPass, reqUser);
                    this.displayName = reqUser;
                }

                this.username = reqUser;
                break;
            }
        }

        // Đăng nhập thành công
        sendPacket(new Packet(Packet.Type.LOGIN_SUCCESS, "SERVER", "Đăng nhập thành công!"));
        clientManager.addClient(this);
        registered = true;
        lastHeartbeatTime = System.currentTimeMillis();

        databaseManager.updateUserStatus(username, "ONLINE");

        roomManager.joinRoom("General", this);
        currentRoom = "General";

        broadcastUserList();
        broadcastRoomList();

        // Gửi danh sách bạn bè và lời mời đang chờ cho Client
        sendFriendList();
        sendPendingFriendRequests();

        // Thông báo cho bạn bè đang online biết user này vừa online
        notifyFriendsStatusChange("ONLINE");

        clientManager.logMessage(getTimestamp() + " [LOGIN] " + username + " đã đăng nhập thành công.");
        roomManager.broadcastToRoom("General", getTimestamp() + " [SERVER] " + username + " đã tham gia phòng General.");
        return true;
    }

    private void processIncomingLine(String line) {
        Packet packet = Packet.fromRawString(line);
        if (packet == null) return;

        switch (packet.getType()) {
            case PING:
                sendPacket(new Packet(Packet.Type.PONG, "SERVER", ""));
                break;

            case PONG:
                lastHeartbeatTime = System.currentTimeMillis();
                break;

            case LOGOUT:
                disconnect();
                break;

            case MESSAGE:
                handleMessagePacket(packet);
                break;

            case PRIVATE:
                handlePrivatePacket(packet);
                break;

            case CREATE_ROOM:
                createAndJoinRoom(packet.getContent());
                break;

            case JOIN_ROOM:
                joinRoom(packet.getContent());
                break;

            case LEAVE_ROOM:
                leaveRoom();
                break;

            // ─── TÍNH NĂNG BẠN BÈ (MODULE 2) ──────────────────────────────────
            case FRIEND_REQUEST:
                handleFriendRequest(packet.getContent());
                break;

            case FRIEND_ACCEPT:
                handleFriendAccept(packet.getContent());
                break;

            case FRIEND_REJECT:
                handleFriendReject(packet.getContent());
                break;

            case FRIEND_REMOVE:
                handleFriendRemove(packet.getContent());
                break;

            case USER_SEARCH:
                handleUserSearch(packet.getContent());
                break;

            case USER_STATUS_CHANGE:
                handleUserStatusChange(packet.getContent());
                break;

            // ─── NHẮN TIN NÂNG CAO (MODULE 3) ──────────────────────────────────
            case HISTORY_REQUEST:
                handleHistoryRequest(packet);
                break;

            case TYPING:
                handleTyping(packet);
                break;

            case REACTION:
                handleReaction(packet);
                break;

            case EDIT_MESSAGE:
                handleEditMessage(packet);
                break;

            case REVOKE_MESSAGE:
                handleRevokeMessage(packet);
                break;

            // ─── SIGNALING CHO GỌI THOẠI & VIDEO (MODULE 4, 5, 6, 7) ─────────
            case CALL_OFFER:
            case CALL_ANSWER:
            case CALL_CANDIDATE:
            case CALL_END:
            case CALL_REJECT:
            case CALL_STATUS:
                handleCallSignaling(packet);
                break;

            // ─── TRUYỀN FILE THEO CHUNK (MODULE 8) ───────────────────────────
            case FILE_REQUEST:
            case FILE_START:
            case FILE_DATA:
            case FILE_END:
            case FILE_CANCEL:
                handleFileRouting(packet);
                break;

            case SYSTEM:
                handleLegacyOrSystemCommand(packet.getContent());
                break;

            default:
                break;
        }
    }

    private void handleMessagePacket(Packet packet) {
        String content = packet.getContent() != null ? packet.getContent().trim() : "";
        if (content.isEmpty()) return;

        if (content.equalsIgnoreCase("/rooms") || content.equalsIgnoreCase("ROOMS")) {
            handleRoomList();
            return;
        }
        if (content.toLowerCase().startsWith("/create ")) {
            createAndJoinRoom(content.substring(8).trim());
            return;
        }
        if (content.toLowerCase().startsWith("/join ")) {
            joinRoom(content.substring(6).trim());
            return;
        }
        if (content.equalsIgnoreCase("/leave")) {
            leaveRoom();
            return;
        }

        String uuid = packet.getId() != null && !packet.getId().isEmpty() ? packet.getId() : java.util.UUID.randomUUID().toString();
        packet.setId(uuid);
        packet.setSender(username);
        packet.setRecipient("#" + currentRoom);

        // Lưu vào SQLite Database
        databaseManager.saveMessage(uuid, "PUBLIC", currentRoom, username, null, content, packet.getExtra(), packet.getTimestamp());

        // Gửi ACK về cho người gửi
        sendPacket(new Packet(Packet.Type.MESSAGE_ACK, "SERVER", uuid));

        // Broadcast tới phòng chat
        roomManager.broadcastToRoom(currentRoom, packet.toRawString());
        clientManager.logMessage(getTimestamp() + " [" + currentRoom + "] " + username + ": " + content);
    }

    private void handlePrivatePacket(Packet packet) {
        String recipient = packet.getRecipient() != null ? packet.getRecipient().trim() : "";
        String content = packet.getContent() != null ? packet.getContent().trim() : "";

        if (recipient.isEmpty() || content.isEmpty()) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Tin nhắn riêng không hợp lệ."));
            return;
        }

        String uuid = packet.getId() != null && !packet.getId().isEmpty() ? packet.getId() : java.util.UUID.randomUUID().toString();
        packet.setId(uuid);
        packet.setSender(username);
        packet.setRecipient(recipient);

        // Lưu vào SQLite Database
        String convTarget = DatabaseManager.getPrivateConversationTarget(username, recipient);
        databaseManager.saveMessage(uuid, "PRIVATE", convTarget, username, recipient, content, packet.getExtra(), packet.getTimestamp());

        // Gửi ACK về cho người gửi
        sendPacket(new Packet(Packet.Type.MESSAGE_ACK, "SERVER", uuid));

        // Chuyển tiếp tới người nhận nếu đang online
        ClientHandler target = clientManager.getClient(recipient);
        if (target != null) {
            target.sendPacket(packet);
        }

        clientManager.logMessage(getTimestamp() + " [PRIVATE] " + username + " -> " + recipient + ": " + content);
    }

    // ─── XỬ LÝ LỊCH SỬ TIN NHẮN (MODULE 3) ───────────────────────────────────
    private void handleHistoryRequest(Packet packet) {
        String target = packet.getRecipient() != null ? packet.getRecipient().trim() : "";
        boolean isPublic = "PUBLIC".equalsIgnoreCase(packet.getExtra()) || target.startsWith("#");

        String convType = isPublic ? "PUBLIC" : "PRIVATE";
        String convTarget = isPublic ? target.replace("#", "").trim() : DatabaseManager.getPrivateConversationTarget(username, target);

        List<DatabaseManager.MessageRecord> history = databaseManager.getMessageHistory(convType, convTarget, 50, 0);

        StringBuilder sb = new StringBuilder();
        for (DatabaseManager.MessageRecord msg : history) {
            if (sb.length() > 0) sb.append(";;");
            sb.append(msg.uuid()).append("::")
              .append(msg.sender()).append("::")
              .append(msg.recipient() != null ? msg.recipient() : "").append("::")
              .append(msg.content()).append("::")
              .append(msg.replyToId() != null ? msg.replyToId() : "").append("::")
              .append(msg.isEdited() ? "1" : "0").append("::")
              .append(msg.isRevoked() ? "1" : "0").append("::")
              .append(msg.reactions() != null ? msg.reactions() : "").append("::")
              .append(msg.timestamp());
        }

        Packet resp = new Packet(Packet.Type.HISTORY_RESPONSE, "SERVER", target, sb.toString());
        resp.setExtra(convType);
        sendPacket(resp);
    }

    private void handleTyping(Packet packet) {
        String target = packet.getRecipient();
        if (target == null) return;

        Packet typingPkt = new Packet(Packet.Type.TYPING, username, target, packet.getContent());
        if (target.startsWith("#")) {
            // Typing in room
            String room = target.substring(1);
            for (ClientHandler ch : clientManager.getClients()) {
                if (!ch.getUsername().equalsIgnoreCase(username) && room.equalsIgnoreCase(ch.getCurrentRoom())) {
                    ch.sendPacket(typingPkt);
                }
            }
        } else {
            // Typing in private chat
            ClientHandler ch = clientManager.getClient(target);
            if (ch != null) {
                ch.sendPacket(typingPkt);
            }
        }
    }

    private void handleReaction(Packet packet) {
        String uuid = packet.getId();
        String emoji = packet.getContent();
        String target = packet.getRecipient();
        if (uuid == null || emoji == null) return;

        String allReactions = databaseManager.toggleReaction(uuid, username, emoji);

        Packet rxPkt = new Packet(Packet.Type.REACTION, username, target, emoji);
        rxPkt.setId(uuid);
        rxPkt.setExtra(allReactions);

        if (target != null && target.startsWith("#")) {
            roomManager.broadcastToRoom(target.substring(1), rxPkt.toRawString());
        } else if (target != null) {
            ClientHandler ch = clientManager.getClient(target);
            if (ch != null) ch.sendPacket(rxPkt);
            sendPacket(rxPkt);
        }
    }

    private void handleEditMessage(Packet packet) {
        String uuid = packet.getId();
        String newContent = packet.getContent();
        String target = packet.getRecipient();
        if (uuid == null || newContent == null) return;

        boolean ok = databaseManager.editMessage(uuid, username, newContent);
        if (ok) {
            Packet editPkt = new Packet(Packet.Type.EDIT_MESSAGE, username, target, newContent);
            editPkt.setId(uuid);

            if (target != null && target.startsWith("#")) {
                roomManager.broadcastToRoom(target.substring(1), editPkt.toRawString());
            } else if (target != null) {
                ClientHandler ch = clientManager.getClient(target);
                if (ch != null) ch.sendPacket(editPkt);
                sendPacket(editPkt);
            }
        }
    }

    private void handleRevokeMessage(Packet packet) {
        String uuid = packet.getId();
        String target = packet.getRecipient();
        if (uuid == null) return;

        boolean ok = databaseManager.revokeMessage(uuid, username);
        if (ok) {
            Packet revPkt = new Packet(Packet.Type.REVOKE_MESSAGE, username, target, "Tin nhắn đã bị thu hồi");
            revPkt.setId(uuid);

            if (target != null && target.startsWith("#")) {
                roomManager.broadcastToRoom(target.substring(1), revPkt.toRawString());
            } else if (target != null) {
                ClientHandler ch = clientManager.getClient(target);
                if (ch != null) ch.sendPacket(revPkt);
                sendPacket(revPkt);
            }
        }
    }

    // ─── SIGNALING CHO GỌI THOẠI & VIDEO (MODULE 4, 5, 6, 7) ─────────────────
    private void handleCallSignaling(Packet packet) {
        String recipient = packet.getRecipient();
        if (recipient == null || recipient.trim().isEmpty()) return;
        packet.setSender(username);
        ClientHandler target = clientManager.getClient(recipient.trim());
        if (target != null) {
            target.sendPacket(packet);
        } else {
            Packet rejected = new Packet(Packet.Type.CALL_REJECT, "SERVER", username, "Người dùng không trực tuyến");
            rejected.setId(packet.getId());
            rejected.setExtra("OFFLINE");
            sendPacket(rejected);
        }
    }

    // ─── ĐIỀU PHỐI FILE CHUNKS (MODULE 8) ────────────────────────────────────
    private void handleFileRouting(Packet packet) {
        String recipient = packet.getRecipient();
        if (recipient == null || recipient.trim().isEmpty()) return;
        packet.setSender(username);
        if (recipient.startsWith("#")) {
            roomManager.broadcastToRoom(recipient.substring(1), packet.toRawString());
        } else {
            ClientHandler target = clientManager.getClient(recipient.trim());
            if (target != null) {
                target.sendPacket(packet);
            }
        }
    }

    // ─── XỬ LÝ QUAN HỆ BẠN BÈ ────────────────────────────────────────────────
    private void handleFriendRequest(String targetUsername) {
        if (targetUsername == null || targetUsername.trim().isEmpty()) return;
        targetUsername = targetUsername.trim();

        if (targetUsername.equalsIgnoreCase(username)) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Bạn không thể tự kết bạn với chính mình."));
            return;
        }

        if (!databaseManager.userExists(targetUsername)) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Không tìm thấy người dùng: @" + targetUsername));
            return;
        }

        if (databaseManager.isFriend(username, targetUsername)) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Bạn và @" + targetUsername + " đã là bạn bè."));
            return;
        }

        boolean sent = databaseManager.sendFriendRequest(username, targetUsername);
        if (sent) {
            sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", "✓ Đã gửi lời mời kết bạn tới @" + targetUsername));

            // Nếu người nhận đang online, gửi thông báo trực tiếp thời gian thực
            ClientHandler targetClient = clientManager.getClient(targetUsername);
            if (targetClient != null) {
                targetClient.sendPacket(new Packet(Packet.Type.FRIEND_REQUEST, username, ""));
                targetClient.sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", "🔔 @" + username + " vừa gửi cho bạn một lời mời kết bạn!"));
                targetClient.sendPendingFriendRequests();
            }
        } else {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Không thể gửi lời mời kết bạn (hoặc lời mời đã tồn tại)."));
        }
    }

    private void handleFriendAccept(String senderUsername) {
        if (senderUsername == null || senderUsername.trim().isEmpty()) return;
        senderUsername = senderUsername.trim();

        boolean ok = databaseManager.acceptFriendRequest(username, senderUsername);
        if (ok) {
            sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", "✓ Bạn và @" + senderUsername + " đã trở thành bạn bè!"));
            sendFriendList();

            ClientHandler senderClient = clientManager.getClient(senderUsername);
            if (senderClient != null) {
                senderClient.sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", "🎉 @" + username + " đã chấp nhận lời mời kết bạn của bạn!"));
                senderClient.sendFriendList();
            }
        }
    }

    private void handleFriendReject(String senderUsername) {
        if (senderUsername == null || senderUsername.trim().isEmpty()) return;
        databaseManager.rejectFriendRequest(username, senderUsername.trim());
        sendPendingFriendRequests();
    }

    private void handleFriendRemove(String targetUsername) {
        if (targetUsername == null || targetUsername.trim().isEmpty()) return;
        targetUsername = targetUsername.trim();

        boolean ok = databaseManager.removeFriend(username, targetUsername);
        if (ok) {
            sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", "Đã hủy kết bạn với @" + targetUsername));
            sendFriendList();

            ClientHandler targetClient = clientManager.getClient(targetUsername);
            if (targetClient != null) {
                targetClient.sendFriendList();
            }
        }
    }

    private void handleUserSearch(String query) {
        List<UserRecord> results = databaseManager.searchUsers(query, username);
        StringBuilder sb = new StringBuilder();
        for (UserRecord u : results) {
            if (sb.length() > 0) sb.append(";");
            boolean isFr = databaseManager.isFriend(username, u.username());
            sb.append(u.username()).append(",")
              .append(u.displayName()).append(",")
              .append(isFr ? "FRIEND" : "STRANGER");
        }
        sendPacket(new Packet(Packet.Type.USER_SEARCH_RESULT, "SERVER", sb.toString()));
    }

    private void handleUserStatusChange(String status) {
        if (status == null) return;
        databaseManager.updateUserStatus(username, status);
        notifyFriendsStatusChange(status);
    }

    public void sendFriendList() {
        List<FriendRecord> friends = databaseManager.getFriends(username);
        StringBuilder sb = new StringBuilder();
        for (FriendRecord f : friends) {
            if (sb.length() > 0) sb.append(";");
            boolean isOnline = clientManager.getClient(f.username()) != null;
            sb.append(f.username()).append(",")
              .append(f.displayName()).append(",")
              .append(isOnline ? "ONLINE" : "OFFLINE");
        }
        sendPacket(new Packet(Packet.Type.FRIEND_LIST, "SERVER", sb.toString()));
    }

    public void sendPendingFriendRequests() {
        List<String> pending = databaseManager.getPendingRequests(username);
        String list = String.join(",", pending);
        sendPacket(new Packet(Packet.Type.FRIEND_PENDING_LIST, "SERVER", list));
    }

    private void notifyFriendsStatusChange(String status) {
        List<FriendRecord> friends = databaseManager.getFriends(username);
        for (FriendRecord f : friends) {
            ClientHandler friendClient = clientManager.getClient(f.username());
            if (friendClient != null) {
                Packet p = new Packet(Packet.Type.USER_STATUS_CHANGE, username, status);
                friendClient.sendPacket(p);
                friendClient.sendFriendList();
            }
        }
    }

    private void handleLegacyOrSystemCommand(String command) {
        if (command == null) return;
        command = command.trim();

        if (command.equalsIgnoreCase("/quit") || command.equalsIgnoreCase("LOGOUT")) {
            disconnect();
        } else if (command.equalsIgnoreCase("ROOMS") || command.equalsIgnoreCase("/rooms")) {
            handleRoomList();
        } else if (command.startsWith("/pm ")) {
            handleOldPrivateMessage(command);
        } else {
            Packet p = new Packet(Packet.Type.MESSAGE, username, command);
            roomManager.broadcastToRoom(currentRoom, p.toRawString());
        }
    }

    private void handleOldPrivateMessage(String message) {
        String content = message.substring(4).trim();
        int index = content.indexOf(" ");
        if (index == -1) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Cú pháp: /pm <username> <message>"));
            return;
        }
        String targetUsername = content.substring(0, index).trim();
        String privateMessage = content.substring(index + 1).trim();
        Packet p = new Packet(Packet.Type.PRIVATE, username, targetUsername, privateMessage);
        handlePrivatePacket(p);
    }

    private void broadcastUserList() {
        StringBuilder users = new StringBuilder();
        for (String name : clientManager.getUsernames()) {
            if (users.length() > 0) users.append(",");
            users.append(name);
        }
        Packet p = new Packet(Packet.Type.USER_LIST, "SERVER", users.toString());
        clientManager.broadcast(p.toRawString());
    }

    private void handleRoomList() {
        String list = String.join(",", roomManager.getRoomNames());
        Packet p = new Packet(Packet.Type.ROOM_LIST, "SERVER", list);
        sendPacket(p);
    }

    private void createAndJoinRoom(String roomName) {
        if (roomName == null || roomName.trim().isEmpty()) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Tên nhóm không hợp lệ."));
            return;
        }
        roomName = roomName.trim();
        roomManager.createRoom(roomName);
        databaseManager.createChatGroup(roomName, username);
        broadcastRoomList();
        joinRoom(roomName);
    }

    private void broadcastRoomList() {
        String list = String.join(",", roomManager.getRoomNames());
        Packet p = new Packet(Packet.Type.ROOM_LIST, "SERVER", list);
        clientManager.broadcast(p.toRawString());
    }

    private void joinRoom(String roomName) {
        if (roomName == null || roomName.trim().isEmpty()) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Tên phòng không hợp lệ."));
            return;
        }
        roomName = roomName.trim();
        if (!roomManager.roomExists(roomName)) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Phòng '" + roomName + "' không tồn tại."));
            return;
        }
        if (currentRoom.equalsIgnoreCase(roomName)) {
            sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", "Bạn đang ở phòng " + roomName));
            return;
        }

        databaseManager.addGroupMember(roomName, username, "MEMBER");

        String oldRoom = currentRoom;
        if (!roomManager.joinRoom(roomName, this)) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Không thể tham gia phòng."));
            return;
        }

        currentRoom = roomName;
        String time = getTimestamp();
        clientManager.logMessage(time + " [ROOM] " + username + " chuyển từ " + oldRoom + " -> " + roomName);
        sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", time + " Bạn đã vào phòng " + roomName));
        roomManager.broadcastToRoom(roomName, time + " [SERVER] " + username + " đã tham gia phòng.");
    }

    private void leaveRoom() {
        if (currentRoom.equalsIgnoreCase("General")) {
            sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", "Bạn đang ở phòng General."));
            return;
        }
        String oldRoom = currentRoom;
        roomManager.joinRoom("General", this);
        currentRoom = "General";

        String time = getTimestamp();
        clientManager.logMessage(time + " [ROOM] " + username + " rời phòng " + oldRoom + " -> General");
        sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", time + " Bạn đã trở về phòng General."));
        roomManager.broadcastToRoom("General", time + " [SERVER] " + username + " đã tham gia phòng General.");
    }

    // ─── Ghi Socket Thread-Safe ────────────────────────────────────────────────
    public void sendPacket(Packet packet) {
        if (packet != null) {
            sendMessage(packet.toRawString());
        }
    }

    public void sendMessage(String rawMessage) {
        if (writer != null) {
            synchronized (writeLock) {
                writer.println(rawMessage);
                writer.flush();
            }
        }
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    private String getTimestamp() {
        return "[" + LocalDateTime.now().format(TIME_FORMAT) + "]";
    }

    public String getUsername() { return username; }
    public String getDisplayName() { return displayName != null ? displayName : username; }
    public String getCurrentRoom() { return currentRoom; }

    public String getClientIp() {
        if (socket != null && socket.getInetAddress() != null) {
            String ip = socket.getInetAddress().getHostAddress();
            return "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
        }
        return "N/A";
    }

    public int getClientPort() {
        return socket != null ? socket.getPort() : 0;
    }

    public String getConnectedTime() {
        return connectedTime.format(DATE_TIME_FORMAT);
    }

    public void closeConnection() {
        try {
            sendPacket(new Packet(Packet.Type.KICK, "SERVER", "Máy chủ đã dừng kết nối."));
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    public void kick(String reason) {
        try {
            sendPacket(new Packet(Packet.Type.KICK, "SERVER", reason));
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    public synchronized void disconnect() {
        if (registered) {
            String leaveMsg = getTimestamp() + " [SERVER] " + username + " đã rời phòng chat.";
            clientManager.logMessage(leaveMsg);
            roomManager.broadcastToRoom(currentRoom, leaveMsg);
            roomManager.removeClient(this);
            clientManager.removeClient(this);
            registered = false;

            databaseManager.updateUserStatus(username, "OFFLINE");
            notifyFriendsStatusChange("OFFLINE");
            broadcastUserList();
        }

        try {
            synchronized (writeLock) {
                if (writer != null) writer.close();
            }
            if (reader != null) reader.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }
}
