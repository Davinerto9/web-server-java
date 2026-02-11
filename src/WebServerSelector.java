import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

public class WebServerSelector {

    private static final int PORT = 6789;
    private static final String CRLF = "\r\n";

    public static void main(String[] args) throws Exception {

        // Abrimos el selector que es un Multiplexador de canales
        Selector selector = Selector.open();
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(PORT));
        server.configureBlocking(false);

        // Registramos el canal para ACCEPT
        server.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("HTTP/1.1 NIO Server running on port " + PORT);

        while (true) {

            // Bloquea hasta que haya eventos
            selector.select();

            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                // Nueva conexión TCP
                if (key.isAcceptable()) {
                    accept(selector, key);
                }

                // Datos listos para leer
                else if (key.isReadable()) {
                    read(key);
                }

                // Canal listo para escribir
                else if (key.isWritable()) {
                    write(key);
                }
            }
        }
    }

    // Acepta una nueva conexión TCP entrante,
    // la configura en modo no bloqueante y la registra en el selector para lectura (READ).
    private static void accept(Selector selector, SelectionKey key) throws Exception {

        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();

        client.configureBlocking(false);

        System.out.println("\n================ NEW TCP CONNECTION ================");
        System.out.println("Remote: " + client.getRemoteAddress());

        ClientContext ctx = new ClientContext();

        // Registramos el canal para READ
        client.register(selector, SelectionKey.OP_READ, ctx);
    }

    // Lee la petición HTTP desde el cliente,
    // parsea request-line y headers,
    // decide qué respuesta preparar (200, 404, 405 o 206)
    // y cambia el interés del canal a WRITE.
    private static void read(SelectionKey key) throws Exception {

        SocketChannel channel = (SocketChannel) key.channel();
        ClientContext ctx = (ClientContext) key.attachment();

        int read = channel.read(ctx.readBuffer);
        if (read == -1) {
            channel.close();
            return;
        }

        ctx.readBuffer.flip();
        String request = StandardCharsets.US_ASCII.decode(ctx.readBuffer).toString();
        ctx.readBuffer.clear();

        BufferedReader reader = new BufferedReader(new StringReader(request));

        System.out.println("================ NEW HTTP REQUEST =================");

        String requestLine = reader.readLine();
        if (requestLine == null)
            return;

        System.out.println("Request-Line: " + requestLine);

        Map<String, String> headers = new HashMap<>();
        String h;
        while ((h = reader.readLine()) != null && !h.isEmpty()) {
            System.out.println(h);
            int idx = h.indexOf(":");
            if (idx > 0)
                headers.put(h.substring(0, idx), h.substring(idx + 1).trim());
        }

        System.out.println("=============== END REQUEST ======================");

        StringTokenizer tokens = new StringTokenizer(requestLine);
        String method = tokens.nextToken();
        String path = tokens.nextToken();

        if (!method.equals("GET")) {
            prepare405(ctx);
            key.interestOps(SelectionKey.OP_WRITE);
            return;
        }

        if (path.equals("/"))
            path = "/index.html";

        File file = new File("." + path);

        if (!file.exists()) {
            prepare404(ctx);
        } else if (headers.containsKey("Range")) {
            prepare206(ctx, file, headers.get("Range"));
        } else {
            prepare200(ctx, file);
        }

        ctx.keepAlive = !"close".equalsIgnoreCase(headers.get("Connection"));

        // Cambiamos interés a WRITE
        key.interestOps(SelectionKey.OP_WRITE);
    }


    // Prepara una respuesta HTTP 405 cuando el método no es GET.
    // Construye headers + body y desactiva keep-alive.
    private static void prepare405(ClientContext ctx) {

        String body = "<h1>405 Method Not Allowed</h1>";

        String response = "HTTP/1.1 405 Method Not Allowed\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body;

        ctx.keepAlive = false;
        ctx.writeBuffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII));
    }

    // Lee completamente un archivo del disco y lo convierte en un arreglo de bytes.
    // Se usa para enviar el cuerpo de la respuesta HTTP.
    private static byte[] fileToBytes(File file) throws IOException {

        try (FileInputStream fis = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;

            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }

            return baos.toByteArray();
        }
    }

    // Escribe la respuesta almacenada en el writeBuffer hacia el cliente.
    // Si termina de enviar, decide cerrar la conexión o volver a READ según keep-alive.
    private static void write(SelectionKey key) {

        ClientContext ctx = (ClientContext) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();

        try {
            channel.write(ctx.writeBuffer);

            if (!ctx.writeBuffer.hasRemaining()) {
                ctx.writeBuffer = null;

                if (!ctx.keepAlive) {
                    channel.close();
                    key.cancel();
                } else {
                    key.interestOps(SelectionKey.OP_READ);
                }
            }

        } catch (IOException e) {
            // cliente cerró conexión
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            key.cancel();
        }
    }

    // Prepara una respuesta HTTP 404 cuando el archivo solicitado no existe.
    // Construye headers + body de error.
    private static void prepare404(ClientContext ctx) {

        String body = "<h1>404 Not Found</h1>";

        String response = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body;

        ctx.writeBuffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII));
    }

    // Prepara una respuesta HTTP 200 OK con el archivo completo.
    // Incluye Content-Type, Content-Length y cuerpo del archivo.
    private static void prepare200(ClientContext ctx, File file) throws Exception {

        byte[] body = fileToBytes(file);

        String headers = "HTTP/1.1 200 OK" + CRLF +
                "Content-Type: " + contentType(file.getName()) + CRLF +
                "Content-Length: " + body.length + CRLF +
                "Connection: keep-alive" + CRLF +
                CRLF;

        ctx.writeBuffer = ByteBuffer.allocate(headers.length() + body.length);
        ctx.writeBuffer.put(headers.getBytes(StandardCharsets.US_ASCII));
        ctx.writeBuffer.put(body);
        ctx.writeBuffer.flip();
    }

    // Lee solo un rango específico de bytes de un archivo.
    // Se usa para soportar descargas parciales (Range requests).
    private static byte[] readRange(File file, long start, long end) throws Exception {

        long length = end - start + 1;
        byte[] data = new byte[(int) length];

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            raf.readFully(data);
        }

        return data;
    }

    // Prepara una respuesta HTTP 206 Partial Content.
    // Calcula el rango solicitado, lee solo esa parte del archivo
    // y construye los headers correspondientes (Content-Range).
    private static void prepare206(ClientContext ctx, File file, String range) throws Exception {

        long size = file.length();

        String bytes = range.replace("bytes=", "");
        String[] parts = bytes.split("-");

        long start = Long.parseLong(parts[0]);
        long end = (parts.length > 1 && !parts[1].isEmpty())
                ? Long.parseLong(parts[1])
                : size - 1;

        byte[] body = readRange(file, start, end);

        String headers = "HTTP/1.1 206 Partial Content" + CRLF +
                "Content-Type: " + contentType(file.getName()) + CRLF +
                "Content-Range: bytes " + start + "-" + end + "/" + size + CRLF +
                "Content-Length: " + body.length + CRLF +
                "Connection: keep-alive" + CRLF +
                CRLF;

        ctx.writeBuffer = ByteBuffer.allocate(headers.length() + body.length);
        ctx.writeBuffer.put(headers.getBytes(StandardCharsets.US_ASCII));
        ctx.writeBuffer.put(body);
        ctx.writeBuffer.flip();
    }

    // Determina el Content-Type (MIME type) según la extensión del archivo.
    // Se usa para que el navegador interprete correctamente el recurso.
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

// Contiene el estado asociado a cada cliente conectado:
// buffers de lectura/escritura y flag de conexión persistente (keep-alive).
class ClientContext {
    ByteBuffer readBuffer = ByteBuffer.allocate(8192);
    ByteBuffer writeBuffer;
    boolean keepAlive = true;
}
