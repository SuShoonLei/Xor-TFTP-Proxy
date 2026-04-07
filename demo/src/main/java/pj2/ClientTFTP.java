package pj2;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.awt.Desktop;
import java.util.Arrays;


/*/terminal 1 
cd /Users/sushoonleikhaing/Downloads/Project2/demo
mvn compile
java -cp target/classes pj2.DataServer
/*/


/*/ terminal 2
cd /Users/sushoonleikhaing/Downloads/Project2/demo
java -cp target/classes pj2.ProxyServer
/*/

/*/ terminal 3
cd /Users/sushoonleikhaing/Downloads/Project2/demo
java -cp target/classes pj2.ClientTFTP display.PNG
/*/
public class ClientTFTP {

    static final String PROXY_HOST = "localhost";
    static final int    PROXY_PORT = 8080;
    static final String SAVE_DIR   = "/tmp/client_out";

    public static void main(String[] args) throws Exception {
        // Make sure the output directory exists
        Files.createDirectories(Paths.get(SAVE_DIR));

        System.out.println("=== ClientTFTP ===");
        System.out.println("Proxy: " + PROXY_HOST + ":" + PROXY_PORT);
        System.out.println("Files will be saved to: " + SAVE_DIR);
        System.out.println();
        System.out.println("Tip: In a web browser open http://localhost:" + PROXY_PORT + "/filename.png");
        System.out.println("     (http://filename.png alone is NOT localhost — start ProxyServer first.)");
        System.out.println();

        if (args.length > 0) {
            fetchAndShow(args[0]);
        } else {
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("Enter image name or URL (files come from the server photos/ folder).");
            System.out.println("Examples: display.png   http://localhost:8080/display.png   http://display.png");
            while (true) {
                System.out.print("> ");
                System.out.flush();
                String input = console.readLine();
                if (input == null || input.equalsIgnoreCase("quit")) break;
                input = input.trim();
                if (input.isEmpty()) continue;
                fetchAndShow(input);
            }
        }
        System.out.println("Goodbye!");
    }

    /**
     * Turn what you typed into an HTTP path like .
     * Accepts: {@code display.png}, {@code /display.png},
     * {@code http://localhost:8080/display.PNG}.
     */
    static String toRequestPath(String input) throws URISyntaxException {
        String s = input.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            URI u = new URI(s);
            String path = u.getPath();
            if (path != null && path.length() > 1) {
                return path.startsWith("/") ? path : "/" + path;
            }
            // No path: e.g. "http://display.png" → use host as filename
            String host = u.getHost();
            if (host != null && !host.isEmpty()) {
                return "/" + host;
            }
            return "/";
        }
        if (!s.startsWith("/")) {
            return "/" + s;
        }
        return s;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Fetch a file from the proxy and display it
    // ─────────────────────────────────────────────────────────────────
    static void fetchAndShow(String userInput) {
        String filePath;
        try {
            filePath = toRequestPath(userInput);
        } catch (URISyntaxException e) {
            System.out.println("Bad URL: " + e.getMessage());
            return;
        }

        System.out.println();
        System.out.println("Fetching: " + filePath + "  (from: " + userInput + ")");

        try {
            // ── 1. Open TCP connection to ProxyServer ──────────────────
            Socket sock = new Socket(PROXY_HOST, PROXY_PORT);

            // ── 2. Send an HTTP GET request ────────────────────────────
            //  We use HTTP/1.0 so the server closes the connection after
            //  sending the response — that tells us where the body ends.
            PrintWriter req = new PrintWriter(sock.getOutputStream(), true);
            req.print("GET " + filePath + " HTTP/1.0\r\n");
            req.print("Host: " + PROXY_HOST + "\r\n");
            req.print("Connection: close\r\n");
            req.print("\r\n");
            req.flush();

            // ── 3. Read the full HTTP response ─────────────────────────
            InputStream  in          = sock.getInputStream();
            byte[]       rawResponse = in.readAllBytes();
            sock.close();

            // Split headers and body at the blank line (\r\n\r\n)
            String responseText = new String(rawResponse, "ISO-8859-1");
            int    splitAt      = responseText.indexOf("\r\n\r\n");

            if (splitAt < 0) {
                System.out.println("Bad response from proxy.");
                return;
            }

            String headers = responseText.substring(0, splitAt);
            byte[] body    = Arrays.copyOfRange(rawResponse, splitAt + 4, rawResponse.length);

            // ── 4. Parse useful headers ────────────────────────────────
            int    statusCode   = 0;
            String contentType  = "application/octet-stream";
            String cacheStatus  = "unknown";

            for (String line : headers.split("\r\n")) {
                String lo = line.toLowerCase();
                if (lo.startsWith("http/")) {
                    try { statusCode = Integer.parseInt(line.split(" ")[1]); }
                    catch (Exception ignored) {}
                } else if (lo.startsWith("content-type:")) {
                    contentType = line.substring(line.indexOf(':') + 1).trim();
                } else if (lo.startsWith("x-cache:")) {
                    cacheStatus = line.substring(line.indexOf(':') + 1).trim();
                }
            }

            // ── 5. Print results ───────────────────────────────────────
            System.out.println("  Status:       HTTP " + statusCode);
            System.out.println("  Cache:        " + cacheStatus);
            System.out.println("  Content-Type: " + contentType);
            System.out.println("  Size:         " + body.length + " bytes");

            if (statusCode != 200) {
                System.out.println("  Error body: " + new String(body, "UTF-8"));
                return;
            }

            // ── 6. Save the file to disk ───────────────────────────────
            // Extract just the filename part from the path
            String fname = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;
            if (fname.isEmpty()) fname = "index.html";

            Path outFile = Paths.get(SAVE_DIR, fname);
            Files.write(outFile, body);
            System.out.println("  Saved to:     " + outFile);

            // ── 7. Open the file (images, HTML, etc.) ─────────────────
            String ct = contentType.toLowerCase();
            if (ct.contains("image") || ct.contains("html") || ct.contains("text")) {
                try {
                    if (Desktop.isDesktopSupported()) {
                        System.out.println("  Opening file...");
                        Desktop.getDesktop().open(outFile.toFile());
                    } else {
                        // On headless servers, just tell the user where to look
                        System.out.println("  (No desktop — open manually: " + outFile + ")");
                    }
                } catch (Exception e) {
                    System.out.println("  (Could not auto-open: " + e.getMessage() + ")");
                }
            }

        } catch (ConnectException e) {
            System.out.println("  ERROR: Cannot connect to ProxyServer at "
                + PROXY_HOST + ":" + PROXY_PORT);
            System.out.println("  Make sure ProxyServer is running first.");
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
        }

        System.out.println();
    }
}