package client;

import shared.protocol.Packet;
import client.messaging.DeliveryTracker;
import shared.protocol.BoundedLineReader;
import shared.protocol.ProtocolLimits;
import shared.protocol.HistoryCodec;
import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * ChatClient - Phiên bản nâng cấp cho TCP Chat.
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

        default void onRoomChanged(String room) {}
        default void onPacketReceived(Packet packet) {}
        default void onMessageDeliveryChanged(String id, DeliveryTracker.State state, String detail) {}
        default void onConnectionStatusChanged(boolean connected, String status) {}
        default void onFriendListUpdated(List<FriendItem> friends) {}
        default void onPendingRequestsUpdated(List<String> senders) {}
        default void onUserSearchResult(List<SearchUserItem> users) {}
        default void onUserSearchResult(String requestId, List<SearchUserItem> users) { onUserSearchResult(users); }
        default void onFriendActionResult(String action, String target, boolean success, String message) {}

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

    private volatile ChatEventListener eventListener;
    private final DeliveryTracker deliveries = new DeliveryTracker(this::sendPacket, (id, state, detail) -> {
        ChatEventListener listener = eventListener;
        if (listener != null) listener.onMessageDeliveryChanged(id, state, detail);
    });
    public boolean retryMessage(String id) { return deliveries.retry(id); }
    public DeliveryTracker.State getMessageDeliveryState(String id) { return deliveries.state(id); }
    public List<Packet> getUnsentMessages() { return deliveries.unsent(); }
    void deliveryResult(String id, boolean stored, String detail) { deliveries.complete(id, stored, detail); }
    private final Object eventStateLock = new Object();
    private List<String> latestUsers;
    private List<String> latestRooms;
    private List<FriendItem> latestFriends;
    private List<String> latestPending;
    private ScheduledExecutorService heartbeatExecutor;
    private volatile long lastPongTime = 0;
    private volatile String currentRoom = "General";
    public String getCurrentRoom() { return currentRoom; }

    public ChatClient() {
    }

    public void setEventListener(ChatEventListener listener) {
        synchronized (eventStateLock) {
            this.eventListener = listener;
            if (listener == null) return;
            if (latestUsers != null) listener.onUserListUpdated(latestUsers);
            if (latestRooms != null) listener.onRoomListUpdated(latestRooms);
            if (latestFriends != null) listener.onFriendListUpdated(latestFriends);
            if (latestPending != null) listener.onPendingRequestsUpdated(latestPending);
            if (connected) listener.onRoomChanged(currentRoom);
        }
    }

    // ─── ĐĂNG KÝ TÀI KHOẢN MỚI ────────────────────────────────────────────────
    public String register(String host, int port, String username, String password, String displayName) throws IOException {
        try (Socket regSocket = new Socket(host, port);
             BufferedReader regReader = BoundedLineReader.create(regSocket.getInputStream());
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

        synchronized (eventStateLock) {
            currentRoom = "General";
            latestUsers = null;
            latestRooms = null;
            latestFriends = null;
            latestPending = null;
        }
        this.serverHost = host;
        this.serverPort = port;
        this.username = username;

        this.socket = new Socket();
        try {
            this.socket.connect(new InetSocketAddress(host, port), 10000);
            this.socket.setSoTimeout(10000);
            this.socket.setTcpNoDelay(true);
            this.socket.setKeepAlive(true);

            this.reader = BoundedLineReader.create(socket.getInputStream());
            this.writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // Gửi gói tin LOGIN kèm mật khẩu
            Packet loginPacket = new Packet(Packet.Type.LOGIN, username, password != null ? password : "");
            synchronized (writeLock) {
                writer.println(loginPacket.toRawString());
                writer.flush();
            }

            String initialResponse = reader.readLine();
            if (initialResponse == null) {
                throw new IOException("Máy chủ đã đóng kết nối hoặc đang tạm dừng hoạt động.");
            }

            Packet responsePacket = Packet.fromRawString(initialResponse);
            if (responsePacket != null && responsePacket.getType() == Packet.Type.LOGIN_FAILED) {
                String reason = responsePacket.getContent();
                throw new IOException(reason != null && !reason.isEmpty() ? reason : "Đăng nhập thất bại.");
            } else if (initialResponse.startsWith("LOGIN_FAILED|") || initialResponse.equals("USERNAME_TAKEN")) {
                String reason = initialResponse.startsWith("LOGIN_FAILED|")
                        ? initialResponse.substring(13).trim()
                        : "Tên người dùng đã tồn tại hoặc mật khẩu sai.";
                throw new IOException(reason);
            }

            if (responsePacket == null || responsePacket.getType() != Packet.Type.LOGIN_SUCCESS) {
                throw new IOException("Phản hồi đăng nhập không hợp lệ.");
            }
            this.socket.setSoTimeout(0);
        } catch (IOException | RuntimeException e) {
            disconnect();
            throw e;
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

        notifySystemMessage("Đăng nhập thành công! Chào mừng @" + username);

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

    public String searchUsers(String query) {
        if (!connected) return null;
        Packet request = new Packet(Packet.Type.USER_SEARCH, username, query);
        sendPacket(request);
        return request.getId();
    }

    public void changeStatus(String status) {
        if (!connected) return;
        sendPacket(new Packet(Packet.Type.USER_STATUS_CHANGE, username, status));
    }

    // ─── Gửi gói tin Thread-Safe ────────────────────────────────────────────────
    public boolean sendPacket(Packet packet) {
        if (!connected || writer == null || packet == null || socket == null || socket.isClosed()) return false;
        synchronized (writeLock) {
            writer.println(packet.toRawString());
            writer.flush();
            return !writer.checkError();
        }
    }

    public void sendPublicMessage(String message) {
        sendPublicMessage(message, null);
    }

    public String sendPublicMessage(String message, String replyToId) { return sendPublicMessage(message, replyToId, currentRoom); }

    public String sendPublicMessage(String message, String replyToId, String room) {
        Packet packet = new Packet(Packet.Type.MESSAGE, username, message);
        if (room != null) packet.setRecipient("#" + room);
        packet.setExtra(replyToId);
        return deliveries.send(packet);
    }

    public void sendPrivateMessage(String recipient, String message) {
        sendPrivateMessage(recipient, message, null);
    }

    public String sendPrivateMessage(String recipient, String message, String replyToId) {
        Packet packet = new Packet(Packet.Type.PRIVATE, username, recipient, message);
        packet.setExtra(replyToId);
        return deliveries.send(packet);
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
        sendCallSignal(Packet.Type.CALL_OFFER, recipient, callType, String.valueOf(udpPort), null);
    }

    public void sendCallSignal(Packet.Type type, String recipient, String content, String extra, String id) {
        if (!connected) return;
        Packet packet = new Packet(type, username, recipient, content);
        if (id != null) packet.setId(id);
        packet.setExtra(extra);
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
        deliveries.disconnected();
        stopHeartbeat();

        if (serverListener != null) {
            serverListener.stopListening();
        }

        try {
            if (socket != null && !socket.isClosed()) socket.close();
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
        synchronized (eventStateLock) {
            latestUsers = List.copyOf(users);
            if (eventListener != null) eventListener.onUserListUpdated(latestUsers);
        }
    }

    void notifyRoomChanged(String room) {
        synchronized (eventStateLock) {
            currentRoom = room;
            if (eventListener != null) eventListener.onRoomChanged(room);
        }
    }

    void notifyRoomList(List<String> rooms) {
        synchronized (eventStateLock) {
            latestRooms = List.copyOf(rooms);
            if (eventListener != null) eventListener.onRoomListUpdated(latestRooms);
        }
    }

    void notifySystemMessage(String message) {
        if (eventListener != null) eventListener.onSystemMessage(message);
    }

    void notifyPacket(Packet packet) {
        if (eventListener != null) eventListener.onPacketReceived(packet);
    }

    void notifyFriendList(List<FriendItem> friends) {
        synchronized (eventStateLock) {
            latestFriends = List.copyOf(friends);
            if (eventListener != null) eventListener.onFriendListUpdated(latestFriends);
        }
    }

    void notifyPendingRequests(List<String> senders) {
        synchronized (eventStateLock) {
            latestPending = List.copyOf(senders);
            if (eventListener != null) eventListener.onPendingRequestsUpdated(latestPending);
        }
    }

    void notifyFriendActionResult(String action, String target, boolean success, String message) {
        if (eventListener != null) eventListener.onFriendActionResult(action, target, success, message);
    }

    void notifyUserSearchResult(String requestId, List<SearchUserItem> users) {
        if (eventListener != null) eventListener.onUserSearchResult(requestId, users);
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
