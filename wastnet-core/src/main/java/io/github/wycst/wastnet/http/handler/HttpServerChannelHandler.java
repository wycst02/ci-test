package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.upgrade.DefaultUpgradeHandler;
import io.github.wycst.wastnet.http.upgrade.UpgradeHandler;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.util.Objects;

/**
 * HTTP server handler
 *
 * @Date 2024/1/19 15:58
 * @Created by wangyc
 */
public final class HttpServerChannelHandler extends ChannelHandler<HttpMessage> {

    static final Log log = LogFactory.getLog(HttpServerChannelHandler.class);

    private ChannelHandler<?> childHandler;
    private HttpRequestHandler requestHandler = new HttpWelcomeHandler();
    private UpgradeHandler upgradeHandler = new DefaultUpgradeHandler();
    private HttpExceptionHandler exceptionHandler;
    private boolean printStackTraceError;

    public void setRequestHandler(HttpRequestHandler requestHandler) {
        Objects.requireNonNull(requestHandler, "requestHandler must not be null");
        this.requestHandler = requestHandler;
    }

    public void setUpgradeHandler(UpgradeHandler upgradeHandler) {
        Objects.requireNonNull(upgradeHandler, "upgradeHandler must not be null");
        this.upgradeHandler = upgradeHandler;
    }

    // Need to check for circular references
    public void setChildHandler(ChannelHandler<?> childHandler) {
        if (childHandler != this) {
            this.childHandler = childHandler;
        }
    }

    public void setPrintStackTraceError(boolean printStackTraceError) {
        this.printStackTraceError = printStackTraceError;
    }

    /**
     * Set custom exception handler
     *
     * @param exceptionHandler Exception handler
     */
    public void setExceptionHandler(HttpExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Prepare the handler before server starts.
     * <p>
     * If the requestHandler is also an UpgradeHandler (e.g., HttpRouterHandler extends DefaultUpgradeHandler),
     * use it directly as the upgrade handler.
     */
    public void prepare() {
        if (requestHandler instanceof UpgradeHandler) {
            this.upgradeHandler = (UpgradeHandler) requestHandler;
        }
    }

    @Override
    public void onConnected(ChannelContext ctx) throws IOException {
        if (childHandler != null) {
            childHandler.onConnected(ctx);
        }
    }

    @Override
    public void onHandle(ChannelContext ctx, HttpMessage message) throws IOException {
        try {
            executeHandle(ctx, message);
        } catch (Throwable throwable) {
            if (printStackTraceError) {
                log.error("Exception in onHandle:", throwable);
            }
        }
    }

    private void executeHandle(ChannelContext ctx, HttpMessage target) throws Throwable {
        if (target.isHttpRequest()) {
            HttpRequest request = (HttpRequest) target;
            if (!request.isBad()) {
                // handshake websocket or h2c upgrade
                if (upgradeHandler.upgrade(request, ctx)) { // upgrade success
                    return;
                } // normal http request
                HttpResponse response = request.getResponse();
                try { // handle application
                    requestHandler.handle(request, response);
                } catch (Throwable throwable) { // Handle exception thrown by application processor
                    handleApplicationException(request, response, throwable);
                } finally {
                    // Framework calls complete() to finish response (HttpCompleteResponse specific method)
                    ((HttpCompleteResponse) response).complete();
                    // clear unread data
                    request.complete();
                }
            } else {
                HttpInternalRequest internalRequest = (HttpInternalRequest) request;
                if (internalRequest.isProtocolError()) {
                    ctx.close();
                } else {
                    // if parse fail then ack a bad response
                    handleBadRequest(internalRequest);
                    request.complete();
                }
            }
        } else if (target.isUpgrade()) { // -> instanceof HttpUpgradeMessage
            upgradeHandler.handle(ctx, (HttpUpgradeMessage) target);
        } // ignore other http message
    }

    @Override
    public void onClosed(ChannelContext ctx) throws IOException {
        upgradeHandler.onClosed(ctx);
        if (childHandler != null) {
            childHandler.onClosed(ctx);
        }
    }

    public UpgradeHandler getUpgradeHandler() {
        return upgradeHandler;
    }

    /**
     * Handle exceptions thrown by application processor
     *
     * @param request   HTTP request
     * @param response  HTTP response
     * @param throwable Thrown exception
     */
    private void handleApplicationException(HttpRequest request, HttpResponse response, Throwable throwable) {
        // If no exception handler is set, return simple error response directly
        if (exceptionHandler == null) {
            // Reuse the failure handling logic of exception handler
            handleInternalException(response, throwable);
            return;
        }
        try {
            // Handle exception using configured exception handler
            exceptionHandler.handleException(request, response, throwable);
        } catch (Throwable handlerException) {
            // Safe handling when exception handler itself fails
            // Check if response has been partially modified by exception handler
            handleExceptionHandlerFailure(response, handlerException);
        }
    }

    /**
     * Handle failure when exception handler execution fails
     *
     * @param response         HTTP response
     * @param handlerException Exception thrown by exception handler
     */
    private void handleExceptionHandlerFailure(HttpResponse response, Throwable handlerException) {
        // Check if response has been partially modified by exception handler
        boolean responseModified = response.getStatus() != HttpStatus.OK ||
                response.getContentLength() > 0;
        if (!responseModified) {
            // If response was not modified, use internal exception handling
            handleInternalException(response, handlerException);
        }
        // If response was modified, do nothing - let the partially built response be sent
    }

    /**
     * Handle internal exceptions with basic error response
     *
     * @param response         HTTP response
     * @param handlerException Exception thrown by exception handler
     */
    private void handleInternalException(HttpResponse response, Throwable handlerException) {
        // Log the error
        if (printStackTraceError) {
            log.error("Exception handler failed:", handlerException);
        }

        // Return basic 500 error response
        try {
            response.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType("text/plain;charset=utf-8")
                    .body("500 Internal Server Error".getBytes());
        } catch (Throwable ignored) {
            // If even the most basic response cannot be built, give up
            log.error("Failed to send internal server error response:", handlerException);
        }
    }

    /**
     * Handle bad HTTP request with appropriate error response
     *
     * @param badRequest Bad HTTP request
     */
    private void handleBadRequest(HttpInternalRequest badRequest) {
        HttpResponse response = badRequest.getResponse();
        try {
            response.status(badRequest.getHttpStatus()).contentType("text/plain;charset=utf-8")
                    .body(badRequest.getErrorMessage().getBytes()).commit();
        } catch (Throwable throwable) {
            if (printStackTraceError) {
                log.error("Failed to send bad request response:", throwable);
            }
        }
    }
}

