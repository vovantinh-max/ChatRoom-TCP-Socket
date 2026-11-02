package test;

import client.ui.UIUtils;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Runs the real Windows look-and-feel painter, including disabled captions. */
public final class UiThemeRenderingTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                UIUtils.applyGlobalDefaults();
                String[] captions = {"Chấp nhận", "Đã gửi lời mời", "Tìm kiếm", "Từ chối", "Nút Windows"};
                JPanel root = new JPanel(new GridLayout(captions.length, 1, 0, 12));
                root.setBackground(UIUtils.COLOR_BG_DARK);
                root.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
                for (int i = 0; i < captions.length; i++) {
                    JButton button = i == 4 ? new JButton(captions[i]) : UIUtils.primaryButton(captions[i]);
                    if (i == 4) {
                        button.setBackground(UIUtils.COLOR_SURFACE_2);
                        button.setForeground(UIUtils.COLOR_TEXT_PRIMARY);
                    }
                    button.setFont(new Font("Segoe UI", Font.BOLD, 18));
                    button.setSize(360, 48);
                    if (i == 1) button.setEnabled(false);
                    if (i == 2) { button.getModel().setArmed(true); button.getModel().setPressed(true); }
                    BufferedImage image = new BufferedImage(360, 48, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    g.setColor(UIUtils.COLOR_BG_DARK);
                    g.fillRect(0, 0, image.getWidth(), image.getHeight());
                    button.printAll(g);
                    g.dispose();
                    Color fill = new Color(image.getRGB(8, 24));
                    Color text = button.isEnabled() ? button.getForeground() : UIUtils.COLOR_TEXT_SECONDARY;
                    if (contrast(fill, text) < 4.5) throw new AssertionError("Unreadable contrast: " + captions[i]);
                    int captionPixels = 0;
                    for (int y = 8; y < 40; y++) for (int x = 40; x < 320; x++)
                        if ((image.getRGB(x, y) & 0xffffff) == (text.getRGB() & 0xffffff)) captionPixels++;
                    if (captionPixels < 10) throw new AssertionError("Caption was covered by theme painter: " + captions[i]);
                    root.add(new JLabel(new ImageIcon(image)));
                    System.out.println("[PASS] Visible caption and contrast: " + captions[i]);
                }
                root.setSize(400, 328);
                root.doLayout();
                BufferedImage preview = new BufferedImage(400, 328, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = preview.createGraphics();
                root.printAll(g);
                g.dispose();
                new File("build").mkdirs();
                ImageIO.write(preview, "png", new File("build/ui-button-states.png"));
                System.out.println("UI theme: 5 rendering checks passed.");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }
    private static double contrast(Color a, Color b) {
        double first = luminance(a), second = luminance(b);
        return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
    }
}
