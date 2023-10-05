package com.netty.rpc.server.registry;

import com.netty.rpc.config.Constant;
import com.netty.rpc.protocol.RpcProtocol;
import com.netty.rpc.protocol.RpcServiceInfo;
import com.netty.rpc.util.ServiceUtil;
import com.netty.rpc.zookeeper.CuratorClient;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 服务注册

 */
public class ServiceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ServiceRegistry.class);

    private CuratorClient curatorClient;
    private List<String> pathList = new ArrayList<>();

    public ServiceRegistry(String registryAddress) {
        this.curatorClient = new CuratorClient(registryAddress, 5000);
    }

    public void registerService(String host, int port, Map<String, Object> serviceMap) {
        // 注册服务信息, 既放入zookeeper中
        // 首先,先拿个list去存储 service的信息
        List<RpcServiceInfo> serviceInfoList = new ArrayList<>();

        //遍历之前的那个servicemap -----> {serviceKey, serviceBean}的结构
        for (String key : serviceMap.keySet()) {
            String[] serviceInfo = key.split(ServiceUtil.SERVICE_CONCAT_TOKEN);
//            重点看看这里的写法
            if (serviceInfo.length > 0) {
                RpcServiceInfo rpcServiceInfo = new RpcServiceInfo();
                rpcServiceInfo.setServiceName(serviceInfo[0]);
                if (serviceInfo.length == 2) {
                    rpcServiceInfo.setVersion(serviceInfo[1]);
                } else {
                    rpcServiceInfo.setVersion("");
                }
                logger.info("Register new service: {} ", key);
                serviceInfoList.add(rpcServiceInfo);
            } else {
                logger.warn("Can not get service name and version: {} ", key);
            }
        }
        try {
            RpcProtocol rpcProtocol = new RpcProtocol();
            rpcProtocol.setHost(host);
            rpcProtocol.setPort(port);
            rpcProtocol.setServiceInfoList(serviceInfoList);
            String serviceData = rpcProtocol.toJson();
            byte[] bytes = serviceData.getBytes();
            String path = Constant.ZK_DATA_PATH + "-" + rpcProtocol.hashCode();
            path = this.curatorClient.createPathData(path, bytes);
            pathList.add(path);
            logger.info("Register {} new service, host: {}, port: {}", serviceInfoList.size(), host, port);
        } catch (Exception e) {
            logger.error("Register service fail, exception: {}", e.getMessage());
        }

//        添加一个监听器，如果节点发生改变，那么直接执行新的registerService方法，依据是connectionState == RECONNECT
        curatorClient.addConnectionStateListener(new ConnectionStateListener() {
            @Override
            public void stateChanged(CuratorFramework curatorFramework, ConnectionState connectionState) {
                if (connectionState == ConnectionState.RECONNECTED) {
                    logger.info("Connection state: {}, register service after reconnected", connectionState);
                    registerService(host, port, serviceMap);
                }
            }
        });
    }

    public void unregisterService() {
        logger.info("Unregister all service");
        for (String path : pathList) {
            try {
                this.curatorClient.deletePath(path);
            } catch (Exception ex) {
                logger.error("Delete service path error: " + ex.getMessage());
            }
        }
        this.curatorClient.close();
    }
}
