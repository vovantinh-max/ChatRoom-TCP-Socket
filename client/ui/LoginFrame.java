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
 * Hỗ trợ chuyển đổi mượt mà giữa Đăng nhập và Đăng ký tài khoản mới,
 * mật khẩu an toàn, lưu cấu hình server, và hiệu ứng Glassmorphism.
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

    // Animated background particles
    private Timer bgAnimTimer;
    private final float[] particleX = new float[24];
    private final float[] particleY = new float[24];
    private final float[] particleR = new float[24];
    private final float[] particleA = new float[24];
    private final float[] particleSpeedY = new float[24];

    public LoginFrame() {
        initParticles();
        initUI();
    }

    private void initParticles() {
        for (int i = 0; i < particleX.length; i++) {
            resetParticle(i, true);
        }
    }

    private void resetParticle(int i, boolean randomY) {
        particleX[i] = (float)(Math.random() * 520);
        particleY[i] = randomY ? (float)(Math.random() * 700) : 720f;
        particleR[i] = 1.5f + (float)(Math.random() * 3.5f);
        particleA[i] = 0.04f + (float)(Math.random() * 0.10f);
        particleSpeedY[i] = 0.3f + (float)(Math.random() * 0.7f);
    }

    private void initUI() {
        setTitle("Messenger Desktop Pro — Đăng nhập & Đăng ký");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(520, 720);
        setMinimumSize(new Dimension(500, 680));
        setLocationRelativeTo(null);
        setResizable(false);
        UIUtils.applyGlobalDefaults();

        // ── Animated background panel ─────────────────────────────────────────
        JPanel bgPanel = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                GradientPaint bg = new GradientPaint(0, 0, UIUtils.COLOR_BG_DARKEST,
                        getWidth(), getHeight(), new Color(18, 14, 35));
                g2.setPaint(bg);
                g2.fillRect(0, 0, getWidth(), getHeight());

                // Orbs
                RadialGradientPaint orb1 = new RadialGradientPaint(
                    getWidth() * 0.2f, getHeight() * 0.3f, 220,
                    new float[]{0f, 1f},
                    new Color[]{ new Color(99, 102, 241, 35), new Color(99, 102, 241, 0) }
                );
                g2.setPaint(orb1);
                g2.fillOval(-80, 50, 440, 440);

                RadialGradientPaint orb2 = new RadialGradientPaint(
                    getWidth() * 0.85f, getHeight() * 0.7f, 220,
                    new float[]{0f, 1f},
                    new Color[]{ new Color(139, 92, 246, 30), new Color(139, 92, 246, 0) }
                );
                g2.setPaint(orb2);
                g2.fillOval(200, 280, 440, 440);

                // Particles
                for (int i = 0; i < particleX.length; i++) {
                    int alpha = (int)(particleA[i] * 255);
                    g2.setColor(new Color(180, 180, 255, Math.min(255, alpha)));
                    g2.fillOval((int)particleX[i], (int)particleY[i], (int)particleR[i] * 2, (int)particleR[i] * 2);
                }

                g2.dispose();
            }
        };
        bgPanel.setOpaque(true);

        // ── Card ──────────────────────────────────────────────────────────────
        UIUtils.GlassPanel card = new UIUtils.GlassPanel(
            new Color(20, 22, 32, 235),
            UIUtils.COLOR_BORDER,
            20
        );
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(26, 38, 26, 38));
        card.setPreferredSize(new Dimension(430, 630));
        card.setMaximumSize(new Dimension(430, 630));

        // ── Logo icon ────────────────────────────────────────────────────────
        JPanel logoWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        logoWrapper.setOpaque(false);
        logoWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));

        JPanel logoIcon = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int sz = 50;
                int x = (getWidth() - sz) / 2;

                RadialGradientPaint glow = new RadialGradientPaint(
                    x + sz / 2f, sz / 2f, sz,
                    new float[]{0f, 1f},
                    new Color[]{ new Color(99, 102, 241, 80), new Color(99, 102, 241, 0) }
                );
                g2.setPaint(glow);
                g2.fillOval(x - sz / 2, -sz / 2, sz * 2, sz * 2);

                GradientPaint grad = new GradientPaint(x, 0, UIUtils.COLOR_PRIMARY, x, sz, UIUtils.COLOR_ACCENT_VIOLET);
                g2.setPaint(grad);
                g2.fillOval(x, 0, sz, sz);

                g2.setColor(Color.WHITE);
                int bx = x + 11, by = 13, bw = 28, bh = 18;
                g2.fillRoundRect(bx, by, bw, bh, 6, 6);
                int[] px = {bx + 5, bx + 12, bx + 5};
                int[] py = {by + bh, by + bh + 7, by + bh + 7};
                g2.fillPolygon(px, py, 3);

                g2.setColor(new Color(99, 102, 241));
                int dotY = by + bh / 2 - 2;
                g2.fillOval(bx + 6, dotY, 4, 4);
                g2.fillOval(bx + 12, dotY, 4, 4);
                g2.fillOval(bx + 18, dotY, 4, 4);

                g2.dispose();
            }
        };
        logoIcon.setOpaque(false);
        logoIcon.setPreferredSize(new Dimension(340, 58));
        logoWrapper.add(logoIcon);
        card.add(logoWrapper);

        card.add(Box.createVerticalStrut(8));

        // ── Title ─────────────────────────────────────────────────────────────
        JLabel lblTitle = new JLabel("Messenger Desktop Pro", SwingConstants.CENTER);
        lblTitle.setFont(UIUtils.FONT_TITLE);
        lblTitle.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        lblTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblTitle);

        card.add(Box.createVerticalStrut(4));

        JLabel lblSub = new JLabel("Hệ thống Nhắn tin & Kết nối Thời gian thực", SwingConstants.CENTER);
        lblSub.setFont(UIUtils.FONT_TINY);
        lblSub.setForeground(UIUtils.COLOR_TEXT_MUTED);
        lblSub.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblSub);

        card.add(Box.createVerticalStrut(16));

        // ── Segmented Tab Switcher (Đăng nhập / Đăng ký) ──────────────────────
        JPanel tabSwitcher = new JPanel(new GridLayout(1, 2, 4, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UIUtils.COLOR_SURFACE_2);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        tabSwitcher.setOpaque(false);
        tabSwitcher.setBorder(new EmptyBorder(3, 3, 3, 3));
        tabSwitcher.setMaximumSize(new Dimension(340, 38));
        tabSwitcher.setPreferredSize(new Dimension(340, 38));

        btnTabLogin = createSegmentButton("Đăng nhập", true);
        btnTabRegister = createSegmentButton("Đăng ký mới", false);

        btnTabLogin.addActionListener(e -> switchMode(false));
        btnTabRegister.addActionListener(e -> switchMode(true));

        tabSwitcher.add(btnTabLogin);
        tabSwitcher.add(btnTabRegister);
        card.add(tabSwitcher);

        card.add(Box.createVerticalStrut(14));

        // ── Form fields ───────────────────────────────────────────────────────
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setAlignmentX(Component.CENTER_ALIGNMENT);
        form.setMaximumSize(new Dimension(Integer.MAX_VALUE, 999));

        // Username
        addFieldLabel(form, "Tên tài khoản (Username)");
        form.add(Box.createVerticalStrut(4));
        txtUsername = new UIUtils.ModernTextField("Nhập username (ví dụ: tinh, nam, an)...");
        txtUsername.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        txtUsername.setPreferredSize(new Dimension(340, 40));
        form.add(txtUsername);

        // Display Name (chỉ hiện khi Đăng ký)
        displayNamePanel = new JPanel();
        displayNamePanel.setLayout(new BoxLayout(displayNamePanel, BoxLayout.Y_AXIS));
        displayNamePanel.setOpaque(false);
        displayNamePanel.setVisible(false);
        displayNamePanel.add(Box.createVerticalStrut(10));
        addFieldLabel(displayNamePanel, "Tên hiển thị (Display Name)");
        displayNamePanel.add(Box.createVerticalStrut(4));
        txtDisplayName = new UIUtils.ModernTextField("Tên hiển thị (ví dụ: Võ Văn Tính)...");
        txtDisplayName.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        displayNamePanel.add(txtDisplayName);
        form.add(displayNamePanel);

        form.add(Box.createVerticalStrut(10));

        // Password
        addFieldLabel(form, "Mật khẩu");
        form.add(Box.createVerticalStrut(4));
        txtPassword = new UIUtils.ModernPasswordField("Nhập mật khẩu...");
        txtPassword.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        txtPassword.setPreferredSize(new Dimension(340, 40));
        form.add(txtPassword);

        // Confirm Password (chỉ hiện khi Đăng ký)
        confirmPasswordPanel = new JPanel();
        confirmPasswordPanel.setLayout(new BoxLayout(confirmPasswordPanel, BoxLayout.Y_AXIS));
        confirmPasswordPanel.setOpaque(false);
        confirmPasswordPanel.setVisible(false);
        confirmPasswordPanel.add(Box.createVerticalStrut(10));
        addFieldLabel(confirmPasswordPanel, "Xác nhận mật khẩu");
        confirmPasswordPanel.add(Box.createVerticalStrut(4));
        txtConfirmPassword = new UIUtils.ModernPasswordField("Nhập lại mật khẩu...");
        txtConfirmPassword.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        confirmPasswordPanel.add(txtConfirmPassword);
        form.add(confirmPasswordPanel);

        form.add(Box.createVerticalStrut(10));

        // Server IP & Port row
        JPanel serverRow = new JPanel(new GridLayout(1, 2, 10, 0));
        serverRow.setOpaque(false);
        serverRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        JPanel ipCol = new JPanel();
        ipCol.setLayout(new BoxLayout(ipCol, BoxLayout.Y_AXIS));
        ipCol.setOpaque(false);
        addFieldLabel(ipCol, "IP Server");
        ipCol.add(Box.createVerticalStrut(4));
        txtHost = new UIUtils.ModernTextField("127.0.0.1");
        txtHost.setText("127.0.0.1");
        txtHost.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        ipCol.add(txtHost);
        serverRow.add(ipCol);

        JPanel portCol = new JPanel();
        portCol.setLayout(new BoxLayout(portCol, BoxLayout.Y_AXIS));
        portCol.setOpaque(false);
        addFieldLabel(portCol, "Port");
        portCol.add(Box.createVerticalStrut(4));
        txtPort = new UIUtils.ModernTextField("5000");
        txtPort.setText("5000");
        txtPort.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        portCol.add(txtPort);
        serverRow.add(portCol);

        form.add(serverRow);
        card.add(form);

        card.add(Box.createVerticalStrut(8));

        // Status label
        lblStatus = new JLabel(" ", SwingConstants.CENTER);
        lblStatus.setFont(UIUtils.FONT_SMALL);
        lblStatus.setForeground(UIUtils.COLOR_DANGER);
        lblStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblStatus);

        card.add(Box.createVerticalStrut(12));

        // Submit button
        JPanel btnWrapper = new JPanel(new BorderLayout());
        btnWrapper.setOpaque(false);
        btnWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        btnSubmit = new UIUtils.ModernButton(
            "🚀  VÀO MESSENGER",
            UIUtils.COLOR_PRIMARY,
            UIUtils.COLOR_PRIMARY_HOVER,
            Color.WHITE
        );
        btnSubmit.setCornerRadius(10);
        btnSubmit.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnSubmit.setPreferredSize(new Dimension(340, 44));
        btnWrapper.add(btnSubmit, BorderLayout.CENTER);
        card.add(btnWrapper);

        bgPanel.add(card);
        setContentPane(bgPanel);

        // ── Actions ───────────────────────────────────────────────────────────
        ActionListener submitAction = e -> performSubmit();
        btnSubmit.addActionListener(submitAction);
        txtUsername.addActionListener(submitAction);
        txtPassword.addActionListener(submitAction);
        txtConfirmPassword.addActionListener(submitAction);
        txtDisplayName.addActionListener(submitAction);
        txtHost.addActionListener(submitAction);
        txtPort.addActionListener(submitAction);

        // ── Background animation ──────────────────────────────────────────────
        bgAnimTimer = new Timer(33, e -> {
            for (int i = 0; i < particleY.length; i++) {
                particleY[i] -= particleSpeedY[i];
                if (particleY[i] < -10) resetParticle(i, false);
            }
            bgPanel.repaint();
        });
        bgAnimTimer.start();
    }

    private JButton createSegmentButton(String text, boolean active) {
        JButton btn = new JButton(text);
        btn.setFont(UIUtils.FONT_BOLD);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        applySegmentStyle(btn, active);
        return btn;
    }

    private void applySegmentStyle(JButton btn, boolean active) {
        if (active) {
            btn.setBackground(UIUtils.COLOR_PRIMARY);
            btn.setForeground(Color.WHITE);
        } else {
            btn.setBackground(UIUtils.COLOR_SURFACE_2);
            btn.setForeground(UIUtils.COLOR_TEXT_MUTED);
        }
    }

    private void switchMode(boolean registerMode) {
        this.isRegisterMode = registerMode;
        applySegmentStyle(btnTabLogin, !registerMode);
        applySegmentStyle(btnTabRegister, registerMode);

        displayNamePanel.setVisible(registerMode);
        confirmPasswordPanel.setVisible(registerMode);

        if (registerMode) {
            btnSubmit.setText("✨  TẠO TÀI KHOẢN MỚI");
            setTitle("Messenger Desktop Pro — Đăng ký tài khoản");
        } else {
            btnSubmit.setText("🚀  VÀO MESSENGER");
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
        if (isRegisterMode) {
            performRegister();
        } else {
            performLogin();
        }
    }

    private void performLogin() {
        String username = txtUsername.getText().trim();
        String password = new String(txtPassword.getPassword()).trim();
        String host = txtHost.getText().trim();
        String portStr = txtPort.getText().trim();

        if (username.isEmpty()) {
            showError("⚠  Vui lòng nhập tên tài khoản!");
            txtUsername.requestFocus();
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
        String password = new String(txtPassword.getPassword()).trim();
        String confirmPass = new String(txtConfirmPassword.getPassword()).trim();
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
        lblStatus.setText(msg);
    }

    private void setLoading(boolean loading, String statusText) {
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
