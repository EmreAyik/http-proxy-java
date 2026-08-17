import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Transparent HTTP handler.
 *
 * <p>The proxy listens on port 80. Clients reach it because DNS has
 * been poisoned to return the proxy's IP for every hostname. The real
 * target host is taken from the HTTP {@code Host:} header; the proxy
 * resolves the real IP via its own (un-poisoned) DNS, then relays the
 * request/response while applying caching, filtering, method checks
 * and logging.
 */
public final class HttpHandler {

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD", "OPTIONS", "POST");
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade");
    private static final int UPSTREAM_TIMEOUT_MS = 60_000;
    private static final int READ_CHUNK = 64 * 1024;
    static final String LOGIN_PATH = "/__proxy_login";

    private HttpHandler() {}

    public static void handle(Socket clientSock, ProxyContext ctx) {
        String clientIp = clientSock.getInetAddress().getHostAddress();
        try {
            clientSock.setSoTimeout(UPSTREAM_TIMEOUT_MS);
            InputStream in  = clientSock.getInputStream();
            OutputStream out = clientSock.getOutputStream();

            ParsedHeaders pr = readHeaders(in);
            if (pr == null) return;

            String[] rl = pr.requestLine.split(" ", 3);
            if (rl.length != 3) { sendSimple(out, 400, "Bad Request"); return; }
            String method  = rl[0].toUpperCase();
            String target  = rl[1];
            String version = rl[2];

            String hostHeader = pr.headers.getOrDefault("host", "").trim();
            HostPort hp = splitHost(hostHeader, 80);
            String host = hp.host;
            int port    = hp.port;
            String pathStr = target.startsWith("/") ? target : "/";

            // Bonus: token submission (intercept on any host).
            if (method.equals("POST") && pathStr.startsWith(LOGIN_PATH)) {
                handleLoginPost(in, out, clientIp, pr, ctx);
                return;
            }
            // Bonus: first-time client gets a login page (HTTP only).
            if (ctx.bonusLoginEnabled &&
                    ctx.filterStore.clientMode(clientIp) == FilterStore.ClientMode.UNKNOWN) {
                ctx.log.record(clientIp, host, pathStr, method, 200);
                sendHtml(out, 200, loginPageHtml(host, null));
                return;
            }

            if (ctx.filterStore.shouldApplyFilter(clientIp, host)) {
                ctx.log.record(clientIp, host, pathStr, method, 401);
                sendSimple(out, 401, "Unauthorized");
                return;
            }
            if (!ALLOWED_METHODS.contains(method)) {
                ctx.log.record(clientIp, host, pathStr, method, 405);
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("Allow", "GET, HEAD, OPTIONS, POST");
                sendSimple(out, 405, "Method Not Allowed", extra);
                return;
            }

            String fullUrl = "http://" + host + pathStr;
            DiskCache.Entry cacheEntry = null;
            if (method.equals("GET") || method.equals("HEAD")) {
                cacheEntry = ctx.cache.get(fullUrl);
            }

            Socket upstream;
            try {
                upstream = new Socket();
                upstream.connect(new InetSocketAddress(host, port), UPSTREAM_TIMEOUT_MS);
                upstream.setSoTimeout(UPSTREAM_TIMEOUT_MS);
            } catch (IOException e) {
                ctx.log.record(clientIp, host, pathStr, method, 502);
                sendSimple(out, 502, "Bad Gateway: " + e.getMessage());
                return;
            }

            try (upstream) {
                InputStream uIn  = upstream.getInputStream();
                OutputStream uOut = upstream.getOutputStream();

                // Build upstream request: drop hop-by-hop, force Connection: close, add cache validators.
                Map<String, String> upstreamHeaders = new LinkedHashMap<>(pr.headers);
                upstreamHeaders.remove("proxy-connection");
                upstreamHeaders.put("connection", "close");
                if (cacheEntry != null) {
                    upstreamHeaders.put("if-modified-since", cacheEntry.lastModified);
                    if (cacheEntry.etag != null) upstreamHeaders.put("if-none-match", cacheEntry.etag);
                }
                uOut.write(buildRequest(method, target, version, upstreamHeaders));
                if (pr.leftover.length > 0) uOut.write(pr.leftover);
                if (method.equals("POST")) relayRequestBody(in, uOut, pr.headers, pr.leftover);
                uOut.flush();

                ParsedHeaders resp = readHeaders(uIn);
                if (resp == null) { sendSimple(out, 502, "Empty upstream response"); return; }
                int status = parseStatus(resp.requestLine);

                // Cache validation success -> serve from cache.
                if (status == 304 && cacheEntry != null && method.equals("GET")) {
                    ctx.log.record(clientIp, host, pathStr, method, 200);
                    sendCached(out, cacheEntry, ctx.cache.bodyPath(fullUrl));
                    return;
                }
                ctx.log.record(clientIp, host, pathStr, method, status);

                Map<String, String> sanitized = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : resp.headers.entrySet()) {
                    if (!HOP_BY_HOP.contains(e.getKey().toLowerCase()))
                        sanitized.put(e.getKey(), e.getValue());
                }
                sanitized.put("connection", "close");

                // Cache any GET 200 with Last-Modified. Chunked bodies are
                // dechunked into the cache file and re-served with explicit length.
                boolean cacheable = method.equals("GET") && status == 200
                        && resp.headers.containsKey("last-modified");

                out.write(buildResponseHead(resp.requestLine, sanitized));

                if (cacheable) {
                    Path tmp = Files.createTempFile(ctx.cache.root(), "dl_", null);
                    try (OutputStream f = Files.newOutputStream(tmp, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                        if (resp.leftover.length > 0) {
                            f.write(resp.leftover);
                            out.write(resp.leftover);
                        }
                        streamBody(uIn, out, resp.headers, f);
                    }
                    ctx.cache.store(fullUrl, status, resp.headers, tmp);
                } else {
                    if (resp.leftover.length > 0) out.write(resp.leftover);
                    streamBody(uIn, out, resp.headers, null);
                }
                out.flush();
            }
        } catch (IOException ignore) {
            // Client disconnects, broken pipes - normal during browsing.
        } finally {
            try { clientSock.close(); } catch (IOException ignore) {}
        }
    }

    // ---------------- IO helpers ----------------

    static final class ParsedHeaders {
        final String requestLine;
        final Map<String, String> headers;  // lowercase keys
        final byte[] leftover;              // bytes already read past the headers
        ParsedHeaders(String rl, Map<String, String> h, byte[] lo) {
            this.requestLine = rl; this.headers = h; this.leftover = lo;
        }
    }

    static ParsedHeaders readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[READ_CHUNK];
        int blank;
        while ((blank = indexOf(buf.toByteArray(), new byte[]{13,10,13,10})) < 0) {
            int n = in.read(tmp);
            if (n < 0) {
                if (buf.size() == 0) return null;
                break;
            }
            buf.write(tmp, 0, n);
            if (buf.size() > 1 << 20) break; // 1 MB header cap
        }
        byte[] all = buf.toByteArray();
        int headerEnd = blank >= 0 ? blank : all.length;
        byte[] headBytes = new byte[headerEnd];
        System.arraycopy(all, 0, headBytes, 0, headerEnd);
        byte[] leftover;
        if (blank >= 0 && blank + 4 <= all.length) {
            leftover = new byte[all.length - (blank + 4)];
            System.arraycopy(all, blank + 4, leftover, 0, leftover.length);
        } else {
            leftover = new byte[0];
        }
        String head = new String(headBytes, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                String k = lines[i].substring(0, c).trim().toLowerCase();
                String v = lines[i].substring(c + 1).trim();
                headers.put(k, v);
            }
        }
        return new ParsedHeaders(lines[0], headers, leftover);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++)
                if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    static final class HostPort {
        final String host; final int port;
        HostPort(String h, int p) { this.host = h; this.port = p; }
    }

    private static HostPort splitHost(String hostHeader, int defaultPort) {
        if (hostHeader == null || hostHeader.isEmpty()) return new HostPort("", defaultPort);
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            if (end < 0) return new HostPort(hostHeader, defaultPort);
            String h = hostHeader.substring(1, end);
            String rest = hostHeader.substring(end + 1);
            if (rest.startsWith(":")) {
                try { return new HostPort(h, Integer.parseInt(rest.substring(1))); }
                catch (NumberFormatException e) { return new HostPort(h, defaultPort); }
            }
            return new HostPort(h, defaultPort);
        }
        int c = hostHeader.lastIndexOf(':');
        if (c < 0) return new HostPort(hostHeader, defaultPort);
        try {
            return new HostPort(hostHeader.substring(0, c), Integer.parseInt(hostHeader.substring(c + 1)));
        } catch (NumberFormatException e) {
            return new HostPort(hostHeader, defaultPort);
        }
    }

    private static Integer contentLength(Map<String, String> headers) {
        String v = headers.get("content-length");
        if (v == null) return null;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
    }

    private static int parseStatus(String statusLine) {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) return 0;
        try { return Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return 0; }
    }

    private static byte[] buildRequest(String method, String target, String version,
                                       Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(target).append(' ').append(version).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(titleCase(e.getKey())).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] buildResponseHead(String statusLine, Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        sb.append(statusLine).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(titleCase(e.getKey())).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = (c == '-');
        }
        return sb.toString();
    }

    // ---------------- body relay ----------------

    private static void relayRequestBody(InputStream in, OutputStream out,
                                         Map<String, String> headers, byte[] peek) throws IOException {
        Integer cl = contentLength(headers);
        if (cl != null) {
            int remaining = cl - peek.length;
            byte[] buf = new byte[READ_CHUNK];
            while (remaining > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) return;
                out.write(buf, 0, n);
                remaining -= n;
            }
            return;
        }
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            streamChunked(in, out, null, peek);
        }
    }

    static void streamBody(InputStream src, OutputStream dst,
                           Map<String, String> headers, OutputStream mirror) throws IOException {
        String te = headers.getOrDefault("transfer-encoding", "");
        Integer cl = contentLength(headers);
        if ("chunked".equalsIgnoreCase(te)) {
            streamChunked(src, dst, mirror, new byte[0]);
            return;
        }
        byte[] buf = new byte[READ_CHUNK];
        if (cl != null) {
            int remaining = cl;
            while (remaining > 0) {
                int n;
                try { n = src.read(buf, 0, Math.min(buf.length, remaining)); }
                catch (IOException e) { break; }
                if (n < 0) break;
                dst.write(buf, 0, n);
                if (mirror != null) mirror.write(buf, 0, n);
                remaining -= n;
            }
            return;
        }
        // No Content-Length: read until upstream closes.
        while (true) {
            int n;
            try { n = src.read(buf); } catch (IOException e) { break; }
            if (n < 0) break;
            dst.write(buf, 0, n);
            if (mirror != null) mirror.write(buf, 0, n);
        }
    }

    private static void streamChunked(InputStream src, OutputStream dst,
                                      OutputStream mirror, byte[] prefill) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (prefill.length > 0) buf.write(prefill);
        byte[] tmp = new byte[READ_CHUNK];
        while (true) {
            int crlf;
            while ((crlf = indexOf(buf.toByteArray(), new byte[]{13,10})) < 0) {
                int n; try { n = src.read(tmp); } catch (IOException e) { return; }
                if (n < 0) { if (buf.size() > 0) dst.write(buf.toByteArray()); return; }
                buf.write(tmp, 0, n);
            }
            byte[] all = buf.toByteArray();
            byte[] sizeLine = new byte[crlf];
            System.arraycopy(all, 0, sizeLine, 0, crlf);
            byte[] rest = new byte[all.length - (crlf + 2)];
            System.arraycopy(all, crlf + 2, rest, 0, rest.length);
            buf.reset();
            buf.write(rest);

            dst.write(sizeLine);
            dst.write(new byte[]{13,10});
            String sizeStr = new String(sizeLine, StandardCharsets.ISO_8859_1);
            int semi = sizeStr.indexOf(';');
            if (semi >= 0) sizeStr = sizeStr.substring(0, semi);
            int chunkSize;
            try { chunkSize = Integer.parseInt(sizeStr.trim(), 16); }
            catch (NumberFormatException e) { return; }

            if (chunkSize == 0) {
                // Trailer block ends with \r\n. Read until we see \r\n at the start of buf.
                while (buf.size() < 2 || !startsWith(buf.toByteArray(), new byte[]{13,10})) {
                    if (indexOf(buf.toByteArray(), new byte[]{13,10,13,10}) >= 0) break;
                    int n; try { n = src.read(tmp); } catch (IOException e) { break; }
                    if (n < 0) break;
                    buf.write(tmp, 0, n);
                }
                dst.write(buf.toByteArray());
                return;
            }

            int needed = chunkSize + 2;
            while (buf.size() < needed) {
                int n; try { n = src.read(tmp); } catch (IOException e) { return; }
                if (n < 0) return;
                buf.write(tmp, 0, n);
            }
            byte[] all2 = buf.toByteArray();
            dst.write(all2, 0, needed);
            if (mirror != null) mirror.write(all2, 0, chunkSize);
            byte[] leftover = new byte[all2.length - needed];
            System.arraycopy(all2, needed, leftover, 0, leftover.length);
            buf.reset();
            buf.write(leftover);
        }
    }

    private static boolean startsWith(byte[] arr, byte[] prefix) {
        if (arr.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (arr[i] != prefix[i]) return false;
        return true;
    }

    // ---------------- canned responses ----------------

    private static void sendSimple(OutputStream out, int status, String reason) {
        sendSimple(out, status, reason, null);
    }

    private static void sendSimple(OutputStream out, int status, String reason,
                                   Map<String, String> extraHeaders) {
        byte[] body = ("<html><body><h1>" + status + " " + reason + "</h1></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/html; charset=utf-8");
        headers.put("Content-Length", String.valueOf(body.length));
        headers.put("Connection", "close");
        if (extraHeaders != null) headers.putAll(extraHeaders);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet())
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        try {
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException ignore) {}
    }

    static void sendHtml(OutputStream out, int status, String html) {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try {
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException ignore) {}
    }

    private static void sendCached(OutputStream out, DiskCache.Entry entry, Path bodyPath) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entry.headers.entrySet()) {
            String k = e.getKey().toLowerCase();
            if (HOP_BY_HOP.contains(k) || k.equals("transfer-encoding")) continue;
            headers.put(e.getKey(), e.getValue());
        }
        long size = 0;
        try { if (Files.exists(bodyPath)) size = Files.size(bodyPath); } catch (IOException ignore) {}
        headers.put("Content-Length", String.valueOf(size));
        headers.put("Connection", "close");
        headers.put("X-Proxy-Cache", "HIT");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(entry.status).append(" OK\r\n");
        for (Map.Entry<String, String> e : headers.entrySet())
            sb.append(titleCase(e.getKey())).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        try {
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (Files.exists(bodyPath)) {
                try (InputStream f = Files.newInputStream(bodyPath)) {
                    byte[] buf = new byte[READ_CHUNK];
                    int n;
                    while ((n = f.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            out.flush();
        } catch (IOException ignore) {}
    }

    // ---------------- bonus: token login ----------------

    private static void handleLoginPost(InputStream in, OutputStream out, String clientIp,
                                        ParsedHeaders pr, ProxyContext ctx) throws IOException {
        Integer cl = contentLength(pr.headers);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(pr.leftover);
        if (cl != null) {
            byte[] tmp = new byte[READ_CHUNK];
            while (body.size() < cl) {
                int n = in.read(tmp, 0, Math.min(tmp.length, cl - body.size()));
                if (n < 0) break;
                body.write(tmp, 0, n);
            }
        }
        Map<String, String> form = parseForm(body.toString(StandardCharsets.UTF_8));
        String token = form.getOrDefault("token", "").trim();
        String msg;
        if (FilterStore.TOKEN_DISABLE_FILTER.equals(token)) {
            ctx.filterStore.setClientMode(clientIp, FilterStore.ClientMode.UNFILTERED);
            msg = "Filtering disabled for this client.";
        } else if (FilterStore.TOKEN_ENABLE_FILTER.equals(token)) {
            ctx.filterStore.setClientMode(clientIp, FilterStore.ClientMode.FILTERED);
            msg = "Filtering enabled for this client.";
        } else {
            sendHtml(out, 200, loginPageHtml("", "Invalid token. Please try again."));
            return;
        }
        String html = "<!doctype html><html><body style='font-family:sans-serif;padding:2em'>"
                + "<h2>" + msg + "</h2>"
                + "<p>You can now close this page and browse normally.</p>"
                + "</body></html>";
        sendHtml(out, 200, html);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.put(k, v);
            } catch (IllegalArgumentException ignore) {}
        }
        return out;
    }

    static String loginPageHtml(String host, String error) {
        String safeHost = (host == null ? "" : host).replace("<", "&lt;").replace(">", "&gt;");
        String err = error == null ? "" : "<p style='color:#b00'>" + error + "</p>";
        return "<!doctype html>"
                + "<html><head><meta charset=\"utf-8\"><title>Proxy Authentication</title></head>"
                + "<body style=\"font-family:sans-serif;max-width:480px;margin:3em auto;padding:1em\">"
                + "<h2>Transparent Proxy - Token Required</h2>"
                + "<p>You are browsing through the campus proxy. Please enter your access "
                + "token to continue. (Original host: <code>" + safeHost + "</code>)</p>"
                + err
                + "<form method=\"POST\" action=\"" + LOGIN_PATH + "\">"
                + "<input type=\"text\" name=\"token\" placeholder=\"token\" autofocus"
                + " style=\"width:100%;padding:.6em;font-size:1em\"/>"
                + "<button type=\"submit\" style=\"margin-top:1em;padding:.6em 1.2em\">Submit</button>"
                + "</form></body></html>";
    }
}
