package server;

import java.awt.*;
import java.net.*;
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

    private JButton startButton;
    private JButton stopButton;

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
        JList<String> clientList = new JList<>(clientListModel);
        JScrollPane clientScroll = new JScrollPane(clientList);
        clientScroll.setBorder(BorderFactory.createTitledBorder("Client đang online"));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Server Log"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, clientScroll, logScroll);
        splitPane.setDividerLocation(250);

        startButton = new JButton("Start Server");
        stopButton = new JButton("Stop Server");
        stopButton.setEnabled(false);

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);

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
        appendLog("Server đã dừng.");
    }

    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public void refreshClientList() {
        SwingUtilities.invokeLater(() -> {
            clientListModel.clear();
            for (String username : clientManager.getUsernames()) {
                clientListModel.addElement(username);
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