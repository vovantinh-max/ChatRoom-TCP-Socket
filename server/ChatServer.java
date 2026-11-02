package server;

import server.db.DatabaseManager;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/**
 * ChatServer - Máy chủ thời gian thực của Messenger Desktop Pro.
 * Tích hợp DatabaseManager (SQLite), Virtual Threads (Java 23),
 * và bộ giám sát kết nối chết (Heartbeat Watchdog).
 */
public class ChatServer {

    private static final int PORT = Integer.getInteger("chat.port", 5000);
    private final ClientManager clientManager;
    private final RoomManager roomManager;
    private final DatabaseManager databaseManager;

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private volatile boolean locked = false;
    private ServerGUI serverGUI;

    private ScheduledExecutorService watchdogExecutor;
    private final Set<ClientHandler> connections = ConcurrentHashMap.newKeySet();

    public ChatServer(ClientManager clientManager, ServerGUI serverGUI, RoomManager roomManager, DatabaseManager databaseManager) {
        this.clientManager = clientManager;
        this.serverGUI = serverGUI;
        this.roomManager = roomManager;
        this.databaseManager = databaseManager;
    }

    public ChatServer(ClientManager clientManager, ServerGUI serverGUI, RoomManager roomManager) {
        this(clientManager, serverGUI, roomManager, new DatabaseManager());
    }

    public synchronized boolean startServer() {
        if (running) {
            return false;
        }
        try {
            if (!databaseManager.isAvailable()) throw new IOException("Cơ sở dữ liệu không sẵn sàng.");
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            running = true;

            // Khởi động luồng chấp nhận kết nối bằng Virtual Thread
            ServerSocket listener = serverSocket;
            Thread.ofVirtual().name("ServerAcceptThread").start(() -> acceptClients(listener));

            // Khởi động Watchdog kiểm tra Heartbeat các kết nối chết
            startWatchdog();

            if (serverGUI != null) {
                if (databaseManager != null && databaseManager.isUsingMemoryFallback()) {
                    serverGUI.appendLog("⚠ SQLite Driver chưa nạp. Tự động chạy In-Memory Fallback an toàn.");
                } else {
                    serverGUI.appendLog("✓ SQLite Database khởi tạo thành công tại: data/messenger.db");
                }
            }

            return true;
        } catch (IOException e) {
            clientManager.logMessage("Cannot start server on port " + PORT + ": " + e.getMessage());
            if (serverGUI != null) {
                serverGUI.appendLog("Không thể khởi động Server: " + e.getMessage());
            }
            return false;
        }
    }

    private void acceptClients(ServerSocket listener) {
        if (serverGUI != null) {
            serverGUI.appendLog("Server đang chờ Client kết nối (Virtual Threads + SQLite enabled)...");
        }

        while (running && !listener.isClosed()) {
            try {
                Socket clientSocket = listener.accept();
                if (serverGUI != null) {
                    serverGUI.appendLog("Client mới kết nối: " + clientSocket.getInetAddress().getHostAddress());
                }

                ClientHandler clientHandler = new ClientHandler(clientSocket, clientManager, roomManager, databaseManager, this);
                synchronized (this) {
                    if (!running || listener.isClosed()) {
                        clientSocket.close();
                        continue;
                    }
                    connections.add(clientHandler);
                }
                Thread.ofVirtual().name("ClientHandler-" + clientSocket.getPort()).start(clientHandler);
            } catch (IOException e) {
                if (running && serverGUI != null) {
                    serverGUI.appendLog("Lỗi nhận Client: " + e.getMessage());
                }
            }
        }
    }

    private void startWatchdog() {
        stopWatchdog();
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Server-Watchdog");
            t.setDaemon(true);
            return t;
        });

        watchdogExecutor.scheduleAtFixedRate(() -> {
            if (!running || clientManager == null) return;
            long now = System.currentTimeMillis();
            for (ClientHandler ch : clientManager.getClients()) {
                if (now - ch.getLastHeartbeatTime() > 60000) {
                    clientManager.logMessage("[WATCHDOG] Ngắt kết nối treo (timeout): " + ch.getUsername());
                    ch.disconnect();
                }
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private void stopWatchdog() {
        if (watchdogExecutor != null && !watchdogExecutor.isShutdown()) {
            watchdogExecutor.shutdownNow();
            watchdogExecutor = null;
        }
    }

    public synchronized void stopServer() {
        running = false;
        locked = false;
        stopWatchdog();
        Set<ClientHandler> stopping = Set.copyOf(connections);
        for (ClientHandler connection : stopping) {
            connection.closeConnection();
        }

        if (clientManager != null) {
            clientManager.disconnectAllClients();
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            if (serverGUI != null) {
                serverGUI.appendLog("Lỗi khi dừng Server: " + e.getMessage());
            }
        }
        // Finish status updates before callers close the shared database.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (ClientHandler connection : stopping) {
            try {
                if (!connection.awaitTermination(deadline - System.nanoTime())) {
                    clientManager.logMessage("Timed out waiting for connection cleanup");
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    void onClientDisconnected(ClientHandler client) {
        connections.remove(client);
    }

    public boolean isLocked() { return locked; }

    public boolean toggleLock() {
        locked = !locked;
        return locked;
    }

    public boolean isRunning() { return running; }
    public ClientManager getClientManager() { return clientManager; }
    public RoomManager getRoomManager() { return roomManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
            DatabaseManager databaseManager = new DatabaseManager();
            ClientManager clientManager = new ClientManager();
            RoomManager roomManager = new RoomManager();
            ServerGUI serverGUI = new ServerGUI(clientManager);
            clientManager.setServerGUI(serverGUI);

            ChatServer chatServer = new ChatServer(clientManager, serverGUI, roomManager, databaseManager);
            serverGUI.setChatServer(chatServer);
            if (java.util.Arrays.asList(args).contains("--start")) {
                serverGUI.startServer();
            }
            } catch (IllegalStateException e) {
                javax.swing.JOptionPane.showMessageDialog(null, e.getMessage(), "Không mở được máy chủ", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
