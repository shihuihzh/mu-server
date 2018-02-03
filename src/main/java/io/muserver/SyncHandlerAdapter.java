package io.muserver;

import java.nio.ByteBuffer;

class SyncHandlerAdapter implements AsyncMuHandler {

    private final MuHandler muHandler;

    SyncHandlerAdapter(MuHandler muHandler) {
        this.muHandler = muHandler;
    }


    public boolean onHeaders(AsyncContext ctx, Headers headers) throws Exception {
        boolean handled;
        try {
            handled = muHandler.handle(ctx.request, ctx.response);
        } catch (Exception e) {
            System.out.println("Error while running handler " + muHandler);
            e.printStackTrace();
            MuServerHandler.sendPlainText(ctx, "Server error", 500);
            handled = true;
        }
        if (handled) {
            ctx.complete();
        }
        return handled;
    }

    public void onRequestData(AsyncContext ctx, ByteBuffer buffer) {
        ((NettyRequestAdapter) ctx.request).feed(buffer);
    }

    public void onRequestComplete(AsyncContext ctx) {
        ((NettyRequestAdapter) ctx.request).requestBodyComplete();
    }

}
