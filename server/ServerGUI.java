package server;

import java.awt.*;
import java.awt.event.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import javax.swing.*;

public class ServerGUI extends JFrame {

    private final ClientManager clientManager;
    private ChatServer chatServer;

    private JLabel statusLabel;
    private JLabel clientCountLabel;
    private String serverIp;

    private JTextArea logArea;
    private DefaultListModel<String> clientListModel;
    private JList<String> clientList;

    private JButton startButton;
    private JButton stopButton;
    private JButton kickButton;
    private JButton lockButton;

    public ServerGUI(ClientManager clientManager) {
        this.clientManager = clientManager;
        createGUI();
    }

    public void setChatServer(ChatServer chatServer) {
        this.chatServer = chatServer;
    }

    private void createGUI() {
        setTitle("TCP Chat Room - Server");
        setSize(850, 550);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JLabel titleLabel = new JLabel("TCP CHAT ROOM SERVER");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));

        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        serverIp = getServerIpAddress();
        statusLabel = new JLabel("● Server đang dừng");

        JLabel ipLabel = new JLabel("   IP: " + serverIp);
        ipLabel.setFont(new Font("Arial", Font.BOLD, 12));
        ipLabel.setForeground(new Color(0, 102, 204));

        clientCountLabel = new JLabel("Client online: 0");

        infoPanel.add(statusLabel);
        infoPanel.add(ipLabel);
        infoPanel.add(new JLabel(" | Port: 5000 | "));
        infoPanel.add(clientCountLabel);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(titleLabel, BorderLayout.NORTH);
        headerPanel.add(infoPanel, BorderLayout.SOUTH);

        clientListModel = new DefaultListModel<>();
        clientList = new JList<>(clientListModel);
        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane clientScroll = new JScrollPane(clientList);
        clientScroll.setBorder(BorderFactory.createTitledBorder("Client đang online"));

        kickButton = new JButton("Xóa Client (Kick)");
        kickButton.setEnabled(false);
        kickButton.addActionListener(e -> kickSelectedClient());

        clientList.addListSelectionListener(e -> {
            boolean hasSelection = clientList.getSelectedValue() != null;
            boolean isRunning = chatServer != null && chatServer.isRunning();
            kickButton.setEnabled(hasSelection && isRunning);
        });

        clientList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = clientList.locationToIndex(e.getPoint());
                if (index >= 0 && clientList.getCellBounds(index, index) != null
                        && clientList.getCellBounds(index, index).contains(e.getPoint())) {
                    String selected = clientListModel.getElementAt(index);
                    displayClientInfo(selected);
                }
            }
        });

        clientList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_DOWN) {
                    String selected = clientList.getSelectedValue();
                    if (selected != null) {
                        displayClientInfo(selected);
                    }
                }
            }
        });

        JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
        leftPanel.add(clientScroll, BorderLayout.CENTER);
        leftPanel.add(kickButton, BorderLayout.SOUTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Server Log"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, logScroll);
        splitPane.setDividerLocation(250);

        startButton = new JButton("Start Server");
        stopButton = new JButton("Stop Server");
        stopButton.setEnabled(false);

        lockButton = new JButton("Khóa Server");
        lockButton.setEnabled(false);

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        lockButton.addActionListener(e -> toggleLockServer());

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(lockButton);

        add(headerPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        setVisible(true);
        appendLog("Server GUI đã khởi động.");
    }

    private void startServer() {
        if (chatServer == null) {
            appendLog("Không thể Start Server.");
            return;
        }

        boolean started = chatServer.startServer();
        if (started) {
            statusLabel.setText("● Server đang chạy");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            lockButton.setEnabled(true);
            lockButton.setText("Khóa Server");
            lockButton.setForeground(Color.BLACK);
            appendLog("Server đã bắt đầu chạy tại IP: " + serverIp + " trên port 5000.");
            appendLog("Gợi ý: Nhập IP trên vào ô 'IP Server' của Client để kết nối qua Wi-Fi/LAN.");
        }
    }

    private void stopServer() {
        if (chatServer != null) {
            chatServer.stopServer();
        }
        statusLabel.setText("● Server đang dừng");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        lockButton.setEnabled(false);
        lockButton.setText("Khóa Server");
        lockButton.setForeground(Color.BLACK);
        kickButton.setEnabled(false);
        appendLog("Server đã dừng.");
    }

    private void toggleLockServer() {
        if (chatServer == null || !chatServer.isRunning()) {
            return;
        }
        boolean locked = chatServer.toggleLock();
        if (locked) {
            lockButton.setText("Mở khóa Server");
            lockButton.setForeground(new Color(200, 0, 0));
            statusLabel.setText("● Server đang chạy [ĐÃ KHÓA]");
            appendLog("[ADMIN] Server đã bị KHÓA. Chặn tất cả kết nối mới.");
        } else {
            lockButton.setText("Khóa Server");
            lockButton.setForeground(Color.BLACK);
            statusLabel.setText("● Server đang chạy");
            appendLog("[ADMIN] Server đã MỞ KHÓA. Cho phép kết nối mới bình thường.");
        }
    }

    private void kickSelectedClient() {
        String selected = clientList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một client từ danh sách để xóa.", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
            this,
            "Bạn có chắc muốn xóa client '" + selected + "' ra khỏi server?",
            "Xác nhận xóa Client",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );

        if (confirm == JOptionPane.YES_OPTION) {
            boolean success = clientManager.kickClient(selected);
            if (success) {
                appendLog("[ADMIN] Đã xóa client '" + selected + "' ra khỏi server.");
            } else {
                JOptionPane.showMessageDialog(this, "Không thể xóa client (có thể client đã ngắt kết nối).", "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void displayClientInfo(String username) {
        if (username == null || clientManager == null) {
            return;
        }
        ClientHandler client = clientManager.getClient(username);
        if (client == null) {
            return;
        }

        String timeNow = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String ip = client.getClientIp();
        int port = client.getClientPort();
        String room = client.getCurrentRoom();
        String connectTime = client.getConnectedTime();

        appendLog(String.format("[%s] [THÔNG TIN CLIENT] Tên: %s | IP: %s (Port: %d) | Phòng: %s | Thời gian kết nối: %s",
                timeNow, username, ip, port, room, connectTime));
    }

    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public void refreshClientList() {
        SwingUtilities.invokeLater(() -> {
            String selected = clientList.getSelectedValue();
            clientListModel.clear();
            for (String username : clientManager.getUsernames()) {
                clientListModel.addElement(username);
            }
            if (selected != null && clientListModel.contains(selected)) {
                clientList.setSelectedValue(selected, true);
            } else {
                kickButton.setEnabled(false);
            }
            clientCountLabel.setText("Client online: " + clientManager.getClientCount());
        });
    }

    private String getServerIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}