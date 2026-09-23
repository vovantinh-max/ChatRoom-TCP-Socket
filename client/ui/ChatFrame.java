package client.ui;

import client.ChatClient;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Giao diện chính phòng chat:
 * - Hiển thị tin nhắn chat (phân biệt tin chung, tin riêng, thông báo hệ thống)
 * - Hiển thị danh sách người dùng đang online
 * - Hỗ trợ chat chung và chọn người dùng để chat riêng
 * - Nút Đăng xuất và xử lý ngắt kết nối
 */
public class ChatFrame extends JFrame implements ChatClient.ChatEventListener {

    private final ChatClient client;
    private final LoginFrame loginFrame;

    private JTextPane chatPane;
    private StyledDocument doc;
    private JTextField txtInput;
    private JButton btnSend;
    private JButton btnLogout;

    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JLabel lblUserCount;

    private JComboBox<String> cbRecipient;
    private final String ALL_USERS = "Tất cả (Mọi người)";

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    public ChatFrame(ChatClient client, LoginFrame loginFrame) {
        this.client = client;
        this.loginFrame = loginFrame;

        // Đăng ký nhận sự kiện từ ChatClient
        this.client.setEventListener(this);

        initUI();
    }

    private void initUI() {
        setTitle("Phòng Chat TCP - [" + client.getUsername() + "]");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(800, 560);
        setMinimumSize(new Dimension(650, 450));
        setLocationRelativeTo(null);

        // Bắt sự kiện đóng cửa sổ bằng nút X
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                performLogout();
            }
        });

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 12, 10, 12));
        mainPanel.setBackground(new Color(245, 247, 250));

        // 1. THANH TRÊN CÙNG: Thông tin người dùng, IP/Port, nút Đăng xuất
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setOpaque(false);
        topPanel.setBorder(new EmptyBorder(0, 0, 5, 0));

        JLabel lblInfo = new JLabel(
            "<html>Người dùng: <b style='color:#1a73e8;'>" + client.getUsername() + "</b> " +
            "| Server: <i>" + client.getServerHost() + ":" + client.getServerPort() + "</i></html>"
        );
        lblInfo.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        topPanel.add(lblInfo, BorderLayout.WEST);

        btnLogout = new JButton("Đăng xuất");
        btnLogout.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnLogout.setBackground(new Color(220, 53, 69));
        btnLogout.setForeground(Color.WHITE);
        btnLogout.setFocusPainted(false);
        btnLogout.addActionListener(e -> performLogout());
        topPanel.add(btnLogout, BorderLayout.EAST);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 2. KHU VỰC TRUNG TÂM: Khung chat và Danh sách người dùng online
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.75);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(null);

        // 2.1 Khung hiển thị tin nhắn chat (Bên trái)
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        chatPane.setMargin(new Insets(8, 8, 8, 8));
        doc = chatPane.getStyledDocument();

        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setBorder(new TitledBorder(new LineBorder(new Color(200, 205, 215)), "Nội dung cuộc trò chuyện"));
        chatScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        splitPane.setLeftComponent(chatScroll);

        // 2.2 Danh sách user online (Bên phải)
        JPanel onlinePanel = new JPanel(new BorderLayout(5, 5));
        onlinePanel.setOpaque(false);
        onlinePanel.setBorder(new TitledBorder(new LineBorder(new Color(200, 205, 215)), "Đang online"));

        lblUserCount = new JLabel("Online: 1", SwingConstants.LEFT);
        lblUserCount.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        lblUserCount.setBorder(new EmptyBorder(0, 5, 3, 5));
        onlinePanel.add(lblUserCount, BorderLayout.NORTH);

        userListModel = new DefaultListModel<>();
        userListModel.addElement(client.getUsername() + " (Bạn)");

        userList = new JList<>(userListModel);
        userList.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setFixedCellHeight(26);

        // Nhấp đúp vào một user trong danh sách để chọn chat riêng
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
        onlinePanel.add(userScroll, BorderLayout.CENTER);

        JLabel lblHint = new JLabel("<html><small><i>* Nhấp đúp user<br>để chat riêng</i></small></html>");
        lblHint.setForeground(Color.GRAY);
        lblHint.setBorder(new EmptyBorder(5, 5, 2, 5));
        onlinePanel.add(lblHint, BorderLayout.SOUTH);

        splitPane.setRightComponent(onlinePanel);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        // 3. KHU VỰC DƯỚI CÙNG: Chọn đối tượng nhận tin nhắn và ô gửi tin
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        bottomPanel.setOpaque(false);
        bottomPanel.setBorder(new EmptyBorder(5, 0, 0, 0));

        // Thanh chọn người nhận: Chat chung hoặc Chat riêng
        JPanel recipientPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        recipientPanel.setOpaque(false);

        JLabel lblSendTo = new JLabel("Gửi tới:");
        lblSendTo.setFont(new Font("Segoe UI", Font.BOLD, 13));
        recipientPanel.add(lblSendTo);

        cbRecipient = new JComboBox<>();
        cbRecipient.addItem(ALL_USERS);
        cbRecipient.setPreferredSize(new Dimension(200, 28));
        cbRecipient.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        recipientPanel.add(cbRecipient);

        JButton btnResetRecipient = new JButton("Chat chung");
        btnResetRecipient.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btnResetRecipient.setFocusPainted(false);
        btnResetRecipient.addActionListener(e -> cbRecipient.setSelectedItem(ALL_USERS));
        recipientPanel.add(btnResetRecipient);

        bottomPanel.add(recipientPanel, BorderLayout.NORTH);

        // Hàng nhập tin nhắn và nút Gửi
        JPanel inputPanel = new JPanel(new BorderLayout(8, 0));
        inputPanel.setOpaque(false);

        txtInput = new JTextField();
        txtInput.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        txtInput.setPreferredSize(new Dimension(0, 36));
        inputPanel.add(txtInput, BorderLayout.CENTER);

        btnSend = new JButton("Gửi");
        btnSend.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnSend.setBackground(new Color(40, 120, 220));
        btnSend.setForeground(Color.WHITE);
        btnSend.setFocusPainted(false);
        btnSend.setPreferredSize(new Dimension(85, 36));
        inputPanel.add(btnSend, BorderLayout.EAST);

        bottomPanel.add(inputPanel, BorderLayout.CENTER);

        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
        setContentPane(mainPanel);

        // Sự kiện gửi tin nhắn
        ActionListener sendAction = e -> performSendMessage();
        btnSend.addActionListener(sendAction);
        txtInput.addActionListener(sendAction);

        // Đặt con trỏ vào ô nhập tin nhắn
        txtInput.requestFocusInWindow();

        appendStyledText("Hệ thống", "Đã kết nối thành công vào phòng chat!", Color.GRAY, true);
    }

    /**
     * Chọn user từ danh sách online để đổi đối tượng nhận sang chat riêng
     */
    private void selectUserForPrivateChat() {
        String selected = userList.getSelectedValue();
        if (selected == null) return;

        // Bỏ hậu tố (Bạn) nếu chọn chính mình
        String target = selected.replace(" (Bạn)", "").trim();
        if (target.equalsIgnoreCase(client.getUsername())) {
            JOptionPane.showMessageDialog(this, "Bạn không thể gửi tin nhắn riêng cho chính mình!", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Chọn trong combobox
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

    /**
     * Thực hiện gửi tin nhắn (chung hoặc riêng)
     */
    private void performSendMessage() {
        String message = txtInput.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        String recipient = (String) cbRecipient.getSelectedItem();

        if (recipient == null || recipient.equals(ALL_USERS)) {
            // Chat chung: gửi MESSAGE|<username>|<message>
            client.sendPublicMessage(message);
        } else {
            // Chat riêng: gửi PRIVATE|<username>|<recipient>|<message>
            client.sendPrivateMessage(recipient, message);
            // Hiển thị tin nhắn riêng vừa gửi lên màn hình của người gửi
            String time = LocalTime.now().format(timeFormatter);
            appendStyledText("[" + time + "] [Chat riêng tới " + recipient + "]: ", message, new Color(180, 80, 0), false);
        }

        txtInput.setText("");
        txtInput.requestFocusInWindow();
    }

    /**
     * Thực hiện đăng xuất và quay về màn hình đăng nhập
     */
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
    // Triển khai các phương thức ChatEventListener từ ChatClient
    // =========================================================================

    @Override
    public void onMessageReceived(String sender, String message) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(timeFormatter);
            boolean isSelf = sender.equalsIgnoreCase(client.getUsername());
            Color headerColor = isSelf ? new Color(20, 100, 200) : new Color(40, 140, 40);
            appendStyledText("[" + time + "] " + sender + ": ", message, headerColor, false);
        });
    }

    @Override
    public void onPrivateMessageReceived(String sender, String receiver, String message) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(timeFormatter);
            Color privateColor = new Color(170, 0, 140);
            appendStyledText("[" + time + "] [Chat riêng từ " + sender + "]: ", message, privateColor, false);
        });
    }

    @Override
    public void onUserListUpdated(List<String> users) {
        SwingUtilities.invokeLater(() -> {
            // Lưu lại người nhận hiện tại đang được chọn (nếu có)
            String currentSelected = (String) cbRecipient.getSelectedItem();

            userListModel.clear();
            cbRecipient.removeAllItems();
            cbRecipient.addItem(ALL_USERS);

            for (String u : users) {
                if (u.equalsIgnoreCase(client.getUsername())) {
                    userListModel.addElement(u + " (Bạn)");
                } else {
                    userListModel.addElement(u);
                    cbRecipient.addItem(u);
                }
            }

            lblUserCount.setText("Online: " + users.size());

            // Phục hồi lại lựa chọn người nhận nếu người đó vẫn còn online
            if (currentSelected != null && !currentSelected.equals(ALL_USERS)) {
                for (int i = 0; i < cbRecipient.getItemCount(); i++) {
                    if (cbRecipient.getItemAt(i).equalsIgnoreCase(currentSelected)) {
                        cbRecipient.setSelectedIndex(i);
                        break;
                    }
                }
            }
        });
    }

    @Override
    public void onSystemMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(timeFormatter);
            appendStyledText("[" + time + "] [Hệ thống]: ", message, Color.GRAY, true);
        });
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
     * Thêm dòng văn bản có định dạng màu sắc vào JTextPane
     */
    private void appendStyledText(String prefix, String message, Color color, boolean italic) {
        try {
            Style style = chatPane.addStyle("MsgStyle", null);
            StyleConstants.setForeground(style, color);
            StyleConstants.setBold(style, true);
            StyleConstants.setItalic(style, italic);
            doc.insertString(doc.getLength(), prefix, style);

            StyleConstants.setForeground(style, Color.BLACK);
            StyleConstants.setBold(style, false);
            StyleConstants.setItalic(style, false);
            doc.insertString(doc.getLength(), message + "\n", style);

            // Tự động cuộn xuống cuối cùng
            chatPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {
        }
    }
}
