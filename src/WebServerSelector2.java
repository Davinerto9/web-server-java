/**
 * Servidor HTTP/1.1 implementado con Java NIO (Non-blocking I/O).
 * Soporta conexiones persistentes (keep-alive) y solicitudes de rango (Range requests).
 * 
 * Características:
 * - Manejo asíncrono de múltiples conexiones simultáneas usando Selector
 * - Soporte para HTTP 200 OK, 206 Partial Content y 404 Not Found
 * - Keep-alive para reutilización de conexiones TCP
 */

import java.nio.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

public class WebServerSelector2 {

    private static final int PORT = 6789;
    
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final ConnectionHandler connectionHandler;

    public WebServerSelector2() throws IOException {
        this.selector = Selector.open();
        this.serverChannel = createServerChannel();
        this.connectionHandler = new ConnectionHandler();
    }

    /**
     * Crea y configura el canal del servidor en modo no bloqueante
     */
    private ServerSocketChannel createServerChannel() throws IOException {
        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.bind(new InetSocketAddress(PORT));
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_ACCEPT);
        return channel;
    }

    /**
     * Inicia el servidor y procesa eventos de forma continua
     */
    public void start() throws IOException {
        System.out.println("Servidor HTTP/1.1 ejecutándose en puerto " + PORT);
        System.out.println("Modo: Non-blocking I/O");
        System.out.println("Esperando conexiones...\n");

        while (true) {
            // Bloquea hasta que al menos un canal esté listo para I/O
            selector.select();
            
            processSelectedKeys();
        }
    }

    /**
     * Procesa todos los canales que tienen eventos pendientes
     */
    private void processSelectedKeys() throws IOException {
        Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();

        while (keyIterator.hasNext()) {
            SelectionKey key = keyIterator.next();
            keyIterator.remove(); // Importante: remover la key procesada

            try {
                if (!key.isValid()) {
                    continue;
                }

                // Despachar según el tipo de evento
                if (key.isAcceptable()) {
                    handleAccept(key);
                } else if (key.isReadable()) {
                    handleRead(key);
                } else if (key.isWritable()) {
                    handleWrite(key);
                }
                
            } catch (IOException e) {
                System.err.println("Error procesando conexión: " + e.getMessage());
                closeConnection(key);
            }
        }
    }

    /**
     * Acepta una nueva conexión TCP entrante
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        
        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            
            System.out.println("\n═══════════════════════════════════════════════════");
            System.out.println("✓ NUEVA CONEXIÓN TCP");
            System.out.println("  Cliente: " + clientChannel.getRemoteAddress());
            System.out.println("═══════════════════════════════════════════════════");
            
            // Crear contexto para almacenar el estado de esta conexión
            ClientContext context = new ClientContext();
            
            // Registrar el canal para lectura con el contexto adjunto
            clientChannel.register(selector, SelectionKey.OP_READ, context);
        }
    }

    /**
     * Lee datos de un cliente (solicitud HTTP)
     */
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ClientContext context = (ClientContext) key.attachment();
        
        // Leer datos del canal
        int bytesRead = clientChannel.read(context.getReadBuffer());
        
        if (bytesRead == -1) {
            // El cliente cerró la conexión
            closeConnection(key);
            return;
        }
        
        // Procesar la solicitud HTTP
        HttpRequest request = connectionHandler.parseRequest(context);
        
        if (request != null) {
            System.out.println("\n─────────────────────────────────────────────────");
            System.out.println("SOLICITUD HTTP RECIBIDA");
            System.out.println("  Método: " + request.getMethod());
            System.out.println("  Ruta: " + request.getPath());
            System.out.println("  Keep-Alive: " + request.isKeepAlive());
            System.out.println("─────────────────────────────────────────────────");
            
            // Preparar respuesta
            connectionHandler.prepareResponse(context, request);
            
            // Cambiar a modo escritura
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    /**
     * Escribe datos al cliente (respuesta HTTP)
     */
    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ClientContext context = (ClientContext) key.attachment();
        
        // Escribir datos al canal
        clientChannel.write(context.getWriteBuffer());
        
        // Si se escribió todo el buffer
        if (!context.getWriteBuffer().hasRemaining()) {
            context.resetWriteBuffer();
            
            if (context.isKeepAlive()) {
                // Reutilizar la conexión - cambiar a modo lectura
                key.interestOps(SelectionKey.OP_READ);
                System.out.println("♻ Conexión reutilizada (keep-alive)");
            } else {
                // Cerrar la conexión
                closeConnection(key);
                System.out.println("✗ Conexión cerrada");
            }
        }
    }

    /**
     * Cierra una conexión y cancela su SelectionKey
     */
    private void closeConnection(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException e) {
            // Ignorar errores al cerrar
        }
        key.cancel();
    }

    // ═══════════════════════════════════════════════════════════════
    // MÉTODO MAIN
    // ═══════════════════════════════════════════════════════════════
    
    public static void main(String[] args) {
        try {
            WebServerSelector2 server = new WebServerSelector2();
            server.start();
        } catch (IOException e) {
            System.err.println("✗ Error fatal al iniciar el servidor: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}


/**
 * Maneja el procesamiento de solicitudes HTTP y la preparación de respuestas.
 * Separa la lógica de negocio HTTP de la lógica de I/O del servidor.
 */
class ConnectionHandler {

    private final FileManager fileManager;

    public ConnectionHandler() {
        this.fileManager = new FileManager();
    }

    /**
     * Parsea una solicitud HTTP desde el contexto del cliente
     * @return HttpRequest o null si la solicitud está incompleta
     */
    public HttpRequest parseRequest(ClientContext context) throws IOException {
        // Preparar el buffer para lectura
        context.getReadBuffer().flip();
        
        String rawRequest = StandardCharsets.UTF_8.decode(context.getReadBuffer()).toString();
        context.getReadBuffer().clear();
        
        if (rawRequest.isEmpty()) {
            return null;
        }
        
        BufferedReader reader = new BufferedReader(new StringReader(rawRequest));
        
        // Parsear línea de solicitud (ej: "GET /index.html HTTP/1.1")
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        
        // Extraer método y ruta
        StringTokenizer tokens = new StringTokenizer(requestLine);
        if (tokens.countTokens() < 2) {
            return null;
        }
        
        String method = tokens.nextToken();
        String path = tokens.nextToken();
        
        // Parsear cabeceras HTTP
        Map<String, String> headers = parseHeaders(reader);
        
        return new HttpRequest(method, path, headers);
    }

    /**
     * Parsea las cabeceras HTTP de la solicitud
     */
    private Map<String, String> parseHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(":");
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim();
                String headerValue = line.substring(colonIndex + 1).trim();
                headers.put(headerName, headerValue);
            }
        }
        
        return headers;
    }

    /**
     * Prepara la respuesta HTTP apropiada según la solicitud
     */
    public void prepareResponse(ClientContext context, HttpRequest request) throws IOException {
        File requestedFile = new File("." + request.getPath());
        
        // Actualizar el estado de keep-alive en el contexto
        context.setKeepAlive(request.isKeepAlive());
        
        // Determinar tipo de respuesta
        if (!requestedFile.exists()) {
            prepare404Response(context);
        } else if (request.hasHeader("Range")) {
            prepare206Response(context, requestedFile, request.getHeader("Range"));
        } else {
            prepare200Response(context, requestedFile);
        }
    }

    /**
     * Prepara una respuesta 404 Not Found
     */
    private void prepare404Response(ClientContext context) {
        context.setWriteBuffer(HttpResponse.create404Response());
        context.setKeepAlive(false); // Cerrar conexión en caso de error
        System.out.println("  Respuesta: 404 Not Found");
    }

    /**
     * Prepara una respuesta 200 OK con el archivo completo
     */
    private void prepare200Response(ClientContext context, File file) throws IOException {
        byte[] fileContent = fileManager.readCompleteFile(file);
        context.setWriteBuffer(HttpResponse.create200Response(fileContent));
        System.out.println("  Respuesta: 200 OK (" + fileContent.length + " bytes)");
    }

    /**
     * Prepara una respuesta 206 Partial Content para solicitudes de rango
     */
    private void prepare206Response(ClientContext context, File file, String rangeHeader) 
            throws IOException {
        
        long fileSize = file.length();
        RangeInfo range = parseRangeHeader(rangeHeader, fileSize);
        
        byte[] rangeContent = fileManager.readFileRange(file, range.start, range.end);
        String contentType = ContentTypeDetector.detect(file.getName());
        
        context.setWriteBuffer(HttpResponse.create206Response(
            rangeContent, 
            contentType, 
            range.start, 
            range.end, 
            fileSize
        ));
        
        System.out.println("  Respuesta: 206 Partial Content (bytes " + 
                          range.start + "-" + range.end + "/" + fileSize + ")");
    }

    /**
     * Parsea la cabecera Range (ej: "bytes=0-1023")
     */
    private RangeInfo parseRangeHeader(String rangeHeader, long fileSize) {
        String bytesSpec = rangeHeader.replace("bytes=", "").trim();
        String[] parts = bytesSpec.split("-");
        
        long start = Long.parseLong(parts[0]);
        long end = (parts.length > 1 && !parts[1].isEmpty()) 
                   ? Long.parseLong(parts[1]) 
                   : fileSize - 1;
        
        return new RangeInfo(start, end);
    }

    /**
     * Clase interna para almacenar información de rango
     */
    private static class RangeInfo {
        final long start;
        final long end;
        
        RangeInfo(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }
}

/**
 * Almacena el estado de una conexión de cliente.
 * Cada conexión TCP tiene su propio contexto adjunto a su SelectionKey.
 */
class ClientContext {
    
    private static final int BUFFER_SIZE = 8192; // 8 KB
    
    private final ByteBuffer readBuffer;
    private ByteBuffer writeBuffer;
    private boolean keepAlive;

    public ClientContext() {
        this.readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.writeBuffer = null;
        this.keepAlive = true;
    }

    // ═══════════════════════════════════════════════════════════════
    // GETTERS Y SETTERS
    // ═══════════════════════════════════════════════════════════════

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public ByteBuffer getWriteBuffer() {
        return writeBuffer;
    }

    public void setWriteBuffer(ByteBuffer writeBuffer) {
        this.writeBuffer = writeBuffer;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public void resetWriteBuffer() {
        this.writeBuffer = null;
    }
}

/**
 * Representa una solicitud HTTP parseada.
 * Contiene el método, ruta, cabeceras y otra información relevante.
 */
class HttpRequest {
    
    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final boolean keepAlive;

    public HttpRequest(String method, String path, Map<String, String> headers) {
        this.method = method;
        this.path = normalizePath(path);
        this.headers = headers != null ? headers : new HashMap<>();
        this.keepAlive = determineKeepAlive();
    }

    /**
     * Normaliza la ruta: "/" se convierte en "/index.html"
     */
    private String normalizePath(String path) {
        if (path == null || path.equals("/")) {
            return "/index.html";
        }
        return path;
    }

    /**
     * Determina si la conexión debe mantenerse viva
     */
    private boolean determineKeepAlive() {
        String connection = headers.get("Connection");
        return !"close".equalsIgnoreCase(connection);
    }

    // ═══════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name);
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public Map<String, String> getHeaders() {
        return new HashMap<>(headers); // Copia defensiva
    }
}

/**
 * Constructor de respuestas HTTP.
 * Proporciona métodos estáticos para crear respuestas 200, 206 y 404.
 */
class HttpResponse {
    
    private static final String CRLF = "\r\n";
    private static final String HTTP_VERSION = "HTTP/1.1";

    /**
     * Crea una respuesta 404 Not Found
     */
    public static ByteBuffer create404Response() {
        String body = "<html><body><h1>404 Not Found</h1><p>El recurso solicitado no existe.</p></body></html>";
        
        StringBuilder response = new StringBuilder();
        response.append(HTTP_VERSION).append(" 404 Not Found").append(CRLF);
        response.append("Content-Type: text/html; charset=UTF-8").append(CRLF);
        response.append("Content-Length: ").append(body.length()).append(CRLF);
        response.append("Connection: close").append(CRLF);
        response.append(CRLF);
        response.append(body);
        
        return ByteBuffer.wrap(response.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Crea una respuesta 200 OK con el contenido completo del archivo
     */
    public static ByteBuffer create200Response(byte[] fileContent) {
        StringBuilder headers = new StringBuilder();
        headers.append(HTTP_VERSION).append(" 200 OK").append(CRLF);
        headers.append("Content-Length: ").append(fileContent.length).append(CRLF);
        headers.append("Connection: keep-alive").append(CRLF);
        headers.append(CRLF);
        
        byte[] headerBytes = headers.toString().getBytes(StandardCharsets.UTF_8);
        
        // Crear buffer combinado: cabeceras + cuerpo
        ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + fileContent.length);
        buffer.put(headerBytes);
        buffer.put(fileContent);
        buffer.flip();
        
        return buffer;
    }

    /**
     * Crea una respuesta 206 Partial Content (para solicitudes de rango)
     */
    public static ByteBuffer create206Response(byte[] rangeContent, 
                                                 String contentType,
                                                 long start, 
                                                 long end, 
                                                 long totalSize) {
        StringBuilder headers = new StringBuilder();
        headers.append(HTTP_VERSION).append(" 206 Partial Content").append(CRLF);
        headers.append("Content-Type: ").append(contentType).append(CRLF);
        headers.append("Content-Range: bytes ").append(start).append("-")
               .append(end).append("/").append(totalSize).append(CRLF);
        headers.append("Content-Length: ").append(rangeContent.length).append(CRLF);
        headers.append("Connection: keep-alive").append(CRLF);
        headers.append(CRLF);
        
        byte[] headerBytes = headers.toString().getBytes(StandardCharsets.UTF_8);
        
        // Crear buffer combinado: cabeceras + cuerpo
        ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + rangeContent.length);
        buffer.put(headerBytes);
        buffer.put(rangeContent);
        buffer.flip();
        
        return buffer;
    }
}

/**
 * Detecta el tipo de contenido (MIME type) basándose en la extensión del archivo.
 */
class ContentTypeDetector {
    
    private static final Map<String, String> MIME_TYPES = new HashMap<>();
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";
    
    static {
        // Tipos de texto
        MIME_TYPES.put(".html", "text/html");
        MIME_TYPES.put(".htm", "text/html");
        MIME_TYPES.put(".css", "text/css");
        MIME_TYPES.put(".js", "application/javascript");
        MIME_TYPES.put(".json", "application/json");
        MIME_TYPES.put(".xml", "application/xml");
        MIME_TYPES.put(".txt", "text/plain");
        
        // Imágenes
        MIME_TYPES.put(".jpg", "image/jpeg");
        MIME_TYPES.put(".jpeg", "image/jpeg");
        MIME_TYPES.put(".png", "image/png");
        MIME_TYPES.put(".gif", "image/gif");
        MIME_TYPES.put(".svg", "image/svg+xml");
        MIME_TYPES.put(".ico", "image/x-icon");
        MIME_TYPES.put(".webp", "image/webp");
        
        // Video
        MIME_TYPES.put(".mp4", "video/mp4");
        MIME_TYPES.put(".webm", "video/webm");
        MIME_TYPES.put(".avi", "video/x-msvideo");
        
        // Audio
        MIME_TYPES.put(".mp3", "audio/mpeg");
        MIME_TYPES.put(".wav", "audio/wav");
        MIME_TYPES.put(".ogg", "audio/ogg");
        
        // Documentos
        MIME_TYPES.put(".pdf", "application/pdf");
        MIME_TYPES.put(".zip", "application/zip");
        MIME_TYPES.put(".tar", "application/x-tar");
        MIME_TYPES.put(".gz", "application/gzip");
    }

    /**
     * Detecta el tipo MIME basándose en el nombre del archivo
     * @param filename Nombre del archivo (puede incluir la ruta)
     * @return El tipo MIME correspondiente o "application/octet-stream" si no se reconoce
     */
    public static String detect(String filename) {
        if (filename == null || filename.isEmpty()) {
            return DEFAULT_MIME_TYPE;
        }
        
        // Obtener la extensión del archivo
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            return DEFAULT_MIME_TYPE;
        }
        
        String extension = filename.substring(lastDotIndex).toLowerCase();
        return MIME_TYPES.getOrDefault(extension, DEFAULT_MIME_TYPE);
    }
}


/**
 * Maneja todas las operaciones de lectura de archivos.
 * Proporciona métodos para leer archivos completos y rangos específicos.
 */
class FileManager {

    /**
     * Lee un archivo completo y retorna su contenido como array de bytes
     */
    public byte[] readCompleteFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[8192]; // Buffer de 8 KB
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            
            return baos.toByteArray();
        }
    }

    /**
     * Lee un rango específico de bytes de un archivo
     * @param file El archivo a leer
     * @param start Byte inicial (inclusive)
     * @param end Byte final (inclusive)
     * @return Array de bytes con el contenido del rango
     */
    public byte[] readFileRange(File file, long start, long end) throws IOException {
        long rangeLength = end - start + 1;
        
        if (rangeLength > Integer.MAX_VALUE) {
            throw new IOException("Rango demasiado grande: " + rangeLength + " bytes");
        }
        
        byte[] data = new byte[(int) rangeLength];
        
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            raf.readFully(data);
        }
        
        return data;
    }
}