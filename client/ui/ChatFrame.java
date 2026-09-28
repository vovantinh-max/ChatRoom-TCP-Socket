package client.ui;

import client.ChatClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ChatFrame extends JFrame implements ChatClient.ChatEventListener {

    private final ChatClient client;
    private final LoginFrame loginFrame;

    private JPanel messagesBox;
    private JScrollPane chatScroll;
    private UIUtils.ModernTextField txtInput;
    private UIUtils.ModernButton btnSend;

    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JLabel lblOnlineBadge;

    private DefaultListModel<String> groupListModel;
    private JList<String> groupList;
    private JLabel lblGroupCountBadge;
    private String currentRoomName = "General";

    private CardLayout sidebarCardLayout;
    private JPanel sidebarCardPanel;
    private JButton btnTabMembers;
    private JButton btnTabGroups;

    private JLabel lblServerInfo;
    private JComboBox<String> cbRecipient;
    private JPanel recipientStatusPanel;
    private JLabel lblCurrentRecipient;
    private JButton btnCancelPrivate;
    private final String ALL_USERS = "Tất cả (Mọi người)";

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

    public ChatFrame(ChatClient client, LoginFrame loginFrame) {
        this.client = client;
        this.loginFrame = loginFrame;

        this.client.setEventListener(this);
        initUI();
    }

    private void initUI() {
        setTitle("Phòng Chat TCP - [" + client.getUsername() + "]");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(980, 660);
        setMinimumSize(new Dimension(800, 520));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                performLogout();
            }
        });

        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBackground(UIUtils.COLOR_BACKGROUND);

        JPanel headerPanel = new JPanel(new BorderLayout(15, 0));
        headerPanel.setBackground(UIUtils.COLOR_SURFACE);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtils.COLOR_BORDER),
            new EmptyBorder(10, 20, 10, 20)
        ));

        JPanel userInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        userInfoPanel.setOpaque(false);

        JPanel myAvatar = createAvatarComponent(client.getUsername(), 38);
        userInfoPanel.add(myAvatar);

        JPanel userTextCol = new JPanel();
        userTextCol.setLayout(new BoxLayout(userTextCol, BoxLayout.Y_AXIS));
        userTextCol.setOpaque(false);

        JLabel lblName = new JLabel(client.getUsername());
        lblName.setFont(UIUtils.FONT_TITLE);
        lblName.setForeground(UIUtils.COLOR_TEXT_MAIN);
        userTextCol.add(lblName);

        lblServerInfo = new JLabel("● Đang trực tuyến  |  Nhóm: " + currentRoomName + "  |  Server: " + client.getServerHost() + ":" + client.getServerPort());
        lblServerInfo.setFont(UIUtils.FONT_TINY);
        lblServerInfo.setForeground(UIUtils.COLOR_ONLINE);
        userTextCol.add(lblServerInfo);

        userInfoPanel.add(userTextCol);
        headerPanel.add(userInfoPanel, BorderLayout.WEST);

        UIUtils.ModernButton btnLogout = new UIUtils.ModernButton(
            "Đăng xuất",
            new Color(254, 242, 242),
            UIUtils.COLOR_DANGER,
            UIUtils.COLOR_DANGER
        );
        btnLogout.setPreferredSize(new Dimension(100, 34));
        btnLogout.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                btnLogout.setForeground(Color.WHITE);
            }
            @Override
            public void mouseExited(MouseEvent e) {
                btnLogout.setForeground(UIUtils.COLOR_DANGER);
            }
        });
        btnLogout.addActionListener(e -> performLogout());
        headerPanel.add(btnLogout, BorderLayout.EAST);

        rootPanel.add(headerPanel, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.74);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(null);
        splitPane.setDividerSize(1);

        messagesBox = new JPanel();
        messagesBox.setLayout(new BoxLayout(messagesBox, BoxLayout.Y_AXIS));
        messagesBox.setBackground(UIUtils.COLOR_BACKGROUND);
        messagesBox.setBorder(new EmptyBorder(15, 18, 15, 18));

        chatScroll = new JScrollPane(messagesBox);
        chatScroll.setBorder(null);
        chatScroll.getVerticalScrollBar().setUnitIncrement(16);
        chatScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScroll.setBackground(UIUtils.COLOR_BACKGROUND);
        splitPane.setLeftComponent(chatScroll);

        JPanel sidebarPanel = new JPanel(new BorderLayout());
        sidebarPanel.setBackground(UIUtils.COLOR_SURFACE);
        sidebarPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, UIUtils.COLOR_BORDER));

        JPanel sidebarHeader = new JPanel(new GridLayout(1, 2, 8, 0));
        sidebarHeader.setOpaque(false);
        sidebarHeader.setBorder(new EmptyBorder(12, 14, 12, 14));

        btnTabMembers = new JButton("Thành viên");
        btnTabMembers.setFont(UIUtils.FONT_BOLD);
        btnTabMembers.setFocusPainted(false);
        btnTabMembers.setCursor(new Cursor(Cursor.HAND_CURSOR));

        btnTabGroups = new JButton("Nhóm chat");
        btnTabGroups.setFont(UIUtils.FONT_BOLD);
        btnTabGroups.setFocusPainted(false);
        btnTabGroups.setCursor(new Cursor(Cursor.HAND_CURSOR));

        styleTabButton(btnTabMembers, true);
        styleTabButton(btnTabGroups, false);

        sidebarHeader.add(btnTabMembers);
        sidebarHeader.add(btnTabGroups);
        sidebarPanel.add(sidebarHeader, BorderLayout.NORTH);

        sidebarCardLayout = new CardLayout();
        sidebarCardPanel = new JPanel(sidebarCardLayout);
        sidebarCardPanel.setOpaque(false);

        JPanel membersPanel = new JPanel(new BorderLayout());
        membersPanel.setOpaque(false);

        JPanel membersSubHeader = new JPanel(new BorderLayout());
        membersSubHeader.setOpaque(false);
        membersSubHeader.setBorder(new EmptyBorder(0, 16, 8, 16));

        lblOnlineBadge = new JLabel("1 trực tuyến");
        lblOnlineBadge.setFont(UIUtils.FONT_TINY);
        lblOnlineBadge.setForeground(UIUtils.COLOR_ONLINE);
        membersSubHeader.add(lblOnlineBadge, BorderLayout.WEST);
        membersPanel.add(membersSubHeader, BorderLayout.NORTH);

        userListModel = new DefaultListModel<>();
        userListModel.addElement(client.getUsername());

        userList = new JList<>(userListModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new UserCellRenderer());
        userList.setBackground(UIUtils.COLOR_SURFACE);
        userList.setBorder(new EmptyBorder(4, 8, 4, 8));

        userList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    selectUserForPrivateChat();
                }
            }
        });

        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(null);
        userScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        membersPanel.add(userScroll, BorderLayout.CENTER);

        JPanel membersFooter = new JPanel(new BorderLayout());
        membersFooter.setOpaque(false);
        membersFooter.setBorder(new EmptyBorder(10, 14, 12, 14));
        JLabel lblHint = new JLabel("<html><small style='color:#64748B;'>* <i>Nhấp đúp vào tên<br>để chuyển sang chat riêng</i></small></html>");
        membersFooter.add(lblHint, BorderLayout.CENTER);
        membersPanel.add(membersFooter, BorderLayout.SOUTH);

        JPanel groupsPanel = new JPanel(new BorderLayout());
        groupsPanel.setOpaque(false);

        JPanel groupsTop = new JPanel();
        groupsTop.setLayout(new BoxLayout(groupsTop, BoxLayout.Y_AXIS));
        groupsTop.setOpaque(false);
        groupsTop.setBorder(new EmptyBorder(0, 14, 8, 14));

        JButton btnCreateGroup = new JButton("+ Tạo nhóm chat");
        btnCreateGroup.setFont(UIUtils.FONT_BOLD);
        btnCreateGroup.setForeground(UIUtils.COLOR_PRIMARY);
        btnCreateGroup.setBackground(UIUtils.COLOR_PRIMARY_LIGHT);
        btnCreateGroup.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(191, 219, 254), 1),
            new EmptyBorder(8, 12, 8, 12)
        ));
        btnCreateGroup.setFocusPainted(false);
        btnCreateGroup.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnCreateGroup.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        btnCreateGroup.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnCreateGroup.addActionListener(e -> performCreateGroup());
        groupsTop.add(btnCreateGroup);

        groupsTop.add(Box.createVerticalStrut(10));

        JPanel groupsSubHeader = new JPanel(new BorderLayout());
        groupsSubHeader.setOpaque(false);
        lblGroupCountBadge = new JLabel("Các nhóm trò chuyện (3)");
        lblGroupCountBadge.setFont(UIUtils.FONT_TINY);
        lblGroupCountBadge.setForeground(UIUtils.COLOR_TEXT_MUTED);
        groupsSubHeader.add(lblGroupCountBadge, BorderLayout.WEST);
        groupsTop.add(groupsSubHeader);

        groupsPanel.add(groupsTop, BorderLayout.NORTH);

        groupListModel = new DefaultListModel<>();
        groupListModel.addElement("General");
        groupListModel.addElement("Java");
        groupListModel.addElement("Gaming");

        groupList = new JList<>(groupListModel);
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setCellRenderer(new GroupCellRenderer());
        groupList.setBackground(UIUtils.COLOR_SURFACE);
        groupList.setBorder(new EmptyBorder(4, 8, 4, 8));

        groupList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 || e.getClickCount() == 1) {
                    performJoinSelectedGroup();
                }
            }
        });

        JScrollPane groupScroll = new JScrollPane(groupList);
        groupScroll.setBorder(null);
        groupScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        groupsPanel.add(groupScroll, BorderLayout.CENTER);

        JPanel groupsFooter = new JPanel(new BorderLayout());
        groupsFooter.setOpaque(false);
        groupsFooter.setBorder(new EmptyBorder(10, 14, 12, 14));
        JLabel lblGroupHint = new JLabel("<html><small style='color:#64748B;'>* <i>Nhấp vào nhóm<br>để tham gia trò chuyện</i></small></html>");
        groupsFooter.add(lblGroupHint, BorderLayout.CENTER);
        groupsPanel.add(groupsFooter, BorderLayout.SOUTH);

        sidebarCardPanel.add(membersPanel, "MEMBERS");
        sidebarCardPanel.add(groupsPanel, "GROUPS");
        sidebarPanel.add(sidebarCardPanel, BorderLayout.CENTER);

        btnTabMembers.addActionListener(e -> {
            styleTabButton(btnTabMembers, true);
            styleTabButton(btnTabGroups, false);
            sidebarCardLayout.show(sidebarCardPanel, "MEMBERS");
        });

        btnTabGroups.addActionListener(e -> {
            styleTabButton(btnTabGroups, true);
            styleTabButton(btnTabMembers, false);
            sidebarCardLayout.show(sidebarCardPanel, "GROUPS");
            client.requestRoomList();
        });

        sidebarPanel.setPreferredSize(new Dimension(265, 0));
        splitPane.setRightComponent(sidebarPanel);

        rootPanel.add(splitPane, BorderLayout.CENTER);

        JPanel bottomArea = new JPanel();
        bottomArea.setLayout(new BoxLayout(bottomArea, BoxLayout.Y_AXIS));
        bottomArea.setBackground(UIUtils.COLOR_SURFACE);
        bottomArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UIUtils.COLOR_BORDER),
            new EmptyBorder(10, 18, 12, 18)
        ));

        recipientStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        recipientStatusPanel.setOpaque(false);
        recipientStatusPanel.setBorder(new EmptyBorder(0, 0, 8, 0));

        lblCurrentRecipient = new JLabel("[Chung] Gửi tới nhóm: " + currentRoomName);
        lblCurrentRecipient.setFont(UIUtils.FONT_BOLD);
        lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIMARY);
        recipientStatusPanel.add(lblCurrentRecipient);

        cbRecipient = new JComboBox<>();
        cbRecipient.addItem(ALL_USERS);
        cbRecipient.setFont(UIUtils.FONT_SMALL);
        cbRecipient.setPreferredSize(new Dimension(170, 26));
        cbRecipient.addActionListener(e -> updateRecipientBadge());
        recipientStatusPanel.add(cbRecipient);

        btnCancelPrivate = new JButton("x Hủy chat riêng");
        btnCancelPrivate.setFont(UIUtils.FONT_TINY);
        btnCancelPrivate.setForeground(UIUtils.COLOR_DANGER);
        btnCancelPrivate.setContentAreaFilled(false);
        btnCancelPrivate.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_DANGER, 1));
        btnCancelPrivate.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnCancelPrivate.setVisible(false);
        btnCancelPrivate.addActionListener(e -> {
            cbRecipient.setSelectedItem(ALL_USERS);
        });
        recipientStatusPanel.add(btnCancelPrivate);

        JPanel emojiPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        emojiPanel.setOpaque(false);
        String[] quickEmojis = {":)", ":D", "<3", "(y)", "^^", "O_o"};
        for (String emoji : quickEmojis) {
            JButton btnEmoji = new JButton(emoji);
            btnEmoji.setFont(UIUtils.FONT_SMALL);
            btnEmoji.setForeground(UIUtils.COLOR_TEXT_MUTED);
            btnEmoji.setPreferredSize(new Dimension(42, 26));
            btnEmoji.setMargin(new Insets(0, 0, 0, 0));
            btnEmoji.setContentAreaFilled(false);
            btnEmoji.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
            btnEmoji.setCursor(new Cursor(Cursor.HAND_CURSOR));
            btnEmoji.addActionListener(e -> {
                txtInput.setText(txtInput.getText() + (txtInput.getText().isEmpty() ? "" : " ") + emoji);
                txtInput.requestFocusInWindow();
            });
            emojiPanel.add(btnEmoji);
        }

        JPanel recipientRow = new JPanel(new BorderLayout());
        recipientRow.setOpaque(false);
        recipientRow.add(recipientStatusPanel, BorderLayout.WEST);
        recipientRow.add(emojiPanel, BorderLayout.EAST);
        bottomArea.add(recipientRow);

        JPanel inputRow = new JPanel(new BorderLayout(10, 0));
        inputRow.setOpaque(false);

        txtInput = new UIUtils.ModernTextField("Nhập tin nhắn của bạn... (Nhấn Enter để gửi)");
        txtInput.setPreferredSize(new Dimension(0, 42));
        inputRow.add(txtInput, BorderLayout.CENTER);

        btnSend = new UIUtils.ModernButton("Gửi >", UIUtils.COLOR_PRIMARY, UIUtils.COLOR_PRIMARY_HOVER, Color.WHITE);
        btnSend.setPreferredSize(new Dimension(95, 42));
        inputRow.add(btnSend, BorderLayout.EAST);

        bottomArea.add(inputRow);
        rootPanel.add(bottomArea, BorderLayout.SOUTH);

        setContentPane(rootPanel);

        ActionListener sendAction = e -> performSendMessage();
        btnSend.addActionListener(sendAction);
        txtInput.addActionListener(sendAction);

        addSystemMessage("Đã kết nối thành công vào phòng chat!");

        txtInput.requestFocusInWindow();
    }

    private void styleTabButton(JButton btn, boolean active) {
        if (active) {
            btn.setBackground(UIUtils.COLOR_PRIMARY);
            btn.setForeground(Color.WHITE);
            btn.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        } else {
            btn.setBackground(new Color(241, 245, 249));
            btn.setForeground(UIUtils.COLOR_TEXT_MUTED);
            btn.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER, 1));
        }
    }

    private void performCreateGroup() {
        String name = JOptionPane.showInputDialog(
            this,
            "Nhập tên nhóm chat mới:",
            "Tạo nhóm chat",
            JOptionPane.PLAIN_MESSAGE
        );
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty()) return;

        if (name.contains("|") || name.contains(",")) {
            JOptionPane.showMessageDialog(this, "Tên nhóm không được chứa ký tự '|' hoặc ','", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        client.sendCreateRoom(name);
        currentRoomName = name;
        updateRoomHeader();
        if (!groupListModel.contains(name)) {
            groupListModel.addElement(name);
        }
        groupList.setSelectedValue(name, true);
        addSystemMessage("Bạn đã tạo và vào nhóm: " + name);
    }

    private void performJoinSelectedGroup() {
        String selected = groupList.getSelectedValue();
        if (selected == null || selected.equalsIgnoreCase(currentRoomName)) return;

        client.sendJoinRoom(selected);
        currentRoomName = selected;
        updateRoomHeader();
        groupList.repaint();
        addSystemMessage("Bạn đã chuyển sang nhóm chat: " + selected);
    }

    private void updateRoomHeader() {
        if (lblServerInfo != null) {
            lblServerInfo.setText("● Đang trực tuyến  |  Nhóm: " + currentRoomName + "  |  Server: " + client.getServerHost() + ":" + client.getServerPort());
        }
        if (cbRecipient.getSelectedItem() == null || cbRecipient.getSelectedItem().equals(ALL_USERS)) {
            lblCurrentRecipient.setText("[Chung] Gửi tới nhóm: " + currentRoomName);
        }
    }

    private void updateRecipientBadge() {
        String selected = (String) cbRecipient.getSelectedItem();
        if (selected == null || selected.equals(ALL_USERS)) {
            lblCurrentRecipient.setText("[Chung] Gửi tới nhóm: " + currentRoomName);
            lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIMARY);
            btnCancelPrivate.setVisible(false);
        } else {
            lblCurrentRecipient.setText("[Riêng] Đang chat với: " + selected);
            lblCurrentRecipient.setForeground(UIUtils.COLOR_PRIVATE_TEXT);
            btnCancelPrivate.setVisible(true);
        }
    }

    private void selectUserForPrivateChat() {
        String selected = userList.getSelectedValue();
        if (selected == null) return;

        String target = selected.replace(" (Bạn)", "").trim();
        if (target.equalsIgnoreCase(client.getUsername())) {
            JOptionPane.showMessageDialog(this, "Bạn không thể gửi tin nhắn riêng cho chính mình!", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
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
        if (!found) {
            cbRecipient.addItem(target);
            cbRecipient.setSelectedItem(target);
        }
        txtInput.requestFocusInWindow();
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
        int confirm = JOptionPane.showConfirmDialog(
            this,
            "Bạn có chắc chắn muốn đăng xuất khỏi phòng chat?",
            "Xác nhận",
            JOptionPane.YES_NO_OPTION
        );
        if (confirm == JOptionPane.YES_OPTION) {
            client.logout();
            this.dispose();
            if (loginFrame != null) {
                loginFrame.setVisible(true);
            } else {
                System.exit(0);
            }
        }
    }

    public void addPublicMessage(String sender, String message) {
        boolean isMe = sender.equalsIgnoreCase(client.getUsername());
        String time = LocalTime.now().format(timeFormatter);

        JPanel row = new JPanel(new FlowLayout(isMe ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 4));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(1000, 120));

        if (!isMe) {
            row.add(createAvatarComponent(sender, 32));
            row.add(Box.createHorizontalStrut(8));
        }

        JPanel bubbleContainer = new JPanel();
        bubbleContainer.setLayout(new BoxLayout(bubbleContainer, BoxLayout.Y_AXIS));
        bubbleContainer.setOpaque(false);

        if (!isMe) {
            JLabel lblSender = new JLabel(sender);
            lblSender.setFont(UIUtils.FONT_TINY);
            lblSender.setForeground(UIUtils.COLOR_TEXT_MUTED);
            bubbleContainer.add(lblSender);
            bubbleContainer.add(Box.createVerticalStrut(3));
        }

        Color bgColor = isMe ? UIUtils.COLOR_MY_BUBBLE : UIUtils.COLOR_OTHER_BUBBLE;
        Color borderColor = isMe ? null : UIUtils.COLOR_BORDER;
        UIUtils.RoundedPanel bubble = new UIUtils.RoundedPanel(bgColor, borderColor, 14);
        bubble.setLayout(new BorderLayout(8, 2));
        bubble.setBorder(new EmptyBorder(8, 12, 8, 12));

        JLabel lblMsg = new JLabel("<html><body style='max-width:380px; word-wrap:break-word;'>" + escapeHtml(message) + "</body></html>");
        lblMsg.setFont(UIUtils.FONT_REGULAR);
        lblMsg.setForeground(isMe ? Color.WHITE : UIUtils.COLOR_TEXT_MAIN);
        bubble.add(lblMsg, BorderLayout.CENTER);

        JLabel lblTime = new JLabel(time, SwingConstants.RIGHT);
        lblTime.setFont(UIUtils.FONT_TINY);
        lblTime.setForeground(isMe ? new Color(220, 235, 255) : UIUtils.COLOR_TEXT_MUTED);
        bubble.add(lblTime, BorderLayout.SOUTH);

        bubbleContainer.add(bubble);
        row.add(bubbleContainer);

        appendBubbleRow(row);
    }

    public void addPrivateMessage(String sender, String receiver, String message, boolean isOutgoing) {
        String time = LocalTime.now().format(timeFormatter);

        JPanel row = new JPanel(new FlowLayout(isOutgoing ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 4));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(1000, 120));

        if (!isOutgoing) {
            row.add(createAvatarComponent(sender, 32));
            row.add(Box.createHorizontalStrut(8));
        }

        UIUtils.RoundedPanel bubble = new UIUtils.RoundedPanel(
            UIUtils.COLOR_PRIVATE_BG,
            UIUtils.COLOR_PRIVATE_BORDER,
            14
        );
        bubble.setLayout(new BorderLayout(8, 3));
        bubble.setBorder(new EmptyBorder(8, 12, 8, 12));

        String headerText = isOutgoing ? "[Chat riêng tới @" + receiver + "]" : "[Chat riêng từ @" + sender + "]";
        JLabel lblHeader = new JLabel(headerText);
        lblHeader.setFont(UIUtils.FONT_TINY);
        lblHeader.setForeground(UIUtils.COLOR_PRIVATE_TEXT);
        bubble.add(lblHeader, BorderLayout.NORTH);

        JLabel lblMsg = new JLabel("<html><body style='max-width:380px; word-wrap:break-word;'>" + escapeHtml(message) + "</body></html>");
        lblMsg.setFont(UIUtils.FONT_REGULAR);
        lblMsg.setForeground(UIUtils.COLOR_TEXT_MAIN);
        bubble.add(lblMsg, BorderLayout.CENTER);

        JLabel lblTime = new JLabel(time, SwingConstants.RIGHT);
        lblTime.setFont(UIUtils.FONT_TINY);
        lblTime.setForeground(UIUtils.COLOR_TEXT_MUTED);
        bubble.add(lblTime, BorderLayout.SOUTH);

        row.add(bubble);
        appendBubbleRow(row);
    }

    public void addSystemMessage(String text) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(1000, 40));

        UIUtils.RoundedPanel pill = new UIUtils.RoundedPanel(UIUtils.COLOR_SYSTEM_PILL, null, 12);
        pill.setBorder(new EmptyBorder(4, 14, 4, 14));

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

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JPanel createAvatarComponent(String name, int size) {
        Color color = UIUtils.getAvatarColor(name);
        String letter = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase() : "?";

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                g2.setColor(color);
                g2.fillOval(0, 0, size, size);

                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI", Font.BOLD, size / 2 + 1));
                FontMetrics fm = g2.getFontMetrics();
                int x = (size - fm.stringWidth(letter)) / 2;
                int y = (size - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(letter, x, y);

                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(size, size));
        panel.setMaximumSize(new Dimension(size, size));
        return panel;
    }

    @Override
    public void onMessageReceived(String sender, String message) {
        SwingUtilities.invokeLater(() -> addPublicMessage(sender, message));
    }

    @Override
    public void onPrivateMessageReceived(String sender, String receiver, String message) {
        SwingUtilities.invokeLater(() -> addPrivateMessage(sender, receiver, message, false));
    }

    @Override
    public void onUserListUpdated(List<String> users) {
        SwingUtilities.invokeLater(() -> {
            String currentSelected = (String) cbRecipient.getSelectedItem();

            userListModel.clear();
            cbRecipient.removeAllItems();
            cbRecipient.addItem(ALL_USERS);

            for (String u : users) {
                userListModel.addElement(u);
                if (!u.equalsIgnoreCase(client.getUsername())) {
                    cbRecipient.addItem(u);
                }
            }

            lblOnlineBadge.setText(users.size() + " trực tuyến");

            if (currentSelected != null && !currentSelected.equals(ALL_USERS)) {
                for (int i = 0; i < cbRecipient.getItemCount(); i++) {
                    if (cbRecipient.getItemAt(i).equalsIgnoreCase(currentSelected)) {
                        cbRecipient.setSelectedIndex(i);
                        break;
                    }
                }
            }
            updateRecipientBadge();
        });
    }

    @Override
    public void onRoomListUpdated(List<String> rooms) {
        SwingUtilities.invokeLater(() -> {
            groupListModel.clear();
            for (String r : rooms) {
                groupListModel.addElement(r);
            }
            if (lblGroupCountBadge != null) {
                lblGroupCountBadge.setText("Các nhóm trò chuyện (" + rooms.size() + ")");
            }
            if (groupList != null) {
                groupList.repaint();
            }
        });
    }

    @Override
    public void onSystemMessage(String message) {
        SwingUtilities.invokeLater(() -> addSystemMessage(message));
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                this,
                "Mất kết nối tới Server!\n" + (reason != null ? reason : ""),
                "Ngắt kết nối",
                JOptionPane.WARNING_MESSAGE
            );
            this.dispose();
            if (loginFrame != null) {
                loginFrame.setVisible(true);
            }
        });
    }

    private class UserCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String username = (String) value;
            boolean isSelf = username.equalsIgnoreCase(client.getUsername());

            JPanel itemPanel = new JPanel(new BorderLayout(8, 0));
            itemPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
            itemPanel.setOpaque(true);

            if (isSelected) {
                itemPanel.setBackground(UIUtils.COLOR_PRIMARY_LIGHT);
            } else {
                itemPanel.setBackground(UIUtils.COLOR_SURFACE);
            }

            itemPanel.add(createAvatarComponent(username, 28), BorderLayout.WEST);

            JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            namePanel.setOpaque(false);

            JLabel lblName = new JLabel(username);
            lblName.setFont(UIUtils.FONT_REGULAR);
            lblName.setForeground(UIUtils.COLOR_TEXT_MAIN);
            namePanel.add(lblName);

            if (isSelf) {
                JLabel lblSelf = new JLabel("(Bạn)");
                lblSelf.setFont(UIUtils.FONT_TINY);
                lblSelf.setForeground(UIUtils.COLOR_PRIMARY);
                namePanel.add(lblSelf);
            }

            itemPanel.add(namePanel, BorderLayout.CENTER);

            JLabel lblDot = new JLabel("●");
            lblDot.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            lblDot.setForeground(UIUtils.COLOR_ONLINE);
            itemPanel.add(lblDot, BorderLayout.EAST);

            return itemPanel;
        }
    }

    private class GroupCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String roomName = (String) value;
            boolean isCurrent = roomName.equalsIgnoreCase(currentRoomName);

            JPanel itemPanel = new JPanel(new BorderLayout(8, 0));
            itemPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
            itemPanel.setOpaque(true);

            if (isSelected) {
                itemPanel.setBackground(UIUtils.COLOR_PRIMARY_LIGHT);
            } else if (isCurrent) {
                itemPanel.setBackground(new Color(240, 253, 244));
            } else {
                itemPanel.setBackground(UIUtils.COLOR_SURFACE);
            }

            JPanel iconPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(isCurrent ? UIUtils.COLOR_ONLINE : new Color(139, 92, 246));
                    g2.fillOval(0, 0, 28, 28);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
                    FontMetrics fm = g2.getFontMetrics();
                    int x = (28 - fm.stringWidth("#")) / 2;
                    int y = (28 - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString("#", x, y);
                    g2.dispose();
                }
            };
            iconPanel.setOpaque(false);
            iconPanel.setPreferredSize(new Dimension(28, 28));
            itemPanel.add(iconPanel, BorderLayout.WEST);

            JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            namePanel.setOpaque(false);

            JLabel lblName = new JLabel(roomName);
            lblName.setFont(UIUtils.FONT_REGULAR);
            lblName.setForeground(UIUtils.COLOR_TEXT_MAIN);
            namePanel.add(lblName);

            if (isCurrent) {
                JLabel lblCurrent = new JLabel("(Đang tham gia)");
                lblCurrent.setFont(UIUtils.FONT_TINY);
                lblCurrent.setForeground(UIUtils.COLOR_ONLINE);
                namePanel.add(lblCurrent);
            }

            itemPanel.add(namePanel, BorderLayout.CENTER);

            return itemPanel;
        }
    }
}
