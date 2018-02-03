package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class MuServerHandler extends ChannelInboundHandlerAdapter {
    static final AttributeKey<String> PROTO_ATTRIBUTE = AttributeKey.newInstance("proto");
    static final AttributeKey<State> STATE_ATTRIBUTE = AttributeKey.newInstance("state");

    private final ConcurrentHashMap<ChannelHandlerContext, State> state = new ConcurrentHashMap<>();
    private final List<AsyncMuHandler> asyncHandlers;
    private final HandlerNode node;

    public MuServerHandler(List<AsyncMuHandler> asyncHandlers) {
        this.asyncHandlers = asyncHandlers;

        HandlerNode hn = null;
        for (int i = asyncHandlers.size() - 1; i >= 0; i--) {
            hn = new HandlerNode(asyncHandlers.get(i), hn);
        }
        this.node = hn;
    }


    private static final class State {
        public final AsyncContext asyncContext;
        public final AsyncMuHandler handler;

        private State(AsyncContext asyncContext, AsyncMuHandler handler) {
            this.asyncContext = asyncContext;
            this.handler = handler;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean processingComplete = true;
        try {
            if (msg instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) msg;

                if (request.decoderResult().isFailure()) {
                    handleHttpRequestDecodeFailure(ctx, request.decoderResult().cause());
                } else {

                    HttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK, false);
                    String proto = ctx.channel().attr(PROTO_ATTRIBUTE).get();

                    NettyRequestAdapter muRequest = new NettyRequestAdapter(proto, request);
                    AsyncContext asyncContext = new AsyncContext(muRequest, new NettyResponseAdaptor(ctx, muRequest, response), ctx.executor());

                    dealWithIt(ctx, asyncContext, node);
                    processingComplete = false;
                }


            } else if (msg instanceof HttpContent) {
                State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
                if (state == null) {
                    // This can happen when a request is rejected based on headers, and then the rejected body arrives
                    System.out.println("Got a chunk of message for an unknown request");
                } else {
                    HttpContent content = (HttpContent) msg;
                    ByteBuf byteBuf = content.content();
                    if (byteBuf.capacity() > 0) {
                        // TODO: why does the buffer need to be copied? Does this only need to happen for sync processing?
                        ByteBuf copy = byteBuf.copy();
                        ByteBuffer byteBuffer = ByteBuffer.allocate(byteBuf.capacity());
                        copy.readBytes(byteBuffer).release();
                        byteBuffer.flip();
                        state.handler.onRequestData(state.asyncContext, byteBuffer);
                    }
                    if (msg instanceof LastHttpContent) {
                        state.handler.onRequestComplete(state.asyncContext);
                    }
                }

            }
        } finally {
            if (processingComplete) {
                finishIt(ctx, msg, true);
            }
        }
    }

    private static void finishIt(ChannelHandlerContext ctx, Object msg, boolean stopProcessing) {
        if (stopProcessing) {
            ReferenceCountUtil.release(msg);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private static class HandlerNode {
        final AsyncMuHandler handler;
        final HandlerNode next;
        HandlerNode(AsyncMuHandler handler, HandlerNode next) {
            this.handler = handler;
            this.next = next;
        }
    }

    private void dealWithIt(ChannelHandlerContext ctx, AsyncContext asyncContext, HandlerNode node) throws Exception {
        if (node == null) {
            finishIt(ctx, asyncContext, false);
        } else {
            node.handler.onHeaders(asyncContext, asyncContext.request.headers())
                .addListener(future -> {
                    boolean did = (Boolean) future.getNow();
                    if (did) {
                        ctx.channel().attr(STATE_ATTRIBUTE).set(new State(asyncContext, node.handler));
                        finishIt(ctx, asyncContext, true);
                    } else {
                        dealWithIt(ctx, asyncContext, node.next);
                    }
                });
        }
//        AsyncMuHandler cur = handlers.get(index);
//        cur.onHeaders(asyncContext, asyncContext.request.headers())
//            .addListener((Future<? super Boolean> future) -> {
//                boolean handled = (Boolean)future.getNow();
//                if (handled) {
//                    asyncContext.complete();
//                } else {
//                    dealWithIt(ctx, asyncContext, index + 1);
//                }
//            });


//        for (AsyncMuHandler handler : handlers) {
//Promise<Boolean> handled = handler.onHeaders(asyncContext, asyncContext.request.headers());
//
//if (handled) {
//                state.put(ctx, new State(asyncContext, handler));
//                break;
//            }
//        }
//        if (!handled) {
//send404(asyncContext);
//asyncContext.complete();
//}
    }

    public static void send404(AsyncContext asyncContext) {
        sendPlainText(asyncContext, "404 Not Found", 404);
    }

    public static void sendPlainText(AsyncContext asyncContext, String message, int statusCode) {
        asyncContext.response.status(statusCode);
        asyncContext.response.contentType(ContentTypes.TEXT_PLAIN);
        asyncContext.response.headers().set(HeaderNames.CONTENT_LENGTH, message.length());
        asyncContext.response.write(message);
    }

    private void handleHttpRequestDecodeFailure(ChannelHandlerContext ctx, Throwable cause) {
        String message = "Server error";
        int code = 500;
        if (cause instanceof TooLongFrameException) {
            if (cause.getMessage().contains("header is larger")) {
                code = 431;
                message = "HTTP headers too large";
            } else if (cause.getMessage().contains("line is larger")) {
                code = 414;
                message = "URI too long";
            }
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(code), copiedBuffer(message.getBytes(UTF_8)));
        response.headers().set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN);
        response.headers().set(HeaderNames.CONTENT_LENGTH, message.length());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

}
