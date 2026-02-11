import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;


// Blocking I/O Web Server using NIO.Channels 
public final class WebServer {

    public static void main(String[] args) throws Exception {

        int port = 6789;

        ExecutorService pool = Executors.newFixedThreadPool(50);

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(port));
            server.configureBlocking(true);

            System.out.println("Web Server starting on port " + port + "...");

            while (true) {
                SocketChannel client = server.accept();
                pool.execute(new HttpRequest(client));
            }
        }
    }
}

final class HttpRequest implements Runnable {

    private static final String CRLF = "\r\n";
    private final SocketChannel channel;

    HttpRequest(SocketChannel channel) {
        this.channel = channel;
    }

    @Override
    public void run() {
        try (SocketChannel sc = channel) {
            handleConnection(sc);
        } catch (IOException e) {
            // cliente cerró la conexión (normal)
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleConnection(SocketChannel channel) throws Exception {

        while (true) {

            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int read = channel.read(buffer);
            if (read <= 0)
                return;

            buffer.flip();
            String raw = StandardCharsets.US_ASCII.decode(buffer).toString();
            BufferedReader reader = new BufferedReader(new StringReader(raw));

            System.out.println("\n================ NEW HTTP REQUEST ================");
            System.out.println("Remote: " + channel.getRemoteAddress());

            String requestLine = reader.readLine();
            if (requestLine == null)
                return;

            System.out.println("Request-Line: " + requestLine);

            Map<String, String> headers = new HashMap<>();
            String h;
            while ((h = reader.readLine()) != null && !h.isEmpty()) {
                System.out.println(h);
                int idx = h.indexOf(":");
                if (idx > 0) {
                    headers.put(h.substring(0, idx).trim(), h.substring(idx + 1).trim());
                }
            }

            System.out.println("=============== END REQUEST ======================");

            StringTokenizer tokens = new StringTokenizer(requestLine);
            String method = tokens.nextToken();
            String path = tokens.nextToken();

            if (!method.equals("GET")) {
                send404();
                continue;
            }

            if (path.equals("/"))
                path = "/index.html";

            File file = new File("." + path);
            if (!file.exists()) {
                send404();
                continue;
            }

            if (headers.containsKey("Range")) {
                sendPartial(file, headers.get("Range"));
            } else {
                send200(file);
            }

            if ("close".equalsIgnoreCase(headers.get("Connection"))) {
                return;
            }
        }
    }

    private void send200(File file) throws Exception {

        String headers = "HTTP/1.1 200 OK" + CRLF +
                "Content-Type: " + contentType(file.getName()) + CRLF +
                "Content-Length: " + file.length() + CRLF +
                "Connection: keep-alive" + CRLF +
                CRLF;

        channel.write(ByteBuffer.wrap(headers.getBytes(StandardCharsets.US_ASCII)));

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                channel.write(ByteBuffer.wrap(buf, 0, n));
            }
        }
    }

    private void sendPartial(File file, String range) throws Exception {

        long size = file.length();
        String bytes = range.replace("bytes=", "");
        long start = Long.parseLong(bytes.split("-")[0]);
        long end = size - 1;
        long length = end - start + 1;

        String headers = "HTTP/1.1 206 Partial Content" + CRLF +
                "Content-Type: " + contentType(file.getName()) + CRLF +
                "Content-Length: " + length + CRLF +
                "Content-Range: bytes " + start + "-" + end + "/" + size + CRLF +
                "Connection: keep-alive" + CRLF +
                CRLF;

        channel.write(ByteBuffer.wrap(headers.getBytes(StandardCharsets.US_ASCII)));

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            byte[] buf = new byte[8192];
            long remaining = length;
            while (remaining > 0) {
                int read = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (read == -1)
                    break;
                channel.write(ByteBuffer.wrap(buf, 0, read));
                remaining -= read;
            }
        }
    }

    private void send404() throws Exception {
        String body = "<h1>404 Not Found</h1>";
        String response = "HTTP/1.1 404 Not Found" + CRLF +
                "Content-Type: text/html" + CRLF +
                "Content-Length: " + body.length() + CRLF +
                "Connection: close" + CRLF +
                CRLF +
                body;

        channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String contentType(String name) {
        if (name.endsWith(".html"))
            return "text/html";
        if (name.endsWith(".css"))
            return "text/css";
        if (name.endsWith(".js"))
            return "application/javascript";
        if (name.endsWith(".jpg"))
            return "image/jpeg";
        if (name.endsWith(".gif"))
            return "image/gif";
        if (name.endsWith(".mp4"))
            return "video/mp4";
        if (name.endsWith(".ico"))
            return "image/x-icon";
        if (name.endsWith(".pdf"))
            return "application/pdf";
        if (name.endsWith(".svg"))
            return "image/svg+xml";
        if (name.endsWith(".mp3"))
            return "audio/mpeg";
        return "application/octet-stream";
    }
}
