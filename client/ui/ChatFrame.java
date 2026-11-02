package client.ui;

import client.ChatClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ChatFrame — Premium dark-theme chat window.
 * Features: modern sidebar, bubble messages, emoji quick-panel, glassmorphism elements.
 */
public class ChatFrame extends JFrame implements ChatClient.ChatEventListener {

    private final ChatClient  client;
    private final LoginFrame  loginFrame;

    private JPanel               messagesBox;
    private JScrollPane          chatScroll;
    private UIUtils.ModernTextField txtInput;
    private UIUtils.ModernButton    btnSend;

    private DefaultListModel<String> userListModel;
    private JList<String>            userList;
    private JLabel                   lblOnlineBadge;

    private DefaultListModel<String> groupListModel;
    private JList<String>            groupList;
    private JLabel                   lblGroupCountBadge;
    private String currentRoomName = "General";

    private CardLayout sidebarCardLayout;
    private JPanel     sidebarCardPanel;
    private JButton    btnTabMembers;
    private JButton    btnTabGroups;

    private JLabel               lblServerInfo;
    private JComboBox<String>    cbRecipient;
    private JPanel               recipientStatusPanel;
    private JLabel               lblCurrentRecipient;
    private JButton              btnCancelPrivate;
    private final String ALL_USERS = "Tất cả (Mọi người)";

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

    // ── Toast notification ───────────────────────────────────────────────────────
    private JPanel  toastPanel;
    private JLabel  toastLabel;
    private Timer   toastHideTimer;
    private float   toastAlpha = 0f;
    private Timer   toastFadeTimer;

    public ChatFrame(ChatClient client, LoginFrame loginFrame) {
        this.client     = client;
        this.loginFrame = loginFrame;
        this.client.setEventListener(this);
        initUI();
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  UI CONSTRUCTION
    // ════════════════════════════════════════════════════════════════════════════
    private void initUI() {
        setTitle("ChatRoom TCP  ·  " + client.getUsername());
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1060, 700);
        setMinimumSize(new Dimension(860, 560));
        setLocationRelativeTo(null);
        UIUtils.applyGlobalDefaults();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { performLogout(); }
        });

        // ── Root layered pane (for toast overlay) ────────────────────────────
        JLayeredPane layeredRoot = new JLayeredPane();
        layeredRoot.setLayout(null);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(UIUtils.COLOR_BG_DARK);

        // ── Header ──────────────────────────────────────────────────────────
        mainPanel.add(buildHeader(), BorderLayout.NORTH);

        // ── Centre split ─────────────────────────────────────────────────────
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.76);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setDividerSize(1);
        split.setBackground(UIUtils.COLOR_BORDER);

        split.setLeftComponent(buildChatArea());
        split.setRightComponent(buildSidebar());
        mainPanel.add(split, BorderLayout.CENTER);

        // ── Input bar ────────────────────────────────────────────────────────
        mainPanel.add(buildInputBar(), BorderLayout.SOUTH);

        // Put main panel into layered pane
        layeredRoot.add(mainPanel, JLayeredPane.DEFAULT_LAYER);
        mainPanel.setBounds(0, 0, 1060, 700);

        // Resize listener to keep panels full-size
        layeredRoot.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                Dimension sz = layeredRoot.getSize();
                mainPanel.setBounds(0, 0, sz.width, sz.height);
                if (toastPanel != null) {
                    int tw = 340, th = 48;
                    toastPanel.setBounds((sz.width - tw) / 2, sz.height - th - 24, tw, th);
                }
            }
        });

        // ── Toast notification overlay ────────────────────────────────────
        toastPanel = buildToastPanel();
        toastPanel.setBounds((1060 - 340) / 2, 700 - 48 - 24, 340, 48);
        toastPanel.setVisible(false);
        layeredRoot.add(toastPanel, JLayeredPane.POPUP_LAYER);

        setContentPane(layeredRoot);

        // Initial system message
        addSystemMessage("✓  Đã kết nối thành công vào phòng chat!");
        txtInput.requestFocusInWindow();
    }

    // ── Header ──────────────────────────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UIUtils.COLOR_SURFACE_1);
                g2.fillRect(0, 0, getWidth(), getHeight());
                // Bottom border
                g2.setColor(UIUtils.COLOR_BORDER);
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);
                g2.dispose();
            }
        };
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(12, 22, 12, 22));

        // Left: avatar + info
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);

        JPanel avatar = UIUtils.createAvatarPanel(client.getUsername(), 40);
        left.add(avatar);

        JPanel textCol = new JPanel();
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        textCol.setOpaque(false);

        JLabel lblName = new JLabel(client.getUsername());
        lblName.setFont(UIUtils.FONT_HEADER);
        lblName.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        textCol.add(lblName);

        lblServerInfo = new JLabel("● Đang trực tuyến  ·  " + currentRoomName + "  ·  " +
                client.getServerHost() + ":" + client.getServerPort());
        lblServerInfo.setFont(UIUtils.FONT_TINY);
        lblServerInfo.setForeground(UIUtils.COLOR_ONLINE);
        textCol.add(lblServerInfo);

        left.add(textCol);
        header.add(left, BorderLayout.WEST);

        // Right: channel badge + logout
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        // Room badge
        UIUtils.RoundedPanel roomBadge = new UIUtils.RoundedPanel(UIUtils.COLOR_PRIMARY_LIGHT, UIUtils.COLOR_BORDER, 8);
        roomBadge.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JLabel lblRoom = new JLabel("# " + currentRoomName);
        lblRoom.setFont(UIUtils.FONT_BOLD);
        lblRoom.setForeground(UIUtils.COLOR_PRIMARY);
        roomBadge.add(lblRoom);
        right.add(roomBadge);

        // Logout button
        UIUtils.ModernButton btnLogout = new UIUtils.ModernButton(
            "Đăng xuất",
            new Color(60, 20, 20, 180),
            UIUtils.COLOR_DANGER,
            UIUtils.COLOR_ACCENT_ROSE
        );
        btnLogout.setCornerRadius(8);
        btnLogout.setPreferredSize(new Dimension(100, 34));
        btnLogout.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btnLogout.setForeground(Color.WHITE); }
            @Override public void mouseExited(MouseEvent e)  { btnLogout.setForeground(UIUtils.COLOR_ACCENT_ROSE); }
        });
        btnLogout.addActionListener(e -> performLogout());
        right.add(btnLogout);

        header.add(right, BorderLayout.EAST);
        return header;
    }

    // ── Chat area ────────────────────────────────────────────────────────────────
    private JScrollPane buildChatArea() {
        messagesBox = new JPanel();
        messagesBox.setLayout(new BoxLayout(messagesBox, BoxLayout.Y_AXIS));
        messagesBox.setBackground(UIUtils.COLOR_BG_DARK);
        messagesBox.setBorder(new EmptyBorder(16, 20, 16, 20));

        chatScroll = new JScrollPane(messagesBox);
        chatScroll.setBorder(null);
        chatScroll.getVerticalScrollBar().setUnitIncrement(18);
        chatScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScroll.setBackground(UIUtils.COLOR_BG_DARK);
        chatScroll.getViewport().setBackground(UIUtils.COLOR_BG_DARK);

        // Custom scrollbar styling
        chatScroll.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        return chatScroll;
    }

    // ── Sidebar ──────────────────────────────────────────────────────────────────
    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(UIUtils.COLOR_BG_SIDEBAR);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        sidebar.setOpaque(false);
        sidebar.setPreferredSize(new Dimension(270, 0));

        // Tab bar
        JPanel tabBar = new JPanel(new GridLayout(1, 2, 6, 0));
        tabBar.setOpaque(false);
        tabBar.setBorder(new EmptyBorder(12, 14, 12, 14));

        btnTabMembers = createSidebarTab("👥  Thành viên", true);
        btnTabGroups  = createSidebarTab("💬  Nhóm chat", false);
        tabBar.add(btnTabMembers);
        tabBar.add(btnTabGroups);
        sidebar.add(tabBar, BorderLayout.NORTH);

        // Card panels
        sidebarCardLayout = new CardLayout();
        sidebarCardPanel  = new JPanel(sidebarCardLayout);
        sidebarCardPanel.setOpaque(false);
        sidebarCardPanel.add(buildMembersPanel(), "MEMBERS");
        sidebarCardPanel.add(buildGroupsPanel(),  "GROUPS");
        sidebar.add(sidebarCardPanel, BorderLayout.CENTER);

        btnTabMembers.addActionListener(e -> {
            activateSidebarTab(btnTabMembers, btnTabGroups);
            sidebarCardLayout.show(sidebarCardPanel, "MEMBERS");
        });
        btnTabGroups.addActionListener(e -> {
            activateSidebarTab(btnTabGroups, btnTabMembers);
            sidebarCardLayout.show(sidebarCardPanel, "GROUPS");
            client.requestRoomList();
        });

        return sidebar;
    }

    private JButton createSidebarTab(String text, boolean active) {
        JButton btn = new JButton(text);
        btn.setFont(UIUtils.FONT_BOLD);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorderPainted(false);
        applyTabStyle(btn, active);
        return btn;
    }

    private void applyTabStyle(JButton btn, boolean active) {
        if (active) {
            btn.setBackground(UIUtils.COLOR_PRIMARY);
            btn.setForeground(Color.WHITE);
            btn.setBorder(new EmptyBorder(7, 14, 7, 14));
        } else {
            btn.setBackground(UIUtils.COLOR_SURFACE_2);
            btn.setForeground(UIUtils.COLOR_TEXT_MUTED);
            btn.setBorder(new EmptyBorder(7, 14, 7, 14));
        }
    }

    private void activateSidebarTab(JButton active, JButton inactive) {
        applyTabStyle(active, true);
        applyTabStyle(inactive, false);
    }

    private JPanel buildMembersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        // Sub-header
        JPanel subHeader = new JPanel(new BorderLayout());
        subHeader.setOpaque(false);
        subHeader.setBorder(new EmptyBorder(4, 16, 8, 16));
        lblOnlineBadge = new JLabel("● 1 trực tuyến");
        lblOnlineBadge.setFont(UIUtils.FONT_TINY);
        lblOnlineBadge.setForeground(UIUtils.COLOR_ONLINE);
        subHeader.add(lblOnlineBadge, BorderLayout.WEST);
        panel.add(subHeader, BorderLayout.NORTH);

        // User list
        userListModel = new DefaultListModel<>();
        userListModel.addElement(client.getUsername());
        userList = new JList<>(userListModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new UserCellRenderer());
        userList.setBackground(UIUtils.COLOR_BG_SIDEBAR);
        userList.setBorder(new EmptyBorder(4, 8, 4, 8));
        userList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) selectUserForPrivateChat();
            }
        });

        JScrollPane scroll = new JScrollPane(userList);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        panel.add(scroll, BorderLayout.CENTER);

        // Hint footer
        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(10, 16, 14, 16));
        JLabel hint = new JLabel("<html><span style='color:#6466A4; font-size:10px;'>↑ Nhấp đúp để chat riêng</span></html>");
        footer.add(hint);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildGroupsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        JPanel topArea = new JPanel();
        topArea.setLayout(new BoxLayout(topArea, BoxLayout.Y_AXIS));
        topArea.setOpaque(false);
        topArea.setBorder(new EmptyBorder(4, 14, 8, 14));

        // Create group button
        UIUtils.ModernButton btnCreate = new UIUtils.ModernButton(
            "+ Tạo nhóm mới",
            new Color(30, 35, 55),
            UIUtils.COLOR_SURFACE_3,
            UIUtils.COLOR_PRIMARY
        );
        btnCreate.setCornerRadius(8);
        btnCreate.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        btnCreate.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnCreate.setBorder(new EmptyBorder(0, 0, 0, 0));
        // Custom border via RoundedPanel wrapping
        btnCreate.addActionListener(e -> performCreateGroup());
        topArea.add(btnCreate);

        topArea.add(Box.createVerticalStrut(10));

        lblGroupCountBadge = new JLabel("Các nhóm trò chuyện");
        lblGroupCountBadge.setFont(UIUtils.FONT_TINY);
        lblGroupCountBadge.setForeground(UIUtils.COLOR_TEXT_MUTED);
        topArea.add(lblGroupCountBadge);

        panel.add(topArea, BorderLayout.NORTH);

        groupListModel = new DefaultListModel<>();
        groupListModel.addElement("General");
        groupListModel.addElement("Java");
        groupListModel.addElement("Gaming");

        groupList = new JList<>(groupListModel);
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setCellRenderer(new GroupCellRenderer());
        groupList.setBackground(UIUtils.COLOR_BG_SIDEBAR);
        groupList.setBorder(new EmptyBorder(4, 8, 4, 8));
        groupList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                performJoinSelectedGroup();
            }
        });

        JScrollPane scroll = new JScrollPane(groupList);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        panel.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(10, 16, 14, 16));
        JLabel hint = new JLabel("<html><span style='color:#6466A4; font-size:10px;'>↑ Nhấp vào nhóm để tham gia</span></html>");
        footer.add(hint);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    // ── Input bar ────────────────────────────────────────────────────────────────
    private JPanel buildInputBar() {
        JPanel bar = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(UIUtils.COLOR_SURFACE_1);
                g2.fillRect(0, 0, getWidth(), getHeight());
                // Top border
                g2.setColor(UIUtils.COLOR_BORDER);
                g2.fillRect(0, 0, getWidth(), 1);
                g2.dispose();
            }
        };
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(10, 20, 14, 20));

        // ── Recipient row ────────────────────────────────────────────────────
        JPanel recipRow = new JPanel(new BorderLayout(8, 0));
        recipRow.setOpaque(false);
        recipRow.setBorder(new EmptyBorder(0, 0, 8, 0));

        recipientStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        recipientStatusPanel.setOpaque(false);

        lblCurrentRecipient = new JLabel("[Chung] Gửi tới: " + currentRoomName);
        lblCurrentRecipient.setFont(UIUtils.FONT_BOLD);
        lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIMARY);
        recipientStatusPanel.add(lblCurrentRecipient);

        cbRecipient = new JComboBox<>();
        cbRecipient.addItem(ALL_USERS);
        cbRecipient.setFont(UIUtils.FONT_SMALL);
        cbRecipient.setPreferredSize(new Dimension(175, 28));
        cbRecipient.setBackground(UIUtils.COLOR_SURFACE_2);
        cbRecipient.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        cbRecipient.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        cbRecipient.addActionListener(e -> updateRecipientBadge());
        recipientStatusPanel.add(cbRecipient);

        btnCancelPrivate = new JButton("✕ Hủy riêng");
        btnCancelPrivate.setFont(UIUtils.FONT_TINY);
        btnCancelPrivate.setForeground(UIUtils.COLOR_DANGER);
        btnCancelPrivate.setBackground(new Color(40, 15, 15, 180));
        btnCancelPrivate.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(80, 30, 30), 1),
            new EmptyBorder(2, 8, 2, 8)
        ));
        btnCancelPrivate.setFocusPainted(false);
        btnCancelPrivate.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnCancelPrivate.setVisible(false);
        btnCancelPrivate.addActionListener(e -> cbRecipient.setSelectedItem(ALL_USERS));
        recipientStatusPanel.add(btnCancelPrivate);

        recipRow.add(recipientStatusPanel, BorderLayout.WEST);

        // ── Quick emoji row ──────────────────────────────────────────────────
        JPanel emojiRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        emojiRow.setOpaque(false);
        String[] emojis = {"😀", "😂", "❤️", "👍", "🔥", "😮"};
        for (String em : emojis) {
            JButton eb = new JButton(em);
            eb.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 15));
            eb.setPreferredSize(new Dimension(34, 28));
            eb.setMargin(new Insets(0, 0, 0, 0));
            eb.setContentAreaFilled(false);
            eb.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
            eb.setBackground(UIUtils.COLOR_SURFACE_2);
            eb.setCursor(new Cursor(Cursor.HAND_CURSOR));
            eb.setFocusPainted(false);
            eb.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    eb.setBackground(UIUtils.COLOR_SURFACE_3);
                    eb.setContentAreaFilled(true);
                }
                @Override public void mouseExited(MouseEvent e) {
                    eb.setContentAreaFilled(false);
                }
            });
            eb.addActionListener(e -> {
                txtInput.setText(txtInput.getText() + em);
                txtInput.requestFocusInWindow();
            });
            emojiRow.add(eb);
        }
        recipRow.add(emojiRow, BorderLayout.EAST);
        bar.add(recipRow);

        // ── Message input row ────────────────────────────────────────────────
        JPanel inputRow = new JPanel(new BorderLayout(10, 0));
        inputRow.setOpaque(false);

        txtInput = new UIUtils.ModernTextField("Nhập tin nhắn... (Enter để gửi)");
        txtInput.setPreferredSize(new Dimension(0, 44));
        inputRow.add(txtInput, BorderLayout.CENTER);

        btnSend = new UIUtils.ModernButton("Gửi ➤", UIUtils.COLOR_PRIMARY, UIUtils.COLOR_PRIMARY_HOVER, Color.WHITE);
        btnSend.setCornerRadius(10);
        btnSend.setPreferredSize(new Dimension(100, 44));
        inputRow.add(btnSend, BorderLayout.EAST);

        bar.add(inputRow);

        ActionListener sendAction = e -> performSendMessage();
        btnSend.addActionListener(sendAction);
        txtInput.addActionListener(sendAction);

        return bar;
    }

    // ── Toast overlay panel ───────────────────────────────────────────────────────
    private JPanel buildToastPanel() {
        JPanel tp = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, toastAlpha));
                g2.setColor(new Color(30, 33, 50, 230));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
                g2.setColor(UIUtils.COLOR_BORDER_LIGHT);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 14, 14));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        tp.setOpaque(false);
        tp.setBorder(new EmptyBorder(0, 16, 0, 16));

        toastLabel = new JLabel("", SwingConstants.CENTER);
        toastLabel.setFont(UIUtils.FONT_SMALL);
        toastLabel.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        tp.add(toastLabel, BorderLayout.CENTER);
        return tp;
    }

    public void showToast(String message) {
        showToast(message, UIUtils.COLOR_TEXT_PRIMARY);
    }

    public void showToast(String message, Color color) {
        SwingUtilities.invokeLater(() -> {
            toastLabel.setText(message);
            toastLabel.setForeground(color);
            toastPanel.setVisible(true);

            if (toastFadeTimer != null) toastFadeTimer.stop();
            if (toastHideTimer != null) toastHideTimer.stop();

            // Fade in
            toastAlpha = 0f;
            toastFadeTimer = new Timer(16, null);
            toastFadeTimer.addActionListener(e -> {
                toastAlpha = Math.min(1f, toastAlpha + 0.08f);
                toastPanel.repaint();
                if (toastAlpha >= 1f) toastFadeTimer.stop();
            });
            toastFadeTimer.start();

            // Auto-hide after 2.5s
            toastHideTimer = new Timer(2500, e -> {
                if (toastFadeTimer != null) toastFadeTimer.stop();
                toastFadeTimer = new Timer(16, null);
                toastFadeTimer.addActionListener(fe -> {
                    toastAlpha = Math.max(0f, toastAlpha - 0.06f);
                    toastPanel.repaint();
                    if (toastAlpha <= 0f) {
                        toastFadeTimer.stop();
                        toastPanel.setVisible(false);
                    }
                });
                toastFadeTimer.start();
            });
            toastHideTimer.setRepeats(false);
            toastHideTimer.start();
        });
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  SIDEBAR TAB BUTTONS STYLING (legacy)
    // ════════════════════════════════════════════════════════════════════════════
    private void styleTabButton(JButton btn, boolean active) {
        applyTabStyle(btn, active);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  BUSINESS LOGIC
    // ════════════════════════════════════════════════════════════════════════════
    private void performCreateGroup() {
        String name = JOptionPane.showInputDialog(this,
            "Nhập tên nhóm chat mới:", "Tạo nhóm chat", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty()) return;
        if (name.contains("|") || name.contains(",")) {
            JOptionPane.showMessageDialog(this, "Tên nhóm không được chứa '|' hoặc ','", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }
        client.sendCreateRoom(name);
        currentRoomName = name;
        updateRoomHeader();
        if (!groupListModel.contains(name)) groupListModel.addElement(name);
        groupList.setSelectedValue(name, true);
        addSystemMessage("✓  Bạn đã tạo và vào nhóm: " + name);
        showToast("Đã tạo nhóm: " + name, UIUtils.COLOR_ONLINE);
    }

    private void performJoinSelectedGroup() {
        String selected = groupList.getSelectedValue();
        if (selected == null || selected.equalsIgnoreCase(currentRoomName)) return;
        client.sendJoinRoom(selected);
        currentRoomName = selected;
        updateRoomHeader();
        groupList.repaint();
        addSystemMessage("→  Bạn đã chuyển sang nhóm: " + selected);
        showToast("Đã vào nhóm: " + selected, UIUtils.COLOR_PRIMARY);
    }

    private void updateRoomHeader() {
        if (lblServerInfo != null)
            lblServerInfo.setText("● Đang trực tuyến  ·  " + currentRoomName + "  ·  " +
                    client.getServerHost() + ":" + client.getServerPort());
        if (cbRecipient.getSelectedItem() == null || cbRecipient.getSelectedItem().equals(ALL_USERS))
            lblCurrentRecipient.setText("[Chung] Gửi tới: " + currentRoomName);
    }

    private void updateRecipientBadge() {
        String sel = (String) cbRecipient.getSelectedItem();
        if (sel == null || sel.equals(ALL_USERS)) {
            lblCurrentRecipient.setText("[Chung] Gửi tới: " + currentRoomName);
            lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIMARY);
            btnCancelPrivate.setVisible(false);
        } else {
            lblCurrentRecipient.setText("[Riêng] Đang chat với: @" + sel);
            lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIVATE_TEXT);
            btnCancelPrivate.setVisible(true);
        }
    }

    private void selectUserForPrivateChat() {
        String sel = userList.getSelectedValue();
        if (sel == null) return;
        String target = sel.replace(" (Bạn)", "").trim();
        if (target.equalsIgnoreCase(client.getUsername())) {
            JOptionPane.showMessageDialog(this, "Bạn không thể chat riêng với chính mình!", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean found = false;
        for (int i = 0; i < cbRecipient.getItemCount(); i++) {
            if (cbRecipient.getItemAt(i).equalsIgnoreCase(target)) {
                cbRecipient.setSelectedIndex(i);
                found = true;
                break;
            }
        }
        if (!found) { cbRecipient.addItem(target); cbRecipient.setSelectedItem(target); }
        txtInput.requestFocusInWindow();
        showToast("Chat riêng với @" + target, UIUtils.COLOR_PRIVATE_TEXT);
    }

    private void performSendMessage() {
        String message = txtInput.getText().trim();
        if (message.isEmpty()) return;
        String recipient = (String) cbRecipient.getSelectedItem();
        if (recipient == null || recipient.equals(ALL_USERS)) {
            client.sendPublicMessage(message);
        } else {
            client.sendPrivateMessage(recipient, message);
            addPrivateMessage(client.getUsername(), recipient, message, true);
        }
        txtInput.setText("");
        txtInput.requestFocusInWindow();
    }

    private void performLogout() {
        int confirm = JOptionPane.showConfirmDialog(this,
            "Bạn có chắc chắn muốn đăng xuất?", "Xác nhận đăng xuất", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            client.logout();
            this.dispose();
            if (loginFrame != null) loginFrame.setVisible(true);
            else System.exit(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  MESSAGE RENDERING
    // ════════════════════════════════════════════════════════════════════════════
    public void addPublicMessage(String sender, String message) {
        boolean isMe = sender.equalsIgnoreCase(client.getUsername());
        String time  = LocalTime.now().format(timeFormatter);

        JPanel row = new JPanel(new FlowLayout(isMe ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 999));

        if (!isMe) {
            row.add(UIUtils.createAvatarPanel(sender, 34));
        }

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setOpaque(false);

        if (!isMe) {
            JLabel senderLbl = new JLabel("  " + sender);
            senderLbl.setFont(UIUtils.FONT_TINY);
            senderLbl.setForeground(UIUtils.getAvatarColor(sender));
            senderLbl.setAlignmentX(isMe ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
            container.add(senderLbl);
            container.add(Box.createVerticalStrut(3));
        }

        // Bubble
        Color bgColor  = isMe ? UIUtils.COLOR_MY_BUBBLE : UIUtils.COLOR_OTHER_BUBBLE;
        Color bdrColor = isMe ? null : UIUtils.COLOR_BORDER;
        UIUtils.RoundedPanel bubble = new UIUtils.RoundedPanel(bgColor, bdrColor, 16);
        bubble.setLayout(new BorderLayout(0, 4));
        bubble.setBorder(new EmptyBorder(9, 14, 9, 14));
        bubble.setAlignmentX(isMe ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);

        JLabel msgLbl = new JLabel("<html><body style='max-width:380px; word-wrap:break-word;'>" + escapeHtml(message) + "</body></html>");
        msgLbl.setFont(UIUtils.FONT_REGULAR);
        msgLbl.setForeground(isMe ? Color.WHITE : UIUtils.COLOR_TEXT_PRIMARY);
        bubble.add(msgLbl, BorderLayout.CENTER);

        JLabel timeLbl = new JLabel(time, isMe ? SwingConstants.RIGHT : SwingConstants.LEFT);
        timeLbl.setFont(UIUtils.FONT_TINY);
        timeLbl.setForeground(isMe ? new Color(200, 210, 255, 160) : UIUtils.COLOR_TEXT_MUTED);
        bubble.add(timeLbl, BorderLayout.SOUTH);

        container.add(bubble);
        row.add(container);
        appendBubbleRow(row);
    }

    public void addPrivateMessage(String sender, String receiver, String message, boolean isOutgoing) {
        String time = LocalTime.now().format(timeFormatter);
        JPanel row = new JPanel(new FlowLayout(isOutgoing ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 999));

        if (!isOutgoing) {
            row.add(UIUtils.createAvatarPanel(sender, 34));
        }

        UIUtils.RoundedPanel bubble = new UIUtils.RoundedPanel(UIUtils.COLOR_PRIVATE_BG, UIUtils.COLOR_PRIVATE_BORDER, 16);
        bubble.setLayout(new BorderLayout(0, 4));
        bubble.setBorder(new EmptyBorder(9, 14, 9, 14));

        String hdr = isOutgoing ? "🔒 chat riêng → @" + receiver : "🔒 @" + sender + " nhắn riêng";
        JLabel hdrLbl = new JLabel(hdr);
        hdrLbl.setFont(UIUtils.FONT_TINY);
        hdrLbl.setForeground(UIUtils.COLOR_PRIVATE_TEXT);
        bubble.add(hdrLbl, BorderLayout.NORTH);

        JLabel msgLbl = new JLabel("<html><body style='max-width:360px; word-wrap:break-word;'>" + escapeHtml(message) + "</body></html>");
        msgLbl.setFont(UIUtils.FONT_REGULAR);
        msgLbl.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        bubble.add(msgLbl, BorderLayout.CENTER);

        JLabel timeLbl = new JLabel(time, SwingConstants.RIGHT);
        timeLbl.setFont(UIUtils.FONT_TINY);
        timeLbl.setForeground(UIUtils.COLOR_TEXT_MUTED);
        bubble.add(timeLbl, BorderLayout.SOUTH);

        row.add(bubble);
        appendBubbleRow(row);
    }

    public void addSystemMessage(String text) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 6));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        UIUtils.RoundedPanel pill = new UIUtils.RoundedPanel(UIUtils.COLOR_SYSTEM_PILL, UIUtils.COLOR_BORDER, 12);
        pill.setBorder(new EmptyBorder(4, 16, 4, 16));
        JLabel lbl = new JLabel(text);
        lbl.setFont(UIUtils.FONT_TINY);
        lbl.setForeground(UIUtils.COLOR_TEXT_MUTED);
        pill.add(lbl);

        row.add(pill);
        appendBubbleRow(row);
    }

    private void appendBubbleRow(JPanel row) {
        messagesBox.add(row);
        messagesBox.revalidate();
        messagesBox.repaint();
        SwingUtilities.invokeLater(() -> {
            JScrollBar v = chatScroll.getVerticalScrollBar();
            v.setValue(v.getMaximum());
        });
    }

    private String escapeHtml(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  ChatEventListener callbacks
    // ════════════════════════════════════════════════════════════════════════════
    @Override public void onMessageReceived(String sender, String message) {
        SwingUtilities.invokeLater(() -> addPublicMessage(sender, message));
    }
    @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {
        SwingUtilities.invokeLater(() -> addPrivateMessage(sender, receiver, message, false));
    }
    @Override public void onUserListUpdated(List<String> users) {
        SwingUtilities.invokeLater(() -> {
            String cur = (String) cbRecipient.getSelectedItem();
            userListModel.clear();
            cbRecipient.removeAllItems();
            cbRecipient.addItem(ALL_USERS);
            for (String u : users) {
                userListModel.addElement(u);
                if (!u.equalsIgnoreCase(client.getUsername())) cbRecipient.addItem(u);
            }
            lblOnlineBadge.setText("● " + users.size() + " trực tuyến");
            if (cur != null && !cur.equals(ALL_USERS)) {
                for (int i = 0; i < cbRecipient.getItemCount(); i++) {
                    if (cbRecipient.getItemAt(i).equalsIgnoreCase(cur)) {
                        cbRecipient.setSelectedIndex(i); break;
                    }
                }
            }
            updateRecipientBadge();
        });
    }
    @Override public void onRoomListUpdated(List<String> rooms) {
        SwingUtilities.invokeLater(() -> {
            groupListModel.clear();
            for (String r : rooms) groupListModel.addElement(r);
            if (lblGroupCountBadge != null)
                lblGroupCountBadge.setText("Các nhóm trò chuyện (" + rooms.size() + ")");
            if (groupList != null) groupList.repaint();
        });
    }
    @Override public void onSystemMessage(String message) {
        SwingUtilities.invokeLater(() -> addSystemMessage(message));
    }
    @Override public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this,
                "Mất kết nối tới Server!\n" + (reason != null ? reason : ""),
                "Ngắt kết nối", JOptionPane.WARNING_MESSAGE);
            this.dispose();
            if (loginFrame != null) loginFrame.setVisible(true);
        });
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  CELL RENDERERS
    // ════════════════════════════════════════════════════════════════════════════
    private class UserCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String username = (String) value;
            boolean isSelf  = username.equalsIgnoreCase(client.getUsername());

            JPanel item = new JPanel(new BorderLayout(10, 0));
            item.setBorder(new EmptyBorder(7, 10, 7, 10));
            item.setOpaque(true);
            item.setBackground(isSelected ? UIUtils.COLOR_PRIMARY_LIGHT : UIUtils.COLOR_BG_SIDEBAR);

            item.add(UIUtils.createAvatarPanel(username, 30), BorderLayout.WEST);

            JPanel nameCol = new JPanel();
            nameCol.setLayout(new BoxLayout(nameCol, BoxLayout.Y_AXIS));
            nameCol.setOpaque(false);

            JLabel nameLbl = new JLabel(username + (isSelf ? "  (Bạn)" : ""));
            nameLbl.setFont(isSelf ? UIUtils.FONT_BOLD : UIUtils.FONT_REGULAR);
            nameLbl.setForeground(isSelf ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_TEXT_PRIMARY);
            nameCol.add(nameLbl);

            JLabel statusLbl = new JLabel("● Online");
            statusLbl.setFont(UIUtils.FONT_TINY);
            statusLbl.setForeground(UIUtils.COLOR_ONLINE);
            nameCol.add(statusLbl);

            item.add(nameCol, BorderLayout.CENTER);
            return item;
        }
    }

    private class GroupCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String roomName = (String) value;
            boolean isCurrent = roomName.equalsIgnoreCase(currentRoomName);

            JPanel item = new JPanel(new BorderLayout(10, 0));
            item.setBorder(new EmptyBorder(8, 10, 8, 10));
            item.setOpaque(true);
            if (isSelected)        item.setBackground(UIUtils.COLOR_PRIMARY_LIGHT);
            else if (isCurrent)    item.setBackground(new Color(25, 35, 50));
            else                   item.setBackground(UIUtils.COLOR_BG_SIDEBAR);

            // Channel icon
            JPanel icon = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color ic = isCurrent ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_ACCENT_VIOLET;
                    g2.setColor(new Color(ic.getRed(), ic.getGreen(), ic.getBlue(), 40));
                    g2.fillOval(0, 0, 30, 30);
                    g2.setColor(ic);
                    g2.fillOval(3, 3, 24, 24);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString("#", (30 - fm.stringWidth("#")) / 2, (30 - fm.getHeight()) / 2 + fm.getAscent());
                    g2.dispose();
                }
            };
            icon.setOpaque(false);
            icon.setPreferredSize(new Dimension(30, 30));
            item.add(icon, BorderLayout.WEST);

            JPanel nameCol = new JPanel();
            nameCol.setLayout(new BoxLayout(nameCol, BoxLayout.Y_AXIS));
            nameCol.setOpaque(false);

            JLabel nameLbl = new JLabel(roomName);
            nameLbl.setFont(isCurrent ? UIUtils.FONT_BOLD : UIUtils.FONT_REGULAR);
            nameLbl.setForeground(isCurrent ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_TEXT_PRIMARY);
            nameCol.add(nameLbl);

            if (isCurrent) {
                JLabel curLbl = new JLabel("✓ Đang tham gia");
                curLbl.setFont(UIUtils.FONT_TINY);
                curLbl.setForeground(UIUtils.COLOR_ONLINE);
                nameCol.add(curLbl);
            }

            item.add(nameCol, BorderLayout.CENTER);
            return item;
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  MODERN SCROLLBAR UI
    // ════════════════════════════════════════════════════════════════════════════
    private static class ModernScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            this.thumbColor      = UIUtils.COLOR_SURFACE_3;
            this.trackColor      = UIUtils.COLOR_SURFACE_1;
        }
        @Override protected JButton createDecreaseButton(int o) { return zeroButton(); }
        @Override protected JButton createIncreaseButton(int o) { return zeroButton(); }
        private JButton zeroButton() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(thumbColor);
            g2.fill(new RoundRectangle2D.Float(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8));
            g2.dispose();
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }
}
