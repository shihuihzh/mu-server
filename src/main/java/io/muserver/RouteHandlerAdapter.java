package io.muserver;

import io.muserver.rest.PathMatch;
import io.muserver.rest.UriPattern;
import io.netty.util.concurrent.Future;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;

class RouteHandlerAdapter implements AsyncMuHandler {

	private final ExecutorService executor;
    private final RouteHandler routeHandler;
    private final UriPattern uriPattern;
    private final Method method;

    public RouteHandlerAdapter(Method method, String uriTemplate, ExecutorService executor, RouteHandler routeHandler) {
        this.executor = executor;
        this.routeHandler = routeHandler;
        this.uriPattern = UriPattern.uriTemplateToRegex(uriTemplate);
        this.method = method;
    }

    public Future<Boolean> onHeaders(AsyncContext ctx, Headers headers) throws Exception {

        MuRequest request = ctx.request;
        boolean methodMatches = method == null || method.equals(request.method());
        if (methodMatches) {
            PathMatch matcher = uriPattern.matcher(request.uri());
            if (matcher.fullyMatches()) {
                executor.submit(() -> {
                    try {
                        routeHandler.handle(request, ctx.response, matcher.params());
                    } catch (Exception e) {
                        System.out.println("Error while running route handler " + routeHandler);
                        e.printStackTrace();
                        MuServerHandler.sendPlainText(ctx, "Server error", 500);
                    } finally {
                        ctx.complete();
                    }
                });
                return ctx.wasHandled();
            }
        }

        return ctx.wasNotHandled();
	}

    public void onRequestData(AsyncContext ctx, ByteBuffer buffer) {
        ((NettyRequestAdapter) ctx.request).feed(buffer);
    }

    public void onRequestComplete(AsyncContext ctx) {
        ((NettyRequestAdapter) ctx.request).requestBodyComplete();
    }


}
