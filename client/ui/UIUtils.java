package client.ui;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * Lớp tiện ích cung cấp giao diện hiện đại: Bảng màu, Font chữ, Avatar tròn,
 * và các Component giao diện phẳng (ModernButton, ModernTextField, RoundedPanel).
 */
public class UIUtils {

    // Bảng màu hiện đại (Modern Slate & Royal Blue)
    public static final Color COLOR_PRIMARY = new Color(37, 99, 235);       // #2563EB
    public static final Color COLOR_PRIMARY_HOVER = new Color(29, 78, 216); // #1D4ED8
    public static final Color COLOR_PRIMARY_LIGHT = new Color(239, 246, 255);// #EFF6FF
    public static final Color COLOR_BACKGROUND = new Color(248, 250, 252);  // #F8FAFC
    public static final Color COLOR_SURFACE = Color.WHITE;
    public static final Color COLOR_BORDER = new Color(226, 232, 240);      // #E2E8F0
    public static final Color COLOR_BORDER_FOCUS = new Color(59, 130, 246); // #3B82F6
    public static final Color COLOR_TEXT_MAIN = new Color(15, 23, 42);      // #0F172A
    public static final Color COLOR_TEXT_MUTED = new Color(100, 116, 139);  // #64748B
    public static final Color COLOR_ONLINE = new Color(34, 197, 94);        // #22C55E
    public static final Color COLOR_DANGER = new Color(239, 68, 68);        // #EF4444
    public static final Color COLOR_DANGER_HOVER = new Color(220, 38, 38);  // #DC2626

    // Màu sắc tin nhắn
    public static final Color COLOR_MY_BUBBLE = new Color(37, 99, 235);
    public static final Color COLOR_OTHER_BUBBLE = Color.WHITE;
    public static final Color COLOR_PRIVATE_BG = new Color(250, 245, 255);  // #FAF5FF
    public static final Color COLOR_PRIVATE_BORDER = new Color(216, 180, 254);// #D8B4FE
    public static final Color COLOR_PRIVATE_TEXT = new Color(126, 34, 206); // #7E22CE
    public static final Color COLOR_SYSTEM_PILL = new Color(241, 245, 249); // #F1F5F9

    // Font chữ tiêu chuẩn
    public static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 18);
    public static final Font FONT_HEADER = new Font("Segoe UI", Font.BOLD, 14);
    public static final Font FONT_REGULAR = new Font("Segoe UI", Font.PLAIN, 14);
    public static final Font FONT_BOLD = new Font("Segoe UI", Font.BOLD, 13);
    public static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 12);
    public static final Font FONT_TINY = new Font("Segoe UI", Font.PLAIN, 11);

    // Bảng màu ngẫu nhiên cho Avatar người dùng
    private static final Color[] AVATAR_COLORS = {
        new Color(59, 130, 246),  // Blue
        new Color(16, 185, 129),  // Emerald
        new Color(245, 158, 11),  // Amber
        new Color(236, 72, 153),  // Pink
        new Color(139, 92, 246),  // Purple
        new Color(6, 182, 212),   // Cyan
        new Color(249, 115, 22),  // Orange
        new Color(20, 184, 166)   // Teal
    };

    /**
     * Lấy màu avatar dựa theo mã băm tên người dùng
     */
    public static Color getAvatarColor(String name) {
        if (name == null || name.isEmpty()) return AVATAR_COLORS[0];
        int hash = Math.abs(name.hashCode());
        return AVATAR_COLORS[hash % AVATAR_COLORS.length];
    }

    /**
     * Nút bấm giao diện phẳng bo tròn, có hiệu ứng rê chuột
     */
    public static class ModernButton extends JButton {
        private final Color normalColor;
        private final Color hoverColor;
        private boolean isHover = false;
        private int cornerRadius = 10;

        public ModernButton(String text, Color normalColor, Color hoverColor, Color textColor) {
            super(text);
            this.normalColor = normalColor;
            this.hoverColor = hoverColor;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setForeground(textColor);
            setFont(FONT_BOLD);
            setCursor(new Cursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    isHover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    isHover = false;
                    repaint();
                }
            });
        }

        public void setCornerRadius(int radius) {
            this.cornerRadius = radius;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (!isEnabled()) {
                g2.setColor(new Color(203, 213, 225));
            } else if (isHover) {
                g2.setColor(hoverColor);
            } else {
                g2.setColor(normalColor);
            }

            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), cornerRadius, cornerRadius));
            g2.dispose();

            super.paintComponent(g);
        }
    }

    /**
     * Ô nhập văn bản hiện đại với bo góc, viền đổi màu khi focus và văn bản gợi ý (placeholder)
     */
    public static class ModernTextField extends JTextField {
        private String placeholder = "";
        private boolean isFocused = false;
        private int cornerRadius = 10;

        public ModernTextField(String placeholder) {
            this.placeholder = placeholder;
            setFont(FONT_REGULAR);
            setForeground(COLOR_TEXT_MAIN);
            setCaretColor(COLOR_PRIMARY);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));

            addFocusListener(new FocusListener() {
                @Override
                public void focusGained(FocusEvent e) {
                    isFocused = true;
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    isFocused = false;
                    repaint();
                }
            });
        }

        public void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Nền trắng
            g2.setColor(COLOR_SURFACE);
            g2.fill(new RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, cornerRadius, cornerRadius));

            // Viền
            if (isFocused) {
                g2.setColor(COLOR_BORDER_FOCUS);
                g2.setStroke(new BasicStroke(1.8f));
            } else {
                g2.setColor(COLOR_BORDER);
                g2.setStroke(new BasicStroke(1.2f));
            }
            g2.draw(new RoundRectangle2D.Float(1, 1, getWidth() - 3, getHeight() - 3, cornerRadius, cornerRadius));

            g2.dispose();
            super.paintComponent(g);

            // Vẽ placeholder nếu rỗng
            if (getText().isEmpty() && !isFocused && placeholder != null && !placeholder.isEmpty()) {
                Graphics2D gText = (Graphics2D) g.create();
                gText.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                gText.setColor(COLOR_TEXT_MUTED);
                gText.setFont(getFont());
                Insets insets = getInsets();
                FontMetrics fm = gText.getFontMetrics();
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                gText.drawString(placeholder, insets.left, y);
                gText.dispose();
            }
        }
    }

    /**
     * Panel bo tròn góc với màu nền và viền tùy biến
     */
    public static class RoundedPanel extends JPanel {
        private Color backgroundColor;
        private Color borderColor;
        private int cornerRadius = 12;

        public RoundedPanel(Color bg, Color border, int radius) {
            this.backgroundColor = bg;
            this.borderColor = border;
            this.cornerRadius = radius;
            setOpaque(false);
        }

        public void setBgColor(Color color) {
            this.backgroundColor = color;
            repaint();
        }

        public void setBorderColor(Color color) {
            this.borderColor = color;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (backgroundColor != null) {
                g2.setColor(backgroundColor);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), cornerRadius, cornerRadius));
            }

            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.setStroke(new BasicStroke(1.2f));
                g2.draw(new RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, cornerRadius, cornerRadius));
            }

            g2.dispose();
            super.paintComponent(g);
        }
    }
}
