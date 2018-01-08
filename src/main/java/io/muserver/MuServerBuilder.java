package io.muserver;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.Attribute;
import io.muserver.handlers.ResourceType;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerHandler.PROTO_ATTRIBUTE;

public class MuServerBuilder {
    private static final int LENGTH_OF_METHOD_AND_PROTOCOL = 17; // e.g. "OPTIONS HTTP/1.1 "
    private int minimumGzipSize = 1400;
    private int httpPort = 0;
    private int httpsPort = 0;
    private int maxHeadersSize = 8192;
    private int maxUrlSize = 8192 - LENGTH_OF_METHOD_AND_PROTOCOL;
    private List<AsyncMuHandler> asyncHandlers = new ArrayList<>();
    private List<MuHandler> handlers = new ArrayList<>();
    private SSLContext sslContext;
    private boolean gzipEnabled = true;
    private Set<String> mimeTypesToGzip = ResourceType.gzippableMimeTypes(ResourceType.getResourceTypes());

    public MuServerBuilder withHttpConnection(int port) {
        this.httpPort = port;
        return this;
    }

    public MuServerBuilder withGzipEnabled(boolean enabled) {
        this.gzipEnabled = enabled;
        return this;
    }
    public MuServerBuilder withGzip(int minimumGzipSize, Set<String> mimeTypesToGzip) {
        this.gzipEnabled = true;
        this.mimeTypesToGzip = mimeTypesToGzip;
        this.minimumGzipSize = minimumGzipSize;
        return this;
    }

    public MuServerBuilder withHttpDisabled() {
        this.httpPort = -1;
        return this;
    }

    public MuServerBuilder withHttpsConnection(int port, SSLContext sslEngine) {
        this.httpsPort = port;
        this.sslContext = sslEngine;
        return this;
    }

    public MuServerBuilder withHttpsDisabled() {
        this.httpsPort = -1;
        this.sslContext = null;
        return this;
    }

    public MuServerBuilder withMaxHeadersSize(int size) {
        this.maxHeadersSize = size;
        return this;
    }

    public MuServerBuilder withMaxUrlSize(int size) {
        this.maxUrlSize = size;
        return this;
    }

    public MuServerBuilder addAsyncHandler(AsyncMuHandler handler) {
        asyncHandlers.add(handler);
        return this;
    }

    public MuServerBuilder addHandler(MuHandler handler) {
        handlers.add(handler);
        return this;
    }

    public MuServerBuilder addHandler(Method method, String pathRegex, MuHandler handler) {
        return addHandler(Routes.route(method, pathRegex, handler));
    }

    public MuServer start() {
        if (!handlers.isEmpty()) {
            asyncHandlers.add(new SyncHandlerAdapter(handlers));
        }
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup();
        List<Channel> channels = new ArrayList<>();

        Runnable shutdown = () -> {
            try {
                for (Channel channel : channels) {
                    channel.close().sync();
                }
                bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
                workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();

            } catch (Exception e) {
                System.out.println("Error while shutting down. Will ignore. Error was: " + e.getMessage());
            }
        };


        try {
            Channel httpChannel = httpPort < 0 ? null : createChannel(bossGroup, workerGroup, httpPort, null);
            Channel httpsChannel = sslContext == null ? null : createChannel(bossGroup, workerGroup, httpsPort, sslContext);
            URI uri = null;
            if (httpChannel != null) {
                channels.add(httpChannel);
                uri = getUriFromChannel(httpChannel, "http");
            }
            URI httpsUri = null;
            if (httpsChannel != null) {
                channels.add(httpsChannel);
                httpsUri = getUriFromChannel(httpsChannel, "https");
            }

            return new MuServer(uri, httpsUri, shutdown);

        } catch (Exception ex) {
            shutdown.run();
            throw new MuException("Error while starting server", ex);
        }

    }

    private static URI getUriFromChannel(Channel httpChannel, String protocol) {
        InetSocketAddress a = (InetSocketAddress) httpChannel.localAddress();
        return URI.create(protocol + "://localhost:" + a.getPort());
    }

    private Channel createChannel(NioEventLoopGroup bossGroup, NioEventLoopGroup workerGroup, int port, SSLContext rawSSLContext) throws InterruptedException {
        boolean usesSsl = rawSSLContext != null;
        JdkSslContext sslContext = usesSsl ? new JdkSslContext(rawSSLContext, false, ClientAuth.NONE) : null;

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {

                protected void initChannel(SocketChannel socketChannel) throws Exception {
                    Attribute<String> proto = socketChannel.attr(PROTO_ATTRIBUTE);
                    proto.set(usesSsl ? "https" : "http");
                    ChannelPipeline p = socketChannel.pipeline();
                    if (usesSsl) {
                        p.addLast("ssl", sslContext.newHandler(socketChannel.alloc()));
                    }
                    p.addLast("decoder", new HttpRequestDecoder(maxUrlSize + LENGTH_OF_METHOD_AND_PROTOCOL, maxHeadersSize, 8192));
                    p.addLast("encoder", new HttpResponseEncoder());
                    if (gzipEnabled) {
                        p.addLast("compressor", new SelectiveHttpContentCompressor(minimumGzipSize, mimeTypesToGzip));
                    }
                    p.addLast("muhandler", new MuServerHandler(asyncHandlers));
                }
            });
        return b.bind(port).sync().channel();
    }

    public static MuServerBuilder muServer() {
        return new MuServerBuilder()
            .withHttpsDisabled();
    }

    public static MuServerBuilder httpServer() {
        return new MuServerBuilder()
            .withHttpsDisabled();
    }

    public static MuServerBuilder httpsServer() {
        return new MuServerBuilder()
            .withHttpsConnection(0, SSLContextBuilder.unsignedLocalhostCert())
            .withHttpDisabled();
    }
}