package server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientManager {

    // Danh sách Client đang kết nối
    private final Set<ClientHandler> clients =
            ConcurrentHashMap.newKeySet();

    // GUI Server
    private ServerGUI serverGUI;

    // Gắn GUI vào ClientManager
    public void setServerGUI(ServerGUI serverGUI) {
        this.serverGUI = serverGUI;
    }

    // Thêm Client
    public void addClient(ClientHandler client) {

        clients.add(client);

        System.out.println(
                "Client da tham gia. So Client: "
                        + clients.size()
        );

        updateGUI();
    }

    // Xóa Client
    public void removeClient(ClientHandler client) {

        clients.remove(client);

        System.out.println(
                "Client da roi. So Client: "
                        + clients.size()
        );

        updateGUI();
    }

    // Gửi tin nhắn đến tất cả Client
    public void broadcast(String message) {

        for (ClientHandler client : clients) {
            client.sendMessage(message);
        }
    }

    // Gửi tin nhắn đến một Client
    public boolean sendToClient(
            String username,
            String message) {

        for (ClientHandler client : clients) {

            if (username.equalsIgnoreCase(
                    client.getUsername())) {

                client.sendMessage(message);

                return true;
            }
        }

        return false;
    }

    // Số Client online
    public int getClientCount() {
        return clients.size();
    }

    // Kiểm tra username
    public boolean isUsernameTaken(
            String username) {

        for (ClientHandler client : clients) {

            if (username.equalsIgnoreCase(
                    client.getUsername())) {

                return true;
            }
        }

        return false;
    }

    // Lấy danh sách username
    public Set<String> getUsernames() {

        Set<String> usernames =
                ConcurrentHashMap.newKeySet();

        for (ClientHandler client : clients) {

            if (client.getUsername() != null) {

                usernames.add(
                        client.getUsername()
                );
            }
        }

        return usernames;
    }

    // ==========================================
    // LẤY DANH SÁCH CLIENT
    // Dùng cho File Transfer
    // ==========================================

    public Set<ClientHandler> getClients() {
        return clients;
    }

    // Cập nhật GUI
    private void updateGUI() {

        if (serverGUI != null) {

            serverGUI.refreshClientList();
        }
    }

    // Ghi Log lên Server
    public void logMessage(String message) {

        System.out.println(message);

        if (serverGUI != null) {
            serverGUI.appendLog(message);
        }
    }
}