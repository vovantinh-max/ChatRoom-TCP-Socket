package client.ui;

import client.ChatClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.IOException;

/**
 * Giao diện Đăng nhập hiện đại:
 * - Thẻ màu trắng bo tròn (Card) nổi bật trên nền Slate nhẹ
 * - Logo phòng chat vẽ trực tiếp bằng Graphics2D
 * - Trường nhập liệu bo tròn hỗ trợ placeholder và hiệu ứng viền phát sáng
 * - Nút hành động hiện đại với hiệu ứng rê chuột
 */
public class LoginFrame extends JFrame {

    private UIUtils.ModernTextField txtUsername;
    private UIUtils.ModernTextField txtHost;
    private UIUtils.ModernTextField txtPort;
    private UIUtils.ModernButton btnConnect;
    private JLabel lblStatus;

    public LoginFrame() {
        initUI();
    }

    private void initUI() {
        setTitle("Đăng nhập - Chat Room TCP");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(480, 580);
        setLocationRelativeTo(null);
        setResizable(false);
        getContentPane().setBackground(UIUtils.COLOR_BACKGROUND);

        JPanel rootPanel = new JPanel(new GridBagLayout());
        rootPanel.setBackground(UIUtils.COLOR_BACKGROUND);

        // Thẻ trắng bo góc ở giữa (Card Panel)
        UIUtils.RoundedPanel card = new UIUtils.RoundedPanel(UIUtils.COLOR_SURFACE, UIUtils.COLOR_BORDER, 16);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(28, 36, 28, 36));
        card.setPreferredSize(new Dimension(400, 480));

        // 1. Logo vẽ vector và Tiêu đề ứng dụng
        JPanel logoPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int cx = getWidth() / 2;
                int size = 52;
                int x = cx - size / 2;
                int y = 0;

                // Vòng tròn nền xanh
                g2.setColor(UIUtils.COLOR_PRIMARY);
                g2.fillOval(x, y, size, size);

                // Biểu tượng bong bóng chat trắng
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(x + 13, y + 13, 26, 17, 7, 7);
                int[] px = {x + 16, x + 23, x + 16};
                int[] py = {y + 26, y + 29, y + 34};
                g2.fillPolygon(px, py, 3);

                g2.dispose();
            }
        };
        logoPanel.setOpaque(false);
        logoPanel.setPreferredSize(new Dimension(340, 54));
        logoPanel.setMaximumSize(new Dimension(340, 54));
        logoPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(logoPanel);

        card.add(Box.createVerticalStrut(10));

        JLabel lblTitle = new JLabel("Phòng Chat TCP Socket");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 22));
        lblTitle.setForeground(UIUtils.COLOR_TEXT_MAIN);
        lblTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblTitle);

        JLabel lblSub = new JLabel("Hệ thống Chat Nhóm & Chat Riêng");
        lblSub.setFont(UIUtils.FONT_SMALL);
        lblSub.setForeground(UIUtils.COLOR_TEXT_MUTED);
        lblSub.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblSub);

        card.add(Box.createVerticalStrut(24));

        // Form fields container
        JPanel formContainer = new JPanel();
        formContainer.setLayout(new BoxLayout(formContainer, BoxLayout.Y_AXIS));
        formContainer.setOpaque(false);
        formContainer.setAlignmentX(Component.CENTER_ALIGNMENT);
        formContainer.setMaximumSize(new Dimension(330, 200));

        // 2. Ô nhập Tên đăng nhập
        JLabel lblUser = new JLabel("Tên hiển thị của bạn");
        lblUser.setFont(UIUtils.FONT_BOLD);
        lblUser.setForeground(UIUtils.COLOR_TEXT_MAIN);
        formContainer.add(lblUser);
        formContainer.add(Box.createVerticalStrut(6));

        txtUsername = new UIUtils.ModernTextField("Nhập tên (ví dụ: Tinh, Nam, An)...");
        txtUsername.setMaximumSize(new Dimension(330, 40));
        txtUsername.setPreferredSize(new Dimension(330, 40));
        formContainer.add(txtUsername);

        formContainer.add(Box.createVerticalStrut(14));

        // 3. Hàng nhập IP và Port
        JPanel serverConfigPanel = new JPanel(new GridLayout(1, 2, 12, 0));
        serverConfigPanel.setOpaque(false);
        serverConfigPanel.setMaximumSize(new Dimension(330, 65));

        // Cột IP
        JPanel ipCol = new JPanel();
        ipCol.setLayout(new BoxLayout(ipCol, BoxLayout.Y_AXIS));
        ipCol.setOpaque(false);
        JLabel lblIp = new JLabel("IP Server");
        lblIp.setFont(UIUtils.FONT_BOLD);
        lblIp.setForeground(UIUtils.COLOR_TEXT_MAIN);
        ipCol.add(lblIp);
        ipCol.add(Box.createVerticalStrut(6));
        txtHost = new UIUtils.ModernTextField("127.0.0.1");
        txtHost.setText("127.0.0.1");
        txtHost.setPreferredSize(new Dimension(150, 40));
        ipCol.add(txtHost);
        serverConfigPanel.add(ipCol);

        // Cột Port
        JPanel portCol = new JPanel();
        portCol.setLayout(new BoxLayout(portCol, BoxLayout.Y_AXIS));
        portCol.setOpaque(false);
        JLabel lblPort = new JLabel("Port");
        lblPort.setFont(UIUtils.FONT_BOLD);
        lblPort.setForeground(UIUtils.COLOR_TEXT_MAIN);
        portCol.add(lblPort);
        portCol.add(Box.createVerticalStrut(6));
        txtPort = new UIUtils.ModernTextField("5000");
        txtPort.setText("5000");
        txtPort.setPreferredSize(new Dimension(150, 40));
        portCol.add(txtPort);
        serverConfigPanel.add(portCol);

        formContainer.add(serverConfigPanel);
        card.add(formContainer);

        card.add(Box.createVerticalStrut(8));

        // 4. Nhãn trạng thái / báo lỗi
        lblStatus = new JLabel(" ");
        lblStatus.setFont(UIUtils.FONT_TINY);
        lblStatus.setForeground(UIUtils.COLOR_DANGER);
        lblStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblStatus);

        card.add(Box.createVerticalStrut(12));

        // 5. Nút đăng nhập chính
        btnConnect = new UIUtils.ModernButton(
            "VÀO PHÒNG CHAT >",
            UIUtils.COLOR_PRIMARY,
            UIUtils.COLOR_PRIMARY_HOVER,
            Color.WHITE
        );
        btnConnect.setMaximumSize(new Dimension(330, 44));
        btnConnect.setPreferredSize(new Dimension(330, 44));
        btnConnect.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(btnConnect);

        rootPanel.add(card);
        setContentPane(rootPanel);

        // Lắng nghe sự kiện
        ActionListener connectAction = e -> performConnect();
        btnConnect.addActionListener(connectAction);
        txtUsername.addActionListener(connectAction);
        txtHost.addActionListener(connectAction);
        txtPort.addActionListener(connectAction);
    }

    private void performConnect() {
        String username = txtUsername.getText().trim();
        String host = txtHost.getText().trim();
        String portStr = txtPort.getText().trim();

        if (username.isEmpty()) {
            showError("Vui lòng nhập tên hiển thị của bạn!");
            txtUsername.requestFocus();
            return;
        }

        if (username.contains("|") || username.contains(",")) {
            showError("Tên không được chứa ký tự '|' hoặc ','");
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
            showError("Cổng (Port) phải là số từ 1 đến 65535!");
            txtPort.requestFocus();
            return;
        }

        setLoading(true, "Đang kết nối tới " + host + ":" + port + "...");

        new Thread(() -> {
            try {
                ChatClient client = new ChatClient();
                client.connect(host, port, username);

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
                        "Không thể kết nối đến máy chủ tại " + host + ":" + port + "\n\n" +
                        "Chi tiết: " + ex.getMessage() + "\n\n" +
                        "Vui lòng kiểm tra Server đã được khởi động chưa!",
                        "Lỗi kết nối Server",
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
        lblStatus.setForeground(loading ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_DANGER);
        btnConnect.setEnabled(!loading);
        btnConnect.setText(loading ? "ĐANG KẾT NỐI..." : "VÀO PHÒNG CHAT >");
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
