package io.github.wycst.wastnet.socket.tcp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.channels.SocketChannel;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ConnectionFilter} and {@link SSLContextFactory} interfaces.
 */
public class SocketInterfacesTest {

    @Test
    public void testConnectionFilterAccepts() throws Exception {
        ConnectionFilter filter = new ConnectionFilter() {
            public boolean onAccept(SocketChannel channel) throws Exception {
                return true;
            }
        };
        Assertions.assertTrue(filter.onAccept(mock(SocketChannel.class)));
    }

    @Test
    public void testConnectionFilterRejects() throws Exception {
        ConnectionFilter filter = new ConnectionFilter() {
            public boolean onAccept(SocketChannel channel) throws Exception {
                return false;
            }
        };
        Assertions.assertFalse(filter.onAccept(mock(SocketChannel.class)));
    }

    @Test
    public void testConnectionFilterThrowsException() {
        ConnectionFilter filter = new ConnectionFilter() {
            public boolean onAccept(SocketChannel channel) throws Exception {
                throw new RuntimeException("rejected");
            }
        };
        Assertions.assertThrows(RuntimeException.class,
                () -> filter.onAccept(mock(SocketChannel.class)));
    }

    @Test
    public void testSSLContextFactoryCreatesContext() throws Exception {
        SSLContextFactory factory = new SSLContextFactory() {
            public javax.net.ssl.SSLContext create() {
                try {
                    return javax.net.ssl.SSLContext.getInstance("TLS");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        javax.net.ssl.SSLContext ctx = factory.create();
        Assertions.assertNotNull(ctx);
        Assertions.assertEquals("TLS", ctx.getProtocol());
    }
}
