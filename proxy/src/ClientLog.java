import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-client request log. One line per request, TAB separated:
 * <pre>date\tclient_ip\thost\tpath\tmethod\tstatus</pre>
 * Fields that are not visible (HTTPS path / status) are written as {@code "-"}.
 */
public final class ClientLog {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Object lock = new Object();
    private final Path path;
    private final List<String> entries = new ArrayList<>();

    public ClientLog(Path path) {
        this.path = path;
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) entries.addAll(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (IOException ignore) {}
    }

    public void record(String clientIp, String host, String pathStr,
                       String method, Integer status) {
        String line = String.join("\t",
                LocalDateTime.now().format(FMT),
                nullSafe(clientIp),
                nullSafe(host),
                nullSafe(pathStr),
                nullSafe(method),
                status == null ? "-" : status.toString());
        synchronized (lock) {
            entries.add(line);
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
                w.write('\n');
            } catch (IOException ignore) {}
        }
    }

    public List<String> reportFor(String clientIp) {
        List<String> out = new ArrayList<>();
        synchronized (lock) {
            for (String e : entries) {
                String[] parts = e.split("\t", 3);
                if (parts.length >= 2 && parts[1].equals(clientIp)) out.add(e);
            }
        }
        return out;
    }

    /** Writes the per-client report to {@code outPath} and returns the line count. */
    public int saveReport(String clientIp, Path outPath) throws IOException {
        List<String> lines = reportFor(clientIp);
        if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        Files.writeString(outPath, sb.toString(), StandardCharsets.UTF_8);
        return lines.size();
    }

    private static String nullSafe(String s) { return s == null || s.isEmpty() ? "-" : s; }
}
