package client.ui;

import client.ChatClient;
import client.ChatClient.SearchUserItem;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/** Server-confirmed contact actions; the UI never invents a successful friendship. */
public final class FriendsDialog extends JDialog {
    private final ChatClient client;
    private final Consumer<String> openChat;
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final DefaultListModel<SearchUserItem> results = new DefaultListModel<>();
    private final DefaultListModel<String> requests = new DefaultListModel<>();
    private final JList<SearchUserItem> resultList = new JList<>(results);
    private final JList<String> requestList = new JList<>(requests);
    private final UIUtils.ModernTextField search = new UIUtils.ModernTextField("Tên tài khoản hoặc tên hiển thị");
    private final JButton searchButton = UIUtils.primaryButton("Tìm kiếm");
    private final JButton contactAction = UIUtils.primaryButton("Kết bạn");
    private final JButton accept = UIUtils.primaryButton("Chấp nhận");
    private final JButton reject = UIUtils.secondaryButton("Từ chối");
    private final JButton searchTab = UIUtils.secondaryButton("Tìm bạn");
    private final JButton requestTab = UIUtils.secondaryButton("Lời mời (0)");
    private final JLabel status = new JLabel("Tìm một người bạn để bắt đầu kết nối.");
    private final Timer timeout;
    private String requestId;
    private String operation;
    private String operationTarget;

    public FriendsDialog(JFrame owner, ChatClient client, Consumer<String> openChat) {
        super(owner, "Bạn bè · Messenger", false);
        this.client = client;
        this.openChat = openChat;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(620, 520);
        setMinimumSize(new Dimension(540, 440));
        setLocationRelativeTo(owner);
        timeout = new Timer(10000, e -> {
            requestId = null;
            operation = null;
            operationTarget = null;
            searchButton.setEnabled(true);
            updateActions();
            feedback("Chưa nhận được phản hồi. Kiểm tra kết nối và thử tìm lại.", false);
        });
        timeout.setRepeats(false);
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(UIUtils.COLOR_BG_DARK);
        root.setBorder(new EmptyBorder(24, 24, 20, 24));
        JPanel heading = new JPanel(new BorderLayout(0, 18));
        heading.setOpaque(false);
        JLabel title = new JLabel("Kết nối với mọi người");
        title.setFont(UIUtils.FONT_TITLE);
        title.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        heading.add(title, BorderLayout.NORTH);
        JPanel tabs = new JPanel(new GridLayout(1, 2, 8, 0));
        tabs.setOpaque(false);
        tabs.add(searchTab);
        tabs.add(requestTab);
        heading.add(tabs, BorderLayout.SOUTH);
        searchTab.addActionListener(e -> showPage(false));
        requestTab.addActionListener(e -> showPage(true));
        root.add(heading, BorderLayout.NORTH);
        pages.setOpaque(false);
        pages.add(buildSearch(), "SEARCH");
        pages.add(buildRequests(), "REQUESTS");
        root.add(pages, BorderLayout.CENTER);
        status.setFont(UIUtils.FONT_SMALL);
        status.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        status.setPreferredSize(new Dimension(500, 42));
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        updateActions();
    }

    private JPanel buildSearch() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        top.add(search, BorderLayout.CENTER);
        top.add(searchButton, BorderLayout.EAST);
        search.setPreferredSize(new Dimension(330, 44));
        search.getAccessibleContext().setAccessibleName("Tìm tên tài khoản hoặc tên hiển thị");
        search.addActionListener(e -> search());
        searchButton.addActionListener(e -> search());
        page.add(top, BorderLayout.NORTH);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setBackground(UIUtils.COLOR_SURFACE_1);
        resultList.setCellRenderer((list, item, index, selected, focus) -> contactRow(
                item.username(), item.displayName(), relationLabel(item.relationship()), selected));
        resultList.addListSelectionListener(e -> updateActions());
        page.add(scroll(resultList), BorderLayout.CENTER);
        contactAction.addActionListener(e -> actOnSearchResult());
        page.add(contactAction, BorderLayout.SOUTH);
        return page;
    }

    private JPanel buildRequests() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setOpaque(false);
        requestList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestList.setBackground(UIUtils.COLOR_SURFACE_1);
        requestList.setCellRenderer((list, name, index, selected, focus) ->
                contactRow(name, name, "Muốn kết bạn với bạn", selected));
        requestList.addListSelectionListener(e -> updateActions());
        page.add(scroll(requestList), BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1, 2, 10, 0));
        actions.setOpaque(false);
        actions.add(accept);
        actions.add(reject);
        accept.addActionListener(e -> startAction("ACCEPT", requestList.getSelectedValue()));
        reject.addActionListener(e -> startAction("REJECT", requestList.getSelectedValue()));
        page.add(actions, BorderLayout.SOUTH);
        return page;
    }

    private JScrollPane scroll(JList<?> list) {
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(UIUtils.COLOR_BORDER));
        scroll.getViewport().setBackground(UIUtils.COLOR_SURFACE_1);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private JPanel contactRow(String username, String name, String relationship, boolean selected) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBackground(selected ? UIUtils.COLOR_PRIMARY_LIGHT : UIUtils.COLOR_SURFACE_1);
        row.setBorder(new EmptyBorder(14, 14, 14, 14));
        row.add(UIUtils.createAvatarPanel(username, 36), BorderLayout.WEST);
        JPanel labels = new JPanel(new GridLayout(2, 1, 0, 4));
        labels.setOpaque(false);
        JLabel title = new JLabel(name);
        title.setFont(UIUtils.FONT_BOLD);
        title.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
        JLabel subtitle = new JLabel("@" + username + " · " + relationship);
        subtitle.setFont(UIUtils.FONT_SMALL);
        subtitle.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        labels.add(title);
        labels.add(subtitle);
        row.add(labels, BorderLayout.CENTER);
        return row;
    }

    private String relationLabel(String relation) {
        return switch (relation) {
            case "FRIEND" -> "Đã là bạn bè";
            case "OUTGOING" -> "Đã gửi lời mời";
            case "INCOMING" -> "Đã gửi lời mời cho bạn";
            default -> "Chưa kết bạn";
        };
    }

    public void showPage(boolean pending) {
        cards.show(pages, pending ? "REQUESTS" : "SEARCH");
        searchTab.setForeground(pending ? UIUtils.COLOR_TEXT_SECONDARY : UIUtils.COLOR_PRIMARY);
        requestTab.setForeground(pending ? UIUtils.COLOR_PRIMARY : UIUtils.COLOR_TEXT_SECONDARY);
        getRootPane().setDefaultButton(pending ? accept : searchButton);
        if (pending) feedback(requests.isEmpty() ? "Bạn chưa có lời mời kết bạn nào đang chờ."
                : "Chọn lời mời rồi nhấn Chấp nhận hoặc Từ chối.", true);
        else if (!pending && results.isEmpty()) feedback("Nhập tên tài khoản hoặc tên hiển thị rồi nhấn Tìm kiếm.", true);
        setVisible(true);
        if (!pending) search.requestFocusInWindow();
    }

    private void search() {
        if (requestId != null || operation != null) return;
        String query = search.getText().trim();
        if (query.isEmpty()) {
            feedback("Nhập tên người dùng cần tìm.", false);
            search.requestFocusInWindow();
            return;
        }
        requestId = client.searchUsers(query);
        if (requestId == null) { feedback("Chưa kết nối với máy chủ.", false); return; }
        results.clear();
        searchButton.setEnabled(false);
        feedback("Đang tìm kiếm…", true);
        updateActions();
        timeout.restart();
    }

    public void receiveSearch(String id, List<SearchUserItem> users) {
        if (requestId == null || !requestId.equals(id)) return;
        requestId = null;
        timeout.stop();
        results.clear();
        for (SearchUserItem item : users) results.addElement(item);
        if (!results.isEmpty()) resultList.setSelectedIndex(0);
        searchButton.setEnabled(true);
        feedback(users.isEmpty() ? "Không tìm thấy người dùng phù hợp." : "Tìm thấy " + users.size() + " người dùng. Chọn một người để kết nối.", true);
        updateActions();
    }

    private void actOnSearchResult() {
        SearchUserItem item = resultList.getSelectedValue();
        if (item == null || operation != null) return;
        switch (item.relationship()) {
            case "FRIEND" -> { dispose(); openChat.accept(item.username()); }
            case "INCOMING" -> startAction("ACCEPT", item.username());
            case "STRANGER" -> startAction("REQUEST", item.username());
            default -> { }
        }
    }

    private void startAction(String action, String target) {
        if (target == null || operation != null || requestId != null) return;
        if (!client.isConnected()) { feedback("Đã mất kết nối với máy chủ.", false); return; }
        operation = action;
        operationTarget = target;
        switch (action) {
            case "REQUEST" -> client.sendFriendRequest(target);
            case "ACCEPT" -> client.acceptFriendRequest(target);
            case "REJECT" -> client.rejectFriendRequest(target);
            default -> throw new IllegalArgumentException(action);
        }
        feedback("Đang chờ máy chủ xác nhận…", true);
        searchButton.setEnabled(false);
        updateActions();
        timeout.restart();
    }

    public void receiveAction(String action, String target, boolean success, String message) {
        if (operation == null || !operation.equals(action) || !operationTarget.equalsIgnoreCase(target)) return;
        operation = null;
        operationTarget = null;
        timeout.stop();
        if (success) {
            String relation = switch (action) { case "REQUEST" -> "OUTGOING"; case "ACCEPT" -> "FRIEND"; default -> "STRANGER"; };
            for (int i = 0; i < results.size(); i++) {
                SearchUserItem item = results.get(i);
                if (item.username().equalsIgnoreCase(target)) results.set(i, new SearchUserItem(item.username(), item.displayName(), relation));
            }
        }
        feedback(message, success);
        searchButton.setEnabled(true);
        updateActions();
    }

    public void receivePending(List<String> names) {
        String selected = requestList.getSelectedValue();
        requests.clear();
        for (String name : names) requests.addElement(name);
        if (selected != null && names.contains(selected)) requestList.setSelectedValue(selected, true);
        else if (!requests.isEmpty()) requestList.setSelectedIndex(0);
        requestTab.setText("Lời mời (" + requests.size() + ")");
        updateActions();
    }

    public void receiveFriends(List<ChatClient.FriendItem> friends) {
        for (int i = 0; i < results.size(); i++) {
            SearchUserItem item = results.get(i);
            boolean isFriend = friends.stream().anyMatch(friend -> friend.username().equalsIgnoreCase(item.username()));
            if (isFriend || "FRIEND".equals(item.relationship())) {
                results.set(i, new SearchUserItem(item.username(), item.displayName(), isFriend ? "FRIEND" : "STRANGER"));
            }
        }
        updateActions();
    }

    private void updateActions() {
        boolean idle = operation == null && requestId == null;
        SearchUserItem selected = resultList.getSelectedValue();
        contactAction.setText(selected == null ? "Chọn một người dùng" : switch (selected.relationship()) {
            case "FRIEND" -> "Nhắn tin";
            case "INCOMING" -> "Chấp nhận lời mời";
            case "OUTGOING" -> "Đã gửi lời mời";
            default -> "Gửi lời mời kết bạn";
        });
        contactAction.setEnabled(idle && selected != null && !"OUTGOING".equals(selected.relationship()));
        accept.setEnabled(idle && requestList.getSelectedValue() != null);
        reject.setEnabled(idle && requestList.getSelectedValue() != null);
    }

    private void feedback(String message, boolean success) {
        status.setForeground(success ? UIUtils.COLOR_TEXT_SECONDARY : UIUtils.COLOR_DANGER);
        status.setText("<html>" + message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
        status.setToolTipText(message);
    }

    @Override public void dispose() {
        if (timeout != null) timeout.stop();
        super.dispose();
    }
}
