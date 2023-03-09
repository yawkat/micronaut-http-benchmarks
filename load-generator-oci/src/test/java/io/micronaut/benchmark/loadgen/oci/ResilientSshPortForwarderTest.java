package io.micronaut.benchmark.loadgen.oci;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

class ResilientSshPortForwarderTest {
    private EventLoop loop;
    private Queue<Future<InetSocketAddress>> upstreamAddresses;
    private AtomicInteger closed;
    private ResilientSshPortForwarder forwarder;
    private InetSocketAddress localAddress;

    @BeforeEach
    public void setUp() {
        loop = new NioEventLoopGroup(1).next();
        upstreamAddresses = new ArrayDeque<>();
        closed = new AtomicInteger();
        forwarder = new ResilientSshPortForwarder(loop, () -> {
            Future<InetSocketAddress> addressFuture = upstreamAddresses.remove();
            return new ResilientSshPortForwarder.TransientPortForwarder() {
                @Override
                public Future<InetSocketAddress> boundAddress() {
                    return addressFuture;
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        });
        localAddress = forwarder.bind();
    }

    @AfterEach
    public void tearDown() {
        loop.parent().shutdownGracefully().syncUninterruptibly();
    }

    private ServerSocketChannel createNewUpstreamServer(ChannelHandler initialHandler) {
        return (ServerSocketChannel) new ServerBootstrap()
                .channel(NioServerSocketChannel.class)
                .group(loop)
                .childHandler(initialHandler)
                .localAddress(InetAddress.getLoopbackAddress(), 0)
                .bind().syncUninterruptibly().channel();
    }

    private SocketChannel connectDownstream(ChannelHandler initialHandler) {
        return (SocketChannel) new Bootstrap()
                .channel(NioSocketChannel.class)
                .group(loop)
                .handler(initialHandler)
                .connect(localAddress).syncUninterruptibly().channel();
    }

    @Test
    public void basicForwarding() throws InterruptedException {
        ServerSocketChannel upstream1 = createNewUpstreamServer(new EchoHandler());
        upstreamAddresses.add(loop.newSucceededFuture(upstream1.localAddress()));

        LinkedBlockingQueue<ByteBuf> incoming = new LinkedBlockingQueue<>();
        SocketChannel downstream = connectDownstream(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                incoming.put((ByteBuf) msg);
                ctx.read();
            }
        });
        downstream.writeAndFlush(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8)), downstream.voidPromise());
        ByteBuf response = incoming.take();
        Assertions.assertEquals("FOO", response.toString(StandardCharsets.UTF_8));
        response.release();

        Assertions.assertEquals(0, closed.get());
    }

    @Test
    public void multipleConnectionsToOneRemote() throws InterruptedException {
        ServerSocketChannel upstream1 = createNewUpstreamServer(new EchoHandler());
        upstreamAddresses.add(loop.newSucceededFuture(upstream1.localAddress()));

        {
            LinkedBlockingQueue<ByteBuf> incoming1 = new LinkedBlockingQueue<>();
            SocketChannel downstream1 = connectDownstream(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    incoming1.put((ByteBuf) msg);
                    ctx.read();
                }
            });
            downstream1.writeAndFlush(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8)), downstream1.voidPromise());
            ByteBuf response1 = incoming1.take();
            Assertions.assertEquals("FOO", response1.toString(StandardCharsets.UTF_8));
            response1.release();
        }

        {
            LinkedBlockingQueue<ByteBuf> incoming2 = new LinkedBlockingQueue<>();
            SocketChannel downstream2 = connectDownstream(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    incoming2.put((ByteBuf) msg);
                    ctx.read();
                }
            });
            downstream2.writeAndFlush(Unpooled.wrappedBuffer("bar".getBytes(StandardCharsets.UTF_8)), downstream2.voidPromise());
            ByteBuf response2 = incoming2.take();
            Assertions.assertEquals("BAR", response2.toString(StandardCharsets.UTF_8));
            response2.release();
        }

        Assertions.assertEquals(0, closed.get());
    }

    @Test
    public void fallback() throws InterruptedException {
        ServerSocketChannel upstream1 = createNewUpstreamServer(new EchoHandler());
        upstreamAddresses.add(loop.newSucceededFuture(upstream1.localAddress()));

        {
            LinkedBlockingQueue<ByteBuf> incoming1 = new LinkedBlockingQueue<>();
            SocketChannel downstream1 = connectDownstream(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    incoming1.put((ByteBuf) msg);
                    ctx.read();
                }
            });
            downstream1.writeAndFlush(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8)), downstream1.voidPromise());
            ByteBuf response1 = incoming1.take();
            Assertions.assertEquals("FOO", response1.toString(StandardCharsets.UTF_8));
            response1.release();
        }

        upstream1.close().syncUninterruptibly();
        ServerSocketChannel upstream2 = createNewUpstreamServer(new EchoHandler());
        upstreamAddresses.add(loop.newSucceededFuture(upstream2.localAddress()));

        {
            LinkedBlockingQueue<ByteBuf> incoming2 = new LinkedBlockingQueue<>();
            SocketChannel downstream2 = connectDownstream(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    incoming2.put((ByteBuf) msg);
                    ctx.read();
                }
            });
            downstream2.writeAndFlush(Unpooled.wrappedBuffer("bar".getBytes(StandardCharsets.UTF_8))).syncUninterruptibly();
            ByteBuf response2 = incoming2.take();
            Assertions.assertEquals("BAR", response2.toString(StandardCharsets.UTF_8));
            response2.release();
        }

        Assertions.assertEquals(1, closed.get());
    }

    @ChannelHandler.Sharable
    private static class EchoHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf request = (ByteBuf) msg;
            ByteBuf response = ctx.alloc().buffer();
            response.writeBytes(request.toString(StandardCharsets.UTF_8).toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            request.release();
            ctx.writeAndFlush(response, ctx.voidPromise());
            ctx.read();
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            ctx.read();
        }
    }
}