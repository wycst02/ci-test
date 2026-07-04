# HTTP 配置参考手册

`HttpConf` 是 wastnet 的 HTTP 全局配置控制类，所有配置项通过 `wastnet-http.properties` 文件、系统属性或 Docker 环境变量进行设置。

**默认场景下无需任何配置即可运行。** 所有配置项均有缺省值，默认值基于常见场景的最佳实践设置（如 512KB 内存阈值、8KB 单头限制等）。除非有特定的生产环境安全加固或性能调优需求，通常不需要修改默认值。

---

## 配置文件加载

### 配置文件位置

`wastnet-http.properties` 的加载按以下优先级从低到高搜索（高优先级覆盖低优先级）：

| 优先级 | 位置 | 说明 |
|--------|------|------|
| 1 (最低) | `{classpath}/wastnet-http.properties` | 源代码根目录 |
| 2 | `{classpath}/config/wastnet-http.properties` | 源代码根目录 config 目录 |
| 3 | `{JAR 同级}/wastnet-http.properties` | JAR 文件同目录 |
| 4 | `{JAR 同级}/config/wastnet-http.properties` | JAR 文件同目录 config 子目录 |
| 5 (最高) | `{JAR 父级}/config/wastnet-http.properties` | JAR 文件所在目录的父级下的 config 目录 |

### 属性值优先级

配置项最终值的获取顺序：

```
系统属性 (System.getProperty)  > 环境变量 > 配置文件 (wastnet-http.properties)
```

---

## 配置项完整列表

### 头部安全与大小限制

| 配置键 | 说明 | 默认值 | 最小值 |
|-------|------|--------|-------|
| `wastnet.http.max-single-header-size` | 单个请求头的总大小限制（键 + 值，字节） | `8192` (8KB) | `1` |
| `wastnet.http.max-http-header-size` | 所有 HTTP 头部的总大小限制（字节） | `16384` (16KB) | `1` |
| `wastnet.http.max-uri-length` | 请求 URI (request-target) 的最大长度 | `16384` | `1` |

### Body 大小与内存控制

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.body-memory-threshold` | 响应 Body 内存阈值（字节）。缓冲区大小超过此值时立即刷新到 channel 以防止 OOM | `524288` (512KB) |
| `wastnet.http.max-body-in-memory` | 请求 Body 在内存中保留的最大大小（字节）。超过此值转为流式处理 | `2097152` (2MB) |
| `wastnet.http.body-max-size` | Body 允许的最大总大小（字节）。≤ 0 表示无限制。正值时最小为 `body-memory-threshold` | `-1` (无限制) |
| `wastnet.http.enable-temp-file` | Body 超过内存阈值时是否生成临时文件。关闭时将忽略原需写入临时文件的上传域 | `true` |
| `wastnet.http.temp-file-dir` | 临时文件存储目录 | `{java.io.tmpdir}/wast-http` |
| `wastnet.http.temp-file-prefix` | 临时文件前缀 | `wast_http_` |

### 字符集与编码

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.default-charset` | 默认字符集 | `UTF-8` |

### GZIP 压缩

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.gzip` | 是否启用 GZIP 压缩 | `false` |
| `wastnet.http.gzip-min-size` | GZIP 压缩最小阈值（字节）。小于该值不压缩，`0` 表示无下限 | `2048` (2KB) |

### 响应头控制

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.header.default.enabled` | 是否自动写入默认响应头（Date、Server、Connection） | `true` |
| `wastnet.http.server-header.expose` | 是否在 HTTP 响应中暴露 Server 头（安全最佳实践建议隐藏） | `false` |
| `wastnet.http.header.order.preserve` | 是否保留 HTTP 头部插入顺序。`false` 使用 HashMap (性能优先)，`true` 使用 LinkedHashMap | `false` |

### 连接与请求控制

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.pipeline.enabled` | 是否启用 HTTP/1.1 Pipeline。启用后单个 TCP 包中的多个 HTTP 请求会被处理，禁用后多余的请求被丢弃 | `false` |
| `wastnet.http.request-timeout` | 从第一个字节到达到请求完整接收的最大允许时间（毫秒）。≤ 0 视为禁用。每次 NIO 数据到达时非阻塞检查，超时返回 408 Request Timeout | `Long.MAX_VALUE` (禁用) |
| `wastnet.http.implemented-methods` | 服务器实际实现的 HTTP 方法白名单（逗号分隔，如 `GET,POST,PUT,DELETE`）。未配置时所有已知方法均可使用；配置后将只识别列表中的方法，其他返回 501 Not Implemented | 全部放行 |

### WebSocket

| 配置键 | 说明 | 默认值 | 最小值 |
|--------|------|--------|--------|
| `wastnet.http.max-ws-frame-size` | WebSocket 单个帧的最大有效载荷大小（字节） | `536870912` (512MB payload) | `1` |

---

## 配置文件示例

```properties
# ==================== 头部安全 ====================
wastnet.http.max-single-header-size=8192
wastnet.http.max-http-header-size=16384
wastnet.http.max-uri-length=16384

# ==================== Body 控制 ====================
wastnet.http.body-memory-threshold=524288
wastnet.http.max-body-in-memory=2097152
wastnet.http.body-max-size=-1
wastnet.http.enable-temp-file=true
wastnet.http.temp-file-dir=${java.io.tmpdir}/wast-http
wastnet.http.temp-file-prefix=wast_http_

# ==================== 字符集 ====================
wastnet.http.default-charset=UTF-8

# ==================== GZIP 压缩 ====================
wastnet.http.gzip=false
wastnet.http.gzip-min-size=2048

# ==================== 响应头 ====================
wastnet.http.header.default.enabled=true
wastnet.http.server-header.expose=false
wastnet.http.header.order.preserve=false

# ==================== 请求控制 ====================
wastnet.http.pipeline.enabled=false
# wastnet.http.request-timeout=30000

# ==================== 方法实现白名单 ====================
# wastnet.http.implemented-methods=GET,POST,PUT,DELETE
```

---

## HTTP/2 配置

HTTP/2 相关配置不在 `wastnet-http.properties` 中，而是通过 JVM 系统属性直接设置：

| 配置键 | 默认值 | 说明 |
|-------|--------|------|
| `wastnet.http2.initial.send-window-size` | `65535` | 初始发送/接收窗口大小（字节）。增大可提升吞吐量，但会占用更多内存 |
| `wastnet.http2.max-concurrent-streams` | `Integer.MAX_VALUE` (无限制) | 服务端最大并发流数量。**生产环境建议设置**（如 1024），防止资源耗尽（Rapid Reset 攻击） |
| `wastnet.http2.debug` | `false` | 调试开关，开启后打印 H2 帧的详细信息 |

### 通过系统属性设置

```bash
java -Dwastnet.http2.max-concurrent-streams=1024 \
     -Dwastnet.http2.initial.send-window-size=1048576 \
     -jar your-app.jar
```

### 生产环境建议

```properties
# 限制并发流，防止资源耗尽
-Dwastnet.http2.max-concurrent-streams=1024
# 增大窗口以提升吞吐（1MB）
-Dwastnet.http2.initial.send-window-size=1048576
```

> 详细协议实现说明参见 [HTTP/2 协议详解](HTTP2_PROTOCOL.md)，服务端集成示例参见 [H2 服务端集成指南](../02-guide/h2-server-integration.md)。

---

## 运行时 API

`HttpConf` 提供了两个运行时方法，可在应用代码中调用以查看当前生效的配置：

### dumpAsJson()

以 JSON 格式输出所有 HTTP 配置：

```java
String json = HttpConf.dumpAsJson();
System.out.println(json);
// 输出：
// {
//   "MAX_SINGLE_HEADER_SIZE": 8192,
//   "MAX_HTTP_HEADER_SIZE": 16384,
//   ...
// }
```

### dumpAsProperties()

以 Properties 格式输出所有 HTTP 配置：

```java
String props = HttpConf.dumpAsProperties();
System.out.println(props);
// 输出：
// # HTTP Configuration
// wastnet.http.max-single-header-size=8192
// wastnet.http.max-http-header-size=16384
// ...
```

### getProperty(String key)

动态获取配置属性值（优先系统属性 > 环境变量 > 配置文件）：

```java
String value = HttpConf.getProperty("wastnet.http.gzip");
```

---

## 性能调优建议

### 大文件上传场景

```properties
wastnet.http.max-body-in-memory=1048576      # 1MB 内存阈值，超限转为流式
wastnet.http.body-max-size=1073741824        # 限制最大 Body 为 1GB
wastnet.http.enable-temp-file=true           # 启用临时文件（默认）
```

### 高并发 API 场景

```properties
wastnet.http.body-memory-threshold=131072    # 小 Body 内存阈值，快速刷出
wastnet.http.max-uri-length=2048             # 缩短 URI 限制，防止滥用
wastnet.http.header.default.enabled=false    # 关闭默认响应头，节省带宽
```

### 安全加固场景

```properties
wastnet.http.max-single-header-size=4096     # 收紧单头限制
wastnet.http.max-http-header-size=8192       # 收紧总头限制
wastnet.http.server-header.expose=false      # 隐藏 Server 信息（默认）
wastnet.http.request-timeout=10000           # 请求超时 10 秒
```
