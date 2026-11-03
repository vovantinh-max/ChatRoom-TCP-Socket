package client.media.video;

import client.ChatClient;
import shared.protocol.Packet;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VideoCallEngine - Động cơ Video Call và Screen Sharing Real-Time (Modules 5 & 7).
 * - Thu nhận hình ảnh camera hoặc chụp màn hình Robot (Screen Share)
 * - Nén ảnh JPEG hiệu năng cao và phân mảnh truyền qua UDP DatagramSocket
 * - Tái cấu trúc frame phía người nhận và chuyển giao tới UI VideoCallFrame
 * - Tự động điều chỉnh FPS và độ phân giải tối ưu hóa CPU & băng thông
 */
public class VideoCallEngine {

    public enum VideoMode {
        CAMERA,
        SCREEN_SHARE,
        OFF
    }

    public interface VideoFrameListener {
        void onRemoteFrameReceived(BufferedImage frame);
        void onLocalPreviewFrame(BufferedImage frame);
        void onVideoCallEnded(String reason);
        default void onCameraUnavailable() {}
    }

    private static final int MAX_UDP_PAYLOAD = 60000; // An toàn dưới 64KB UDP limit
    private static final int TARGET_WIDTH = 640;
    private static final int TARGET_HEIGHT = 480;
    private static final int FPS = 20;

    private WindowsCamera camera;
    private volatile VideoFrameListener frameListener;

    private DatagramSocket videoSocket;
    private int localVideoPort = 0;
    private InetAddress remoteAddress;
    private int remoteVideoPort = 0;
    private String currentPeer;

    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean isReceiving = new AtomicBoolean(false);
    private volatile VideoMode currentMode = VideoMode.CAMERA;

    private Thread captureThread;
    private Thread receiveThread;
    private Robot robot;

    // Buffer tái cấu trúc frame
    private final ConcurrentHashMap<Integer, byte[][]> frameAssembly = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> frameChunksCount = new ConcurrentHashMap<>();
    private int currentFrameId = 0;

    public VideoCallEngine(ChatClient client) {
        try {
            this.robot = new Robot();
        } catch (AWTException ignored) {}
    }

    public void setFrameListener(VideoFrameListener listener) {
        this.frameListener = listener;
    }

    public VideoMode getCurrentMode() {
        return currentMode;
    }

    public synchronized void setVideoMode(VideoMode mode) {
        this.currentMode = mode;
        if (mode != VideoMode.CAMERA) closeCamera();
    }

    private void closeCamera() {
        if (camera != null) {
            camera.close();
            camera = null;
        }
    }

    public int getLocalVideoPort() {
        return localVideoPort;
    }

    public synchronized boolean initVideoPort() {
        try {
            if (videoSocket == null || videoSocket.isClosed()) {
                videoSocket = new DatagramSocket(0);
                localVideoPort = videoSocket.getLocalPort();
            }
            return true;
        } catch (SocketException e) {
            return false;
        }
    }

    /**
     * Bắt đầu phiên truyền video tới peer.
     */
    public synchronized boolean startVideoStreaming(String peer, String peerHost, int peerVideoPort) {
        if (peerVideoPort <= 0 || peerVideoPort > 65535 || isStreaming.get()) return false;
        this.currentPeer = peer;
        this.remoteVideoPort = peerVideoPort;
        try {
            this.remoteAddress = InetAddress.getByName(peerHost != null ? peerHost : "127.0.0.1");
            if (!initVideoPort()) return false;
            videoSocket.connect(remoteAddress, remoteVideoPort);
        } catch (UnknownHostException | java.io.UncheckedIOException e) {
            stopVideo();
            return false;
        }
        startReceiver();
        startCapturer();
        return true;
    }

    /**
     * Bắt đầu nhận video.
     */
    private void startReceiver() {
        DatagramSocket socket = videoSocket;
        isReceiving.set(true);
        receiveThread = Thread.ofVirtual().name("VideoReceiver-" + currentPeer).start(() -> {
            byte[] packetBuffer = new byte[65535];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(packetBuffer, packetBuffer.length);
                    socket.receive(packet);

                    byte[] data = packet.getData();
                    int len = packet.getLength();
                    if (len < 8) continue;

                    // Header: [frameId: 4 bytes][chunkIndex: 2 bytes][totalChunks: 2 bytes]
                    int frameId = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                    int chunkIndex = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
                    int totalChunks = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);

                    if (totalChunks < 1 || totalChunks > 32 || chunkIndex >= totalChunks) continue;

                    int payloadLen = len - 8;
                    byte[] payload = new byte[payloadLen];
                    System.arraycopy(data, 8, payload, 0, payloadLen);

                    byte[][] assembly = frameAssembly.computeIfAbsent(frameId, k -> new byte[totalChunks][]);
                    if (assembly.length != totalChunks || assembly[chunkIndex] != null) continue;
                    assembly[chunkIndex] = payload;
                    int count = frameChunksCount.merge(frameId, 1, Integer::sum);

                    if (count == totalChunks) {
                        // Frame đã đủ mảnh, lắp ghép và giải nén
                        byte[][] chunks = frameAssembly.remove(frameId);
                        frameChunksCount.remove(frameId);

                        int totalBytes = 0;
                        for (byte[] ch : chunks) {
                            if (ch != null) totalBytes += ch.length;
                        }

                        byte[] fullJpeg = new byte[totalBytes];
                        int offset = 0;
                        for (byte[] ch : chunks) {
                            if (ch != null) {
                                System.arraycopy(ch, 0, fullJpeg, offset, ch.length);
                                offset += ch.length;
                            }
                        }

                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(fullJpeg));
                        if (img != null && frameListener != null) {
                            frameListener.onRemoteFrameReceived(img);
                        }
                    }

                    // Dọn dẹp frame cũ quá 50 frame
                    if (frameAssembly.size() > 50) {
                        frameAssembly.clear();
                        frameChunksCount.clear();
                    }
                } catch (Exception e) {
                    break;
                }
            }
        });
    }

    /**
     * Bắt đầu luồng chụp hình và gửi video đi.
     */
    private void startCapturer() {
        DatagramSocket socket = videoSocket;
        isStreaming.set(true);
        captureThread = Thread.ofVirtual().name("VideoCapturer-" + currentPeer).start(() -> {
            long frameIntervalMs = 1000 / FPS;

            while (!socket.isClosed()) {
                long start = System.currentTimeMillis();

                try {
                    BufferedImage frame = captureCurrentFrame(socket);
                    if (frame != null && !socket.isClosed()) {
                        // Gửi xem trước cho bản thân (local preview)
                        if (frameListener != null) {
                            frameListener.onLocalPreviewFrame(frame);
                        }

                        // Nén và gửi qua mạng nếu có địa chỉ người nhận
                        if (!socket.isClosed()) {
                            sendFrameOverUdp(socket, frame);
                        }
                    }
                } catch (Exception ignored) {}

                long elapsed = System.currentTimeMillis() - start;
                long sleepTime = frameIntervalMs - elapsed;
                if (sleepTime > 0) {
                    try { Thread.sleep(sleepTime); } catch (InterruptedException e) { break; }
                }
            }
        });
    }

    private BufferedImage captureCurrentFrame(DatagramSocket socket) {
        VideoMode mode = currentMode;
        if (mode == VideoMode.OFF) return generateOffFrame();
        if (mode == VideoMode.SCREEN_SHARE) {
            if (robot == null) return generateOffFrame();
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            BufferedImage fullScreen = robot.createScreenCapture(new Rectangle(0, 0, screen.width, screen.height));
            BufferedImage scaled = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(fullScreen, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, null);
            g2.dispose();
            return scaled;
        }
        synchronized (this) {
            if (socket != videoSocket || socket.isClosed() || currentMode != VideoMode.CAMERA) return null;
            try {
                if (camera == null) camera = new WindowsCamera();
                return camera.nextFrame();
            } catch (IOException e) {
                closeCamera();
                currentMode = VideoMode.OFF;
                VideoFrameListener listener = frameListener;
                if (listener != null) listener.onCameraUnavailable();
                return generateOffFrame();
            }
        }
    }

    private BufferedImage generateOffFrame() {
        BufferedImage img = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(new Color(15, 23, 42));
        g2.fillRect(0, 0, TARGET_WIDTH, TARGET_HEIGHT);
        g2.setColor(new Color(100, 116, 139));
        g2.setFont(new Font("Segoe UI", Font.BOLD, 18));
        String text = "Camera đã tắt";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, (TARGET_WIDTH - fm.stringWidth(text)) / 2, TARGET_HEIGHT / 2);
        g2.dispose();
        return img;
    }

    private void sendFrameOverUdp(DatagramSocket socket, BufferedImage frame) throws IOException {
        // Nén JPEG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) return;
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.6f); // Nén tối ưu độ trễ / chất lượng

        try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(mcios);
            writer.write(null, new IIOImage(frame, null, null), param);
        }
        writer.dispose();

        byte[] jpegBytes = baos.toByteArray();
        int frameId = ++currentFrameId;
        int totalBytes = jpegBytes.length;
        int totalChunks = (int) Math.ceil((double) totalBytes / MAX_UDP_PAYLOAD);
        if (totalChunks == 0) totalChunks = 1;

        for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            int offset = chunkIndex * MAX_UDP_PAYLOAD;
            int length = Math.min(MAX_UDP_PAYLOAD, totalBytes - offset);

            byte[] packetData = new byte[8 + length];
            // Header: frameId (4B) | chunkIndex (2B) | totalChunks (2B)
            packetData[0] = (byte) ((frameId >> 24) & 0xFF);
            packetData[1] = (byte) ((frameId >> 16) & 0xFF);
            packetData[2] = (byte) ((frameId >> 8) & 0xFF);
            packetData[3] = (byte) (frameId & 0xFF);

            packetData[4] = (byte) ((chunkIndex >> 8) & 0xFF);
            packetData[5] = (byte) (chunkIndex & 0xFF);

            packetData[6] = (byte) ((totalChunks >> 8) & 0xFF);
            packetData[7] = (byte) (totalChunks & 0xFF);

            System.arraycopy(jpegBytes, offset, packetData, 8, length);

            DatagramPacket dPacket = new DatagramPacket(packetData, packetData.length);
            socket.send(dPacket);
        }
    }

    public synchronized void stopVideo() {
        closeCamera();
        isStreaming.set(false);
        isReceiving.set(false);

        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }

        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }

        if (videoSocket != null && !videoSocket.isClosed()) {
            videoSocket.close();
            videoSocket = null;
        }

        frameAssembly.clear();
        frameChunksCount.clear();
        remoteAddress = null;
        remoteVideoPort = 0;
        localVideoPort = 0;
        currentPeer = null;
    }
}
