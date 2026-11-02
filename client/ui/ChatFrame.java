package client.ui;

import client.ChatClient;
import client.ChatClient.ChatMessageItem;
import client.ChatClient.FriendItem;
import client.ChatClient.SearchUserItem;
import client.file.FileTransferEngine;
import client.media.audio.AudioCallEngine;
import client.media.video.VideoCallEngine;
import client.notification.NotificationManager;
import shared.protocol.Packet;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatFrame — Giao diện chính của Messenger Desktop Pro.
 * Hỗ trợ Chat Nhóm, Chat Riêng, Hệ thống Bạn bè (Online/Offline), Lời mời kết bạn,
 * Tìm kiếm người dùng, bong bóng tin nhắn, Emoji, và Toast notification.
 */
public class ChatFrame extends JFrame implements ChatClient.ChatEventListener {

    private final ChatClient  client;
    private final LoginFrame  loginFrame;

    private JPanel                  messagesBox;
    private JScrollPane             chatScroll;
    private UIUtils.ModernTextField txtInput;
    private UIUtils.ModernButton   btnSend;

    // Call and Media Engines
    private AudioCallEngine     audioEngine;
    private VideoCallEngine     videoEngine;
    private FileTransferEngine  fileTransferEngine;
    private NotificationManager notificationManager;

    private UIUtils.ModernButton btnVoiceCall;
    private UIUtils.ModernButton btnVideoCall;

    // Typing & Reply state
    private JLabel  lblTypingIndicator;
    private JPanel  replyBar;
    private JLabel  lblReplyPreview;
    private String  replyingToId = null;
    private Timer   typingStopTimer;
    private boolean isTypingNow = false;

    // Message holder struct & map
    private static class MessageBubbleHolder {
        String uuid;
        String sender;
        String content;
        boolean isMe;
        JLabel contentLabel;
        JPanel reactionsPanel;
        JPanel rootRow;
    }

    private final Map<String, MessageBubbleHolder> messageMap = new ConcurrentHashMap<>();
    private final Map<String, JProgressBar>        fileProgressBars = new ConcurrentHashMap<>();
    private final Map<String, JLabel>              fileStatusLabels = new ConcurrentHashMap<>();

    // Friends list
    private DefaultListModel<FriendItem> friendListModel;
    private JList<FriendItem>            friendList;
    private JButton                      btnRequestsBadge;
    private List<String>                 pendingRequests = new ArrayList<>();

    // User list (Online)
    private DefaultListModel<String> userListModel;
    private JList<String>            userList;
    private JLabel                   lblOnlineBadge;

    // Groups list
    private DefaultListModel<String> groupListModel;
    private JList<String>            groupList;
    private JLabel                   lblGroupCountBadge;
    private String currentRoomName = "General";

    // Sidebar navigation
    private CardLayout sidebarCardLayout;
    private JPanel     sidebarCardPanel;
    private JButton    btnTabFriends;
    private JButton    btnTabGroups;
    private JButton    btnTabMembers;

    private JLabel            lblServerInfo;
    private JComboBox<String> cbRecipient;
    private JPanel            recipientStatusPanel;
    private JLabel            lblCurrentRecipient;
    private JButton           btnCancelPrivate;
    private final String ALL_USERS = "Tất cả (Mọi người)";

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

    // Toast notification
    private JPanel toastPanel;
    private JLabel toastLabel;
    private Timer  toastHideTimer;
    private float  toastAlpha = 0f;
    private Timer  toastFadeTimer;

    // Search dialog reference
    private JDialog searchDialog;
    private DefaultListModel<SearchUserItem> searchResultModel;

    public ChatFrame(ChatClient client, LoginFrame loginFrame) {
        this.client     = client;
        this.loginFrame = loginFrame;
        this.client.setEventListener(this);
        initUI();
    }

    private void initUI() {
        setTitle("Messenger Desktop Pro  ·  " + client.getUsername());
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1100, 720);
        setMinimumSize(new Dimension(880, 580));
        setLocationRelativeTo(null);
        UIUtils.applyGlobalDefaults();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { performLogout(); }
        });

        // Khởi tạo các động cơ Media, Gọi thoại & Truyền file (Modules 4, 5, 8, 10)
        audioEngine = new AudioCallEngine(client);
        videoEngine = new VideoCallEngine(client);
        fileTransferEngine = new FileTransferEngine(client);
        notificationManager = NotificationManager.getInstance();

        fileTransferEngine.setProgressListener(new FileTransferEngine.FileProgressListener() {
            @Override
            public void onProgress(String fileId, String fileName, int percent, long bytesTransferred, long totalBytes, boolean isUpload) {
                SwingUtilities.invokeLater(() -> {
                    JProgressBar pb = fileProgressBars.get(fileId);
                    if (pb != null) pb.setValue(percent);
                });
            }

            @Override
            public void onTransferComplete(String fileId, String fileName, File savedFile, boolean isUpload) {
                SwingUtilities.invokeLater(() -> {
                    JProgressBar pb = fileProgressBars.get(fileId);
                    if (pb != null) {
                        pb.setValue(100);
                        pb.setForeground(UIUtils.COLOR_ONLINE);
                    }
                    JLabel lbl = fileStatusLabels.get(fileId);
                    if (lbl != null) {
                        lbl.setText("✓ " + (isUpload ? "Đã gửi thành công" : "Đã lưu vào downloads/"));
                        lbl.setForeground(UIUtils.COLOR_ONLINE);
                    }
                    showToast("✓ File " + fileName + " truyền thành công!", UIUtils.COLOR_ONLINE);
                });
            }

            @Override
            public void onTransferFailed(String fileId, String fileName, String errorReason) {
                SwingUtilities.invokeLater(() -> {
                    JLabel lbl = fileStatusLabels.get(fileId);
                    if (lbl != null) {
                        lbl.setText("✕ Thất bại: " + errorReason);
                        lbl.setForeground(UIUtils.COLOR_DANGER);
                    }
                    showToast("✕ Truyền file thất bại: " + errorReason, UIUtils.COLOR_DANGER);
                });
            }
        });

        JLayeredPane layeredRoot = new JLayeredPane();
        layeredRoot.setLayout(null);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(UIUtils.COLOR_BG_DARK);

        mainPanel.add(buildHeader(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.74);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setDividerSize(1);
        split.setBackground(UIUtils.COLOR_BORDER);

        split.setLeftComponent(buildChatArea());
        split.setRightComponent(buildSidebar());
        mainPanel.add(split, BorderLayout.CENTER);

        mainPanel.add(buildInputBar(), BorderLayout.SOUTH);

        layeredRoot.add(mainPanel, JLayeredPane.DEFAULT_LAYER);
        mainPanel.setBounds(0, 0, 1100, 720);

        layeredRoot.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                Dimension sz = layeredRoot.getSize();
                mainPanel.setBounds(0, 0, sz.width, sz.height);
                if (toastPanel != null) {
                    int tw = 360, th = 48;
                    toastPanel.setBounds((sz.width - tw) / 2, sz.height - th - 24, tw, th);
                }
            }
        });

        toastPanel = buildToastPanel();
        toastPanel.setBounds((1100 - 360) / 2, 720 - 48 - 24, 360, 48);
        toastPanel.setVisible(false);
        layeredRoot.add(toastPanel, JLayeredPane.POPUP_LAYER);

        setContentPane(layeredRoot);

        addSystemMessage("✓  Chào mừng @" + client.getUsername() + " tham gia Messenger Desktop Pro!");
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
                g2.setColor(UIUtils.COLOR_BORDER);
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);
                g2.dispose();
            }
        };
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(10, 20, 10, 20));

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

        lblServerInfo = new JLabel("● Online  ·  Nhóm: #" + currentRoomName + "  ·  " +
                client.getServerHost() + ":" + client.getServerPort());
        lblServerInfo.setFont(UIUtils.FONT_TINY);
        lblServerInfo.setForeground(UIUtils.COLOR_ONLINE);
        textCol.add(lblServerInfo);

        left.add(textCol);
        header.add(left, BorderLayout.WEST);

        // Right actions
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        UIUtils.RoundedPanel roomBadge = new UIUtils.RoundedPanel(UIUtils.COLOR_PRIMARY_LIGHT, UIUtils.COLOR_BORDER, 8);
        roomBadge.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JLabel lblRoom = new JLabel("# " + currentRoomName);
        lblRoom.setFont(UIUtils.FONT_BOLD);
        lblRoom.setForeground(UIUtils.COLOR_PRIMARY);
        roomBadge.add(lblRoom);
        right.add(roomBadge);

        btnVoiceCall = new UIUtils.ModernButton("📞 Thoại", new Color(30, 45, 60), UIUtils.COLOR_ONLINE, Color.WHITE);
        btnVoiceCall.setCornerRadius(8);
        btnVoiceCall.setPreferredSize(new Dimension(85, 32));
        btnVoiceCall.setEnabled(false);
        btnVoiceCall.addActionListener(e -> performStartVoiceCall());
        right.add(btnVoiceCall);

        btnVideoCall = new UIUtils.ModernButton("📹 Video", new Color(40, 30, 60), UIUtils.COLOR_ACCENT_VIOLET, Color.WHITE);
        btnVideoCall.setCornerRadius(8);
        btnVideoCall.setPreferredSize(new Dimension(85, 32));
        btnVideoCall.setEnabled(false);
        btnVideoCall.addActionListener(e -> performStartVideoCall());
        right.add(btnVideoCall);

        UIUtils.ModernButton btnLogout = new UIUtils.ModernButton(
            "Đăng xuất",
            new Color(60, 20, 20, 180),
            UIUtils.COLOR_DANGER,
            UIUtils.COLOR_ACCENT_ROSE
        );
        btnLogout.setCornerRadius(8);
        btnLogout.setPreferredSize(new Dimension(95, 32));
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
        sidebar.setPreferredSize(new Dimension(285, 0));

        // 3 Navigation Tabs: Bạn bè | Nhóm chat | Online
        JPanel tabBar = new JPanel(new GridLayout(1, 3, 4, 0));
        tabBar.setOpaque(false);
        tabBar.setBorder(new EmptyBorder(10, 10, 10, 10));

        btnTabFriends = createSidebarTab("👥 Bạn bè", true);
        btnTabGroups  = createSidebarTab("💬 Nhóm", false);
        btnTabMembers = createSidebarTab("🌐 Online", false);

        tabBar.add(btnTabFriends);
        tabBar.add(btnTabGroups);
        tabBar.add(btnTabMembers);
        sidebar.add(tabBar, BorderLayout.NORTH);

        // Card panels
        sidebarCardLayout = new CardLayout();
        sidebarCardPanel  = new JPanel(sidebarCardLayout);
        sidebarCardPanel.setOpaque(false);

        sidebarCardPanel.add(buildFriendsPanel(), "FRIENDS");
        sidebarCardPanel.add(buildGroupsPanel(),  "GROUPS");
        sidebarCardPanel.add(buildMembersPanel(), "MEMBERS");
        sidebar.add(sidebarCardPanel, BorderLayout.CENTER);

        btnTabFriends.addActionListener(e -> {
            activateSidebarTab(btnTabFriends, btnTabGroups, btnTabMembers);
            sidebarCardLayout.show(sidebarCardPanel, "FRIENDS");
        });
        btnTabGroups.addActionListener(e -> {
            activateSidebarTab(btnTabGroups, btnTabFriends, btnTabMembers);
            sidebarCardLayout.show(sidebarCardPanel, "GROUPS");
            client.requestRoomList();
        });
        btnTabMembers.addActionListener(e -> {
            activateSidebarTab(btnTabMembers, btnTabFriends, btnTabGroups);
            sidebarCardLayout.show(sidebarCardPanel, "MEMBERS");
        });

        return sidebar;
    }

    private JButton createSidebarTab(String text, boolean active) {
        JButton btn = new JButton(text);
        btn.setFont(UIUtils.FONT_TINY);
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
            btn.setBorder(new EmptyBorder(6, 6, 6, 6));
        } else {
            btn.setBackground(UIUtils.COLOR_SURFACE_2);
            btn.setForeground(UIUtils.COLOR_TEXT_MUTED);
            btn.setBorder(new EmptyBorder(6, 6, 6, 6));
        }
    }

    private void activateSidebarTab(JButton active, JButton inactive1, JButton inactive2) {
        applyTabStyle(active, true);
        applyTabStyle(inactive1, false);
        applyTabStyle(inactive2, false);
    }

    // ─── BẢNG BẠN BÈ (MODULE 2) ───────────────────────────────────────────────
    private JPanel buildFriendsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        // Top actions: [ + Thêm bạn ] [ 🔔 Lời mời (0) ]
        JPanel topActions = new JPanel(new GridLayout(1, 2, 6, 0));
        topActions.setOpaque(false);
        topActions.setBorder(new EmptyBorder(0, 10, 8, 10));

        JButton btnAddFriend = new JButton("+ Thêm bạn");
        btnAddFriend.setFont(UIUtils.FONT_TINY);
        btnAddFriend.setForeground(UIUtils.COLOR_PRIMARY);
        btnAddFriend.setBackground(UIUtils.COLOR_SURFACE_2);
        btnAddFriend.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        btnAddFriend.setFocusPainted(false);
        btnAddFriend.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnAddFriend.addActionListener(e -> showAddFriendDialog());
        topActions.add(btnAddFriend);

        btnRequestsBadge = new JButton("🔔 Lời mời (0)");
        btnRequestsBadge.setFont(UIUtils.FONT_TINY);
        btnRequestsBadge.setForeground(UIUtils.COLOR_WARNING);
        btnRequestsBadge.setBackground(UIUtils.COLOR_SURFACE_2);
        btnRequestsBadge.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        btnRequestsBadge.setFocusPainted(false);
        btnRequestsBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnRequestsBadge.addActionListener(e -> showPendingRequestsDialog());
        topActions.add(btnRequestsBadge);

        panel.add(topActions, BorderLayout.NORTH);

        friendListModel = new DefaultListModel<>();
        friendList = new JList<>(friendListModel);
        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendList.setCellRenderer(new FriendCellRenderer());
        friendList.setBackground(UIUtils.COLOR_BG_SIDEBAR);
        friendList.setBorder(new EmptyBorder(2, 6, 2, 6));
        friendList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                FriendItem sel = friendList.getSelectedValue();
                if (sel != null && e.getClickCount() >= 1) {
                    switchToPrivateChat(sel.username());
                }
            }
        });

        JScrollPane scroll = new JScrollPane(friendList);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        panel.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(6, 12, 10, 12));
        JLabel hint = new JLabel("<html><span style='color:#6466A4; font-size:10px;'>Nhấp vào bạn bè để nhắn tin riêng</span></html>");
        footer.add(hint);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildMembersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        JPanel subHeader = new JPanel(new BorderLayout());
        subHeader.setOpaque(false);
        subHeader.setBorder(new EmptyBorder(0, 14, 6, 14));
        lblOnlineBadge = new JLabel("● 1 trực tuyến");
        lblOnlineBadge.setFont(UIUtils.FONT_TINY);
        lblOnlineBadge.setForeground(UIUtils.COLOR_ONLINE);
        subHeader.add(lblOnlineBadge, BorderLayout.WEST);
        panel.add(subHeader, BorderLayout.NORTH);

        userListModel = new DefaultListModel<>();
        userListModel.addElement(client.getUsername());
        userList = new JList<>(userListModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new UserCellRenderer());
        userList.setBackground(UIUtils.COLOR_BG_SIDEBAR);
        userList.setBorder(new EmptyBorder(4, 6, 4, 6));
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

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(6, 12, 10, 12));
        JLabel hint = new JLabel("<html><span style='color:#6466A4; font-size:10px;'>Nhấp đúp người dùng để chat riêng</span></html>");
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
        topArea.setBorder(new EmptyBorder(0, 12, 6, 12));

        UIUtils.ModernButton btnCreate = new UIUtils.ModernButton(
            "+ Tạo nhóm mới",
            new Color(30, 35, 55),
            UIUtils.COLOR_SURFACE_3,
            UIUtils.COLOR_PRIMARY
        );
        btnCreate.setCornerRadius(8);
        btnCreate.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        btnCreate.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnCreate.addActionListener(e -> performCreateGroup());
        topArea.add(btnCreate);

        topArea.add(Box.createVerticalStrut(8));
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
        groupList.setBorder(new EmptyBorder(4, 6, 4, 6));
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

        return panel;
    }

    // ── Input bar ────────────────────────────────────────────────────────────────
    private JPanel buildInputBar() {
        JPanel bar = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(UIUtils.COLOR_SURFACE_1);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(UIUtils.COLOR_BORDER);
                g2.fillRect(0, 0, getWidth(), 1);
                g2.dispose();
            }
        };
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(8, 18, 12, 18));

        JPanel recipRow = new JPanel(new BorderLayout(8, 0));
        recipRow.setOpaque(false);
        recipRow.setBorder(new EmptyBorder(0, 0, 6, 0));

        recipientStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        recipientStatusPanel.setOpaque(false);

        lblCurrentRecipient = new JLabel("[Chung] Gửi tới: #" + currentRoomName);
        lblCurrentRecipient.setFont(UIUtils.FONT_BOLD);
        lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIMARY);
        recipientStatusPanel.add(lblCurrentRecipient);

        cbRecipient = new JComboBox<>();
        cbRecipient.addItem(ALL_USERS);
        cbRecipient.setFont(UIUtils.FONT_SMALL);
        cbRecipient.setPreferredSize(new Dimension(170, 26));
        cbRecipient.setBackground(UIUtils.COLOR_SURFACE_2);
        cbRecipient.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        cbRecipient.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        cbRecipient.addActionListener(e -> updateRecipientBadge());
        recipientStatusPanel.add(cbRecipient);

        btnCancelPrivate = new JButton("✕ Hủy");
        btnCancelPrivate.setFont(UIUtils.FONT_TINY);
        btnCancelPrivate.setForeground(UIUtils.COLOR_DANGER);
        btnCancelPrivate.setBackground(new Color(40, 15, 15, 180));
        btnCancelPrivate.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(80, 30, 30), 1),
            new EmptyBorder(2, 6, 2, 6)
        ));
        btnCancelPrivate.setFocusPainted(false);
        btnCancelPrivate.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnCancelPrivate.setVisible(false);
        btnCancelPrivate.addActionListener(e -> cbRecipient.setSelectedItem(ALL_USERS));
        recipientStatusPanel.add(btnCancelPrivate);

        recipRow.add(recipientStatusPanel, BorderLayout.WEST);

        // Emoji row
        JPanel emojiRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        emojiRow.setOpaque(false);
        String[] emojis = {"😀", "😂", "❤️", "👍", "🔥", "🎉"};
        for (String em : emojis) {
            JButton eb = new JButton(em);
            eb.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
            eb.setPreferredSize(new Dimension(32, 26));
            eb.setMargin(new Insets(0, 0, 0, 0));
            eb.setContentAreaFilled(false);
            eb.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
            eb.setCursor(new Cursor(Cursor.HAND_CURSOR));
            eb.setFocusPainted(false);
            eb.addActionListener(e -> {
                txtInput.setText(txtInput.getText() + em);
                txtInput.requestFocusInWindow();
            });
            emojiRow.add(eb);
        }
        recipRow.add(emojiRow, BorderLayout.EAST);
        bar.add(recipRow);

        // Thanh xem trước trả lời tin nhắn
        replyBar = new JPanel(new BorderLayout(8, 0));
        replyBar.setOpaque(false);
        replyBar.setBorder(new EmptyBorder(0, 4, 4, 4));
        lblReplyPreview = new JLabel("↩️ Đang trả lời...");
        lblReplyPreview.setFont(UIUtils.FONT_TINY);
        lblReplyPreview.setForeground(UIUtils.COLOR_TEXT_MUTED);
        replyBar.add(lblReplyPreview, BorderLayout.CENTER);
        JButton btnCancelReply = new JButton("✕");
        btnCancelReply.setContentAreaFilled(false);
        btnCancelReply.setBorderPainted(false);
        btnCancelReply.setForeground(UIUtils.COLOR_DANGER);
        btnCancelReply.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnCancelReply.addActionListener(e -> cancelReply());
        replyBar.add(btnCancelReply, BorderLayout.EAST);
        replyBar.setVisible(false);
        bar.add(replyBar);

        // Đèn báo đang nhập (Typing indicator)
        lblTypingIndicator = new JLabel("");
        lblTypingIndicator.setFont(UIUtils.FONT_TINY);
        lblTypingIndicator.setForeground(UIUtils.COLOR_ONLINE);
        lblTypingIndicator.setBorder(new EmptyBorder(0, 4, 2, 4));
        lblTypingIndicator.setVisible(false);
        bar.add(lblTypingIndicator);

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setOpaque(false);

        JButton btnAttach = new JButton("📎");
        btnAttach.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        btnAttach.setPreferredSize(new Dimension(42, 42));
        btnAttach.setBackground(UIUtils.COLOR_SURFACE_2);
        btnAttach.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        btnAttach.setFocusPainted(false);
        btnAttach.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        btnAttach.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnAttach.setToolTipText("Gửi file, ảnh, tài liệu...");
        btnAttach.addActionListener(e -> chooseAndSendFile());
        inputRow.add(btnAttach, BorderLayout.WEST);

        txtInput = new UIUtils.ModernTextField("Nhập tin nhắn... (Enter để gửi)");
        txtInput.setPreferredSize(new Dimension(0, 42));
        inputRow.add(txtInput, BorderLayout.CENTER);

        btnSend = new UIUtils.ModernButton("Gửi ➤", UIUtils.COLOR_PRIMARY, UIUtils.COLOR_PRIMARY_HOVER, Color.WHITE);
        btnSend.setCornerRadius(10);
        btnSend.setPreferredSize(new Dimension(95, 42));
        inputRow.add(btnSend, BorderLayout.EAST);

        bar.add(inputRow);

        typingStopTimer = new Timer(2000, e -> {
            isTypingNow = false;
            sendTypingIndicator(false);
        });
        typingStopTimer.setRepeats(false);

        txtInput.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onText(); }
            @Override public void removeUpdate(DocumentEvent e) { onText(); }
            @Override public void changedUpdate(DocumentEvent e) { onText(); }
            private void onText() {
                if (!isTypingNow && !txtInput.getText().trim().isEmpty()) {
                    isTypingNow = true;
                    sendTypingIndicator(true);
                }
                typingStopTimer.restart();
            }
        });

        ActionListener sendAction = e -> performSendMessage();
        btnSend.addActionListener(sendAction);
        txtInput.addActionListener(sendAction);

        return bar;
    }

    private void sendTypingIndicator(boolean typing) {
        String sel = (String) cbRecipient.getSelectedItem();
        String target = (sel == null || sel.equals(ALL_USERS)) ? "#" + currentRoomName : sel;
        client.sendTyping(target, typing);
    }

    private void setReplyTarget(String uuid, String sender, String content) {
        replyingToId = uuid;
        String preview = content.length() > 30 ? content.substring(0, 27) + "..." : content;
        lblReplyPreview.setText("↩️ Đang trả lời @" + sender + ": \"" + preview + "\"");
        replyBar.setVisible(true);
        txtInput.requestFocusInWindow();
    }

    private void cancelReply() {
        replyingToId = null;
        if (replyBar != null) replyBar.setVisible(false);
    }

    private void chooseAndSendFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn file hoặc hình ảnh để gửi");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            String sel = (String) cbRecipient.getSelectedItem();
            String target = (sel == null || sel.equals(ALL_USERS)) ? "#" + currentRoomName : sel;
            String fileId = fileTransferEngine.sendFile(f, target);
            if (fileId != null) {
                addFileMessage(client.getUsername(), f.getName(), f.length(), true, fileId);
            }
        }
    }

    private void performStartVoiceCall() {
        String sel = (String) cbRecipient.getSelectedItem();
        if (sel == null || sel.equals(ALL_USERS)) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một người bạn hoặc thành viên cụ thể để gọi thoại 1-1.", "Chọn người nhận", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        audioEngine.startCall(sel);
        showToast("Đang gọi thoại tới @" + sel + "...", UIUtils.COLOR_ONLINE);
    }

    private void performStartVideoCall() {
        String sel = (String) cbRecipient.getSelectedItem();
        if (sel == null || sel.equals(ALL_USERS)) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một người bạn hoặc thành viên cụ thể để gọi video 1-1.", "Chọn người nhận", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        VideoCallFrame vf = new VideoCallFrame(sel, audioEngine, videoEngine);
        vf.setVisible(true);
        audioEngine.startCall(sel);
        videoEngine.initVideoPort();
        showToast("Đang gọi video tới @" + sel + "...", UIUtils.COLOR_ACCENT_VIOLET);
    }

    // ── Dialog: Tìm kiếm & Kết bạn ──────────────────────────────────────────────
    private void showAddFriendDialog() {
        searchDialog = new JDialog(this, "Tìm kiếm người dùng & Kết bạn", true);
        searchDialog.setSize(440, 360);
        searchDialog.setLocationRelativeTo(this);
        searchDialog.getContentPane().setBackground(UIUtils.COLOR_BG_DARK);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(16, 16, 16, 16));

        // Top search bar
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        UIUtils.ModernTextField txtSearch = new UIUtils.ModernTextField("Nhập username để tìm kiếm...");
        JButton btnSearch = new JButton("Tìm");
        btnSearch.setBackground(UIUtils.COLOR_PRIMARY);
        btnSearch.setForeground(Color.WHITE);
        btnSearch.setFont(UIUtils.FONT_BOLD);
        btnSearch.setFocusPainted(false);
        btnSearch.setCursor(new Cursor(Cursor.HAND_CURSOR));

        top.add(txtSearch, BorderLayout.CENTER);
        top.add(btnSearch, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        searchResultModel = new DefaultListModel<>();
        JList<SearchUserItem> resList = new JList<>(searchResultModel);
        resList.setBackground(UIUtils.COLOR_SURFACE_1);
        resList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                SearchUserItem item = (SearchUserItem) value;
                JPanel p = new JPanel(new BorderLayout(8, 0));
                p.setOpaque(true);
                p.setBackground(isSelected ? UIUtils.COLOR_SURFACE_2 : UIUtils.COLOR_SURFACE_1);
                p.setBorder(new EmptyBorder(8, 10, 8, 10));

                p.add(UIUtils.createAvatarPanel(item.username(), 28), BorderLayout.WEST);

                JLabel l = new JLabel(item.displayName() + " (@" + item.username() + ")");
                l.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
                l.setFont(UIUtils.FONT_SMALL);
                p.add(l, BorderLayout.CENTER);

                JLabel tag = new JLabel("FRIEND".equals(item.relationship()) ? "✓ Bạn bè" : "+ Kết bạn");
                tag.setForeground("FRIEND".equals(item.relationship()) ? UIUtils.COLOR_ONLINE : UIUtils.COLOR_PRIMARY);
                tag.setFont(UIUtils.FONT_TINY);
                p.add(tag, BorderLayout.EAST);
                return p;
            }
        });

        JScrollPane scroll = new JScrollPane(resList);
        scroll.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        root.add(scroll, BorderLayout.CENTER);

        JButton btnAdd = new JButton("Gửi lời mời kết bạn");
        btnAdd.setBackground(UIUtils.COLOR_PRIMARY);
        btnAdd.setForeground(Color.WHITE);
        btnAdd.setFont(UIUtils.FONT_BOLD);
        btnAdd.setFocusPainted(false);
        btnAdd.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnAdd.addActionListener(e -> {
            SearchUserItem sel = resList.getSelectedValue();
            if (sel != null) {
                client.sendFriendRequest(sel.username());
                showToast("Đã gửi lời mời tới @" + sel.username(), UIUtils.COLOR_ONLINE);
                searchDialog.dispose();
            }
        });
        root.add(btnAdd, BorderLayout.SOUTH);

        ActionListener doSearch = e -> {
            String q = txtSearch.getText().trim();
            if (!q.isEmpty()) {
                client.searchUsers(q);
            }
        };
        btnSearch.addActionListener(doSearch);
        txtSearch.addActionListener(doSearch);

        searchDialog.setContentPane(root);
        searchDialog.setVisible(true);
    }

    // ── Dialog: Lời mời kết bạn đang chờ ──────────────────────────────────────
    private void showPendingRequestsDialog() {
        if (pendingRequests.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Không có lời mời kết bạn nào đang chờ.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JDialog dlg = new JDialog(this, "Lời mời kết bạn đang chờ", true);
        dlg.setSize(400, 320);
        dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(UIUtils.COLOR_BG_DARK);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(16, 16, 16, 16));

        DefaultListModel<String> reqModel = new DefaultListModel<>();
        for (String s : pendingRequests) reqModel.addElement(s);

        JList<String> list = new JList<>(reqModel);
        list.setBackground(UIUtils.COLOR_SURFACE_1);
        list.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object val, int i, boolean sel, boolean foc) {
                String u = (String) val;
                JPanel p = new JPanel(new BorderLayout(8, 0));
                p.setOpaque(true);
                p.setBackground(sel ? UIUtils.COLOR_SURFACE_2 : UIUtils.COLOR_SURFACE_1);
                p.setBorder(new EmptyBorder(8, 10, 8, 10));
                p.add(UIUtils.createAvatarPanel(u, 28), BorderLayout.WEST);
                JLabel lbl = new JLabel("@" + u + " muốn kết bạn với bạn");
                lbl.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
                p.add(lbl, BorderLayout.CENTER);
                return p;
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        root.add(scroll, BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new GridLayout(1, 2, 8, 0));
        btnRow.setOpaque(false);

        JButton btnAccept = new JButton("✓ Đồng ý");
        btnAccept.setBackground(UIUtils.COLOR_ONLINE);
        btnAccept.setForeground(Color.WHITE);
        btnAccept.setFont(UIUtils.FONT_BOLD);
        btnAccept.setFocusPainted(false);
        btnAccept.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnAccept.addActionListener(e -> {
            String sel = list.getSelectedValue();
            if (sel != null) {
                client.acceptFriendRequest(sel);
                pendingRequests.remove(sel);
                reqModel.removeElement(sel);
                updateRequestsBadge();
                if (reqModel.isEmpty()) dlg.dispose();
            }
        });
        btnRow.add(btnAccept);

        JButton btnReject = new JButton("✕ Từ chối");
        btnReject.setBackground(UIUtils.COLOR_SURFACE_3);
        btnReject.setForeground(UIUtils.COLOR_DANGER);
        btnReject.setFont(UIUtils.FONT_BOLD);
        btnReject.setFocusPainted(false);
        btnReject.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnReject.addActionListener(e -> {
            String sel = list.getSelectedValue();
            if (sel != null) {
                client.rejectFriendRequest(sel);
                pendingRequests.remove(sel);
                reqModel.removeElement(sel);
                updateRequestsBadge();
                if (reqModel.isEmpty()) dlg.dispose();
            }
        });
        btnRow.add(btnReject);

        root.add(btnRow, BorderLayout.SOUTH);
        dlg.setContentPane(root);
        dlg.setVisible(true);
    }

    private void updateRequestsBadge() {
        if (btnRequestsBadge != null) {
            int count = pendingRequests.size();
            btnRequestsBadge.setText("🔔 Lời mời (" + count + ")");
            btnRequestsBadge.setForeground(count > 0 ? UIUtils.COLOR_ONLINE : UIUtils.COLOR_WARNING);
        }
    }

    private void switchToPrivateChat(String targetUsername) {
        if (targetUsername.equalsIgnoreCase(client.getUsername())) return;
        boolean found = false;
        for (int i = 0; i < cbRecipient.getItemCount(); i++) {
            if (cbRecipient.getItemAt(i).equalsIgnoreCase(targetUsername)) {
                cbRecipient.setSelectedIndex(i);
                found = true;
                break;
            }
        }
        if (!found) {
            cbRecipient.addItem(targetUsername);
            cbRecipient.setSelectedItem(targetUsername);
        }
        txtInput.requestFocusInWindow();
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

            toastAlpha = 0f;
            toastFadeTimer = new Timer(16, null);
            toastFadeTimer.addActionListener(e -> {
                toastAlpha = Math.min(1f, toastAlpha + 0.08f);
                toastPanel.repaint();
                if (toastAlpha >= 1f) toastFadeTimer.stop();
            });
            toastFadeTimer.start();

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

    private void performCreateGroup() {
        String name = JOptionPane.showInputDialog(this,
            "Nhập tên nhóm chat mới:", "Tạo nhóm chat", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty()) return;
        client.sendCreateRoom(name);
        currentRoomName = name;
        updateRoomHeader();
        if (!groupListModel.contains(name)) groupListModel.addElement(name);
        groupList.setSelectedValue(name, true);
        showToast("Đã tạo nhóm: #" + name, UIUtils.COLOR_ONLINE);
    }

    private void performJoinSelectedGroup() {
        String selected = groupList.getSelectedValue();
        if (selected == null || selected.equalsIgnoreCase(currentRoomName)) return;
        client.sendJoinRoom(selected);
        currentRoomName = selected;
        updateRoomHeader();
        groupList.repaint();
        client.requestHistory("#" + currentRoomName, true);
        showToast("Đã vào nhóm: #" + selected, UIUtils.COLOR_PRIMARY);
    }

    private void updateRoomHeader() {
        if (lblServerInfo != null)
            lblServerInfo.setText("● Online  ·  Nhóm: #" + currentRoomName + "  ·  " +
                    client.getServerHost() + ":" + client.getServerPort());
        if (cbRecipient.getSelectedItem() == null || cbRecipient.getSelectedItem().equals(ALL_USERS))
            lblCurrentRecipient.setText("[Chung] Gửi tới: #" + currentRoomName);
    }

    private void updateRecipientBadge() {
        String sel = (String) cbRecipient.getSelectedItem();
        if (sel == null || sel.equals(ALL_USERS)) {
            lblCurrentRecipient.setText("[Chung] Gửi tới: #" + currentRoomName);
            lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIMARY);
            btnCancelPrivate.setVisible(false);
            if (btnVoiceCall != null) btnVoiceCall.setEnabled(false);
            if (btnVideoCall != null) btnVideoCall.setEnabled(false);
            client.requestHistory("#" + currentRoomName, true);
        } else {
            lblCurrentRecipient.setText("[Riêng] Đang chat với: @" + sel);
            lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIVATE_TEXT);
            btnCancelPrivate.setVisible(true);
            if (btnVoiceCall != null) btnVoiceCall.setEnabled(true);
            if (btnVideoCall != null) btnVideoCall.setEnabled(true);
            client.requestHistory(sel, false);
        }
    }

    private void selectUserForPrivateChat() {
        String sel = userList.getSelectedValue();
        if (sel == null) return;
        String target = sel.replace(" (Bạn)", "").trim();
        switchToPrivateChat(target);
    }

    private void performSendMessage() {
        String message = txtInput.getText().trim();
        if (message.isEmpty()) return;
        String recipient = (String) cbRecipient.getSelectedItem();
        String replyTo = replyingToId;
        cancelReply();

        if (recipient == null || recipient.equals(ALL_USERS)) {
            client.sendPublicMessage(message, replyTo);
        } else {
            client.sendPrivateMessage(recipient, message, replyTo);
            String fakeUuid = UUID.randomUUID().toString();
            addPrivateChatMessage(new ChatMessageItem(
                fakeUuid, client.getUsername(), recipient, message, replyTo, false, false, "", System.currentTimeMillis()
            ), true);
        }
        txtInput.setText("");
        txtInput.requestFocusInWindow();
        sendTypingIndicator(false);
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

    // ── Message rendering & Reactions ───────────────────────────────────────────
    public void addPublicMessage(String sender, String message) {
        addPublicChatMessage(new ChatMessageItem(UUID.randomUUID().toString(), sender, "#" + currentRoomName, message, null, false, false, "", System.currentTimeMillis()));
    }

    public void addPrivateMessage(String sender, String receiver, String message, boolean isOutgoing) {
        addPrivateChatMessage(new ChatMessageItem(UUID.randomUUID().toString(), sender, receiver, message, null, false, false, "", System.currentTimeMillis()), isOutgoing);
    }

    public void addPublicChatMessage(ChatMessageItem item) {
        String sender = item.sender();
        String message = item.content();
        String uuid = item.uuid() != null && !item.uuid().isEmpty() ? item.uuid() : UUID.randomUUID().toString();
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

        Color bgColor  = isMe ? UIUtils.COLOR_MY_BUBBLE : UIUtils.COLOR_OTHER_BUBBLE;
        Color bdrColor = isMe ? null : UIUtils.COLOR_BORDER;
        UIUtils.RoundedPanel bubble = new UIUtils.RoundedPanel(bgColor, bdrColor, 16);
        bubble.setLayout(new BorderLayout(0, 4));
        bubble.setBorder(new EmptyBorder(9, 14, 9, 14));
        bubble.setAlignmentX(isMe ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);

        if (item.replyToId() != null && !item.replyToId().isEmpty()) {
            JLabel rLbl = new JLabel("↩️ Đang trả lời");
            rLbl.setFont(new Font("Segoe UI", Font.ITALIC, 10));
            rLbl.setForeground(isMe ? new Color(220, 230, 255, 180) : UIUtils.COLOR_TEXT_MUTED);
            bubble.add(rLbl, BorderLayout.NORTH);
        }

        String displayContent = item.isRevoked()
            ? "<i style='color:#94A3B8;'>🚫 Tin nhắn đã bị thu hồi</i>"
            : escapeHtml(message) + (item.isEdited() ? " <i style='font-size:9px; color:#94A3B8;'>(đã sửa)</i>" : "");

        JLabel msgLbl = new JLabel("<html><body style='max-width:380px; word-wrap:break-word;'>" + displayContent + "</body></html>");
        msgLbl.setFont(UIUtils.FONT_REGULAR);
        msgLbl.setForeground(isMe ? Color.WHITE : UIUtils.COLOR_TEXT_PRIMARY);
        bubble.add(msgLbl, BorderLayout.CENTER);

        JLabel timeLbl = new JLabel(time, isMe ? SwingConstants.RIGHT : SwingConstants.LEFT);
        timeLbl.setFont(UIUtils.FONT_TINY);
        timeLbl.setForeground(isMe ? new Color(200, 210, 255, 160) : UIUtils.COLOR_TEXT_MUTED);
        bubble.add(timeLbl, BorderLayout.SOUTH);

        JPanel rxPanel = new JPanel(new FlowLayout(isMe ? FlowLayout.RIGHT : FlowLayout.LEFT, 4, 0));
        rxPanel.setOpaque(false);
        rxPanel.setVisible(false);

        container.add(bubble);
        container.add(rxPanel);
        row.add(container);

        MessageBubbleHolder holder = new MessageBubbleHolder();
        holder.uuid = uuid;
        holder.sender = sender;
        holder.content = message;
        holder.isMe = isMe;
        holder.contentLabel = msgLbl;
        holder.reactionsPanel = rxPanel;
        holder.rootRow = row;
        messageMap.put(uuid, holder);

        if (!item.isRevoked()) {
            attachMessageContextMenu(bubble, uuid, sender, message);
        }
        if (item.reactions() != null && !item.reactions().isEmpty()) {
            updateReactionsUI(holder, item.reactions());
        }

        appendBubbleRow(row);
    }

    public void addPrivateChatMessage(ChatMessageItem item, boolean isOutgoing) {
        String sender = item.sender();
        String receiver = item.recipient();
        String message = item.content();
        String uuid = item.uuid() != null && !item.uuid().isEmpty() ? item.uuid() : UUID.randomUUID().toString();
        String time = LocalTime.now().format(timeFormatter);

        JPanel row = new JPanel(new FlowLayout(isOutgoing ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 999));

        if (!isOutgoing) {
            row.add(UIUtils.createAvatarPanel(sender, 34));
        }

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setOpaque(false);

        UIUtils.RoundedPanel bubble = new UIUtils.RoundedPanel(UIUtils.COLOR_PRIVATE_BG, UIUtils.COLOR_PRIVATE_BORDER, 16);
        bubble.setLayout(new BorderLayout(0, 4));
        bubble.setBorder(new EmptyBorder(9, 14, 9, 14));

        String hdr = isOutgoing ? "🔒 chat riêng → @" + receiver : "🔒 @" + sender + " nhắn riêng";
        if (item.replyToId() != null && !item.replyToId().isEmpty()) {
            hdr += " · ↩️ Trả lời";
        }
        JLabel hdrLbl = new JLabel(hdr);
        hdrLbl.setFont(UIUtils.FONT_TINY);
        hdrLbl.setForeground(UIUtils.COLOR_PRIVATE_TEXT);
        bubble.add(hdrLbl, BorderLayout.NORTH);

        String displayContent = item.isRevoked()
            ? "<i style='color:#94A3B8;'>🚫 Tin nhắn đã bị thu hồi</i>"
            : escapeHtml(message) + (item.isEdited() ? " <i style='font-size:9px; color:#94A3B8;'>(đã sửa)</i>" : "");

        JLabel msgLbl = new JLabel("<html><body style='max-width:360px; word-wrap:break-word;'>" + displayContent + "</body></html>");
        msgLbl.setFont(UIUtils.FONT_REGULAR);
        msgLbl.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        bubble.add(msgLbl, BorderLayout.CENTER);

        JLabel timeLbl = new JLabel(time, SwingConstants.RIGHT);
        timeLbl.setFont(UIUtils.FONT_TINY);
        timeLbl.setForeground(UIUtils.COLOR_TEXT_MUTED);
        bubble.add(timeLbl, BorderLayout.SOUTH);

        JPanel rxPanel = new JPanel(new FlowLayout(isOutgoing ? FlowLayout.RIGHT : FlowLayout.LEFT, 4, 0));
        rxPanel.setOpaque(false);
        rxPanel.setVisible(false);

        container.add(bubble);
        container.add(rxPanel);
        row.add(container);

        MessageBubbleHolder holder = new MessageBubbleHolder();
        holder.uuid = uuid;
        holder.sender = sender;
        holder.content = message;
        holder.isMe = isOutgoing;
        holder.contentLabel = msgLbl;
        holder.reactionsPanel = rxPanel;
        holder.rootRow = row;
        messageMap.put(uuid, holder);

        if (!item.isRevoked()) {
            attachMessageContextMenu(bubble, uuid, sender, message);
        }
        if (item.reactions() != null && !item.reactions().isEmpty()) {
            updateReactionsUI(holder, item.reactions());
        }

        appendBubbleRow(row);
    }

    public void addFileMessage(String sender, String fileName, long fileSize, boolean isUpload, String fileId) {
        boolean isMe = sender.equalsIgnoreCase(client.getUsername());
        String time = LocalTime.now().format(timeFormatter);

        JPanel row = new JPanel(new FlowLayout(isMe ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 999));

        if (!isMe) {
            row.add(UIUtils.createAvatarPanel(sender, 34));
        }

        UIUtils.RoundedPanel bubble = new UIUtils.RoundedPanel(UIUtils.COLOR_SURFACE_2, UIUtils.COLOR_BORDER, 14);
        bubble.setLayout(new BorderLayout(8, 6));
        bubble.setBorder(new EmptyBorder(10, 14, 10, 14));
        bubble.setPreferredSize(new Dimension(300, 90));

        JPanel topInfo = new JPanel(new BorderLayout(8, 0));
        topInfo.setOpaque(false);

        JLabel iconLbl = new JLabel("📄");
        iconLbl.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 24));
        topInfo.add(iconLbl, BorderLayout.WEST);

        JPanel nameCol = new JPanel(new GridLayout(2, 1, 0, 2));
        nameCol.setOpaque(false);
        JLabel nameLbl = new JLabel(fileName.length() > 24 ? fileName.substring(0, 21) + "..." : fileName);
        nameLbl.setFont(UIUtils.FONT_BOLD);
        nameLbl.setForeground(Color.WHITE);
        nameCol.add(nameLbl);

        String sizeStr = String.format("%.1f KB", (double) fileSize / 1024);
        if (fileSize > 1024 * 1024) {
            sizeStr = String.format("%.1f MB", (double) fileSize / (1024 * 1024));
        }
        JLabel sizeLbl = new JLabel(sizeStr + (isUpload ? " · Đang tải lên" : " · Đang nhận"));
        sizeLbl.setFont(UIUtils.FONT_TINY);
        sizeLbl.setForeground(UIUtils.COLOR_TEXT_MUTED);
        nameCol.add(sizeLbl);
        topInfo.add(nameCol, BorderLayout.CENTER);

        bubble.add(topInfo, BorderLayout.NORTH);

        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setFont(new Font("Segoe UI", Font.BOLD, 10));
        progressBar.setForeground(UIUtils.COLOR_PRIMARY);
        progressBar.setBackground(UIUtils.COLOR_SURFACE_3);
        progressBar.setPreferredSize(new Dimension(270, 16));
        bubble.add(progressBar, BorderLayout.CENTER);

        JLabel statusLbl = new JLabel(isUpload ? "Đang gửi..." : "Đang tải về...", SwingConstants.RIGHT);
        statusLbl.setFont(UIUtils.FONT_TINY);
        statusLbl.setForeground(UIUtils.COLOR_TEXT_MUTED);
        bubble.add(statusLbl, BorderLayout.SOUTH);

        fileProgressBars.put(fileId, progressBar);
        fileStatusLabels.put(fileId, statusLbl);

        row.add(bubble);
        appendBubbleRow(row);
    }

    private void attachMessageContextMenu(JComponent comp, String uuid, String sender, String content) {
        comp.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) showMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showMenu(e); }
            private void showMenu(MouseEvent e) {
                JPopupMenu popup = new JPopupMenu();

                JMenu rxMenu = new JMenu("❤️ Thả cảm xúc");
                String[] rxList = {"❤️", "👍", "😂", "😮", "😢", "🔥"};
                for (String emoji : rxList) {
                    JMenuItem mi = new JMenuItem(emoji);
                    mi.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
                    mi.addActionListener(ev -> {
                        String recipient = (String) cbRecipient.getSelectedItem();
                        String target = (recipient == null || recipient.equals(ALL_USERS)) ? "#" + currentRoomName : recipient;
                        client.sendReaction(uuid, emoji, target);
                    });
                    rxMenu.add(mi);
                }
                popup.add(rxMenu);

                JMenuItem replyItem = new JMenuItem("↩️ Trả lời");
                replyItem.addActionListener(ev -> setReplyTarget(uuid, sender, content));
                popup.add(replyItem);

                if (sender.equalsIgnoreCase(client.getUsername())) {
                    popup.addSeparator();

                    JMenuItem editItem = new JMenuItem("✏️ Chỉnh sửa tin nhắn");
                    editItem.addActionListener(ev -> {
                        String newText = JOptionPane.showInputDialog(ChatFrame.this, "Nội dung chỉnh sửa:", content);
                        if (newText != null && !newText.trim().isEmpty()) {
                            String recipient = (String) cbRecipient.getSelectedItem();
                            String target = (recipient == null || recipient.equals(ALL_USERS)) ? "#" + currentRoomName : recipient;
                            client.sendEditMessage(uuid, newText.trim(), target);
                        }
                    });
                    popup.add(editItem);

                    JMenuItem revokeItem = new JMenuItem("🗑️ Thu hồi tin nhắn");
                    revokeItem.addActionListener(ev -> {
                        int c = JOptionPane.showConfirmDialog(ChatFrame.this, "Bạn có chắc muốn thu hồi tin nhắn này?", "Thu hồi", JOptionPane.YES_NO_OPTION);
                        if (c == JOptionPane.YES_OPTION) {
                            String recipient = (String) cbRecipient.getSelectedItem();
                            String target = (recipient == null || recipient.equals(ALL_USERS)) ? "#" + currentRoomName : recipient;
                            client.sendRevokeMessage(uuid, target);
                        }
                    });
                    popup.add(revokeItem);
                }

                popup.show(comp, e.getX(), e.getY());
            }
        });
    }

    private void updateReactionsUI(MessageBubbleHolder holder, String reactionsStr) {
        if (holder == null || holder.reactionsPanel == null) return;
        holder.reactionsPanel.removeAll();
        if (reactionsStr == null || reactionsStr.trim().isEmpty()) {
            holder.reactionsPanel.setVisible(false);
            holder.rootRow.revalidate();
            holder.rootRow.repaint();
            return;
        }

        String[] blocks = reactionsStr.split("\\|");
        for (String blk : blocks) {
            int colon = blk.indexOf(":");
            if (colon > 0) {
                String emoji = blk.substring(0, colon);
                String users = blk.substring(colon + 1);
                int count = users.isEmpty() ? 0 : users.split(",").length;
                if (count > 0) {
                    UIUtils.RoundedPanel tag = new UIUtils.RoundedPanel(UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_BORDER, 10);
                    tag.setBorder(new EmptyBorder(2, 6, 2, 6));
                    JLabel lbl = new JLabel(emoji + " " + count);
                    lbl.setFont(UIUtils.FONT_TINY);
                    lbl.setForeground(Color.WHITE);
                    tag.add(lbl);
                    holder.reactionsPanel.add(tag);
                }
            }
        }
        holder.reactionsPanel.setVisible(holder.reactionsPanel.getComponentCount() > 0);
        holder.rootRow.revalidate();
        holder.rootRow.repaint();
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

    // ─── Callbacks ──────────────────────────────────────────────────────────────
    @Override public void onMessageReceived(String sender, String message) {
        SwingUtilities.invokeLater(() -> addPublicMessage(sender, message));
    }
    @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {
        SwingUtilities.invokeLater(() -> addPrivateMessage(sender, receiver, message, false));
    }
    @Override public void onChatMessageReceived(ChatMessageItem item) {
        SwingUtilities.invokeLater(() -> {
            addPublicChatMessage(item);
            if (!item.sender().equalsIgnoreCase(client.getUsername())) {
                notificationManager.notifyNewMessage(item.sender(), item.content());
            }
        });
    }
    @Override public void onPrivateChatMessageReceived(ChatMessageItem item) {
        SwingUtilities.invokeLater(() -> {
            boolean isMe = item.sender().equalsIgnoreCase(client.getUsername());
            addPrivateChatMessage(item, isMe);
            if (!isMe) {
                notificationManager.notifyNewMessage(item.sender(), item.content());
            }
        });
    }
    @Override public void onTyping(String sender, boolean isTyping) {
        SwingUtilities.invokeLater(() -> {
            if (isTyping) {
                lblTypingIndicator.setText("✍️ @" + sender + " đang nhập tin nhắn...");
                lblTypingIndicator.setVisible(true);
            } else {
                lblTypingIndicator.setText("");
                lblTypingIndicator.setVisible(false);
            }
        });
    }
    @Override public void onReactionUpdated(String messageId, String emoji, String allReactions) {
        SwingUtilities.invokeLater(() -> {
            MessageBubbleHolder holder = messageMap.get(messageId);
            if (holder != null) {
                updateReactionsUI(holder, allReactions);
            }
        });
    }
    @Override public void onMessageEdited(String messageId, String newContent) {
        SwingUtilities.invokeLater(() -> {
            MessageBubbleHolder holder = messageMap.get(messageId);
            if (holder != null && holder.contentLabel != null) {
                holder.contentLabel.setText("<html><body style='max-width:380px; word-wrap:break-word;'>"
                    + escapeHtml(newContent) + " <i style='font-size:9px; color:#94A3B8;'>(đã sửa)</i></body></html>");
                messagesBox.revalidate();
                messagesBox.repaint();
            }
        });
    }
    @Override public void onMessageRevoked(String messageId) {
        SwingUtilities.invokeLater(() -> {
            MessageBubbleHolder holder = messageMap.get(messageId);
            if (holder != null && holder.contentLabel != null) {
                holder.contentLabel.setText("<html><body style='max-width:380px; word-wrap:break-word;'><i style='color:#94A3B8;'>🚫 Tin nhắn đã bị thu hồi</i></body></html>");
                if (holder.reactionsPanel != null) {
                    holder.reactionsPanel.removeAll();
                    holder.reactionsPanel.setVisible(false);
                }
                messagesBox.revalidate();
                messagesBox.repaint();
            }
        });
    }
    @Override public void onHistoryLoaded(String target, List<ChatMessageItem> messages) {
        SwingUtilities.invokeLater(() -> {
            messagesBox.removeAll();
            messageMap.clear();
            addSystemMessage("— Đã tải lịch sử cuộc trò chuyện (" + messages.size() + " tin nhắn) —");
            for (ChatMessageItem item : messages) {
                if ("PUBLIC".equalsIgnoreCase(item.recipient()) || (item.recipient() != null && item.recipient().startsWith("#"))) {
                    addPublicChatMessage(item);
                } else {
                    boolean isMe = item.sender().equalsIgnoreCase(client.getUsername());
                    addPrivateChatMessage(item, isMe);
                }
            }
            messagesBox.revalidate();
            messagesBox.repaint();
            SwingUtilities.invokeLater(() -> {
                JScrollBar v = chatScroll.getVerticalScrollBar();
                v.setValue(v.getMaximum());
            });
        });
    }
    @Override public void onCallPacketReceived(Packet packet) {
        if (packet == null) return;
        audioEngine.handleSignalingPacket(packet);

        if (packet.getType() == Packet.Type.CALL_OFFER) {
            String caller = packet.getSender();
            String callType = packet.getContent();
            int callerPort = 0;
            try { callerPort = Integer.parseInt(packet.getExtra()); } catch (Exception ignored) {}

            notificationManager.startRingtone();
            final int finalPort = callerPort;
            SwingUtilities.invokeLater(() -> {
                int choice = JOptionPane.showConfirmDialog(
                    this,
                    "@" + caller + " đang gọi " + ("VIDEO".equalsIgnoreCase(callType) ? "video" : "thoại") + " cho bạn.\nBạn có đồng ý nhận cuộc gọi?",
                    "Cuộc gọi đến",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
                notificationManager.stopRingtone();
                if (choice == JOptionPane.YES_OPTION) {
                    if ("VIDEO".equalsIgnoreCase(callType)) {
                        VideoCallFrame vf = new VideoCallFrame(caller, audioEngine, videoEngine);
                        vf.setVisible(true);
                        audioEngine.acceptCall(caller, finalPort, "127.0.0.1");
                        videoEngine.startVideoStreaming(caller, "127.0.0.1", finalPort);
                    } else {
                        audioEngine.acceptCall(caller, finalPort, "127.0.0.1");
                        showToast("Đã kết nối cuộc gọi thoại với @" + caller, UIUtils.COLOR_ONLINE);
                    }
                } else {
                    audioEngine.rejectCall(caller, "BUSY");
                }
            });
        }
    }
    @Override public void onFilePacketReceived(Packet packet) {
        if (packet == null) return;
        fileTransferEngine.handleIncomingPacket(packet);
        if (packet.getType() == Packet.Type.FILE_START) {
            String sender = packet.getSender();
            String fileName = packet.getContent();
            String extra = packet.getExtra();
            long fileSize = 0;
            if (extra != null && extra.contains(":")) {
                try { fileSize = Long.parseLong(extra.split(":")[0]); } catch (Exception ignored) {}
            }
            final long finalSize = fileSize;
            SwingUtilities.invokeLater(() -> {
                addFileMessage(sender, fileName, finalSize, false, packet.getId());
                notificationManager.notifyNewMessage(sender, "Gửi file: " + fileName);
            });
        }
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
    @Override public void onFriendListUpdated(List<FriendItem> friends) {
        SwingUtilities.invokeLater(() -> {
            friendListModel.clear();
            for (FriendItem f : friends) friendListModel.addElement(f);
            if (friendList != null) friendList.repaint();
        });
    }
    @Override public void onPendingRequestsUpdated(List<String> senders) {
        SwingUtilities.invokeLater(() -> {
            this.pendingRequests = senders;
            updateRequestsBadge();
            if (!senders.isEmpty()) {
                showToast("Bạn có " + senders.size() + " lời mời kết bạn mới!", UIUtils.COLOR_ONLINE);
            }
        });
    }
    @Override public void onUserSearchResult(List<SearchUserItem> users) {
        SwingUtilities.invokeLater(() -> {
            if (searchResultModel != null) {
                searchResultModel.clear();
                for (SearchUserItem u : users) searchResultModel.addElement(u);
            }
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

    // ─── Cell Renderers ─────────────────────────────────────────────────────────
    private class FriendCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            FriendItem friend = (FriendItem) value;
            JPanel item = new JPanel(new BorderLayout(8, 0));
            item.setBorder(new EmptyBorder(6, 8, 6, 8));
            item.setOpaque(true);
            item.setBackground(isSelected ? UIUtils.COLOR_PRIMARY_LIGHT : UIUtils.COLOR_BG_SIDEBAR);

            item.add(UIUtils.createAvatarPanel(friend.username(), 30), BorderLayout.WEST);

            JPanel info = new JPanel();
            info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
            info.setOpaque(false);

            JLabel nameLbl = new JLabel(friend.displayName());
            nameLbl.setFont(UIUtils.FONT_BOLD);
            nameLbl.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
            info.add(nameLbl);

            JLabel userLbl = new JLabel("@" + friend.username());
            userLbl.setFont(UIUtils.FONT_TINY);
            userLbl.setForeground(UIUtils.COLOR_TEXT_MUTED);
            info.add(userLbl);

            item.add(info, BorderLayout.CENTER);

            // Online/Offline dot
            JPanel dot = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (friend.isOnline()) {
                        g2.setColor(new Color(34, 197, 94, 50));
                        g2.fillOval(0, 0, 10, 10);
                        g2.setColor(UIUtils.COLOR_ONLINE);
                        g2.fillOval(2, 2, 6, 6);
                    } else {
                        g2.setColor(UIUtils.COLOR_TEXT_MUTED);
                        g2.fillOval(2, 2, 6, 6);
                    }
                    g2.dispose();
                }
            };
            dot.setOpaque(false);
            dot.setPreferredSize(new Dimension(10, 10));
            item.add(dot, BorderLayout.EAST);

            return item;
        }
    }

    private class UserCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String username = (String) value;
            boolean isSelf  = username.equalsIgnoreCase(client.getUsername());

            JPanel item = new JPanel(new BorderLayout(8, 0));
            item.setBorder(new EmptyBorder(6, 8, 6, 8));
            item.setOpaque(true);
            item.setBackground(isSelected ? UIUtils.COLOR_PRIMARY_LIGHT : UIUtils.COLOR_BG_SIDEBAR);

            item.add(UIUtils.createAvatarPanel(username, 28), BorderLayout.WEST);

            JLabel nameLbl = new JLabel(username + (isSelf ? "  (Bạn)" : ""));
            nameLbl.setFont(isSelf ? UIUtils.FONT_BOLD : UIUtils.FONT_REGULAR);
            nameLbl.setForeground(isSelf ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_TEXT_PRIMARY);
            item.add(nameLbl, BorderLayout.CENTER);

            JLabel statusLbl = new JLabel("●");
            statusLbl.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            statusLbl.setForeground(UIUtils.COLOR_ONLINE);
            item.add(statusLbl, BorderLayout.EAST);

            return item;
        }
    }

    private class GroupCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String roomName = (String) value;
            boolean isCurrent = roomName.equalsIgnoreCase(currentRoomName);

            JPanel item = new JPanel(new BorderLayout(8, 0));
            item.setBorder(new EmptyBorder(6, 8, 6, 8));
            item.setOpaque(true);
            if (isSelected)        item.setBackground(UIUtils.COLOR_PRIMARY_LIGHT);
            else if (isCurrent)    item.setBackground(new Color(25, 35, 50));
            else                   item.setBackground(UIUtils.COLOR_BG_SIDEBAR);

            JPanel icon = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color ic = isCurrent ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_ACCENT_VIOLET;
                    g2.setColor(new Color(ic.getRed(), ic.getGreen(), ic.getBlue(), 40));
                    g2.fillOval(0, 0, 26, 26);
                    g2.setColor(ic);
                    g2.fillOval(2, 2, 22, 22);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString("#", (26 - fm.stringWidth("#")) / 2, (26 - fm.getHeight()) / 2 + fm.getAscent());
                    g2.dispose();
                }
            };
            icon.setOpaque(false);
            icon.setPreferredSize(new Dimension(26, 26));
            item.add(icon, BorderLayout.WEST);

            JLabel nameLbl = new JLabel(roomName + (isCurrent ? " (Đang vào)" : ""));
            nameLbl.setFont(isCurrent ? UIUtils.FONT_BOLD : UIUtils.FONT_REGULAR);
            nameLbl.setForeground(isCurrent ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_TEXT_PRIMARY);
            item.add(nameLbl, BorderLayout.CENTER);

            return item;
        }
    }

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
