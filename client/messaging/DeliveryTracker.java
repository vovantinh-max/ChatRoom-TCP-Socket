package client.messaging;

import shared.protocol.Packet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Bounded outgoing queue. Retrying keeps the same UUID so a lost ACK cannot duplicate a message. */
public final class DeliveryTracker {
    public enum State { SENDING, STORED, FAILED }
    public interface Listener { void changed(String id, State state, String detail); }
    private static final class Entry {
        final Packet packet;
        State state = State.SENDING;
        ScheduledFuture<?> timeout;
        int attempt;
        Entry(Packet packet) { this.packet = packet; }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Predicate<Packet> sender;
    private final Listener listener;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Message-delivery"); thread.setDaemon(true); return thread;
    });
    public DeliveryTracker(Predicate<Packet> sender, Listener listener) { this.sender = sender; this.listener = listener; }
    public synchronized String send(Packet packet) {
        if (entries.size() >= 200) {
            entries.entrySet().removeIf(entry -> entry.getValue().state == State.STORED);
            if (entries.size() >= 200) return null;
        }
        Entry entry = new Entry(packet);
        entries.put(packet.getId(), entry);
        transmit(entry);
        return packet.getId();
    }
    private void transmit(Entry entry) {
        entry.state = State.SENDING;
        listener.changed(entry.packet.getId(), State.SENDING, "Đang chờ máy chủ lưu tin nhắn");
        int attempt = ++entry.attempt;
        entry.timeout = timer.schedule(() -> timeout(entry.packet.getId(), attempt), 10, TimeUnit.SECONDS);
        if (!sender.test(entry.packet)) complete(entry.packet.getId(), false, "Mất kết nối. Tin nhắn chưa được gửi.");
    }
    private synchronized void timeout(String id, int attempt) {
        Entry entry = entries.get(id);
        if (entry != null && entry.attempt == attempt && entry.state == State.SENDING)
            complete(id, false, "Chưa nhận xác nhận. Bạn có thể gửi lại an toàn.");
    }
    public synchronized boolean retry(String id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.state != State.FAILED) return false;
        transmit(entry);
        return true;
    }
    public synchronized void complete(String id, boolean success, String detail) {
        Entry entry = entries.get(id);
        if (entry == null || (!success && entry.state == State.STORED)) return;
        if (entry.timeout != null) entry.timeout.cancel(false);
        entry.state = success ? State.STORED : State.FAILED;
        listener.changed(id, entry.state, detail);
    }
    public synchronized State state(String id) { Entry entry = entries.get(id); return entry == null ? State.STORED : entry.state; }
    public synchronized List<Packet> unsent() {
        List<Packet> result = new ArrayList<>();
        for (Entry entry : entries.values()) if (entry.state != State.STORED) result.add(entry.packet);
        return result;
    }
    public synchronized void disconnected() {
        for (Entry entry : entries.values()) if (entry.state == State.SENDING) complete(entry.packet.getId(), false, "Mất kết nối. Bạn có thể gửi lại sau khi kết nối.");
    }
}
