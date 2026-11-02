package test;

/** Shares the test listener port with the server, avoiding a running desktop server. */
final class TestSupport {
    static final int PORT = Integer.getInteger("chat.port", 5000);
    private TestSupport() {}
}
