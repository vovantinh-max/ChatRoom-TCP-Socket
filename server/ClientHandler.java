package server;

import server.db.DatabaseManager;
import server.db.DatabaseManager.FriendRecord;
import server.db.DatabaseManager.UserRecord;
import shared.protocol.Packet;
import shared.protocol.BoundedLineReader;
import shared.protocol.ProtocolLimits;
import shared.protocol.HistoryCodec;
import shared.protocol.ContactCodec;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    private final CountDownLatch finished = new CountDownLatch(1);

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
            socket.setSoTimeout(15000);

            reader = BoundedLineReader.create(socket.getInputStream());
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // Xử lý xác thực ban đầu (LOGIN hoặc REGISTER)
            if (!handleInitialHandshake()) {
                return;
            }
            socket.setSoTimeout(60000);

            // Vòng lặp nhận dữ liệu sau khi đăng nhập thành công
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                lastHeartbeatTime = System.currentTimeMillis();
                processIncomingLine(line);
            }

        } catch (Throwable e) {
            if (socket != null && !socket.isClosed()) {
                clientManager.logMessage(getTimestamp() + " [DISCONNECT] Client " + username + ": " + e.getMessage());
            }
        } finally {
            try {
                disconnect();
            } finally {
                if (chatServer != null) chatServer.onClientDisconnected(this);
                finished.countDown();
            }
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
                try {
                    String regUser = packet.getSender() != null ? packet.getSender().trim() : "";
                    String regPass = packet.getContent() != null ? packet.getContent() : "";
                    String regName = packet.getExtra() != null ? packet.getExtra().trim() : regUser;

                    if (!regUser.matches("[A-Za-z0-9_.-]{3,32}") || regPass.length() < 4 || regPass.length() > 256 || regName.length() > 80) {
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
                } catch (Throwable t) {
                    System.err.println("[ClientHandler] Lỗi đăng ký: " + t.getMessage());
                    sendPacket(new Packet(Packet.Type.REGISTER_FAILED, "SERVER", "Lỗi máy chủ: " + t.getMessage()));
                }
                continue;
            }

            // ── XỬ LÝ ĐĂNG NHẬP (LOGIN) ───────────────────────────────────────
            if (packet.getType() == Packet.Type.LOGIN) {
                try {
                    String reqUser = packet.getSender() != null ? packet.getSender().trim() : "";
                    String reqPass = packet.getContent() != null ? packet.getContent() : "";

                    if (!reqUser.matches("[A-Za-z0-9_.-]{3,32}") || reqPass.length() > 256) {
                        sendPacket(new Packet(Packet.Type.LOGIN_FAILED, "SERVER", "Username không hợp lệ."));
                        return false;
                    }

                    if (clientManager.isUsernameTaken(reqUser)) {
                        sendPacket(new Packet(Packet.Type.LOGIN_FAILED, "SERVER", "Tài khoản '" + reqUser + "' đang online trên thiết bị khác."));
                        return false;
                    }

                    UserRecord auth = reqPass.isEmpty() ? null : databaseManager.authenticate(reqUser, reqPass);
                    if (auth == null) {
                        sendPacket(new Packet(Packet.Type.LOGIN_FAILED, "SERVER", "Tài khoản hoặc mật khẩu không chính xác."));
                        return false;
                    }

                    this.username = auth.username();
                    this.displayName = auth.displayName();
                    break;
                } catch (Throwable t) {
                    System.err.println("[ClientHandler] Lỗi đăng nhập: " + t.getMessage());
                    sendPacket(new Packet(Packet.Type.LOGIN_FAILED, "SERVER", "Lỗi máy chủ: " + t.getMessage()));
                    return false;
                }
            }
        }

        // Đăng nhập thành công
        // Keep broadcasts behind LOGIN_SUCCESS while publishing this session.
        synchronized (writeLock) {
            synchronized (clientManager) {
                if (!clientManager.tryAddClient(this)) {
                    sendPacket(new Packet(Packet.Type.LOGIN_FAILED, "SERVER", "Tài khoản đang online trên thiết bị khác."));
                    return false;
                }
                registered = true;
            }
            sendPacket(new Packet(Packet.Type.LOGIN_SUCCESS, "SERVER", "Đăng nhập thành công!"));
        }
        lastHeartbeatTime = System.currentTimeMillis();

        databaseManager.updateUserStatus(username, "ONLINE");

        databaseManager.addGroupMember("General", username, "MEMBER");
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
        try {
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
                handleUserSearch(packet);
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
        } catch (Throwable t) {
            System.err.println("[ClientHandler] Lỗi khi xử lý gói tin từ " + username + ": " + t.getMessage());
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

        if (packet.getRecipient() != null && !packet.getRecipient().isEmpty()
                && !packet.getRecipient().equalsIgnoreCase("#" + currentRoom)) {
            messageFailed(packet, "Hãy quay lại phòng gốc trước khi gửi lại tin nhắn."); return;
        }
        String uuid = packet.getId() != null && !packet.getId().isEmpty() ? packet.getId() : java.util.UUID.randomUUID().toString();
        packet.setId(uuid);
        packet.setSender(username);
        packet.setRecipient("#" + currentRoom);

        packet.setContent(content);
        if (!validateMessage(packet, currentRoom, true)) return;
        DatabaseManager.MessageWriteResult saved = persistMessage(packet, "PUBLIC", currentRoom, null);
        if (saved == DatabaseManager.MessageWriteResult.CREATED) {
            roomManager.broadcastToRoom(currentRoom, packet.toRawString());
        }
    }

    private boolean validateMessage(Packet packet, String target, boolean isPublic) {
        String content = packet.getContent();
        if (content == null || content.isBlank() || content.length() > ProtocolLimits.MAX_MESSAGE_CHARS) {
            messageFailed(packet, "Tin nhắn phải có từ 1 đến " + ProtocolLimits.MAX_MESSAGE_CHARS + " ký tự.");
            return false;
        }
        String reply = packet.getExtra();
        if (reply != null && !reply.isBlank()) {
            DatabaseManager.MessageRecord original = databaseManager.getMessage(reply);
            String conversation = isPublic ? target : DatabaseManager.getPrivateConversationTarget(username, target);
            if (original == null || !original.convType().equals(isPublic ? "PUBLIC" : "PRIVATE")
                    || !original.convTarget().equalsIgnoreCase(conversation) || !canReadMessage(original)) {
                messageFailed(packet, "Tin nhắn trả lời không thuộc cuộc trò chuyện này.");
                return false;
            }
        }
        return true;
    }

    private void messageFailed(Packet request, String detail) {
        Packet failure = new Packet(Packet.Type.MESSAGE_FAILED, "SERVER", request.getRecipient(), detail);
        failure.setId(request.getId());
        sendPacket(failure);
    }

    private DatabaseManager.MessageWriteResult persistMessage(Packet packet, String type, String target, String recipient) {
        packet.setTimestamp(System.currentTimeMillis());
        DatabaseManager.MessageWriteResult result = databaseManager.persistMessage(packet.getId(), type, target,
                username, recipient, packet.getContent(), packet.getExtra(), packet.getTimestamp());
        if (result == DatabaseManager.MessageWriteResult.CREATED || result == DatabaseManager.MessageWriteResult.DUPLICATE) {
            Packet ack = new Packet(Packet.Type.MESSAGE_ACK, "SERVER", packet.getRecipient(), packet.getId());
            ack.setId(packet.getId());
            ack.setExtra("STORED");
            sendPacket(ack);
        } else messageFailed(packet, result == DatabaseManager.MessageWriteResult.CONFLICT
                ? "Mã tin nhắn đã được dùng cho nội dung khác." : "Chưa lưu được tin nhắn. Vui lòng gửi lại.");
        return result;
    }

    private void handlePrivatePacket(Packet packet) {
        String recipient = packet.getRecipient() == null ? "" : packet.getRecipient().trim();
        UserRecord other = databaseManager.getUser(recipient);
        if (other == null) { messageFailed(packet, "Không tìm thấy người nhận."); return; }
        recipient = other.username();
        packet.setRecipient(recipient);
        packet.setSender(username);
        packet.setContent(packet.getContent() == null ? "" : packet.getContent().trim());
        if (!validateMessage(packet, recipient, false)) return;
        String conversation = DatabaseManager.getPrivateConversationTarget(username, recipient);
        if (persistMessage(packet, "PRIVATE", conversation, recipient) == DatabaseManager.MessageWriteResult.CREATED) {
            ClientHandler peer = clientManager.getClient(recipient);
            if (peer != null) peer.sendPacket(packet);
        }
    }

    private void error(String message) { sendPacket(new Packet(Packet.Type.ERROR, "SERVER", message)); }

    private boolean canReadRoom(String room) {
        return roomManager.roomExists(room) && currentRoom.equalsIgnoreCase(room)
                && databaseManager.isGroupMember(room, username);
    }

    private boolean canReadMessage(DatabaseManager.MessageRecord message) {
        return "PUBLIC".equals(message.convType()) ? canReadRoom(message.convTarget())
                : username.equalsIgnoreCase(message.sender()) || username.equalsIgnoreCase(message.recipient());
    }

    private DatabaseManager.MessageRecord authorizedMessage(Packet packet, boolean ownerOnly) {
        DatabaseManager.MessageRecord message = databaseManager.getMessage(packet.getId());
        if (message == null || !canReadMessage(message) || (ownerOnly && !username.equalsIgnoreCase(message.sender()))) {
            error("Bạn không có quyền thao tác với tin nhắn này.");
            return null;
        }
        return message;
    }

    private void routeMessageChange(DatabaseManager.MessageRecord original, Packet update) {
        if ("PUBLIC".equals(original.convType())) {
            update.setRecipient("#" + original.convTarget());
            roomManager.broadcastToRoom(original.convTarget(), update.toRawString());
            if (!currentRoom.equalsIgnoreCase(original.convTarget())) sendPacket(update);
        } else {
            String peerName = original.sender().equalsIgnoreCase(username) ? original.recipient() : original.sender();
            update.setRecipient(peerName);
            ClientHandler peer = clientManager.getClient(peerName);
            if (peer != null && peer != this) peer.sendPacket(update);
            sendPacket(update);
        }
    }

    private void handleHistoryRequest(Packet packet) {
        String target = packet.getRecipient() == null ? "" : packet.getRecipient().trim();
        boolean isPublic = target.startsWith("#") || "PUBLIC".equalsIgnoreCase(packet.getExtra());
        String conversation = isPublic ? (target.startsWith("#") ? target.substring(1) : target)
                : DatabaseManager.getPrivateConversationTarget(username, target);
        if ((isPublic && !canReadRoom(conversation)) || (!isPublic && !databaseManager.userExists(target))) {
            error("Bạn không có quyền xem lịch sử cuộc trò chuyện này."); return;
        }
        int offset = 0;
        try { if (packet.getContent() != null && !packet.getContent().isBlank()) offset = Integer.parseInt(packet.getContent()); }
        catch (NumberFormatException e) { error("Trang lịch sử không hợp lệ."); return; }
        if (offset < 0 || offset > 100000) { error("Trang lịch sử không hợp lệ."); return; }
        String type = isPublic ? "PUBLIC" : "PRIVATE";
        List<DatabaseManager.MessageRecord> history = databaseManager.getMessageHistory(type, conversation, 50, offset);
        List<String[]> rows = new java.util.ArrayList<>();
        for (DatabaseManager.MessageRecord message : history) rows.add(new String[]{message.uuid(), message.sender(),
                isPublic ? "#" + conversation : message.recipient(), message.content(), message.replyToId(),
                message.isEdited() ? "1" : "0", message.isRevoked() ? "1" : "0", message.reactions(), Long.toString(message.timestamp())});
        Packet response = new Packet(Packet.Type.HISTORY_RESPONSE, "SERVER", target, HistoryCodec.encode(rows));
        response.setExtra(HistoryCodec.FORMAT + ":" + type);
        sendPacket(response);
    }

    private void handleTyping(Packet packet) {
        String target = packet.getRecipient();
        if (target == null) return;
        Packet update = new Packet(Packet.Type.TYPING, username, target, "START".equals(packet.getContent()) ? "START" : "STOP");
        if (target.startsWith("#")) {
            String room = target.substring(1);
            if (!currentRoom.equalsIgnoreCase(room) || !canReadRoom(room)) { error("Bạn chưa tham gia phòng này."); return; }
            for (ClientHandler peer : clientManager.getClients())
                if (peer != this && room.equalsIgnoreCase(peer.getCurrentRoom())) peer.sendPacket(update);
        } else {
            ClientHandler peer = clientManager.getClient(target);
            if (peer != null) peer.sendPacket(update);
        }
    }

    private void handleReaction(Packet packet) {
        DatabaseManager.MessageRecord message = authorizedMessage(packet, false);
        if (message == null) return;
        String emoji = packet.getContent();
        if (message.isRevoked() || !List.of("❤️", "👍", "😂", "😮", "😢", "🔥").contains(emoji)) {
            error("Cảm xúc không hợp lệ hoặc tin nhắn đã thu hồi."); return;
        }
        Packet update = new Packet(Packet.Type.REACTION, username, "", emoji);
        update.setId(message.uuid());
        update.setExtra(databaseManager.toggleReaction(message.uuid(), username, emoji));
        routeMessageChange(message, update);
    }

    private void handleEditMessage(Packet packet) {
        DatabaseManager.MessageRecord message = authorizedMessage(packet, true);
        if (message == null) return;
        String content = packet.getContent();
        if (content == null || content.isBlank() || content.length() > ProtocolLimits.MAX_MESSAGE_CHARS) { error("Nội dung chỉnh sửa không hợp lệ."); return; }
        if (!databaseManager.editMessage(message.uuid(), username, content)) { error("Không thể sửa tin nhắn."); return; }
        Packet update = new Packet(Packet.Type.EDIT_MESSAGE, username, "", content);
        update.setId(message.uuid());
        routeMessageChange(message, update);
    }

    private void handleRevokeMessage(Packet packet) {
        DatabaseManager.MessageRecord message = authorizedMessage(packet, true);
        if (message == null) return;
        if (!databaseManager.revokeMessage(message.uuid(), username)) { error("Không thể thu hồi tin nhắn."); return; }
        Packet update = new Packet(Packet.Type.REVOKE_MESSAGE, username, "", "Tin nhắn đã bị thu hồi");
        update.setId(message.uuid());
        routeMessageChange(message, update);
    }

    // ─── SIGNALING CHO GỌI THOẠI & VIDEO (MODULE 4, 5, 6, 7) ─────────────────
    private void handleCallSignaling(Packet packet) {
        String recipient = packet.getRecipient();
        if (recipient == null || recipient.trim().isEmpty()) return;
        if (recipient.trim().equalsIgnoreCase(username)) return;
        if (packet.getType() == Packet.Type.CALL_OFFER || packet.getType() == Packet.Type.CALL_ANSWER) {
            try {
                var endpoint = shared.protocol.CallEndpoint.parse(packet.getExtra());
                packet.setExtra(endpoint.ports() + "," + socket.getInetAddress().getHostAddress());
            } catch (IllegalArgumentException e) {
                return;
            }
        }
        packet.setSender(username);
        ClientHandler target = clientManager.getClient(recipient.trim());
        if (target != null) {
            target.sendPacket(packet);
        } else if (packet.getType() == Packet.Type.CALL_OFFER) {
            Packet rejected = new Packet(Packet.Type.CALL_REJECT, "SERVER", username, "Người dùng không trực tuyến");
            rejected.setId(packet.getId());
            rejected.setExtra("OFFLINE");
            sendPacket(rejected);
        }
    }

    // ─── ĐIỀU PHỐI FILE CHUNKS (MODULE 8) ────────────────────────────────────
    private void handleFileRouting(Packet packet) {
        String recipient = packet.getRecipient();
        if (recipient == null || recipient.isBlank()) return;
        packet.setSender(username);
        if (recipient.startsWith("#")) {
            String room = recipient.substring(1);
            if (!currentRoom.equalsIgnoreCase(room) || !canReadRoom(room)) { error("Bạn chưa tham gia phòng này."); return; }
            roomManager.broadcastToRoom(room, packet.toRawString());
        } else {
            ClientHandler peer = clientManager.getClient(recipient.trim());
            if (peer != null) peer.sendPacket(packet);
        }
    }

    // ─── XỬ LÝ QUAN HỆ BẠN BÈ ────────────────────────────────────────────────
    private void sendFriendResult(String operation, String target, boolean success, String message) {
        Packet result = new Packet(Packet.Type.FRIEND_ACTION_RESULT, "SERVER", target, message);
        result.setExtra(operation + ":" + (success ? "SUCCESS" : "ERROR"));
        sendPacket(result);
    }

    private void handleFriendRequest(String targetUsername) {
        String target = targetUsername == null ? "" : targetUsername.trim();
        boolean success = databaseManager.sendFriendRequest(username, target);
        sendFriendResult("REQUEST", target, success, success
                ? "Đã gửi lời mời kết bạn tới @" + target
                : "Không thể gửi: tài khoản không tồn tại, đã là bạn bè hoặc đã có lời mời đang chờ.");
        if (success) {
            ClientHandler receiver = clientManager.getClient(target);
            if (receiver != null) {
                receiver.sendPacket(new Packet(Packet.Type.FRIEND_REQUEST, username, ""));
                receiver.sendPendingFriendRequests();
            }
        }
    }

    private void handleFriendAccept(String senderUsername) {
        String target = senderUsername == null ? "" : senderUsername.trim();
        boolean success = databaseManager.acceptFriendRequest(username, target);
        sendFriendResult("ACCEPT", target, success, success
                ? "Bạn và @" + target + " đã trở thành bạn bè."
                : "Lời mời không còn tồn tại hoặc đã được xử lý.");
        sendPendingFriendRequests();
        if (success) {
            sendFriendList();
            ClientHandler sender = clientManager.getClient(target);
            if (sender != null) {
                sender.sendFriendList();
                sender.sendPendingFriendRequests();
                sender.sendPacket(new Packet(Packet.Type.SYSTEM, "SERVER", "@" + username + " đã chấp nhận lời mời kết bạn."));
            }
        }
    }

    private void handleFriendReject(String senderUsername) {
        String target = senderUsername == null ? "" : senderUsername.trim();
        boolean success = databaseManager.rejectFriendRequest(username, target);
        sendFriendResult("REJECT", target, success, success
                ? "Đã từ chối lời mời từ @" + target : "Lời mời không còn tồn tại hoặc đã được xử lý.");
        sendPendingFriendRequests();
    }

    private void handleFriendRemove(String targetUsername) {
        String target = targetUsername == null ? "" : targetUsername.trim();
        boolean success = databaseManager.removeFriend(username, target);
        sendFriendResult("REMOVE", target, success, success
                ? "Đã hủy kết bạn với @" + target : "Quan hệ bạn bè không còn tồn tại.");
        sendFriendList();
        if (success) {
            ClientHandler other = clientManager.getClient(target);
            if (other != null) other.sendFriendList();
        }
    }

    private void handleUserSearch(Packet request) {
        String query = request.getContent();
        List<UserRecord> results = databaseManager.searchUsers(query, username);
        StringBuilder sb = new StringBuilder();
        for (UserRecord u : results) {
            if (sb.length() > 0) sb.append(";");
            String relationship = databaseManager.getFriendRelationship(username, u.username());
            sb.append(ContactCodec.encode(u.username())).append(",")
              .append(ContactCodec.encode(u.displayName())).append(",")
              .append(relationship);
        }
        Packet response = new Packet(Packet.Type.USER_SEARCH_RESULT, "SERVER", sb.toString());
        response.setExtra(ContactCodec.FORMAT);
        response.setId(request.getId());
        sendPacket(response);
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
            sb.append(ContactCodec.encode(f.username())).append(",")
              .append(ContactCodec.encode(f.displayName())).append(",")
              .append(isOnline ? "ONLINE" : "OFFLINE");
        }
        Packet friendPacket = new Packet(Packet.Type.FRIEND_LIST, "SERVER", sb.toString());
        friendPacket.setExtra(ContactCodec.FORMAT);
        sendPacket(friendPacket);
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
            handleMessagePacket(p);
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
        if (roomName == null || !roomName.trim().matches("[\\p{L}\\p{N} _.-]{1,64}")) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Tên nhóm không hợp lệ."));
            return;
        }
        roomName = roomName.trim();
        if (roomManager.roomExists(roomName)) { error("Tên nhóm đã tồn tại."); return; }
        if (!databaseManager.createChatGroup(roomName, username)) { error("Không lưu được nhóm mới."); return; }
        roomManager.createRoom(roomName);
        broadcastRoomList();
        joinRoom(roomName);
    }

    private void broadcastRoomList() {
        String list = String.join(",", roomManager.getRoomNames());
        Packet p = new Packet(Packet.Type.ROOM_LIST, "SERVER", list);
        clientManager.broadcast(p.toRawString());
    }

    private void joinRoom(String roomName) {
        if (roomName == null || !roomName.trim().matches("[\\p{L}\\p{N} _.-]{1,64}")) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Tên phòng không hợp lệ."));
            return;
        }
        roomName = roomName.trim();
        if (!roomManager.roomExists(roomName)) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Phòng '" + roomName + "' không tồn tại."));
            return;
        }
        roomName = roomManager.canonicalName(roomName);
        if (currentRoom.equalsIgnoreCase(roomName)) {
            sendPacket(new Packet(Packet.Type.JOIN_ROOM, "SERVER", roomName));
            return;
        }

        if (!databaseManager.isGroupMember(roomName, username) && !databaseManager.addGroupMember(roomName, username, "MEMBER")) {
            error("Không lưu được quyền thành viên phòng."); return;
        }

        String oldRoom = currentRoom;
        if (!roomManager.joinRoom(roomName, this)) {
            sendPacket(new Packet(Packet.Type.ERROR, "SERVER", "Không thể tham gia phòng."));
            return;
        }

        currentRoom = roomName;
        sendPacket(new Packet(Packet.Type.JOIN_ROOM, "SERVER", roomName));
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
        sendPacket(new Packet(Packet.Type.JOIN_ROOM, "SERVER", "General"));

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

    boolean awaitTermination(long timeoutNanos) throws InterruptedException {
        return finished.await(Math.max(0, timeoutNanos), TimeUnit.NANOSECONDS);
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
            registered = false;

            databaseManager.updateUserStatus(username, "OFFLINE");
            clientManager.removeClient(this);
            notifyFriendsStatusChange("OFFLINE");
            broadcastUserList();
        }

        try {
            // Close the socket first to unblock any thread waiting in readLine().
            if (socket != null && !socket.isClosed()) socket.close();
            synchronized (writeLock) {
                if (writer != null) writer.close();
            }
            if (reader != null) reader.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }
}
