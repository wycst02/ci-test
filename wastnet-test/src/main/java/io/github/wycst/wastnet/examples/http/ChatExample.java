package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpResourceHandler;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketFrame;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 综合演示：WebSocket 聊天室 + SSE 推送 + 静态页面
 * <p>
 * 访问 {@code http://localhost:8080/websocket-chat.html} 进入 WebSocket 聊天室
 * 访问 {@code http://localhost:8080/sse-demo.html} 进入 SSE 演示页
 */
public class ChatExample {

    private static final Log log = LogFactory.getLog(ChatExample.class);

    public static void main(String[] args) throws Exception {
        HttpRouterHandler router = new HttpRouterHandler();

        // 静态页面（pages 目录）
        String docBase = ChatExample.class.getResource("/pages").getPath();
        router.resource(new HttpResourceHandler("/", docBase));

        // WebSocket 聊天（通过 URL 参数 ?name=xxx 传递用户名）
        final AtomicInteger online = new AtomicInteger();
        router.ws("/ws/chat", new WebSocketResource(300) {

            @Override
            public boolean beforeHandshake(HttpRequest request, HttpResponse response) {
                return request.getParameter("name") != null;
            }

            @Override
            public void onOpen(WebSocketConnection conn) {
                String name = conn.request().getParameter("name");
                conn.setAccount(name);
                broadcastMessage(WebSocketFrame.textOf("system:" + name + " 加入了群聊（" + online.incrementAndGet() + "人）"));
                log.info("{} connected", name);
            }

            @Override
            public void onMessage(WebSocketConnection conn, String message) throws IOException {
                broadcastMessage(WebSocketFrame.textOf(conn.getAccount() + ":" + message));
            }

            @Override
            public void onClose(WebSocketConnection conn, int code, String reason) {
                broadcastMessage(WebSocketFrame.textOf("system:" + conn.getAccount() + " 离开了群聊（" + online.decrementAndGet() + "人）"));
                log.info("{} disconnected", conn.getAccount());
            }
        });

        // SSE 推送（loop 模式）
        router.get("/sse/clock", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request,
                               HttpResponse response) throws Throwable {
                for (int i = 0; i < 10; i++) {
                    response.sse("{\"tick\":" + i + ",\"time\":" + System.currentTimeMillis() + "}");
                    Thread.sleep(1000);
                }
            }
        });

        // SSE 推送（emitter 模式）
        router.sse("/sse/news", 60000L, emitter -> {
            for (int i = 1; i <= 10; i++) {
                String id = "news-" + i;
                emitter.emit("news", "{\"id\":" + id + ",\"title\":\"Breaking News " + i + "\"}", id, 3000);
                Thread.sleep(1000);
            }
            emitter.close();
        });

        // 启动服务器
        HTTPServer.of(8080)
                .requestHandler(router)
                .start();

        log.info("ChatExample server started on http://localhost:8080");
        log.info("  WebSocket Chat: http://localhost:8080/websocket-chat.html");
        log.info("  SSE Demo:       http://localhost:8080/sse-demo.html");
    }
}
