package server;

import javax.swing.*;
import java.awt.*;

public class ServerGUI extends JFrame {

    private final ClientManager clientManager;
    private ChatServer chatServer;

    private JLabel statusLabel;
    private JLabel clientCountLabel;

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

        setDefaultCloseOperation(
                JFrame.EXIT_ON_CLOSE
        );

        // =========================
        // TIÊU ĐỀ
        // =========================

        JLabel titleLabel =
                new JLabel("TCP CHAT ROOM SERVER");

        titleLabel.setFont(
                new Font(
                        "Arial",
                        Font.BOLD,
                        24
                )
        );

        // =========================
        // THÔNG TIN SERVER
        // =========================

        JPanel infoPanel =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.LEFT
                        )
                );

        statusLabel =
                new JLabel(
                        "● Server đang dừng"
                );

        clientCountLabel =
                new JLabel(
                        "Client online: 0"
                );

        infoPanel.add(statusLabel);

        infoPanel.add(
                new JLabel("   Port: 5000")
        );

        infoPanel.add(clientCountLabel);

        // =========================
        // HEADER
        // =========================

        JPanel headerPanel =
                new JPanel(
                        new BorderLayout()
                );

        headerPanel.add(
                titleLabel,
                BorderLayout.NORTH
        );

        headerPanel.add(
                infoPanel,
                BorderLayout.SOUTH
        );

        // =========================
        // DANH SÁCH CLIENT
        // =========================

        clientListModel =
                new DefaultListModel<>();

        JList<String> clientList =
                new JList<>(
                        clientListModel
                );

        JScrollPane clientScroll =
                new JScrollPane(clientList);

        clientScroll.setBorder(
                BorderFactory.createTitledBorder(
                        "Client đang online"
                )
        );

        // =========================
        // SERVER LOG
        // =========================

        logArea =
                new JTextArea();

        logArea.setEditable(false);

        logArea.setLineWrap(true);

        JScrollPane logScroll =
                new JScrollPane(logArea);

        logScroll.setBorder(
                BorderFactory.createTitledBorder(
                        "Server Log"
                )
        );

        // =========================
        // CHIA 2 KHUNG
        // =========================

        JSplitPane splitPane =
                new JSplitPane(
                        JSplitPane.HORIZONTAL_SPLIT,
                        clientScroll,
                        logScroll
                );

        splitPane.setDividerLocation(250);

        // =========================
        // BUTTON
        // =========================

        startButton =
                new JButton("Start Server");

        stopButton =
                new JButton("Stop Server");

        stopButton.setEnabled(false);

        startButton.addActionListener(
                e -> startServer()
        );

        stopButton.addActionListener(
                e -> stopServer()
        );

        JPanel buttonPanel =
                new JPanel();

        buttonPanel.add(startButton);

        buttonPanel.add(stopButton);

        // =========================
        // MAIN
        // =========================

        add(
                headerPanel,
                BorderLayout.NORTH
        );

        add(
                splitPane,
                BorderLayout.CENTER
        );

        add(
                buttonPanel,
                BorderLayout.SOUTH
        );

        setVisible(true);

        appendLog(
                "Server GUI đã khởi động."
        );
    }

    // =========================
    // START SERVER
    // =========================

    private void startServer() {

        if (chatServer == null) {

            appendLog(
                    "Không thể Start Server."
            );

            return;
        }

        boolean started =
                chatServer.startServer();

        if (started) {

            statusLabel.setText(
                    "● Server đang chạy"
            );

            startButton.setEnabled(false);

            stopButton.setEnabled(true);

            appendLog(
                    "Server đã bắt đầu chạy trên port 5000."
            );
        }
    }

    // =========================
    // STOP SERVER
    // =========================

    private void stopServer() {

        if (chatServer != null) {

            chatServer.stopServer();
        }

        statusLabel.setText(
                "● Server đang dừng"
        );

        startButton.setEnabled(true);

        stopButton.setEnabled(false);

        appendLog(
                "Server đã dừng."
        );
    }

    // =========================
    // LOG
    // =========================

    public void appendLog(String message) {

        SwingUtilities.invokeLater(() -> {

            logArea.append(
                    message + "\n"
            );

            logArea.setCaretPosition(
                    logArea.getDocument()
                            .getLength()
            );
        });
    }

    // =========================
    // REFRESH CLIENT
    // =========================

    public void refreshClientList() {

        SwingUtilities.invokeLater(() -> {

            clientListModel.clear();

            for (String username :
                    clientManager.getUsernames()) {

                clientListModel.addElement(
                        username
                );
            }

            clientCountLabel.setText(
                    "Client online: "
                            + clientManager
                            .getClientCount()
            );
        });
    }
}