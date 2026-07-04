package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.h2.Http2Request;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Internal abstract base class for HTTP request implementations.
 * <p>
 * Provides common functionality shared between HTTP/1.x ({@link HttpDecodedRequest})
 * and HTTP/2 ({@link Http2Request}) request implementations,
 * including ChannelContext delegation, HttpBodyDecoder lazy loading, parameter merging,
 * identity methods, and framework-internal error handling.
 *
 * @author wangyc
 */
public abstract class HttpInternalRequest implements HttpRequest {

    private final long id;
    protected final ChannelContext ctx;
    protected HttpResponse response;
    private HttpBodyDecoder bodyDecoder;

    protected HttpInternalRequest(ChannelContext ctx) {
        this.id = Utils.id();
        this.ctx = ctx;
    }

    /**
     * Get request ID
     *
     * @return Request ID
     */
    public final long getRequestId() {
        return id;
    }

    // ==================== ChannelContext delegates ====================
    /**
     * Get the underlying channel context.
     *
     * @return the channel context
     */
    public final ChannelContext ctx() {
        return ctx;
    }

    @Override
    public final boolean isSSL() {
        return ctx.isSSL();
    }

    @Override
    public final long getConnectionId() {
        return ctx.getId();
    }

    @Override
    public void setAttribute(String key, Object value) {
        ctx.setAttribute(key, value);
    }

    @Override
    public Object getAttribute(String key) {
        return ctx.getAttribute(key);
    }

    @Override
    public final InetSocketAddress getRemoteAddress() {
        return ctx.getRemoteAddress();
    }

    @Override
    public final String getRemoteHost() {
        InetSocketAddress remoteAddress = getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getHostString() : null;
    }

    @Override
    public final int getRemotePort() {
        InetSocketAddress remoteAddress = getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getPort() : -1;
    }

    @Override
    public final InetSocketAddress getServerAddress() {
        return ctx.getLocalAddress();
    }

    @Override
    public final String getServerHost() {
        InetSocketAddress serverAddress = getServerAddress();
        return serverAddress != null ? serverAddress.getHostString() : null;
    }

    @Override
    public final int getServerPort() {
        InetSocketAddress serverAddress = getServerAddress();
        return serverAddress != null ? serverAddress.getPort() : -1;
    }

    // ==================== Identity methods ====================

    @Override
    public final boolean isHttpRequest() {
        return true;
    }

    @Override
    public final boolean isUpgrade() {
        return false;
    }

    @Override
    public boolean isBad() {
        return false;
    }

    public boolean isProtocolError() {
        return false;
    }

    public HttpStatus getHttpStatus() {
        return null;
    }

    public String getErrorMessage() {
        return null;
    }

    // ==================== Response ====================

    @Override
    public final HttpResponse getResponse() {
        return response;
    }

    // ==================== HttpBodyDecoder lazy loading ====================

    private HttpBodyDecoder getBodyDecoder() {
        if (bodyDecoder == null) {
            bodyDecoder = HttpBodyDecoder.of(this);
        }
        return bodyDecoder;
    }

    /**
     * Release body decoder resources.
     * Subclasses that override {@link #complete()} should call this method.
     */
    protected void releaseBodyDecoder() {
        if (bodyDecoder != null) {
            bodyDecoder.release();
            bodyDecoder = null;
        }
    }

    @Override
    public final boolean isMultipart() {
        return getBodyDecoder().isMultipart();
    }

    @Override
    public final boolean isFormUrlencoded() {
        return getBodyDecoder().isFormUrlencoded();
    }

    @Override
    public final boolean isJson() {
        return getBodyDecoder().isJson();
    }

    @Override
    public final Charset getCharset() {
        return getBodyDecoder().getCharset();
    }

    @Override
    public final MultipartField getMultipartField(String name) {
        return getBodyDecoder().getMultipartField(name);
    }

    @Override
    public final List<MultipartField> getMultipartFields(String name) {
        return getBodyDecoder().getMultipartFields(name);
    }

    @Override
    public final String getMultipartFieldValue(String name) {
        return getBodyDecoder().getMultipartFieldValue(name);
    }

    @Override
    public final List<String> getMultipartFieldValues(String name) {
        return getBodyDecoder().getMultipartFieldValues(name);
    }

    @Override
    public final Set<String> getMultipartFieldNames() {
        return getBodyDecoder().getMultipartFieldNames();
    }

    // ==================== Parameter methods ====================

    @Override
    public final String getParameter(String name) {
        String value = getUriParameter(name);
        if (value != null) {
            return value;
        }
        return getBodyDecoder().getParameter(name);
    }

    @Override
    public final String getBodyParameter(String name) {
        return getBodyDecoder().getParameter(name);
    }

    @Override
    public final List<String> getParameterValues(String name) {
        List<String> uriValues = getUriParameterValues(name);
        List<String> bodyValues = getBodyDecoder().getParameterValues(name);
        if (uriValues == null) {
            return bodyValues;
        }
        if (bodyValues == null) {
            return uriValues;
        }
        List<String> result = new ArrayList<String>(uriValues.size() + bodyValues.size());
        result.addAll(uriValues);
        result.addAll(bodyValues);
        return result;
    }

    @Override
    public final Set<String> getParameterNames() {
        Set<String> names = new LinkedHashSet<String>();
        Set<String> uriNames = getUriParameterNames();
        if (uriNames != null) {
            names.addAll(uriNames);
        }
        HttpBodyDecoder decoder = getBodyDecoder();
        if (decoder.isMultipart()) {
            names.addAll(decoder.getMultipartFieldNames());
        } else if (decoder.isFormUrlencoded()) {
            names.addAll(decoder.getUrlencodedParameterNames());
        }
        return names;
    }

    // ==================== URL methods ====================

    @Override
    public final String getDecodedRequestURL() {
        StringBuffer url = getRequestURL();
        if (url == null) return null;
        try {
            return java.net.URLDecoder.decode(url.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return url.toString();
        }
    }

    // ==================== Default implementations (subclass-specific ====================

    @Override
    public boolean isStream() {
        return false;
    }

    @Override
    public boolean completed() {
        return false;
    }

    @Override
    public String getHeader(String name, boolean caseSensitive) {
        return getHeader(name);
    }

    // ==================== Lifecycle ====================

    @Override
    public void complete() {
        releaseBodyDecoder();
    }

    // ==================== URI parameter helpers (for subclass implementations) ====================

    /**
     * Get all URI parameter values by name.
     * Used by parent's {@link #getParameterValues(String)} implementation.
     *
     * @param name parameter name
     * @return list of values, or null if parameter not found
     */
    protected abstract List<String> getUriParameterValues(String name);

    /**
     * Get all URI parameter names.
     * Used by parent's {@link #getParameterNames()} implementation.
     *
     * @return set of parameter names, never null
     */
    protected abstract Set<String> getUriParameterNames();

    /**
     * Set header directly (overwrite mode, no multi-value merge)
     *
     * @param key   the header key
     * @param value the header value
     */
    public abstract void setHeader(String key, Serializable value);

    /**
     * Remove header by key
     *
     * @param key the header key
     */
    public abstract void removeHeader(String key);

    /**
     * Set the URI to be used for the request.
     *
     * @param newUri the new URI
     */
    public abstract void setRewriteUri(String newUri);

    @Override
    public List<String> getFullHeader(String name) {
        Object val = getRawHeader(name);
        if (val == null) return null;
        if (val instanceof String) return java.util.Collections.singletonList((String) val);
        return (List<String>) val;
    }
}
