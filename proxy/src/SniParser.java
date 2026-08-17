import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parses a TLS ClientHello record from an {@link InputStream} and
 * extracts the Server Name Indication (SNI) hostname. The raw bytes
 * that were consumed are returned alongside the hostname so the caller
 * can forward them verbatim to the upstream server.
 */
public final class SniParser {

    public static final class Result {
        public final byte[] raw;
        public final String sni; // may be null if the ClientHello had no SNI
        public Result(byte[] raw, String sni) { this.raw = raw; this.sni = sni; }
    }

    /** Read exactly {@code n} bytes from {@code in} or throw EOFException. */
    private static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) throw new IOException("connection closed while reading TLS record");
            read += r;
        }
        return buf;
    }

    public static Result readClientHello(InputStream in) throws IOException {
        byte[] header = readExact(in, 5);
        if ((header[0] & 0xFF) != 0x16) {
            throw new IOException("not a TLS handshake record (type=0x"
                    + Integer.toHexString(header[0] & 0xFF) + ")");
        }
        int recLen = ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
        byte[] body = readExact(in, recLen);
        byte[] raw = new byte[5 + recLen];
        System.arraycopy(header, 0, raw, 0, 5);
        System.arraycopy(body, 0, raw, 5, recLen);
        String sni = null;
        try {
            sni = extractSni(body);
        } catch (Exception ignore) {
            // malformed extension - we still have the raw bytes to forward
        }
        return new Result(raw, sni);
    }

    private static String extractSni(byte[] body) {
        if (body.length < 4 || (body[0] & 0xFF) != 0x01) return null; // handshake type = ClientHello
        // handshake length (3 bytes) deliberately ignored - we trust the record length
        ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        b.position(4);
        // client_version (2) + random (32)
        if (b.remaining() < 34) return null;
        b.position(b.position() + 34);
        // session id
        if (!b.hasRemaining()) return null;
        int sidLen = b.get() & 0xFF;
        if (b.remaining() < sidLen) return null;
        b.position(b.position() + sidLen);
        // cipher suites
        if (b.remaining() < 2) return null;
        int csLen = b.getShort() & 0xFFFF;
        if (b.remaining() < csLen) return null;
        b.position(b.position() + csLen);
        // compression methods
        if (!b.hasRemaining()) return null;
        int cmLen = b.get() & 0xFF;
        if (b.remaining() < cmLen) return null;
        b.position(b.position() + cmLen);
        // extensions
        if (b.remaining() < 2) return null;
        int extTotal = b.getShort() & 0xFFFF;
        int end = b.position() + extTotal;
        if (end > body.length) end = body.length;
        while (b.position() + 4 <= end) {
            int extType = b.getShort() & 0xFFFF;
            int extLen = b.getShort() & 0xFFFF;
            int extStart = b.position();
            if (extType == 0x0000) { // server_name
                if (b.remaining() < 2) return null;
                int listLen = b.getShort() & 0xFFFF;
                int listEnd = b.position() + listLen;
                while (b.position() + 3 <= listEnd) {
                    int nameType = b.get() & 0xFF;
                    int nameLen = b.getShort() & 0xFFFF;
                    if (b.remaining() < nameLen) return null;
                    byte[] nameBytes = new byte[nameLen];
                    b.get(nameBytes);
                    if (nameType == 0x00) {
                        return new String(nameBytes, java.nio.charset.StandardCharsets.US_ASCII);
                    }
                }
                return null;
            }
            b.position(extStart + extLen);
        }
        return null;
    }

    private SniParser() {}
}
