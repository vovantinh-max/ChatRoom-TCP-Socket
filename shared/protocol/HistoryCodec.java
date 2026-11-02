package shared.protocol;

import java.util.ArrayList;
import java.util.List;

/** Each of nine fields is encoded independently; user content never acts as a separator. */
public final class HistoryCodec {
    public static final String FORMAT = "HISTORY_V1";
    private HistoryCodec() {}
    public static String encode(List<String[]> rows) {
        List<String> encoded = new ArrayList<>();
        for (String[] fields : rows) {
            if (fields.length != 9) throw new IllegalArgumentException("History requires nine fields");
            List<String> row = new ArrayList<>();
            for (String field : fields) row.add(ContactCodec.encode(field));
            encoded.add(String.join(",", row));
        }
        return String.join(";", encoded);
    }
    public static List<String[]> decode(String content) {
        List<String[]> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) return rows;
        for (String row : content.split(";", -1)) {
            String[] fields = row.split(",", -1);
            if (fields.length != 9 || rows.size() >= 100) throw new IllegalArgumentException("Invalid history response");
            for (int i = 0; i < fields.length; i++) fields[i] = ContactCodec.decode(fields[i], ContactCodec.FORMAT);
            rows.add(fields);
        }
        return rows;
    }
}
