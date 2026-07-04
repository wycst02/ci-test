# wastnet

[![Java CI](https://github.com/wycst02/wast-net/actions/workflows/maven.yml/badge.svg)](https://github.com/wycst02/wast-net/actions/workflows/maven.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-8-green.svg)](https://www.oracle.com/java/)
[![codecov](https://codecov.io/gh/wycst/wast-net/branch/main/graph/badge.svg)](https://codecov.io/gh/wycst/wast-net)

**wastnet** is a lightweight, high-performance Java NIO network framework based on the Reactor multi-thread pattern, providing complete TCP and HTTP server/client implementations. Zero third-party dependencies, only relies on JDK.

---

## Features

### Core Features

- **Reactor Multi-thread Architecture** - Single Acceptor thread + multiple Worker threads (independent Selectors), lock-free design
- **Zero Dependencies** - Only relies on JDK, no third-party libraries
- **High Performance** - Zero-copy (`FileChannel.transferTo`), bitwise batch byte scanning
- **Flexible Thread Model** - Supports synchronous/asynchronous dual execution modes, auto-adaptation
- **SSL/TLS** - PEM certificate loading, first-byte sniffing for auto-detection of plaintext/encrypted connections
- **TCP Client** - NIO client with auto-reconnect, custom codec, SSL/TLS
- **SSE (Server-Sent Events)** - Event emitter with auto-close timeout, custom event types

### HTTP Server Features

| Feature | Description |
|:--------|:------------|
| **HTTP/1.1** | Full support for GET/POST/PUT/DELETE/PATCH, Pipeline, Keep-Alive, SSE |
| **HTTP/2 (h2/h2c)** | HPACK header compression, Huffman encoding, flow control, multiplexing, ALPN negotiation |
| **103 Early Hints** | Static resource preloading hints (RFC 8297), dual-protocol support for H1/H2 |
| **Router** | Exact match, prefix match, regex match, HTTP method filtering |
| **Reverse Proxy** | URL rewriting, header variables (`$remote_addr` etc.), H2→H1 protocol conversion |
| **WebSocket** | Frame encoding/decoding, text/binary/ping-pong/fragmented frames |
| **Static Resources** | Zero-copy file sending, ETag/Last-Modified caching, streaming GZIP compression |
| **File Upload** | Multipart/Form-Data parsing, streaming body reading |
| **Chunked Encoding** | Bidirectional Chunked Transfer Encoding for request/response |
| **GZIP Compression** | Auto-GZIP compression (configurable threshold, MIME type filtering) |

### Security Features

- SSL/TLS encrypted transport (JKS/PEM certificate formats)
- Direct PEM certificate loading (no keytool conversion needed)
- Path traversal protection (`..` detection)
- HTTP method whitelist (static resources only allow GET by default)
- Request size limits (URI length, Header size, Body size)
- Connection timeout detection (auto-close idle connections)
- ALPN protocol negotiation (h2, http/1.1)
- **Connection Filtering** - TCP accept-level blacklist/whitelist, IP rate limiting (`ConnectionFilter`)

---

## Quick Start

### Requirements

- JDK 8 or higher
- Maven 3.x

> **Note**: HTTP/2 over TLS (h2) requires ALPN negotiation, which needs JDK 9+.
> HTTP/2 cleartext (h2c / H2_PRIOR_KNOWLEDGE) has no such restriction and works on JDK 8+.

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.wycst</groupId>
    <artifactId>wastnet</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### Basic HTTP Server

```java
import io.github.wycst.wastnet.http.HTTPServer;

HTTPServer server = HTTPServer.of(8080)
        .requestHandler((request, response) -> {
            response.contentType("application/json;charset=utf-8")
                    .body("{\"message\": \"Hello World\"}".getBytes());
        })
        .start();
```

### HTTP Server with Router

```java
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

HttpRouterHandler router = new HttpRouterHandler();

// Exact match
router.get("/user", (path, request, response) -> {
    response.body("User page".getBytes());
});

// Prefix match
router.route("/api", (path, request, response) -> {
    response.body(("API: " + path).getBytes());
});

// Regex match
router.route("^/v\\d+/resource$", routeHandler);

HTTPServer.of(8080).requestHandler(router).start();
```

### HTTPS Server (PEM Certificate)

```java
HTTPServer.of(8443)
        .pemSSL("cert/cert.pem", "cert/server.pem")
        .h2()
        .requestHandler(router)
        .start();
```

### WebSocket

```java
router.ws("/ws", new WebSocketResource(30) {
    public void onOpen(WebSocketConnection conn) {
        System.out.println("Connected: " + conn.id());
    }
    public void onMessage(WebSocketConnection conn, String msg) throws IOException {
        conn.sendText("Echo: " + msg);
    }
    public void onClose(WebSocketConnection conn, int code, String reason) {
        System.out.println("Closed: " + conn.id());
    }
});
```

### Reverse Proxy

```java
router.proxy("/rest", "http://backend:8080");  // Quick way

// Or with full configuration
router.proxy("/rest", HttpProxyConfig.target("http://backend:8080")
        .upgrade(true)
        .readTimeout(5000)
        .rewrite(path -> path.replaceFirst("^/rest", "")));
```

### Static Resource Serving + 103 Early Hints

```java
router.resource(new HttpResourceHandler("/", "/var/www")
        .earlyHints(
                "<$base_path/style.css>; rel=preload; as=style",
                "<$base_path/app.js>; rel=preload; as=script"
        ));
// $base_path is automatically replaced by the router with the contextPath
```

---

## Architecture

### Reactor Multi-thread Model

```
┌──────────────────────────────────────────────────────────────────────┐
│                          TCPServer                                     │
├──────────────────────────────────────────────────────────────────────┤
│  ChannelAcceptDispatcher (1 thread) ← Handles Accept                   │
│         │                                                              │
│         │  Round-robin distribution (AtomicInteger modulo)             │
│         ↓                                                              │
│  ChannelReaderWorker[0..N] (multi-thread, independent Selectors)       │
│  ┌──────┐ ┌──────┐ ┌──────┐           ┌──────┐                        │
│  │ W-0  │ │ W-1  │ │ W-2  │    ...    │ W-N  │                        │
│  └──────┘ └──────┘ └──────┘           └──────┘                        │
│         │                                                              │
│         ↓                                                              │
│  ChannelRunner / ChannelSSLRunner (sync/async execution)               │
│         ↓                                                              │
│  ChannelReader → ChannelHandler (business processing)                  │
└──────────────────────────────────────────────────────────────────────┘
```

### Design Advantages

| Feature | Description |
|:--------|:------------|
| **Lock-free Design** | Each Worker has its own Selector, connections are fixed-allocated, no cross-thread contention |
| **Zero Copy** | `FileChannel.transferTo` for file sending, kernel-space direct transfer |
| **Batch Byte Scanning** | Long-word read + bitmask for 8-byte parallel delimiter detection |
| **Dynamic Execution** | Fast connections execute synchronously (zero thread switching), slow connections execute asynchronously (non-blocking Workers) |
| **Connection-level Buffer Reuse** | Each connection has its own read/write buffers, reducing GC |

---

## TCP Client

`TcpClient` is a NIO-based TCP client with auto-reconnect (exponential backoff), custom codec, and SSL/TLS support.

```java
import io.github.wycst.wastnet.socket.tcp.TcpClient;

TcpClient client = TcpClient.of("127.0.0.1", 8080)
        .channelHandler(new ChannelHandler<ByteBuffer>() {
            public void onHandle(ChannelContext ctx, ByteBuffer message) throws IOException {
                byte[] data = new byte[message.remaining()];
                message.get(data);
                System.out.println("Received: " + new String(data));
            }
        })
        .connect();
```

### Auto-Reconnect

```java
TcpClient client = TcpClient.of("127.0.0.1", 8080)
        .autoReconnect(true)            // Enable auto-reconnect
        .reconnectAttempts(10)          // Max retry attempts (0 = infinite)
        .reconnectDelay(1000)           // Initial delay (ms, exponential backoff)
        .channelHandler(handler)
        .connect();
```

### Custom Protocol Codec

```java
// Using ObjectCodec (Magic + BodyLength + SeqID + CRC16)
ObjectProtocol protocol = new ObjectProtocol() {
    public Object decode(byte[] data) throws Exception {
        return new String(data, "UTF-8");
    }
    public byte[] encode(Object msg) throws Exception {
        return ((String) msg).getBytes("UTF-8");
    }
};

TcpClient client = TcpClient.of("127.0.0.1", 8080)
        .channelCodec(new ObjectCodec<String>(65536, protocol))
        .channelHandler(myHandler)
        .connect();
```

---

## Protocol Codec

wastnet provides built-in TCP protocol codecs for message framing and assembly in custom binary protocols.

### LengthFrameCodec — Generic Length-Prefixed Frame Protocol

Supports 1-4 byte length fields, variable headers, trailer checksums, and byte order:

```java
// 4-byte header, length field at offset=2, 2 bytes, max body 64KB
LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 2, 2, 65536);

// Little-endian byte order
codec.byteOrder(ByteOrder.LITTLE_ENDIAN);

// With trailer (e.g. CRC16)
LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 65536, 2, false);

// Usage in TCP server
TCPServer server = TCPServer.of(port)
        .channelCodec(codec)
        .channelHandler(handler)
        .start();
```

### ObjectCodec — Object Message Protocol

Built-in message protocol: 12-byte header (Magic `0x57534E54` + BodyLength + SeqID) + Payload + CRC16 trailer.

```java
ObjectProtocol protocol = new ObjectProtocol() {
    public Object decode(byte[] data) throws Exception { ... }
    public byte[] encode(Object msg) throws Exception { ... }
};

// TCP server
TCPServer.of(port)
    .channelCodec(new ObjectCodec<String>(65536, protocol))
    .channelHandler(handler)
    .start();

// TCP client
TcpClient.of(host, port)
    .channelCodec(new ObjectCodec<String>(65536, protocol))
    .channelHandler(handler)
    .connect();
```

See `docs/02-guide/length-codec-guide.md` for details.

---

## HTTP Routing (HttpRouterHandler)

### Route Types

| Method | Match Mode | Example |
|:-------|:-----------|:--------|
| `get/post/put/delete/patch` | Exact + Method filter | `router.get("/user", handler)` |
| `exactRoute` | Exact (any method) | `router.exactRoute("/health", handler)` |
| `route` | Prefix match (default) | `router.route("/api", handler)` matches `/api/xxx` |
| `route("^pattern")` | Regex match | `router.route("^/v\\d+/resource$", handler)` |
| `resource` | Static resource serving | `router.resource(new HttpResourceHandler("/", "/var/www"))` |
| `proxy` | Reverse proxy | `router.proxy("/api", "http://backend:8080")` |
| `ws` | WebSocket | `router.ws("/ws", new WebSocketResource())` |
| `h2c` | H2C upgrade | `router.h2c("/h2c")` |
| `sse` | SSE events | `router.route("/events", new SseHandler() {...})` |

Method filtering also supports `HttpMethodRoute` builder:

```java
router.exactRoute("/api", new HttpMethodRoute()
    .get(getHandler)    // GET requests
    .post(postHandler)  // POST requests
    .put(putHandler)    // PUT requests
);
```

### Context Path

```java
HttpRouterHandler router = new HttpRouterHandler("/my-app");
// Request /my-app/api/users → subPath = /api/users
```

### Health Check

```java
router.healthRoute("/health");  // Built-in endpoint, returns uptime, route stats, etc.
```

### Reverse Proxy Configuration

```java
HttpProxyConfig config = HttpProxyConfig.target("http://upstream:8080")
    .upgrade(true)                  // Supports H2→H1 protocol conversion
    .rewrite(path -> path.replaceFirst("^/api", ""))  // Path rewriting
    .connectionTimeout(3000)        // Connection timeout
    .readTimeout(5000)              // Read timeout
    .changeOrigin(true)             // Modify Host header
    .addHeader("X-Real-IP", "$remote_addr");  // Dynamic header variables

router.proxy("/api", config);       // Register with the router
```

Supported header variables: `$remote_addr`, `$remote_port`, `$host`, `$scheme`, `$request_uri`, `$query_string`, `$server_addr`, `$server_port`

---

## HTTP/2 Support

### Features

- HPACK header compression (static table + dynamic table)
- Huffman encoding/decoding (high-performance bitwise implementation)
- Flow control (connection-level + stream-level WINDOW_UPDATE)
- Multiplexing (concurrent Stream processing)
- H2C upgrade (non-encrypted H2)
- H2→H1 proxy conversion
- 103 Early Hints
- ALPN negotiation (`.h2()`)

### Enable H2

```java
HTTPServer.of(8443)
    .pemSSL("cert.pem", "key.pem")
    .h2()
    .requestHandler(router)
    .start();
```

> **JDK Version Requirement**: h2 (HTTP/2 over TLS) relies on ALPN negotiation and requires JDK 9+.
> h2c (HTTP/2 Cleartext) uses plaintext HTTP Upgrade and works on JDK 8+ (see [h2c upgrade](docs/02-guide/h2-server-integration.md#h2c-upgrade-cleartext)).
> For intranet scenarios, use `.applicationProtocols("h2c")` to enable H2_PRIOR_KNOWLEDGE mode and save one RTT (HTTP/1.1 fallback is always automatically supported, no need to declare).

---

## 103 Early Hints

`HttpResourceHandler` supports sending 103 Early Hints (RFC 8297), notifying the browser to preload resources before returning index.html:

```java
new HttpResourceHandler("/", docBase)
    .earlyHints(
        "<$base_path/style.css>; rel=preload; as=style",
        "<$base_path/app.js>; rel=preload; as=script"
    );
```

- `$base_path` is automatically replaced with `contextPath`, no manual concatenation needed
- H1 sends raw 103 text, H2 sends HEADERS frame (`:status=103`)
- Only triggered when the default index page is requested, does not affect specific resource requests

---

## Static Resource Serving

```java
// Basic usage
router.resource(new HttpResourceHandler("/", "/var/www"));

// Custom default file
router.resource(new HttpResourceHandler("/", "/var/www", new File("index.htm")));

// Security control
new HttpResourceHandler("/", docBase)
    .allowAllMethods()     // Allow non-GET methods (GET only by default)
    .notAllowedBody("Custom 405");  // Custom 405 response
```

- Auto-detects `index.html` / `index.htm` as default pages
- Path traversal protection (`..` detection)
- Only `GET` method allowed by default, others return 405

---

## WebSocket

| Event | Method | Description |
|:------|:-------|:------------|
| Connection Open | `onOpen(WebSocketConnection)` | New connection |
| Text Message | `onMessage(WebSocketConnection, String)` | UTF-8 text |
| Binary Message | `onBinary(WebSocketConnection, byte[])` | Binary data |
| Connection Close | `onClose(WebSocketConnection, int, String)` | Close event |
| Error Close | `onErrorClose(WebSocketConnection)` | Abnormal disconnection |

`WebSocketConnection` API:

```java
connection.sendText("message");    // Send text
connection.sendBinary(data);       // Send binary
connection.ping();                 // Ping frame
connection.close();                // Close connection
```

Supports configurable idle timeout (seconds):

```java
new WebSocketResource(60) { ... }  // Auto-close after 60 seconds of inactivity
```

---

## Server-Sent Events (SSE)

Server-sent events (SSE) support via `SseEmitter`, thread-safe with auto-close timeout.

### Basic Usage

```java
router.route("/events", new SseHandler() {
    @Override
    public void onOpen(SseEmitter emitter) {
        for (int i = 0; i < 10; i++) {
            emitter.emit("data", "count: " + i);
            Thread.sleep(1000);
        }
        emitter.close();
    }

    @Override
    public void onError(SseEmitter emitter, Throwable t) {
        System.err.println("Client disconnected");
    }
});
```

### SseEmitter API

| Method | Description |
|:-------|:------------|
| `emit(String data)` | Send data-only event |
| `emit(String event, String data)` | Send named event |
| `emit(String event, String data, String id, Integer retry)` | Full control |
| `close()` | Close connection |
| `setTimeout(long millis)` | Auto-close after timeout |

See `docs/02-guide/sse-guide.md` for details.

---

## File Upload

### Basic Usage

```java
router.exactRoute("/upload", (path, request, response) -> {
    if (!request.isMultipart()) {
        response.status(400).body("Not multipart".getBytes());
        return;
    }

    // Iterate over upload fields
    for (String fieldName : request.getMultipartFieldNames()) {
        MultipartField field = request.getMultipartField(fieldName);

        if (field.isFile()) {
            // File field: zero-copy via transferTo
            field.transferTo(new File("/tmp/" + field.getFileName()));
        } else {
            // Regular form field
            System.out.println(fieldName + " = " + field.getDataAsString());
        }
    }

    response.body("OK".getBytes());
});
```

### Large File Support

Files exceeding the memory threshold are automatically spilled to temporary files, using streaming reads to avoid OOM:

```java
MultipartField field = request.getMultipartField("largeFile");
InputStream in = field.getInputStream();  // Streaming, no memory load
field.transferTo(new File("/dest/file.zip"));  // Or direct transfer to target file
```

### Configuration

| Property | Default | Description |
|:---------|:-------:|:------------|
| `wastnet.http.max-body-in-memory` | 2MB | Max request body kept in memory, beyond which streaming is used |
| `wastnet.http.body-max-size` | Unlimited | Max request body size limit (bytes), applies to all requests |
| `wastnet.http.enable-temp-file` | true | Enable temporary file spill |
| `wastnet.http.temp-file-dir` | System temp dir | Temporary file directory |

See `docs/02-guide/file-upload-guide.md` for detailed usage.

---

## SSL/TLS Configuration

### PEM Certificate (Recommended)

```java
// From file
server.pemSSL("cert.pem", "key.pem");

// From classpath
server.pemSSL("classpath:cert.pem", "classpath:key.pem");

// From input stream
server.pemSSL(certInputStream, keyInputStream);
```

### JKS Certificate

```java
server.ssl(true).sslContext(sslContext);
```

---

## Configuration

> All configuration items have sensible defaults. The server runs out-of-the-box without any configuration. Only tune these for production deployment or specific performance requirements.

For the complete configuration reference (all properties, loading priorities, and tuning guides), see:

- [HTTP Configuration Reference](docs/03-reference/http-conf-reference.md)
- [Socket Configuration Reference](docs/03-reference/socket-conf-reference.md)

### System Properties

| Property | Default | Description |
|:---------|:-------:|:------------|
| `wastnet.selector-timeout-ms` | 1000 | Selector timeout |
| `wastnet.max-runner-count` | 512 | Max concurrent connections |
| `wastnet.worker.select-timeout-ms` | 300 | Worker Selector timeout |
| `wastnet.socket.read-timeout-ms` | 0 | Read timeout (0 = unlimited) |
| `wastnet.socket.write-timeout-ms` | 30000 | Write timeout (ms) |
| `wastnet.ssl.handshake-timeout-ms` | 10000 | SSL handshake timeout |
| `wastnet.http2.initial.send-window-size` | 65535 | H2 initial send window |
| `wastnet.http2.debug` | false | H2 Debug logging |

### ServerConfig

```java
ServerConfig config = new ServerConfig();
config.setWorkerNum(8);                     // Worker thread count
config.setSyncRunner(true);                 // Sync execution mode
config.setReadBufferSize(8192);             // Read buffer
config.setWriteBufferSize(32768);           // Write buffer
config.setSslHandshakeTimeoutMs(5000);      // SSL handshake timeout
config.setAllowPlaintextWhenSslEnabled(true); // Allow plaintext on TLS port
```

---

## Startup Banner

The server prints a startup banner with URL and timing information by default. Use `startupBannerEnabled(false)` to disable it:

```java
HTTPServer.of(8080)
    .startupBannerEnabled(false)
    .requestHandler(...)
    .start();
```

Default output:

```
  wastnet/0.0.1-SNAPSHOT started in 1363 ms
  ➜  Local:   http://localhost:8080
  ➜  Network: http://10.252.31.235:8080
```

For full customization, extend `HTTPServer` and override `onStarted()`:

```java
class MyServer extends HTTPServer {
    @Override
    protected void onStarted() {
        System.out.println("MyServer ready on port " + port);
    }
}
```

> **Note**: Subclasses of `TCPServer` can also override `onStarted()` for custom output.

---

## Exception Handling

```java
router.notFoundHandler((request, response) -> {
    response.status(404).body("Custom 404".getBytes());
});

// Global exception handling
server.exceptionHandler((request, response, exception) -> {
    response.status(500)
            .contentType("application/json")
            .body("{\"error\":\"Internal Error\"}".getBytes());
});

// Debug mode (print stack traces)
server.printStackTraceError(true);
```

---

## Idle Connection Detection

`IdleStateHandler` triggers a callback when a connection has no read/write activity beyond the configured timeout:

```java
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import java.util.concurrent.TimeUnit;

server.idleStateHandler(new IdleStateHandler(10, 0, TimeUnit.SECONDS) {
    @Override
    public void onIdleTriggered(ChannelContext ctx, IdleStateHandler.IdleType idleType,
                                 long triggerTotalCount, long triggerConsecutiveCount) throws Throwable {
        System.out.println("Idle: conn=" + ctx.getId()
                + " type=" + idleType
                + " consecutive=" + triggerConsecutiveCount);
        // Close after 3 consecutive idle triggers (30s total)
        if (triggerConsecutiveCount >= 3) {
            ctx.close();
        }
    }
});
```

Constructor Parameters:

| Parameter | Description |
|:----------|:------------|
| `readerIdleTime` | Read idle timeout (≤ 0 disables) |
| `writerIdleTime` | Write idle timeout (≤ 0 disables) |
| `unit` | Time unit (`TimeUnit.SECONDS`, `MILLISECONDS`, etc.) |

Callback Parameters (`onIdleTriggered`):

| Parameter | Description |
|:----------|:------------|
| `idleType` | Idle type: `IdleType.Read` (read idle) or `IdleType.Write` (write idle) |
| `triggerTotalCount` | Total idle triggers since the connection was established |
| `triggerConsecutiveCount` | Consecutive idle triggers since the last read/write activity. Useful for graduated handling: warn on first, close after several |

---

## Connection Filter (ConnectionFilter)

Intercepts connections at the TCP accept stage, suitable for IP blacklist/whitelist, connection count rate limiting, etc. **Zero resource waste** (rejected connections do not create ChannelContext or ByteBuffer).

```java
// IP blacklist
final Set<String> blacklist = new HashSet<String>(Arrays.asList("192.168.1.100", "10.0.0.5"));
HTTPServer.of(8080)
    .connectionFilter(ch -> !blacklist.contains(
        ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress()))
    .requestHandler(router)
    .start();

// IP-level connection rate limiting
final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<String, AtomicInteger>();
HTTPServer.of(8080)
    .connectionFilter(ch -> {
        String ip = ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress();
        return counters.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet() <= 50;
    })
    .requestHandler(router)
    .start();
```

The `connectionFilter` executes in `AcceptDispatcher`. If the filter throws an exception or returns `false`, the connection is automatically closed and logged, without affecting the Acceptor main loop.

---

## Project Structure

```
wastnet/                               ← Parent project (pom)
├── pom.xml                              ← Module management, version management
│
├── wastnet-core/                       ← Core network framework (jar)
│   ├── pom.xml                          ← zero external dependencies
│   └── src/main/java/io/github/wycst/wastnet/
│       ├── socket/                  # TCP core module
│       │   ├── tcp/                 # Server, connection context
│       │   ├── handler/            # Business handlers, idle detection
│       │   ├── channel/            # Codecs
│       │   └── conf/               # Configuration management
│       ├── http/                   # HTTP core
│       │   ├── HTTPServer.java     # HTTP server
│       │   ├── handler/            # Router, resources, exception handlers
│       │   ├── h2/                 # HTTP/2 (HPACK/Huffman/frames/streams)
│       │   ├── proxy/              # Reverse proxy
│       │   ├── upgrade/            # Protocol upgrade (WebSocket/H2C)
│       │   └── reader/             # HTTP request decoding
│       ├── http3/                  # HTTP/3 reserved
│       ├── log/                    # Built-in logging
│       ├── util/                   # Utilities
│       └── exception/              # Exception definitions
│       └── src/main/resources/         # SPI configuration
│
├── wastnet-test/                       ← Tests and examples (jar)
│   ├── pom.xml                          # depends on wastnet-core
│   ├── src/main/java/                   # Test code + runnable examples
│   │   └── ... (HTTP decoders, HTTP/2, WebSocket, TCP tests)
│   ├── src/main/resources/              # Certificates, keystores, demo pages
│   └── test-files/                      # Test data files
│
├── docs/
│   ├── 02-guide/                       # User guides
│   │   ├── http-server-api.md
│   │   ├── http-router-guide.md
│   │   ├── response-api.md
│   │   ├── file-upload-guide.md
│   │   ├── websocket-advanced-api.md
│   │   ├── h2-server-integration.md
│   │   └── length-codec-guide.md
│   ├── 03-reference/                   # Configuration/protocol references
│   │   ├── http-conf-reference.md
│   │   ├── socket-conf-reference.md
│   │   ├── HTTP2_PROTOCOL.md
│   │   ├── huffman-table-design.md
│   │   ├── websocket-*.md
│   ├── 05-standards/                   # RFC standards
│   │   └── rfc*.md
│   └── 04-architecture/               # Architecture principles
│       └── TCPSERVER_ARCHITECTURE.md
├── SKILL.md                            # Coding standards
```

---

## Detailed Documentation

| Document | Content |
|:---------|:--------|
| `docs/02-guide/http-router-guide.md` | Route matching, Context Path, reverse proxy configuration |
| `docs/02-guide/response-api.md` | Response API: status codes, headers, chunked, GZIP, sendFile |
| `docs/02-guide/file-upload-guide.md` | File upload: Multipart API, large file streaming, configuration |
| `docs/02-guide/sse-guide.md` | SSE server push: Emitter API, timeout control |
| `docs/02-guide/websocket-advanced-api.md` | WebSocket advanced API: frame encoding, Ping-Pong, fragments |
| `docs/02-guide/h2-server-integration.md` | H2/H2C server setup: TLS ALPN, cleartext upgrade |
| `docs/02-guide/length-codec-guide.md` | Length-prefixed frame codec: custom binary protocols |
| `docs/03-reference/http-conf-reference.md` | HTTP configuration reference (with tuning tips) |
| `docs/03-reference/socket-conf-reference.md` | Socket configuration reference |
| `docs/03-reference/HTTP2_PROTOCOL.md` | H2 connection setup, frame structure, HPACK, flow control |
| `docs/03-reference/websocket-cheatsheet.md` | WebSocket frame type quick reference |
| `docs/03-reference/websocket-implementation-guide.md` | WebSocket implementation details |
| `docs/04-architecture/TCPSERVER_ARCHITECTURE.md` | TCP server architecture principles |
| `SKILL.md` | Coding standards (JDK 8 syntax, code style) |

---

## Performance Tuning

| Scenario | Recommended Workers | Mode | Buffer |
|:---------|:------------------:|:----:|:------:|
| API service (small payload) | CPU cores | Sync | 1-4 KB |
| Static files (large payload) | CPU cores × 2 | Async | 16-64 KB |
| Reverse proxy | CPU cores × 2 | Sync | 4-8 KB |
| WebSocket long connections | CPU cores | Async | 2-4 KB |

---

## Comparison with Other Frameworks

| Feature | wastnet | Netty | Vert.x |
|:--------|:--------:|:-----:|:------:|
| Dependencies | None | Multiple | Multiple |
| Learning curve | Low | Medium | Medium |
| HTTP routing | Built-in | Needs codec | Built-in |
| HTTP/2 | Built-in | Needs handler | Built-in |
| WebSocket | Built-in | Needs handler | Built-in |
| Reverse proxy | Built-in | Manual | Extension needed |
| Zero copy | ✅ | ✅ | ✅ |
| Memory pool | Connection-level reuse | Configurable | Configurable |

---

## Roadmap

- [x] HTTP/2 full implementation (HPACK + Huffman + flow control + multiplexing)
- [x] Reverse proxy (H2→H1 conversion, URL rewriting, header variables)
- [x] 103 Early Hints (RFC 8297)
- [x] TCP client (auto-reconnect, custom codec, SSL/TLS)
- [x] SSE (Server-Sent Events)
- [x] Protocol codec (LengthFrameCodec, ObjectCodec)
- [ ] HTTP/3 (QUIC) support (reserved)
- [ ] Connection pool
- [ ] UDP support
- [ ] Monitoring metrics (Prometheus integration)

---

## License

This project is licensed under the [Apache 2.0](LICENSE) License.
