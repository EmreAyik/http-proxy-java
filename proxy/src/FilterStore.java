import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Thread-safe filter list and per-client auth-token state.
 *
 * <p>Bonus token contract (from the assignment):
 * <ul>
 *   <li>{@code "8a21bce200"} – disable filtering for that client.</li>
 *   <li>{@code "51e2cba401"} – enable filtering for that client.</li>
 * </ul>
 */
public final class FilterStore {

    public static final String TOKEN_DISABLE_FILTER = "8a21bce200";
    public static final String TOKEN_ENABLE_FILTER  = "51e2cba401";

    public enum ClientMode { UNKNOWN, FILTERED, UNFILTERED }

    private final Object lock = new Object();
    private final TreeSet<String> hosts = new TreeSet<>();
    private final Map<String, ClientMode> clientMode = new HashMap<>();
    private final Path path;

    public FilterStore(Path path) {
        this.path = path;
        load();
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String h = line.trim().toLowerCase();
                if (!h.isEmpty()) hosts.add(h);
            }
        } catch (IOException e) {
            // Non-fatal: start with an empty list if the file is unreadable.
        }
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder();
            for (String h : hosts) sb.append(h).append('\n');
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Persistence failures should not crash the proxy.
        }
    }

    public void add(String host) {
        String h = host.trim().toLowerCase();
        while (h.startsWith(".")) h = h.substring(1);
        if (h.isEmpty()) return;
        synchronized (lock) {
            hosts.add(h);
            save();
        }
    }

    public void remove(String host) {
        String h = host.trim().toLowerCase();
        while (h.startsWith(".")) h = h.substring(1);
        synchronized (lock) {
            hosts.remove(h);
            save();
        }
    }

    public List<String> list() {
        synchronized (lock) {
            return new ArrayList<>(hosts);
        }
    }

    /** True if {@code host}, or any of its parent domains, is in the filter list. */
    public boolean isFilteredHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase();
        while (h.startsWith(".")) h = h.substring(1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        synchronized (lock) {
            if (hosts.contains(h)) return true;
            String[] parts = h.split("\\.");
            for (int i = 1; i < parts.length; i++) {
                String parent = String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
                if (hosts.contains(parent)) return true;
            }
        }
        return false;
    }

    public ClientMode clientMode(String ip) {
        synchronized (lock) {
            return clientMode.getOrDefault(ip, ClientMode.UNKNOWN);
        }
    }

    public void setClientMode(String ip, ClientMode mode) {
        synchronized (lock) {
            clientMode.put(ip, mode);
        }
    }

    /** Combined check used at request time. */
    public boolean shouldApplyFilter(String clientIp, String host) {
        if (clientMode(clientIp) == ClientMode.UNFILTERED) return false;
        return isFilteredHost(host);
    }
}
