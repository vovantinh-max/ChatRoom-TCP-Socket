package client.media.audio;

import client.ChatClient;
import shared.protocol.Packet;

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
    private AudioCallListener listener;

    private CallState state = CallState.IDLE;
    private String currentPeer;
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
    public synchronized boolean startCall(String recipient) {
        if (state != CallState.IDLE) return false;

        try {
            initUdpSocket();
            this.currentPeer = recipient;
            changeState(CallState.CALLING);

            // Gửi CALL_OFFER qua TCP kèm cổng UDP của máy mình
            client.sendCallOffer(recipient, "AUDIO", localUdpPort);
            return true;
        } catch (Exception e) {
            cleanupCall();
            if (listener != null) listener.onError("Không thể khởi tạo cổng âm thanh: " + e.getMessage());
            return false;
        }
    }

    /**
     * Chấp nhận cuộc gọi đến từ caller.
     */
    public synchronized boolean acceptCall(String caller, int callerUdpPort, String callerHost) {
        if (state != CallState.RINGING) return false;

        try {
            this.currentPeer = caller;
            this.remoteUdpPort = callerUdpPort;
            this.remoteAddress = InetAddress.getByName(callerHost != null ? callerHost : "127.0.0.1");

            // Báo lại bên kia qua TCP rằng đã nhận cuộc gọi
            client.sendCallAnswer(caller, true, localUdpPort);

            // Bắt đầu truyền và phát âm thanh
            startMediaStreaming();
            changeState(CallState.CONNECTED);
            startDurationTimer();
            return true;
        } catch (Exception e) {
            cleanupCall();
            if (listener != null) listener.onError("Chấp nhận cuộc gọi thất bại: " + e.getMessage());
            return false;
        }
    }

    /**
     * Từ chối cuộc gọi đến.
     */
    public synchronized void rejectCall(String caller, String reason) {
        client.sendCallReject(caller, reason);
        cleanupCall();
        changeState(CallState.IDLE);
    }

    /**
     * Kết thúc cuộc gọi hiện tại.
     */
    public synchronized void endCall() {
        if (state == CallState.IDLE) return;
        if (currentPeer != null) {
            client.sendCallEnd(currentPeer);
        }
        cleanupCall();
        changeState(CallState.ENDED);
        changeState(CallState.IDLE);
    }

    /**
     * Xử lý gói tin signaling điều phối cuộc gọi từ ServerListener.
     */
    public synchronized void handleSignalingPacket(Packet packet) {
        if (packet == null) return;

        switch (packet.getType()) {
            case CALL_OFFER:
                if (state == CallState.IDLE) {
                    try {
                        initUdpSocket();
                        this.currentPeer = packet.getSender();
                        int callerPort = 0;
                        try { callerPort = Integer.parseInt(packet.getExtra()); } catch (NumberFormatException ignored) {}
                        changeState(CallState.RINGING);
                        if (listener != null) {
                            listener.onIncomingCall(packet.getSender(), callerPort);
                        }
                    } catch (Exception e) {
                        client.sendCallReject(packet.getSender(), "BUSY");
                    }
                } else {
                    // Đang bận cuộc gọi khác
                    client.sendCallReject(packet.getSender(), "BUSY");
                }
                break;

            case CALL_ANSWER:
                if (state == CallState.CALLING) {
                    int peerPort = 0;
                    try { peerPort = Integer.parseInt(packet.getExtra()); } catch (NumberFormatException ignored) {}
                    try {
                        this.remoteUdpPort = peerPort;
                        this.remoteAddress = InetAddress.getByName("127.0.0.1");
                        startMediaStreaming();
                        changeState(CallState.CONNECTED);
                        startDurationTimer();
                    } catch (Exception e) {
                        endCall();
                    }
                }
                break;

            case CALL_REJECT:
                if (state == CallState.CALLING || state == CallState.RINGING) {
                    cleanupCall();
                    changeState(CallState.ENDED);
                    changeState(CallState.IDLE);
                }
                break;

            case CALL_END:
                cleanupCall();
                changeState(CallState.ENDED);
                changeState(CallState.IDLE);
                break;

            default:
                break;
        }
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
