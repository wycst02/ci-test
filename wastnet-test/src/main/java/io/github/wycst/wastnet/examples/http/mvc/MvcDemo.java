package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wast.json.JSON;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.AnnotationRouterHandler;
import io.github.wycst.wastnet.http.annotation.ConverterConfig;
import io.github.wycst.wastnet.http.annotation.HttpMessageConverter;

/**
 * 注解路由 + 轻量 DI + HttpMessageConverter SPI 综合演示.
 * <p>
 * 启动后访问：
 * <ul>
 *   <li><a href="http://localhost:8080/api/user/list">/api/user/list</a></li>
 *   <li><a href="http://localhost:8080/api/user/get?id=42">/api/user/get?id=42</a></li>
 *   <li><a href="http://localhost:8080/api/user/save?name=foo">/api/user/save?name=foo</a></li>
 *   <li>WebSocket: ws://localhost:8080/ws/chat</li>
 * </ul>
 *
 * @author wangyc
 */
public class MvcDemo {

    public static void main(String[] args) throws Exception {
        // 1. 创建注解路由器，注入 HttpMessageConverter SPI（返回值自动 JSON 序列化 + @RequestBody 反序列化）
        AnnotationRouterHandler annotationRouterHandler = new AnnotationRouterHandler()
                .messageConverter(new HttpMessageConverter() {
                    @Override
                    public void write(Object value, ConverterConfig config, HttpResponse response) throws Exception {
                        response.contentType("application/json;charset=utf-8")
                                .body(config.isPretty() ? JSON.toJsonString(value).getBytes()
                                        : JSON.toJsonBytes(value));
                    }

                    @Override
                    public <T> T read(HttpRequest request, ConverterConfig config, Class<T> type) throws Exception {
                        if (request.isStream()) return null;
                        byte[] data = request.getBodyData();
                        return data != null && data.length > 0 ? JSON.parseObject(data, type) : null;
                    }
                })
                // 2. 配置属性（用于 @Value 注入）
                .property("app.prefix", "Member-")
                // 3. 扫描包
                .scanPackages("io.github.wycst.wastnet.examples.http.mvc");

        // 4. 启动 HTTP 服务器
        HTTPServer.of(8080)
                .requestHandler(annotationRouterHandler)
                .start();

        System.out.println("Server started: http://localhost:8080");
    }
}
