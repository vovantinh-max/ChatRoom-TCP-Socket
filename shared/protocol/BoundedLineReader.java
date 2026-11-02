package shared.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounds UTF-8 frame bytes before allocation, retaining the JDK's virtual-thread-friendly reader locks. */
public final class BoundedLineReader {
    private BoundedLineReader() {}
    public static BufferedReader create(InputStream source) {
        InputStream limited = new FilterInputStream(source) {
            private int lineBytes;
            private void count(int value) throws IOException {
                if (value == 10) lineBytes = 0;
                else if (++lineBytes > ProtocolLimits.MAX_FRAME_CHARS)
                    throw new IOException("Gói tin vượt kích thước cho phép.");
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int size = in.read(buffer, offset, length);
                for (int i = 0; i < size; i++) count(buffer[offset + i] & 255);
                return size;
            }
            @Override public int read() throws IOException {
                int value = in.read(); if (value != -1) count(value); return value;
            }
        };
        return new BufferedReader(new InputStreamReader(limited, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)));
    }
}
