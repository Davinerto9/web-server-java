# Java HTTP Web Server – Enfoques de NIO vs BIO

David Chicué Romero - A00405613

Este proyecto contiene 2 implementaciones  de un **servidor web HTTP/1.1** usando Java puro con enfoque en concurrencia múltiple. El enfoque inicial de la asignación era implementar la transición de un patrón Thread per request a un Executor (Command) con fixed Thread Pools sin Channels Non Blocking y hacer funciones básicas de un servidor web HTTP. Dado a mis ganas de indagación y proactividad, quise innovar y salir de la zona de confort con efoques I/O tradicionales (ServerSocket, Socket, InputStrem, BufferedReader etc.), es por eso que en este proyecto se presentarán nuevas formas más efectivas y escalables de servidores web Multi concurrentes,  utilizando dos modelos de I/O distintos (Sin Frameworks) para mostrar el constraste entre cada uno:

* 🔹 **NIO No Bloqueante (Selector / Reactor Pattern)**
* 🔹 **BIO Bloqueante (Thread-per-request con ThreadPool)**

El objetivo del proyecto es entender cómo funcionan internamente los servidores web y comparar arquitecturas de concurrencia.

---

# Características

Ambas implementaciones soportan:

* Método `GET`
* Archivos estáticos (`html`, `css`, `js`, `jpg`, `mp4`, `pdf`, `svg`, `mp3`, etc.)
* `Content-Type` dinámico
* `Content-Length`
* `Connection: keep-alive`
* `Range Requests` (`206 Partial Content`)
* `404 Not Found`

---

# Arquitectura

## 1. Servidor NIO (No Bloqueante)

### Modelo: **Reactor Pattern**

Utiliza:

* `Selector`
* `ServerSocketChannel`
* `SocketChannel`
* `ByteBuffer`
* Modo no bloqueante (`configureBlocking(false)`)

### Flujo de ejecución

1. Se crea un `Selector`
2. Se registra el `ServerSocketChannel` con `OP_ACCEPT`
3. El servidor entra en un loop:

```java
selector.select();
```

4. El selector notifica eventos:

   * `ACCEPT`
   * `READ`
   * `WRITE`

5. Se maneja el estado de cada conexión mediante:

```java
class ClientContext {
    ByteBuffer readBuffer;
    ByteBuffer writeBuffer;
    boolean keepAlive;
}
```

### Características

* Un solo hilo maneja múltiples conexiones
* Alta escalabilidad
* Manejo manual del estado
* Mayor complejidad

---

## 2. Servidor BIO (Bloqueante)

### Modelo: **Thread-per-request**

Utiliza:

* `ServerSocketChannel` en modo bloqueante
* `ExecutorService` (pool fijo de 50 hilos)
* Cada conexión es atendida por un `Runnable`

```java
pool.execute(new HttpRequest(client));
```

### Flujo

1. `server.accept()` bloquea hasta nueva conexión
2. Se asigna un hilo del pool
3. El hilo:

   * Lee la petición
   * Procesa
   * Envía respuesta
   * Mantiene la conexión si es keep-alive

### Características

* Más simple
* Cada conexión ocupa un hilo
* Escalabilidad limitada por número de hilos

---

# Comparación NIO vs BIO

| Característica     | NIO (Selector) | BIO (Thread Pool)  |
| ------------------ | -------------- | ------------------ |
| Modelo             | Reactor        | Thread-per-request |
| Bloqueante         | ❌ No           | ✅ Sí               |
| Hilos por conexión | 1 compartido   | 1 por cliente      |
| Escalabilidad      | Alta           | Media              |
| Complejidad        | Alta           | Baja               |
| Uso de memoria     | Bajo           | Más alto           |

---

# Soporte HTTP

## 200 OK

Entrega archivo completo.

## 206 Partial Content

Soporta:

```
Range: bytes=START-END
```

Permite:

* Streaming de video
* Reanudar descargas
* Saltos en archivos grandes

## 404 Not Found

Cuando el archivo no existe.

---

# Tipos MIME Soportados

* `text/html`
* `text/css`
* `application/javascript`
* `image/jpeg`
* `image/gif`
* `image/svg+xml`
* `image/x-icon`
* `video/mp4`
* `audio/mpeg`
* `application/pdf`
* `application/octet-stream` (default)

---

# Cómo Ejecutar

Compilar:

```bash
javac WebServerSelector.java
javac WebServer.java
```

Ejecutar NIO:

```bash
java WebServerSelector
```

Ejecutar BIO:

```bash
java WebServer
```

Abrir en navegador:

```
http://localhost:6789
```

---

# Consideraciones Importantes

## 1. Seguridad

Actualmente no se valida path traversal:

```
GET /../../etc/passwd
```

Debe normalizarse la ruta y restringirse a un directorio raíz.

---

## 2. Memoria

* NIO carga archivos completos en memoria (`byte[]`)
* BIO transmite en streaming por bloques

Para producción, NIO debería usar streaming (`FileChannel.transferTo()`).

---

## 3. No soporta

* POST
* HTTPS (TLS)
* HTTP/2
* Chunked encoding
* Compresión gzip
* ETag / Cache-Control

Es un servidor **educativo**, no para producción.

---

# Objetivos del Proyecto

Este proyecto permite entender:

* Cómo funciona HTTP a bajo nivel
* Qué es I/O bloqueante vs no bloqueante
* Cómo funciona el patrón Reactor
* Cómo escalan servidores como:

  * Nginx (event-driven)
  * Apache clásico (thread-based)
  * Node.js (event loop)

---

# Futuras Mejoras

* Streaming real con `FileChannel.transferTo`
* Soporte POST
* Soporte HTTPS (TLS)
* Timeout de conexiones keep-alive
* Compresión gzip
* Manejo robusto de errores HTTP

---

# Licencia

Proyecto educativo libre para uso académico.

---

Si este proyecto te ayudó a entender networking en Java, ⭐ dale una estrella al repo.
