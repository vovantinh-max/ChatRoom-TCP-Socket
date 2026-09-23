package client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ChatClient quản lý kết nối TCP Socket tới ChatServer.
 * Chịu trách nhiệm gửi các bản tin theo giao thức đã thống nhất và quản lý vòng đời kết nối.
 */
public class ChatClient {

    public interface ChatEventListener {
        void onMessageReceived(String sender, String message);
        void onPrivateMessageReceived(String sender, String receiver, String message);
        void onUserListUpdated(List<String> users);
        void onSystemMessage(String message);
        void onDisconnected(String reason);
    }

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private ServerListener serverListener;
    private Thread listenerThread;

    private String username;
    private String serverHost;
    private int serverPort;
    private boolean connected = false;

    private ChatEventListener eventListener;

    public ChatClient() {
    }

    public void setEventListener(ChatEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * Mở kết nối TCP Socket tới Server và gửi bản tin LOGIN
     */
    public boolean connect(String host, int port, String username) throws IOException {
        this.serverHost = host;
        this.serverPort = port;
        this.username = username;

        this.socket = new Socket(host, port);
        this.reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
        );
        this.writer = new PrintWriter(
            new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)),
            true
        );

        this.connected = true;

        // Khởi chạy ServerListener trên một luồng riêng để lắng nghe dữ liệu từ Server
        this.serverListener = new ServerListener(reader, this);
        this.listenerThread = new Thread(serverListener, "ServerListener-Thread");
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();

        // Gửi thông điệp LOGIN theo giao thức: LOGIN|<username>
        sendRawMessage("LOGIN|" + username);

        return true;
    }

    /**
     * Gửi tin nhắn chung tới tất cả mọi người trong phòng: MESSAGE|<sender>|<message>
     */
    public void sendPublicMessage(String message) {
        if (!connected || writer == null) return;
        sendRawMessage("MESSAGE|" + username + "|" + message);
    }

    /**
     * Gửi tin nhắn riêng tới 1 người: PRIVATE|<sender>|<receiver>|<message>
     */
    public void sendPrivateMessage(String recipient, String message) {
        if (!connected || writer == null) return;
        sendRawMessage("PRIVATE|" + username + "|" + recipient + "|" + message);
    }

    /**
     * Gửi yêu cầu đăng xuất: LOGOUT|<username> và ngắt kết nối
     */
    public void logout() {
        if (connected) {
            sendRawMessage("LOGOUT|" + username);
            disconnect();
        }
    }

    /**
     * Gửi dữ liệu thô kết thúc bằng ký tự xuống dòng
     */
    private synchronized void sendRawMessage(String raw) {
        if (writer != null) {
            writer.println(raw);
        }
    }

    /**
     * Đóng socket và giải phóng tài nguyên
     */
    public synchronized void disconnect() {
        if (!connected) return;
        connected = false;

        if (serverListener != null) {
            serverListener.stopListening();
        }

        try {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            // Bỏ qua lỗi khi đóng tài nguyên
        }
    }

    // Các hàm callback gọi từ ServerListener để chuyển tiếp tới UI
    void notifyMessage(String sender, String content) {
        if (eventListener != null) {
            eventListener.onMessageReceived(sender, content);
        }
    }

    void notifyPrivateMessage(String sender, String receiver, String content) {
        if (eventListener != null) {
            eventListener.onPrivateMessageReceived(sender, receiver, content);
        }
    }

    void notifyUserList(List<String> users) {
        if (eventListener != null) {
            eventListener.onUserListUpdated(users);
        }
    }

    void notifySystemMessage(String message) {
        if (eventListener != null) {
            eventListener.onSystemMessage(message);
        }
    }

    void notifyDisconnected(String reason) {
        connected = false;
        if (eventListener != null) {
            eventListener.onDisconnected(reason);
        }
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public String getUsername() {
        return username;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }
}
