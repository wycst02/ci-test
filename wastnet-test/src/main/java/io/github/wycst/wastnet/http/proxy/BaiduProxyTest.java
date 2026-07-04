package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

/**
 * Test for HTTPS proxy to Baidu
 */
public class BaiduProxyTest {

    public static void main(String[] args) {
        int port = Integer.getInteger("port", 8000);

        HttpProxyConfig proxyConfig =
                HttpProxyConfig.target("https://www.baidu.com");

        HttpRouterHandler router = new HttpRouterHandler("/");
        router.route("/", new HttpProxyRoute(proxyConfig));

        HTTPServer.of(port).requestHandler(router).start();

        System.out.println("Baidu proxy server started on http://localhost:" + port + "/");
        System.out.println("  Proxy target: https://www.baidu.com");
        System.out.println("  Test: curl http://localhost:" + port + "/s?wd=hello");
    }
}
