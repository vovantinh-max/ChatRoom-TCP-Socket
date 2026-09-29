package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ServerListener implements Runnable {

    private final BufferedReader reader;
    private final ChatClient client;
    private volatile boolean running = true;

    public ServerListener(BufferedReader reader, ChatClient client) {
        this.reader = reader;
        this.client = client;
    }

    @Override
    public void run() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                processMessage(line);
            }
        } catch (IOException e) {
            // connection dropped
        } finally {
            if (running) {
                running = false;
                client.notifyDisconnected("Mất kết nối với Server.");
            }
        }
    }

    void processMessage(String message) {
        String[] parts = message.split("\\|", -1);
        String command = parts[0];

        switch (command) {
            case "USER_LIST":
                if (parts.length >= 2) {
                    String userString = parts[1];
                    List<String> users = new ArrayList<>();
                    if (!userString.isEmpty()) {
                        for (String u : userString.split(",")) {
                            String trimmed = u.trim();
                            if (!trimmed.isEmpty()) {
                                users.add(trimmed);
                            }
                        }
                    }
                    client.notifyUserList(users);
                }
                break;

            case "ROOM_LIST":
                if (parts.length >= 2) {
                    String roomString = parts[1];
                    List<String> rooms = new ArrayList<>();
                    if (!roomString.isEmpty()) {
                        for (String r : roomString.split(",")) {
                            String trimmed = r.trim();
                            if (!trimmed.isEmpty()) {
                                rooms.add(trimmed);
                            }
                        }
                    }
                    client.notifyRoomList(rooms);
                }
                break;

            case "MESSAGE":
                if (parts.length >= 3) {
                    String sender = parts[1];
                    String content = parts[2];
                    client.notifyMessage(sender, content);
                } else if (parts.length == 2) {
                    client.notifySystemMessage(parts[1]);
                }
                break;

            case "PRIVATE":
                if (parts.length >= 4) {
                    String sender = parts[1];
                    String receiver = parts[2];
                    String content = parts[3];
                    client.notifyPrivateMessage(sender, receiver, content);
                }
                break;

            case "LOGOUT":
                if (parts.length >= 2) {
                    client.notifySystemMessage(parts[1] + " đã rời khỏi phòng chat.");
                }
                break;

            case "LOGIN_SUCCESS":
            case "LOGIN_OK":
                client.notifySystemMessage("Đăng nhập thành công!");
                break;

            case "KICK":
            case "DISCONNECT":
                String kickReason = (parts.length >= 2) ? parts[1] : "Bạn đã bị xóa khỏi Server.";
                running = false;
                client.notifyDisconnected(kickReason);
                break;

            case "LOGIN_FAILED":
            case "ERROR":
                String reason = (parts.length >= 2) ? parts[1] : "Có lỗi xảy ra từ máy chủ.";
                client.notifySystemMessage("[LỖI] " + reason);
                break;

            case "SYSTEM":
            case "INFO":
                if (parts.length >= 2) {
                    client.notifySystemMessage(parts[1]);
                }
                break;

            default:
                client.notifySystemMessage(message);
                break;
        }
    }

    public void stopListening() {
        this.running = false;
    }
}
