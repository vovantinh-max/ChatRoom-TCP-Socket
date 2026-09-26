package server;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomManager {

    // Danh sách phòng và Client trong từng phòng
    private final Map<String, Set<ClientHandler>> rooms =
            new ConcurrentHashMap<>();

    public RoomManager() {

        // Tạo các phòng mặc định
        createRoom("General");
        createRoom("Java");
        createRoom("Gaming");
    }

    // ==========================================
    // TẠO PHÒNG
    // ==========================================

    public void createRoom(String roomName) {

        rooms.putIfAbsent(
                roomName,
                ConcurrentHashMap.newKeySet()
        );
    }

    // ==========================================
    // KIỂM TRA PHÒNG
    // ==========================================

    public boolean roomExists(String roomName) {

        return rooms.containsKey(roomName);
    }

    // ==========================================
    // THÊM CLIENT VÀO PHÒNG
    // ==========================================

    public boolean joinRoom(
            String roomName,
            ClientHandler client) {

        if (!roomExists(roomName)) {
            return false;
        }

        // Nếu Client đang ở phòng khác
        // thì xóa khỏi phòng cũ
        leaveCurrentRoom(client);

        rooms.get(roomName).add(client);

        return true;
    }

    // ==========================================
    // RỜI PHÒNG HIỆN TẠI
    // ==========================================

    public void leaveCurrentRoom(
            ClientHandler client) {

        for (Set<ClientHandler> clients :
                rooms.values()) {

            clients.remove(client);
        }
    }

    // ==========================================
    // GỬI TIN NHẮN TRONG PHÒNG
    // ==========================================

    public void broadcastToRoom(
            String roomName,
            String message) {

        Set<ClientHandler> clients =
                rooms.get(roomName);

        if (clients == null) {
            return;
        }

        for (ClientHandler client : clients) {

            client.sendMessage(message);
        }
    }

    // ==========================================
    // LẤY DANH SÁCH PHÒNG
    // ==========================================

    public Set<String> getRoomNames() {

        return rooms.keySet();
    }

    // ==========================================
    // ĐẾM CLIENT TRONG PHÒNG
    // ==========================================

    public int getClientCount(
            String roomName) {

        Set<ClientHandler> clients =
                rooms.get(roomName);

        if (clients == null) {
            return 0;
        }

        return clients.size();
    }

    // ==========================================
    // TÌM PHÒNG CỦA CLIENT
    // ==========================================

    public String getClientRoom(
            ClientHandler client) {

        for (Map.Entry<String, Set<ClientHandler>> entry :
                rooms.entrySet()) {

            if (entry.getValue().contains(client)) {

                return entry.getKey();
            }
        }

        return null;
    }

    // ==========================================
    // XÓA CLIENT KHỎI TẤT CẢ PHÒNG
    // ==========================================

    public void removeClient(
            ClientHandler client) {

        leaveCurrentRoom(client);
    }
}