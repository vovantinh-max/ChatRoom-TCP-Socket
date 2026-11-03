package client.media.video;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the Windows camera helper for one continuous camera-on interval. */
final class WindowsCamera implements AutoCloseable {
    private final Process process;
    private final AtomicReference<BufferedImage> latest = new AtomicReference<>();
    private volatile long lastFrame = System.nanoTime();

    WindowsCamera() throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new IOException("Windows camera required");
        Path helper = Path.of("client", "media", "video", "windows-camera.ps1").toAbsolutePath();
        process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-File", helper.toString(), Long.toString(ProcessHandle.current().pid()))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Thread.ofVirtual().name("WindowsCamera").start(this::read);
    }

    private void read() {
        try (var input = new DataInputStream(new BufferedInputStream(process.getInputStream()))) {
            while (true) {
                int length = Integer.reverseBytes(input.readInt());
                if (length <= 0 || length > 1_048_576) break;
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) break;
                var frame = ImageIO.read(new ByteArrayInputStream(bytes));
                if (frame != null) {
                    latest.set(frame);
                    lastFrame = System.nanoTime();
                }
            }
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    BufferedImage nextFrame() throws IOException {
        if (!process.isAlive() || System.nanoTime() - lastFrame > TimeUnit.SECONDS.toNanos(10))
            throw new IOException("Camera unavailable");
        return latest.getAndSet(null);
    }

    @Override public void close() {
        process.destroyForcibly();
    }
}
