package client.ui;

import client.ChatClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;

/**
 * Giao diện đăng nhập Client: Nhập username, IP Server, Port và thực hiện kết nối.
 */
public class LoginFrame extends JFrame {

    private JTextField txtUsername;
    private JTextField txtHost;
    private JTextField txtPort;
    private JButton btnConnect;
    private JButton btnExit;
    private JLabel lblStatus;

    public LoginFrame() {
        initUI();
    }

    private void initUI() {
        setTitle("Đăng nhập Chat Room - TCP Socket");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(420, 340);
        setLocationRelativeTo(null);
        setResizable(false);

        // Panel chính
        JPanel mainPanel = new JPanel(new BorderLayout(10, 15));
        mainPanel.setBorder(new EmptyBorder(20, 25, 20, 25));
        mainPanel.setBackground(new Color(245, 247, 250));

        // Tiêu đề
        JLabel lblHeader = new JLabel("CHAT ROOM CLIENT", SwingConstants.CENTER);
        lblHeader.setFont(new Font("Segoe UI", Font.BOLD, 20));
        lblHeader.setForeground(new Color(33, 50, 91));
        mainPanel.add(lblHeader, BorderLayout.NORTH);

        // Form nhập liệu
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 5, 8, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Tên người dùng
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.3;
        JLabel lblUser = new JLabel("Tên đăng nhập:");
        lblUser.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        formPanel.add(lblUser, gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.7;
        txtUsername = new JTextField(15);
        txtUsername.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        txtUsername.setPreferredSize(new Dimension(180, 30));
        formPanel.add(txtUsername, gbc);

        // IP Server
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.3;
        JLabel lblIp = new JLabel("IP Server:");
        lblIp.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        formPanel.add(lblIp, gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.7;
        txtHost = new JTextField("127.0.0.1", 15);
        txtHost.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        txtHost.setPreferredSize(new Dimension(180, 30));
        formPanel.add(txtHost, gbc);

        // Cổng kết nối
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0.3;
        JLabel lblPort = new JLabel("Cổng (Port):");
        lblPort.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        formPanel.add(lblPort, gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.7;
        txtPort = new JTextField("5000", 15);
        txtPort.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        txtPort.setPreferredSize(new Dimension(180, 30));
        formPanel.add(txtPort, gbc);

        mainPanel.add(formPanel, BorderLayout.CENTER);

        // Panel dưới: nút bấm và thanh trạng thái
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 10));
        bottomPanel.setOpaque(false);

        lblStatus = new JLabel(" ", SwingConstants.CENTER);
        lblStatus.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        lblStatus.setForeground(Color.RED);
        bottomPanel.add(lblStatus, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 0));
        buttonPanel.setOpaque(false);

        btnConnect = new JButton("Kết nối");
        btnConnect.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnConnect.setBackground(new Color(40, 120, 220));
        btnConnect.setForeground(Color.WHITE);
        btnConnect.setFocusPainted(false);
        btnConnect.setPreferredSize(new Dimension(110, 36));

        btnExit = new JButton("Thoát");
        btnExit.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        btnExit.setPreferredSize(new Dimension(90, 36));

        buttonPanel.add(btnConnect);
        buttonPanel.add(btnExit);
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH);

        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
        setContentPane(mainPanel);

        // Lắng nghe sự kiện
        ActionListener connectAction = e -> performConnect();
        btnConnect.addActionListener(connectAction);
        txtUsername.addActionListener(connectAction);
        txtHost.addActionListener(connectAction);
        txtPort.addActionListener(connectAction);

        btnExit.addActionListener(e -> System.exit(0));
    }

    private void performConnect() {
        String username = txtUsername.getText().trim();
        String host = txtHost.getText().trim();
        String portStr = txtPort.getText().trim();

        // Kiểm tra dữ liệu rỗng và hợp lệ
        if (username.isEmpty()) {
            showError("Vui lòng nhập tên đăng nhập!");
            txtUsername.requestFocus();
            return;
        }

        if (username.contains("|") || username.contains(",")) {
            showError("Tên đăng nhập không được chứa ký tự '|' hoặc ','");
            txtUsername.requestFocus();
            return;
        }

        if (host.isEmpty()) {
            showError("Vui lòng nhập IP máy chủ!");
            txtHost.requestFocus();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            showError("Cổng (Port) phải là số nguyên từ 1 đến 65535!");
            txtPort.requestFocus();
            return;
        }

        setLoading(true, "Đang kết nối tới " + host + ":" + port + "...");

        // Kết nối bất đồng bộ để không làm đơ giao diện
        new Thread(() -> {
            try {
                ChatClient client = new ChatClient();
                client.connect(host, port, username);

                // Khi kết nối thành công, mở màn hình Chat
                SwingUtilities.invokeLater(() -> {
                    ChatFrame chatFrame = new ChatFrame(client, this);
                    chatFrame.setVisible(true);
                    this.setVisible(false);
                    setLoading(false, "");
                });

            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    setLoading(false, "");
                    JOptionPane.showMessageDialog(
                        this,
                        "Không thể kết nối đến máy chủ tại " + host + ":" + port + "\nChi tiết: " + ex.getMessage(),
                        "Lỗi kết nối",
                        JOptionPane.ERROR_MESSAGE
                    );
                });
            }
        }).start();
    }

    private void showError(String msg) {
        lblStatus.setText(msg);
    }

    private void setLoading(boolean loading, String statusText) {
        lblStatus.setText(statusText);
        btnConnect.setEnabled(!loading);
        txtUsername.setEnabled(!loading);
        txtHost.setEnabled(!loading);
        txtPort.setEnabled(!loading);
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(() -> {
            new LoginFrame().setVisible(true);
        });
    }
}
