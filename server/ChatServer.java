package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ChatServer {

    private static final int PORT = 5000;

    public static void main(String[] args) {
        System.out.println("Starting Chat Server...");

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {

            System.out.println("Server is running on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();

                System.out.println(
                    "Client connected: "
                    + clientSocket.getInetAddress().getHostAddress()
                );
            }

        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }
}