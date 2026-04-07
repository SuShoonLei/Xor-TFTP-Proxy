package pj2;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;


public class DataServer {

    // ── Settings ──────────────────────────────────────────────────────────
    static final int    BLKSIZE   = 1024;       // bytes per DATA block
    static final int    MAX_PKT   = 65535;      // largest UDP packet we allocate

    // ── Packet opcodes ────────────────────────────────────────────────────
    static final int OP_RRQ           = 1;
    static final int OP_DATA          = 3;
    static final int OP_ACK           = 4;
    static final int OP_ERROR         = 5;
    static final int OP_HANDSHAKE     = 11;
    static final int OP_HANDSHAKE_ACK = 12;

    int    port     = 8069;
    double dropRate = 0.0;   // 0.0 = no drop, 0.01 = 1% drop

    // ─────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        DataServer srv = new DataServer();
        for (String a : args) {
            if (a.equals("--drop"))       srv.dropRate = 0.01;
            if (a.startsWith("--port="))  srv.port = Integer.parseInt(a.substring(7));
        }
        srv.run();
    }

    void run() throws Exception {
        // One shared thread pool — each client gets its own worker thread
        ExecutorService pool = Executors.newCachedThreadPool();

        // Main socket only receives the first RRQ packet.
        // Each session then uses its own ephemeral socket (different port).
        try (DatagramSocket mainSock = new DatagramSocket(port)) {
            System.out.println("DataServer ready on UDP port " + port);
            System.out.println("Files: ../photos/ then ./photos/ (project folder or demo folder; first match wins)");
            System.out.println("Start ProxyServer next, then use browser: http://localhost:8080/<file>");
            if (dropRate > 0) System.out.println("[!] 1% packet drop simulation ON");

            while (true) {
                byte[]         buf = new byte[MAX_PKT];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                mainSock.receive(pkt);   // block until an RRQ arrives

                // Make a copy so the worker thread has its own buffer
                byte[]      data = Arrays.copyOf(pkt.getData(), pkt.getLength());
                InetAddress from = pkt.getAddress();
                int         fromPort = pkt.getPort();

                pool.submit(() -> handleRequest(data, from, fromPort));
            }
        }
    }

    /**
     * Looks in {@code ./photos/} then {@code ../photos/} so images can live next to {@code demo/}
     * or inside it. Matches {@code display.PNG} if the request used {@code display.png}.
     */
    static File findPublishedFile(String safeName) {
        for (String root : new String[] { "../photos", "photos" }) {
            File f = findInDirIgnoreCase(root, safeName);
            if (f.isFile()) return f;
        }
        return new File("photos", safeName);
    }

    static File findInDirIgnoreCase(String dir, String name) {
        File exact = new File(dir, name);
        if (exact.isFile()) return exact;
        File d = new File(dir);
        if (!d.isDirectory()) return exact;
        String[] list = d.list();
        if (list == null) return exact;
        String want = name.toLowerCase(Locale.ROOT);
        for (String n : list) {
            if (n != null && n.toLowerCase(Locale.ROOT).equals(want)) {
                return new File(d, n);
            }
        }
        return exact;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Handle one file-transfer session
    // ─────────────────────────────────────────────────────────────────────
    void handleRequest(byte[] rrqBuf, InetAddress clientAddr, int clientPort) {
        // Each session uses its own socket so the client can tell sessions apart
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(5000);   // give up after 5 s of silence

            // ── 1. Parse the filename from the RRQ packet ─────────────
            // RRQ format:  [0x00][0x01] filename \0 "octet" \0 options...
            String filename = parseFilename(rrqBuf);
            int    winSize  = parseWindowSize(rrqBuf);   // client's requested window
            winSize = Math.max(1, Math.min(64, winSize));

            System.out.printf("[Session %s:%d] RRQ '%s'  window=%d%n",
                clientAddr, clientPort, filename, winSize);

            // ── 2. Open the file ───────────────────────────────────────
            // Strip leading slashes: "/display.PNG" → "display.PNG"
            String safeName = filename.replaceAll("^/+", "");
            File   file     = findPublishedFile(safeName);

            if (!file.exists() || !file.isFile()) {
                sendError(sock, clientAddr, clientPort, "File not found: " + filename);
                return;
            }
            byte[] fileBytes = Files.readAllBytes(file.toPath());

            // ── 3. HANDSHAKE — exchange IDs and nonces to build XOR key ──
            //
            //  Both sides generate a random ID (int) and nonce (long).
            //  The XOR session key is derived from both nonces XOR'd together.
            //  This means neither side knows the key until both have spoken.
            //
            SecureRandom rng     = new SecureRandom();
            int          myID    = rng.nextInt();
            long         myNonce = rng.nextLong();

            // Send HANDSHAKE:  [opcode 2B][myID 4B][myNonce 8B]  = 14 bytes
            byte[] hs = buildHandshake(OP_HANDSHAKE, myID, myNonce);
            sock.send(new DatagramPacket(hs, hs.length, clientAddr, clientPort));

            // Wait for HANDSHAKE_ACK from the proxy
            byte[]         buf = new byte[MAX_PKT];
            DatagramPacket p   = new DatagramPacket(buf, buf.length);
            sock.receive(p);

            if (opcode(buf) != OP_HANDSHAKE_ACK) {
                sendError(sock, clientAddr, clientPort, "Expected HANDSHAKE_ACK");
                return;
            }
            long theirNonce = nonce(buf);

            // XOR key = myNonce XOR theirNonce  (same result on both sides)
            long xorKey = myNonce ^ theirNonce;
            if (xorKey == 0) xorKey = 0xDEADBEEFL;  // XOR key must be non-zero

            System.out.printf("[Session] Key derived: 0x%016X%n", xorKey);

            // ── 4. Send DATA blocks with a sliding window ──────────────
            //
            //  SLIDING WINDOW means we can send multiple blocks without
            //  waiting for an ACK for each one:
            //
            //    window=1:  send 1, wait for ACK, send 1, wait, ...  (slow)
            //    window=8:  send 8 at once, then wait for ACKs        (faster)
            //    window=64: send 64 at once, wait                      (fastest)
            //
            sendFile(sock, clientAddr, clientPort, fileBytes, winSize, xorKey, filename);

        } catch (Exception e) {
            System.err.println("[Session] Error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Send the whole file using a sliding window + RTO retransmit
    // ─────────────────────────────────────────────────────────────────────
    void sendFile(DatagramSocket sock,
                  InetAddress   clientAddr,
                  int           clientPort,
                  byte[]        fileBytes,
                  int           winSize,
                  long          xorKey,
                  String        filename) throws Exception {

        // Split file into blocks of BLKSIZE bytes (RFC 1350: if size is a multiple
        // of BLKSIZE, send one extra zero-length DATA block to mark end of file).
        int totalBlocks = Math.max(1, (int) Math.ceil((double) fileBytes.length / BLKSIZE));
        if (fileBytes.length > 0 && fileBytes.length % BLKSIZE == 0) {
            totalBlocks++;
        }

        // Pre-encrypt every block with XOR.
        // Block N uses key derived by advancing the XorShift stream N times,
        // so each block has a different key even with the same seed.
        byte[][] encBlocks = new byte[totalBlocks][];
        for (int i = 0; i < totalBlocks; i++) {
            int    off   = i * BLKSIZE;
            int    len   = Math.min(BLKSIZE, fileBytes.length - off);
            byte[] plain = Arrays.copyOfRange(fileBytes, off, off + len);
            encBlocks[i] = xorEncrypt(plain, xorKey, i);   // each block uses block-index as salt
        }

        // Sliding window state
        int base    = 1;   // oldest unACKed block number (1-based)
        int nextSeq = 1;   // next block to send

        // RTO estimator: starts at 1 second, adjusts with each ACK
        double srtt   = -1, rttvar = 0, rto = 1000;

        // When we sent each block (for RTT measurement)
        Map<Integer, Long> sentAt = new HashMap<>();
        int retransmits = 0;
        long startMs = System.currentTimeMillis();

        while (base <= totalBlocks) {

            // ── Send blocks until window is full ──────────────────────
            while (nextSeq < base + winSize && nextSeq <= totalBlocks) {
                int    idx = nextSeq - 1;
                byte[] pkt = buildData(nextSeq, encBlocks[idx]);
                if (!drop()) sock.send(new DatagramPacket(pkt, pkt.length, clientAddr, clientPort));
                sentAt.put(nextSeq, System.currentTimeMillis());
                nextSeq++;
            }

            // ── Wait for an ACK ───────────────────────────────────────
            sock.setSoTimeout((int) Math.max(100, rto));
            byte[]         ackBuf = new byte[8];
            DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);

            try {
                sock.receive(ackPkt);
                int ackNum = seqno(ackBuf);

                // Update RTT estimate (TCP RFC 6298 style)
                Long t = sentAt.get(ackNum);
                if (t != null) {
                    double sample = System.currentTimeMillis() - t;
                    if (srtt < 0) { srtt = sample; rttvar = sample / 2.0; }
                    else {
                        rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - sample);
                        srtt   = 0.875 * srtt   + 0.125 * sample;
                    }
                    rto = Math.min(60000, Math.max(200, srtt + 4 * rttvar));
                }

                // Slide the window: everything up to ackNum is confirmed
                if (ackNum >= base) base = ackNum + 1;

            } catch (SocketTimeoutException e) {
                // Timeout: go back to base and retransmit from there
                rto = Math.min(60000, rto * 2);   // exponential back-off
                retransmits++;
                System.out.printf("[DataServer] Timeout, retransmit from block %d%n", base);
                nextSeq = base;   // will re-send from base on next loop iteration
                sentAt.clear();
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        double kbps  = fileBytes.length * 8.0 / Math.max(1, elapsed);
        System.out.printf("[DataServer] Done '%s'  %d bytes  %.0f kbps  retx=%d%n",
            filename, fileBytes.length, kbps, retransmits);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  XOR Encryption
    // ─────────────────────────────────────────────────────────────────────
    /**
     * Encrypt (or decrypt — XOR is symmetric) a byte array.
     *
     * The key stream is a XorShift-64 PRNG seeded with:
     *     seed = xorKey  XOR  (blockIndex << 16)
     * Using the block index as a salt means block 0 and block 1 get
     * completely different key streams even with the same master key.
     *
     * XorShift-64 advances the 64-bit state with three shift operations.
     * For each byte of output we advance the state once and XOR the
     * low 8 bits of the state with the input byte.
     */
    static byte[] xorEncrypt(byte[] input, long masterKey, int blockIndex) {
        long   seed   = masterKey ^ ((long) blockIndex << 16);
        if (seed == 0) seed = 0xCAFEBABEL;
        byte[] output = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            // XorShift-64 step
            seed ^= (seed << 13);
            seed ^= (seed >>> 7);
            seed ^= (seed << 17);
            output[i] = (byte) (input[i] ^ seed);
        }
        return output;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Packet builders
    // ─────────────────────────────────────────────────────────────────────

    /** HANDSHAKE or HANDSHAKE_ACK: [opcode 2B][senderID 4B][nonce 8B] */
    static byte[] buildHandshake(int opcode, int id, long nonce) {
        byte[] b = new byte[14];
        b[0] = 0; b[1] = (byte) opcode;
        b[2] = (byte)(id >> 24); b[3] = (byte)(id >> 16);
        b[4] = (byte)(id >>  8); b[5] = (byte)(id);
        for (int i = 0; i < 8; i++) b[6 + i] = (byte)(nonce >> (56 - 8*i));
        return b;
    }

    /** DATA packet: [0x00][0x03][blockHi][blockLo][payload...] */
    static byte[] buildData(int blockNum, byte[] payload) {
        byte[] pkt = new byte[4 + payload.length];
        pkt[0] = 0; pkt[1] = (byte) OP_DATA;
        pkt[2] = (byte)(blockNum >> 8); pkt[3] = (byte)(blockNum & 0xFF);
        System.arraycopy(payload, 0, pkt, 4, payload.length);
        return pkt;
    }

    /** ERROR packet: [0x00][0x05][0x00][code][message \0] */
    static void sendError(DatagramSocket sock, InetAddress addr, int port, String msg) {
        try {
            byte[] m = msg.getBytes();
            byte[] e = new byte[5 + m.length];
            e[0] = 0; e[1] = (byte) OP_ERROR; e[2] = 0; e[3] = 1;
            System.arraycopy(m, 0, e, 4, m.length);
            sock.send(new DatagramPacket(e, e.length, addr, port));
        } catch (Exception ignored) {}
    }

    //  Packet parsers

    /** Read the 2-byte opcode from the front of a packet buffer. */
    static int opcode(byte[] buf) {
        return ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
    }

    /** Read the 2-byte sequence/block number (bytes 2-3). */
    static int seqno(byte[] buf) {
        return ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
    }

    /** Read the 8-byte nonce from a HANDSHAKE packet (bytes 6-13). */
    static long nonce(byte[] buf) {
        long n = 0;
        for (int i = 0; i < 8; i++) n = (n << 8) | (buf[6 + i] & 0xFF);
        return n;
    }

    /**
     * Read the filename from an RRQ packet.
     * Format: [0x00][0x01] filename \0 "octet" \0 [options...]
     */
    static String parseFilename(byte[] buf) {
        int i = 2;
        int start = i;
        while (i < buf.length && buf[i] != 0) i++;
        return new String(buf, start, i - start);
    }

    /**
     * Read the "windowsize" option from an RRQ packet's option list.
     * Options appear after the mode string as: key \0 value \0 pairs.
     */
    static int parseWindowSize(byte[] buf) {
        // Skip opcode (2), filename (until \0), mode (until \0)
        int i = 2;
        while (i < buf.length && buf[i] != 0) i++; i++;  // skip filename
        while (i < buf.length && buf[i] != 0) i++; i++;  // skip mode
        // Now read key-value option pairs
        while (i < buf.length) {
            int s = i;
            while (i < buf.length && buf[i] != 0) i++;
            String key = new String(buf, s, i - s).toLowerCase(); i++;
            s = i;
            while (i < buf.length && buf[i] != 0) i++;
            String val = new String(buf, s, i - s); i++;
            if (key.equals("windowsize")) {
                try { return Integer.parseInt(val); } catch (Exception ignored) {}
            }
        }
        return 8;   // default window size if option not found
    }

    //  Packet drop simulation
    // 
    boolean drop() {
        return dropRate > 0 && Math.random() < dropRate;
    }
}