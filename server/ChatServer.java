package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ChatServer {

    private static final int PORT = 5000;

    // Quản lý toàn bộ Client
    private static final ClientManager clientManager =
            new ClientManager();

    public static void main(String[] args) {

        System.out.println("=================================");
        System.out.println("       TCP CHAT ROOM SERVER");
        System.out.println("=================================");

        try (ServerSocket serverSocket =
                     new ServerSocket(PORT)) {

            System.out.println(
                    "Server da khoi dong!"
            );

            System.out.println(
                    "Port: " + PORT
            );

            System.out.println(
                    "Dang cho Client ket noi..."
            );

            while (true) {

                // Chờ Client
                Socket clientSocket =
                        serverSocket.accept();

                System.out.println(
                        "Client moi ket noi: "
                                + clientSocket
                                .getInetAddress()
                                .getHostAddress()
                );

                // Tạo ClientHandler
                ClientHandler clientHandler =
                        new ClientHandler(
                                clientSocket,
                                clientManager
                        );

                // Tạo Thread riêng
                Thread clientThread =
                        new Thread(clientHandler);

                clientThread.start();

                System.out.println(
                        "Da tao Thread xu ly Client."
                );
            }

        } catch (IOException e) {

            System.out.println(
                    "Khong the khoi dong Server!"
            );

            System.out.println(
                    "Loi: " + e.getMessage()
            );
        }
    }
}