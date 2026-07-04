package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Coverage tests for {@link HttpRoute} abstract base class.
 */
public class HttpRouteTest {

    @Test
    public void testHandleDelegates() throws Throwable {
        HttpRoute route = new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) {
            }
        };
        HttpRequest mockReq = mock(HttpRequest.class);
        HttpResponse mockResp = mock(HttpResponse.class);
        route.handle(mockReq, mockResp);
    }

    @Test
    public void testSelfReturnsThis() {
        HttpRoute route = new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) {}
        };
        Assertions.assertSame(route, route.self());
    }

    @Test
    public void testGetRouteInfoReturnsNull() {
        HttpRoute route = new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) {}
        };
        Assertions.assertNull(route.getRouteInfo());
    }
}
