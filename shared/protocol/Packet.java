package shared.protocol;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Packet - Cấu trúc gói tin chuẩn hóa thời gian thực của Messenger Desktop Pro.
 * Hỗ trợ định danh duy nhất (UUID), phân loại lệnh, timestamp epoch, và dữ liệu linh hoạt.
 */
public class Packet implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        // Auth & System
        LOGIN,
        LOGIN_SUCCESS,
        LOGIN_FAILED,
        REGISTER,
        REGISTER_SUCCESS,
        REGISTER_FAILED,
        LOGOUT,
        PING,
        PONG,
        SYSTEM,
        ERROR,
        KICK,
        USER_STATUS_CHANGE,
        PROFILE_UPDATE,

        // Messaging
        MESSAGE,
        PRIVATE,
        MESSAGE_ACK,
        TYPING,
        REACTION,
        EDIT_MESSAGE,
        REVOKE_MESSAGE,
        HISTORY_REQUEST,
        HISTORY_RESPONSE,
        READ_RECEIPT,

        // Contacts & Friends
        USER_LIST,
        ROOM_LIST,
        CREATE_ROOM,
        JOIN_ROOM,
        LEAVE_ROOM,
        FRIEND_REQUEST,
        FRIEND_ACCEPT,
        FRIEND_REJECT,
        FRIEND_REMOVE,
        FRIEND_LIST,
        FRIEND_PENDING_LIST,
        USER_SEARCH,
        USER_SEARCH_RESULT,

        // Signaling (Voice / Video Call / WebRTC)
        CALL_OFFER,
        CALL_ANSWER,
        CALL_CANDIDATE,
        CALL_END,
        CALL_REJECT,
        CALL_STATUS,

        // File Transfer
        FILE_REQUEST,
        FILE_START,
        FILE_DATA,
        FILE_END,
        FILE_CANCEL
    }

    private String id;
    private Type type;
    private String sender;
    private String recipient;
    private String content;
    private long timestamp;
    private String extra; // JSON metadata hoặc thông số phụ

    public Packet() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now().toEpochMilli();
    }

    public Packet(Type type, String sender, String content) {
        this();
        this.type = type;
        this.sender = sender;
        this.content = content;
    }

    public Packet(Type type, String sender, String recipient, String content) {
        this(type, sender, content);
        this.recipient = recipient;
    }

    // ─── Encoding / Decoding an toàn cho TCP Socket stream ───────────────────────
    // Định dạng frame chuẩn: [TYPE]^[ID]^[TIMESTAMP]^[SENDER]^[RECIPIENT]^[EXTRA]^[CONTENT]
    public String toRawString() {
        return escape(type != null ? type.name() : "") + "^" +
               escape(id != null ? id : "") + "^" +
               timestamp + "^" +
               escape(sender != null ? sender : "") + "^" +
               escape(recipient != null ? recipient : "") + "^" +
               escape(extra != null ? extra : "") + "^" +
               escape(content != null ? content : "");
    }

    public static Packet fromRawString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        // Tương thích ngược: Nếu chuỗi không chứa '^' nhưng chứa '|', parse theo giao thức cũ
        if (!raw.contains("^") && raw.contains("|")) {
            return parseLegacyFormat(raw);
        }

        List<String> parts = splitFields(raw);
        if (parts.size() < 7) {
            return parseLegacyFormat(raw);
        }

        Packet p = new Packet();
        try {
            p.type = Type.valueOf(unescape(parts.get(0)));
        } catch (IllegalArgumentException e) {
            p.type = Type.MESSAGE;
        }
        p.id = unescape(parts.get(1));
        try {
            p.timestamp = Long.parseLong(parts.get(2));
        } catch (NumberFormatException e) {
            p.timestamp = Instant.now().toEpochMilli();
        }
        p.sender = unescape(parts.get(3));
        p.recipient = unescape(parts.get(4));
        p.extra = unescape(parts.get(5));
        p.content = unescape(parts.get(6));

        return p;
    }

    private static List<String> splitFields(String raw) {
        List<String> fields = new ArrayList<>(7);
        StringBuilder current = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                current.append('\\').append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '^') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (escaped) {
            current.append('\\');
        }
        fields.add(current.toString());
        return fields;
    }

    private static Packet parseLegacyFormat(String raw) {
        String[] parts = raw.split("\\|", -1);
        Packet p = new Packet();
        String cmd = parts[0].trim();

        switch (cmd) {
            case "LOGIN":
                p.type = Type.LOGIN;
                p.sender = parts.length > 1 ? parts[1].trim() : "";
                break;
            case "LOGIN_SUCCESS":
            case "LOGIN_OK":
                p.type = Type.LOGIN_SUCCESS;
                p.content = "Đăng nhập thành công!";
                break;
            case "LOGIN_FAILED":
                p.type = Type.LOGIN_FAILED;
                p.content = parts.length > 1 ? parts[1] : "Đăng nhập thất bại";
                break;
            case "MESSAGE":
                p.type = Type.MESSAGE;
                p.sender = parts.length > 1 ? parts[1] : "";
                p.content = parts.length > 2 ? parts[2] : "";
                break;
            case "PRIVATE":
                p.type = Type.PRIVATE;
                p.sender = parts.length > 1 ? parts[1] : "";
                p.recipient = parts.length > 2 ? parts[2] : "";
                p.content = parts.length > 3 ? parts[3] : "";
                break;
            case "USER_LIST":
                p.type = Type.USER_LIST;
                p.content = parts.length > 1 ? parts[1] : "";
                break;
            case "ROOM_LIST":
                p.type = Type.ROOM_LIST;
                p.content = parts.length > 1 ? parts[1] : "";
                break;
            case "CREATE_ROOM":
            case "CREATE":
                p.type = Type.CREATE_ROOM;
                p.content = parts.length > 1 ? parts[1] : "";
                break;
            case "JOIN":
                p.type = Type.JOIN_ROOM;
                p.content = parts.length > 1 ? parts[1] : "";
                break;
            case "LOGOUT":
                p.type = Type.LOGOUT;
                p.sender = parts.length > 1 ? parts[1] : "";
                break;
            case "PING":
                p.type = Type.PING;
                break;
            case "PONG":
                p.type = Type.PONG;
                break;
            case "KICK":
            case "DISCONNECT":
                p.type = Type.KICK;
                p.content = parts.length > 1 ? parts[1] : "Bị ngắt kết nối";
                break;
            case "SYSTEM":
            case "INFO":
                p.type = Type.SYSTEM;
                p.content = parts.length > 1 ? parts[1] : "";
                break;
            case "ERROR":
                p.type = Type.ERROR;
                p.content = parts.length > 1 ? parts[1] : "";
                break;
            default:
                p.type = Type.SYSTEM;
                p.content = raw;
                break;
        }
        return p;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("^", "\\^")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String unescape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else if (c == '^') sb.append('^');
                else if (c == '\\') sb.append('\\');
                else sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ─── Getters & Setters ───────────────────────────────────────────────────────
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getExtra() { return extra; }
    public void setExtra(String extra) { this.extra = extra; }

    @Override
    public String toString() {
        return "Packet{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", sender='" + sender + '\'' +
                ", recipient='" + recipient + '\'' +
                ", timestamp=" + timestamp +
                ", content='" + content + '\'' +
                '}';
    }
}
