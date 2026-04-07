package pj2;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;


public class ProxyServer {

    // ── Where DataServer lives ─────────────────────────────────────────
    static final String DATA_HOST = "localhost";
    /** UDP port where DataServer listens (default 8069; override with --data-port=). */
    int dataPort = 8069;
    static final int    BLKSIZE   = 1024;
    static final int    MAX_PKT   = 65535;

    // ── Packet opcodes ────────────────────────────────────────────────
    static final int OP_RRQ           = 1;
    static final int OP_DATA          = 3;
    static final int OP_ACK           = 4;
    static final int OP_ERROR         = 5;
    static final int OP_HANDSHAKE     = 11;
    static final int OP_HANDSHAKE_ACK = 12;

    // ── Runtime config ────────────────────────────────────────────────
    int    httpPort   = 8080;
    int    windowSize = 8;
    double dropRate   = 0.0;

    /** Set when tftpFetch fails so the 502 page can explain why. */
    volatile String lastFetchDetail = "";

    static final int CACHE_MAX = 10;

    static class CachedFile {
        final byte[] data;
        final String mime;
        CachedFile(byte[] data, String mime) { this.data = data; this.mime = mime; }
    }

    final Map<String, CachedFile> cache = Collections.synchronizedMap(
        new LinkedHashMap<String, CachedFile>(CACHE_MAX, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,CachedFile> e) {
                boolean evict = size() > CACHE_MAX;
                if (evict) System.out.println("[Cache] Evicted: " + e.getKey());
                return evict;
            }
        }
    );

    // ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        ProxyServer proxy = new ProxyServer();
        for (String a : args) {
            if (a.equals("--drop"))         proxy.dropRate   = 0.01;
            if (a.startsWith("--window="))  proxy.windowSize = Integer.parseInt(a.substring(9));
            if (a.startsWith("--port="))    proxy.httpPort   = Integer.parseInt(a.substring(7));
            if (a.startsWith("--data-port=")) proxy.dataPort = Integer.parseInt(a.substring(12));
        }
        proxy.run();
    }

    void run() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket ss = new ServerSocket(httpPort)) {
            System.out.println("ProxyServer (HTTP) — this is your web server; no Python needed.");
            System.out.println("  Listening: http://localhost:" + httpPort);
            System.out.println("  → DataServer must be running: UDP " + DATA_HOST + ":" + dataPort);
            System.out.println("  → Window size: " + windowSize);
            System.out.println("  → Packet drop: " + (dropRate > 0 ? "1% ON" : "OFF"));
            System.out.println();
            System.out.println("In a BROWSER use this host (not http://picture.png):");
            System.out.println("  http://localhost:" + httpPort + "/yourfile.png");
            System.out.println("  (put files in the ./photos/ folder next to DataServer)");
            System.out.println("Also: http://localhost:" + httpPort + "/throughput");

            while (true) {
                Socket client = ss.accept();
                pool.submit(() -> handleHttpClient(client));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  HTTP: handle one browser/client connection
    // ─────────────────────────────────────────────────────────────────
    void handleHttpClient(Socket client) {
        try (client;
             BufferedReader in  = new BufferedReader(
                 new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
             OutputStream   out = client.getOutputStream()) {

            // Read the first line of the HTTP request, e.g.:
            //   "GET /display.PNG HTTP/1.1"
            //   "GET http://localhost:8080/display.PNG HTTP/1.1"  (absolute form)
            String line = in.readLine();
            if (line == null || line.isBlank()) return;

            // Drain the rest of the headers (we don't need them)
            String h;
            while ((h = in.readLine()) != null && !h.isBlank()) {}

            String[] parts  = line.split("\\s+");
            String   method = parts.length > 0 ? parts[0] : "GET";
            String   path   = parts.length > 1 ? parseRequestTarget(parts[1]) : "/";

            // ── Special API endpoints ──────────────────────────────────
            if (path.equals("/api/cache")) {
                sendHtml(out, buildCachePage());
                return;
            }
            if (path.equals("/throughput") || path.equals("/results")) {
                sendHtml(out, buildThroughputPage());
                return;
            }

            // ── Regular file request ───────────────────────────────────
            if (path.equals("/")) path = "/index.html";
            serveFile(out, path);

        } catch (Exception e) {
            System.err.println("[Proxy] HTTP handler failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Path only: strips {@code ?query} and {@code #fragment}, handles
     * absolute request-URLs, percent-decoding. Wrong MIME / empty responses
     * in browsers often come from a raw {@code /file.png?...} path.
     */
    static String parseRequestTarget(String raw) {
        if (raw == null || raw.isEmpty()) return "/";
        int cut = raw.length();
        int q = raw.indexOf('?');
        int h = raw.indexOf('#');
        if (q >= 0) cut = Math.min(cut, q);
        if (h >= 0) cut = Math.min(cut, h);
        String s = raw.substring(0, cut);
        if (s.isEmpty()) return "/";
        if (s.startsWith("http://") || s.startsWith("https://")) {
            try {
                URI u = new URI(s);
                String p = u.getPath();
                s = (p == null || p.isEmpty()) ? "/" : p;
            } catch (URISyntaxException e) {
                return "/";
            }
        }
        try {
            s = URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) { /* malformed % */ }
        if (!s.startsWith("/")) s = "/" + s;
        return s.isEmpty() ? "/" : s;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Serve a file: check cache first, then fetch from DataServer
    // ─────────────────────────────────────────────────────────────────
    void serveFile(OutputStream out, String filePath) throws Exception {
        // Check the cache first
        CachedFile cached;
        synchronized (cache) { cached = cache.get(filePath); }

        if (cached != null) {
            System.out.println("[Proxy] Cache HIT: " + filePath);
            sendBytes(out, 200, cached.mime, cached.data, "HIT");
            return;
        }

        // Cache MISS → fetch from DataServer via TFTP+XOR
        System.out.println("[Proxy] Cache MISS: " + filePath + " → fetching from DataServer...");
        CachedFile fetched = tftpFetch(filePath);

        if (fetched == null) {
            String msg = "<h1>502 Bad Gateway</h1><p>Could not fetch: " + esc(filePath) + "</p>";
            if (lastFetchDetail != null && !lastFetchDetail.isEmpty()) {
                msg += "<p style='color:#c00'><b>Reason:</b> " + esc(lastFetchDetail) + "</p>";
            }
            msg += "<p>Check: (1) <code>DataServer</code> is running on UDP " + dataPort
                + ", (2) put the file in <code>Project2/photos/</code> or <code>demo/photos/</code> "
                + "(names can differ by case: <code>display.PNG</code> works for <code>/display.png</code>).</p>";
            sendHtml(out, msg);
            return;
        }

        // Store in cache and respond
        synchronized (cache) { cache.put(filePath, fetched); }
        System.out.println("[Proxy] Fetched and cached: " + filePath
            + " (" + fetched.data.length + " bytes)");
        sendBytes(out, 200, fetched.mime, fetched.data, "MISS");
    }

    // ─────────────────────────────────────────────────────────────────
    //  TFTP+XOR client: fetch one file from DataServer
    // ─────────────────────────────────────────────────────────────────
    CachedFile tftpFetch(String filePath) {
        lastFetchDetail = "";
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(5000);
            InetAddress ds = InetAddress.getByName(DATA_HOST);

            // ── Step 1: Send RRQ ───────────────────────────────────────
            byte[] rrq = buildRRQ(filePath, windowSize);
            sock.send(new DatagramPacket(rrq, rrq.length, ds, dataPort));

            byte[]         buf = new byte[MAX_PKT];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);

            // ── Step 2: Receive HANDSHAKE ──────────────────────────────
            //  The DataServer replies from a NEW ephemeral port (its session port).
            //  We must remember that port — all future packets for THIS session
            //  come from it.
            sock.receive(pkt);
            int sessionPort = pkt.getPort();   // DataServer's session port

            if (opcode(buf) == OP_ERROR) {
                lastFetchDetail = errorMsg(buf, pkt.getLength());
                System.err.println("[Proxy] Server error: " + lastFetchDetail);
                return null;
            }
            if (opcode(buf) != OP_HANDSHAKE) {
                lastFetchDetail = "Expected HANDSHAKE, got opcode " + opcode(buf);
                System.err.println("[Proxy] " + lastFetchDetail);
                return null;
            }

            long serverNonce = nonce(buf);

            // ── Step 3: Send HANDSHAKE_ACK with our own nonce ──────────
            SecureRandom rng   = new SecureRandom();
            long myNonce       = rng.nextLong();
            byte[] ack = buildHandshake(OP_HANDSHAKE_ACK, rng.nextInt(), myNonce);
            sock.send(new DatagramPacket(ack, ack.length, ds, sessionPort));

            // Both sides compute the same key: serverNonce XOR myNonce
            long xorKey = serverNonce ^ myNonce;
            if (xorKey == 0) xorKey = 0xDEADBEEFL;
            System.out.printf("[Proxy] Session key: 0x%016X%n", xorKey);

            // ── Step 4: Receive DATA blocks ────────────────────────────
            //  We use a sliding window receiver:
            //   - Keep a buffer of out-of-order blocks
            //   - Send cumulative ACK = highest in-order block received
            //   - Reassemble blocks in order into the output stream
            //
            ByteArrayOutputStream assembled = new ByteArrayOutputStream();
            TreeMap<Integer,byte[]> outOfOrder = new TreeMap<>();
            int  nextExpected = 1;
            int  retransmits  = 0;
            double rto        = 1000;
            double srtt       = -1, rttvar = 0;
            Map<Integer,Long> sentAck = new HashMap<>();
            boolean done      = false;

            while (!done) {
                sock.setSoTimeout((int) Math.max(200, rto));
                pkt = new DatagramPacket(buf, buf.length);

                try {
                    sock.receive(pkt);
                    // Optional 1% loss on receive (same flag as DataServer --drop)
                    if (dropRate > 0 && Math.random() < dropRate) {
                        continue;
                    }
                } catch (SocketTimeoutException e) {
                    // Re-send cumulative ACK so DataServer knows where we stopped
                    rto = Math.min(60000, rto * 2);
                    retransmits++;
                    byte[] cumAck = buildAck(nextExpected - 1);
                    sock.send(new DatagramPacket(cumAck, cumAck.length, ds, sessionPort));
                    continue;
                }

                // Ignore packets from wrong port (stale from previous session)
                if (pkt.getPort() != sessionPort) continue;

                if (opcode(buf) == OP_ERROR) {
                    lastFetchDetail = errorMsg(buf, pkt.getLength());
                    System.err.println("[Proxy] TFTP error: " + lastFetchDetail);
                    return null;
                }
                if (opcode(buf) != OP_DATA) continue;

                int seqno = seqno(buf);

                // Only accept blocks within our window
                if (seqno < nextExpected || seqno >= nextExpected + windowSize) {
                    // Out-of-window: send a duplicate ACK to help the server
                    byte[] dupAck = buildAck(nextExpected - 1);
                    sock.send(new DatagramPacket(dupAck, dupAck.length, ds, sessionPort));
                    continue;
                }

                // Extract and DECRYPT the payload
                int    payloadLen = pkt.getLength() - 4;
                byte[] encrypted  = Arrays.copyOfRange(buf, 4, 4 + payloadLen);
                int    blockIndex = seqno - 1;   // 0-based for encryption
                byte[] plain      = xorDecrypt(encrypted, xorKey, blockIndex);

                // Buffer this block (may be out of order)
                outOfOrder.put(seqno, plain);

                // Drain any consecutive in-order blocks into the output
                while (!outOfOrder.isEmpty() && outOfOrder.firstKey() == nextExpected) {
                    assembled.write(outOfOrder.remove(nextExpected));
                    nextExpected++;
                }

                // Update RTT estimate
                Long t = sentAck.get(seqno);
                if (t != null) {
                    double sample = System.currentTimeMillis() - t;
                    if (srtt < 0) { srtt = sample; rttvar = sample / 2.0; }
                    else {
                        rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - sample);
                        srtt   = 0.875 * srtt   + 0.125 * sample;
                    }
                    rto = Math.min(60000, Math.max(200, srtt + 4 * rttvar));
                }

                // Send cumulative ACK
                byte[] cumAck = buildAck(nextExpected - 1);
                sentAck.put(nextExpected - 1, System.currentTimeMillis());
                sock.send(new DatagramPacket(cumAck, cumAck.length, ds, sessionPort));

                // RFC 1350: the last block is shorter than BLKSIZE
                if (payloadLen < BLKSIZE) done = true;
            }

            byte[] data = assembled.toByteArray();
            System.out.printf("[Proxy] Received %d bytes  retransmits=%d%n",
                data.length, retransmits);

            // ── Step 5: Validate — write to /tmp and compare ──────────
            validate(filePath, data);

            return new CachedFile(data, mimeFor(filePath));

        } catch (Exception e) {
            lastFetchDetail = e.getMessage() != null ? e.getMessage() : e.toString();
            System.err.println("[Proxy] Fetch failed: " + lastFetchDetail);
            return null;
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Same rules as DataServer: {@code photos/} or {@code ../photos/}, case-insensitive name. */
    static File findOriginalForCompare(String name) {
        for (String root : new String[] { "../photos", "photos" }) {
            File exact = new File(root, name);
            if (exact.isFile()) return exact;
            File dir = new File(root);
            if (!dir.isDirectory()) continue;
            String[] list = dir.list();
            if (list == null) continue;
            String want = name.toLowerCase(Locale.ROOT);
            for (String n : list) {
                if (n != null && n.toLowerCase(Locale.ROOT).equals(want)) {
                    return new File(dir, n);
                }
            }
        }
        return new File("photos", name);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Validate by writing to /tmp and comparing with the source
    // ─────────────────────────────────────────────────────────────────
    static void validate(String path, byte[] data) {
        try {
            String fname = path.replaceAll("^/+", "").replace('/', '_');
            Path   dir   = Paths.get("/tmp/proxy_recv");
            Files.createDirectories(dir);
            Path   tmp   = dir.resolve(fname);
            Files.write(tmp, data);

            String baseName = path.replaceAll("^/+", "");
            File orig = findOriginalForCompare(baseName);
            if (!orig.isFile()) {
                System.out.println("[Validate] No source file to compare (looked in photos/ and ../photos/).");
                return;
            }
            int rc = new ProcessBuilder("cmp", "--silent",
                         orig.getAbsolutePath(), tmp.toString())
                     .redirectErrorStream(true).start().waitFor();
            System.out.println("[Validate] " + (rc == 0 ? "✓ MATCH" : "✗ MISMATCH")
                + "  " + tmp);
        } catch (Exception e) {
            System.err.println("[Validate] " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  XOR Decrypt  (identical to encrypt — XOR is its own inverse)
    // ─────────────────────────────────────────────────────────────────
    static byte[] xorDecrypt(byte[] input, long masterKey, int blockIndex) {
        long   seed   = masterKey ^ ((long) blockIndex << 16);
        if (seed == 0) seed = 0xCAFEBABEL;
        byte[] output = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            seed ^= (seed << 13);
            seed ^= (seed >>> 7);
            seed ^= (seed << 17);
            output[i] = (byte) (input[i] ^ seed);
        }
        return output;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Packet builders
    // ─────────────────────────────────────────────────────────────────

    /** Build an RRQ packet with windowsize option. */
    static byte[] buildRRQ(String filename, int winSize) {
        String s = "\u0000\u0001" + filename + "\u0000octet\u0000windowsize\u0000" + winSize + "\u0000";
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Build a 4-byte ACK: [0x00][0x04][blockHi][blockLo] */
    static byte[] buildAck(int blockNum) {
        return new byte[]{0, (byte) OP_ACK,
                          (byte)(blockNum >> 8), (byte)(blockNum & 0xFF)};
    }

    /** Build a HANDSHAKE or HANDSHAKE_ACK packet: 14 bytes. */
    static byte[] buildHandshake(int opcode, int id, long nonce) {
        byte[] b = new byte[14];
        b[0] = 0; b[1] = (byte) opcode;
        b[2] = (byte)(id >> 24); b[3] = (byte)(id >> 16);
        b[4] = (byte)(id >>  8); b[5] = (byte) id;
        for (int i = 0; i < 8; i++) b[6 + i] = (byte)(nonce >> (56 - 8*i));
        return b;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Packet parsers
    // ─────────────────────────────────────────────────────────────────

    static int opcode(byte[] buf) {
        return ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
    }
    static int seqno(byte[] buf) {
        return ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
    }
    static long nonce(byte[] buf) {
        long n = 0;
        for (int i = 0; i < 8; i++) n = (n << 8) | (buf[6 + i] & 0xFF);
        return n;
    }
    static String errorMsg(byte[] buf, int len) {
        return len > 4 ? new String(buf, 4, len - 5) : "(no message)";
    }

    // ─────────────────────────────────────────────────────────────────
    //  HTTP response helpers
    // ─────────────────────────────────────────────────────────────────

    /** Send a byte array as an HTTP response. */
    void sendBytes(OutputStream out, int status, String mime,
                   byte[] body, String cacheStatus) throws IOException {
        StringBuilder hdr = new StringBuilder();
        hdr.append("HTTP/1.1 ").append(status).append(" OK\r\n");
        hdr.append("Content-Type: ").append(mime).append("\r\n");
        hdr.append("Content-Length: ").append(body.length).append("\r\n");
        hdr.append("X-Cache: ").append(cacheStatus).append("\r\n");
        hdr.append("Access-Control-Allow-Origin: *\r\n");
        if (mime != null && mime.startsWith("image/")) {
            hdr.append("Content-Disposition: inline\r\n");
        }
        hdr.append("Connection: close\r\n\r\n");
        out.write(hdr.toString().getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    /** Send an HTML string as an HTTP response. */
    void sendHtml(OutputStream out, String html) throws IOException {
        sendBytes(out, 200, "text/html; charset=utf-8",
                  html.getBytes("UTF-8"), "N/A");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Cache status page (served at /api/cache)
    // ─────────────────────────────────────────────────────────────────
    String buildCachePage() {
        StringBuilder sb = new StringBuilder(
            "<html><head><title>Proxy Cache</title>" +
            "<style>body{font-family:monospace;background:#111;color:#eee;padding:2rem}" +
            "table{border-collapse:collapse;width:100%}" +
            "th,td{border:1px solid #444;padding:8px;text-align:left}" +
            "th{background:#222}</style></head><body>" +
            "<h2>Proxy Cache Contents</h2><table>" +
            "<tr><th>Path</th><th>Size (bytes)</th><th>MIME</th></tr>");
        synchronized (cache) {
            if (cache.isEmpty()) {
                sb.append("<tr><td colspan=3>(empty)</td></tr>");
            } else {
                for (Map.Entry<String, CachedFile> e : cache.entrySet()) {
                    sb.append("<tr><td><a href='").append(e.getKey())
                      .append("' style='color:#7af'>").append(e.getKey()).append("</a></td>")
                      .append("<td>").append(e.getValue().data.length).append("</td>")
                      .append("<td>").append(e.getValue().mime).append("</td></tr>");
                }
            }
        }
        sb.append("</table><br><a href='/api/cache'>Refresh</a> · <a href='/throughput'>Throughput</a></body></html>");
        return sb.toString();
    }

    /** Demo throughput table: fill kbps from DataServer console lines when you benchmark. */
    String buildThroughputPage() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>TFTP proxy throughput</title>"
            + "<style>body{font-family:system-ui,Segoe UI,sans-serif;background:#1a1a2e;color:#eee;max-width:960px;margin:2rem auto;padding:0 1rem}"
            + "table{border-collapse:collapse;width:100%;margin:1rem 0}th,td{border:1px solid #444;padding:10px;text-align:left}"
            + "th{background:#16213e}caption{text-align:left;font-weight:bold;margin-bottom:.5rem}"
            + "code{background:#0f3460;padding:2px 6px;border-radius:4px}</style></head><body>"
            + "<h1>Throughput (demo)</h1>"
            + "<p>Run pairs: <code>DataServer</code> + <code>ProxyServer --window=N [--drop]</code>, fetch a file with <code>ClientTFTP</code>. "
            + "Copy the <strong>kbps</strong> number from the DataServer console into the table below for your report.</p>"
            + "<table>"
            + "<caption>Host pair A — same machine (localhost)</caption>"
            + "<tr><th>Window</th><th>No drop (kbps)</th><th>1% drop (kbps)</th></tr>"
            + "<tr><td>1</td><td>—</td><td>—</td></tr>"
            + "<tr><td>8</td><td>—</td><td>—</td></tr>"
            + "<tr><td>64</td><td>—</td><td>—</td></tr>"
            + "</table>"
            + "<table>"
            + "<caption>Host pair B — two machines (replace with your IPs)</caption>"
            + "<tr><th>Window</th><th>No drop (kbps)</th><th>1% drop (kbps)</th></tr>"
            + "<tr><td>1</td><td>—</td><td>—</td></tr>"
            + "<tr><td>8</td><td>—</td><td>—</td></tr>"
            + "<tr><td>64</td><td>—</td><td>—</td></tr>"
            + "</table>"
            + "<p><a href='/'>Home</a> · <a href='/api/cache'>Cache</a></p>"
            + "</body></html>";
    }

    // ─────────────────────────────────────────────────────────────────
    //  MIME type lookup
    // ─────────────────────────────────────────────────────────────────
    static String mimeFor(String filename) {
        String f = filename.toLowerCase();
        if (f.endsWith(".png"))                return "image/png";
        if (f.endsWith(".jpg")||f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".gif"))                return "image/gif";
        if (f.endsWith(".html")||f.endsWith(".htm")) return "text/html";
        if (f.endsWith(".txt"))                return "text/plain";
        if (f.endsWith(".css"))                return "text/css";
        if (f.endsWith(".js"))                 return "application/javascript";
        return "application/octet-stream";
    }
}