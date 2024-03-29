package cn.t.rpc.core.network.client;

import cn.t.rpc.core.config.RpcServiceProperties;
import cn.t.rpc.core.network.msg.CallMethodMsg;
import cn.t.rpc.core.network.msg.InternalMsg;
import cn.t.rpc.core.util.RpcServiceUtil;
import cn.t.rpc.core.zookeeper.ZookeeperTemplate;
import cn.t.tool.nettytool.client.NettyTcpClient;
import cn.t.tool.nettytool.launcher.DefaultLauncher;
import cn.t.tool.nettytool.server.DaemonServer;
import cn.t.util.common.CollectionUtil;
import cn.t.util.common.JsonUtil;
import cn.t.util.common.StringUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @description: rpc客户端
 * create: 2019-09-18 15:06
 * @author: yj
 **/
public class RpcClient {

    private static final Logger logger = LoggerFactory.getLogger(RpcClient.class);

    private ZookeeperTemplate zookeeperTemplate;

    private static Map<String, NettyTcpClient> tcpClientMap = new ConcurrentHashMap<>();

    public Object callMethod(CallMethodMsg callMethodMsg) {
        //根据接口名称缓存
        NettyTcpClient tcpClient = tcpClientMap.get(callMethodMsg.getInterfaceName());
        if(tcpClient == null) {
            RpcServiceProperties.ServerConfig server = getProvider(callMethodMsg.getInterfaceName());
            if(server == null) {
                logger.info("服务: {} 未发现可用端点", callMethodMsg.getInterfaceName());
            } else {
                RpcClientNettyChannelInitializer clientNettyChannelInitializer = new RpcClientNettyChannelInitializer();
                tcpClient = new NettyTcpClient("rpc-client", server.getHost(), server.getPort(), clientNettyChannelInitializer);
                DefaultLauncher defaultLauncher = new DefaultLauncher();
                List<DaemonServer> daemonServerList = new ArrayList<>();
                daemonServerList.add(tcpClient);
                defaultLauncher.setDaemonServerList(daemonServerList);
                defaultLauncher.startup();
                tcpClientMap.put(callMethodMsg.getInterfaceName(), tcpClient);
            }
        }
        tcpClient.sendMsg(callMethodMsg);
        Object result = null;
        int sleepCount = 0;
        while (sleepCount < 5) {
            result = RpcServiceUtil.getRequestResult(callMethodMsg.getId());
            if(result != null) {
                break;
            } else {
                System.out.println("结果集合: " + RpcServiceUtil.getAllResults());
                sleepCount++;
                try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
        if(result == null) {
            logger.info("rpc 远程调用失败, request id: {}", callMethodMsg.getId());
            System.out.println(RpcServiceUtil.getAllResults());
        } else if(result instanceof InternalMsg) {
            result = ((InternalMsg)result).result;
        }
        RpcServiceUtil.clearRequestResult(callMethodMsg.getId());
        return result;
    }

    private RpcServiceProperties.ServerConfig getProvider(String interfaceName) {
        try {
            List<String> nodes = zookeeperTemplate.getChildren("/");
            List<String> interfaceNameList;
            if(nodes.contains("rpc") && zookeeperTemplate.getChildren("/rpc").contains("services")) {
                interfaceNameList =  zookeeperTemplate.getChildren("/rpc/services");
            } else {
                interfaceNameList = Collections.emptyList();
            }
            RpcServiceProperties.ServerConfig server;
            if(interfaceNameList.contains(interfaceName)) {
                String serverListStr = zookeeperTemplate.getData("/rpc/services/" + interfaceName);
                if(StringUtil.isEmpty(serverListStr)) {
                    server = null;
                } else {
                    List<RpcServiceProperties.ServerConfig> serverList = JsonUtil.deserialize(serverListStr, new TypeReference<ArrayList<RpcServiceProperties.ServerConfig>>() {});
                    if(CollectionUtil.isEmpty(serverList)) {
                        server = null;
                    } else {
                        server = serverList.get(0);
                    }
                }
            } else {
                server = null;
            }
            return server;
        } catch (Exception e) {
            logger.error("获取RPC服务列表失败", e);
        }
        return null;
    }

    public RpcClient(ZookeeperTemplate zookeeperTemplate) {
        this.zookeeperTemplate = zookeeperTemplate;
    }
}
