package client.notification;

import javax.sound.sampled.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * NotificationManager - Quản lý thông báo Desktop & Âm thanh.
 * Đáp ứng Module 10 của Master Prompt:
 * - Thông báo tin nhắn mới, lời mời kết bạn, cuộc gọi đến
 * - Tích hợp SystemTray trên Windows Desktop
 * - Phát âm thanh thông báo (chime) & nhạc chuông cuộc gọi (ringtone)
 * - Chế độ Không làm phiền (Do Not Disturb - DND)
 */
public class NotificationManager {

    private static NotificationManager instance;

    private boolean doNotDisturb = false;
    private TrayIcon trayIcon;
    private boolean systemTraySupported = false;
    private volatile boolean ringing = false;
    private Thread ringtoneThread;

    public static synchronized NotificationManager getInstance() {
        if (instance == null) {
            instance = new NotificationManager();
        }
        return instance;
    }

    private NotificationManager() {
        initSystemTray();
    }

    private void initSystemTray() {
        if (!SystemTray.isSupported()) {
            systemTraySupported = false;
            return;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image iconImage = createDefaultTrayIcon();

            PopupMenu popup = new PopupMenu();
            MenuItem dndItem = new MenuItem("Bật/Tắt Không làm phiền");
            dndItem.addActionListener(e -> {
                doNotDisturb = !doNotDisturb;
                notifyMessage("Messenger Pro", doNotDisturb ? "Đã bật chế độ Không làm phiền." : "Đã tắt Không làm phiền.");
            });
            popup.add(dndItem);

            trayIcon = new TrayIcon(iconImage, "Messenger Desktop Pro", popup);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
            systemTraySupported = true;
        } catch (Exception e) {
            systemTraySupported = false;
        }
    }

    private Image createDefaultTrayIcon() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(59, 130, 246));
        g2.fillOval(2, 2, 28, 28);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Segoe UI", Font.BOLD, 18));
        g2.drawString("M", 8, 22);
        g2.dispose();
        return img;
    }

    public void setDoNotDisturb(boolean dnd) {
        this.doNotDisturb = dnd;
    }

    public boolean isDoNotDisturb() {
        return doNotDisturb;
    }

    /**
     * Bắn thông báo tin nhắn mới
     */
    public void notifyNewMessage(String sender, String preview) {
        if (doNotDisturb) return;
        notifyMessage("Tin nhắn mới từ @" + sender, preview);
        playMessageChime();
    }

    /**
     * Bắn thông báo lời mời kết bạn mới
     */
    public void notifyFriendRequest(String sender) {
        if (doNotDisturb) return;
        notifyMessage("Lời mời kết bạn mới", "@" + sender + " vừa gửi lời mời kết bạn cho bạn!");
        playMessageChime();
    }

    /**
     * Bắn thông báo hệ thống / popup
     */
    public void notifyMessage(String title, String message) {
        if (systemTraySupported && trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    /**
     * Phát âm thanh chime thông báo ngắn (523Hz + 659Hz).
     */
    public void playMessageChime() {
        if (doNotDisturb) return;
        Thread.ofVirtual().start(() -> {
            try {
                // Tạo âm sắc tổng hợp 16kHz
                float sampleRate = 16000f;
                int durationMs = 180;
                int numSamples = (int) (sampleRate * durationMs / 1000);
                byte[] audio = new byte[numSamples * 2];

                for (int i = 0; i < numSamples; i++) {
                    double angle1 = 2.0 * Math.PI * i / (sampleRate / 523.25); // C5
                    double angle2 = 2.0 * Math.PI * i / (sampleRate / 659.25); // E5
                    short sample = (short) ((Math.sin(angle1) + Math.sin(angle2)) * 0.3 * 32767);
                    audio[2 * i] = (byte) (sample & 0xFF);
                    audio[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
                }

                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                    line.open(format);
                    line.start();
                    line.write(audio, 0, audio.length);
                    line.drain();
                }
            } catch (Exception fallback) {
                Toolkit.getDefaultToolkit().beep();
            }
        });
    }

    /**
     * Bắt đầu đổ chuông cuộc gọi
     */
    public synchronized void startRingtone() {
        if (ringing || doNotDisturb) return;
        ringing = true;

        ringtoneThread = Thread.ofVirtual().name("CallRingtone").start(() -> {
            float sampleRate = 16000f;
            AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);

            while (ringing) {
                try {
                    // Beep 800ms
                    int numSamples = (int) (sampleRate * 0.8);
                    byte[] audio = new byte[numSamples * 2];
                    for (int i = 0; i < numSamples; i++) {
                        double angle = 2.0 * Math.PI * i / (sampleRate / 440.0);
                        short sample = (short) (Math.sin(angle) * 0.4 * 32767);
                        audio[2 * i] = (byte) (sample & 0xFF);
                        audio[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
                    }

                    try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                        line.open(format);
                        line.start();
                        line.write(audio, 0, audio.length);
                        line.drain();
                    }
                    Thread.sleep(1200); // Khoảng nghỉ giữa các hồi chuông
                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        });
    }

    /**
     * Dừng đổ chuông
     */
    public synchronized void stopRingtone() {
        ringing = false;
        if (ringtoneThread != null) {
            ringtoneThread.interrupt();
            ringtoneThread = null;
        }
    }
}
