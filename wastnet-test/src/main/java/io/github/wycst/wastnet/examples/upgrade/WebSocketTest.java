package io.github.wycst.wastnet.examples.upgrade;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.http.upgrade.UpgradeHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

/**
 * @Date 2024/1/19 17:51
 * @Created by wangyc
 */
public class WebSocketTest {

    private static Log log = LogFactory.getLog(WebSocketTest.class);

    public static void main(String[] args) throws Exception {
        int workNum = 8;
        try {
            workNum = Integer.parseInt(System.getProperty("wastnet.workNum"));
        } catch (Throwable throwable) {
        }

        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        nioConfig.setWorkerNum(workNum);

        HTTPServer httpServer =
                HTTPServer.of(8080, nioConfig)
//                        .sslContext(createSslContext())
//                        // .sslCipherSuites(new String[] {"TLS_RSA_WITH_AES_128_CBC_SHA"})
//                        .applicationProtocols(new String[]{"h2", "http/1.1"})
//                        .printSSLErrorLog(true)
//                        .printReadErrorLog(true)
//                        .printApplicationMessage(true)
                        .bufferSize(1024)
                        .requestHandler(new HttpRequestHandler() {
                            @Override
                            public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                                response.header("Connection", "keep-alive")
                                        .contentType("text/plain;charset=utf-8")
                                        .body("ok".getBytes());
                            }
                        });

        // 支持websocket
        UpgradeHandler upgradeHandler = httpServer.upgradeHandler();

        // 添加一个ws资源,返回一个websocket资源句柄
        upgradeHandler.ws("/ws", new WebSocketResource(300) {
            public void onOpen(WebSocketConnection connection) {
                System.out.println("on open");
            }

            public void onMessage(WebSocketConnection connection, String message) throws IOException {
                System.out.println("onMessage: " + message);
                connection.sendText("wwwaaaaaaaaaaaaa");
                // connection.ping();
            }

            @Override
            public void onBinary(WebSocketConnection connection, byte[] data) throws IOException {
                System.out.println("onBinary: " + data.length);
                System.out.println("onBinary: " + new String(data, StandardCharsets.UTF_8));
            }

            public void onClose(WebSocketConnection connection, int code, String reason) {
                System.out.println("onClose: " + code + " " + reason);
            }

            @Override
            public void onErrorClose(WebSocketConnection connection) {
                System.out.println("onErrorClose: " + connection.id());
            }
        });

        // 添加一个h2c资源
        upgradeHandler.h2c("/h2c");

        httpServer.start();
        log.info("server http://localhost:{}", httpServer.getPort());
    }

    static byte[] buildBytes(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'a');
        return bytes;
    }


    private static SSLContext createSslContext() throws Exception {
        // SSL
        char[] password = "123456".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");

        // keytool -genkey -alias aliastest -keyalg RSA -keysize 1024 -keypass 123456 -validity 365 -keystore server.keystore -storepass 123456
        InputStream in = WebSocketTest.class.getResourceAsStream("/server.keystore");
        keyStore.load(in, password);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, password);
//        sslContext = SSLContext.getInstance("SSL");
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }

}
