package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ChatServer {

    private static final int PORT = 5000;

    private final ClientManager clientManager;

    private ServerSocket serverSocket;

    private volatile boolean running = false;

    private ServerGUI serverGUI;

    public ChatServer(
            ClientManager clientManager,
            ServerGUI serverGUI) {

        this.clientManager = clientManager;

        this.serverGUI = serverGUI;
    }

    // =========================
    // START SERVER
    // =========================

    public boolean startServer() {

        if (running) {
            return false;
        }

        try {

            serverSocket =
                    new ServerSocket(PORT);

            running = true;

            Thread serverThread =
                    new Thread(
                            this::acceptClients
                    );

            serverThread.start();

            return true;

        } catch (IOException e) {

            if (serverGUI != null) {

                serverGUI.appendLog(
                        "Không thể khởi động Server: "
                                + e.getMessage()
                );
            }

            return false;
        }
    }

    // =========================
    // CHỜ CLIENT
    // =========================

    private void acceptClients() {

        if (serverGUI != null) {

            serverGUI.appendLog(
                    "Server đang chờ Client kết nối..."
            );
        }

        while (running) {

            try {

                Socket clientSocket =
                        serverSocket.accept();

                if (serverGUI != null) {

                    serverGUI.appendLog(
                            "Client mới kết nối: "
                                    + clientSocket
                                    .getInetAddress()
                                    .getHostAddress()
                    );
                }

                ClientHandler clientHandler =
                        new ClientHandler(
                                clientSocket,
                                clientManager
                        );

                Thread clientThread =
                        new Thread(
                                clientHandler
                        );

                clientThread.start();

            } catch (IOException e) {

                if (running &&
                        serverGUI != null) {

                    serverGUI.appendLog(
                            "Lỗi nhận Client: "
                                    + e.getMessage()
                    );
                }
            }
        }
    }

    // =========================
    // STOP SERVER
    // =========================

    public void stopServer() {

        running = false;

        try {

            if (serverSocket != null &&
                    !serverSocket.isClosed()) {

                serverSocket.close();
            }

        } catch (IOException e) {

            if (serverGUI != null) {

                serverGUI.appendLog(
                        "Lỗi khi dừng Server: "
                                + e.getMessage()
                );
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public ClientManager getClientManager() {
        return clientManager;
    }

    // =========================
    // MAIN
    // =========================

    public static void main(String[] args) {

        javax.swing.SwingUtilities.invokeLater(() -> {

            ClientManager clientManager =
                    new ClientManager();

            ServerGUI serverGUI =
                    new ServerGUI(
                            clientManager
                    );

            ChatServer chatServer =
                    new ChatServer(
                            clientManager,
                            serverGUI
                    );

            serverGUI.setChatServer(
                    chatServer
            );
        });
    }
}