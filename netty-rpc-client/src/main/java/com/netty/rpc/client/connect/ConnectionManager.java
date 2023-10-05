package com.netty.rpc.client.connect;

import com.netty.rpc.client.handler.RpcClientHandler;
import com.netty.rpc.client.handler.RpcClientInitializer;
import com.netty.rpc.client.route.RpcLoadBalance;
import com.netty.rpc.client.route.impl.RpcLoadBalanceRoundRobin;
import com.netty.rpc.protocol.RpcProtocol;
import com.netty.rpc.protocol.RpcServiceInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/*

链接管理器，将所有的Client连接集中管理
 */
public class ConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    private EventLoopGroup eventLoopGroup = new NioEventLoopGroup(4);
    private static ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(4, 8,
            600L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1000));

    private Map<RpcProtocol, RpcClientHandler> connectedServerNodes = new ConcurrentHashMap<>();
    private CopyOnWriteArraySet<RpcProtocol> rpcProtocolSet = new CopyOnWriteArraySet<>();
    private ReentrantLock lock = new ReentrantLock();
    private Condition connected = lock.newCondition();
    private long waitTimeout = 5000;
    //负载均衡轮训
    private RpcLoadBalance loadBalance = new RpcLoadBalanceRoundRobin();
    //线程进行状态
    private volatile boolean isRunning = true;

    private ConnectionManager() {
    }

    //客户端单例模式
    private static class SingletonHolder {
        private static final ConnectionManager instance = new ConnectionManager();
    }
    //提供一个方法去获得该单利：优化思路：可以使用双重判断机制来重写这个方法
    public static ConnectionManager getInstance() {
        return SingletonHolder.instance;
    }

//    写了三个同名的ucs方法处理不同请，重载即可

//    第一种用来判断是否存在失效节点

    //如果zk有更新那么调用该方法 使之异步，加入一个新的RPCProtocol，把失效的从集合Set中删除
    public void updateConnectedServer(List<RpcProtocol> serviceList) {
        // Now using 2 collections to manage the service info and TCP connections because making the connection is async
        // Once service info is updated on ZK, will trigger this function
        // Actually client should only care about the service it is using
        if (serviceList != null && serviceList.size() > 0) {
            // Update local server nodes cache
            HashSet<RpcProtocol> serviceSet = new HashSet<>(serviceList.size());
            for (int i = 0; i < serviceList.size(); ++i) {
                RpcProtocol rpcProtocol = serviceList.get(i);
                serviceSet.add(rpcProtocol);
            }

            // Add new server info
            for (final RpcProtocol rpcProtocol : serviceSet) {
                if (!rpcProtocolSet.contains(rpcProtocol)) {
                    connectServerNode(rpcProtocol);
                }
            }

            // Close and remove invalid server nodes
            for (RpcProtocol rpcProtocol : rpcProtocolSet) {
                if (!serviceSet.contains(rpcProtocol)) {
                    logger.info("Remove invalid service: " + rpcProtocol.toJson());
                    removeAndCloseHandler(rpcProtocol);
                }
            }
        } else {
            // No available service
            logger.error("No available service!");
            for (RpcProtocol rpcProtocol : rpcProtocolSet) {
                removeAndCloseHandler(rpcProtocol);
            }
        }
    }

//第二种根据请求事件类型来判断做何种动作
    public void updateConnectedServer(RpcProtocol rpcProtocol, PathChildrenCacheEvent.Type type) {
        if (rpcProtocol == null) {
            return;
        }
        if (type == PathChildrenCacheEvent.Type.CHILD_ADDED && !rpcProtocolSet.contains(rpcProtocol)) {
            //之后写个方法去增加服务节点 如果tupe为增加了话，这里面用枚举的方法
            connectServerNode(rpcProtocol);

        } else if (type == PathChildrenCacheEvent.Type.CHILD_UPDATED) {
            //TODO We may don't need to reconnect remote server if the server'IP and server'port are not changed
            //如果只是更新该节点信息，重连即可，只需把原本的handler关闭，其他照旧
            removeAndCloseHandler(rpcProtocol);
            connectServerNode(rpcProtocol);
        } else if (type == PathChildrenCacheEvent.Type.CHILD_REMOVED) {
            //关闭别连了
            removeAndCloseHandler(rpcProtocol);
        } else {
            throw new IllegalArgumentException("Unknow type:" + type);
        }
    }

//    第三种是核心，来控制client的请求
    private void connectServerNode(RpcProtocol rpcProtocol) {
        //先判断是否有服务响应，能够处理该client的请求
        if (rpcProtocol.getServiceInfoList() == null || rpcProtocol.getServiceInfoList().isEmpty()) {
            logger.info("No service on node, host: {}, port: {}", rpcProtocol.getHost(), rpcProtocol.getPort());
            return;
        }
//        有你就来到set中
        rpcProtocolSet.add(rpcProtocol);
        logger.info("New service node, host: {}, port: {}", rpcProtocol.getHost(), rpcProtocol.getPort());

//        服务的名称和版本
        for (RpcServiceInfo serviceProtocol : rpcProtocol.getServiceInfoList()) {
            logger.info("New service info, name: {}, version: {}", serviceProtocol.getServiceName(), serviceProtocol.getVersion());
        }
        final InetSocketAddress remotePeer = new InetSocketAddress(rpcProtocol.getHost(), rpcProtocol.getPort());

        //尝试去做多线程来处理多个客户端请求，目的是做到异步
        threadPoolExecutor.submit(new Runnable() {
            @Override
            public void run() {
                Bootstrap b = new Bootstrap();
                b.group(eventLoopGroup)
                        .channel(NioSocketChannel.class)
                        .handler(new RpcClientInitializer());

                ChannelFuture channelFuture = b.connect(remotePeer);
//                添加一个监听器，成功连接到服务节点后，执行监听器内部方法，用来替代asyn()的方式
                channelFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                        if (channelFuture.isSuccess()) {
                            logger.info("成功连接服务节点，该服务节点的remoteAddress为: " + remotePeer);
                            RpcClientHandler handler = channelFuture.channel().pipeline().get(RpcClientHandler.class);
                            connectedServerNodes.put(rpcProtocol, handler);
                            handler.setRpcProtocol(rpcProtocol);
                            signalAvailableHandler();
                        } else {
                            //这里可以尝试去判断一下连接失败的类型，进一步优化
                            logger.error("无法连接服务节点，该服务节点的remoteAddress为: " + remotePeer + "请尝试重新连接！");
                        }
                    }
                });
            }
        });
    }

    private void signalAvailableHandler() {
        lock.lock();
        try {
            connected.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean waitingForHandler() throws InterruptedException {
        lock.lock();//考虑一下线程安全， 一开始没考虑
        try {
            logger.warn("Waiting for available service");
            return connected.await(this.waitTimeout, TimeUnit.MILLISECONDS);
        } finally {
            lock.unlock();
        }
    }

    public RpcClientHandler chooseHandler(String serviceKey) throws Exception {

        //基础服务发现,只是判断有没有可用的服务节点,服务发现的操作在后面
        int size = connectedServerNodes.values().size();
        while (isRunning && size <= 0) {
//            client已经连接但是你的服务节点没连接，那么你需要等待这个连接
            try {
                //原本是用wait的方法，但这样线程同步了，栩优化
//                RpcClientHandler tmp = connectedServerNodes.get(serviceKey);
//                tmp.wait();

                //用一个方法去轮询 是否有服务节点能够连接该client
                waitingForHandler();
                size = connectedServerNodes.values().size();
            } catch (InterruptedException e) {
                logger.error("Waiting for available service is interrupted!", e);
            }
        }
        //负载均衡
        RpcProtocol rpcProtocol = loadBalance.route(serviceKey, connectedServerNodes);
        RpcClientHandler handler = connectedServerNodes.get(rpcProtocol);
        if (handler != null) {
            return handler;
        } else {
            throw new Exception("Can not get available connection");
        }
    }

//   关闭handler
    private void removeAndCloseHandler(RpcProtocol rpcProtocol) {
        RpcClientHandler handler = connectedServerNodes.get(rpcProtocol);
        if (handler != null) {
            handler.close();
        }
        connectedServerNodes.remove(rpcProtocol);
        rpcProtocolSet.remove(rpcProtocol);
    }

    public void removeHandler(RpcProtocol rpcProtocol) {
        rpcProtocolSet.remove(rpcProtocol);
        connectedServerNodes.remove(rpcProtocol);
        logger.info("Remove one connection, host: {}, port: {}", rpcProtocol.getHost(), rpcProtocol.getPort());
    }

//    优雅关闭
    public void stop() {
        isRunning = false;
        for (RpcProtocol rpcProtocol : rpcProtocolSet) {
            removeAndCloseHandler(rpcProtocol);
        }
        signalAvailableHandler();
        threadPoolExecutor.shutdown();
        eventLoopGroup.shutdownGracefully();
    }
}
