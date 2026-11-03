package server;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * ServerGUI — Premium dark admin dashboard for TCP Chat Server.
 */
public class ServerGUI extends JFrame {

    // ── Design tokens (self-contained, no UIUtils dependency) ─────────────────
    private static final Color BG_DARK       = new Color(10, 11, 15);
    private static final Color BG_PANEL      = new Color(15, 17, 23);
    private static final Color SURFACE_1     = new Color(20, 22, 30);
    private static final Color SURFACE_2     = new Color(28, 31, 42);
    private static final Color SURFACE_3     = new Color(38, 41, 55);
    private static final Color BORDER        = new Color(38, 41, 58);
    private static final Color BORDER_LIGHT  = new Color(55, 58, 80);
    private static final Color PRIMARY       = new Color(99, 102, 241);
    private static final Color PRIMARY_LIGHT = new Color(99, 102, 241, 18);
    private static final Color COLOR_GREEN   = new Color(34, 197, 94);
    private static final Color COLOR_RED     = new Color(239, 68, 68);
    private static final Color COLOR_AMBER   = new Color(245, 158, 11);
    private static final Color COLOR_VIOLET  = new Color(139, 92, 246);
    private static final Color TXT_PRIMARY   = new Color(237, 238, 248);
    private static final Color TXT_SECONDARY = new Color(148, 151, 180);
    private static final Color TXT_MUTED     = new Color(90, 93, 120);

    private static final Font FONT_DISPLAY   = new Font("Segoe UI", Font.BOLD, 20);
    private static final Font FONT_HEADER    = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font FONT_BOLD      = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font FONT_REGULAR   = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font FONT_SMALL     = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_TINY      = new Font("Segoe UI", Font.PLAIN, 11);
    private static final Font FONT_MONO      = new Font("Consolas", Font.PLAIN, 12);

    // ── State ─────────────────────────────────────────────────────────────────
    private final ClientManager clientManager;
    private ChatServer          chatServer;
    private String              serverIp;

    // ── UI Components ─────────────────────────────────────────────────────────
    private JLabel   lblStatus;
    private JLabel   lblClientCount;
    private JLabel   lblIpValue;
    private JTextArea logArea;
    private DefaultListModel<String> clientListModel;
    private JList<String>            clientList;
    private JButton  btnStart;
    private JButton  btnStop;
    private JButton  btnKick;
    private JButton  btnLock;

    // Status dot animation
    private Timer   statusDotTimer;
    private boolean statusDotVisible = true;

    // ── Stats cards ───────────────────────────────────────────────────────────
    private JLabel statClientVal;
    private JLabel statRoomVal;
    private JLabel statUptimeVal;
    private long   startTimeMs = 0;
    private Timer  uptimeTimer;

    public ServerGUI(ClientManager clientManager) {
        this.clientManager = clientManager;
        applyDefaults();
        createGUI();
    }

    public void setChatServer(ChatServer chatServer) {
        this.chatServer = chatServer;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  LAF DEFAULTS
    // ════════════════════════════════════════════════════════════════════════════
    private void applyDefaults() {
        UIManager.put("Panel.background",            SURFACE_1);
        UIManager.put("ScrollPane.background",       BG_PANEL);
        UIManager.put("Viewport.background",         BG_PANEL);
        UIManager.put("List.background",             SURFACE_1);
        UIManager.put("List.foreground",             TXT_PRIMARY);
        UIManager.put("List.selectionBackground",    PRIMARY_LIGHT);
        UIManager.put("List.selectionForeground",    TXT_PRIMARY);
        UIManager.put("TextArea.background",         BG_PANEL);
        UIManager.put("TextArea.foreground",         new Color(180, 220, 180));
        UIManager.put("TextArea.caretForeground",    PRIMARY);
        UIManager.put("TextArea.font",               FONT_MONO);
        UIManager.put("OptionPane.background",       SURFACE_2);
        UIManager.put("OptionPane.messageForeground",TXT_PRIMARY);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  GUI CONSTRUCTION
    // ════════════════════════════════════════════════════════════════════════════
    private void createGUI() {
        setTitle("TCP Chat · Quản lý máy chủ");
        setSize(980, 640);
        setMinimumSize(new Dimension(860, 560));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        serverIp = getServerIpAddress();

        JPanel root = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(BG_DARK);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        root.setOpaque(false);

        root.add(buildHeader(),  BorderLayout.NORTH);
        root.add(buildCentre(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);

        setContentPane(root);
        setVisible(true);
        appendLog("🖥  Server Admin Panel đã khởi động.");
        appendLog("    IP: " + serverIp + "  |  Port: 5000");
    }

    // ── Header ──────────────────────────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, new Color(18, 14, 40), getWidth(), 0, new Color(10, 15, 30));
                g2.setPaint(gp);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(BORDER);
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);
                g2.dispose();
            }
        };
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(16, 24, 16, 24));

        // Left: logo + title
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        left.setOpaque(false);

        // Logo icon
        JPanel logo = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int s = 44;
                GradientPaint gp = new GradientPaint(0, 0, PRIMARY, 0, s, COLOR_VIOLET);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, s, s, 12, 12);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI", Font.BOLD, 22));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("⚡", (s - fm.stringWidth("⚡")) / 2, (s - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
        logo.setOpaque(false);
        logo.setPreferredSize(new Dimension(44, 44));
        left.add(logo);

        JPanel titleCol = new JPanel();
        titleCol.setLayout(new BoxLayout(titleCol, BoxLayout.Y_AXIS));
        titleCol.setOpaque(false);

        JLabel lblTitle = new JLabel("TCP Chat Server");
        lblTitle.setFont(FONT_DISPLAY);
        lblTitle.setForeground(TXT_PRIMARY);
        titleCol.add(lblTitle);

        JLabel lblSub = new JLabel("Admin Control Panel  ·  v2.0");
        lblSub.setFont(FONT_TINY);
        lblSub.setForeground(TXT_MUTED);
        titleCol.add(lblSub);

        left.add(titleCol);
        header.add(left, BorderLayout.WEST);

        // Right: status + IP chips
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        // IP chip
        JPanel ipChip = buildChip("IP: " + serverIp, PRIMARY, new Color(PRIMARY.getRed(), PRIMARY.getGreen(), PRIMARY.getBlue(), 20));
        right.add(ipChip);

        // Port chip
        JPanel portChip = buildChip("PORT: 5000", TXT_SECONDARY, SURFACE_2);
        right.add(portChip);

        // Status indicator
        lblStatus = new JLabel("● STOPPED");
        lblStatus.setFont(FONT_BOLD);
        lblStatus.setForeground(COLOR_RED);
        right.add(lblStatus);

        header.add(right, BorderLayout.EAST);

        // ── Stats row ────────────────────────────────────────────────────────
        JPanel statsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        statsRow.setOpaque(false);
        statsRow.setBorder(new EmptyBorder(10, 0, 0, 0));

        statClientVal  = new JLabel("0");
        statRoomVal    = new JLabel("0");
        statUptimeVal  = new JLabel("--:--");

        statsRow.add(buildStatCard("👥  Clients", statClientVal, COLOR_GREEN));
        statsRow.add(buildStatCard("💬  Rooms",   statRoomVal,   COLOR_VIOLET));
        statsRow.add(buildStatCard("⏱  Uptime",  statUptimeVal, PRIMARY));

        JPanel headerFull = new JPanel(new BorderLayout());
        headerFull.setOpaque(false);
        headerFull.add(header, BorderLayout.CENTER);
        headerFull.add(statsRow, BorderLayout.SOUTH);
        headerFull.setBorder(new EmptyBorder(0, 24, 12, 24));
        return headerFull;
    }

    private JPanel buildChip(String text, Color fg, Color bg) {
        JPanel chip = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 8, 8));
                g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 80));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 8, 8));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        chip.setOpaque(false);
        chip.setBorder(new EmptyBorder(4, 10, 4, 10));
        JLabel l = new JLabel(text);
        l.setFont(FONT_SMALL);
        l.setForeground(fg);
        chip.add(l);
        return chip;
    }

    private JPanel buildStatCard(String label, JLabel valueLabel, Color accent) {
        JPanel card = new JPanel(new BorderLayout(8, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(SURFACE_1);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
                g2.setColor(BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10, 10));
                // Left accent bar
                g2.setColor(accent);
                g2.fill(new RoundRectangle2D.Float(0, 6, 3, getHeight() - 12, 3, 3));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(8, 16, 8, 16));
        card.setPreferredSize(new Dimension(130, 44));

        JLabel lbl = new JLabel(label);
        lbl.setFont(FONT_TINY);
        lbl.setForeground(TXT_MUTED);
        card.add(lbl, BorderLayout.NORTH);

        valueLabel.setFont(FONT_HEADER);
        valueLabel.setForeground(accent);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    // ── Centre ───────────────────────────────────────────────────────────────────
    private JPanel buildCentre() {
        JPanel centre = new JPanel(new BorderLayout(0, 0));
        centre.setOpaque(false);
        centre.setBorder(new EmptyBorder(0, 16, 0, 16));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildClientPanel(), buildLogPanel());
        split.setDividerLocation(280);
        split.setDividerSize(1);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setBackground(BORDER);

        centre.add(split, BorderLayout.CENTER);
        return centre;
    }

    // ── Client list panel ────────────────────────────────────────────────────────
    private JPanel buildClientPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(SURFACE_1);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 12, 12));
                g2.setColor(BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 12, 12));
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(14, 14, 14, 14));

        // Header row
        JPanel hdr = new JPanel(new BorderLayout(8, 0));
        hdr.setOpaque(false);
        hdr.setBorder(new EmptyBorder(0, 0, 10, 0));

        JLabel title = new JLabel("Clients Online");
        title.setFont(FONT_HEADER);
        title.setForeground(TXT_PRIMARY);
        hdr.add(title, BorderLayout.WEST);

        lblClientCount = new JLabel("0");
        lblClientCount.setFont(FONT_BOLD);
        lblClientCount.setForeground(COLOR_GREEN);
        hdr.add(lblClientCount, BorderLayout.EAST);
        panel.add(hdr, BorderLayout.NORTH);

        // List
        clientListModel = new DefaultListModel<>();
        clientList = new JList<>(clientListModel);
        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientList.setCellRenderer(new ClientCellRenderer());
        clientList.setBackground(SURFACE_1);
        clientList.setFixedCellHeight(52);
        clientList.setBorder(null);

        clientList.addListSelectionListener(e -> {
            boolean hasSel = clientList.getSelectedValue() != null;
            boolean running = chatServer != null && chatServer.isRunning();
            btnKick.setEnabled(hasSel && running);
        });
        clientList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int idx = clientList.locationToIndex(e.getPoint());
                if (idx >= 0) displayClientInfo(clientListModel.getElementAt(idx));
            }
        });
        clientList.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) {
                String sel = clientList.getSelectedValue();
                if (sel != null) displayClientInfo(sel);
            }
        });

        JScrollPane scroll = new JScrollPane(clientList);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBackground(SURFACE_1);
        scroll.getViewport().setBackground(SURFACE_1);
        scroll.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        panel.add(scroll, BorderLayout.CENTER);

        // Kick button
        btnKick = buildActionButton("⚡ Kick Client", COLOR_RED, new Color(50, 15, 15), false);
        btnKick.addActionListener(e -> kickSelectedClient());
        JPanel kickWrap = new JPanel(new BorderLayout());
        kickWrap.setOpaque(false);
        kickWrap.setBorder(new EmptyBorder(10, 0, 0, 0));
        kickWrap.add(btnKick);
        panel.add(kickWrap, BorderLayout.SOUTH);

        return panel;
    }

    // ── Log panel ─────────────────────────────────────────────────────────────────
    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG_PANEL);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 12, 12));
                g2.setColor(BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 12, 12));
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(14, 14, 14, 14));

        // Header
        JPanel hdr = new JPanel(new BorderLayout());
        hdr.setOpaque(false);
        hdr.setBorder(new EmptyBorder(0, 0, 10, 0));

        JLabel title = new JLabel("📋  Server Log");
        title.setFont(FONT_HEADER);
        title.setForeground(TXT_PRIMARY);
        hdr.add(title, BorderLayout.WEST);

        JButton btnClear = new JButton("Xóa log");
        btnClear.setFont(FONT_TINY);
        btnClear.setForeground(TXT_MUTED);
        btnClear.setBackground(SURFACE_2);
        btnClear.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1),
            new EmptyBorder(2, 8, 2, 8)
        ));
        btnClear.setFocusPainted(false);
        btnClear.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnClear.addActionListener(e -> logArea.setText(""));
        hdr.add(btnClear, BorderLayout.EAST);

        panel.add(hdr, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBackground(BG_PANEL);
        logArea.setForeground(new Color(150, 220, 160));
        logArea.setFont(FONT_MONO);
        logArea.setBorder(new EmptyBorder(6, 6, 6, 6));
        logArea.setCaretColor(PRIMARY);

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(null);
        logScroll.setBackground(BG_PANEL);
        logScroll.getViewport().setBackground(BG_PANEL);
        logScroll.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        panel.add(logScroll, BorderLayout.CENTER);

        return panel;
    }

    // ── Footer / buttons ─────────────────────────────────────────────────────────
    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(SURFACE_1);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(BORDER);
                g2.fillRect(0, 0, getWidth(), 1);
                g2.dispose();
            }
        };
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(12, 24, 14, 24));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        btnRow.setOpaque(false);

        btnStart = buildActionButton("▶  Start Server", COLOR_GREEN, new Color(10, 35, 15), true);
        btnStop  = buildActionButton("■  Stop Server",  COLOR_RED,   new Color(35, 10, 10), false);
        btnLock  = buildActionButton("🔒  Khóa Server",  COLOR_AMBER, new Color(35, 30, 10), false);

        btnStop.setEnabled(false);
        btnLock.setEnabled(false);

        btnStart.addActionListener(e -> startServer());
        btnStop.addActionListener(e -> stopServer());
        btnLock.addActionListener(e -> toggleLockServer());

        btnRow.add(btnStart);
        btnRow.add(btnStop);
        btnRow.add(btnLock);
        footer.add(btnRow, BorderLayout.WEST);

        // Version label
        JLabel ver = new JLabel("TCP ChatRoom Server v2.0  ·  Java Swing");
        ver.setFont(FONT_TINY);
        ver.setForeground(TXT_MUTED);
        footer.add(ver, BorderLayout.EAST);
        return footer;
    }

    private JButton buildActionButton(String text, Color fg, Color bg, boolean enabled) {
        JButton btn = new JButton(text) {
            private boolean hover = false;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
                    @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bgC = isEnabled() ? (hover ? SURFACE_3 : bg) : new Color(30, 32, 45);
                g2.setColor(bgC);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
                if (isEnabled()) {
                    g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 120));
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth()-1, getHeight()-1, 10, 10));
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(FONT_BOLD);
        btn.setForeground(enabled ? fg : TXT_MUTED);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setEnabled(enabled);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(8, 18, 8, 18));
        return btn;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  SERVER ACTIONS
    // ════════════════════════════════════════════════════════════════════════════
    void startServer() {
        if (chatServer == null) { appendLog("❌  Không thể Start Server."); return; }
        boolean started = chatServer.startServer();
        if (started) {
            setServerStatus(true, false);
            appendLog("✓  Server đã khởi động tại IP: " + serverIp + "  port 5000");
            appendLog("   Gợi ý: Nhập IP trên vào ô 'IP Server' của Client để kết nối.");
            startTimeMs = System.currentTimeMillis();
            startUptimeTimer();
        }
    }

    private void stopServer() {
        if (chatServer != null) chatServer.stopServer();
        setServerStatus(false, false);
        appendLog("■  Server đã dừng.");
        if (uptimeTimer != null) uptimeTimer.stop();
        statUptimeVal.setText("--:--");
    }

    private void toggleLockServer() {
        if (chatServer == null || !chatServer.isRunning()) return;
        boolean locked = chatServer.toggleLock();
        setServerStatus(true, locked);
        if (locked) {
            btnLock.setText("🔓  Mở khóa");
            appendLog("🔒  Server đã KHÓA — chặn kết nối mới.");
        } else {
            btnLock.setText("🔒  Khóa Server");
            appendLog("🔓  Server đã MỞ KHÓA — cho phép kết nối mới.");
        }
    }

    private void setServerStatus(boolean running, boolean locked) {
        if (running && locked) {
            lblStatus.setText("🔒 LOCKED");
            lblStatus.setForeground(COLOR_AMBER);
        } else if (running) {
            lblStatus.setText("● RUNNING");
            lblStatus.setForeground(COLOR_GREEN);
        } else {
            lblStatus.setText("● STOPPED");
            lblStatus.setForeground(COLOR_RED);
        }
        btnStart.setEnabled(!running);
        btnStart.setForeground(!running ? COLOR_GREEN : TXT_MUTED);
        btnStop.setEnabled(running);
        btnStop.setForeground(running ? COLOR_RED : TXT_MUTED);
        btnLock.setEnabled(running);
        btnLock.setForeground(running ? COLOR_AMBER : TXT_MUTED);
        btnKick.setEnabled(false);
    }

    private void kickSelectedClient() {
        String selected = clientList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn client để kick.", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
            "Kick client '" + selected + "' ra khỏi server?",
            "Xác nhận Kick", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            boolean success = clientManager.kickClient(selected);
            appendLog(success ? "⚡  Đã kick '" + selected + "'." : "❌  Không thể kick '" + selected + "'.");
        }
    }

    private void displayClientInfo(String username) {
        if (username == null || clientManager == null) return;
        ClientHandler client = clientManager.getClient(username);
        if (client == null) return;
        String t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        appendLog(String.format("[%s] 👤 %s  |  IP: %s:%d  |  Room: %s  |  Since: %s",
            t, username, client.getClientIp(), client.getClientPort(),
            client.getCurrentRoom(), client.getConnectedTime()));
    }

    private void startUptimeTimer() {
        if (uptimeTimer != null) uptimeTimer.stop();
        uptimeTimer = new Timer(1000, e -> {
            long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
            long h = elapsed / 3600;
            long m = (elapsed % 3600) / 60;
            long s = elapsed % 60;
            statUptimeVal.setText(String.format("%02d:%02d:%02d", h, m, s));
        });
        uptimeTimer.start();
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════════════════════
    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.append("[" + ts + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public void refreshClientList() {
        SwingUtilities.invokeLater(() -> {
            String sel = clientList.getSelectedValue();
            clientListModel.clear();
            for (String u : clientManager.getUsernames()) clientListModel.addElement(u);
            if (sel != null && clientListModel.contains(sel))
                clientList.setSelectedValue(sel, true);
            else btnKick.setEnabled(false);
            int count = clientManager.getClientCount();
            lblClientCount.setText(count + "");
            statClientVal.setText(count + "");
        });
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  CLIENT CELL RENDERER
    // ════════════════════════════════════════════════════════════════════════════
    private class ClientCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String username = (String) value;

            JPanel item = new JPanel(new BorderLayout(10, 0)) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(isSelected ? new Color(30, 35, 60) : (index % 2 == 0 ? SURFACE_1 : SURFACE_2));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    if (isSelected) {
                        g2.setColor(PRIMARY);
                        g2.fillRect(0, 0, 3, getHeight());
                    }
                    g2.dispose();
                }
            };
            item.setOpaque(false);
            item.setBorder(new EmptyBorder(8, 12, 8, 12));

            // Avatar circle
            JPanel av = createServerAvatar(username, 34);
            item.add(av, BorderLayout.WEST);

            JPanel info = new JPanel();
            info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
            info.setOpaque(false);
            info.setBorder(new EmptyBorder(0, 10, 0, 0));

            JLabel nameLbl = new JLabel(username);
            nameLbl.setFont(FONT_BOLD);
            nameLbl.setForeground(TXT_PRIMARY);
            info.add(nameLbl);

            ClientHandler ch = clientManager.getClient(username);
            String roomInfo = ch != null ? "📍 " + ch.getCurrentRoom() + "  ·  " + ch.getClientIp() : "Connected";
            JLabel detailLbl = new JLabel(roomInfo);
            detailLbl.setFont(FONT_TINY);
            detailLbl.setForeground(TXT_MUTED);
            info.add(detailLbl);

            item.add(info, BorderLayout.CENTER);

            // Online dot
            JPanel dot = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(34, 197, 94, 40));
                    g2.fillOval(0, 0, 12, 12);
                    g2.setColor(COLOR_GREEN);
                    g2.fillOval(3, 3, 6, 6);
                    g2.dispose();
                }
            };
            dot.setOpaque(false);
            dot.setPreferredSize(new Dimension(12, 12));
            item.add(dot, BorderLayout.EAST);

            return item;
        }
    }

    private JPanel createServerAvatar(String name, int size) {
        Color[] colors = {PRIMARY, COLOR_VIOLET, COLOR_GREEN, new Color(6, 182, 212), COLOR_AMBER};
        Color c = colors[Math.abs(name.hashCode()) % colors.length];
        String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();

        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, c, 0, size, c.darker());
                g2.setPaint(gp);
                g2.fillOval(0, 0, size, size);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI", Font.BOLD, size / 2 + 1));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(letter, (size - fm.stringWidth(letter)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(size, size));
        return p;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  CUSTOM SCROLLBAR
    // ════════════════════════════════════════════════════════════════════════════
    private static class DarkScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor = SURFACE_3;
            trackColor = SURFACE_1;
        }
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }
        private JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(thumbColor);
            g2.fill(new RoundRectangle2D.Float(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6));
            g2.dispose();
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  NETWORK UTILITY
    // ════════════════════════════════════════════════════════════════════════════
    private String getServerIpAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress())
                        return addr.getHostAddress();
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) { return "127.0.0.1"; }
    }
}
