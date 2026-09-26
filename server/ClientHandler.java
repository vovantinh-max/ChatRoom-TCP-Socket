package server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
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

    // Client đã thực sự vào phòng chưa?
    private boolean registered = false;

    // Phòng hiện tại
    private String currentRoom = "General";

    // Định dạng ngày giờ
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    // ==========================================
    // CONSTRUCTOR
    // ==========================================

    public ClientHandler(
            Socket socket,
            ClientManager clientManager,
            RoomManager roomManager) {

        this.socket = socket;
        this.clientManager = clientManager;
        this.roomManager = roomManager;
    }

    // ==========================================
    // RUN
    // ==========================================

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

            // ==========================================
            // YÊU CẦU USERNAME
            // ==========================================

            writer.println("USERNAME_REQUIRED");

            username = reader.readLine();

            if (username == null ||
                    username.trim().isEmpty()) {

                return;
            }

            username = username.trim();

            // ==========================================
            // KIỂM TRA USERNAME TRÙNG
            // ==========================================

            if (clientManager.isUsernameTaken(
                    username)) {

                writer.println("USERNAME_TAKEN");

                return;
            }

            // ==========================================
            // ĐĂNG NHẬP THÀNH CÔNG
            // ==========================================

            writer.println("LOGIN_SUCCESS");

            // ==========================================
            // THÊM CLIENT VÀO SERVER
            // ==========================================

            clientManager.addClient(this);

            registered = true;

            // ==========================================
            // THAM GIA PHÒNG GENERAL
            // ==========================================

            roomManager.joinRoom(
                    "General",
                    this
            );

            currentRoom = "General";

            System.out.println(
                    getTimestamp()
                            + " "
                            + username
                            + " da ket noi."
            );

            // ==========================================
            // THÔNG BÁO CLIENT THAM GIA
            // ==========================================

            String joinMessage =
                    getTimestamp()
                            + " [SERVER] "
                            + username
                            + " da tham gia phong chat.";

            clientManager.logMessage(
                    joinMessage
            );

            clientManager.broadcast(
                    joinMessage
            );

            // ==========================================
            // VÒNG LẶP NHẬN TIN NHẮN
            // ==========================================

            String message;

            while ((message =
                    reader.readLine()) != null) {

                message = message.trim();

                if (message.isEmpty()) {
                    continue;
                }

                // ==========================================
                // LỆNH QUIT
                // ==========================================

                if (message.equalsIgnoreCase(
                        "/quit")) {

                    break;
                }

                // ==========================================
                // XEM DANH SÁCH PHÒNG
                // ==========================================

                if (message.equalsIgnoreCase(
                        "/rooms")) {

                    handleRoomList();

                    continue;
                }

                // ==========================================
                // JOIN ROOM
                // ==========================================

                if (message.toLowerCase()
                        .startsWith("/join ")) {

                    handleJoinRoom(message);

                    continue;
                }

                // ==========================================
                // LEAVE ROOM
                // ==========================================

                if (message.equalsIgnoreCase(
                        "/leave")) {

                    handleLeaveRoom();

                    continue;
                }

                // ==========================================
                // PRIVATE CHAT
                // ==========================================

                if (message.startsWith("/pm ")) {

                    handlePrivateMessage(message);

                    continue;
                }

                // ==========================================
                // FILE TRANSFER
                // ==========================================

                if (message.startsWith("/file ")) {

                    handleFileCommand(message);

                    continue;
                }

                // ==========================================
                // CHAT THƯỜNG
                // ==========================================

                String fullMessage =
                        getTimestamp()
                                + " "
                                + username
                                + ": "
                                + message;

                // Ghi vào Server Log
                clientManager.logMessage(
                        fullMessage
                );

                // Chỉ gửi cho Client trong cùng phòng
                roomManager.broadcastToRoom(
                        currentRoom,
                        fullMessage
                );
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

    // ==========================================
    // ROOM LIST
    // ==========================================

    private void handleRoomList() {

        sendMessage(
                "[SERVER] Danh sach phong:"
        );

        for (String roomName :
                roomManager.getRoomNames()) {

            int count =
                    roomManager.getClientCount(
                            roomName
                    );

            sendMessage(
                    " - "
                            + roomName
                            + " ("
                            + count
                            + " client)"
            );
        }

        sendMessage(
                "[SERVER] Phong hien tai: "
                        + currentRoom
        );
    }

    // ==========================================
    // JOIN ROOM
    // ==========================================

    private void handleJoinRoom(
            String message) {

        String roomName =
                message.substring(6).trim();

        if (roomName.isEmpty()) {

            sendMessage(
                    "[SERVER] Sai cu phap. "
                            + "Dung: /join <room>"
            );

            return;
        }

        // Kiểm tra phòng tồn tại
        if (!roomManager.roomExists(
                roomName)) {

            sendMessage(
                    "[SERVER] Phong '"
                            + roomName
                            + "' khong ton tai."
            );

            sendMessage(
                    "[SERVER] Dung /rooms "
                            + "de xem danh sach phong."
            );

            return;
        }

        // Nếu đang ở phòng đó
        if (currentRoom.equals(roomName)) {

            sendMessage(
                    "[SERVER] Ban dang o phong "
                            + roomName
            );

            return;
        }

        String oldRoom =
                currentRoom;

        // Chuyển phòng
        boolean joined =
                roomManager.joinRoom(
                        roomName,
                        this
                );

        if (!joined) {

            sendMessage(
                    "[SERVER] Khong the tham gia phong."
            );

            return;
        }

        currentRoom = roomName;

        // ==========================================
        // LOG SERVER
        // ==========================================

        String logMessage =
                getTimestamp()
                        + " [ROOM] "
                        + username
                        + " chuyen tu "
                        + oldRoom
                        + " -> "
                        + roomName;

        clientManager.logMessage(
                logMessage
        );

        // ==========================================
        // THÔNG BÁO CLIENT
        // ==========================================

        sendMessage(
                getTimestamp()
                        + " [SERVER] "
                        + "Ban da vao phong "
                        + roomName
        );

        roomManager.broadcastToRoom(
                roomName,
                getTimestamp()
                        + " [SERVER] "
                        + username
                        + " da tham gia phong."
        );
    }

    // ==========================================
    // LEAVE ROOM
    // ==========================================

    private void handleLeaveRoom() {

        // Không cho rời General
        if (currentRoom.equals("General")) {

            sendMessage(
                    "[SERVER] Ban dang o phong General."
            );

            return;
        }

        String oldRoom =
                currentRoom;

        // Chuyển về General
        roomManager.joinRoom(
                "General",
                this
        );

        currentRoom = "General";

        // ==========================================
        // LOG SERVER
        // ==========================================

        String logMessage =
                getTimestamp()
                        + " [ROOM] "
                        + username
                        + " roi phong "
                        + oldRoom
                        + " -> General";

        clientManager.logMessage(
                logMessage
        );

        // ==========================================
        // THÔNG BÁO
        // ==========================================

        sendMessage(
                getTimestamp()
                        + " [SERVER] "
                        + "Ban da tro ve phong General."
        );

        roomManager.broadcastToRoom(
                "General",
                getTimestamp()
                        + " [SERVER] "
                        + username
                        + " da tham gia phong General."
        );
    }

    // ==========================================
    // PRIVATE CHAT
    // ==========================================

    private void handlePrivateMessage(
            String message) {

        /*
         * Cú pháp:
         *
         * /pm username nội dung
         *
         * Ví dụ:
         * /pm Nam Xin chao Nam
         */

        String content =
                message.substring(4).trim();

        int spaceIndex =
                content.indexOf(" ");

        if (spaceIndex == -1) {

            sendMessage(
                    "[SERVER] Sai cu phap. "
                            + "Dung: /pm <username> <message>"
            );

            return;
        }

        String targetUsername =
                content.substring(
                        0,
                        spaceIndex
                ).trim();

        String privateMessage =
                content.substring(
                        spaceIndex + 1
                ).trim();

        if (targetUsername.isEmpty() ||
                privateMessage.isEmpty()) {

            sendMessage(
                    "[SERVER] Sai cu phap. "
                            + "Dung: /pm <username> <message>"
            );

            return;
        }

        String formattedMessage =
                getTimestamp()
                        + " [PRIVATE] "
                        + username
                        + " -> "
                        + targetUsername
                        + ": "
                        + privateMessage;

        // Ghi vào Server Log
        clientManager.logMessage(
                formattedMessage
        );

        // Gửi đến Client đích
        boolean sent =
                clientManager.sendToClient(
                        targetUsername,
                        formattedMessage
                );

        if (sent) {

            // Thông báo cho người gửi
            sendMessage(
                    getTimestamp()
                            + " [PRIVATE] Ban -> "
                            + targetUsername
                            + ": "
                            + privateMessage
            );

        } else {

            sendMessage(
                    getTimestamp()
                            + " [SERVER] Khong tim thay Client: "
                            + targetUsername
            );
        }
    }

    // ==========================================
    // FILE TRANSFER
    // ==========================================

    private void handleFileCommand(
            String message) {

        /*
         * Cú pháp:
         *
         * /file username đường_dẫn_file
         *
         * Ví dụ:
         *
         * /file Nam C:\Users\Tinh\Desktop\test.pdf
         */

        String content =
                message.substring(6).trim();

        int spaceIndex =
                content.indexOf(" ");

        if (spaceIndex == -1) {

            sendMessage(
                    "[SERVER] Sai cu phap. "
                            + "Dung: /file <username> <duong_dan_file>"
            );

            return;
        }

        String targetUsername =
                content.substring(
                        0,
                        spaceIndex
                ).trim();

        String filePath =
                content.substring(
                        spaceIndex + 1
                ).trim();

        sendFile(
                targetUsername,
                filePath
        );
    }

    // ==========================================
    // SEND FILE
    // ==========================================

    private void sendFile(
            String targetUsername,
            String filePath) {

        File file =
                new File(filePath);

        // Kiểm tra file
        if (!file.exists() ||
                !file.isFile()) {

            sendMessage(
                    "[SERVER] File khong ton tai."
            );

            return;
        }

        // Tìm Client nhận
        ClientHandler target = null;

        for (ClientHandler client :
                clientManager.getClients()) {

            if (targetUsername.equalsIgnoreCase(
                    client.getUsername())) {

                target = client;

                break;
            }
        }

        if (target == null) {

            sendMessage(
                    "[SERVER] Khong tim thay Client: "
                            + targetUsername
            );

            return;
        }

        try {

            FileInputStream input =
                    new FileInputStream(file);

            byte[] buffer =
                    new byte[3072];

            int bytesRead;

            // ==========================================
            // BẮT ĐẦU FILE
            // ==========================================

            target.sendMessage(
                    "FILE_START|"
                            + file.getName()
                            + "|"
                            + file.length()
            );

            // ==========================================
            // ĐỌC VÀ GỬI FILE
            // ==========================================

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

            // ==========================================
            // KẾT THÚC FILE
            // ==========================================

            target.sendMessage(
                    "FILE_END"
            );

            input.close();

            // ==========================================
            // THÔNG BÁO NGƯỜI GỬI
            // ==========================================

            sendMessage(
                    "[SERVER] Da gui file "
                            + file.getName()
                            + " cho "
                            + targetUsername
            );

            // ==========================================
            // SERVER LOG
            // ==========================================

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
                    "[SERVER] Loi gui file: "
                            + e.getMessage()
            );
        }
    }

    // ==========================================
    // TIMESTAMP
    // ==========================================

    private String getTimestamp() {

        return "["
                + LocalDateTime.now()
                .format(TIME_FORMAT)
                + "]";
    }

    // ==========================================
    // SEND MESSAGE
    // ==========================================

    public void sendMessage(String message) {

        if (writer != null) {

            writer.println(message);
        }
    }

    // ==========================================
    // GET USERNAME
    // ==========================================

    public String getUsername() {

        return username;
    }

    // ==========================================
    // GET CURRENT ROOM
    // ==========================================

    public String getCurrentRoom() {

        return currentRoom;
    }

    // ==========================================
    // DISCONNECT
    // ==========================================

    private void disconnect() {

        if (registered) {

            // Thông báo trước khi xóa Client
            String leaveMessage =
                    getTimestamp()
                            + " [SERVER] "
                            + username
                            + " da roi phong chat.";

            clientManager.logMessage(
                    leaveMessage
            );

            // Thông báo cho Client
            // trong cùng phòng
            roomManager.broadcastToRoom(
                    currentRoom,
                    leaveMessage
            );

            // Xóa khỏi Room
            roomManager.removeClient(
                    this
            );

            // Xóa khỏi ClientManager
            clientManager.removeClient(
                    this
            );

            registered = false;
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