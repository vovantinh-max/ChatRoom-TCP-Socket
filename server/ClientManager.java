package server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientManager {

    // Danh sách Client đang kết nối
    private final Set<ClientHandler> clients =
            ConcurrentHashMap.newKeySet();

    // Thêm Client
    public void addClient(ClientHandler client) {
        clients.add(client);

        System.out.println(
                "Client da tham gia. So Client: " + clients.size()
        );
    }

    // Xóa Client
    public void removeClient(ClientHandler client) {
        clients.remove(client);

        System.out.println(
                "Client da roi. So Client: " + clients.size()
        );
    }

    // Gửi tin nhắn đến tất cả Client
    public void broadcast(String message) {

        for (ClientHandler client : clients) {
            client.sendMessage(message);
        }
    }

    // Gửi tin nhắn đến một Client cụ thể
    public void sendToClient(String username, String message) {

        for (ClientHandler client : clients) {

            if (username.equalsIgnoreCase(client.getUsername())) {
                client.sendMessage(message);
                return;
            }
        }
    }

    // Số Client đang online
    public int getClientCount() {
        return clients.size();
    }

    // Kiểm tra username đã tồn tại chưa
    public boolean isUsernameTaken(String username) {

        for (ClientHandler client : clients) {

            if (username.equalsIgnoreCase(client.getUsername())) {
                return true;
            }
        }

        return false;
    }
}