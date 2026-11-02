package shared.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Escapes contact fields so commas/semicolons in display names cannot corrupt a list. */
public final class ContactCodec {
    public static final String FORMAT = "CONTACT_FIELDS_V1";
    private ContactCodec() {}
    public static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }
    public static String decode(String value, String format) {
        return FORMAT.equals(format) ? new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8) : value;
    }
}
