# TCPServer 架构原理

## 一、整体架构

TCPServer 是基于 Java NIO Selector 实现的高性能 TCP 服务器，采用 **Reactor 模式** 设计。

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              TCPServer                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────────────┐                                                │
│  │ ChannelAcceptDispatcher │  ← 主线程，负责 Accept 事件                  │
│  └──────────┬──────────┘                                                │
│             │                                                            │
│             │ 分发连接到 Worker                                           │
│             ↓                                                            │
│  ┌────────────────────────────────────────────────────────────┐         │
│  │                  ChannelReaderWorker[]                      │         │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐       ┌─────────┐  │         │
│  │  │ Worker0 │  │ Worker1 │  │ Worker2 │  ...  │ WorkerN │  │         │
│  │  │Selector │  │Selector │  │Selector │       │Selector │  │         │
│  │  │ 连接池  │  │ 连接池  │  │ 连接池  │       │ 连接池  │  │         │
│  │  └─────────┘  └─────────┘  └─────────┘       └─────────┘  │         │
│  └────────────────────────────────────────────────────────────┘         │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

## 二、核心组件

### 2.1 TCPServer

主服务器类，负责：
- 初始化配置（端口、SSL、缓冲区大小等）
- 创建和启动线程
- 生命周期管理（start/stop/shutdown）

### 2.2 ChannelAcceptDispatcher

**连接接收分发器**，运行在独立线程中：

```java
class ChannelAcceptDispatcher extends Thread {
    void handleAccept() {
        while (serverRunFlag) {
            selector.select();              // 等待 Accept 事件
            SocketChannel client = serverChannel.accept();
            
            // 连接过滤（可选）
            ConnectionFilter filter = server.serverConfig.getConnectionFilter();
            if (filter != null && (!filter.onAccept(client) || !client.isOpen())) {
                client.close();             // 拒绝连接，零资源浪费
                continue;
            }
            
            client.configureBlocking(false);
            
            // 轮询分配到 Worker
            ChannelReaderWorker worker = workers[++clientCnt & workerMask];
            worker.register(client, runner);
        }
    }
}
```

**职责**：
- 监听服务端 Socket 的 Accept 事件
- 连接过滤（IP 黑/白名单、限流）
- 为新连接分配 Worker（轮询负载均衡）
- 设置连接参数（TCP_NODELAY 等）

### 2.3 ChannelReaderWorker

**IO 事件处理线程**，每个 Worker 维护独立的 Selector：

```java
class ChannelReaderWorker extends Thread {
    final Selector selector;    // 独立的 Selector
    
    void handleRead() {
        while (serverRunFlag) {
            selector.select(timeout);    // 等待 IO 事件
            handleSelectedKeys();        // 处理就绪的连接
        }
    }
    
    void handleSelectedKeys() {
        for (SelectionKey key : selectedKeys) {
            if (key.isReadable()) {
                runnerService.execute(runner);  // 执行读任务
            }
        }
    }
}
```

**职责**：
- 管理一批连接的 IO 事件
- 将读事件交给 RunnerService 处理
- 支持连接注册/注销

### 2.4 SocketChannelRunner

**连接运行器**，每个连接对应一个 Runner：

```java
class SocketChannelRunner {
    final ChannelContext ctx;          // 连接上下文
    final ChannelReader channelReader; // 数据读取器
    final ChannelHandler channelHandler; // 业务处理器
    
    public void run() {
        before();                      // 前置处理（SSL 握手等）
        handleChannelRead();           // 读取并处理数据
    }
    
    protected int handleChannelRead() {
        do {
            buf.flip();
            read(buf);                 // 调用 ChannelReader 解析
            buf.clear();
        } while (ctx.channelRead(buf) > 0);
    }
}
```

**职责**：
- 读取 Socket 数据
- 调用 ChannelReader 解析协议
- 调用 ChannelHandler 处理业务

### 2.5 RunnerService（执行模式）

支持两种执行模式：

```java
// 同步模式：在 Selector 线程直接执行
class SyncImpl extends RunnerService {
    void doExecute(Runnable command) {
        command.run();  // 直接调用
    }
}

// 异步模式：提交到线程池执行
class AsyncImpl extends RunnerService {
    void doExecute(Runnable command) {
        runnerExecutor.execute(command);  // 线程池
    }
}
```

## 三、数据流

### 3.1 连接建立流程

```
Client Connect
      │
      ↓
ChannelAcceptDispatcher.select() 返回
      │
      ↓
serverChannel.accept() 获取 SocketChannel
      │
      ↓
[可选] ConnectionFilter.onAccept() → false 则 close() 并跳过
      │
      ↓
client.configureBlocking(false)
client.socket().setTcpNoDelay(true)
      │
      ↓
轮询选择 Worker: workers[clientCnt++ & mask]
      │
      ↓
worker.register(channel, runner)
      │
      ↓
channel.register(worker.selector, OP_READ, runner)
```

### 3.2 数据读取流程

```
ChannelReaderWorker.select() 返回
      │
      ↓
遍历 selectedKeys
      │
      ↓
key.isReadable() → runnerService.execute(runner)
      │
      ├─────────────────────────────────────┐
      │                                     │
      ↓ (同步模式)                           ↓ (异步模式)
runner.run()                          runnerExecutor.execute(runner)
      │                                     │
      ↓                                     ↓
handleChannelRead()                   线程池调度
      │                                     │
      ↓                                     ↓
ctx.channelRead(buf)                  runner.run()
      │                                     │
      ↓                                     ↓
channelReader.read()                  handleChannelRead()
      │
      ↓
channelHandler.onHandle()
```

## 四、线程模型

### 4.1 线程组成

| 线程类型 | 数量 | 职责 |
|---------|------|------|
| ChannelAcceptDispatcher | 1 | 接收新连接 |
| ChannelReaderWorker | workerNum (默认8) | 处理 IO 事件 |
| runnerExecutor 线程池 | 动态 | 异步执行业务（仅异步模式） |

### 4.2 连接-线程关系

```
Worker 数量 = 8
连接数量 = 10000

每个 Worker 管理约 1250 个连接（负载均衡）

┌──────────────┐
│   Worker0    │ ← 连接 0, 8, 16, 24, ...
│  Selector    │
│  连接 1250个  │
└──────────────┘

┌──────────────┐
│   Worker1    │ ← 连接 1, 9, 17, 25, ...
│  Selector    │
│  连接 1250个  │
└──────────────┘
```

### 4.3 同步 vs 异步

| 特性 | 同步模式 | 异步模式 |
|-----|---------|---------|
| 执行线程 | Selector 线程 | 线程池线程 |
| 线程切换 | 无 | 有 |
| 吞吐量（无阻塞） | 高 | 较低 |
| 阻塞影响 | 影响同 Worker 所有连接 | 影响较小 |
| 适用场景 | 纯 IO 密集 | 业务有阻塞操作 |

## 五、SSL/TLS 支持

### 5.1 架构

```
普通连接：SocketChannelRunner
                ↓
SSL 连接：SocketChannelSSLRunner
                │
                ├── SSLEngineContext（SSL 引擎）
                │       ├── sslEngine
                │       ├── packetInBuf（加密数据缓冲）
                │       ├── applicationInBuf（明文数据缓冲）
                │       └── ...
                │
                └── SSLChannelContext
```

### 5.2 SSL 数据流

```
读取：
Socket → packetInBuf（加密数据）
              ↓
         sslEngine.unwrap()
              ↓
     applicationInBuf（明文数据）
              ↓
         channelReader.read()

写入：
applicationOutBuf（明文数据）
              ↓
         sslEngine.wrap()
              ↓
     packetOutBuf（加密数据）
              ↓
         Socket 写入
```

### 5.3 握手流程

```
客户端连接 → sslHandShake()
                │
                ↓
        读取首个字节判断：
                │
        ┌───────┴───────┐
        ↓               ↓
   首字节=22        首字节≠22
   (SSL握手)        (明文协议)
        │               │
        ↓               ↓
  sslEngine.beginHandshake()  → 禁用 SSL
        │
        ↓
  NEED_UNWRAP → 读取数据 → unwrap()
        │
        ↓
  NEED_WRAP → wrap() → 发送数据
        │
        ↓
  NEED_TASK → 执行委托任务
        │
        ↓
  FINISHED → 握手完成
```

## 六、关键设计

### 6.1 无锁设计

- 每个 Worker 有独立的 Selector
- 连接只属于一个 Worker，无跨线程访问
- ChannelContext 绑定到连接，无共享状态

### 6.2 零拷贝优化

```java
// 写入时直接操作 ByteBuffer，无额外拷贝
public int write(ByteBuffer buf) {
    channelWrite(buf);  // 直接写入
}

// 批量写入时使用缓冲区
public void flush() {
    writeBuf.flip();
    channelWrite(writeBuf);
    writeBuf.clear();
}
```

### 6.3 TCP 参数优化

```java
// 连接建立时
client.socket().setTcpNoDelay(true);     // 禁用 Nagle 算法

// 服务端 Socket
serverSocket.setReuseAddress(true);       // 端口重用
serverSocket.setReceiveBufferSize(65536); // 接收缓冲区
```

### 6.4 Windows 平台兼容

```java
// Windows 平台首次注册时需要同步等待
if (registering) {
    synchronized (this) {
        wait();  // 等待注册完成
    }
}
```

## 七、扩展点

### 7.1 ChannelReader

协议解析扩展：

```java
public interface ChannelReader {
    void init(ChannelContext ctx);
    void read(ChannelContext ctx, ByteBuffer buf, ChannelHandlerDelegation delegation);
    void wakeup();
}
```

### 7.2 ChannelHandler

业务处理扩展：

```java
public interface ChannelHandler<T> {
    void onConnected(ChannelContext ctx);
    void onHandle(ChannelContext ctx, T message);
    void onException(ChannelContext ctx, Throwable throwable);
}
```

### 7.3 ConnectionFilter

连接过滤扩展（accept 级别），基于 `SocketChannel.getRemoteAddress()` 获取客户端 IP，可用于实现 **IP 黑白名单、连接数限流** 等安全控制：

```java
public interface ConnectionFilter {
    boolean onAccept(SocketChannel channel) throws Exception;
}
```

在 `AcceptDispatcher` 中、`configureBlocking` 之前执行，被拒绝的连接**不消耗任何连接级资源**（ChannelContext、ByteBuffer）。

### 7.4 IdleStateHandler

空闲检测扩展：

```java
public abstract class IdleStateHandler {
    public abstract void onIdle(ChannelContext ctx, long idleTime);
}
```

## 八、使用示例

```java
// 创建服务器
TCPServer server = new TCPServer(8080)
    .workerNum(8)                    // Worker 线程数
    .bufferSize(8192)                // 缓冲区大小
    .channelHandler(new MyHandler()) // 设置处理器
    .idleStateHandler(new MyIdleHandler()) // 空闲检测
    .start();                        // 启动

// 配置异步模式
ServerConfig config = new ServerConfig();
config.setSyncRunner(false);  // false = 异步
new TCPServer(8080, config).start();

// 启用 SSL
server.sslContext(sslContext)
       .ssl(true)
       .h2();
```

## 九、性能优化建议

1. **选择合适的执行模式**：
   - 纯 IO 密集：同步模式
   - 业务有阻塞：异步模式

2. **调整 Worker 数量**：
   - CPU 密集型：Worker 数 = CPU 核心数
   - IO 密集型：Worker 数 = CPU 核心数 × 2

3. **调整缓冲区大小**：
   - 小包场景：小缓冲区（1KB-4KB）
   - 大包场景：大缓冲区（16KB-64KB）

4. **启用 TCP 优化**：
   ```java
   // 在 accept 后添加
   client.socket().setReceiveBufferSize(1024 * 1024);
   client.socket().setSendBufferSize(1024 * 1024);
   ```
