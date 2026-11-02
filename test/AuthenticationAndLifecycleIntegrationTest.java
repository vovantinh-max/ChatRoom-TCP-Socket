package test;

import client.ChatClient;
import server.ChatServer;
import server.ClientManager;
import server.RoomManager;
import server.db.DatabaseManager;
import shared.protocol.Packet;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Regression tests for authentication bypasses, login races and blocked reads. */
public class AuthenticationAndLifecycleIntegrationTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("[PASS] " + message);
    }

    private static Packet login(String username, String password) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", TestSupport.PORT)) {
            socket.setSoTimeout(5000);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            writer.println(new Packet(Packet.Type.LOGIN, username, password).toRawString());
            return Packet.fromRawString(new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8)).readLine());
        }
    }

    public static void main(String[] args) throws Exception {
        DatabaseManager db = new DatabaseManager();
        ClientManager manager = new ClientManager();
        ChatServer server = new ChatServer(manager, null, new RoomManager(), db);
        List<Socket> contenders = new ArrayList<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ChatClient client = new ChatClient();
        try {
            check(!db.isUsingMemoryFallback(), "Integration tests use SQLite persistence");
            check(server.startServer(), "Server starts");
            check("SUCCESS".equals(client.register("127.0.0.1", TestSupport.PORT,
                    "secure_user", "  spaced password  ", "Secure User")), "Register account");
            check(login("secure_user", "").getType() == Packet.Type.LOGIN_FAILED,
                    "Empty password cannot bypass authentication");
            check(login("secure_user", "wrong").getType() == Packet.Type.LOGIN_FAILED,
                    "Wrong password is rejected");
            check(login("unknown_user", "secret").getType() == Packet.Type.LOGIN_FAILED,
                    "Unknown login is rejected");
            check(!db.userExists("unknown_user"), "Login never creates an account");
            check(client.connect("127.0.0.1", TestSupport.PORT, "SECURE_USER", "  spaced password  "),
                    "Case-insensitive login preserves password spaces");
            Future<?> disconnected = executor.submit(client::disconnect);
            disconnected.get(3, TimeUnit.SECONDS);
            check(!client.isConnected(), "Disconnect unblocks an idle listener");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (manager.getClientCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            check(manager.getClientCount() == 0, "Disconnected account is released");

            CountDownLatch start = new CountDownLatch(1);
            List<Future<Packet>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Socket socket = new Socket("127.0.0.1", TestSupport.PORT);
                socket.setSoTimeout(10000);
                contenders.add(socket);
                results.add(executor.submit(() -> {
                    start.await();
                    PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                    writer.println(new Packet(Packet.Type.LOGIN, "secure_user", "  spaced password  ").toRawString());
                    return Packet.fromRawString(new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8)).readLine());
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Packet> result : results) {
                Packet response = result.get(15, TimeUnit.SECONDS);
                check(response != null && (response.getType() == Packet.Type.LOGIN_SUCCESS
                        || response.getType() == Packet.Type.LOGIN_FAILED), "Concurrent login returns an auth response");
                if (response.getType() == Packet.Type.LOGIN_SUCCESS) successes++;
            }
            check(successes == 1 && manager.getClientCount() == 1,
                    "Exactly one concurrent session owns the account");

            try (Socket idle = new Socket("127.0.0.1", TestSupport.PORT)) {
                idle.setSoTimeout(3000);
                // Ensure the accept loop has picked up this unauthenticated socket.
                PrintWriter writer = new PrintWriter(idle.getOutputStream(), true, StandardCharsets.UTF_8);
                writer.println(new Packet(Packet.Type.REGISTER, "secure_user", "duplicate").toRawString());
                BufferedReader reader = new BufferedReader(new InputStreamReader(idle.getInputStream(), StandardCharsets.UTF_8));
                check(Packet.fromRawString(reader.readLine()).getType() == Packet.Type.REGISTER_FAILED,
                        "Duplicate registration rejected");
                server.stopServer();
                String line;
                do { line = reader.readLine(); } while (line != null);
                check(true, "Stopping server closes unauthenticated sockets");
            }
            check(manager.getClientCount() == 0, "Server stop waits for authenticated session cleanup");
            check(server.startServer(), "Server can restart on the same port");
            check(client.connect("127.0.0.1", TestSupport.PORT, "secure_user", "  spaced password  "),
                    "Account can reconnect after server restart");
            server.stopServer();
            testUnexpectedLoginResponse(executor);
            System.out.println("Authentication and lifecycle regression tests passed.");
        } finally {
            client.disconnect();
            for (Socket socket : contenders) socket.close();
            server.stopServer();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            db.close();
        }
    }

    private static void testUnexpectedLoginResponse(ExecutorService executor) throws Exception {
        try (ServerSocket fake = new ServerSocket(0)) {
            Future<Boolean> peer = executor.submit(() -> {
                try (Socket socket = fake.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    reader.readLine();
                    new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
                            .println(new Packet(Packet.Type.SYSTEM, "SERVER", "unexpected").toRawString());
                    return reader.readLine() == null;
                }
            });
            ChatClient client = new ChatClient();
            try {
                boolean rejected = false;
                try { client.connect("127.0.0.1", fake.getLocalPort(), "user", "pass"); }
                catch (IOException expected) { rejected = true; }
                check(rejected && !client.isConnected(), "Client requires LOGIN_SUCCESS");
                check(peer.get(5, TimeUnit.SECONDS), "Failed handshake closes client socket");
            } finally { client.disconnect(); }
        }
    }
}
