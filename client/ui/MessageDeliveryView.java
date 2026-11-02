package client.ui;

import client.ChatClient;
import client.messaging.DeliveryTracker.State;
import javax.swing.*;
import java.awt.*;

/** Delivery presentation stays separate from the chat window's message/event routing. */
final class MessageDeliveryView extends JPanel {
    private final JLabel caption = new JLabel();
    private final JButton retry = UIUtils.secondaryButton("Gửi lại");
    private final String time;
    private final boolean outgoing;
    MessageDeliveryView(ChatClient client, String id, String time, boolean outgoing) {
        super(new FlowLayout(outgoing ? FlowLayout.RIGHT : FlowLayout.LEFT, 4, 0));
        this.time = time;
        this.outgoing = outgoing;
        setOpaque(false);
        caption.setFont(UIUtils.FONT_TINY);
        caption.setForeground(UIUtils.COLOR_TEXT_SECONDARY);
        add(caption);
        retry.setFont(UIUtils.FONT_TINY);
        retry.setMargin(new Insets(3, 7, 3, 7));
        retry.addActionListener(e -> client.retryMessage(id));
        add(retry);
        update(outgoing ? client.getMessageDeliveryState(id) : State.STORED, "");
        if (!outgoing) caption.setText(time);
    }
    void update(State state, String detail) {
        caption.setText(time + " · " + switch (state) { case SENDING -> "Đang gửi…"; case STORED -> "Đã gửi"; case FAILED -> "Chưa gửi được"; });
        caption.setForeground(outgoing ? new Color(230, 250, 246)
                : state == State.FAILED ? UIUtils.COLOR_WARNING : UIUtils.COLOR_TEXT_SECONDARY);
        caption.setToolTipText(detail);
        retry.setVisible(state == State.FAILED);
        revalidate();
        repaint();
    }
}
