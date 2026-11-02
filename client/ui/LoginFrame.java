package client.ui;

import client.ChatClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.IOException;

/**
 * LoginFrame - Màn hình Đăng nhập & Đăng ký Messenger Desktop Pro.
 * Giao diện hai cột, form cuộn theo cửa sổ và trạng thái xác thực rõ ràng.
 */
public class LoginFrame extends JFrame {

    private boolean isRegisterMode = false;

    private UIUtils.ModernTextField     txtUsername;
    private UIUtils.ModernTextField     txtDisplayName;
    private UIUtils.ModernPasswordField txtPassword;
    private UIUtils.ModernPasswordField txtConfirmPassword;
    private UIUtils.ModernTextField     txtHost;
    private UIUtils.ModernTextField     txtPort;

    private JPanel                       displayNamePanel;
    private JPanel                       confirmPasswordPanel;

    private JButton                      btnTabLogin;
    private JButton                      btnTabRegister;
    private UIUtils.ModernButton        btnSubmit;
    private JLabel                      lblStatus;

    private JLabel formTitle;
    private JLabel formSubtitle;
    private boolean loading;

    public LoginFrame() { initUI(); }

    private void initUI() {
        UIUtils.applyGlobalDefaults();
        setTitle("Messenger · Đăng nhập");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1040, 800);
        setMinimumSize(new Dimension(900, 700));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new GridLayout(1, 2));
        root.setBackground(UIUtils.COLOR_BG_DARKEST);
        root.add(buildWelcomePanel());

        JPanel form = new UIUtils.WidthTrackingPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(UIUtils.COLOR_BG_DARK);
        form.setBorder(new EmptyBorder(40, 44, 32, 44));
        JLabel eyebrow = label("KHÔNG GIAN CỦA BẠN", UIUtils.FONT_SMALL, UIUtils.COLOR_PRIMARY);
        form.add(eyebrow);
        form.add(Box.createVerticalStrut(12));
        formTitle = label("Chào mừng trở lại.", UIUtils.FONT_DISPLAY, UIUtils.COLOR_TEXT_PRIMARY);
        form.add(formTitle);
        form.add(Box.createVerticalStrut(8));
        formSubtitle = label("Đăng nhập để tiếp tục cuộc trò chuyện.", UIUtils.FONT_SMALL, UIUtils.COLOR_TEXT_SECONDARY);
        form.add(formSubtitle);
        form.add(Box.createVerticalStrut(28));

        JPanel tabs = new JPanel(new GridLayout(1, 2, 4, 0));
        tabs.setBackground(UIUtils.COLOR_SURFACE_1);
        tabs.setBorder(new EmptyBorder(4, 4, 4, 4));
        tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
        tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        tabs.setPreferredSize(new Dimension(340, 44));
        btnTabLogin = createSegmentButton("Đăng nhập", true);
        btnTabRegister = createSegmentButton("Tạo tài khoản", false);
        btnTabLogin.addActionListener(e -> switchMode(false));
        btnTabRegister.addActionListener(e -> switchMode(true));
        tabs.add(btnTabLogin);
        tabs.add(btnTabRegister);
        form.add(tabs);
        form.add(Box.createVerticalStrut(22));

        txtUsername = new UIUtils.ModernTextField("Tên tài khoản của bạn");
        addInput(form, "Tên tài khoản", txtUsername);
        displayNamePanel = new JPanel();
        displayNamePanel.setLayout(new BoxLayout(displayNamePanel, BoxLayout.Y_AXIS));
        displayNamePanel.setOpaque(false);
        displayNamePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        txtDisplayName = new UIUtils.ModernTextField("Mọi người sẽ gọi bạn là gì?");
        addInput(displayNamePanel, "Tên hiển thị", txtDisplayName);
        displayNamePanel.setVisible(false);
        form.add(displayNamePanel);

        txtPassword = new UIUtils.ModernPasswordField("Nhập mật khẩu");
        addInput(form, "Mật khẩu", txtPassword);
        confirmPasswordPanel = new JPanel();
        confirmPasswordPanel.setLayout(new BoxLayout(confirmPasswordPanel, BoxLayout.Y_AXIS));
        confirmPasswordPanel.setOpaque(false);
        confirmPasswordPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        txtConfirmPassword = new UIUtils.ModernPasswordField("Nhập lại mật khẩu");
        addInput(confirmPasswordPanel, "Xác nhận mật khẩu", txtConfirmPassword);
        confirmPasswordPanel.setVisible(false);
        form.add(confirmPasswordPanel);

        JCheckBox reveal = new JCheckBox("Hiện mật khẩu");
        reveal.setOpaque(false);
        reveal.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        reveal.setFont(UIUtils.FONT_SMALL);
        reveal.setAlignmentX(Component.LEFT_ALIGNMENT);
        reveal.addActionListener(e -> {
            txtPassword.setEchoChar(reveal.isSelected() ? (char) 0 : '●');
            txtConfirmPassword.setEchoChar(reveal.isSelected() ? (char) 0 : '●');
        });
        form.add(reveal);
        form.add(Box.createVerticalStrut(18));

        JPanel serverRow = new JPanel(new GridLayout(1, 2, 12, 0));
        serverRow.setOpaque(false);
        serverRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        serverRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        JPanel hostColumn = new JPanel();
        hostColumn.setLayout(new BoxLayout(hostColumn, BoxLayout.Y_AXIS));
        hostColumn.setOpaque(false);
        txtHost = new UIUtils.ModernTextField("Địa chỉ máy chủ");
        txtHost.setText("127.0.0.1");
        addInput(hostColumn, "Máy chủ", txtHost);
        JPanel portColumn = new JPanel();
        portColumn.setLayout(new BoxLayout(portColumn, BoxLayout.Y_AXIS));
        portColumn.setOpaque(false);
        txtPort = new UIUtils.ModernTextField("5000");
        txtPort.setText("5000");
        addInput(portColumn, "Cổng kết nối", txtPort);
        serverRow.add(hostColumn);
        serverRow.add(portColumn);
        form.add(serverRow);

        lblStatus = label(" ", UIUtils.FONT_SMALL, UIUtils.COLOR_DANGER);
        lblStatus.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        lblStatus.setPreferredSize(new Dimension(340, 42));
        form.add(lblStatus);
        form.add(Box.createVerticalStrut(8));
        btnSubmit = new UIUtils.ModernButton("Đăng nhập", UIUtils.COLOR_PRIMARY,
                UIUtils.COLOR_PRIMARY_HOVER, UIUtils.COLOR_BG_DARKEST);
        btnSubmit.setIcon(UIUtils.icon("arrow", 19));
        btnSubmit.setHorizontalTextPosition(SwingConstants.LEFT);
        btnSubmit.setIconTextGap(12);
        btnSubmit.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnSubmit.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        btnSubmit.setPreferredSize(new Dimension(340, 48));
        form.add(btnSubmit);
        form.add(Box.createVerticalStrut(20));
        form.add(label("Một nơi cho những kết nối mỗi ngày.", UIUtils.FONT_SMALL, UIUtils.COLOR_TEXT_MUTED));
        form.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        root.add(scroll);
        setContentPane(root);
        getRootPane().setDefaultButton(btnSubmit);
        btnSubmit.addActionListener(e -> performSubmit());
        SwingUtilities.invokeLater(() -> txtUsername.requestFocusInWindow());
    }

    private JLabel label(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void addInput(JPanel panel, String title, JTextField field) {
        JLabel label = label(title, UIUtils.FONT_SMALL, UIUtils.COLOR_TEXT_SECONDARY);
        label.setLabelFor(field);
        field.getAccessibleContext().setAccessibleName(title);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setPreferredSize(new Dimension(340, 44));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        panel.add(label);
        panel.add(Box.createVerticalStrut(7));
        panel.add(field);
        panel.add(Box.createVerticalStrut(14));
    }

    private JPanel buildWelcomePanel() {
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D p = (Graphics2D) g.create();
                p.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                p.setPaint(new GradientPaint(0, 0, new Color(18, 43, 49), getWidth(), getHeight(), UIUtils.COLOR_BG_DARKEST));
                p.fillRect(0, 0, getWidth(), getHeight());
                p.setColor(new Color(45, 212, 191, 10));
                for (int i = 0; i < 5; i++) p.drawOval(getWidth() - 260 - i * 60, 100 - i * 60, 360 + i * 120, 360 + i * 120);
                p.dispose();
            }
        };
        panel.setBorder(new EmptyBorder(42, 42, 36, 36));
        JLabel brand = label("  messenger", new Font("Segoe UI", Font.BOLD, 24), UIUtils.COLOR_TEXT_PRIMARY);
        brand.setIcon(UIUtils.icon("chat", 30));
        panel.add(brand, BorderLayout.NORTH);
        JPanel story = new JPanel();
        story.setOpaque(false);
        story.setLayout(new BoxLayout(story, BoxLayout.Y_AXIS));
        story.add(Box.createVerticalGlue());
        story.add(label("ÍT KHOẢNG CÁCH. NHIỀU KẾT NỐI.", UIUtils.FONT_SMALL, UIUtils.COLOR_PRIMARY));
        story.add(Box.createVerticalStrut(16));
        story.add(label("Mọi cuộc trò chuyện,", new Font("Segoe UI", Font.BOLD, 30), UIUtils.COLOR_TEXT_PRIMARY));
        story.add(label("gần nhau hơn.", new Font("Segoe UI", Font.BOLD, 30), UIUtils.COLOR_TEXT_PRIMARY));
        story.add(Box.createVerticalStrut(18));
        story.add(label("Chia sẻ ý tưởng. Gặp gỡ bạn bè.", UIUtils.FONT_REGULAR, UIUtils.COLOR_TEXT_SECONDARY));
        story.add(label("Giữ liên lạc theo cách của bạn.", UIUtils.FONT_REGULAR, UIUtils.COLOR_TEXT_SECONDARY));
        story.add(Box.createVerticalStrut(36));
        UIUtils.RoundedPanel preview = new UIUtils.RoundedPanel(UIUtils.COLOR_SURFACE_1, UIUtils.COLOR_BORDER_LIGHT, 22);
        preview.setLayout(new BorderLayout(12, 16));
        preview.setBorder(new EmptyBorder(22, 22, 22, 22));
        preview.setAlignmentX(Component.LEFT_ALIGNMENT);
        preview.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
        JPanel person = new JPanel(new BorderLayout(12, 0));
        person.setOpaque(false);
        person.add(UIUtils.createAvatarPanel("An", 38), BorderLayout.WEST);
        person.add(label("Những người bạn của bạn", UIUtils.FONT_BOLD, UIUtils.COLOR_TEXT_PRIMARY), BorderLayout.CENTER);
        preview.add(person, BorderLayout.NORTH);
        preview.add(label("Một lời chào, một kết nối mới.", UIUtils.FONT_REGULAR, UIUtils.COLOR_TEXT_SECONDARY), BorderLayout.CENTER);
        JLabel bubble = label("  Hôm nay của bạn thế nào?  ", UIUtils.FONT_REGULAR, UIUtils.COLOR_PRIMARY);
        bubble.setBorder(new EmptyBorder(12, 10, 12, 10));
        bubble.setOpaque(true);
        bubble.setBackground(UIUtils.COLOR_PRIMARY_LIGHT);
        preview.add(bubble, BorderLayout.SOUTH);
        story.add(preview);
        story.add(Box.createVerticalStrut(28));
        story.add(label("Nhắn tin  /  Gọi thoại & video  /  Chia sẻ file", UIUtils.FONT_SMALL, UIUtils.COLOR_TEXT_SECONDARY));
        story.add(Box.createVerticalGlue());
        panel.add(story, BorderLayout.CENTER);
        panel.add(label("MESSENGER DESKTOP", UIUtils.FONT_SMALL, UIUtils.COLOR_TEXT_MUTED), BorderLayout.SOUTH);
        return panel;
    }

    private JButton createSegmentButton(String text, boolean active) {
        JButton btn = new UIUtils.ModernButton(text, UIUtils.COLOR_SURFACE_2, UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_TEXT_SECONDARY);
        btn.setFont(UIUtils.FONT_BOLD);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        applySegmentStyle(btn, active);
        return btn;
    }

    private void applySegmentStyle(JButton btn, boolean active) {
        if (btn instanceof UIUtils.ModernButton modern) {
            modern.setColors(active ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_SURFACE_2,
                    active ? UIUtils.COLOR_PRIMARY_HOVER : UIUtils.COLOR_SURFACE_3);
        }
        if (active) {
            btn.setBackground(UIUtils.COLOR_PRIMARY);
            btn.setForeground(UIUtils.COLOR_BG_DARKEST);
        } else {
            btn.setBackground(UIUtils.COLOR_SURFACE_2);
            btn.setForeground(UIUtils.COLOR_TEXT_MUTED);
        }
    }

    private void switchMode(boolean registerMode) {
        if (loading) return;
        this.isRegisterMode = registerMode;
        formTitle.setText(registerMode ? "Bắt đầu kết nối." : "Chào mừng trở lại.");
        formSubtitle.setText(registerMode ? "Tạo tài khoản và gặp gỡ mọi người." : "Đăng nhập để tiếp tục cuộc trò chuyện.");
        applySegmentStyle(btnTabLogin, !registerMode);
        applySegmentStyle(btnTabRegister, registerMode);

        displayNamePanel.setVisible(registerMode);
        confirmPasswordPanel.setVisible(registerMode);

        if (registerMode) {
            btnSubmit.setText("Tạo tài khoản");
            setTitle("Messenger Desktop Pro — Đăng ký tài khoản");
        } else {
            btnSubmit.setText("Đăng nhập");
            setTitle("Messenger Desktop Pro — Đăng nhập");
        }

        lblStatus.setText(" ");
        revalidate();
        repaint();
    }

    private void addFieldLabel(JPanel panel, String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(UIUtils.FONT_TINY);
        lbl.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(lbl);
    }

    private void performSubmit() {
        if (loading) return;
        if (isRegisterMode) {
            performRegister();
        } else {
            performLogin();
        }
    }

    private void performLogin() {
        String username = txtUsername.getText().trim();
        String password = new String(txtPassword.getPassword());
        String host = txtHost.getText().trim();
        String portStr = txtPort.getText().trim();

        if (username.isEmpty()) {
            showError("⚠  Vui lòng nhập tên tài khoản!");
            txtUsername.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            showError("Vui lòng nhập mật khẩu.");
            txtPassword.requestFocusInWindow();
            return;
        }
        if (host.isEmpty()) {
            showError("⚠  Vui lòng nhập địa chỉ IP máy chủ!");
            txtHost.requestFocus();
            return;
        }

        int port = parsePort(portStr);
        if (port == -1) return;

        setLoading(true, "⏳  Đang xác thực với máy chủ...");

        new Thread(() -> {
            try {
                ChatClient client = new ChatClient();
                client.connect(host, port, username, password);
                SwingUtilities.invokeLater(() -> {
                    ChatFrame chatFrame = new ChatFrame(client, this);
                    chatFrame.setVisible(true);
                    this.setVisible(false);
                    setLoading(false, "");
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    setLoading(false, "");
                    showError("✗  " + ex.getMessage());
                });
            }
        }).start();
    }

    private void performRegister() {
        String username = txtUsername.getText().trim();
        String displayName = txtDisplayName.getText().trim();
        String password = new String(txtPassword.getPassword());
        String confirmPass = new String(txtConfirmPassword.getPassword());
        String host = txtHost.getText().trim();
        String portStr = txtPort.getText().trim();

        if (username.length() < 3) {
            showError("⚠  Tên tài khoản tối thiểu 3 ký tự!");
            txtUsername.requestFocus();
            return;
        }
        if (password.length() < 4) {
            showError("⚠  Mật khẩu tối thiểu 4 ký tự!");
            txtPassword.requestFocus();
            return;
        }
        if (!password.equals(confirmPass)) {
            showError("⚠  Mật khẩu xác nhận không khớp!");
            txtConfirmPassword.requestFocus();
            return;
        }
        if (host.isEmpty()) {
            showError("⚠  Vui lòng nhập IP máy chủ!");
            txtHost.requestFocus();
            return;
        }

        int port = parsePort(portStr);
        if (port == -1) return;

        setLoading(true, "⏳  Đang tạo tài khoản...");

        new Thread(() -> {
            try {
                ChatClient client = new ChatClient();
                String res = client.register(host, port, username, password, displayName);
                SwingUtilities.invokeLater(() -> {
                    setLoading(false, "");
                    if ("SUCCESS".equalsIgnoreCase(res)) {
                        JOptionPane.showMessageDialog(this,
                            "Tạo tài khoản '" + username + "' thành công!\nBây giờ bạn có thể đăng nhập.",
                            "Đăng ký thành công", JOptionPane.INFORMATION_MESSAGE);
                        switchMode(false);
                        txtPassword.setText("");
                        txtConfirmPassword.setText("");
                    } else {
                        showError("✗  " + res);
                    }
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    setLoading(false, "");
                    showError("✗  Không thể kết nối Server: " + ex.getMessage());
                });
            }
        }).start();
    }

    private int parsePort(String portStr) {
        try {
            int p = Integer.parseInt(portStr);
            if (p < 1 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException ex) {
            showError("⚠  Cổng phải là số từ 1 đến 65535!");
            txtPort.requestFocus();
            return -1;
        }
    }

    private void showError(String msg) {
        lblStatus.setForeground(UIUtils.COLOR_DANGER);
        lblStatus.setText("<html>" + msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
        lblStatus.setToolTipText(msg);
    }

    private void setLoading(boolean loading, String statusText) {
        this.loading = loading;
        btnTabLogin.setEnabled(!loading);
        btnTabRegister.setEnabled(!loading);
        btnSubmit.setText(loading ? "Đang kết nối…" : isRegisterMode ? "Tạo tài khoản" : "Đăng nhập");
        lblStatus.setText(statusText);
        lblStatus.setForeground(loading ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_DANGER);
        btnSubmit.setEnabled(!loading);
        txtUsername.setEnabled(!loading);
        txtPassword.setEnabled(!loading);
        txtDisplayName.setEnabled(!loading);
        txtConfirmPassword.setEnabled(!loading);
        txtHost.setEnabled(!loading);
        txtPort.setEnabled(!loading);
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        UIUtils.applyGlobalDefaults();
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}
