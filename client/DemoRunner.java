package client;

import client.ui.ChatFrame;
import client.ui.LoginFrame;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DemoRunner: Khởi động máy chủ thử nghiệm mini và 2 Client giả lập,
 * sau đó chụp ảnh màn hình giao diện (Login và Chat) thành các file ảnh PNG.
 */
public class DemoRunner {

    private static final int PORT = 5000;
    private static final Map<String, PrintWriter> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("1. Chụp ảnh màn hình Đăng nhập...");
        captureLoginFrame();

        System.out.println("2. Khởi động Server thử nghiệm nội bộ cổng " + PORT + "...");
        Thread serverThread = new Thread(DemoRunner::runMockServer);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(800);

        System.out.println("3. Khởi tạo Client Tinh, Nam và An...");
        ChatClient clientTinh = new ChatClient();
        ChatClient clientNam = new ChatClient();
        ChatClient clientAn = new ChatClient();

        final ChatFrame[] frameHolder = new ChatFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                clientTinh.connect("127.0.0.1", PORT, "VoVanTinh");
                ChatFrame chatFrame = new ChatFrame(clientTinh, null);
                chatFrame.setSize(960, 660);
                chatFrame.setLocationRelativeTo(null);
                chatFrame.setVisible(true);
                frameHolder[0] = chatFrame;

                clientNam.connect("127.0.0.1", PORT, "NguyenVanNam");
                clientAn.connect("127.0.0.1", PORT, "TranThiAn");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        Thread.sleep(1200);

        System.out.println("4. Gửi các tin nhắn mô phỏng...");
        // Tinh gửi tin nhắn chung
        clientTinh.sendPublicMessage("Xin chào mọi người! Hệ thống Chat TCP phòng chat đã sẵn sàng hoạt động!");
        Thread.sleep(600);

        // Nam gửi tin nhắn chung
        clientNam.sendPublicMessage("Chào Tinh! Giao diện mới dạng bong bóng chat nhìn rất đẹp và trực quan (y)");
        Thread.sleep(600);

        // An gửi tin nhắn chung
        clientAn.sendPublicMessage("Chào cả nhóm! Giao diện có cả avatar màu sắc và điểm báo online xịn quá ^^");
        Thread.sleep(600);

        // Nam gửi tin nhắn riêng cho Tinh
        clientNam.sendPrivateMessage("VoVanTinh", "Chiều nay 2h tụi mình họp online để ghép nối phần Server và Client nhé!");
        Thread.sleep(600);

        // Tinh trả lời riêng cho Nam
        SwingUtilities.invokeAndWait(() -> {
            clientTinh.sendPrivateMessage("NguyenVanNam", "Nhất trí nhé Nam, mã nguồn Client đã sẵn sàng trên GitHub rồi!");
            frameHolder[0].addPrivateMessage("VoVanTinh", "NguyenVanNam", "Nhất trí nhé Nam, mã nguồn Client đã sẵn sàng trên GitHub rồi!", true);
        });

        Thread.sleep(1500);

        System.out.println("5. Chụp ảnh màn hình giao diện Chat...");
        SwingUtilities.invokeAndWait(() -> {
            captureFrame(frameHolder[0], "demo_chat.png");
            frameHolder[0].dispose();
        });

        clientTinh.disconnect();
        clientNam.disconnect();
        clientAn.disconnect();
        System.out.println("Hoàn tất! Đã lưu demo_login.png và demo_chat.png");
        System.exit(0);
    }

    private static void captureLoginFrame() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LoginFrame loginFrame = new LoginFrame();
            loginFrame.setSize(480, 580);
            loginFrame.setLocationRelativeTo(null);
            loginFrame.setVisible(true);

            captureFrame(loginFrame, "demo_login.png");
            loginFrame.dispose();
        });
    }

    private static void captureFrame(JFrame frame, String fileName) {
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        frame.paint(g2);
        g2.dispose();

        try {
            File output = new File(fileName);
            ImageIO.write(image, "png", output);
            System.out.println("Đã xuất ảnh: " + output.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void runMockServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket)).start();
            }
        } catch (IOException e) {
            // Server kết thúc
        }
    }

    private static void handleClient(Socket socket) {
        String clientUser = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\|", -1);
                String cmd = parts[0];

                if ("LOGIN".equals(cmd) && parts.length >= 2) {
                    clientUser = parts[1];
                    clients.put(clientUser, out);
                    out.println("LOGIN_SUCCESS");
                    broadcastUserList();
                    broadcast("MESSAGE|Hệ thống|" + clientUser + " đã tham gia phòng chat.");
                } else if ("MESSAGE".equals(cmd) && parts.length >= 3) {
                    broadcast(line);
                } else if ("PRIVATE".equals(cmd) && parts.length >= 4) {
                    String sender = parts[1];
                    String receiver = parts[2];
                    String content = parts[3];
                    PrintWriter targetOut = clients.get(receiver);
                    if (targetOut != null) {
                        targetOut.println("PRIVATE|" + sender + "|" + receiver + "|" + content);
                    }
                } else if ("LOGOUT".equals(cmd)) {
                    break;
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (clientUser != null) {
                clients.remove(clientUser);
                broadcastUserList();
                broadcast("MESSAGE|Hệ thống|" + clientUser + " đã rời khỏi phòng chat.");
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private static void broadcast(String msg) {
        for (PrintWriter pw : clients.values()) {
            pw.println(msg);
        }
    }

    private static void broadcastUserList() {
        String list = String.join(",", clients.keySet());
        broadcast("USER_LIST|" + list);
    }
}
