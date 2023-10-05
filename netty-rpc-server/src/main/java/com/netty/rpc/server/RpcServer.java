package com.netty.rpc.server;

import com.netty.rpc.annotation.NettyRpcService;
import com.netty.rpc.server.core.NettyServer;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.Map;

//把service转成bean
public class RpcServer extends NettyServer implements ApplicationContextAware, InitializingBean, DisposableBean {
    public RpcServer(String serverAddress, String registryAddress) {
        super(serverAddress, registryAddress);
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
//        把bean放入容器,这里应该可以用注解完成
//        拿到该bean的依赖，放入map
        Map<String, Object> serviceBeanMap = ctx.getBeansWithAnnotation(NettyRpcService.class);
        if (MapUtils.isNotEmpty(serviceBeanMap)) {
            for (Object serviceBean : serviceBeanMap.values()) {
                NettyRpcService nettyRpcService = serviceBean.getClass().getAnnotation(NettyRpcService.class);
//                去拿每个服务提供者的服务名和版本号
                String interfaceName = nettyRpcService.value().getName();
                String version = nettyRpcService.version();
                super.addService(interfaceName, version, serviceBean);
            }
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        super.start();
    }

    @Override
    public void destroy() {
        super.stop();
    }
}
