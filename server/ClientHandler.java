package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ClientManager clientManager;

    private BufferedReader reader;
    private PrintWriter writer;

    private String username;

    public ClientHandler(
            Socket socket,
            ClientManager clientManager) {

        this.socket = socket;
        this.clientManager = clientManager;
    }

    @Override
    public void run() {

        try {

            reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            writer = new PrintWriter(
                    socket.getOutputStream(),
                    true,
                    StandardCharsets.UTF_8
            );

            // Yêu cầu Client gửi username
            writer.println("USERNAME_REQUIRED");

            username = reader.readLine();

            if (username == null ||
                    username.trim().isEmpty()) {

                return;
            }

            username = username.trim();

            // Kiểm tra username trùng
            if (clientManager.isUsernameTaken(username)) {

                writer.println("USERNAME_TAKEN");
                return;
            }

            // Thông báo đăng nhập thành công
            writer.println("LOGIN_SUCCESS");

            // Thêm Client vào danh sách
            clientManager.addClient(this);

            System.out.println(
                    username + " da ket noi."
            );

            // Thông báo cho mọi người
            clientManager.broadcast(
                    "[SERVER] " + username
                            + " da tham gia phong chat."
            );

            String message;

            // Liên tục nhận tin nhắn
            while ((message = reader.readLine()) != null) {

                message = message.trim();

                if (message.isEmpty()) {
                    continue;
                }

                // Lệnh thoát
                if (message.equalsIgnoreCase("/quit")) {
                    break;
                }

                String fullMessage =
                        username + ": " + message;

                System.out.println(fullMessage);

                // Gửi cho tất cả Client
                clientManager.broadcast(fullMessage);
            }

        } catch (IOException e) {

            System.out.println(
                    "Client bi ngat ket noi: "
                            + e.getMessage()
            );

        } finally {

            disconnect();
        }
    }

    // Gửi dữ liệu về Client
    public void sendMessage(String message) {

        if (writer != null) {
            writer.println(message);
        }
    }

    public String getUsername() {
        return username;
    }

    // Xử lý khi Client rời phòng
    private void disconnect() {

        if (username != null) {

            clientManager.removeClient(this);

            clientManager.broadcast(
                    "[SERVER] " + username
                            + " da roi phong chat."
            );
        }

        try {
            socket.close();
        } catch (IOException e) {
            System.out.println(
                    "Loi khi dong Socket."
            );
        }
    }
}