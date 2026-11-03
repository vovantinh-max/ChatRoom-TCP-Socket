package shared.protocol;

/** Ports negotiated by the two clients and address supplied by the server. */
public record CallEndpoint(int audioPort, int videoPort, String host) {
    public static CallEndpoint parse(String extra) {
        String[] fields = extra.split(",", -1);
        if (fields.length > 3) throw new IllegalArgumentException("Invalid call endpoint");
        int audio = Integer.parseInt(fields[0]);
        int video = fields.length > 1 ? Integer.parseInt(fields[1]) : 0;
        if (audio < 1 || audio > 65535 || video < 0 || video > 65535)
            throw new IllegalArgumentException("Invalid call ports");
        return new CallEndpoint(audio, video, fields.length == 3 ? fields[2] : "127.0.0.1");
    }

    public String ports() {
        return audioPort + "," + videoPort;
    }
}
