# wastnet

[![Java CI](https://github.com/wycst02/wast-net/actions/workflows/maven.yml/badge.svg)](https://github.com/wycst02/wast-net/actions/workflows/maven.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-8-green.svg)](https://www.oracle.com/java/)
[![codecov](https://codecov.io/gh/wycst/wast-net/branch/main/graph/badge.svg)](https://codecov.io/gh/wycst/wast-net)

**wastnet** 是一个轻量级、高性能的 Java NIO 网络通信框架，基于 Reactor 多线程模式设计，提供 TCP 和 HTTP 服务器/客户端的完整实现。零第三方依赖，仅依赖 JDK。

---

## 特性

### 核心特性

- **Reactor 多线程架构** - 单 Acceptor 线程 + 多 Worker 线程（独立 Selector），无锁设计
- **零依赖** - 仅依赖 JDK，无第三方库
- **高性能** - 零拷贝（`FileChannel.transferTo`）、位运算批量扫描字节
- **灵活的线程模型** - 支持同步/异步双执行模式，自动适配
- **SSL/TLS** - 支持 PEM 证书加载、首字节嗅探自动识别明文/加密连接
- **TCP 客户端** - NIO 客户端，支持自动重连、自定义编解码、SSL/TLS
- **SSE (Server-Sent Events)** - 事件发射器，支持超时自动关闭、自定义事件类型

### HTTP 服务器特性

| 特性 | 说明 |
|:-----|:------|
| **HTTP/1.1** | 完整支持 GET/POST/PUT/DELETE/PATCH, Pipeline, Keep-Alive, SSE |
| **HTTP/2 (h2/h2c)** | HPACK 头部压缩、Huffman 编码、流控、多路复用、ALPN 协商 |
| **103 Early Hints** | 静态资源预加载提示（RFC 8297），H1/H2 双协议支持 |
| **路由分发** | 精确匹配、前缀匹配、正则匹配、HTTP 方法过滤 |
| **反向代理** | URL 重写、Header 变量（`$remote_addr` 等）、H2→H1 协议转换 |
| **WebSocket** | 帧编解码、文本/二进制/Ping-Pong/分片帧 |
| **静态资源** | 零拷贝发送、ETag/Last-Modified 缓存、GZIP 流式压缩 |
| **文件上传** | Multipart/Form-Data 解析、流式 body 读取 |
| **Chunked 编码** | 请求/响应双向 Chunked Transfer Encoding |
| **GZIP 压缩** | 自动 GZIP 压缩（可配置阈值、MIME 类型过滤） |

### 安全特性

- SSL/TLS 加密传输（JKS/PEM 证书格式）
- PEM 证书直接加载（无需 keytool 转换）
- 路径穿越防护（`..` 检测）
- HTTP 方法白名单（静态资源默认仅允许 GET）
- 请求大小限制（URI 长度、Header 大小、Body 大小）
- 连接超时检测（空闲连接自动关闭）
- ALPN 协议协商（h2, http/1.1）
- **连接过滤** — TCP accept 级黑/白名单、IP 限流（`ConnectionFilter`）

---

## 快速开始

### 环境要求

- JDK 8 或更高版本
- Maven 3.x

> **注**：HTTP/2 over TLS (h2) 基于 ALPN 协议协商，需要 JDK 9+。
> HTTP/2 cleartext (h2c / H2_PRIOR_KNOWLEDGE) 无此限制，JDK 8 即可使用。

### Maven 依赖

```xml
<dependency>
    <groupId>io.github.wycst</groupId>
    <artifactId>wastnet</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 基础 HTTP 服务器

```java
import io.github.wycst.wastnet.http.HTTPServer;

HTTPServer server = HTTPServer.of(8080)
        .requestHandler((request, response) -> {
            response.contentType("application/json;charset=utf-8")
                    .body("{\"message\": \"Hello World\"}".getBytes());
        })
        .start();
```

### 带路由的 HTTP 服务器

```java
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

HttpRouterHandler router = new HttpRouterHandler();

// 精确匹配
router.get("/user", new HttpRoute() {
    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.body("User page".getBytes());
    }
});

// 前缀匹配
router.route("/api", new HttpRoute() {
    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.body(("API: " + path).getBytes());
    }
});

// 正则匹配
router.route("^/v\\d+/resource$", routeHandler);

HTTPServer.of(8080).requestHandler(router).start();
```

### HTTPS 服务器（PEM 证书）

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

### 反向代理

```java
router.proxy("/rest", "http://backend:8080");  // 快速方式

// 或使用完整配置
router.proxy("/rest", HttpProxyConfig.target("http://backend:8080")
        .upgrade(true)
        .readTimeout(5000)
        .rewrite(path -> path.replaceFirst("^/rest", "")));
```

### 静态资源服务 + 103 Early Hints

```java
router.resource(new HttpResourceHandler("/", "/var/www")
        .earlyHints(
                "<$base_path/style.css>; rel=preload; as=style",
                "<$base_path/app.js>; rel=preload; as=script"
        ));
// $base_path 由路由器自动替换为 contextPath
```

---

## 架构设计

### Reactor 多线程模型

```
┌──────────────────────────────────────────────────────────────────────┐
│                          TCPServer                                     │
├──────────────────────────────────────────────────────────────────────┤
│  ChannelAcceptDispatcher (1 线程) ← 负责 Accept                        │
│         │                                                              │
│         │ 轮询分发（AtomicInteger 取模）                                │
│         ↓                                                              │
│  ChannelReaderWorker[0..N] (多线程, 独立 Selector)                      │
│  ┌──────┐ ┌──────┐ ┌──────┐           ┌──────┐                        │
│  │ W-0  │ │ W-1  │ │ W-2  │    ...    │ W-N  │                        │
│  └──────┘ └──────┘ └──────┘           └──────┘                        │
│         │                                                              │
│         ↓                                                              │
│  ChannelRunner / ChannelSSLRunner (同步/异步执行)                      │
│         ↓                                                              │
│  ChannelReader → ChannelHandler (业务处理)                             │
└──────────────────────────────────────────────────────────────────────┘
```

### 设计优势

| 特性 | 说明 |
|:-----|:------|
| **无锁设计** | 每个 Worker 独立 Selector，连接固定分配，无跨线程竞争 |
| **零拷贝** | `FileChannel.transferTo` 发送文件，内核空间直接传输 |
| **批量字节扫描** | 位掩码 + 长整型读取实现 8 字节并行分隔符检测 |
| **动态执行** | 快连接同步执行（零线程切换），慢连接异步执行（不阻塞 Worker） |
| **连接级缓冲区复用** | 每个连接持有独立读写缓冲区，减少 GC |

---

## TCP 客户端

`TcpClient` 是基于 NIO 的 TCP 客户端，支持自动重连（指数退避）、自定义编解码器、SSL/TLS。

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

### 自动重连

```java
TcpClient client = TcpClient.of("127.0.0.1", 8080)
        .autoReconnect(true)            // 启用自动重连
        .reconnectAttempts(10)          // 最大重试次数（0 表示无限）
        .reconnectDelay(1000)           // 初始延迟（毫秒，指数退避）
        .channelHandler(handler)
        .connect();
```

### 自定义协议编解码

```java
// 使用 ObjectCodec（Magic + BodyLength + SeqID + CRC16）
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

## HTTP 路由（HttpRouterHandler）

### 路由类型

| 方法 | 匹配方式 | 示例 |
|:-----|:---------|:-----|
| `get/post/put/delete/patch` | 精确匹配 + 方法过滤 | `router.get("/user", handler)` |
| `exactRoute` | 精确匹配（不限方法） | `router.exactRoute("/health", handler)` |
| `route` | 前缀匹配（默认） | `router.route("/api", handler)` 匹配 `/api/xxx` |
| `route("^pattern")` | 正则匹配 | `router.route("^/v\\d+/resource$", handler)` |
| `resource` | 静态资源服务 | `router.resource(new HttpResourceHandler("/", "/var/www"))` |
| `proxy` | 反向代理 | `router.proxy("/api", "http://backend:8080")` |
| `ws` | WebSocket | `router.ws("/ws", new WebSocketResource())` |
| `h2c` | H2C 升级 | `router.h2c("/h2c")` |
| `sse` | SSE 事件推送 | `router.route("/events", new SseHandler() {...})` |

方法过滤也支持 `HttpMethodRoute` builder 模式，为同一路径的不同方法指定不同 handler：

```java
router.exactRoute("/api", new HttpMethodRoute()
    .get(getHandler)    // GET 请求
    .post(postHandler)  // POST 请求
    .put(putHandler)    // PUT 请求
);

### Context Path

```java
HttpRouterHandler router = new HttpRouterHandler("/my-app");
// 请求 /my-app/api/users → subPath = /api/users
```

### 健康检查

```java
router.healthRoute("/health");  // 内置端点，返回运行时长、路由统计等
```

### 反向代理配置

完整代理配置能力，参考 `docs/02-guide/http-router-guide.md`：

```java
HttpProxyConfig config = HttpProxyConfig.target("http://upstream:8080")
    .upgrade(true)                  // 支持 H2→H1 协议转换
    .rewrite(path -> path.replaceFirst("^/api", ""))  // 路径重写
    .connectionTimeout(3000)        // 连接超时
    .readTimeout(5000)              // 读取超时
    .changeOrigin(true)             // 修改 Host 头
    .addHeader("X-Real-IP", "$remote_addr");  // 动态 Header 变量

router.proxy("/api", config);       // 注册到路由器
```

支持的 Header 变量：`$remote_addr`, `$remote_port`, `$host`, `$scheme`, `$request_uri`, `$query_string`, `$server_addr`, `$server_port`

---

## 协议编解码

wastnet 提供内置的 TCP 协议编解码器，用于自定义二进制协议的消息拆分和组装。

### LengthFrameCodec — 通用长度前缀帧协议

适用于自定义二进制协议，支持 1-4 字节长度字段、可变 header、trailer 校验、大小端字节序：

```java
// 4 字节 header，长度字段在 offset=2 占用 2 字节，body 最大 64KB
LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 2, 2, 65536);

// 使用小端字节序
codec.byteOrder(ByteOrder.LITTLE_ENDIAN);

// 包含 trailer（如 CRC16）
LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 65536, 2, false);

// TCP 服务器中使用
TCPServer server = TCPServer.of(port)
        .channelCodec(codec)
        .channelHandler(handler)
        .start();
```

### ObjectCodec — 对象消息协议

内置的完整消息协议：12 字节 header（Magic `0x57534E54` + BodyLength + SeqID）+ Payload + CRC16 trailer。

```java
ObjectProtocol protocol = new ObjectProtocol() {
    public Object decode(byte[] data) throws Exception { ... }
    public byte[] encode(Object msg) throws Exception { ... }
};

// TCP 服务端
TCPServer.of(port)
    .channelCodec(new ObjectCodec<String>(65536, protocol))
    .channelHandler(handler)
    .start();

// TCP 客户端
TcpClient.of(host, port)
    .channelCodec(new ObjectCodec<String>(65536, protocol))
    .channelHandler(handler)
    .connect();
```

详细文档参见 `docs/02-guide/length-codec-guide.md`。

---

## HTTP/2 支持

### 特性

- HPACK 头部压缩（静态表 + 动态表）
- Huffman 编码/解码（高性能位运算实现）
- 流控（连接级 + 流级 WINDOW_UPDATE）
- 多路复用（Stream 并发处理）
- H2C 升级（非加密 H2）
- H2→H1 代理转换
- 103 Early Hints
- ALPN 协商（`.h2()`）

### 启用 H2

```java
HTTPServer.of(8443)
    .pemSSL("cert.pem", "key.pem")
    .h2()
    .requestHandler(router)
    .start();
```

> **JDK 版本要求**：h2 (HTTP/2 over TLS) 依赖 ALPN 协商，需要 JDK 9+。
> h2c (HTTP/2 Cleartext) 基于明文 HTTP Upgrade，JDK 8 即可使用（参见 [h2c 升级](docs/02-guide/h2-server-integration.md#h2c-升级明文)）。
> 对于纯内网场景，也可通过 `.applicationProtocols("h2c")` 启用 H2_PRIOR_KNOWLEDGE 模式，减少一次 RTT（服务端始终自动支持 HTTP/1.1 回退，无需显式声明）。

---

## 103 Early Hints

`HttpResourceHandler` 支持发送 103 Early Hints（RFC 8297），在返回 index.html 之前通知浏览器预加载资源：

```java
new HttpResourceHandler("/", docBase)
    .earlyHints(
        "<$base_path/style.css>; rel=preload; as=style",
        "<$base_path/app.js>; rel=preload; as=script"
    );
```

- `$base_path` 自动替换为 `contextPath`，无需手动拼接
- H1 发送原始 103 文本，H2 发送 HEADERS 帧（`:status=103`）
- 仅在请求默认索引页时触发，不影响具体资源请求

---

## 静态资源服务

```java
// 基本用法
router.resource(new HttpResourceHandler("/", "/var/www"));

// 自定义默认文件
router.resource(new HttpResourceHandler("/", "/var/www", new File("index.htm")));

// 安全控制
new HttpResourceHandler("/", docBase)
    .allowAllMethods()     // 允许非 GET 方法（默认仅 GET）
    .notAllowedBody("Custom 405");  // 自定义 405 响应
```

- 自动查找 `index.html` / `index.htm` 作为默认页
- 路径穿越防护（`..` 检测）
- 默认仅允许 `GET` 方法，其余返回 405

---

## WebSocket

| 事件 | 方法 | 说明 |
|:-----|:-----|:------|
| 连接建立 | `onOpen(WebSocketConnection)` | 新连接 |
| 文本消息 | `onMessage(WebSocketConnection, String)` | UTF-8 文本 |
| 二进制消息 | `onBinary(WebSocketConnection, byte[])` | 二进制数据 |
| 连接关闭 | `onClose(WebSocketConnection, int, String)` | 关闭事件 |
| 错误关闭 | `onErrorClose(WebSocketConnection)` | 异常断连 |

`WebSocketConnection` API：

```java
connection.sendText("message");    // 发送文本
connection.sendBinary(data);       // 发送二进制
connection.ping();                 // Ping 帧
connection.close();                // 关闭连接
```

支持配置空闲超时（秒）：

```java
new WebSocketResource(60) { ... }  // 60 秒无消息自动关闭
```

---

## Server-Sent Events (SSE)

支持服务端推送事件（SSE），基于 `SseEmitter` 实现，线程安全，支持超时自动关闭。

### 基本用法

```java
router.route("/events", new SseHandler() {
    @Override
    public void onOpen(SseEmitter emitter) {
        // 持续推送事件
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

| 方法 | 说明 |
|:-----|:------|
| `emit(String data)` | 发送 data-only 事件 |
| `emit(String event, String data)` | 发送命名事件 |
| `emit(String event, String data, String id, Integer retry)` | 完整控制 |
| `close()` | 关闭连接 |
| `setTimeout(long millis)` | 超时自动关闭 |

详细文档参见 `docs/02-guide/sse-guide.md`。

---

wastnet 支持 `multipart/form-data` 格式的文件上传解析，自动在小字段（内存）和大文件（临时文件）之间切换。

### 基本用法

```java
router.exactRoute("/upload", new HttpRoute() {
    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        if (!request.isMultipart()) {
            response.status(400).body("Not multipart".getBytes());
            return;
        }

        // 遍历上传字段
        for (String fieldName : request.getMultipartFieldNames()) {
            MultipartField field = request.getMultipartField(fieldName);

            if (field.isFile()) {
                // 文件字段：transferTo 零拷贝写入
                field.transferTo(new File("/tmp/" + field.getFileName()));
            } else {
                // 普通字段：getDataAsString 获取文本值
                System.out.println(fieldName + " = " + field.getDataAsString());
            }
        }

        response.body("OK".getBytes());
    }
});
```

### 大文件支持

超过内存阈值的文件自动落盘到临时文件，使用流式读取避免 OOM：

```java
MultipartField field = request.getMultipartField("largeFile");
InputStream in = field.getInputStream();  // 流式读取，不加载到内存
field.transferTo(new File("/dest/file.zip"));  // 或直接传输到目标文件
```

### 配置项

| 属性 | 默认值 | 说明 |
|:-----|:------:|:------|
| `wastnet.http.max-body-in-memory` | 2MB | 请求 Body 内存上限，超过转为流式处理 |
| `wastnet.http.body-max-size` | 不限 | 请求 Body 最大总大小限制（字节），适用于所有请求 |
| `wastnet.http.enable-temp-file` | true | 是否启用临时文件 |
| `wastnet.http.temp-file-dir` | 系统临时目录 | 临时文件目录 |

详细使用指南参见 `docs/02-guide/file-upload-guide.md`。

---

## SSL/TLS 配置

### PEM 证书（推荐）

```java
// 从文件
server.pemSSL("cert.pem", "key.pem");

// 从 classpath
server.pemSSL("classpath:cert.pem", "classpath:key.pem");

// 从输入流
server.pemSSL(certInputStream, keyInputStream);
```

### JKS 证书

```java
server.ssl(true).sslContext(sslContext);
```

---

## 配置项

> 所有配置项均有缺省值，默认场景下无需任何配置即可运行。仅在生产部署或特定性能调优场景下才需要关注以下配置。

完整配置参考（含所有属性、加载优先级、场景调优建议）详见：

- [HTTP 配置参考](docs/03-reference/http-conf-reference.md)
- [Socket 配置参考](docs/03-reference/socket-conf-reference.md)

### 系统属性

| 属性 | 默认值 | 说明 |
|:-----|:------:|:-----|
| `wastnet.selector-timeout-ms` | 1000 | Selector 超时 |
| `wastnet.max-runner-count` | 512 | 最大并发连接数 |
| `wastnet.worker.select-timeout-ms` | 300 | Worker Selector 超时 |
| `wastnet.socket.read-timeout-ms` | 0 | 读取超时（0 表示不限） |
| `wastnet.socket.write-timeout-ms` | 30000 | 写入超时（毫秒） |
| `wastnet.ssl.handshake-timeout-ms` | 10000 | SSL 握手超时 |
| `wastnet.http2.initial.send-window-size` | 65535 | H2 初始发送窗口 |
| `wastnet.http2.debug` | false | H2 Debug 日志 |

### ServerConfig

```java
ServerConfig config = new ServerConfig();
config.setWorkerNum(8);                     // Worker 线程数
config.setSyncRunner(true);                 // 同步执行模式
config.setReadBufferSize(8192);             // 读缓冲区
config.setWriteBufferSize(32768);           // 写缓冲区
config.setSslHandshakeTimeoutMs(5000);      // SSL 握手超时
config.setAllowPlaintextWhenSslEnabled(true); // TLS 端口允许明文
```

---

## 启动 Banner

服务启动时默认输出 URL 和耗时信息。可通过 `startupBannerEnabled(false)` 关闭：

```java
HTTPServer.of(8080)
    .startupBannerEnabled(false)
    .requestHandler(...)
    .start();
```

默认输出示例：

```
  wastnet/0.0.1-SNAPSHOT started in 1363 ms
  ➜  Local:   http://localhost:8080
  ➜  Network: http://10.252.31.235:8080
```

也可通过继承 `HTTPServer` 重写 `onStarted()` 方法完全自定义：

```java
class MyServer extends HTTPServer {
    @Override
    protected void onStarted() {
        System.out.println("MyServer ready on port " + port);
    }
}
```

> **注意**：`TCPServer` 的子类同样可通过重写 `onStarted()` 自定义输出。

---

## 异常处理

```java
router.notFoundHandler((request, response) -> {
    response.status(404).body("Custom 404".getBytes());
});

// 全局异常处理
server.exceptionHandler((request, response, exception) -> {
    response.status(500)
            .contentType("application/json")
            .body("{\"error\":\"Internal Error\"}".getBytes());
});

// Debug 模式（打印堆栈）
server.printStackTraceError(true);
```

---

## 空闲连接检测

`IdleStateHandler` 在连接超过指定时间无读/写活动时触发回调：

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

构造参数说明：

| 参数 | 说明 |
|:-----|:------|
| `readerIdleTime` | 读取空闲超时（≤ 0 表示不检测） |
| `writerIdleTime` | 写入空闲超时（≤ 0 表示不检测） |
| `unit` | 时间单位（`TimeUnit.SECONDS`、`MILLISECONDS` 等） |

回调参数说明（`onIdleTriggered`）：

| 参数 | 说明 |
|:-----|:------|
| `idleType` | 空闲类型：`IdleType.Read`（读空闲）或 `IdleType.Write`（写空闲） |
| `triggerTotalCount` | 该连接自建立以来触发的空闲总次数 |
| `triggerConsecutiveCount` | 该连接自最后一次读写活动后连续触发的空闲次数。可用于分级处理：首次警告，多次后关闭 |

---

## 连接过滤器（ConnectionFilter）

在 TCP accept 阶段拦截连接，适用于 IP 黑/白名单、连接数限流等场景，**零资源浪费**（被拒绝的连接不会创建 ChannelContext 和 ByteBuffer）。

```java
// IP 黑名单
final Set<String> blacklist = new HashSet<String>(Arrays.asList("192.168.1.100", "10.0.0.5"));
HTTPServer.of(8080)
    .connectionFilter(ch -> !blacklist.contains(
        ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress()))
    .requestHandler(router)
    .start();

// IP 级连接数限流
final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<String, AtomicInteger>();
HTTPServer.of(8080)
    .connectionFilter(ch -> {
        String ip = ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress();
        return counters.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet() <= 50;
    })
    .requestHandler(router)
    .start();
```

`connectionFilter` 在 `AcceptDispatcher` 中执行，filter 抛异常或返回 `false` 时自动关闭连接并记录日志，不影响 Acceptor 主循环。

## 项目结构

```
wastnet/                               ← 父工程 (pom)
├── pom.xml                              ← 模块管理、版本管理
│
├── wastnet-core/                       ← 底层网络框架 (jar)
│   ├── pom.xml                          ← 零外部依赖
│   └── src/main/java/io/github/wycst/wastnet/
│       ├── socket/                  # TCP 核心模块
│       │   ├── tcp/                 # 服务器、连接上下文
│       │   ├── handler/            # 业务处理器、空闲检测
│       │   ├── channel/            # 编解码器
│       │   └── conf/               # 配置管理
│       ├── http/                   # HTTP 核心
│       │   ├── HTTPServer.java     # HTTP 服务器
│       │   ├── handler/            # 路由、资源、异常处理器
│       │   ├── h2/                 # HTTP/2 (HPACK/Huffman/帧/流)
│       │   ├── proxy/              # 反向代理
│       │   ├── upgrade/            # 协议升级 (WebSocket/H2C)
│       │   └── reader/             # HTTP 请求解码
│       ├── http3/                  # HTTP/3 预留
│       ├── log/                    # 内置日志
│       ├── util/                   # 工具类
│       └── exception/              # 异常定义
│       └── src/main/resources/         # SPI 配置
│
├── wastnet-test/                       ← 测试和示例 (jar)
│   ├── pom.xml                          # 依赖 wastnet-core
│   ├── src/main/java/                   # 测试代码 + 可运行示例
│   ├── src/main/resources/              # 证书、密钥库、演示页面
│   └── test-files/                      # 测试数据文件
│
├── docs/
│   ├── 02-guide/                       # 使用指南
│   │   ├── http-server-api.md
│   │   ├── http-router-guide.md
│   │   ├── response-api.md
│   │   ├── file-upload-guide.md
│   │   ├── websocket-advanced-api.md
│   │   ├── h2-server-integration.md
│   │   └── length-codec-guide.md
│   ├── 03-reference/                   # 配置/协议参考
│   │   ├── http-conf-reference.md
│   │   ├── socket-conf-reference.md
│   │   ├── HTTP2_PROTOCOL.md
│   │   ├── huffman-table-design.md
│   │   ├── websocket-*.md
│   ├── 05-standards/                   # RFC 标准
│   │   └── rfc*.md
│   └── 04-architecture/               # 架构原理
│       └── TCPSERVER_ARCHITECTURE.md
├── SKILL.md                            # 编码规范

---

## 详细文档

| 文档 | 内容 |
|:-----|:-----|
| `docs/02-guide/http-router-guide.md` | 路由匹配、Context Path、反向代理配置 |
| `docs/02-guide/response-api.md` | 响应 API：状态码、Header、Chunked、GZIP、sendFile |
| `docs/02-guide/file-upload-guide.md` | 文件上传：Multipart API、大文件流式处理、配置项 |
| `docs/02-guide/sse-guide.md` | SSE 服务端推送：Emitter API、超时控制 |
| `docs/02-guide/websocket-advanced-api.md` | WebSocket 高级 API：帧编码、Ping-Pong、分片 |
| `docs/02-guide/h2-server-integration.md` | H2/H2C 服务器配置：TLS ALPN、明文升级 |
| `docs/02-guide/length-codec-guide.md` | 长度前缀帧编解码：自定义二进制协议 |
| `docs/03-reference/http-conf-reference.md` | HTTP 配置项参考（含调优建议） |
| `docs/03-reference/socket-conf-reference.md` | Socket 配置项参考 |
| `docs/03-reference/HTTP2_PROTOCOL.md` | H2 连接建立、帧结构、HPACK、流控 |
| `docs/03-reference/websocket-cheatsheet.md` | WebSocket 帧类型速查 |
| `docs/03-reference/websocket-implementation-guide.md` | WebSocket 实现细节 |
| `docs/04-architecture/TCPSERVER_ARCHITECTURE.md` | TCP 服务器架构原理 |
| `SKILL.md` | 编码规范（JDK 8 语法、代码风格） |

---

## 性能优化建议

| 场景 | 推荐 Worker 数 | 模式 | 缓冲区 |
|:-----|:-------------:|:----:|:------:|
| API 服务（小包） | CPU 核心数 | 同步 | 1-4 KB |
| 静态文件（大包） | CPU 核心数 × 2 | 异步 | 16-64 KB |
| 反向代理 | CPU 核心数 × 2 | 同步 | 4-8 KB |
| WebSocket 长连 | CPU 核心数 | 异步 | 2-4 KB |

---

## 与其他框架对比

| 特性 | wastnet | Netty | Vert.x |
|:-----|:--------:|:-----:|:------:|
| 依赖 | 无 | 多个 | 多个 |
| 学习曲线 | 低 | 中 | 中 |
| HTTP 路由 | 内置 | 需编解码器 | 内置 |
| HTTP/2 | 内置 | 需添加 handler | 内置 |
| WebSocket | 内置 | 需添加 handler | 内置 |
| 反向代理 | 内置 | 需自行实现 | 需扩展 |
| 零拷贝 | ✅ | ✅ | ✅ |
| 内存池 | 连接级复用 | 可配置 | 可配置 |

---

## 开发路线

- [x] HTTP/2 完整实现（HPACK + Huffman + 流控 + 多路复用）
- [x] 反向代理（H2→H1 转换、URL 重写、Header 变量）
- [x] 103 Early Hints（RFC 8297）
- [x] TCP 客户端（自动重连、自定义编解码、SSL/TLS）
- [x] SSE（Server-Sent Events）
- [x] 协议编解码（LengthFrameCodec、ObjectCodec）
- [ ] HTTP/3 (QUIC) 支持（预留）
- [ ] 连接池
- [ ] UDP 支持
- [ ] 监控指标（Prometheus 集成）

---

## 许可证

本项目基于 [Apache 2.0](LICENSE) 许可证开源。
