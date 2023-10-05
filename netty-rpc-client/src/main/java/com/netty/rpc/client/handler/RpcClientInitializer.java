package com.netty.rpc.client.handler;

import com.netty.rpc.codec.*;
import com.netty.rpc.serializer.Serializer;
import com.netty.rpc.serializer.hessian.HessianSerializer;
import com.netty.rpc.serializer.kryo.KryoSerializer;
import com.netty.rpc.serializer.protostuff.ProtostuffSerializer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;


public class RpcClientInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel socketChannel) throws Exception {
//        Serializer serializer = ProtostuffSerializer.class.newInstance();
//        Serializer serializer = HessianSerializer.class.newInstance();
        Serializer serializer = KryoSerializer.class.newInstance();
        ChannelPipeline cp = socketChannel.pipeline();
//        心跳检测
        cp.addLast(new IdleStateHandler(0, 0, Beat.BEAT_INTERVAL, TimeUnit.SECONDS));
//        编码 out
        cp.addLast(new RpcEncoder(RpcRequest.class, serializer));

//        自定长度解码器 用于将TCP发送的二进制帧进行拼接 对应在handler 中重写channelActive() 按字节长度来划分包的大小
        // in
        cp.addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 0));
        //in
        cp.addLast(new RpcDecoder(RpcResponse.class, serializer));
//        自定义handler in
        cp.addLast(new RpcClientHandler());
    }
}
