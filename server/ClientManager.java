package server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientManager {

    // Danh sách Client đang online
    private final Set<ClientHandler> clients =
            ConcurrentHashMap.newKeySet();

    // GUI của Server
    private ServerGUI serverGUI;

    // Gắn GUI
    public void setServerGUI(ServerGUI serverGUI) {
        this.serverGUI = serverGUI;
    }

    // Thêm Client
    public void addClient(ClientHandler client) {

        clients.add(client);

        logMessage(
                "Client da tham gia. So Client: "
                        + clients.size()
        );

        updateGUI();
    }

    // Xóa Client
    public void removeClient(ClientHandler client) {

        clients.remove(client);

        logMessage(
                "Client da roi. So Client: "
                        + clients.size()
        );

        updateGUI();
    }

    // Gửi message đến tất cả Client
    public void broadcast(String message) {

        for (ClientHandler client : clients) {
            client.sendMessage(message);
        }
    }

    // Gửi message đến một Client theo username
    public boolean sendToClient(
            String username,
            String message) {

        for (ClientHandler client : clients) {

            if (client.getUsername() != null
                    && client.getUsername()
                            .equalsIgnoreCase(username)) {

                client.sendMessage(message);
                return true;
            }
        }

        return false;
    }

    // Kiểm tra username đã tồn tại chưa
    public boolean isUsernameTaken(
            String username) {

        if (username == null) {
            return false;
        }

        for (ClientHandler client : clients) {

            if (client.getUsername() != null
                    && client.getUsername()
                            .equalsIgnoreCase(username)) {

                return true;
            }
        }

        return false;
    }

    // Lấy danh sách username
    // Dùng để Server gửi USER_LIST|...
    public Set<String> getUsernames() {

        Set<String> usernames =
                ConcurrentHashMap.newKeySet();

        for (ClientHandler client : clients) {

            String username =
                    client.getUsername();

            if (username != null
                    && !username.isEmpty()) {

                usernames.add(username);
            }
        }

        return usernames;
    }

    // Lấy toàn bộ Client
    // ClientHandler dùng cho Private Chat và File Transfer
    public Set<ClientHandler> getClients() {
        return clients;
    }

    // Số Client đang online
    public int getClientCount() {
        return clients.size();
    }

    // Cập nhật danh sách Client trên Server GUI
    private void updateGUI() {

        if (serverGUI != null) {
            serverGUI.refreshClientList();
        }
    }

    // Ghi Log
    public void logMessage(String message) {

        System.out.println(message);

        if (serverGUI != null) {
            serverGUI.appendLog(message);
        }
    }
}