package client.ui;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/**
 * Shared desktop design system: navy surfaces, teal accents and accessible controls.
 */
public class UIUtils {

    /** Avoid Windows' white themed fills and embossed, unreadable disabled captions. */
    public static class ReadableButtonUI extends javax.swing.plaf.basic.BasicButtonUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) {
            return new ReadableButtonUI();
        }
        @Override protected void paintButtonPressed(Graphics g, AbstractButton button) {
            if (!(button instanceof ModernButton)) super.paintButtonPressed(g, button);
        }
        @Override protected void paintText(Graphics g, JComponent component, Rectangle bounds, String text) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D textGraphics = (Graphics2D) g.create();
            textGraphics.setFont(button.getFont());
            textGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            textGraphics.setColor(button.isEnabled() ? button.getForeground() : COLOR_TEXT_SECONDARY);
            javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(textGraphics, text,
                    button.getDisplayedMnemonicIndex(), bounds.x, bounds.y + textGraphics.getFontMetrics().getAscent());
            textGraphics.dispose();
        }
    }

    // ─── Color Palette ───────────────────────────────────────────────────────────
    // Dark background layers
    public static final Color COLOR_BG_DARKEST   = new Color(11, 18, 28);
    public static final Color COLOR_BG_DARK      = new Color(15, 23, 35);
    public static final Color COLOR_BG_MEDIUM    = new Color(19, 30, 44);
    public static final Color COLOR_BG_SIDEBAR   = new Color(18, 28, 41);

    // Surface / card colors
    public static final Color COLOR_SURFACE_1    = new Color(23, 35, 50);
    public static final Color COLOR_SURFACE_2    = new Color(29, 43, 60);
    public static final Color COLOR_SURFACE_3    = new Color(39, 55, 73);

    // Primary brand colors (indigo/violet gradient feel)
    public static final Color COLOR_PRIMARY      = new Color(45, 212, 191);   // Indigo-500
    public static final Color COLOR_PRIMARY_HOVER= new Color(36, 184, 165);   // Indigo-600
    public static final Color COLOR_PRIMARY_DARK = new Color(20, 116, 105);
    public static final Color COLOR_PRIMARY_GLOW = new Color(45, 212, 191, 40);
    public static final Color COLOR_PRIMARY_LIGHT= new Color(29, 58, 65);

    // Accent colors
    public static final Color COLOR_ACCENT_VIOLET= new Color(139, 92, 246);  // Violet
    public static final Color COLOR_ACCENT_CYAN  = new Color(6, 182, 212);   // Cyan
    public static final Color COLOR_ACCENT_ROSE  = new Color(244, 63, 94);   // Rose

    // Semantic colors
    public static final Color COLOR_ONLINE       = new Color(34, 197, 94);   // Green-500
    public static final Color COLOR_ONLINE_GLOW  = new Color(34, 197, 94, 50);
    public static final Color COLOR_DANGER       = new Color(239, 68, 68);   // Red-500
    public static final Color COLOR_DANGER_HOVER = new Color(220, 38, 38);
    public static final Color COLOR_WARNING      = new Color(245, 158, 11);  // Amber

    // Text hierarchy
    public static final Color COLOR_TEXT_PRIMARY = new Color(237, 243, 249); // Near-white
    public static final Color COLOR_TEXT_SECONDARY=new Color(163, 180, 198); // Muted blue-gray
    public static final Color COLOR_TEXT_MUTED   = new Color(132, 153, 175); // Dim
    public static final Color COLOR_TEXT_PLACEHOLDER = new Color(126, 145, 166);

    // Borders
    public static final Color COLOR_BORDER       = new Color(39, 55, 74);
    public static final Color COLOR_BORDER_LIGHT = new Color(55, 76, 97);
    public static final Color COLOR_BORDER_FOCUS = new Color(45, 212, 191, 200);

    // Message bubbles
    public static final Color COLOR_MY_BUBBLE    = new Color(20, 116, 105);
    public static final Color COLOR_OTHER_BUBBLE = new Color(29, 43, 60);
    public static final Color COLOR_PRIVATE_BG   = new Color(25, 44, 57);
    public static final Color COLOR_PRIVATE_BORDER= new Color(139, 92, 246, 120);
    public static final Color COLOR_PRIVATE_TEXT = new Color(167, 139, 250); // Violet-400
    public static final Color COLOR_SYSTEM_PILL  = new Color(20, 32, 46);

    // Legacy aliases (kept for backward compat)
    public static final Color COLOR_BACKGROUND   = COLOR_BG_DARK;
    public static final Color COLOR_SURFACE      = COLOR_SURFACE_1;

    // ─── Typography ───────────────────────────────────────────────────────────────
    public static final Font FONT_DISPLAY   = new Font("Segoe UI", Font.BOLD, 26);
    public static final Font FONT_TITLE     = new Font("Segoe UI", Font.BOLD, 18);
    public static final Font FONT_HEADER    = new Font("Segoe UI", Font.BOLD, 15);
    public static final Font FONT_SUBHEADER = new Font("Segoe UI", Font.BOLD, 13);
    public static final Font FONT_REGULAR   = new Font("Segoe UI", Font.PLAIN, 14);
    public static final Font FONT_BOLD      = new Font("Segoe UI", Font.BOLD, 13);
    public static final Font FONT_SMALL     = new Font("Segoe UI", Font.PLAIN, 12);
    public static final Font FONT_TINY      = new Font("Segoe UI", Font.PLAIN, 12);
    public static final Font FONT_MONO      = new Font("Consolas", Font.PLAIN, 12);

    // ─── Avatar color wheel ───────────────────────────────────────────────────────
    private static final Color[] AVATAR_COLORS = {
        new Color(99,  102, 241),  // Indigo
        new Color(6,   182, 212),  // Cyan
        new Color(34,  197, 94),   // Green
        new Color(245, 158, 11),   // Amber
        new Color(244, 63,  94),   // Rose
        new Color(139, 92,  246),  // Violet
        new Color(236, 72,  153),  // Pink
        new Color(20,  184, 166),  // Teal
        new Color(249, 115, 22),   // Orange
        new Color(59,  130, 246),  // Blue
    };

    public static Color getAvatarColor(String name) {
        if (name == null || name.isEmpty()) return AVATAR_COLORS[0];
        return AVATAR_COLORS[Math.floorMod(name.hashCode(), AVATAR_COLORS.length)];
    }

    /** A vertical scroll surface that follows the viewport width instead of clipping forms. */
    public static class WidthTrackingPanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 40); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return getParent() != null && getPreferredSize().height < getParent().getHeight(); }
    }

    public static class EmptyStateList<E> extends JList<E> {
        private final String title;
        private final String description;
        public EmptyStateList(ListModel<E> model, String title, String description) {
            super(model);
            this.title = title;
            this.description = description;
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getModel().getSize() != 0) return;
            Graphics2D p = (Graphics2D) g.create();
            p.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            p.setColor(COLOR_TEXT_SECONDARY);
            p.setFont(FONT_BOLD);
            p.drawString(title, Math.max(12, (getWidth() - p.getFontMetrics().stringWidth(title)) / 2), 65);
            p.setColor(COLOR_TEXT_MUTED);
            p.setFont(FONT_SMALL);
            p.drawString(description, Math.max(12, (getWidth() - p.getFontMetrics().stringWidth(description)) / 2), 90);
            p.dispose();
        }
    }

    public static ModernButton primaryButton(String text) {
        return new ModernButton(text, COLOR_PRIMARY, COLOR_PRIMARY_HOVER, COLOR_BG_DARKEST);
    }

    public static ModernButton secondaryButton(String text) {
        return new ModernButton(text, COLOR_SURFACE_2, COLOR_SURFACE_3, COLOR_TEXT_PRIMARY);
    }

    public static Icon icon(String name, int size) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D p = (Graphics2D) g.create();
                p.translate(x, y);
                p.scale(size / 24.0, size / 24.0);
                p.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                p.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                p.setColor(c.isEnabled() ? c.getForeground() : COLOR_TEXT_MUTED);
                switch (name) {
                    case "send" -> { p.drawLine(3, 3, 21, 12); p.drawLine(21, 12, 3, 21); p.drawLine(3, 21, 6, 12); p.drawLine(6, 12, 3, 3); p.drawLine(6, 12, 16, 12); }
                    case "search" -> { p.drawOval(4, 4, 12, 12); p.drawLine(14, 14, 21, 21); }
                    case "video" -> { p.drawRoundRect(3, 6, 13, 12, 4, 4); p.drawLine(16, 10, 22, 6); p.drawLine(22, 6, 22, 18); p.drawLine(22, 18, 16, 14); }
                    case "phone" -> { p.draw(new Arc2D.Float(4, 3, 16, 18, 150, 230, Arc2D.OPEN)); p.drawRoundRect(3, 3, 6, 6, 2, 2); p.drawRoundRect(15, 16, 6, 5, 2, 2); }
                    case "attach" -> { p.rotate(Math.PI / 4, 12, 12); p.drawRoundRect(6, 2, 12, 20, 9, 9); p.drawRoundRect(9, 5, 6, 13, 5, 5); }
                    case "chat" -> { p.drawRoundRect(3, 3, 18, 14, 5, 5); p.drawLine(7, 17, 7, 22); p.drawLine(7, 22, 13, 17); p.drawLine(8, 9, 16, 9); }
                    default -> { p.drawLine(6, 12, 18, 12); p.drawLine(13, 7, 18, 12); p.drawLine(13, 17, 18, 12); }
                }
                p.dispose();
            }
        };
    }

    // ─── Shadow utility ───────────────────────────────────────────────────────────
    /**
     * Draws a soft drop shadow behind a rounded rect. Call before painting content.
     */
    public static void drawSoftShadow(Graphics2D g2, int x, int y, int w, int h, int radius, Color shadowColor, int layers) {
        for (int i = layers; i >= 1; i--) {
            float alpha = 0.06f * i;
            int offset = i * 2;
            g2.setColor(new Color(shadowColor.getRed(), shadowColor.getGreen(), shadowColor.getBlue(),
                    Math.min(255, (int)(alpha * 255))));
            g2.fill(new RoundRectangle2D.Float(x - offset, y - offset, w + offset * 2, h + offset * 2, radius + offset, radius + offset));
        }
    }

    // ─── Gradient paint utilities ─────────────────────────────────────────────────
    public static GradientPaint verticalGradient(int y, int height, Color top, Color bottom) {
        return new GradientPaint(0, y, top, 0, y + height, bottom);
    }

    // ─── AnimatedButton ───────────────────────────────────────────────────────────
    /**
     * Flat button with hover, pressed, disabled and keyboard-focus states.
     */
    public static class ModernButton extends JButton {
        private Color normalColor;
        private Color hoverColor;
        private boolean isHover = false;
        private boolean isPressed = false;
        private int cornerRadius = 10;

        public ModernButton(String text, Color normalColor, Color hoverColor, Color textColor) {
            super(text);
            setUI(new ReadableButtonUI());
            this.normalColor = normalColor;
            this.hoverColor = hoverColor;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setMargin(new Insets(8, 14, 8, 14));
            setBorderPainted(false);
            setForeground(textColor);
            setFont(FONT_BOLD);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            setOpaque(false);

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    isHover = true;
                    repaint();
                }
                @Override public void mouseExited(MouseEvent e) {
                    isHover = false;
                    isPressed = false;
                    repaint();
                }
                @Override public void mousePressed(MouseEvent e) {
                    isPressed = true;
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    isPressed = false;
                    repaint();
                }
            });
        }

        public void setColors(Color normal, Color hover) {
            normalColor = normal;
            hoverColor = hover;
            repaint();
        }

        public void setCornerRadius(int radius) {
            this.cornerRadius = radius;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, w, h, cornerRadius, cornerRadius);

            Color fill = !isEnabled() ? COLOR_SURFACE_3
                    : isPressed ? normalColor.darker() : isHover ? hoverColor : normalColor;
            g2.setColor(fill);
            g2.fill(shape);
            if (isFocusOwner()) {
                g2.setColor(COLOR_TEXT_PRIMARY);
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Float(3, 3, w - 7, h - 7, cornerRadius, cornerRadius));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ─── ModernTextField ─────────────────────────────────────────────────────────
    public static class ModernTextField extends JTextField {
        private String placeholder = "";
        private boolean isFocused = false;
        private int cornerRadius = 10;
        private float focusAnim = 0f;
        private Timer focusTimer;

        public ModernTextField(String placeholder) {
            this.placeholder = placeholder;
            setFont(FONT_REGULAR);
            setForeground(COLOR_TEXT_PRIMARY);
            setDisabledTextColor(COLOR_TEXT_MUTED);
            setCaretColor(COLOR_PRIMARY);
            setSelectionColor(COLOR_PRIMARY_DARK);
            setSelectedTextColor(Color.WHITE);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            setBackground(COLOR_SURFACE_2);

            addFocusListener(new FocusListener() {
                @Override public void focusGained(FocusEvent e) {
                    isFocused = true;
                    animateFocus(true);
                }
                @Override public void focusLost(FocusEvent e) {
                    isFocused = false;
                    animateFocus(false);
                }
            });
        }

        private void animateFocus(boolean in) {
            if (focusTimer != null) focusTimer.stop();
            focusTimer = new Timer(16, null);
            focusTimer.addActionListener(e -> {
                focusAnim = in ? Math.min(1f, focusAnim + 0.1f) : Math.max(0f, focusAnim - 0.1f);
                repaint();
                if ((in && focusAnim >= 1f) || (!in && focusAnim <= 0f)) focusTimer.stop();
            });
            focusTimer.start();
        }

        public void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();

            // Background
            g2.setColor(COLOR_SURFACE_2);
            g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, cornerRadius, cornerRadius));

            // Border
            if (focusAnim > 0.01f) {
                // Animated focus glow
                Color bc = blend(COLOR_BORDER, COLOR_BORDER_FOCUS, focusAnim);
                g2.setColor(bc);
                g2.setStroke(new BasicStroke(1.5f));
            } else {
                g2.setColor(COLOR_BORDER);
                g2.setStroke(new BasicStroke(1f));
            }
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 3, h - 3, cornerRadius, cornerRadius));

            // Subtle glow when focused
            if (focusAnim > 0.01f) {
                int alpha = (int)(focusAnim * 40);
                g2.setColor(new Color(COLOR_PRIMARY.getRed(), COLOR_PRIMARY.getGreen(), COLOR_PRIMARY.getBlue(), alpha));
                g2.setStroke(new BasicStroke(3f));
                g2.draw(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, cornerRadius + 1, cornerRadius + 1));
            }

            g2.dispose();
            super.paintComponent(g);

            // Placeholder
            if (getText().isEmpty() && placeholder != null && !placeholder.isEmpty()) {
                Graphics2D gp = (Graphics2D) g.create();
                gp.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                gp.setColor(COLOR_TEXT_PLACEHOLDER);
                gp.setFont(getFont());
                Insets ins = getInsets();
                FontMetrics fm = gp.getFontMetrics();
                int y = (h - fm.getHeight()) / 2 + fm.getAscent();
                gp.drawString(placeholder, ins.left, y);
                gp.dispose();
            }
        }
    }

    // ─── ModernPasswordField ─────────────────────────────────────────────────────
    public static class ModernPasswordField extends JPasswordField {
        private String placeholder = "";
        private boolean isFocused = false;
        private int cornerRadius = 10;
        private float focusAnim = 0f;
        private Timer focusTimer;

        public ModernPasswordField(String placeholder) {
            this.placeholder = placeholder;
            setFont(FONT_REGULAR);
            setForeground(COLOR_TEXT_PRIMARY);
            setDisabledTextColor(COLOR_TEXT_MUTED);
            setCaretColor(COLOR_PRIMARY);
            setSelectionColor(COLOR_PRIMARY_DARK);
            setSelectedTextColor(Color.WHITE);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            setEchoChar('●');

            addFocusListener(new FocusListener() {
                @Override public void focusGained(FocusEvent e) {
                    isFocused = true;
                    animateFocus(true);
                }
                @Override public void focusLost(FocusEvent e) {
                    isFocused = false;
                    animateFocus(false);
                }
            });
        }

        private void animateFocus(boolean in) {
            if (focusTimer != null) focusTimer.stop();
            focusTimer = new Timer(16, null);
            focusTimer.addActionListener(e -> {
                focusAnim = in ? Math.min(1f, focusAnim + 0.1f) : Math.max(0f, focusAnim - 0.1f);
                repaint();
                if ((in && focusAnim >= 1f) || (!in && focusAnim <= 0f)) focusTimer.stop();
            });
            focusTimer.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            g2.setColor(COLOR_SURFACE_2);
            g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, cornerRadius, cornerRadius));

            if (focusAnim > 0.01f) {
                Color bc = blend(COLOR_BORDER, COLOR_BORDER_FOCUS, focusAnim);
                g2.setColor(bc);
                g2.setStroke(new BasicStroke(1.5f));
            } else {
                g2.setColor(COLOR_BORDER);
                g2.setStroke(new BasicStroke(1f));
            }
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 3, h - 3, cornerRadius, cornerRadius));

            if (focusAnim > 0.01f) {
                int alpha = (int)(focusAnim * 40);
                g2.setColor(new Color(COLOR_PRIMARY.getRed(), COLOR_PRIMARY.getGreen(), COLOR_PRIMARY.getBlue(), alpha));
                g2.setStroke(new BasicStroke(3f));
                g2.draw(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, cornerRadius + 1, cornerRadius + 1));
            }

            g2.dispose();
            super.paintComponent(g);

            if (getPassword().length == 0 && placeholder != null && !placeholder.isEmpty()) {
                Graphics2D gp = (Graphics2D) g.create();
                gp.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                gp.setColor(COLOR_TEXT_PLACEHOLDER);
                gp.setFont(getFont());
                Insets ins = getInsets();
                FontMetrics fm = gp.getFontMetrics();
                int y = (h - fm.getHeight()) / 2 + fm.getAscent();
                gp.drawString(placeholder, ins.left, y);
                gp.dispose();
            }
        }
    }

    // ─── RoundedPanel ─────────────────────────────────────────────────────────────
    public static class RoundedPanel extends JPanel {
        private Color backgroundColor;
        private Color borderColor;
        private int cornerRadius;
        private boolean drawShadow = false;

        public RoundedPanel(Color bg, Color border, int radius) {
            this.backgroundColor = bg;
            this.borderColor = border;
            this.cornerRadius = radius;
            setOpaque(false);
        }

        public void setDrawShadow(boolean v) { this.drawShadow = v; }
        public void setBgColor(Color c)      { this.backgroundColor = c; repaint(); }
        public void setBorderColor(Color c)  { this.borderColor = c; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (drawShadow) {
                drawSoftShadow(g2, 0, 0, getWidth(), getHeight(), cornerRadius, Color.BLACK, 5);
            }

            if (backgroundColor != null) {
                g2.setColor(backgroundColor);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), cornerRadius, cornerRadius));
            }

            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, cornerRadius, cornerRadius));
            }

            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ─── GlassPanel ──────────────────────────────────────────────────────────────
    /**
     * Glassmorphism-style panel with translucent background and subtle border.
     */
    public static class GlassPanel extends JPanel {
        private int cornerRadius;
        private Color glassColor;
        private Color borderColor;

        public GlassPanel(Color glassColor, Color borderColor, int radius) {
            this.glassColor = glassColor;
            this.borderColor = borderColor;
            this.cornerRadius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Shadow
            drawSoftShadow(g2, 4, 6, getWidth() - 8, getHeight() - 8, cornerRadius, Color.BLACK, 8);

            // Glass body
            g2.setColor(glassColor);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), cornerRadius, cornerRadius));

            // Top highlight (glass shimmer)
            GradientPaint shimmer = new GradientPaint(0, 0, new Color(255, 255, 255, 12), 0, getHeight() / 2f, new Color(255, 255, 255, 3));
            g2.setPaint(shimmer);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight() / 2f, cornerRadius, cornerRadius));

            // Border
            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, cornerRadius, cornerRadius));
            }

            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ─── Avatar ─────────────────────────────────────────────────────────────────
    public static JPanel createAvatarPanel(String name, int size) {
        Color color = getAvatarColor(name);
        String letter = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase() : "?";

        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

                // Subtle glow behind avatar
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
                g2.fillOval(-2, -2, size + 4, size + 4);

                // Avatar circle
                g2.setColor(color);
                g2.fillOval(0, 0, size, size);

                // Inner shine
                g2.setColor(new Color(255, 255, 255, 30));
                g2.fillOval(size / 6, size / 10, size / 3, size / 4);

                // Letter
                g2.setColor(Color.WHITE);
                Font f = new Font("Segoe UI", Font.BOLD, size / 2 + 1);
                g2.setFont(f);
                FontMetrics fm = g2.getFontMetrics();
                int x = (size - fm.stringWidth(letter)) / 2;
                int y = (size - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(letter, x, y);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(size + 4, size + 4));
        panel.setMaximumSize(new Dimension(size + 4, size + 4));
        panel.setMinimumSize(new Dimension(size + 4, size + 4));
        return panel;
    }

    // ─── Online badge dot ────────────────────────────────────────────────────────
    public static JPanel createOnlineDot(int size) {
        JPanel dot = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Glow
                g2.setColor(COLOR_ONLINE_GLOW);
                g2.fillOval(0, 0, size, size);
                // Core
                int pad = 2;
                g2.setColor(COLOR_ONLINE);
                g2.fillOval(pad, pad, size - pad * 2, size - pad * 2);
                g2.dispose();
            }
        };
        dot.setOpaque(false);
        dot.setPreferredSize(new Dimension(size, size));
        dot.setMaximumSize(new Dimension(size, size));
        return dot;
    }

    // ─── Separator line ──────────────────────────────────────────────────────────
    public static JSeparator createSeparator() {
        JSeparator sep = new JSeparator();
        sep.setForeground(COLOR_BORDER);
        sep.setBackground(COLOR_BORDER);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return sep;
    }

    // ─── Color blend utility ─────────────────────────────────────────────────────
    public static Color blend(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int)(a.getRed()   + (b.getRed()   - a.getRed())   * t);
        int g = (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl= (int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t);
        int al= (int)(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t);
        return new Color(r, g, bl, al);
    }

    // ─── Setup global LAF tweaks ─────────────────────────────────────────────────
    public static void applyGlobalDefaults() {
        // Windows native button/menu painters ignore dark background colors.
        UIManager.put("ButtonUI", ReadableButtonUI.class.getName());
        UIManager.put("MenuUI", "javax.swing.plaf.basic.BasicMenuUI");
        UIManager.put("Menu.background", COLOR_SURFACE_1);
        UIManager.put("Menu.foreground", COLOR_TEXT_PRIMARY);
        UIManager.put("Menu.selectionBackground", COLOR_SURFACE_3);
        UIManager.put("Menu.selectionForeground", COLOR_TEXT_PRIMARY);
        UIManager.put("MenuItemUI", "javax.swing.plaf.basic.BasicMenuItemUI");
        UIManager.put("PopupMenuUI", "javax.swing.plaf.basic.BasicPopupMenuUI");
        UIManager.put("Button.select", COLOR_SURFACE_3);
        UIManager.put("Button.disabledText", COLOR_TEXT_MUTED);
        UIManager.put("TextField.inactiveForeground", COLOR_TEXT_MUTED);
        UIManager.put("Label.font", FONT_REGULAR);
        UIManager.put("Button.font", FONT_SMALL);
        UIManager.put("OptionPane.messageFont", FONT_REGULAR);
        UIManager.put("ToolTip.background", COLOR_SURFACE_2);
        UIManager.put("ToolTip.foreground", COLOR_TEXT_PRIMARY);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(COLOR_BORDER_LIGHT));
        UIManager.put("PopupMenu.background", COLOR_SURFACE_1);
        UIManager.put("MenuItem.background", COLOR_SURFACE_1);
        UIManager.put("MenuItem.foreground", COLOR_TEXT_PRIMARY);
        UIManager.put("MenuItem.selectionBackground", COLOR_SURFACE_3);
        UIManager.put("MenuItem.selectionForeground", COLOR_TEXT_PRIMARY);
        UIManager.put("Panel.background", COLOR_BG_DARK);
        UIManager.put("ScrollPane.background", COLOR_BG_DARK);
        UIManager.put("Viewport.background", COLOR_BG_DARK);
        UIManager.put("List.background", COLOR_SURFACE_1);
        UIManager.put("List.foreground", COLOR_TEXT_PRIMARY);
        UIManager.put("List.selectionBackground", COLOR_PRIMARY_LIGHT);
        UIManager.put("List.selectionForeground", COLOR_TEXT_PRIMARY);
        UIManager.put("ScrollBar.thumb", COLOR_SURFACE_3);
        UIManager.put("ScrollBar.thumbHighlight", COLOR_BORDER_LIGHT);
        UIManager.put("ScrollBar.thumbShadow", COLOR_SURFACE_2);
        UIManager.put("ScrollBar.track", COLOR_SURFACE_1);
        UIManager.put("ScrollBar.width", 6);
        UIManager.put("ComboBox.background", COLOR_SURFACE_2);
        UIManager.put("ComboBox.foreground", COLOR_TEXT_PRIMARY);
        UIManager.put("ComboBox.selectionBackground", COLOR_PRIMARY);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("ComboBox.font", FONT_SMALL);
        UIManager.put("OptionPane.background", COLOR_SURFACE_1);
        UIManager.put("OptionPane.messageForeground", COLOR_TEXT_PRIMARY);
        UIManager.put("TextField.background", COLOR_SURFACE_2);
        UIManager.put("TextField.foreground", COLOR_TEXT_PRIMARY);
        UIManager.put("TextField.caretForeground", COLOR_PRIMARY);
        UIManager.put("Label.foreground", COLOR_TEXT_PRIMARY);
        UIManager.put("Button.background", COLOR_SURFACE_3);
        UIManager.put("Button.foreground", COLOR_TEXT_PRIMARY);
        UIManager.put("TitledBorder.titleColor", COLOR_TEXT_SECONDARY);
        UIManager.put("SplitPane.background", COLOR_BG_DARK);
        UIManager.put("SplitPaneDivider.background", COLOR_BORDER);
    }
}
