package test;

import client.ChatClient;
import client.ui.ChatFrame;
import client.ui.UIUtils;
import client.ui.LoginFrame;
import server.*;
import server.db.DatabaseManager;
import javax.swing.*;

/** Runs two real clients against an isolated local database for manual UI testing. */
public final class DesktopQaLauncher {
    public static void main(String[] args) throws Exception {
        if (System.getProperty("chat.db.path") == null || System.getProperty("chat.port") == null)
            throw new IllegalArgumentException("Provide a separate chat.db.path and chat.port for QA");
        DatabaseManager db = new DatabaseManager() {
            private final java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(Boolean.getBoolean("chat.qa.failFirstMessage"));
            @Override public MessageWriteResult persistMessage(String id, String type, String target, String sender, String recipient, String content, String reply, long time) {
                try { Thread.sleep(Long.getLong("chat.qa.storeDelayMillis", 0L)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return MessageWriteResult.FAILED; }
                if (first.compareAndSet(true, false)) return MessageWriteResult.FAILED;
                return super.persistMessage(id, type, target, sender, recipient, content, reply, time);
            }
        };
        ChatServer server = new ChatServer(new ClientManager(), null, new RoomManager(), db);
        if (!server.startServer()) throw new IllegalStateException("QA port is unavailable");
        db.registerUser("qa_alice", "QaPass123!", "An Nguyễn");
        db.registerUser("qa_bob", "QaPass123!", "Bảo, QA; Team");
        db.registerUser("qa_carol", "QaPass123!", "Chi Trần");
        ChatClient alice = new ChatClient();
        ChatClient bob = new ChatClient();
        alice.connect("127.0.0.1", TestSupport.PORT, "qa_alice", "QaPass123!");
        bob.connect("127.0.0.1", TestSupport.PORT, "qa_bob", "QaPass123!");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stopServer(); db.close(); }));
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            UIUtils.applyGlobalDefaults();
            LoginFrame login = new LoginFrame();
            login.setTitle("QA · Login · Messenger");
            login.setVisible(true);
            ChatFrame a = new ChatFrame(alice, null);
            ChatFrame b = new ChatFrame(bob, null);
            a.setTitle("QA · Alice · Messenger");
            b.setTitle("QA · Bob · Messenger");
            a.setVisible(true);
            b.setVisible(true);
        });
    }
}
