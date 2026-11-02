package server;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomManager {

    private final Map<String, Set<ClientHandler>> rooms = new ConcurrentHashMap<>();

    public RoomManager() {
        createRoom("General");
        createRoom("Java");
        createRoom("Gaming");
    }

    public synchronized void createRoom(String roomName) {
        if (roomName == null || roomName.trim().isEmpty()) {
            return;
        }
        if (canonicalName(roomName) != null) return;
        rooms.putIfAbsent(roomName.trim(), ConcurrentHashMap.newKeySet());
    }

    public String canonicalName(String name) {
        if (name == null) return null;
        for (String room : rooms.keySet()) if (room.equalsIgnoreCase(name.trim())) return room;
        return null;
    }

    public boolean roomExists(String roomName) {
        if (roomName == null) {
            return false;
        }
        return canonicalName(roomName) != null;
    }

    public boolean joinRoom(String roomName, ClientHandler client) {
        if (client == null || !roomExists(roomName)) {
            return false;
        }
        roomName = canonicalName(roomName);
        leaveCurrentRoom(client);
        rooms.get(roomName).add(client);
        return true;
    }

    public void leaveCurrentRoom(ClientHandler client) {
        if (client == null) {
            return;
        }
        for (Set<ClientHandler> clients : rooms.values()) {
            clients.remove(client);
        }
    }

    public void broadcastToRoom(String roomName, String message) {
        if (roomName == null || message == null) {
            return;
        }
        Set<ClientHandler> clients = rooms.get(canonicalName(roomName) == null ? "" : canonicalName(roomName));
        if (clients == null) {
            return;
        }
        for (ClientHandler client : clients) {
            client.sendMessage(message);
        }
    }

    public Set<String> getRoomNames() {
        return Set.copyOf(rooms.keySet());
    }

    public int getClientCount(String roomName) {
        if (roomName == null) {
            return 0;
        }
        Set<ClientHandler> clients = rooms.get(canonicalName(roomName) == null ? "" : canonicalName(roomName));
        return (clients == null) ? 0 : clients.size();
    }

    public String getClientRoom(ClientHandler client) {
        if (client == null) {
            return null;
        }
        for (Map.Entry<String, Set<ClientHandler>> entry : rooms.entrySet()) {
            if (entry.getValue().contains(client)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void removeClient(ClientHandler client) {
        leaveCurrentRoom(client);
    }
}