import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk HTTP response cache.
 *
 * <p>For every cached URL, two files are written under {@code root/}:
 * <pre>
 *   &lt;sha256&gt;.meta  – plain-text metadata (key=value, one per line)
 *   &lt;sha256&gt;.body  – the raw response body (de-chunked if needed)
 * </pre>
 * Only responses with a {@code Last-Modified} header are cached, per the
 * assignment spec.
 */
public final class DiskCache {

    public static final class Entry {
        public final String url;
        public final int status;
        public final String lastModified;
        public final String etag;
        public final Map<String, String> headers;
        Entry(String url, int status, String lm, String etag, Map<String, String> headers) {
            this.url = url;
            this.status = status;
            this.lastModified = lm;
            this.etag = etag;
            this.headers = headers;
        }
    }

    private final Path root;
    private final Object lock = new Object();

    public DiskCache(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    public Path root() { return root; }

    private String key(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Path bodyPath(String url) { return root.resolve(key(url) + ".body"); }
    private Path metaPath(String url) { return root.resolve(key(url) + ".meta"); }

    public Entry get(String url) {
        Path m = metaPath(url);
        if (!Files.exists(m)) return null;
        try {
            String u = null, lm = null, etag = null;
            int status = 200;
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line : Files.readAllLines(m, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq);
                String v = unescape(line.substring(eq + 1));
                switch (k) {
                    case "url"            -> u = v;
                    case "status"         -> status = Integer.parseInt(v);
                    case "last_modified"  -> lm = v;
                    case "etag"           -> etag = v.isEmpty() ? null : v;
                    default               -> {
                        if (k.startsWith("h.")) headers.put(k.substring(2), v);
                    }
                }
            }
            return new Entry(u, status, lm, etag, headers);
        } catch (IOException e) {
            return null;
        }
    }

    /** Move {@code bodyFile} into the cache and write metadata. */
    public void store(String url, int status, Map<String, String> headers, Path bodyFile) {
        String lm = lookup(headers, "last-modified");
        String etag = lookup(headers, "etag");
        if (lm == null) {
            try { Files.deleteIfExists(bodyFile); } catch (IOException ignore) {}
            return;
        }
        synchronized (lock) {
            try {
                Path dest = bodyPath(url);
                Files.move(bodyFile, dest, StandardCopyOption.REPLACE_EXISTING);
                try (BufferedWriter w = Files.newBufferedWriter(metaPath(url), StandardCharsets.UTF_8)) {
                    w.write("url=" + escape(url)); w.write('\n');
                    w.write("status=" + status);   w.write('\n');
                    w.write("last_modified=" + escape(lm)); w.write('\n');
                    w.write("etag=" + escape(etag == null ? "" : etag)); w.write('\n');
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        w.write("h." + e.getKey().toLowerCase()); w.write('='); w.write(escape(e.getValue())); w.write('\n');
                    }
                }
            } catch (IOException ignore) {
                try { Files.deleteIfExists(bodyFile); } catch (IOException ignored) {}
            }
        }
    }

    private static String lookup(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }
}
