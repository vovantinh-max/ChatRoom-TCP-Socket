package client.media.audio;

import client.ChatClient;
import shared.protocol.Packet;
import shared.protocol.CallEndpoint;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AudioCallEngine - Động cơ gọi thoại Real-Time độ trễ thấp (Module 4).
 * - Sử dụng Java Sound API (16kHz PCM, 16-bit mono)
 * - Truyền luồng âm thanh thời gian thực qua UDP DatagramSocket
 * - Tách biệt signaling (TCP) và media (UDP)
 * - Quản lý trạng thái cuộc gọi: IDLE, CALLING, RINGING, CONNECTED, ENDED
 * - Bật/tắt Micro, điều chỉnh âm lượng, đếm thời lượng
 */
public class AudioCallEngine {

    public enum CallState {
        IDLE,
        CALLING,
        RINGING,
        CONNECTED,
        ENDED
    }

    public interface AudioCallListener {
        void onCallStateChanged(CallState newState, String peer);
        void onIncomingCall(String caller, int callerPort);
        void onCallDurationUpdated(int seconds);
        void onError(String errorMessage);
    }

    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    private static final int CHANNELS = 1;
    private static final int BUFFER_SIZE = 640; // 20ms of 16kHz 16-bit mono audio

    private final ChatClient client;
    private volatile AudioCallListener listener;

    private volatile CallState state = CallState.IDLE;
    private String currentPeer;
    private String callId;
    private boolean videoCall;
    private DatagramSocket udpSocket;
    private int localUdpPort = 0;
    private InetAddress remoteAddress;
    private int remoteUdpPort = 0;

    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isMuted = new AtomicBoolean(false);
    private float volume = 1.0f; // 0.0 to 1.0

    private TargetDataLine microphoneLine;
    private SourceDataLine speakerLine;

    private Thread captureThread;
    private Thread playbackThread;
    private Thread timerThread;
    private int callDurationSeconds = 0;

    public AudioCallEngine(ChatClient client) {
        this.client = client;
    }

    public void setListener(AudioCallListener listener) {
        this.listener = listener;
    }

    public CallState getState() {
        return state;
    }

    public String getCurrentPeer() {
        return currentPeer;
    }

    public int getLocalUdpPort() {
        return localUdpPort;
    }

    public boolean isMuted() {
        return isMuted.get();
    }

    public void setMuted(boolean muted) {
        this.isMuted.set(muted);
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0.0f, Math.min(1.0f, vol));
    }

    /**
     * Bắt đầu cuộc gọi thoại 1-1 tới người nhận.
     */
    public boolean startCall(String recipient) {
        return startCall(recipient, 0);
    }

    public synchronized boolean startCall(String recipient, int videoPort) {
        if (state != CallState.IDLE) return false;
        try {
            initUdpSocket();
            currentPeer = recipient;
            callId = java.util.UUID.randomUUID().toString();
            videoCall = videoPort != 0;
            changeState(CallState.CALLING);
            client.sendCallSignal(Packet.Type.CALL_OFFER, recipient, videoCall ? "VIDEO" : "AUDIO",
                    new CallEndpoint(localUdpPort, videoPort, "").ports(), callId);
            return true;
        } catch (IOException e) {
            finish();
            return false;
        }
    }

    public boolean acceptCall(String caller, int callerUdpPort, String callerHost) {
        return acceptCall(caller, new CallEndpoint(callerUdpPort, 0, callerHost), 0);
    }

    public synchronized boolean acceptCall(String caller, CallEndpoint endpoint, int videoPort) {
        if (state != CallState.RINGING || !caller.equalsIgnoreCase(currentPeer)
                || videoCall != (videoPort != 0)) return false;
        try {
            connectMedia(endpoint);
            client.sendCallSignal(Packet.Type.CALL_ANSWER, caller, "ACCEPT",
                    new CallEndpoint(localUdpPort, videoPort, "").ports(), callId);
            return true;
        } catch (IOException e) {
            rejectCall(caller, "MEDIA_UNAVAILABLE");
            return false;
        }
    }

    public synchronized void rejectCall(String caller, String reason) {
        if (state != CallState.RINGING || !caller.equalsIgnoreCase(currentPeer)) return;
        client.sendCallSignal(Packet.Type.CALL_REJECT, caller, reason, "", callId);
        finish();
    }

    public synchronized void endCall() {
        if (state == CallState.IDLE) return;
        client.sendCallSignal(Packet.Type.CALL_END, currentPeer, "END", "", callId);
        finish();
    }

    private void finish() {
        cleanupCall();
        callId = null;
        changeState(CallState.ENDED);
        changeState(CallState.IDLE);
    }

    public synchronized boolean handleSignalingPacket(Packet packet) {
        if (packet == null) return false;
        if (packet.getType() == Packet.Type.CALL_OFFER) {
            if (state != CallState.IDLE) {
                if (!packet.getId().equals(callId))
                    client.sendCallSignal(Packet.Type.CALL_REJECT, packet.getSender(), "BUSY", "", packet.getId());
                return false;
            }
            try {
                CallEndpoint endpoint = CallEndpoint.parse(packet.getExtra());
                boolean video = "VIDEO".equals(packet.getContent());
                if (video != (endpoint.videoPort() != 0)) throw new IllegalArgumentException();
                initUdpSocket();
                currentPeer = packet.getSender();
                callId = packet.getId();
                videoCall = video;
                changeState(CallState.RINGING);
                if (listener != null) listener.onIncomingCall(currentPeer, endpoint.audioPort());
                return true;
            } catch (IOException | IllegalArgumentException e) {
                client.sendCallSignal(Packet.Type.CALL_REJECT, packet.getSender(), "MEDIA_UNAVAILABLE", "", packet.getId());
                return false;
            }
        }
        boolean fromServer = packet.getType() == Packet.Type.CALL_REJECT
                && "OFFLINE".equals(packet.getExtra());
        if (!packet.getId().equals(callId)
                || (!fromServer && !packet.getSender().equalsIgnoreCase(currentPeer))) return false;
        switch (packet.getType()) {
            case CALL_ANSWER:
                if (state != CallState.CALLING) return false;
                try {
                    CallEndpoint endpoint = CallEndpoint.parse(packet.getExtra());
                    if (videoCall != (endpoint.videoPort() != 0)) return false;
                    connectMedia(endpoint);
                    return true;
                } catch (IOException | IllegalArgumentException e) {
                    endCall();
                    return false;
                }
            case CALL_REJECT:
                if (state != CallState.CALLING) return false;
                finish();
                return true;
            case CALL_END:
                finish();
                return true;
            default:
                return false;
        }
    }

    private void connectMedia(CallEndpoint endpoint) throws IOException {
        remoteUdpPort = endpoint.audioPort();
        remoteAddress = InetAddress.getByName(endpoint.host());
        udpSocket.connect(remoteAddress, remoteUdpPort);
        isMuted.set(false);
        startMediaStreaming();
        changeState(CallState.CONNECTED);
        startDurationTimer();
    }

    private void initUdpSocket() throws SocketException {
        if (udpSocket == null || udpSocket.isClosed()) {
            udpSocket = new DatagramSocket(0); // Cổng ngẫu nhiên hệ thống cấp phát
            localUdpPort = udpSocket.getLocalPort();
        }
    }

    private void startMediaStreaming() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, true, false);

        // Khởi động phát âm thanh (Speaker playback)
        try {
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
            if (AudioSystem.isLineSupported(speakerInfo)) {
                speakerLine = (SourceDataLine) AudioSystem.getLine(speakerInfo);
                speakerLine.open(format, BUFFER_SIZE * 4);
                speakerLine.start();
            }
        } catch (Exception ignored) {}

        // Khởi động thu âm (Microphone capture)
        try {
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
            if (AudioSystem.isLineSupported(micInfo)) {
                microphoneLine = (TargetDataLine) AudioSystem.getLine(micInfo);
                microphoneLine.open(format, BUFFER_SIZE * 4);
                microphoneLine.start();
            }
        } catch (Exception ignored) {}

        // 1. Luồng truyền âm thanh đi (Capture -> UDP send)
        isCapturing.set(true);
        captureThread = Thread.ofVirtual().name("AudioCapture-" + currentPeer).start(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (isCapturing.get() && udpSocket != null && !udpSocket.isClosed()) {
                try {
                    int bytesRead = 0;
                    if (microphoneLine != null && microphoneLine.isOpen()) {
                        bytesRead = microphoneLine.read(buffer, 0, buffer.length);
                    } else {
                        // Fallback: Giả lập tín hiệu âm thanh hợp lệ (Tone 440Hz)
                        for (int i = 0; i < buffer.length / 2; i++) {
                            short s = (short) (Math.sin(2 * Math.PI * i / 36.0) * 1000);
                            buffer[2 * i] = (byte) (s & 0xFF);
                            buffer[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
                        }
                        bytesRead = buffer.length;
                        Thread.sleep(20);
                    }

                    if (bytesRead > 0 && remoteAddress != null && remoteUdpPort > 0) {
                        if (isMuted.get()) {
                            // Khi mute, truyền gói im lặng (zeros)
                            java.util.Arrays.fill(buffer, (byte) 0);
                        }
                        DatagramPacket packet = new DatagramPacket(buffer, bytesRead, remoteAddress, remoteUdpPort);
                        udpSocket.send(packet);
                    }
                } catch (Exception e) {
                    break;
                }
            }
        });

        // 2. Luồng nhận âm thanh về (UDP receive -> Speaker)
        isPlaying.set(true);
        playbackThread = Thread.ofVirtual().name("AudioPlayback-" + currentPeer).start(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (isPlaying.get() && udpSocket != null && !udpSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);

                    if (speakerLine != null && speakerLine.isOpen()) {
                        byte[] data = packet.getData();
                        int len = packet.getLength();

                        // Áp dụng điều chỉnh âm lượng (software gain)
                        if (volume < 1.0f) {
                            for (int i = 0; i < len - 1; i += 2) {
                                short val = (short) ((data[i + 1] << 8) | (data[i] & 0xFF));
                                val = (short) (val * volume);
                                data[i] = (byte) (val & 0xFF);
                                data[i + 1] = (byte) ((val >> 8) & 0xFF);
                            }
                        }

                        speakerLine.write(data, 0, len);
                    }
                } catch (Exception e) {
                    break;
                }
            }
        });
    }

    private void startDurationTimer() {
        callDurationSeconds = 0;
        timerThread = Thread.ofVirtual().name("CallTimer").start(() -> {
            while (state == CallState.CONNECTED) {
                try {
                    Thread.sleep(1000);
                    callDurationSeconds++;
                    if (listener != null) {
                        listener.onCallDurationUpdated(callDurationSeconds);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    private void cleanupCall() {
        isCapturing.set(false);
        isPlaying.set(false);

        if (microphoneLine != null) {
            try {
                microphoneLine.stop();
                microphoneLine.close();
            } catch (Exception ignored) {}
            microphoneLine = null;
        }

        if (speakerLine != null) {
            try {
                speakerLine.stop();
                speakerLine.close();
            } catch (Exception ignored) {}
            speakerLine = null;
        }

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            udpSocket = null;
        }

        if (timerThread != null) {
            timerThread.interrupt();
            timerThread = null;
        }

        remoteAddress = null;
        remoteUdpPort = 0;
        localUdpPort = 0;
        currentPeer = null;
        callDurationSeconds = 0;
    }

    private void changeState(CallState newState) {
        this.state = newState;
        if (listener != null) {
            listener.onCallStateChanged(newState, currentPeer);
        }
    }
}
