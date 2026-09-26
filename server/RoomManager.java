package server;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomManager {

    // Danh sách phòng
    // Mỗi phòng chứa danh sách Client
    private final Map<String, Set<ClientHandler>> rooms =
            new ConcurrentHashMap<>();

    public RoomManager() {

        // Các phòng mặc định
        createRoom("General");
        createRoom("Java");
        createRoom("Gaming");
    }

    // Tạo phòng
    public void createRoom(String roomName) {

        if (roomName == null || roomName.trim().isEmpty()) {
            return;
        }

        roomName = roomName.trim();

        rooms.putIfAbsent(
                roomName,
                ConcurrentHashMap.newKeySet()
        );
    }

    // Kiểm tra phòng có tồn tại
    public boolean roomExists(String roomName) {

        if (roomName == null) {
            return false;
        }

        return rooms.containsKey(roomName.trim());
    }

    // Cho Client vào phòng
    public boolean joinRoom(
            String roomName,
            ClientHandler client) {

        if (client == null || !roomExists(roomName)) {
            return false;
        }

        roomName = roomName.trim();

        // Xóa Client khỏi phòng cũ
        leaveCurrentRoom(client);

        // Thêm Client vào phòng mới
        rooms.get(roomName).add(client);

        return true;
    }

    // Xóa Client khỏi phòng hiện tại
    public void leaveCurrentRoom(
            ClientHandler client) {

        if (client == null) {
            return;
        }

        for (Set<ClientHandler> clients :
                rooms.values()) {

            clients.remove(client);
        }
    }

    // Gửi tin nhắn trong phòng
    public void broadcastToRoom(
            String roomName,
            String message) {

        if (roomName == null || message == null) {
            return;
        }

        Set<ClientHandler> clients =
                rooms.get(roomName);

        if (clients == null) {
            return;
        }

        for (ClientHandler client : clients) {
            client.sendMessage(message);
        }
    }

    // Lấy danh sách phòng
    public Set<String> getRoomNames() {
        return rooms.keySet();
    }

    // Đếm số Client trong phòng
    public int getClientCount(
            String roomName) {

        if (roomName == null) {
            return 0;
        }

        Set<ClientHandler> clients =
                rooms.get(roomName);

        if (clients == null) {
            return 0;
        }

        return clients.size();
    }

    // Tìm phòng hiện tại của Client
    public String getClientRoom(
            ClientHandler client) {

        if (client == null) {
            return null;
        }

        for (Map.Entry<String, Set<ClientHandler>> entry :
                rooms.entrySet()) {

            if (entry.getValue().contains(client)) {
                return entry.getKey();
            }
        }

        return null;
    }

    // Xóa Client khỏi tất cả phòng
    public void removeClient(
            ClientHandler client) {

        leaveCurrentRoom(client);
    }
}