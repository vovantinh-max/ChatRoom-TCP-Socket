package client.ui;

import client.media.audio.AudioCallEngine;
import client.media.video.VideoCallEngine;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

/**
 * VideoCallFrame - Giao diện Cuộc gọi Video & Chia sẻ màn hình hiện đại (Modules 5 & 7).
 * - Cửa sổ chính hiển thị video hoặc màn hình của đối phương
 * - Cửa sổ nhỏ góc dưới (PiP) xem trước camera / màn hình của bản thân
 * - Thanh công cụ điều khiển nổi bên dưới:
 *   + Bật / Tắt Microphone
 *   + Bật / Tắt Camera
 *   + Chuyển đổi Chia sẻ màn hình (Screen Share)
 *   + Kết thúc cuộc gọi
 *   + Đồng hồ thời lượng cuộc gọi
 */
public class VideoCallFrame extends JFrame implements VideoCallEngine.VideoFrameListener, AudioCallEngine.AudioCallListener {

    private final String peerUsername;
    private final AudioCallEngine audioEngine;
    private final VideoCallEngine videoEngine;

    private BufferedImage remoteImage;
    private BufferedImage localImage;

    private JPanel mainVideoPanel;
    private JPanel pipPreviewPanel;
    private JLabel lblDuration;

    private JButton btnMute;
    private JButton btnCamera;
    private JButton btnScreenShare;
    private JButton btnEndCall;

    private boolean connected;
    private String cameraStatus = "Đang mở camera…";
    private boolean isMuted = false;
    private boolean isCameraOn = true;
    private boolean isSharingScreen = false;

    public VideoCallFrame(String peerUsername, AudioCallEngine audioEngine, VideoCallEngine videoEngine) {
        this.peerUsername = peerUsername;
        this.audioEngine = audioEngine;
        this.videoEngine = videoEngine;

        if (this.videoEngine != null) {
            this.videoEngine.setFrameListener(this);
        }
        if (this.audioEngine != null) {
            this.audioEngine.setListener(this);
        }

        initUI();
    }

    private void initUI() {
        setTitle("TCP Chat · Gọi video  ·  @" + peerUsername);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(960, 640);
        setMinimumSize(new Dimension(800, 520));
        setLocationRelativeTo(null);
        getContentPane().setBackground(new Color(15, 23, 42));

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                performEndCall();
            }
        });

        JLayeredPane layeredPane = new JLayeredPane();
        layeredPane.setLayout(null);

        // 1. Vùng hiển thị video đối phương (Chiếm toàn bộ)
        mainVideoPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                if (remoteImage != null) {
                    int pw = getWidth(), ph = getHeight();
                    int iw = remoteImage.getWidth(), ih = remoteImage.getHeight();
                    double scale = Math.min((double) pw / iw, (double) ph / ih);
                    int nw = (int) (iw * scale);
                    int nh = (int) (ih * scale);
                    int x = (pw - nw) / 2;
                    int y = (ph - nh) / 2;
                    g2.drawImage(remoteImage, x, y, nw, nh, null);
                } else {
                    // Màn hình chờ kết nối
                    g2.setColor(new Color(30, 41, 59));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 22));
                    String text = connected ? "Đang chờ hình ảnh từ @" + peerUsername : "Đang gọi @" + peerUsername + "…";
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, getHeight() / 2 - 20);

                    g2.setColor(new Color(148, 163, 184));
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 14));
                    String sub = connected ? cameraStatus : "Camera sẽ mở khi người nhận chấp nhận.";
                    fm = g2.getFontMetrics();
                    g2.drawString(sub, (getWidth() - fm.stringWidth(sub)) / 2, getHeight() / 2 + 15);
                }
                g2.dispose();
            }
        };
        mainVideoPanel.setBackground(new Color(15, 23, 42));
        layeredPane.add(mainVideoPanel, JLayeredPane.DEFAULT_LAYER);

        // 2. Picture-in-Picture (PiP) góc dưới phải xem trước của bản thân
        pipPreviewPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                if (localImage != null) {
                    g2.drawImage(localImage, 0, 0, getWidth(), getHeight(), null);
                } else {
                    g2.setColor(new Color(20, 20, 30));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }

                // Bo viền nổi
                g2.setColor(new Color(59, 130, 246));
                g2.setStroke(new BasicStroke(2));
                g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 12, 12);

                g2.setColor(new Color(0, 0, 0, 160));
                g2.fillRoundRect(6, 6, 48, 18, 6, 6);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
                g2.drawString("Bạn", 18, 19);
                if (connected && cameraStatus != null && (!cameraStatus.isEmpty())) {
                    g2.setColor(new Color(0, 0, 0, 200));
                    g2.fillRect(0, getHeight() - 26, getWidth(), 26);
                    g2.setColor(Color.WHITE);
                    g2.drawString(cameraStatus, 8, getHeight() - 10);
                }

                g2.dispose();
            }
        };
        pipPreviewPanel.setOpaque(false);
        layeredPane.add(pipPreviewPanel, JLayeredPane.PALETTE_LAYER);

        // 3. Thanh điều khiển nổi bên dưới (Control Bar)
        JPanel controlBar = buildControlBar();
        layeredPane.add(controlBar, JLayeredPane.MODAL_LAYER);

        // Resize layout handler
        layeredPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int w = layeredPane.getWidth();
                int h = layeredPane.getHeight();

                mainVideoPanel.setBounds(0, 0, w, h);

                // PiP đặt góc dưới bên phải
                int pipW = 200, pipH = 150;
                pipPreviewPanel.setBounds(w - pipW - 24, h - pipH - 96, pipW, pipH);

                // Control bar đặt giữa đáy
                int cbW = 558, cbH = 64;
                controlBar.setBounds((w - cbW) / 2, h - cbH - 20, cbW, cbH);
            }
        });

        setContentPane(layeredPane);
    }

    private JPanel buildControlBar() {
        JPanel bar = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(15, 23, 42, 220));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 32, 32);
                g2.setColor(new Color(255, 255, 255, 30));
                g2.setStroke(new BasicStroke(1));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 32, 32);
                g2.dispose();
            }
        };
        bar.setOpaque(false);
        bar.setLayout(new FlowLayout(FlowLayout.CENTER, 14, 10));

        // Nút Mic
        btnMute = createCallButton("Mic", new Color(51, 65, 85));
        btnMute.setToolTipText("Bật/Tắt Micro");
        btnMute.addActionListener(e -> toggleMute());
        bar.add(btnMute);

        // Nút Camera
        btnCamera = createCallButton("Camera", new Color(51, 65, 85));
        btnCamera.setToolTipText("Bật/Tắt Camera");
        btnCamera.addActionListener(e -> toggleCamera());
        bar.add(btnCamera);

        // Nút Chia sẻ màn hình
        btnScreenShare = createCallButton("Chia sẻ", new Color(51, 65, 85));
        btnScreenShare.setToolTipText("Chia sẻ toàn màn hình");
        btnScreenShare.addActionListener(e -> toggleScreenShare());
        bar.add(btnScreenShare);

        // Thời lượng cuộc gọi
        lblDuration = new JLabel("00:00");
        lblDuration.setFont(new Font("Consolas", Font.BOLD, 15));
        lblDuration.setForeground(Color.WHITE);
        lblDuration.setBorder(new EmptyBorder(0, 8, 0, 8));
        bar.add(lblDuration);

        // Nút Kết thúc cuộc gọi
        btnEndCall = createCallButton("Cúp máy", new Color(239, 68, 68));
        btnEndCall.setToolTipText("Kết thúc cuộc gọi");
        btnEndCall.addActionListener(e -> performEndCall());
        bar.add(btnEndCall);

        return bar;
    }

    private JButton createCallButton(String caption, Color bgColor) {
        JButton btn = new JButton(caption) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isArmed() ? bgColor.darker() : bgColor);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setPreferredSize(new Dimension(92, 44));
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setMargin(new Insets(0, 0, 0, 0));
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        btnCamera.setEnabled(connected);
        btnScreenShare.setEnabled(connected);
        btnMute.setEnabled(connected);
        mainVideoPanel.repaint();
    }

    private void toggleMute() {
        isMuted = !isMuted;
        if (audioEngine != null) {
            audioEngine.setMuted(isMuted);
        }
        btnMute.setText(isMuted ? "Mic tắt" : "Mic");
        btnMute.setBackground(isMuted ? new Color(185, 28, 28) : new Color(51, 65, 85));
    }

    private void toggleCamera() {
        isCameraOn = !isCameraOn;
        isSharingScreen = false;
        btnScreenShare.setText("Chia sẻ");
        localImage = null;
        cameraStatus = isCameraOn ? "Đang mở camera…" : "Camera đã tắt";
        pipPreviewPanel.repaint();
        if (videoEngine != null) {
            if (!isCameraOn) {
                videoEngine.setVideoMode(VideoCallEngine.VideoMode.OFF);
            } else {
                videoEngine.setVideoMode(VideoCallEngine.VideoMode.CAMERA);
            }
        }
        btnCamera.setText(isCameraOn ? "Camera" : "Bật camera");
        btnCamera.setToolTipText("Bật/Tắt Camera");
    }

    private void toggleScreenShare() {
        isSharingScreen = !isSharingScreen;
        localImage = null;
        cameraStatus = isSharingScreen ? "" : (isCameraOn ? "Đang mở camera…" : "Camera đã tắt");
        if (videoEngine != null) {
            videoEngine.setVideoMode(isSharingScreen ? VideoCallEngine.VideoMode.SCREEN_SHARE
                    : (isCameraOn ? VideoCallEngine.VideoMode.CAMERA : VideoCallEngine.VideoMode.OFF));
        }
        btnScreenShare.setText(isSharingScreen ? "Dừng chia sẻ" : "Chia sẻ");
    }

    private void performEndCall() {
        if (audioEngine != null) {
            audioEngine.endCall();
        }
        if (videoEngine != null) {
            videoEngine.stopVideo();
        }
        this.dispose();
    }

    // ── VideoFrameListener ───────────────────────────────────────────────────────
    @Override
    public void onRemoteFrameReceived(BufferedImage frame) {
        SwingUtilities.invokeLater(() -> { remoteImage = frame; mainVideoPanel.repaint(); });
    }

    @Override
    public void onLocalPreviewFrame(BufferedImage frame) {
        SwingUtilities.invokeLater(() -> {
            localImage = frame;
            if (videoEngine.getCurrentMode() == VideoCallEngine.VideoMode.CAMERA) cameraStatus = "";
            pipPreviewPanel.repaint();
            mainVideoPanel.repaint();
        });
    }

    @Override public void onCameraUnavailable() {
        SwingUtilities.invokeLater(() -> {
            if (!isDisplayable() || videoEngine.getCurrentMode() != VideoCallEngine.VideoMode.OFF) return;
            isCameraOn = false;
            btnCamera.setText("Thử lại");
            btnCamera.setToolTipText("Kiểm tra camera và quyền Windows, rồi bấm để thử lại");
            cameraStatus = "Không mở được camera";
            pipPreviewPanel.repaint();
            mainVideoPanel.repaint();
        });
    }

    @Override
    public void onVideoCallEnded(String reason) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "Cuộc gọi đã kết thúc (" + reason + ")", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            performEndCall();
        });
    }

    // ── AudioCallListener ───────────────────────────────────────────────────────
    @Override
    public void onCallDurationUpdated(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        String timeStr = String.format("%02d:%02d", m, s);
        SwingUtilities.invokeLater(() -> lblDuration.setText(timeStr));
    }

    @Override public void onCallStateChanged(AudioCallEngine.CallState newState, String peer) {
        if (newState == AudioCallEngine.CallState.ENDED) {
            SwingUtilities.invokeLater(this::dispose);
        }
    }
    @Override public void onIncomingCall(String caller, int callerPort) {}
    @Override public void onError(String errorMessage) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, errorMessage, "Lỗi cuộc gọi", JOptionPane.ERROR_MESSAGE));
    }
}
