package client;

import client.ChatClient.FriendItem;
import client.ChatClient.SearchUserItem;
import shared.protocol.Packet;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ServerListener - Luồng đọc dữ liệu từ Server.
 * Hỗ trợ phân tích cả định dạng Packet chuẩn mới và tương thích ngược với định dạng cũ.
 */
public class ServerListener implements Runnable {

    private final BufferedReader reader;
    private final ChatClient client;
    private volatile boolean running = true;

    public ServerListener(BufferedReader reader, ChatClient client) {
        this.reader = reader;
        this.client = client;
    }

    @Override
    public void run() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                processMessage(line);
            }
        } catch (IOException e) {
            // Connection closed or broken
        } finally {
            if (running) {
                running = false;
                client.notifyDisconnected("Mất kết nối với Server.");
            }
        }
    }

    void processMessage(String raw) {
        Packet packet = Packet.fromRawString(raw);
        if (packet == null) return;

        client.notifyPacket(packet);

        switch (packet.getType()) {
            case PONG:
                client.handlePong();
                break;

            case PING:
                Packet pong = new Packet(Packet.Type.PONG, client.getUsername(), "");
                client.sendPacket(pong);
                break;

            case USER_LIST:
                parseUserList(packet.getContent());
                break;

            case ROOM_LIST:
                parseRoomList(packet.getContent());
                break;

            case MESSAGE:
                client.notifyMessage(packet.getSender(), packet.getContent());
                client.notifyChatMessage(new ChatClient.ChatMessageItem(
                    packet.getId(), packet.getSender(), packet.getRecipient(),
                    packet.getContent(), packet.getExtra(), false, false, "", packet.getTimestamp()
                ));
                break;

            case PRIVATE:
                client.notifyPrivateMessage(packet.getSender(), packet.getRecipient(), packet.getContent());
                client.notifyPrivateChatMessage(new ChatClient.ChatMessageItem(
                    packet.getId(), packet.getSender(), packet.getRecipient(),
                    packet.getContent(), packet.getExtra(), false, false, "", packet.getTimestamp()
                ));
                break;

            // ─── NHẮN TIN THỜI GIAN THỰC NÂNG CAO (MODULE 3) ──────────────────
            case TYPING:
                client.notifyTyping(packet.getSender(), "START".equalsIgnoreCase(packet.getContent()));
                break;

            case REACTION:
                client.notifyReactionUpdated(packet.getId(), packet.getContent(), packet.getExtra());
                break;

            case EDIT_MESSAGE:
                client.notifyMessageEdited(packet.getId(), packet.getContent());
                break;

            case REVOKE_MESSAGE:
                client.notifyMessageRevoked(packet.getId());
                break;

            case HISTORY_RESPONSE:
                parseHistoryResponse(packet.getRecipient(), packet.getContent());
                break;

            // ─── SIGNALING GỌI THOẠI & VIDEO (MODULE 4, 5, 6, 7) ──────────────
            case CALL_OFFER:
            case CALL_ANSWER:
            case CALL_CANDIDATE:
            case CALL_END:
            case CALL_REJECT:
            case CALL_STATUS:
                client.notifyCallPacket(packet);
                break;

            // ─── FILE TRANSFER (MODULE 8) ─────────────────────────────────────
            case FILE_REQUEST:
            case FILE_START:
            case FILE_DATA:
            case FILE_END:
            case FILE_CANCEL:
                client.notifyFilePacket(packet);
                break;

            case LOGOUT:
                client.notifySystemMessage(packet.getSender() + " đã rời khỏi phòng chat.");
                break;

            case LOGIN_SUCCESS:
                client.notifySystemMessage(packet.getContent() != null ? packet.getContent() : "Đăng nhập thành công!");
                break;

            // ─── TÍNH NĂNG BẠN BÈ & USER (MODULE 2) ───────────────────────────
            case FRIEND_REQUEST:
                client.notifySystemMessage("🔔 @" + packet.getSender() + " vừa gửi cho bạn một lời mời kết bạn!");
                break;

            case FRIEND_LIST:
                parseFriendList(packet.getContent());
                break;

            case FRIEND_PENDING_LIST:
                parsePendingRequests(packet.getContent());
                break;

            case USER_SEARCH_RESULT:
                parseUserSearchResult(packet.getContent());
                break;

            case USER_STATUS_CHANGE:
                client.notifySystemMessage("Trạng thái của @" + packet.getSender() + " hiện là: " + packet.getContent());
                break;

            case KICK:
                running = false;
                client.notifyDisconnected(packet.getContent() != null ? packet.getContent() : "Bạn đã bị xóa khỏi Server.");
                break;

            case LOGIN_FAILED:
            case REGISTER_FAILED:
            case ERROR:
                client.notifySystemMessage("[LỖI] " + packet.getContent());
                break;

            case SYSTEM:
                client.notifySystemMessage(packet.getContent());
                break;

            default:
                if (packet.getContent() != null && !packet.getContent().isEmpty()) {
                    client.notifySystemMessage(packet.getContent());
                }
                break;
        }
    }

    private void parseFriendList(String content) {
        List<FriendItem> list = new ArrayList<>();
        if (content != null && !content.trim().isEmpty()) {
            for (String entry : content.split(";")) {
                String[] parts = entry.split(",", -1);
                if (parts.length >= 3) {
                    String username = parts[0].trim();
                    String displayName = parts[1].trim();
                    String status = parts[2].trim();
                    boolean isOnline = "ONLINE".equalsIgnoreCase(status);
                    list.add(new FriendItem(username, displayName, status, isOnline));
                }
            }
        }
        client.notifyFriendList(list);
    }

    private void parsePendingRequests(String content) {
        List<String> list = new ArrayList<>();
        if (content != null && !content.trim().isEmpty()) {
            for (String u : content.split(",")) {
                String trimmed = u.trim();
                if (!trimmed.isEmpty()) {
                    list.add(trimmed);
                }
            }
        }
        client.notifyPendingRequests(list);
    }

    private void parseUserSearchResult(String content) {
        List<SearchUserItem> list = new ArrayList<>();
        if (content != null && !content.trim().isEmpty()) {
            for (String entry : content.split(";")) {
                String[] parts = entry.split(",", -1);
                if (parts.length >= 3) {
                    list.add(new SearchUserItem(parts[0].trim(), parts[1].trim(), parts[2].trim()));
                }
            }
        }
        client.notifyUserSearchResult(list);
    }

    private void parseUserList(String userString) {
        List<String> users = new ArrayList<>();
        if (userString != null && !userString.isEmpty()) {
            for (String u : userString.split(",")) {
                String trimmed = u.trim();
                if (!trimmed.isEmpty()) {
                    users.add(trimmed);
                }
            }
        }
        client.notifyUserList(users);
    }

    private void parseRoomList(String roomString) {
        List<String> rooms = new ArrayList<>();
        if (roomString != null && !roomString.isEmpty()) {
            for (String r : roomString.split(",")) {
                String trimmed = r.trim();
                if (!trimmed.isEmpty()) {
                    rooms.add(trimmed);
                }
            }
        }
        client.notifyRoomList(rooms);
    }

    private void parseHistoryResponse(String target, String content) {
        List<ChatClient.ChatMessageItem> list = new ArrayList<>();
        if (content != null && !content.trim().isEmpty()) {
            for (String itemStr : content.split(";;")) {
                String[] parts = itemStr.split("::", -1);
                if (parts.length >= 9) {
                    String uuid = parts[0];
                    String sender = parts[1];
                    String recipient = parts[2];
                    String text = parts[3];
                    String replyTo = parts[4];
                    boolean isEdited = "1".equals(parts[5]);
                    boolean isRevoked = "1".equals(parts[6]);
                    String reactions = parts[7];
                    long timestamp = 0;
                    try { timestamp = Long.parseLong(parts[8]); } catch (NumberFormatException ignored) {}
                    list.add(new ChatClient.ChatMessageItem(uuid, sender, recipient, text, replyTo, isEdited, isRevoked, reactions, timestamp));
                }
            }
        }
        client.notifyHistoryLoaded(target, list);
    }

    public void stopListening() {
        this.running = false;
    }
}
