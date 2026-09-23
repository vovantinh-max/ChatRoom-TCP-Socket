package client.ui;

import client.ChatClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Giao diện phòng chat cao cấp:
 * - Hiển thị tin nhắn dạng bong bóng (Chat Bubble) tương tự Telegram/Messenger
 * - Phân biệt tin nhắn của mình (xanh dương, căn phải), tin nhắn người khác (trắng, căn trái),
 *   tin nhắn riêng (tím nổi bật), thông báo hệ thống (pill ở giữa)
 * - Thanh thành viên online hiển thị avatar tròn và trạng thái trực tuyến
 * - Thao tác chuyển đổi chat chung / chat riêng nhanh chóng
 */
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
        setSize(960, 660);
        setMinimumSize(new Dimension(750, 500));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                performLogout();
            }
        });

        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBackground(UIUtils.COLOR_BACKGROUND);

        // 1. THANH ĐIỀU HƯỚNG TRÊN CÙNG (HEADER)
        JPanel headerPanel = new JPanel(new BorderLayout(15, 0));
        headerPanel.setBackground(UIUtils.COLOR_SURFACE);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtils.COLOR_BORDER),
            new EmptyBorder(10, 20, 10, 20)
        ));

        // Thông tin người dùng hiện tại và trạng thái
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

        JLabel lblServerInfo = new JLabel("● Đang trực tuyến  |  Server: " + client.getServerHost() + ":" + client.getServerPort());
        lblServerInfo.setFont(UIUtils.FONT_TINY);
        lblServerInfo.setForeground(UIUtils.COLOR_ONLINE);
        userTextCol.add(lblServerInfo);

        userInfoPanel.add(userTextCol);
        headerPanel.add(userInfoPanel, BorderLayout.WEST);

        // Nút Đăng xuất
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

        // 2. KHU VỰC CHÍNH (NỘI DUNG CHAT + DANH SÁCH USER)
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.76);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(null);
        splitPane.setDividerSize(1);

        // 2.1 Khung hiển thị tin nhắn (Bong bóng chat)
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

        // 2.2 Cột danh sách người dùng online
        JPanel sidebarPanel = new JPanel(new BorderLayout());
        sidebarPanel.setBackground(UIUtils.COLOR_SURFACE);
        sidebarPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, UIUtils.COLOR_BORDER));

        // Tiêu đề sidebar
        JPanel sidebarHeader = new JPanel(new BorderLayout());
        sidebarHeader.setOpaque(false);
        sidebarHeader.setBorder(new EmptyBorder(14, 16, 12, 16));

        JLabel lblSidebarTitle = new JLabel("Thành viên");
        lblSidebarTitle.setFont(UIUtils.FONT_HEADER);
        lblSidebarTitle.setForeground(UIUtils.COLOR_TEXT_MAIN);
        sidebarHeader.add(lblSidebarTitle, BorderLayout.WEST);

        lblOnlineBadge = new JLabel("1 online", SwingConstants.CENTER);
        lblOnlineBadge.setFont(UIUtils.FONT_TINY);
        lblOnlineBadge.setForeground(UIUtils.COLOR_ONLINE);
        sidebarHeader.add(lblOnlineBadge, BorderLayout.EAST);

        sidebarPanel.add(sidebarHeader, BorderLayout.NORTH);

        // Danh sách user với CellRenderer đẹp mắt
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
        sidebarPanel.add(userScroll, BorderLayout.CENTER);

        // Ghi chú dưới chân sidebar
        JPanel sidebarFooter = new JPanel(new BorderLayout());
        sidebarFooter.setOpaque(false);
        sidebarFooter.setBorder(new EmptyBorder(10, 14, 12, 14));
        JLabel lblHint = new JLabel("<html><small style='color:#64748B;'>* <i>Nhấp đúp vào tên<br>để chuyển sang chat riêng</i></small></html>");
        sidebarFooter.add(lblHint, BorderLayout.CENTER);
        sidebarPanel.add(sidebarFooter, BorderLayout.SOUTH);

        sidebarPanel.setPreferredSize(new Dimension(230, 0));
        splitPane.setRightComponent(sidebarPanel);

        rootPanel.add(splitPane, BorderLayout.CENTER);

        // 3. KHU VỰC NHẬP LIỆU DƯỚI CÙNG (INPUT BAR)
        JPanel bottomArea = new JPanel();
        bottomArea.setLayout(new BoxLayout(bottomArea, BoxLayout.Y_AXIS));
        bottomArea.setBackground(UIUtils.COLOR_SURFACE);
        bottomArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UIUtils.COLOR_BORDER),
            new EmptyBorder(10, 18, 12, 18)
        ));

        // 3.1 Thanh trạng thái người nhận (Chat chung / Chat riêng)
        recipientStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        recipientStatusPanel.setOpaque(false);
        recipientStatusPanel.setBorder(new EmptyBorder(0, 0, 8, 0));

        lblCurrentRecipient = new JLabel("[Chung] Gửi tới: Tất cả phòng chat");
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

        // Thanh phím tắt cảm xúc nhanh
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

        // 3.2 Hàng nhập tin nhắn và nút Gửi
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

        // Lắng nghe sự kiện gửi
        ActionListener sendAction = e -> performSendMessage();
        btnSend.addActionListener(sendAction);
        txtInput.addActionListener(sendAction);

        addSystemMessage("Đã kết nối thành công vào phòng chat!");

        txtInput.requestFocusInWindow();
    }

    private void updateRecipientBadge() {
        String selected = (String) cbRecipient.getSelectedItem();
        if (selected == null || selected.equals(ALL_USERS)) {
            lblCurrentRecipient.setText("[Chung] Gửi tới: Tất cả phòng chat");
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

    // =========================================================================
    // Các phương thức tạo bong bóng chat (Chat Bubbles)
    // =========================================================================

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

    // =========================================================================
    // Callbacks từ ChatClient
    // =========================================================================

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

            lblOnlineBadge.setText(users.size() + " online");

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

    /**
     * Renderer danh sách người dùng với Avatar tròn và điểm chỉ báo online
     */
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

            // Avatar
            itemPanel.add(createAvatarComponent(username, 28), BorderLayout.WEST);

            // Tên và huy hiệu (Bạn)
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

            // Chấm xanh online
            JLabel lblDot = new JLabel("●");
            lblDot.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            lblDot.setForeground(UIUtils.COLOR_ONLINE);
            itemPanel.add(lblDot, BorderLayout.EAST);

            return itemPanel;
        }
    }
}
