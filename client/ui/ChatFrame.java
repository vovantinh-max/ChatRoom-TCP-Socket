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
 * ChatFrame — Giao diện chính của TCP Chat.
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
    private VideoCallFrame videoCallWindow;
    private JDialog incomingCallWindow;
    private javax.swing.Timer callTimeout;
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
        boolean revoked;
        MessageDeliveryView delivery;
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

    private JLabel conversationTitle;
    private JLabel conversationDescription;
    private JPanel emptyConversation;
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
    private FriendsDialog friendsDialog;
    private boolean updatingRecipients;

    public ChatFrame(ChatClient client, LoginFrame loginFrame) {
        this.client     = client;
        this.loginFrame = loginFrame;
        initUI();
        this.client.setEventListener(this);
    }

    private void initUI() {
        setTitle("TCP Chat  ·  " + client.getUsername());
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1220, 820);
        setMinimumSize(new Dimension(960, 660));
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

        JLayeredPane layeredRoot = new JLayeredPane() {
            @Override public void doLayout() {
                for (Component child : getComponents()) {
                    if (child == toastPanel) {
                        int width = Math.min(420, getWidth() - 40);
                        child.setBounds((getWidth() - width) / 2, getHeight() - 150, width, 48);
                    } else child.setBounds(0, 0, getWidth(), getHeight());
                }
            }
        };
        layeredRoot.setLayout(null);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(UIUtils.COLOR_BG_DARK);

        JPanel conversation = new JPanel(new BorderLayout());
        conversation.setBackground(UIUtils.COLOR_BG_DARK);
        conversation.add(buildHeader(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.0);
        split.setDividerLocation(310);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setDividerSize(1);
        split.setBackground(UIUtils.COLOR_BORDER);

        conversation.add(buildChatArea(), BorderLayout.CENTER);
        conversation.add(buildInputBar(), BorderLayout.SOUTH);
        split.setLeftComponent(buildSidebar());
        split.setRightComponent(conversation);
        mainPanel.add(split, BorderLayout.CENTER);



        layeredRoot.add(mainPanel, JLayeredPane.DEFAULT_LAYER);
        mainPanel.setBounds(0, 0, 1100, 720);

        toastPanel = buildToastPanel();
        toastPanel.setBounds((1100 - 360) / 2, 720 - 48 - 24, 360, 48);
        toastPanel.setVisible(false);
        layeredRoot.add(toastPanel, JLayeredPane.POPUP_LAYER);

        setContentPane(layeredRoot);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK), "findPeople");
        getRootPane().getActionMap().put("findPeople", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showAddFriendDialog(); }
        });

        showEmptyConversation();
        txtInput.requestFocusInWindow();
    }

    // ── Header ──────────────────────────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(UIUtils.COLOR_BG_DARK);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtils.COLOR_BORDER),
                new EmptyBorder(22, 28, 22, 28)));
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        conversationTitle = new JLabel("# " + currentRoomName);
        conversationTitle.setFont(new Font("Segoe UI", Font.BOLD, 22));
        conversationTitle.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        titles.add(conversationTitle);
        titles.add(Box.createVerticalStrut(5));
        conversationDescription = new JLabel("Phòng trò chuyện · Cùng chia sẻ và kết nối");
        conversationDescription.setFont(UIUtils.FONT_SMALL);
        conversationDescription.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        titles.add(conversationDescription);
        lblServerInfo = new JLabel("● Đã kết nối");
        lblServerInfo.setToolTipText(client.getServerHost() + ":" + client.getServerPort());
        lblServerInfo.setFont(UIUtils.FONT_SMALL);
        lblServerInfo.setForeground(UIUtils.COLOR_ONLINE);
        header.add(titles, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        actions.setOpaque(false);
        btnVoiceCall = new UIUtils.ModernButton("", UIUtils.COLOR_SURFACE_1, UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_TEXT_PRIMARY);
        btnVoiceCall.setIcon(UIUtils.icon("phone", 21));
        btnVoiceCall.setPreferredSize(new Dimension(42, 38));
        btnVoiceCall.setToolTipText("Gọi thoại · Chọn một người để gọi");
        btnVoiceCall.getAccessibleContext().setAccessibleName("Gọi thoại");
        btnVoiceCall.setEnabled(false);
        btnVoiceCall.addActionListener(e -> performStartVoiceCall());
        btnVideoCall = new UIUtils.ModernButton("", UIUtils.COLOR_SURFACE_1, UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_TEXT_PRIMARY);
        btnVideoCall.setIcon(UIUtils.icon("video", 21));
        btnVideoCall.setPreferredSize(new Dimension(42, 38));
        btnVideoCall.setToolTipText("Gọi video · Chọn một người để gọi");
        btnVideoCall.getAccessibleContext().setAccessibleName("Gọi video");
        btnVideoCall.setEnabled(false);
        btnVideoCall.addActionListener(e -> performStartVideoCall());
        actions.add(btnVoiceCall);
        actions.add(btnVideoCall);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    // ── Chat area ────────────────────────────────────────────────────────────────
    private JScrollPane buildChatArea() {
        messagesBox = new UIUtils.WidthTrackingPanel() {
            @Override public boolean getScrollableTracksViewportHeight() { return false; }
        };
        messagesBox.setLayout(new BoxLayout(messagesBox, BoxLayout.Y_AXIS));
        messagesBox.setBackground(UIUtils.COLOR_BG_DARK);
        messagesBox.setBorder(new EmptyBorder(24, 28, 20, 28));

        chatScroll = new JScrollPane(messagesBox);
        chatScroll.setBorder(null);
        chatScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { reflowMessages(); }
        });
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
        sidebar.setPreferredSize(new Dimension(310, 0));

        sidebar.setMinimumSize(new Dimension(288, 0));
        JPanel sidebarTop = new JPanel();
        sidebarTop.setLayout(new BoxLayout(sidebarTop, BoxLayout.Y_AXIS));
        sidebarTop.setOpaque(false);
        sidebarTop.setBorder(new EmptyBorder(26, 18, 14, 18));
        JLabel brand = new JLabel("  TCP Chat");
        brand.setIcon(UIUtils.icon("chat", 25));
        brand.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        brand.setFont(new Font("Segoe UI", Font.BOLD, 23));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebarTop.add(brand);
        sidebarTop.add(Box.createVerticalStrut(25));
        UIUtils.ModernButton search = new UIUtils.ModernButton("Tìm người dùng", UIUtils.COLOR_SURFACE_2,
                UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_TEXT_SECONDARY);
        search.setIcon(UIUtils.icon("search", 17));
        search.setIconTextGap(12);
        search.setHorizontalAlignment(SwingConstants.LEFT);
        search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        search.setAlignmentX(Component.LEFT_ALIGNMENT);
        search.addActionListener(e -> showAddFriendDialog());
        sidebarTop.add(search);
        sidebarTop.add(Box.createVerticalStrut(24));
        JLabel section = new JLabel("CUỘC TRÒ CHUYỆN");
        section.setFont(UIUtils.FONT_SMALL);
        section.setForeground(UIUtils.COLOR_TEXT_MUTED);
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebarTop.add(section);
        sidebarTop.add(Box.createVerticalStrut(12));
        // 3 Navigation Tabs: Bạn bè | Nhóm chat | Online
        JPanel tabBar = new JPanel(new GridLayout(1, 3, 4, 0));
        tabBar.setOpaque(false);
        tabBar.setBorder(new EmptyBorder(0, 0, 0, 0));

        btnTabFriends = createSidebarTab("Bạn bè", false);
        btnTabGroups  = createSidebarTab("Nhóm", true);
        btnTabMembers = createSidebarTab("Online", false);

        tabBar.add(btnTabFriends);
        tabBar.add(btnTabGroups);
        tabBar.add(btnTabMembers);
        tabBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        tabBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        tabBar.setPreferredSize(new Dimension(270, 36));
        sidebarTop.add(tabBar);
        sidebar.add(sidebarTop, BorderLayout.NORTH);

        // Card panels
        sidebarCardLayout = new CardLayout();
        sidebarCardPanel  = new JPanel(sidebarCardLayout);
        sidebarCardPanel.setOpaque(false);

        sidebarCardPanel.add(buildFriendsPanel(), "FRIENDS");
        sidebarCardPanel.add(buildGroupsPanel(),  "GROUPS");
        sidebarCardPanel.add(buildMembersPanel(), "MEMBERS");
        sidebar.add(sidebarCardPanel, BorderLayout.CENTER);
        sidebarCardLayout.show(sidebarCardPanel, "GROUPS");
        JPanel account = new JPanel(new BorderLayout(10, 12));
        account.setOpaque(false);
        account.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIUtils.COLOR_BORDER),
                new EmptyBorder(18, 20, 20, 20)));
        account.add(UIUtils.createAvatarPanel(client.getUsername(), 38), BorderLayout.WEST);
        JPanel identity = new JPanel(new GridLayout(2, 1, 0, 3));
        identity.setOpaque(false);
        JLabel username = new JLabel(client.getUsername());
        username.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        username.setFont(UIUtils.FONT_BOLD);
        identity.add(username);
        identity.add(lblServerInfo);
        account.add(identity, BorderLayout.CENTER);
        UIUtils.ModernButton logout = new UIUtils.ModernButton("Đăng xuất", UIUtils.COLOR_SURFACE_2,
                UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_TEXT_SECONDARY);
        logout.setFont(UIUtils.FONT_SMALL);
        logout.addActionListener(e -> performLogout());
        account.add(logout, BorderLayout.SOUTH);
        sidebar.add(account, BorderLayout.SOUTH);

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
        JButton btn = new UIUtils.ModernButton(text, UIUtils.COLOR_SURFACE_2, UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_TEXT_SECONDARY);
        btn.setFont(UIUtils.FONT_SMALL);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorderPainted(false);
        applyTabStyle(btn, active);
        return btn;
    }

    private void applyTabStyle(JButton btn, boolean active) {
        if (btn instanceof UIUtils.ModernButton modern) {
            modern.setColors(active ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_SURFACE_2,
                    active ? UIUtils.COLOR_PRIMARY_HOVER : UIUtils.COLOR_SURFACE_3);
        }
        if (active) {
            btn.setBackground(UIUtils.COLOR_PRIMARY);
            btn.setForeground(UIUtils.COLOR_BG_DARKEST);
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
        topActions.setPreferredSize(new Dimension(285, 46));

        JButton btnAddFriend = new UIUtils.ModernButton("+ Thêm bạn", UIUtils.COLOR_SURFACE_2, UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_PRIMARY);
        btnAddFriend.setFont(UIUtils.FONT_TINY);
        btnAddFriend.setForeground(UIUtils.COLOR_PRIMARY);
        btnAddFriend.setBackground(UIUtils.COLOR_SURFACE_2);
        btnAddFriend.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        btnAddFriend.setFocusPainted(false);
        btnAddFriend.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnAddFriend.addActionListener(e -> showAddFriendDialog());
        topActions.add(btnAddFriend);

        btnRequestsBadge = new UIUtils.ModernButton("Lời mời (0)", UIUtils.COLOR_SURFACE_2, UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_TEXT_SECONDARY);
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
        friendList = new UIUtils.EmptyStateList<>(friendListModel, "Chưa có bạn bè", "Thêm bạn để bắt đầu trò chuyện.");
        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendList.setCellRenderer(new FriendCellRenderer());
        friendList.setBackground(UIUtils.COLOR_BG_SIDEBAR);
        friendList.setBorder(new EmptyBorder(2, 6, 2, 6));
        friendList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int index = friendList.locationToIndex(e.getPoint());
                if (index < 0 || !friendList.getCellBounds(index, index).contains(e.getPoint())) return;
                FriendItem sel = friendList.getSelectedValue();
                if (sel != null && SwingUtilities.isLeftMouseButton(e)) {
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
            UIUtils.COLOR_SURFACE_2,
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
        groupList.setSelectedIndex(0);
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
        bar.setBorder(new EmptyBorder(14, 24, 18, 24));

        JPanel recipRow = new JPanel(new BorderLayout(8, 0));
        recipRow.setOpaque(false);
        recipRow.setBorder(new EmptyBorder(0, 0, 6, 0));

        recipientStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        recipientStatusPanel.setOpaque(false);

        lblCurrentRecipient = new JLabel("Gửi tới #" + currentRoomName);
        lblCurrentRecipient.setFont(UIUtils.FONT_BOLD);
        lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIMARY);
        recipientStatusPanel.add(lblCurrentRecipient);

        cbRecipient = new JComboBox<>();
        cbRecipient.addItem(ALL_USERS);
        cbRecipient.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                return new javax.swing.plaf.basic.BasicArrowButton(SwingConstants.SOUTH,
                        UIUtils.COLOR_SURFACE_2, UIUtils.COLOR_SURFACE_2,
                        UIUtils.COLOR_TEXT_SECONDARY, UIUtils.COLOR_SURFACE_2);
            }
        });
        cbRecipient.setFont(UIUtils.FONT_SMALL);
        cbRecipient.setPreferredSize(new Dimension(170, 26));
        cbRecipient.setBackground(UIUtils.COLOR_SURFACE_2);
        cbRecipient.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        cbRecipient.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        cbRecipient.addActionListener(e -> { if (!updatingRecipients) updateRecipientBadge(); });
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
        JButton emojiPicker = new UIUtils.ModernButton("Biểu cảm", UIUtils.COLOR_SURFACE_2,
                UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_TEXT_SECONDARY);
        emojiPicker.setFont(UIUtils.FONT_SMALL);
        emojiPicker.setPreferredSize(new Dimension(96, 28));
        emojiPicker.setToolTipText("Chèn biểu cảm");
        JPopupMenu emojiMenu = new JPopupMenu();
        JPanel emojiGrid = new JPanel(new GridLayout(2, 3, 6, 6));
        emojiGrid.setBorder(new EmptyBorder(10, 10, 10, 10));
        emojiGrid.setBackground(UIUtils.COLOR_SURFACE_1);
        for (String emoji : new String[]{"😀", "😂", "❤️", "👍", "🔥", "🎉"}) {
            JButton choice = new JButton(emoji);
            choice.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 22));
            choice.setPreferredSize(new Dimension(46, 42));
            choice.setBackground(UIUtils.COLOR_SURFACE_2);
            choice.setFocusPainted(false);
            choice.addActionListener(e -> {
                txtInput.replaceSelection(emoji);
                emojiMenu.setVisible(false);
                txtInput.requestFocusInWindow();
            });
            emojiGrid.add(choice);
        }
        emojiMenu.add(emojiGrid);
        emojiPicker.addActionListener(e -> emojiMenu.show(emojiPicker, -70, -125));
        emojiRow.add(emojiPicker);
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

        JButton btnAttach = new UIUtils.ModernButton("", UIUtils.COLOR_SURFACE_2, UIUtils.COLOR_SURFACE_3, UIUtils.COLOR_TEXT_PRIMARY);
        btnAttach.setIcon(UIUtils.icon("attach", 22));
        btnAttach.getAccessibleContext().setAccessibleName("Đính kèm file");
        btnAttach.setPreferredSize(new Dimension(42, 42));
        btnAttach.setBackground(UIUtils.COLOR_SURFACE_2);
        btnAttach.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        btnAttach.setFocusPainted(false);
        btnAttach.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        btnAttach.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnAttach.setToolTipText("Gửi file, ảnh, tài liệu...");
        btnAttach.addActionListener(e -> chooseAndSendFile());
        inputRow.add(btnAttach, BorderLayout.WEST);

        txtInput = new UIUtils.ModernTextField("Nhắn tin tới #General…");
        txtInput.setPreferredSize(new Dimension(0, 42));
        inputRow.add(txtInput, BorderLayout.CENTER);

        btnSend = new UIUtils.ModernButton("Gửi", UIUtils.COLOR_PRIMARY, UIUtils.COLOR_PRIMARY_HOVER, Color.WHITE);
        btnSend.setIcon(UIUtils.icon("send", 18));
        btnSend.setIconTextGap(8);
        btnSend.setForeground(UIUtils.COLOR_BG_DARKEST);
        btnSend.setCornerRadius(10);
        btnSend.setPreferredSize(new Dimension(95, 42));
        btnSend.setEnabled(false);
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
                btnSend.setEnabled(!txtInput.getText().trim().isEmpty());
                if (!isTypingNow && !txtInput.getText().trim().isEmpty()) {
                    isTypingNow = true;
                    sendTypingIndicator(true);
                }
                typingStopTimer.restart();
            }
        });

        ActionListener sendAction = e -> performSendMessage();
        btnSend.addActionListener(sendAction);
        txtInput.setToolTipText("Enter để gửi · Chuột phải vào tin nhắn để trả lời, sửa hoặc thu hồi");
        txtInput.getAccessibleContext().setAccessibleName("Soạn tin nhắn");
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
        if (!audioEngine.startCall(sel)) {
            showToast("Không thể bắt đầu cuộc gọi. Hãy kết thúc cuộc gọi hiện tại trước.", UIUtils.COLOR_DANGER);
            return;
        }
        startCallTimeout();
        showToast("Đang gọi thoại tới @" + sel + "...", UIUtils.COLOR_ONLINE);
    }

    private void performStartVideoCall() {
        String sel = (String) cbRecipient.getSelectedItem();
        if (sel == null || sel.equals(ALL_USERS)) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một người bạn hoặc thành viên cụ thể để gọi video 1-1.", "Chọn người nhận", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (audioEngine.getState() != AudioCallEngine.CallState.IDLE) {
            showToast("Bạn đang có cuộc gọi khác.", UIUtils.COLOR_DANGER);
            return;
        }
        if (!videoEngine.initVideoPort() || !audioEngine.startCall(sel, videoEngine.getLocalVideoPort())) {
            videoEngine.stopVideo();
            showToast("Không thể bắt đầu gọi video.", UIUtils.COLOR_DANGER);
            return;
        }
        openVideoCall(sel, false);
        startCallTimeout();
    }

    private void openVideoCall(String peer, boolean connected) {
        videoEngine.setVideoMode(VideoCallEngine.VideoMode.CAMERA);
        videoCallWindow = new VideoCallFrame(peer, audioEngine, videoEngine);
        videoCallWindow.setConnected(connected);
        VideoCallFrame window = videoCallWindow;
        window.setAlwaysOnTop(connected);
        window.setVisible(true);
        window.toFront();
        window.requestFocus();
        if (connected) {
            javax.swing.Timer foreground = new javax.swing.Timer(500, e -> window.setAlwaysOnTop(false));
            foreground.setRepeats(false);
            foreground.start();
        }
    }

    private void startCallTimeout() {
        stopCallTimeout();
        callTimeout = new javax.swing.Timer(30_000, e -> {
            if (audioEngine.getState() == AudioCallEngine.CallState.CALLING) {
                endCallWindows();
                showToast("Không có phản hồi từ người nhận.", UIUtils.COLOR_DANGER);
            }
        });
        callTimeout.setRepeats(false);
        callTimeout.start();
    }

    private void stopCallTimeout() {
        if (callTimeout != null) callTimeout.stop();
    }

    private void closeIncomingCall() {
        notificationManager.stopRingtone();
        if (incomingCallWindow != null) {
            incomingCallWindow.dispose();
            incomingCallWindow = null;
        }
    }

    private void endCallWindows() {
        stopCallTimeout();
        closeIncomingCall();
        audioEngine.endCall();
        videoEngine.stopVideo();
        if (videoCallWindow != null) {
            videoCallWindow.dispose();
            videoCallWindow = null;
        }
    }

    // ── Dialog: Tìm kiếm & Kết bạn ──────────────────────────────────────────────
    private FriendsDialog friendsDialog() {
        if (friendsDialog == null || !friendsDialog.isDisplayable()) {
            friendsDialog = new FriendsDialog(this, client, this::switchToPrivateChat);
            friendsDialog.receivePending(pendingRequests);
        }
        return friendsDialog;
    }

    private void showAddFriendDialog() { friendsDialog().showPage(false); }

    private void showPendingRequestsDialog() { friendsDialog().showPage(true); }

    private void updateRequestsBadge() {
        if (btnRequestsBadge != null) {
            int count = pendingRequests.size();
            btnRequestsBadge.setText("Lời mời (" + count + ")");
            btnTabFriends.setText(count > 0 ? "Bạn bè (" + count + ")" : "Bạn bè");
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
    }

    private void performJoinSelectedGroup() {
        String selected = groupList.getSelectedValue();
        if (selected == null || selected.equalsIgnoreCase(currentRoomName)) return;
        client.sendJoinRoom(selected);
    }

    @Override public void onRoomChanged(String room) {
        SwingUtilities.invokeLater(() -> {
            currentRoomName = room;
            updatingRecipients = true;
            try { cbRecipient.setSelectedItem(ALL_USERS); }
            finally { updatingRecipients = false; }
            updateRoomHeader();
            updateRecipientBadge();
            groupList.setSelectedValue(room, true);
        });
    }

    private void updateRoomHeader() {
        updateConversationHeading();
        if (cbRecipient.getSelectedItem() == null || cbRecipient.getSelectedItem().equals(ALL_USERS))
            lblCurrentRecipient.setText("Gửi tới #" + currentRoomName);
        groupList.repaint();
    }

    private void updateConversationHeading() {
        String recipient = cbRecipient == null ? null : (String) cbRecipient.getSelectedItem();
        boolean isPublic = recipient == null || recipient.equals(ALL_USERS);
        conversationTitle.setText(isPublic ? "# " + currentRoomName : "@" + recipient);
        conversationDescription.setText(isPublic ? "Phòng trò chuyện · Cùng chia sẻ và kết nối" : "Cuộc trò chuyện riêng");
        if (txtInput != null) txtInput.setPlaceholder(isPublic ? "Nhắn tin tới #" + currentRoomName + "…" : "Nhắn tin cho @" + recipient + "…");
    }

    private void updateRecipientBadge() {
        updateConversationHeading();
        String sel = (String) cbRecipient.getSelectedItem();
        if (sel == null || sel.equals(ALL_USERS)) {
            lblCurrentRecipient.setText("Gửi tới #" + currentRoomName);
            lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIMARY);
            btnCancelPrivate.setVisible(false);
            if (btnVoiceCall != null) btnVoiceCall.setEnabled(false);
            if (btnVideoCall != null) btnVideoCall.setEnabled(false);
            client.requestHistory("#" + currentRoomName, true);
        } else {
            lblCurrentRecipient.setText("Nhắn riêng với @" + sel);
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
        if (message.length() > shared.protocol.ProtocolLimits.MAX_MESSAGE_CHARS) {
            showToast("Tin nhắn tối đa " + shared.protocol.ProtocolLimits.MAX_MESSAGE_CHARS + " ký tự.", UIUtils.COLOR_DANGER);
            return;
        }
        String recipient = (String) cbRecipient.getSelectedItem();
        if (recipient == null || recipient.equals(ALL_USERS)) {
            String command = message.toLowerCase(java.util.Locale.ROOT);
            boolean handled = true;
            if (command.equals("/rooms") || command.equals("rooms")) client.requestRoomList();
            else if (command.startsWith("/join ")) client.sendJoinRoom(message.substring(6).trim());
            else if (command.startsWith("/create ")) client.sendCreateRoom(message.substring(8).trim());
            else if (command.equals("/leave")) client.sendPacket(new Packet(Packet.Type.LEAVE_ROOM, client.getUsername(), ""));
            else handled = false;
            if (handled) { txtInput.setText(""); return; }
        }
        String replyTo = replyingToId;
        cancelReply();

        if (recipient == null || recipient.equals(ALL_USERS)) {
            String id = client.sendPublicMessage(message, replyTo, currentRoomName);
            if (id == null) { showToast("Có quá nhiều tin chưa gửi. Vui lòng thử lại sau.", UIUtils.COLOR_DANGER); return; }
            addPublicChatMessage(new ChatMessageItem(id, client.getUsername(), "#" + currentRoomName, message,
                    replyTo, false, false, "", System.currentTimeMillis()));
        } else {
            String messageId = client.sendPrivateMessage(recipient, message, replyTo);
            if (messageId == null) {
                showToast("Đã mất kết nối. Tin nhắn chưa được gửi.", UIUtils.COLOR_DANGER);
                return;
            }
            addPrivateChatMessage(new ChatMessageItem(
                messageId, client.getUsername(), recipient, message, replyTo, false, false, "", System.currentTimeMillis()
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
            endCallWindows();
            client.logout();
            if (friendsDialog != null) friendsDialog.dispose();
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
        if (messageMap.containsKey(uuid)) return;
        boolean isMe = sender.equalsIgnoreCase(client.getUsername());
        String time  = java.time.Instant.ofEpochMilli(item.timestamp()).atZone(java.time.ZoneId.systemDefault()).format(timeFormatter);

        JPanel row = new MessageRow(new FlowLayout(isMe ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
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
        bubble.setBorder(new EmptyBorder(12, 16, 10, 16));
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

        JLabel msgLbl = new JLabel(messageHtml(displayContent));
        msgLbl.setFont(UIUtils.FONT_REGULAR);
        msgLbl.setForeground(isMe ? Color.WHITE : UIUtils.COLOR_TEXT_PRIMARY);
        bubble.add(msgLbl, BorderLayout.CENTER);

        MessageDeliveryView delivery = new MessageDeliveryView(client, uuid, time, isMe);
        bubble.add(delivery, BorderLayout.SOUTH);

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
        holder.delivery = delivery;
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
        if (messageMap.containsKey(uuid)) return;
        String time = java.time.Instant.ofEpochMilli(item.timestamp()).atZone(java.time.ZoneId.systemDefault()).format(timeFormatter);

        JPanel row = new MessageRow(new FlowLayout(isOutgoing ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
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
        bubble.setBorder(new EmptyBorder(12, 16, 10, 16));

        String hdr = isOutgoing ? "Gửi tới @" + receiver : "@" + sender;
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

        JLabel msgLbl = new JLabel(messageHtml(displayContent));
        msgLbl.setFont(UIUtils.FONT_REGULAR);
        msgLbl.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        bubble.add(msgLbl, BorderLayout.CENTER);

        MessageDeliveryView delivery = new MessageDeliveryView(client, uuid, time, isOutgoing);
        bubble.add(delivery, BorderLayout.SOUTH);

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
        holder.delivery = delivery;
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

        JPanel row = new MessageRow(new FlowLayout(isMe ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
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
        MouseAdapter trigger = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) showMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showMenu(e); }
            private void showMenu(MouseEvent e) {
                MessageBubbleHolder holder = messageMap.get(uuid);
                if (holder != null && holder.revoked) return;
                String currentContent = holder == null ? content : holder.content;
                JPopupMenu popup = new JPopupMenu();

                JMenu rxMenu = new JMenu("Thả cảm xúc");
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

                JMenuItem replyItem = new JMenuItem("Trả lời");
                replyItem.addActionListener(ev -> setReplyTarget(uuid, sender, currentContent));
                popup.add(replyItem);

                if (sender.equalsIgnoreCase(client.getUsername())) {
                    popup.addSeparator();

                    JMenuItem editItem = new JMenuItem("Chỉnh sửa tin nhắn");
                    editItem.addActionListener(ev -> {
                        String newText = JOptionPane.showInputDialog(ChatFrame.this, "Nội dung chỉnh sửa:", currentContent);
                        if (newText != null && !newText.trim().isEmpty()) {
                            String recipient = (String) cbRecipient.getSelectedItem();
                            String target = (recipient == null || recipient.equals(ALL_USERS)) ? "#" + currentRoomName : recipient;
                            client.sendEditMessage(uuid, newText.trim(), target);
                        }
                    });
                    popup.add(editItem);

                    JMenuItem revokeItem = new JMenuItem("Thu hồi tin nhắn");
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

                popup.show(e.getComponent(), e.getX(), e.getY());
            }
        };
        installPopupTrigger(comp, trigger);
    }

    private void installPopupTrigger(Component component, MouseAdapter trigger) {
        component.addMouseListener(trigger);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) installPopupTrigger(child, trigger);
        }
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
        JPanel row = new MessageRow(new FlowLayout(FlowLayout.CENTER, 0, 6));
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
        if (emptyConversation != null) messagesBox.remove(emptyConversation);
        messagesBox.add(row);
        messagesBox.revalidate();
        messagesBox.repaint();
        SwingUtilities.invokeLater(() -> {
            JScrollBar v = chatScroll.getVerticalScrollBar();
            v.setValue(v.getMaximum());
        });
    }

    private static class MessageRow extends JPanel {
        MessageRow(FlowLayout layout) { super(layout); }
        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private void reflowMessages() {
        for (MessageBubbleHolder holder : messageMap.values()) {
            String content = holder.contentLabel.getText()
                    .replaceFirst("^<html><body[^>]*>", "")
                    .replaceFirst("</body></html>$", "");
            holder.contentLabel.setText(messageHtml(content));
        }
        messagesBox.revalidate();
        messagesBox.repaint();
    }

    private String messageHtml(String content) {
        int available = chatScroll.getViewport().getWidth();
        int limit = Math.min(330, Math.max(140, (available - 150) * 3 / 4));
        String plain = content.replaceAll("<[^>]*>", "");
        int textWidth = messagesBox.getFontMetrics(UIUtils.FONT_REGULAR).stringWidth(plain);
        int width = Math.min(limit, Math.max(75, textWidth * 3 / 4 + 5));
        return "<html><body style='width:" + width + "px'>" + content + "</body></html>";
    }

    private void showEmptyConversation() {
        emptyConversation = new JPanel();
        emptyConversation.setOpaque(false);
        emptyConversation.setLayout(new BoxLayout(emptyConversation, BoxLayout.Y_AXIS));
        emptyConversation.setBorder(new EmptyBorder(70, 20, 50, 20));
        JLabel icon = new JLabel(UIUtils.icon("chat", 48));
        icon.setForeground(UIUtils.COLOR_PRIMARY);
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyConversation.add(icon);
        emptyConversation.add(Box.createVerticalStrut(20));
        JLabel title = new JLabel("Một lời chào để bắt đầu.");
        title.setFont(UIUtils.FONT_DISPLAY);
        title.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyConversation.add(title);
        emptyConversation.add(Box.createVerticalStrut(10));
        JLabel hint = new JLabel("Chọn một người bạn hoặc gửi tin nhắn đầu tiên vào phòng.");
        hint.setFont(UIUtils.FONT_SMALL);
        hint.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyConversation.add(hint);
        messagesBox.add(emptyConversation);
    }

    private String escapeHtml(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ─── Callbacks ──────────────────────────────────────────────────────────────
    // ServerListener emits legacy and rich callbacks for API compatibility. Render only rich events.
    @Override public void onMessageReceived(String sender, String message) {}
    @Override public void onPrivateMessageReceived(String sender, String receiver, String message) {}
    @Override public void onMessageDeliveryChanged(String id, client.messaging.DeliveryTracker.State state, String detail) {
        SwingUtilities.invokeLater(() -> {
            MessageBubbleHolder holder = messageMap.get(id);
            if (holder != null && holder.delivery != null) {
                holder.delivery.update(state, detail);
                holder.rootRow.revalidate();
                messagesBox.revalidate();
            }
        });
    }

    @Override public void onChatMessageReceived(ChatMessageItem item) {
        SwingUtilities.invokeLater(() -> {
            String selected = (String) cbRecipient.getSelectedItem();
            if ((selected == null || selected.equals(ALL_USERS))
                    && (item.recipient() == null || item.recipient().equalsIgnoreCase("#" + currentRoomName))) addPublicChatMessage(item);
            if (!item.sender().equalsIgnoreCase(client.getUsername())) {
                notificationManager.notifyNewMessage(item.sender(), item.content());
            }
        });
    }
    @Override public void onPrivateChatMessageReceived(ChatMessageItem item) {
        SwingUtilities.invokeLater(() -> {
            boolean isMe = item.sender().equalsIgnoreCase(client.getUsername());
            String other = isMe ? item.recipient() : item.sender();
            String selected = (String) cbRecipient.getSelectedItem();
            if (other != null && other.equalsIgnoreCase(selected)) addPrivateChatMessage(item, isMe);
            else if (!isMe) showToast("Tin nhắn mới từ @" + other, UIUtils.COLOR_PRIMARY);
            if (!isMe) {
                notificationManager.notifyNewMessage(item.sender(), item.content());
            }
        });
    }
    @Override public void onTyping(String sender, boolean isTyping) {
        SwingUtilities.invokeLater(() -> {
            if (isTyping) {
                lblTypingIndicator.setText("@" + sender + " đang nhập tin nhắn…");
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
                holder.content = newContent;
                holder.contentLabel.setText(messageHtml(escapeHtml(newContent) + " <i style='font-size:9px; color:#94A3B8;'>(đã sửa)</i>"));
                messagesBox.revalidate();
                messagesBox.repaint();
            }
        });
    }
    @Override public void onMessageRevoked(String messageId) {
        SwingUtilities.invokeLater(() -> {
            MessageBubbleHolder holder = messageMap.get(messageId);
            if (holder != null && holder.contentLabel != null) {
                holder.revoked = true;
                holder.contentLabel.setText(messageHtml("<i style='color:#94A3B8;'>Tin nhắn đã bị thu hồi</i>"));
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
            String selected = (String) cbRecipient.getSelectedItem();
            String expected = selected == null || selected.equals(ALL_USERS) ? "#" + currentRoomName : selected;
            if (!expected.equalsIgnoreCase(target)) return;
            messagesBox.removeAll();
            messageMap.clear();
            if (messages.isEmpty()) showEmptyConversation();
            for (ChatMessageItem item : messages) {
                if (expected.startsWith("#")) {
                    addPublicChatMessage(item);
                } else {
                    boolean isMe = item.sender().equalsIgnoreCase(client.getUsername());
                    addPrivateChatMessage(item, isMe);
                }
            }
            for (Packet pending : client.getUnsentMessages()) {
                boolean isPublic = pending.getType() == Packet.Type.MESSAGE;
                if ((isPublic && expected.equalsIgnoreCase(pending.getRecipient()))
                        || (!isPublic && expected.equalsIgnoreCase(pending.getRecipient()))) {
                    ChatMessageItem draft = new ChatMessageItem(pending.getId(), client.getUsername(),
                            isPublic ? expected : pending.getRecipient(), pending.getContent(), pending.getExtra(), false, false, "", pending.getTimestamp());
                    if (isPublic) addPublicChatMessage(draft); else addPrivateChatMessage(draft, true);
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
        SwingUtilities.invokeLater(() -> {
            if (!isDisplayable() || !audioEngine.handleSignalingPacket(packet)) return;
            String peer = packet.getSender();
            switch (packet.getType()) {
                case CALL_OFFER -> showIncomingCall(peer, shared.protocol.CallEndpoint.parse(packet.getExtra()));
                case CALL_ANSWER -> {
                    stopCallTimeout();
                    var endpoint = shared.protocol.CallEndpoint.parse(packet.getExtra());
                    if (endpoint.videoPort() != 0 && videoCallWindow != null) {
                        videoCallWindow.setConnected(true);
                        if (!videoEngine.startVideoStreaming(peer, endpoint.host(), endpoint.videoPort())) {
                            endCallWindows();
                            showToast("Không thể kết nối video.", UIUtils.COLOR_DANGER);
                        }
                    } else {
                        showToast("Đã kết nối cuộc gọi với @" + peer, UIUtils.COLOR_ONLINE);
                    }
                }
                case CALL_REJECT, CALL_END -> {
                    endCallWindows();
                    showToast(packet.getType() == Packet.Type.CALL_END ? "Cuộc gọi đã kết thúc."
                            : "Người nhận không thể nhận cuộc gọi.", UIUtils.COLOR_TEXT_MUTED);
                }
                default -> {}
            }
        });
    }

    private void showIncomingCall(String peer, shared.protocol.CallEndpoint endpoint) {
        boolean video = endpoint.videoPort() != 0;
        JDialog dialog = new JDialog((Frame) null, "TCP Chat · Cuộc gọi đến", false);
        incomingCallWindow = dialog;
        dialog.setAlwaysOnTop(true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JButton accept = new JButton("Chấp nhận");
        JButton reject = new JButton("Từ chối");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(reject);
        actions.add(accept);
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20));
        panel.add(new JLabel("@" + peer + " đang gọi " + (video ? "video" : "thoại") + " cho bạn."), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        Runnable decline = () -> {
            if (incomingCallWindow != dialog) return;
            closeIncomingCall();
            audioEngine.rejectCall(peer, "DECLINED");
        };
        reject.addActionListener(e -> decline.run());
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { decline.run(); }
        });
        accept.addActionListener(e -> {
            if (incomingCallWindow != dialog) return;
            closeIncomingCall();
            if (video && !videoEngine.initVideoPort()) {
                audioEngine.rejectCall(peer, "MEDIA_UNAVAILABLE");
                showToast("Không thể mở cổng video.", UIUtils.COLOR_DANGER);
                return;
            }
            if (!audioEngine.acceptCall(peer, endpoint, video ? videoEngine.getLocalVideoPort() : 0)) {
                endCallWindows();
                showToast("Không thể nhận cuộc gọi.", UIUtils.COLOR_DANGER);
                return;
            }
            if (video) {
                openVideoCall(peer, true);
                if (!videoEngine.startVideoStreaming(peer, endpoint.host(), endpoint.videoPort())) {
                    endCallWindows();
                    showToast("Không thể kết nối video.", UIUtils.COLOR_DANGER);
                }
            } else {
                showToast("Đã kết nối cuộc gọi thoại với @" + peer, UIUtils.COLOR_ONLINE);
            }
        });
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(accept);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(360, 130));
        dialog.setSize(dialog.getMinimumSize().width, Math.max(dialog.getHeight(), 130));
        dialog.setLocationRelativeTo(isShowing() ? this : null);
        notificationManager.startRingtone();
        dialog.setVisible(true);
        dialog.toFront();
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
            userListModel.clear();
            for (String user : users) userListModel.addElement(user);
            lblOnlineBadge.setText("● " + users.size() + " trực tuyến");
            rebuildRecipients();
        });
    }

    private void rebuildRecipients() {
        String selected = (String) cbRecipient.getSelectedItem();
        java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < userListModel.size(); i++) names.add(userListModel.get(i));
        for (int i = 0; i < friendListModel.size(); i++) names.add(friendListModel.get(i).username());
        if (selected != null && !selected.equals(ALL_USERS)) names.add(selected);
        names.remove(client.getUsername());
        updatingRecipients = true;
        try {
            cbRecipient.removeAllItems();
            cbRecipient.addItem(ALL_USERS);
            for (String name : names) cbRecipient.addItem(name);
            cbRecipient.setSelectedItem(selected == null ? ALL_USERS : selected);
        } finally { updatingRecipients = false; }
        updateConversationHeading();
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
            if (friendsDialog != null && friendsDialog.isDisplayable()) friendsDialog.receiveFriends(friends);
            if (friendList != null) friendList.repaint();
            rebuildRecipients();
        });
    }
    @Override public void onPendingRequestsUpdated(List<String> senders) {
        SwingUtilities.invokeLater(() -> {
            int previousCount = this.pendingRequests.size();
            this.pendingRequests = new ArrayList<>(senders);
            if (friendsDialog != null && friendsDialog.isDisplayable()) friendsDialog.receivePending(senders);
            updateRequestsBadge();
            if (senders.size() > previousCount) {
                showToast("Bạn có " + senders.size() + " lời mời kết bạn mới!", UIUtils.COLOR_ONLINE);
            }
        });
    }
    @Override public void onUserSearchResult(String requestId, List<SearchUserItem> users) {
        SwingUtilities.invokeLater(() -> {
            if (friendsDialog != null && friendsDialog.isDisplayable()) friendsDialog.receiveSearch(requestId, users);
        });
    }

    @Override public void onFriendActionResult(String action, String target, boolean success, String message) {
        SwingUtilities.invokeLater(() -> {
            if (friendsDialog != null && friendsDialog.isDisplayable()) friendsDialog.receiveAction(action, target, success, message);
            showToast(message, success ? UIUtils.COLOR_ONLINE : UIUtils.COLOR_DANGER);
        });
    }

    @Override public void onSystemMessage(String message) {
        SwingUtilities.invokeLater(() -> addSystemMessage(message));
    }
    @Override public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            endCallWindows();
            JOptionPane.showMessageDialog(this,
                "Mất kết nối tới Server!\n" + (reason != null ? reason : ""),
                "Ngắt kết nối", JOptionPane.WARNING_MESSAGE);
            if (friendsDialog != null) friendsDialog.dispose();
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
            item.setBorder(new EmptyBorder(12, 14, 12, 14));
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
            item.setBorder(new EmptyBorder(12, 14, 12, 14));
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
            item.setBorder(new EmptyBorder(12, 14, 12, 14));
            item.setOpaque(true);
            if (isSelected)        item.setBackground(UIUtils.COLOR_PRIMARY_LIGHT);
            else if (isCurrent)    item.setBackground(UIUtils.COLOR_PRIMARY_LIGHT);
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

            JLabel nameLbl = new JLabel(roomName + (isCurrent ? "  ·  Đang mở" : ""));
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
