import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CSE471 Term Project - Transparent Proxy entry point.
 *
 * <p>Two run modes:
 * <ul>
 *   <li>GUI mode (default) - Swing control panel with File / Help menus.</li>
 *   <li>Headless mode ({@code --headless}) - no GUI, server starts immediately;
 *       used inside the Docker container for the docker-compose bonus.</li>
 * </ul>
 *
 * <p>Author: Emre AYIK (student no: 20230702107)
 */
public final class Main {

    public static final String DEV_NAME       = "Emre AYIK";
    public static final String DEV_STUDENT_NO = "20230702107";

    private static final int DEFAULT_HTTP_PORT  = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final String DATA_DIR_ENV = "PROXY_DATA_DIR";

    private Main() {}

    public static void main(String[] args) throws Exception {
        boolean headless = false;
        boolean bonusLogin = true;
        int httpPort  = DEFAULT_HTTP_PORT;
        int httpsPort = DEFAULT_HTTPS_PORT;
        String bind   = "0.0.0.0";
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--headless" -> headless = true;
                case "--no-bonus-login" -> bonusLogin = false;
                case "--http-port"  -> httpPort  = Integer.parseInt(args[++i]);
                case "--https-port" -> httpsPort = Integer.parseInt(args[++i]);
                case "--bind"       -> bind      = args[++i];
                case "-h", "--help" -> { printUsage(); return; }
                default -> { System.err.println("Unknown argument: " + a); printUsage(); System.exit(2); }
            }
        }

        ProxyContext ctx = buildContext(bonusLogin);

        if (headless || GraphicsEnvironment.isHeadless()) {
            runHeadless(ctx, httpPort, httpsPort, bind);
        } else {
            runGui(ctx, httpPort, httpsPort, bind);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp out Main [--headless] "
                + "[--http-port N] [--https-port N] [--bind ADDR] [--no-bonus-login]");
    }

    private static Path dataDir() {
        String env = System.getenv(DATA_DIR_ENV);
        if (env != null && !env.isEmpty()) return Paths.get(env);
        return Paths.get("_data").toAbsolutePath();
    }

    private static ProxyContext buildContext(boolean bonusLogin) throws IOException {
        Path data = dataDir();
        java.nio.file.Files.createDirectories(data);
        return new ProxyContext(
                new FilterStore(data.resolve("filter.txt")),
                new DiskCache(data.resolve("cache")),
                new ClientLog(data.resolve("client_log.tsv")),
                bonusLogin);
    }

    private static void runHeadless(ProxyContext ctx, int httpPort, int httpsPort, String bind) {
        ProxyServer server = new ProxyServer(ctx, httpPort, httpsPort, bind,
                msg -> System.out.println(msg));
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("FATAL: " + e.getMessage());
            System.exit(2);
        }
        System.out.println("Headless proxy is running. Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        // Keep the main thread alive; accept loops are daemon threads.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignore) {}
    }

    private static void runGui(ProxyContext ctx, int httpPort, int httpsPort, String bind) {
        final ProxyServer[] holder = new ProxyServer[1];
        final ProxyGui[]   guiHolder = new ProxyGui[1];
        javax.swing.SwingUtilities.invokeLater(() -> {
            ProxyServer server = new ProxyServer(ctx, httpPort, httpsPort, bind,
                    msg -> { if (guiHolder[0] != null) guiHolder[0].setStatus(msg); });
            holder[0] = server;
            ProxyGui gui = new ProxyGui(ctx, server);
            guiHolder[0] = gui;
            gui.show();
        });
    }
}
