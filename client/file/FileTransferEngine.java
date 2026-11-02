package client.file;

import client.ChatClient;
import shared.protocol.Packet;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FileTransferEngine - Động cơ truyền tải file & media theo chunk (64KB).
 * Đáp ứng Module 8 của Master Prompt:
 * - Chunking 64KB, không nạp toàn bộ file vào RAM
 * - Tính toán và đối soát Checksum SHA-256 chống hỏng file
 * - Báo cáo tiến trình tải lên / tải xuống thời gian thực
 * - Lưu file an toàn trong thư mục downloads/
 */
public class FileTransferEngine {

    public static final int CHUNK_SIZE = 64 * 1024; // 64 KB chunks
    private static final String DOWNLOADS_DIR = "downloads";

    public interface FileProgressListener {
        void onProgress(String fileId, String fileName, int percent, long bytesTransferred, long totalBytes, boolean isUpload);
        void onTransferComplete(String fileId, String fileName, File savedFile, boolean isUpload);
        void onTransferFailed(String fileId, String fileName, String errorReason);
    }

    private static class ReceivingSession {
        String fileId;
        String fileName;
        long totalBytes;
        int totalChunks;
        String expectedChecksum;
        long bytesReceived = 0;
        int chunksReceived = 0;
        File targetFile;
        BufferedOutputStream outputStream;
        MessageDigest digest;
    }

    private final ChatClient client;
    private final Map<String, ReceivingSession> activeDownloads = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancelledTransfers = new ConcurrentHashMap<>();
    private FileProgressListener progressListener;

    public FileTransferEngine(ChatClient client) {
        this.client = client;
        ensureDownloadDir();
    }

    public void setProgressListener(FileProgressListener listener) {
        this.progressListener = listener;
    }

    private void ensureDownloadDir() {
        File dir = new File(DOWNLOADS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * Gửi file bất kỳ theo chunk 64KB chạy trên Virtual Thread.
     */
    public String sendFile(File file, String recipient) {
        if (file == null || !file.exists() || !file.isFile() || recipient == null) {
            if (progressListener != null) {
                progressListener.onTransferFailed("", file != null ? file.getName() : "", "File không hợp lệ.");
            }
            return null;
        }

        String fileId = UUID.randomUUID().toString();
        cancelledTransfers.put(fileId, false);

        Thread.ofVirtual().name("FileUpload-" + fileId).start(() -> {
            long fileSize = file.length();
            int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
            if (totalChunks == 0) totalChunks = 1;

            try {
                // 1. Tính toán trước Checksum SHA-256
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                    byte[] buffer = new byte[CHUNK_SIZE];
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        sha256.update(buffer, 0, read);
                    }
                }
                String checksum = bytesToHex(sha256.digest());

                // 2. Gửi gói FILE_START
                client.sendFileStart(recipient, fileId, file.getName(), fileSize, totalChunks, checksum);

                // 3. Đọc và truyền từng chunk
                try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                    byte[] chunkBuf = new byte[CHUNK_SIZE];
                    int chunkIndex = 0;
                    long bytesSent = 0;
                    int bytesRead;

                    while ((bytesRead = is.read(chunkBuf)) != -1) {
                        if (Boolean.TRUE.equals(cancelledTransfers.get(fileId))) {
                            client.sendFileCancel(recipient, fileId);
                            if (progressListener != null) {
                                progressListener.onTransferFailed(fileId, file.getName(), "Người gửi đã hủy truyền file.");
                            }
                            return;
                        }

                        byte[] actualData = new byte[bytesRead];
                        System.arraycopy(chunkBuf, 0, actualData, 0, bytesRead);
                        String base64 = Base64.getEncoder().encodeToString(actualData);

                        client.sendFileData(recipient, fileId, chunkIndex, base64);
                        bytesSent += bytesRead;
                        chunkIndex++;

                        int percent = (int) ((bytesSent * 100) / fileSize);
                        if (progressListener != null) {
                            progressListener.onProgress(fileId, file.getName(), percent, bytesSent, fileSize, true);
                        }

                        // Điều tiết nhẹ để tránh nghẽn socket
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                    }

                    // 4. Gửi kết thúc
                    client.sendFileEnd(recipient, fileId, checksum);
                    if (progressListener != null) {
                        progressListener.onTransferComplete(fileId, file.getName(), file, true);
                    }
                }
            } catch (Exception e) {
                if (progressListener != null) {
                    progressListener.onTransferFailed(fileId, file.getName(), "Lỗi truyền file: " + e.getMessage());
                }
            } finally {
                cancelledTransfers.remove(fileId);
            }
        });

        return fileId;
    }

    /**
     * Xử lý gói tin File đến từ ServerListener.
     */
    public void handleIncomingPacket(Packet packet) {
        if (packet == null) return;

        switch (packet.getType()) {
            case FILE_START:
                handleFileStart(packet);
                break;
            case FILE_DATA:
                handleFileData(packet);
                break;
            case FILE_END:
                handleFileEnd(packet);
                break;
            case FILE_CANCEL:
                handleFileCancel(packet);
                break;
            default:
                break;
        }
    }

    private void handleFileStart(Packet packet) {
        String fileId = packet.getId();
        String fileName = packet.getContent();
        String extra = packet.getExtra(); // format: "fileSize:totalChunks:checksum"

        if (fileId == null || fileName == null || extra == null) return;

        try {
            String[] parts = extra.split(":", -1);
            long fileSize = Long.parseLong(parts[0]);
            int totalChunks = Integer.parseInt(parts[1]);
            String checksum = parts.length > 2 ? parts[2] : "";

            // Tránh ghi đè file có sẵn bằng cách thêm timestamp nếu trùng
            String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            File target = new File(DOWNLOADS_DIR, safeName);
            if (target.exists()) {
                String nameOnly = safeName.contains(".") ? safeName.substring(0, safeName.lastIndexOf('.')) : safeName;
                String ext = safeName.contains(".") ? safeName.substring(safeName.lastIndexOf('.')) : "";
                target = new File(DOWNLOADS_DIR, nameOnly + "_" + System.currentTimeMillis() + ext);
            }

            ReceivingSession session = new ReceivingSession();
            session.fileId = fileId;
            session.fileName = fileName;
            session.totalBytes = fileSize;
            session.totalChunks = totalChunks;
            session.expectedChecksum = checksum;
            session.targetFile = target;
            session.outputStream = new BufferedOutputStream(new FileOutputStream(target));
            session.digest = MessageDigest.getInstance("SHA-256");

            activeDownloads.put(fileId, session);

            if (progressListener != null) {
                progressListener.onProgress(fileId, fileName, 0, 0, fileSize, false);
            }
        } catch (Exception e) {
            if (progressListener != null) {
                progressListener.onTransferFailed(fileId, fileName, "Khởi tạo tải xuống thất bại: " + e.getMessage());
            }
        }
    }

    private void handleFileData(Packet packet) {
        String fileId = packet.getId();
        ReceivingSession session = activeDownloads.get(fileId);
        if (session == null) return;

        try {
            byte[] data = Base64.getDecoder().decode(packet.getContent());
            session.outputStream.write(data);
            session.digest.update(data);
            session.bytesReceived += data.length;
            session.chunksReceived++;

            int percent = session.totalBytes > 0 ? (int) ((session.bytesReceived * 100) / session.totalBytes) : 100;
            if (progressListener != null) {
                progressListener.onProgress(fileId, session.fileName, percent, session.bytesReceived, session.totalBytes, false);
            }
        } catch (Exception e) {
            closeSession(session);
            activeDownloads.remove(fileId);
            if (progressListener != null) {
                progressListener.onTransferFailed(fileId, session.fileName, "Lỗi ghi dữ liệu: " + e.getMessage());
            }
        }
    }

    private void handleFileEnd(Packet packet) {
        String fileId = packet.getId();
        ReceivingSession session = activeDownloads.remove(fileId);
        if (session == null) return;

        closeSession(session);

        String actualChecksum = bytesToHex(session.digest.digest());
        if (session.expectedChecksum != null && !session.expectedChecksum.isEmpty()
                && !session.expectedChecksum.equalsIgnoreCase(actualChecksum)) {
            if (progressListener != null) {
                progressListener.onTransferFailed(fileId, session.fileName, "Sai lệch Checksum SHA-256! File có thể bị hỏng.");
            }
            if (session.targetFile.exists()) session.targetFile.delete();
            return;
        }

        if (progressListener != null) {
            progressListener.onTransferComplete(fileId, session.fileName, session.targetFile, false);
        }
    }

    private void handleFileCancel(Packet packet) {
        String fileId = packet.getId();
        ReceivingSession session = activeDownloads.remove(fileId);
        if (session != null) {
            closeSession(session);
            if (session.targetFile.exists()) session.targetFile.delete();
            if (progressListener != null) {
                progressListener.onTransferFailed(fileId, session.fileName, "Quá trình truyền file đã bị hủy.");
            }
        }
    }

    public void cancelTransfer(String fileId) {
        cancelledTransfers.put(fileId, true);
        ReceivingSession session = activeDownloads.remove(fileId);
        if (session != null) {
            closeSession(session);
            if (session.targetFile.exists()) session.targetFile.delete();
        }
    }

    private void closeSession(ReceivingSession session) {
        try {
            if (session.outputStream != null) {
                session.outputStream.flush();
                session.outputStream.close();
            }
        } catch (IOException ignored) {}
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
