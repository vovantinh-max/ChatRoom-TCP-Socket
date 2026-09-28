package server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientManager {

    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private ServerGUI serverGUI;

    public void setServerGUI(ServerGUI serverGUI) {
        this.serverGUI = serverGUI;
    }

    public void addClient(ClientHandler client) {
        clients.add(client);
        logMessage("Client da tham gia. So Client: " + clients.size());
        updateGUI();
    }

    public void removeClient(ClientHandler client) {
        clients.remove(client);
        logMessage("Client da roi. So Client: " + clients.size());
        updateGUI();
    }

    public void disconnectAllClients() {
        for (ClientHandler client : clients) {
            client.closeConnection();
        }
        clients.clear();
        updateGUI();
    }

    public void broadcast(String message) {
        for (ClientHandler client : clients) {
            client.sendMessage(message);
        }
    }

    public boolean sendToClient(String username, String message) {
        for (ClientHandler client : clients) {
            if (client.getUsername() != null && client.getUsername().equalsIgnoreCase(username)) {
                client.sendMessage(message);
                return true;
            }
        }
        return false;
    }

    public boolean isUsernameTaken(String username) {
        if (username == null) {
            return false;
        }
        for (ClientHandler client : clients) {
            if (client.getUsername() != null && client.getUsername().equalsIgnoreCase(username)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getUsernames() {
        Set<String> usernames = ConcurrentHashMap.newKeySet();
        for (ClientHandler client : clients) {
            String username = client.getUsername();
            if (username != null && !username.isEmpty()) {
                usernames.add(username);
            }
        }
        return usernames;
    }

    public Set<ClientHandler> getClients() {
        return clients;
    }

    public int getClientCount() {
        return clients.size();
    }

    private void updateGUI() {
        if (serverGUI != null) {
            serverGUI.refreshClientList();
        }
    }

    public void logMessage(String message) {
        System.out.println(message);
        if (serverGUI != null) {
            serverGUI.appendLog(message);
        }
    }
}