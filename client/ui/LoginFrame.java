package client.ui;

import client.ChatClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.IOException;

/**
 * LoginFrame - Premium dark-themed login screen.
 * Modern glassmorphism card with animated background.
 */
public class LoginFrame extends JFrame {

    private UIUtils.ModernTextField  txtUsername;
    private UIUtils.ModernTextField  txtHost;
    private UIUtils.ModernTextField  txtPort;
    private UIUtils.ModernButton     btnConnect;
    private JLabel                   lblStatus;

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
        particleX[i] = (float)(Math.random() * 480);
        particleY[i] = randomY ? (float)(Math.random() * 600) : 620f;
        particleR[i] = 1.5f + (float)(Math.random() * 3.5f);
        particleA[i] = 0.04f + (float)(Math.random() * 0.10f);
        particleSpeedY[i] = 0.3f + (float)(Math.random() * 0.7f);
    }

    private void initUI() {
        setTitle("ChatRoom TCP — Đăng nhập");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(500, 620);
        setMinimumSize(new Dimension(480, 580));
        setLocationRelativeTo(null);
        setResizable(false);
        UIUtils.applyGlobalDefaults();

        // ── Animated background panel ─────────────────────────────────────────
        JPanel bgPanel = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Deep gradient background
                GradientPaint bg = new GradientPaint(0, 0, UIUtils.COLOR_BG_DARKEST,
                        getWidth(), getHeight(), new Color(18, 14, 35));
                g2.setPaint(bg);
                g2.fillRect(0, 0, getWidth(), getHeight());

                // Subtle colored orbs
                RadialGradientPaint orb1 = new RadialGradientPaint(
                    getWidth() * 0.2f, getHeight() * 0.3f, 180,
                    new float[]{0f, 1f},
                    new Color[]{ new Color(99, 102, 241, 30), new Color(99, 102, 241, 0) }
                );
                g2.setPaint(orb1);
                g2.fillOval(-80, 50, 360, 360);

                RadialGradientPaint orb2 = new RadialGradientPaint(
                    getWidth() * 0.85f, getHeight() * 0.7f, 200,
                    new float[]{0f, 1f},
                    new Color[]{ new Color(139, 92, 246, 25), new Color(139, 92, 246, 0) }
                );
                g2.setPaint(orb2);
                g2.fillOval(200, 280, 400, 400);

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
            new Color(20, 22, 32, 230),
            UIUtils.COLOR_BORDER,
            20
        );
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(36, 40, 36, 40));
        card.setPreferredSize(new Dimension(420, 520));
        card.setMaximumSize(new Dimension(420, 520));

        // ── Logo icon ────────────────────────────────────────────────────────
        JPanel logoWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        logoWrapper.setOpaque(false);
        logoWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));

        JPanel logoIcon = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int sz = 60;
                int x = (getWidth() - sz) / 2;

                // Glow behind logo
                RadialGradientPaint glow = new RadialGradientPaint(
                    x + sz / 2f, sz / 2f, sz,
                    new float[]{0f, 1f},
                    new Color[]{ new Color(99, 102, 241, 80), new Color(99, 102, 241, 0) }
                );
                g2.setPaint(glow);
                g2.fillOval(x - sz / 2, -sz / 2, sz * 2, sz * 2);

                // Circle background
                GradientPaint grad = new GradientPaint(x, 0, UIUtils.COLOR_PRIMARY, x, sz, UIUtils.COLOR_ACCENT_VIOLET);
                g2.setPaint(grad);
                g2.fillOval(x, 0, sz, sz);

                // Chat bubble icon (white)
                g2.setColor(Color.WHITE);
                int bx = x + 12, by = 14, bw = 36, bh = 22;
                g2.fillRoundRect(bx, by, bw, bh, 8, 8);
                // Tail
                int[] px = {bx + 7, bx + 14, bx + 7};
                int[] py = {by + bh, by + bh + 10, by + bh + 10};
                g2.fillPolygon(px, py, 3);

                // Dots inside bubble
                g2.setColor(new Color(99, 102, 241));
                int dotY = by + bh / 2 - 3;
                g2.fillOval(bx + 8, dotY, 6, 6);
                g2.fillOval(bx + 17, dotY, 6, 6);
                g2.fillOval(bx + 26, dotY, 6, 6);

                g2.dispose();
            }
        };
        logoIcon.setOpaque(false);
        logoIcon.setPreferredSize(new Dimension(340, 72));
        logoWrapper.add(logoIcon);
        card.add(logoWrapper);

        card.add(Box.createVerticalStrut(14));

        // ── Title ─────────────────────────────────────────────────────────────
        JLabel lblTitle = new JLabel("Phòng Chat TCP Socket", SwingConstants.CENTER);
        lblTitle.setFont(UIUtils.FONT_DISPLAY);
        lblTitle.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        lblTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblTitle);

        card.add(Box.createVerticalStrut(6));

        JLabel lblSub = new JLabel("Hệ thống Chat Nhóm & Riêng tư theo thời gian thực", SwingConstants.CENTER);
        lblSub.setFont(UIUtils.FONT_SMALL);
        lblSub.setForeground(UIUtils.COLOR_TEXT_MUTED);
        lblSub.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblSub);

        card.add(Box.createVerticalStrut(28));

        // ── Form ──────────────────────────────────────────────────────────────
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setAlignmentX(Component.CENTER_ALIGNMENT);
        form.setMaximumSize(new Dimension(Integer.MAX_VALUE, 999));

        // Username
        addFieldLabel(form, "Tên hiển thị");
        form.add(Box.createVerticalStrut(6));
        txtUsername = new UIUtils.ModernTextField("Nhập tên của bạn...");
        txtUsername.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        txtUsername.setPreferredSize(new Dimension(340, 44));
        form.add(txtUsername);

        form.add(Box.createVerticalStrut(16));

        // Server row (IP + Port)
        JPanel serverRow = new JPanel(new GridLayout(1, 2, 12, 0));
        serverRow.setOpaque(false);
        serverRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

        JPanel ipCol = new JPanel();
        ipCol.setLayout(new BoxLayout(ipCol, BoxLayout.Y_AXIS));
        ipCol.setOpaque(false);
        addFieldLabel(ipCol, "IP Máy chủ");
        ipCol.add(Box.createVerticalStrut(6));
        txtHost = new UIUtils.ModernTextField("127.0.0.1");
        txtHost.setText("127.0.0.1");
        txtHost.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        ipCol.add(txtHost);
        serverRow.add(ipCol);

        JPanel portCol = new JPanel();
        portCol.setLayout(new BoxLayout(portCol, BoxLayout.Y_AXIS));
        portCol.setOpaque(false);
        addFieldLabel(portCol, "Cổng (Port)");
        portCol.add(Box.createVerticalStrut(6));
        txtPort = new UIUtils.ModernTextField("5000");
        txtPort.setText("5000");
        txtPort.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        portCol.add(txtPort);
        serverRow.add(portCol);

        form.add(serverRow);
        card.add(form);

        card.add(Box.createVerticalStrut(10));

        // ── Status label ──────────────────────────────────────────────────────
        lblStatus = new JLabel(" ", SwingConstants.CENTER);
        lblStatus.setFont(UIUtils.FONT_SMALL);
        lblStatus.setForeground(UIUtils.COLOR_DANGER);
        lblStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblStatus);

        card.add(Box.createVerticalStrut(16));

        // ── Connect button ────────────────────────────────────────────────────
        JPanel btnWrapper = new JPanel(new BorderLayout());
        btnWrapper.setOpaque(false);
        btnWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        btnConnect = new UIUtils.ModernButton(
            "🚀  VÀO PHÒNG CHAT",
            UIUtils.COLOR_PRIMARY,
            UIUtils.COLOR_PRIMARY_HOVER,
            Color.WHITE
        );
        btnConnect.setCornerRadius(12);
        btnConnect.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnConnect.setPreferredSize(new Dimension(340, 48));
        btnWrapper.add(btnConnect, BorderLayout.CENTER);
        card.add(btnWrapper);

        card.add(Box.createVerticalStrut(16));

        // ── Footer tip ─────────────────────────────────────────────────────────
        JLabel tip = new JLabel("Nhấn Enter để kết nối nhanh", SwingConstants.CENTER);
        tip.setFont(UIUtils.FONT_TINY);
        tip.setForeground(UIUtils.COLOR_TEXT_MUTED);
        tip.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(tip);

        bgPanel.add(card);
        setContentPane(bgPanel);

        // ── Actions ───────────────────────────────────────────────────────────
        ActionListener connectAction = e -> performConnect();
        btnConnect.addActionListener(connectAction);
        txtUsername.addActionListener(connectAction);
        txtHost.addActionListener(connectAction);
        txtPort.addActionListener(connectAction);

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

    private void addFieldLabel(JPanel panel, String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(UIUtils.FONT_BOLD);
        lbl.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(lbl);
    }

    private void performConnect() {
        String username = txtUsername.getText().trim();
        String host     = txtHost.getText().trim();
        String portStr  = txtPort.getText().trim();

        if (username.isEmpty()) {
            showError("⚠  Vui lòng nhập tên hiển thị của bạn!");
            txtUsername.requestFocus();
            return;
        }
        if (username.contains("|") || username.contains(",")) {
            showError("⚠  Tên không được chứa ký tự '|' hoặc ','");
            txtUsername.requestFocus();
            return;
        }
        if (host.isEmpty()) {
            showError("⚠  Vui lòng nhập địa chỉ IP máy chủ!");
            txtHost.requestFocus();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            showError("⚠  Cổng phải là số từ 1 đến 65535!");
            txtPort.requestFocus();
            return;
        }

        setLoading(true, "⏳  Đang kết nối tới " + host + ":" + port + "...");

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
                    showError("✗  Không thể kết nối: " + ex.getMessage());
                    JOptionPane.showMessageDialog(
                        this,
                        "Không thể kết nối đến máy chủ tại " + host + ":" + port +
                        "\n\nChi tiết: " + ex.getMessage() +
                        "\n\nVui lòng kiểm tra Server đã được khởi động chưa!",
                        "Lỗi kết nối Server",
                        JOptionPane.ERROR_MESSAGE
                    );
                });
            }
        }).start();
    }

    private void showError(String msg) {
        lblStatus.setForeground(UIUtils.COLOR_DANGER);
        lblStatus.setText(msg);
    }

    private void setLoading(boolean loading, String statusText) {
        lblStatus.setText(statusText);
        lblStatus.setForeground(loading ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_DANGER);
        btnConnect.setEnabled(!loading);
        btnConnect.setText(loading ? "⏳  ĐANG KẾT NỐI..." : "🚀  VÀO PHÒNG CHAT");
        txtUsername.setEnabled(!loading);
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
