import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Owns the two listening sockets (HTTP + HTTPS) and dispatches workers. */
public final class ProxyServer {

    private final ProxyContext ctx;
    private final int httpPort;
    private final int httpsPort;
    private final String bind;
    private final Consumer<String> statusCb;

    private volatile boolean running;
    private ServerSocket httpSock;
    private ServerSocket httpsSock;
    private Thread httpThread;
    private Thread httpsThread;
    private ExecutorService workers;

    public ProxyServer(ProxyContext ctx, int httpPort, int httpsPort,
                       String bind, Consumer<String> statusCb) {
        this.ctx = ctx;
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;
        this.bind = bind;
        this.statusCb = statusCb == null ? s -> {} : statusCb;
    }

    public boolean isRunning() { return running; }

    public synchronized void start() throws IOException {
        if (running) return;
        try {
            httpSock = openListener(httpPort);
            httpsSock = openListener(httpsPort);
        } catch (IOException e) {
            cleanup();
            throw e;
        }
        workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "proxy-worker");
            t.setDaemon(true);
            return t;
        });
        httpThread  = new Thread(() -> acceptLoop(httpSock,  "HTTP",  HttpHandler::handle),  "http-accept");
        httpsThread = new Thread(() -> acceptLoop(httpsSock, "HTTPS", HttpsHandler::handle), "https-accept");
        httpThread.setDaemon(true);
        httpsThread.setDaemon(true);
        running = true;
        httpThread.start();
        httpsThread.start();
        statusCb.accept("Proxy running on " + bind + ":" + httpPort + " (HTTP) and "
                + bind + ":" + httpsPort + " (HTTPS)");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        cleanup();
        statusCb.accept("Proxy stopped.");
    }

    private void cleanup() {
        running = false;
        for (ServerSocket s : new ServerSocket[]{httpSock, httpsSock}) {
            if (s != null) try { s.close(); } catch (IOException ignore) {}
        }
        httpSock = httpsSock = null;
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }

    private ServerSocket openListener(int port) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName(bind), port));
        s.setSoTimeout(1000); // so accept() unblocks for stop()
        return s;
    }

    @FunctionalInterface
    private interface Handler { void handle(Socket s, ProxyContext c); }

    private void acceptLoop(ServerSocket sock, String label, Handler handler) {
        while (running) {
            Socket client;
            try {
                client = sock.accept();
            } catch (SocketTimeoutException ignore) {
                continue;
            } catch (IOException e) {
                return;
            }
            ExecutorService ws = workers;
            if (ws == null) {
                try { client.close(); } catch (IOException ignore) {}
                return;
            }
            ws.submit(() -> {
                try { handler.handle(client, ctx); }
                catch (Throwable t) { statusCb.accept("[" + label + "] handler error: " + t); }
            });
        }
    }
}
