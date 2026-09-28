package server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ClientManager clientManager;
    private final RoomManager roomManager;

    private BufferedReader reader;
    private PrintWriter writer;

    private String username;
    private boolean registered = false;
    private String currentRoom = "General";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    public ClientHandler(
            Socket socket,
            ClientManager clientManager,
            RoomManager roomManager) {

        this.socket = socket;
        this.clientManager = clientManager;
        this.roomManager = roomManager;
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

            // LOGIN
            writer.println("USERNAME_REQUIRED");

            String login = reader.readLine();

            if (login == null || login.trim().isEmpty()) {
                return;
            }

            login = login.trim();

            if (login.startsWith("LOGIN|")) {
                username = login.substring(6).trim();
            } else {
                username = login;
            }

            if (username.isEmpty()) {
                writer.println("LOGIN_FAILED|Username khong hop le.");
                return;
            }

            if (clientManager.isUsernameTaken(username)) {
                writer.println("USERNAME_TAKEN");
                writer.println("LOGIN_FAILED|Username da ton tai.");
                return;
            }

            writer.println("LOGIN_SUCCESS");

            clientManager.addClient(this);
            registered = true;

            roomManager.joinRoom("General", this);
            currentRoom = "General";

            broadcastUserList();

            clientManager.logMessage(
                    getTimestamp() + " [LOGIN] "
                            + username + " da ket noi."
            );

            roomManager.broadcastToRoom(
                    "General",
                    getTimestamp() + " [SERVER] "
                            + username
                            + " da tham gia phong General."
            );

            // NHẬN MESSAGE
            String message;

            while ((message = reader.readLine()) != null) {

                message = message.trim();

                if (message.isEmpty()) {
                    continue;
                }

                // LOGOUT
                if (message.equalsIgnoreCase("/quit")
                        || message.equalsIgnoreCase("LOGOUT")
                        || message.equalsIgnoreCase(
                                "LOGOUT|" + username)) {
                    break;
                }

                // CHAT
                if (message.startsWith("MESSAGE|")) {
                    handleMessage(message);
                    continue;
                }

                // PRIVATE
                if (message.startsWith("PRIVATE|")) {
                    handlePrivateMessageProtocol(message);
                    continue;
                }

                // ROOM LIST
                if (message.equalsIgnoreCase("ROOMS")
                        || message.equalsIgnoreCase("/rooms")) {
                    handleRoomList();
                    continue;
                }

                // JOIN ROOM
                if (message.startsWith("JOIN|")) {
                    joinRoom(message.substring(5).trim());
                    continue;
                }

                if (message.toLowerCase().startsWith("/join ")) {
                    joinRoom(message.substring(6).trim());
                    continue;
                }

                // LEAVE ROOM
                if (message.equalsIgnoreCase("LEAVE")
                        || message.equalsIgnoreCase("/leave")) {
                    leaveRoom();
                    continue;
                }

                // PRIVATE CŨ
                if (message.startsWith("/pm ")) {
                    handleOldPrivateMessage(message);
                    continue;
                }

                // FILE
                if (message.startsWith("/file ")) {
                    handleFileCommand(message);
                    continue;
                }

                // MESSAGE CŨ
                handleOldMessage(message);
            }

        } catch (IOException e) {

            clientManager.logMessage(
                    getTimestamp()
                            + " [ERROR] Client "
                            + username
                            + ": "
                            + e.getMessage()
            );

        } finally {
            disconnect();
        }
    }

    // =========================
    // CHAT
    // =========================

    private void handleMessage(String message) {

        String[] parts = message.split("\\|", 3);

        if (parts.length < 3) {
            sendMessage("ERROR|Sai cu phap MESSAGE.");
            return;
        }

        String content = parts[2].trim();

        if (content.isEmpty()) {
            return;
        }

        String time = getTimestamp();

        roomManager.broadcastToRoom(
                currentRoom,
                "MESSAGE|"
                        + username
                        + "|"
                        + time
                        + " "
                        + content
        );

        clientManager.logMessage(
                time + " "
                        + username
                        + ": "
                        + content
        );
    }

    private void handleOldMessage(String message) {

        String content = message.trim();

        if (content.isEmpty()) {
            return;
        }

        String time = getTimestamp();

        roomManager.broadcastToRoom(
                currentRoom,
                "MESSAGE|"
                        + username
                        + "|"
                        + time
                        + " "
                        + content
        );

        clientManager.logMessage(
                time + " "
                        + username
                        + ": "
                        + content
        );
    }

    // =========================
    // PRIVATE MESSAGE
    // =========================

    private void handlePrivateMessageProtocol(
            String message) {

        String[] parts = message.split("\\|", 4);

        if (parts.length < 4) {
            sendMessage("ERROR|Sai cu phap PRIVATE.");
            return;
        }

        String targetUsername = parts[2].trim();
        String content = parts[3].trim();

        sendPrivateMessage(
                targetUsername,
                content
        );
    }

    private void handleOldPrivateMessage(
            String message) {

        String content = message.substring(4).trim();
        int index = content.indexOf(" ");

        if (index == -1) {
            sendMessage(
                    "ERROR|Dung: /pm <username> <message>"
            );
            return;
        }

        String targetUsername =
                content.substring(0, index).trim();

        String privateMessage =
                content.substring(index + 1).trim();

        sendPrivateMessage(
                targetUsername,
                privateMessage
        );
    }

    private void sendPrivateMessage(
            String targetUsername,
            String content) {

        if (targetUsername.isEmpty()
                || content.isEmpty()) {

            sendMessage(
                    "ERROR|Tin nhan private khong hop le."
            );
            return;
        }

        ClientHandler target =
                findClient(targetUsername);

        if (target == null) {
            sendMessage(
                    "ERROR|Khong tim thay Client: "
                            + targetUsername
            );
            return;
        }

        String time = getTimestamp();

        String packet =
                "PRIVATE|"
                        + username
                        + "|"
                        + targetUsername
                        + "|"
                        + time
                        + " "
                        + content;

        target.sendMessage(packet);

        if (target != this) {
            sendMessage(packet);
        }

        clientManager.logMessage(
                time
                        + " [PRIVATE] "
                        + username
                        + " -> "
                        + targetUsername
                        + ": "
                        + content
        );
    }

    private ClientHandler findClient(
            String name) {

        for (ClientHandler client :
                clientManager.getClients()) {

            if (name.equalsIgnoreCase(
                    client.getUsername())) {

                return client;
            }
        }

        return null;
    }

    // =========================
    // USER LIST
    // =========================

    private void broadcastUserList() {

        StringBuilder users =
                new StringBuilder();

        for (String name :
                clientManager.getUsernames()) {

            if (users.length() > 0) {
                users.append(",");
            }

            users.append(name);
        }

        clientManager.broadcast(
                "USER_LIST|" + users
        );
    }

    // =========================
    // ROOM
    // =========================

    private void handleRoomList() {

        sendMessage(
                getTimestamp()
                        + " [SERVER] Danh sach phong:"
        );

        for (String room :
                roomManager.getRoomNames()) {

            sendMessage(
                    getTimestamp()
                            + " [SERVER] "
                            + room
                            + " ("
                            + roomManager.getClientCount(room)
                            + " client)"
            );
        }

        sendMessage(
                getTimestamp()
                        + " [SERVER] Phong hien tai: "
                        + currentRoom
        );
    }

    private void joinRoom(String roomName) {

        if (roomName.isEmpty()) {
            sendMessage("ERROR|Ten phong khong hop le.");
            return;
        }

        if (!roomManager.roomExists(roomName)) {
            sendMessage(
                    "ERROR|Phong '"
                            + roomName
                            + "' khong ton tai."
            );
            return;
        }

        if (currentRoom.equalsIgnoreCase(roomName)) {
            sendMessage(
                    "INFO|Ban dang o phong "
                            + roomName
            );
            return;
        }

        String oldRoom = currentRoom;

        if (!roomManager.joinRoom(roomName, this)) {
            sendMessage(
                    "ERROR|Khong the tham gia phong."
            );
            return;
        }

        currentRoom = roomName;

        String time = getTimestamp();

        clientManager.logMessage(
                time
                        + " [ROOM] "
                        + username
                        + " chuyen tu "
                        + oldRoom
                        + " -> "
                        + roomName
        );

        sendMessage(
                "INFO|"
                        + time
                        + " Ban da vao phong "
                        + roomName
        );

        roomManager.broadcastToRoom(
                roomName,
                time
                        + " [SERVER] "
                        + username
                        + " da tham gia phong."
        );
    }

    private void leaveRoom() {

        if (currentRoom.equalsIgnoreCase("General")) {
            sendMessage(
                    "INFO|"
                            + getTimestamp()
                            + " Ban dang o phong General."
            );
            return;
        }

        String oldRoom = currentRoom;

        roomManager.joinRoom(
                "General",
                this
        );

        currentRoom = "General";

        String time = getTimestamp();

        clientManager.logMessage(
                time
                        + " [ROOM] "
                        + username
                        + " roi phong "
                        + oldRoom
                        + " -> General"
        );

        sendMessage(
                "INFO|"
                        + time
                        + " Ban da tro ve phong General."
        );

        roomManager.broadcastToRoom(
                "General",
                time
                        + " [SERVER] "
                        + username
                        + " da tham gia phong General."
        );
    }

    // =========================
    // FILE TRANSFER
    // =========================

    private void handleFileCommand(
            String message) {

        String content =
                message.substring(6).trim();

        int index = content.indexOf(" ");

        if (index == -1) {
            sendMessage(
                    "ERROR|Dung: /file <username> <duong_dan_file>"
            );
            return;
        }

        String targetUsername =
                content.substring(0, index).trim();

        String filePath =
                content.substring(index + 1).trim();

        sendFile(
                targetUsername,
                filePath
        );
    }

    private void sendFile(
            String targetUsername,
            String filePath) {

        File file = new File(filePath);

        if (!file.exists() || !file.isFile()) {
            sendMessage(
                    "ERROR|File khong ton tai."
            );
            return;
        }

        ClientHandler target =
                findClient(targetUsername);

        if (target == null) {
            sendMessage(
                    "ERROR|Khong tim thay Client: "
                            + targetUsername
            );
            return;
        }

        try (FileInputStream input =
                     new FileInputStream(file)) {

            byte[] buffer = new byte[3072];
            int bytesRead;

            target.sendMessage(
                    "FILE_START|"
                            + file.getName()
                            + "|"
                            + file.length()
            );

            while ((bytesRead =
                    input.read(buffer)) != -1) {

                String encoded =
                        Base64.getEncoder()
                                .encodeToString(
                                        Arrays.copyOf(
                                                buffer,
                                                bytesRead
                                        )
                                );

                target.sendMessage(
                        "FILE_DATA|"
                                + encoded
                );
            }

            target.sendMessage("FILE_END");

            sendMessage(
                    "INFO|"
                            + getTimestamp()
                            + " Da gui file "
                            + file.getName()
                            + " cho "
                            + targetUsername
            );

            clientManager.logMessage(
                    getTimestamp()
                            + " [FILE] "
                            + username
                            + " -> "
                            + targetUsername
                            + ": "
                            + file.getName()
            );

        } catch (IOException e) {

            sendMessage(
                    "ERROR|Loi gui file: "
                            + e.getMessage()
            );
        }
    }

    // =========================
    // UTILITY
    // =========================

    private String getTimestamp() {

        return "["
                + LocalDateTime.now()
                .format(TIME_FORMAT)
                + "]";
    }

    public void sendMessage(String message) {

        if (writer != null) {
            writer.println(message);
        }
    }

    public String getUsername() {
        return username;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    // =========================
    // DISCONNECT
    // =========================

    private void disconnect() {

        if (registered) {

            String leaveMessage =
                    getTimestamp()
                            + " [SERVER] "
                            + username
                            + " da roi phong chat.";

            clientManager.logMessage(
                    leaveMessage
            );

            roomManager.broadcastToRoom(
                    currentRoom,
                    leaveMessage
            );

            roomManager.removeClient(this);
            clientManager.removeClient(this);

            registered = false;

            broadcastUserList();
        }

        try {

            if (socket != null &&
                    !socket.isClosed()) {

                socket.close();
            }

        } catch (IOException e) {

            System.out.println(
                    "Loi khi dong Socket."
            );
        }
    }
}