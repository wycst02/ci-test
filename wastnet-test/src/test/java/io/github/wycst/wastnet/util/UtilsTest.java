package io.github.wycst.wastnet.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Utils#encodeUriPath(String)}.
 */
public class UtilsTest {

    @Test
    public void testEncodeUriPathSafeAscii() {
        // Pure ASCII safe characters — returns original string (zero allocation)
        Assertions.assertSame("/hello/world", Utils.encodeUriPath("/hello/world"));
        Assertions.assertSame("index.html", Utils.encodeUriPath("index.html"));
        Assertions.assertSame("/api/users?id=1&name=test", Utils.encodeUriPath("/api/users?id=1&name=test"));
        Assertions.assertSame("-_.~", Utils.encodeUriPath("-_.~"));
        Assertions.assertSame("", Utils.encodeUriPath(""));
    }

    @Test
    public void testEncodeUriPathSpace() {
        Assertions.assertEquals("/hello%20world", Utils.encodeUriPath("/hello world"));
        Assertions.assertEquals("%20", Utils.encodeUriPath(" "));
    }

    @Test
    public void testEncodeUriPathSpecialChars() {
        // < > " should be percent-encoded
        Assertions.assertEquals("hello%3Cworld", Utils.encodeUriPath("hello<world"));
        Assertions.assertEquals("hello%3Eworld", Utils.encodeUriPath("hello>world"));
        Assertions.assertEquals("a%22b", Utils.encodeUriPath("a\"b"));
        // Percent sign itself
        Assertions.assertEquals("a%25b", Utils.encodeUriPath("a%b"));
    }

    @Test
    public void testEncodeUriPathNonAscii() {
        // Chinese characters, UTF-8 encoded: 你 = %E4%BD%A0, 好 = %E5%A5%BD
        Assertions.assertEquals("/%E4%BD%A0%E5%A5%BD", Utils.encodeUriPath("/你好"));
        Assertions.assertEquals("test/%E4%B8%AD%E6%96%87", Utils.encodeUriPath("test/中文"));
    }

    @Test
    public void testEncodeUriPathSurrogatePair() {
        // Emoji 😀 (U+1F600) — UTF-8: F0 9F 98 80
        Assertions.assertEquals("%F0%9F%98%80", Utils.encodeUriPath("\uD83D\uDE00"));
        Assertions.assertEquals("a%F0%9F%98%80b", Utils.encodeUriPath("a\uD83D\uDE00b"));
    }

    @Test
    public void testEncodeUriPathMixed() {
        // Safe prefix is preserved, then unsafe chars encoded
        Assertions.assertEquals("/safe/path/%E4%BD%A0%E5%A5%BD", Utils.encodeUriPath("/safe/path/你好"));
        Assertions.assertEquals("hello%20world%21", Utils.encodeUriPath("hello world!"));
    }

    @Test
    public void testEncodeUriPathPreservesStructuralChars() {
        // / ? = & # are safe — should not be encoded
        Assertions.assertEquals("/a/b/c", Utils.encodeUriPath("/a/b/c"));
        Assertions.assertEquals("?a=1&b=2#frag", Utils.encodeUriPath("?a=1&b=2#frag"));
    }

    @Test
    public void testEncodeUriPathAllUnsafe() {
        // Characters not in the safe set should be percent-encoded
        Assertions.assertEquals("%21%40%24%25%5E", Utils.encodeUriPath("!@$%^"));
    }
}
