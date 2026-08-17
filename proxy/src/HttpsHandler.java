import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Transparent HTTPS handler (SNI-based tunneling, NO TLS decryption).
 *
 * <p>We peek the SNI from the unencrypted TLS ClientHello, look up the
 * real server IP via the proxy's upstream DNS, open a TCP tunnel to
 * {@code <realIp>:443}, replay the original ClientHello bytes, and then
 * act as a bidirectional byte relay until either side closes.
 *
 * <p>If the SNI hostname matches the filter list, the connection is
 * dropped — the client sees a TLS handshake failure, which is the
 * behaviour the assignment asks for.
 */
public final class HttpsHandler {

    private static final int UPSTREAM_TIMEOUT_MS = 30_000;
    private static final int RELAY_CHUNK = 64 * 1024;

    private HttpsHandler() {}

    public static void handle(Socket clientSock, ProxyContext ctx) {
        String clientIp = clientSock.getInetAddress().getHostAddress();
        try {
            clientSock.setSoTimeout(UPSTREAM_TIMEOUT_MS);
            InputStream cIn = clientSock.getInputStream();

            SniParser.Result hello;
            try {
                hello = SniParser.readClientHello(cIn);
            } catch (IOException e) {
                ctx.log.record(clientIp, "?", "-", "-", null);
                return;
            }
            String sni = hello.sni;
            if (sni == null) {
                ctx.log.record(clientIp, "?", "-", "CONNECT", null);
                return;
            }

            if (ctx.filterStore.shouldApplyFilter(clientIp, sni)) {
                ctx.log.record(clientIp, sni, "-", "CONNECT", 401);
                return; // drop -> client sees handshake failure
            }

            Socket upstream;
            try {
                upstream = new Socket();
                upstream.connect(new InetSocketAddress(sni, 443), UPSTREAM_TIMEOUT_MS);
                upstream.setSoTimeout(UPSTREAM_TIMEOUT_MS);
            } catch (IOException e) {
                ctx.log.record(clientIp, sni, "-", "CONNECT", 502);
                return;
            }

            ctx.log.record(clientIp, sni, "-", "CONNECT", 200);

            try (upstream) {
                upstream.getOutputStream().write(hello.raw);
                upstream.getOutputStream().flush();
                relay(clientSock, upstream);
            }
        } catch (IOException ignore) {
            // Normal disconnects
        } finally {
            try { clientSock.close(); } catch (IOException ignore) {}
        }
    }

    /** Bidirectional byte relay between two blocking sockets, on two threads. */
    private static void relay(Socket a, Socket b) throws IOException {
        Thread t1 = new Thread(() -> pump(a, b), "https-pump-down");
        Thread t2 = new Thread(() -> pump(b, a), "https-pump-up");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        try { t1.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { t2.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void pump(Socket src, Socket dst) {
        try {
            InputStream in = src.getInputStream();
            OutputStream out = dst.getOutputStream();
            byte[] buf = new byte[RELAY_CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignore) {
        } finally {
            try { dst.shutdownOutput(); } catch (IOException ignore) {}
        }
    }
}
