/*
 * (C) Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *   ohun@live.cn (夜色)
 */

package com.mpush.netty.server;

import com.mpush.api.service.BaseService;
import com.mpush.api.service.Listener;
import com.mpush.api.service.Server;
import com.mpush.api.service.ServiceException;
import com.mpush.netty.codec.PacketDecoder;
import com.mpush.netty.codec.PacketEncoder;
import com.mpush.tools.config.CC;
import com.mpush.tools.log.Logs;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by ohun on 2015/12/22.
 *
 * @author ohun@live.cn
 */
public abstract class NettyServer extends BaseService implements Server {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public enum State {Created, Initialized, Starting, Started, Shutdown}

    protected final AtomicReference<State> serverState = new AtomicReference<>(State.Created);

    protected final int port;
    protected EventLoopGroup bossGroup;
    protected EventLoopGroup workerGroup;

    public NettyServer(int port) {
        this.port = port;
    }

    public void init() {
        if (!serverState.compareAndSet(State.Created, State.Initialized)) {
            throw new IllegalStateException("Server already init");
        }
    }

    @Override
    public boolean isRunning() {
        return serverState.get() == State.Started;
    }

    @Override
    public void stop(Listener listener) {
        if (!serverState.compareAndSet(State.Started, State.Shutdown)) {
            IllegalStateException e = new IllegalStateException("server was already shutdown.");
            if (listener != null) listener.onFailure(e);
            Logs.Console.error("{} was already shutdown.", this.getClass().getSimpleName());
            return;
        }
        Logs.Console.error("try shutdown {}...", this.getClass().getSimpleName());
        if (workerGroup != null) workerGroup.shutdownGracefully().syncUninterruptibly();
        if (bossGroup != null) bossGroup.shutdownGracefully().syncUninterruptibly();
        Logs.Console.error("{} shutdown success.", this.getClass().getSimpleName());
        if (listener != null) {
            listener.onSuccess(port);
        }
    }

    @Override
    public void start(final Listener listener) {
        if (!serverState.compareAndSet(State.Initialized, State.Starting)) {
            throw new IllegalStateException("Server already started or have not init");
        }
        if (useNettyEpoll()) {
            createEpollServer(listener);
        } else {
            createNioServer(listener);
        }
    }

    private void createServer(final Listener listener, EventLoopGroup boss, EventLoopGroup work, Class<? extends ServerChannel> clazz) {
        /***
         * NioEventLoopGroup 是用来处理I/O操作的多线程事件循环器，
         * Netty提供了许多不同的EventLoopGroup的实现用来处理不同传输协议。
         * 在一个服务端的应用会有2个NioEventLoopGroup会被使用。
         * 第一个经常被叫做‘boss’，用来接收进来的连接。
         * 第二个经常被叫做‘worker’，用来处理已经被接收的连接，
         * 一旦‘boss’接收到连接，就会把连接信息注册到‘worker’上。
         * 如何知道多少个线程已经被使用，如何映射到已经创建的Channels上都需要依赖于EventLoopGroup的实现，
         * 并且可以通过构造函数来配置他们的关系。
         */
        this.bossGroup = boss;
        this.workerGroup = work;

        try {
            /**
             * ServerBootstrap 是一个启动NIO服务的辅助启动类
             * 你可以在这个服务中直接使用Channel
             */
            ServerBootstrap b = new ServerBootstrap();

            /**
             * 这一步是必须的，如果没有设置group将会报java.lang.IllegalStateException: group not set异常
             */
            b.group(bossGroup, workerGroup);

            /***
             * ServerSocketChannel以NIO的selector为基础进行实现的，用来接收新的连接
             * 这里告诉Channel如何获取新的连接.
             */
            b.channel(clazz);


            /***
             * 这里的事件处理类经常会被用来处理一个最近的已经接收的Channel。
             * ChannelInitializer是一个特殊的处理类，
             * 他的目的是帮助使用者配置一个新的Channel。
             * 也许你想通过增加一些处理类比如NettyServerHandler来配置一个新的Channel
             * 或者其对应的ChannelPipeline来实现你的网络程序。
             * 当你的程序变的复杂时，可能你会增加更多的处理类到pipeline上，
             * 然后提取这些匿名类到最顶层的类上。
             */
            b.childHandler(new ChannelInitializer<SocketChannel>() { // (4)
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    initPipeline(ch.pipeline());
                }
            });

            initOptions(b);

            /***
             * 绑定端口并启动去接收进来的连接
             */
            ChannelFuture f = b.bind(port).sync().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        Logs.Console.error("server start success on:{}", port);
                        if (listener != null) listener.onSuccess(port);
                    } else {
                        Logs.Console.error("server start failure on:{}", port, future.cause());
                        if (listener != null) listener.onFailure(future.cause());
                    }
                }
            });
            if (f.isSuccess()) {
                serverState.set(State.Started);
                /**
                 * 这里会一直等待，直到socket被关闭
                 */
                f.channel().closeFuture().sync();
            }

        } catch (Exception e) {
            logger.error("server start exception", e);
            if (listener != null) listener.onFailure(e);
            throw new ServiceException("server start exception, port=" + port, e);
        } finally {
            /***
             * 优雅关闭
             */
            stop(null);
        }
    }

    private void createNioServer(Listener listener) {
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1, getBossExecutor());
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(0, getWorkExecutor());
        createServer(listener, bossGroup, workerGroup, NioServerSocketChannel.class);
    }

    @SuppressWarnings("unused")
    private void createEpollServer(Listener listener) {
        EpollEventLoopGroup bossGroup = new EpollEventLoopGroup(1, getBossExecutor());
        EpollEventLoopGroup workerGroup = new EpollEventLoopGroup(0, getWorkExecutor());
        createServer(listener, bossGroup, workerGroup, EpollServerSocketChannel.class);
    }

    protected void initOptions(ServerBootstrap b) {

        /***
         * option()是提供给NioServerSocketChannel用来接收进来的连接。
         * childOption()是提供给由父管道ServerChannel接收到的连接，
         * 在这个例子中也是NioServerSocketChannel。
         */
        b.childOption(ChannelOption.SO_KEEPALIVE, true);


        /**
         * 在Netty 4中实现了一个新的ByteBuf内存池，它是一个纯Java版本的 jemalloc （Facebook也在用）。
         * 现在，Netty不会再因为用零填充缓冲区而浪费内存带宽了。不过，由于它不依赖于GC，开发人员需要小心内存泄漏。
         * 如果忘记在处理程序中释放缓冲区，那么内存使用率会无限地增长。
         * Netty默认不使用内存池，需要在创建客户端或者服务端的时候进行指定
         */
        b.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        b.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }


    public abstract ChannelHandler getChannelHandler();

    protected ChannelHandler getDecoder() {
        return new PacketDecoder();
    }

    protected ChannelHandler getEncoder() {
        return PacketEncoder.INSTANCE;
    }

    protected void initPipeline(ChannelPipeline pipeline) {
        pipeline.addLast("decoder", getDecoder());
        pipeline.addLast("encoder", getEncoder());
        pipeline.addLast("handler", getChannelHandler());
    }

    protected Executor getBossExecutor() {
        return null;
    }

    protected Executor getWorkExecutor() {
        return null;
    }

    private boolean useNettyEpoll() {
        if (!"netty".equals(CC.mp.core.epoll_provider)) return false;
        String name = CC.cfg.getString("os.name").toLowerCase(Locale.UK).trim();
        return name.startsWith("linux");
    }

    @Override
    protected void doStart(Listener listener) throws Throwable {

    }

    @Override
    protected void doStop(Listener listener) throws Throwable {

    }
}
