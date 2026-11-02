package client;

import shared.protocol.Packet;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * ChatClient - Phiên bản nâng cấp cho Messenger Desktop Pro.
 * Quản lý kết nối TCP Socket, Đăng ký, Đăng nhập với mật khẩu,
 * Quản lý Bạn bè thời gian thực và Heartbeat Ping-Pong.
 */
public class ChatClient {

    public record FriendItem(String username, String displayName, String status, boolean isOnline) {}
    public record SearchUserItem(String username, String displayName, String relationship) {}
    public record ChatMessageItem(
        String uuid,
        String sender,
        String recipient,
        String content,
        String replyToId,
        boolean isEdited,
        boolean isRevoked,
        String reactions,
        long timestamp
    ) {}

    public interface ChatEventListener {
        void onMessageReceived(String sender, String message);
        void onPrivateMessageReceived(String sender, String receiver, String message);
        void onUserListUpdated(List<String> users);
        void onRoomListUpdated(List<String> rooms);
        void onSystemMessage(String message);
        void onDisconnected(String reason);

        default void onPacketReceived(Packet packet) {}
        default void onConnectionStatusChanged(boolean connected, String status) {}
        default void onFriendListUpdated(List<FriendItem> friends) {}
        default void onPendingRequestsUpdated(List<String> senders) {}
        default void onUserSearchResult(List<SearchUserItem> users) {}

        // Phase 3 Callbacks
        default void onChatMessageReceived(ChatMessageItem item) {}
        default void onPrivateChatMessageReceived(ChatMessageItem item) {}
        default void onTyping(String sender, boolean isTyping) {}
        default void onReactionUpdated(String messageId, String emoji, String allReactions) {}
        default void onMessageEdited(String messageId, String newContent) {}
        default void onMessageRevoked(String messageId) {}
        default void onHistoryLoaded(String target, List<ChatMessageItem> messages) {}

        // Phase 4 & 5 & 6 Callbacks (Media & Files)
        default void onCallPacketReceived(Packet packet) {}
        default void onFilePacketReceived(Packet packet) {}
    }

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private final Object writeLock = new Object();

    private ServerListener serverListener;
    private Thread listenerThread;

    private String username;
    private String serverHost;
    private int serverPort;
    private volatile boolean connected = false;

    private ChatEventListener eventListener;
    private ScheduledExecutorService heartbeatExecutor;
    private volatile long lastPongTime = 0;

    public ChatClient() {
    }

    public void setEventListener(ChatEventListener listener) {
        this.eventListener = listener;
    }

    // ─── ĐĂNG KÝ TÀI KHOẢN MỚI ────────────────────────────────────────────────
    public String register(String host, int port, String username, String password, String displayName) throws IOException {
        try (Socket regSocket = new Socket(host, port);
             BufferedReader regReader = new BufferedReader(new InputStreamReader(regSocket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter regWriter = new PrintWriter(new OutputStreamWriter(regSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            regSocket.setSoTimeout(10000);

            Packet regPacket = new Packet(Packet.Type.REGISTER, username, password);
            regPacket.setExtra(displayName);
            regWriter.println(regPacket.toRawString());
            regWriter.flush();

            String responseLine = regReader.readLine();
            if (responseLine == null) {
                return "Máy chủ không phản hồi.";
            }

            Packet response = Packet.fromRawString(responseLine);
            if (response != null && response.getType() == Packet.Type.REGISTER_SUCCESS) {
                return "SUCCESS";
            } else if (response != null && response.getType() == Packet.Type.REGISTER_FAILED) {
                return response.getContent();
            } else {
                return "Đăng ký thất bại.";
            }
        }
    }

    // ─── ĐĂNG NHẬP VỚI MẬT KHẨU ───────────────────────────────────────────────
    public boolean connect(String host, int port, String username, String password) throws IOException {
        if (connected) {
            disconnect();
        }

        this.serverHost = host;
        this.serverPort = port;
        this.username = username;

        this.socket = new Socket(host, port);
        this.socket.setTcpNoDelay(true);
        this.socket.setKeepAlive(true);

        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

        // Gửi gói tin LOGIN kèm mật khẩu
        Packet loginPacket = new Packet(Packet.Type.LOGIN, username, password != null ? password : "");
        synchronized (writeLock) {
            writer.println(loginPacket.toRawString());
            writer.flush();
        }

        String initialResponse = reader.readLine();
        if (initialResponse == null) {
            disconnect();
            throw new IOException("Máy chủ đã đóng kết nối hoặc đang tạm dừng hoạt động.");
        }

        Packet responsePacket = Packet.fromRawString(initialResponse);
        if (responsePacket != null && responsePacket.getType() == Packet.Type.LOGIN_FAILED) {
            disconnect();
            String reason = responsePacket.getContent();
            throw new IOException(reason != null && !reason.isEmpty() ? reason : "Đăng nhập thất bại.");
        } else if (initialResponse.startsWith("LOGIN_FAILED|") || initialResponse.equals("USERNAME_TAKEN")) {
            disconnect();
            String reason = initialResponse.startsWith("LOGIN_FAILED|")
                    ? initialResponse.substring(13).trim()
                    : "Tên người dùng đã tồn tại hoặc mật khẩu sai.";
            throw new IOException(reason);
        }

        this.connected = true;
        this.lastPongTime = System.currentTimeMillis();

        // Khởi động luồng đọc ServerListener bằng Virtual Thread
        this.serverListener = new ServerListener(reader, this);
        this.listenerThread = Thread.ofVirtual().name("ServerListener-" + username).unstarted(serverListener);
        this.listenerThread.start();

        // Heartbeat
        startHeartbeat();

        if (eventListener != null) {
            eventListener.onConnectionStatusChanged(true, "Đã kết nối");
        }

        if (responsePacket != null && responsePacket.getType() == Packet.Type.LOGIN_SUCCESS) {
            notifySystemMessage("Đăng nhập thành công! Chào mừng @" + username);
        }

        return true;
    }

    // Backward compatible connect
    public boolean connect(String host, int port, String username) throws IOException {
        return connect(host, port, username, "");
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChatClient-Heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!connected) return;
            try {
                long elapsed = System.currentTimeMillis() - lastPongTime;
                if (elapsed > 45000) {
                    notifyDisconnected("Mất kết nối với máy chủ (Heartbeat timeout).");
                    return;
                }
                Packet ping = new Packet(Packet.Type.PING, username, "");
                sendPacket(ping);
            } catch (Exception e) {
                notifyDisconnected("Lỗi kết nối khi gửi heartbeat.");
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    public void handlePong() {
        this.lastPongTime = System.currentTimeMillis();
    }

    // ─── TÍNH NĂNG BẠN BÈ (MODULE 2) ─────────────────────────────────────────
    public void sendFriendRequest(String targetUsername) {
        if (!connected) return;
        sendPacket(new Packet(Packet.Type.FRIEND_REQUEST, username, targetUsername));
    }

    public void acceptFriendRequest(String senderUsername) {
        if (!connected) return;
        sendPacket(new Packet(Packet.Type.FRIEND_ACCEPT, username, senderUsername));
    }

    public void rejectFriendRequest(String senderUsername) {
        if (!connected) return;
        sendPacket(new Packet(Packet.Type.FRIEND_REJECT, username, senderUsername));
    }

    public void removeFriend(String targetUsername) {
        if (!connected) return;
        sendPacket(new Packet(Packet.Type.FRIEND_REMOVE, username, targetUsername));
    }

    public void searchUsers(String query) {
        if (!connected) return;
        sendPacket(new Packet(Packet.Type.USER_SEARCH, username, query));
    }

    public void changeStatus(String status) {
        if (!connected) return;
        sendPacket(new Packet(Packet.Type.USER_STATUS_CHANGE, username, status));
    }

    // ─── Gửi gói tin Thread-Safe ────────────────────────────────────────────────
    public void sendPacket(Packet packet) {
        if (!connected || writer == null || packet == null || socket == null || socket.isClosed()) return;
        synchronized (writeLock) {
            writer.println(packet.toRawString());
            writer.flush();
        }
    }

    public void sendPublicMessage(String message) {
        sendPublicMessage(message, null);
    }

    public void sendPublicMessage(String message, String replyToId) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.MESSAGE, username, message);
        packet.setExtra(replyToId);
        sendPacket(packet);
    }

    public void sendPrivateMessage(String recipient, String message) {
        sendPrivateMessage(recipient, message, null);
    }

    public void sendPrivateMessage(String recipient, String message, String replyToId) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.PRIVATE, username, recipient, message);
        packet.setExtra(replyToId);
        sendPacket(packet);
    }

    public void sendTyping(String target, boolean isTyping) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.TYPING, username, target, isTyping ? "START" : "STOP");
        sendPacket(packet);
    }

    public void sendReaction(String messageId, String emoji, String target) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.REACTION, username, target, emoji);
        packet.setId(messageId);
        sendPacket(packet);
    }

    public void sendEditMessage(String messageId, String newContent, String target) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.EDIT_MESSAGE, username, target, newContent);
        packet.setId(messageId);
        sendPacket(packet);
    }

    public void sendRevokeMessage(String messageId, String target) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.REVOKE_MESSAGE, username, target, "");
        packet.setId(messageId);
        sendPacket(packet);
    }

    public void requestHistory(String target, boolean isPublic) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.HISTORY_REQUEST, username, target, "");
        packet.setExtra(isPublic ? "PUBLIC" : "PRIVATE");
        sendPacket(packet);
    }

    public void sendCreateRoom(String roomName) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.CREATE_ROOM, username, roomName);
        sendPacket(packet);
    }

    public void sendJoinRoom(String roomName) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.JOIN_ROOM, username, roomName);
        sendPacket(packet);
    }

    public void requestRoomList() {
        if (!connected) return;
        sendRawMessage("ROOMS");
    }

    // ─── SIGNALING CUỘC GỌI (MODULE 4, 5, 6, 7) ──────────────────────────────
    public void sendCallOffer(String recipient, String callType, int udpPort) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.CALL_OFFER, username, recipient, callType);
        packet.setExtra(String.valueOf(udpPort));
        sendPacket(packet);
    }

    public void sendCallAnswer(String caller, boolean accepted, int udpPort) {
        if (!connected) return;
        Packet packet = new Packet(accepted ? Packet.Type.CALL_ANSWER : Packet.Type.CALL_REJECT, username, caller, accepted ? "ACCEPT" : "REJECT");
        packet.setExtra(String.valueOf(udpPort));
        sendPacket(packet);
    }

    public void sendCallEnd(String target) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.CALL_END, username, target, "END");
        sendPacket(packet);
    }

    public void sendCallReject(String caller, String reason) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.CALL_REJECT, username, caller, reason != null ? reason : "BUSY");
        sendPacket(packet);
    }

    // ─── TRUYỀN FILE THEO CHUNK (MODULE 8) ───────────────────────────────────
    public void sendFileStart(String recipient, String fileId, String fileName, long fileSize, int totalChunks, String checksum) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.FILE_START, username, recipient, fileName);
        packet.setId(fileId);
        packet.setExtra(fileSize + ":" + totalChunks + ":" + checksum);
        sendPacket(packet);
    }

    public void sendFileData(String recipient, String fileId, int chunkIndex, String base64Data) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.FILE_DATA, username, recipient, base64Data);
        packet.setId(fileId);
        packet.setExtra(String.valueOf(chunkIndex));
        sendPacket(packet);
    }

    public void sendFileEnd(String recipient, String fileId, String checksum) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.FILE_END, username, recipient, checksum);
        packet.setId(fileId);
        sendPacket(packet);
    }

    public void sendFileCancel(String recipient, String fileId) {
        if (!connected) return;
        Packet packet = new Packet(Packet.Type.FILE_CANCEL, username, recipient, "CANCELLED");
        packet.setId(fileId);
        sendPacket(packet);
    }

    public void logout() {
        if (connected) {
            Packet logout = new Packet(Packet.Type.LOGOUT, username, "");
            sendPacket(logout);
            disconnect();
        }
    }

    public void sendRawMessage(String raw) {
        if (writer != null) {
            synchronized (writeLock) {
                writer.println(raw);
                writer.flush();
            }
        }
    }

    public synchronized void disconnect() {
        if (!connected && (socket == null || socket.isClosed())) return;
        connected = false;
        stopHeartbeat();

        if (serverListener != null) {
            serverListener.stopListening();
        }

        try {
            synchronized (writeLock) {
                if (writer != null) writer.close();
            }
            if (reader != null) reader.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}

        if (eventListener != null) {
            eventListener.onConnectionStatusChanged(false, "Đã ngắt kết nối");
        }
    }

    // ─── Event dispatchers ──────────────────────────────────────────────────────
    void notifyMessage(String sender, String content) {
        if (eventListener != null) eventListener.onMessageReceived(sender, content);
    }

    void notifyPrivateMessage(String sender, String receiver, String content) {
        if (eventListener != null) eventListener.onPrivateMessageReceived(sender, receiver, content);
    }

    void notifyUserList(List<String> users) {
        if (eventListener != null) eventListener.onUserListUpdated(users);
    }

    void notifyRoomList(List<String> rooms) {
        if (eventListener != null) eventListener.onRoomListUpdated(rooms);
    }

    void notifySystemMessage(String message) {
        if (eventListener != null) eventListener.onSystemMessage(message);
    }

    void notifyPacket(Packet packet) {
        if (eventListener != null) eventListener.onPacketReceived(packet);
    }

    void notifyFriendList(List<FriendItem> friends) {
        if (eventListener != null) eventListener.onFriendListUpdated(friends);
    }

    void notifyPendingRequests(List<String> senders) {
        if (eventListener != null) eventListener.onPendingRequestsUpdated(senders);
    }

    void notifyUserSearchResult(List<SearchUserItem> users) {
        if (eventListener != null) eventListener.onUserSearchResult(users);
    }

    void notifyChatMessage(ChatMessageItem item) {
        if (eventListener != null) eventListener.onChatMessageReceived(item);
    }

    void notifyPrivateChatMessage(ChatMessageItem item) {
        if (eventListener != null) eventListener.onPrivateChatMessageReceived(item);
    }

    void notifyTyping(String sender, boolean isTyping) {
        if (eventListener != null) eventListener.onTyping(sender, isTyping);
    }

    void notifyReactionUpdated(String messageId, String emoji, String allReactions) {
        if (eventListener != null) eventListener.onReactionUpdated(messageId, emoji, allReactions);
    }

    void notifyMessageEdited(String messageId, String newContent) {
        if (eventListener != null) eventListener.onMessageEdited(messageId, newContent);
    }

    void notifyMessageRevoked(String messageId) {
        if (eventListener != null) eventListener.onMessageRevoked(messageId);
    }

    void notifyHistoryLoaded(String target, List<ChatMessageItem> messages) {
        if (eventListener != null) eventListener.onHistoryLoaded(target, messages);
    }

    void notifyCallPacket(Packet packet) {
        if (eventListener != null) eventListener.onCallPacketReceived(packet);
    }

    void notifyFilePacket(Packet packet) {
        if (eventListener != null) eventListener.onFilePacketReceived(packet);
    }

    void notifyDisconnected(String reason) {
        disconnect();
        if (eventListener != null) eventListener.onDisconnected(reason);
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public String getUsername() { return username; }
    public String getServerHost() { return serverHost; }
    public int getServerPort() { return serverPort; }
}
