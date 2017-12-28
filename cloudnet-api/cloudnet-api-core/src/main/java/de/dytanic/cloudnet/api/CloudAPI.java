/*
 * Copyright (c) Tarek Hosni El Alaoui 2017
 */

package de.dytanic.cloudnet.api;

import com.google.gson.reflect.TypeToken;
import de.dytanic.cloudnet.api.config.CloudConfigLoader;
import de.dytanic.cloudnet.api.database.DatabaseManager;
import de.dytanic.cloudnet.api.handlers.NetworkHandlerProvider;
import de.dytanic.cloudnet.api.network.packet.api.*;
import de.dytanic.cloudnet.api.network.packet.api.sync.*;
import de.dytanic.cloudnet.api.network.packet.in.*;
import de.dytanic.cloudnet.api.network.packet.out.*;
import de.dytanic.cloudnet.api.player.PlayerExecutorBridge;
import de.dytanic.cloudnet.lib.CloudNetwork;
import de.dytanic.cloudnet.lib.ConnectableAddress;
import de.dytanic.cloudnet.lib.DefaultType;
import de.dytanic.cloudnet.lib.NetworkUtils;
import de.dytanic.cloudnet.lib.network.NetDispatcher;
import de.dytanic.cloudnet.lib.network.NetworkConnection;
import de.dytanic.cloudnet.lib.network.WrapperInfo;
import de.dytanic.cloudnet.lib.network.auth.Auth;
import de.dytanic.cloudnet.lib.network.protocol.packet.PacketManager;
import de.dytanic.cloudnet.lib.network.protocol.packet.PacketRC;
import de.dytanic.cloudnet.lib.network.protocol.packet.result.Result;
import de.dytanic.cloudnet.lib.player.CloudPlayer;
import de.dytanic.cloudnet.lib.player.OfflinePlayer;
import de.dytanic.cloudnet.lib.player.permission.PermissionGroup;
import de.dytanic.cloudnet.lib.player.permission.PermissionPool;
import de.dytanic.cloudnet.lib.server.*;
import de.dytanic.cloudnet.lib.server.defaults.BasicServerConfig;
import de.dytanic.cloudnet.lib.server.info.ProxyInfo;
import de.dytanic.cloudnet.lib.server.info.ServerInfo;
import de.dytanic.cloudnet.lib.server.template.Template;
import de.dytanic.cloudnet.lib.service.plugin.ServerInstallablePlugin;
import de.dytanic.cloudnet.lib.service.ServiceId;
import de.dytanic.cloudnet.lib.utility.Acceptable;
import de.dytanic.cloudnet.lib.utility.CollectionWrapper;
import de.dytanic.cloudnet.lib.utility.document.Document;
import de.dytanic.cloudnet.lib.interfaces.MetaObj;
import de.dytanic.cloudnet.lib.utility.threading.Runnabled;
import de.dytanic.cloudnet.lib.utility.threading.Scheduler;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class CloudAPI implements MetaObj {

    private static CloudAPI instance;

    private Document config;
    private ServiceId serviceId;
    private CloudConfigLoader cloudConfigLoader;

    private NetworkConnection networkConnection;
    private int memory;
    private Runnable shutdownTask;

    private Scheduler scheduler = new Scheduler(20);
    private Thread thread = new Thread(scheduler);

    //Init
    private CloudNetwork cloudNetwork = new CloudNetwork();
    private NetworkHandlerProvider networkHandlerProvider = new NetworkHandlerProvider();
    private DatabaseManager databaseManager = new DatabaseManager(scheduler);

    public CloudAPI(CloudConfigLoader loader, Runnable cancelTask)
    {
        instance = this;
        this.cloudConfigLoader = loader;
        this.config = loader.loadConfig();
        this.networkConnection = new NetworkConnection(loader.loadConnnection());
        this.serviceId = config.getObject("serviceId", new TypeToken<ServiceId>() {
        }.getType());
        this.shutdownTask = cancelTask;
        this.memory = config.getInt("memory");

        initDefaultHandlers();
    }

    /*================= Internal =====================*/

    private void initDefaultHandlers()
    {
        PacketManager packetManager = networkConnection.getPacketManager();

        packetManager.registerHandler(PacketRC.SERVER_HANDLE + 1, PacketInCloudNetwork.class);
        packetManager.registerHandler(PacketRC.SERVER_HANDLE + 2, PacketInServerAdd.class);
        packetManager.registerHandler(PacketRC.SERVER_HANDLE + 3, PacketInServerInfoUpdate.class);
        packetManager.registerHandler(PacketRC.SERVER_HANDLE + 4, PacketInServerRemove.class);
        packetManager.registerHandler(PacketRC.SERVER_HANDLE + 5, PacketInProxyAdd.class);
        packetManager.registerHandler(PacketRC.SERVER_HANDLE + 6, PacketInProxyInfoUpdate.class);
        packetManager.registerHandler(PacketRC.SERVER_HANDLE + 7, PacketInProxyRemove.class);

        packetManager.registerHandler(PacketRC.SERVER_HANDLE + 8, PacketInCustomChannelMessage.class);
        packetManager.registerHandler(PacketRC.SERVER_HANDLE + 9, PacketInCustomSubChannelMessage.class);

        packetManager.registerHandler(PacketRC.PLAYER_HANDLE + 1, PacketInLoginPlayer.class);
        packetManager.registerHandler(PacketRC.PLAYER_HANDLE + 2, PacketInLogoutPlayer.class);
        packetManager.registerHandler(PacketRC.PLAYER_HANDLE + 3, PacketInUpdatePlayer.class);
        packetManager.registerHandler(PacketRC.PLAYER_HANDLE + 4, PacketInUpdateOnlineCount.class);
    }

    /*================= Internal =====================*/

    @Deprecated
    public void bootstrap()
    {
        this.networkConnection.tryConnect(config.getBoolean("ssl"), new NetDispatcher(networkConnection, false), new Auth(serviceId), shutdownTask);
        NetworkUtils.header();

        thread.setDaemon(true);
        thread.start();
    }

    @Deprecated
    public void shutdown()
    {
        this.networkConnection.tryDisconnect();
        scheduler.cancelAllTasks();
        thread.stop();
    }

    @Deprecated
    public CloudAPI update(ServerInfo serverInfo)
    {
        if (networkConnection.isConnected())
            networkConnection.sendPacket(new PacketOutUpdateServerInfo(serverInfo));
        return this;
    }

    @Deprecated
    public CloudAPI update(ProxyInfo proxyInfo)
    {
        if (networkConnection.isConnected())
            networkConnection.sendPacket(new PacketOutUpdateProxyInfo(proxyInfo));
        return this;
    }

        /*================= API =====================*/

    /**
     *Returns synchronized the OnlineCount from the group
     */
    public int getOnlineCount(String group)
    {
        AtomicInteger integer = new AtomicInteger(0);
        CollectionWrapper.iterator(getServers(group), new Runnabled<ServerInfo>() {
            @Override
            public void run(ServerInfo obj)
            {
                integer.addAndGet(obj.getOnlineCount());
            }
        });
        return integer.get();
    }

    /**
     * Returns the Scheduler from the cloudnet with 20 ticks
     * @return
     */
    public Scheduler getScheduler()
    {
        return scheduler;
    }

    /**
     * Returns the instance of the CloudAPI
     */
    public static CloudAPI getInstance()
    {
        return instance;
    }

    /**
     * Returns the Configuration Loader from this Plugin
     */
    public CloudConfigLoader getCloudConfigLoader()
    {
        return cloudConfigLoader;
    }

    /**
     * Internal CloudNetwork update set
     *
     * @param cloudNetwork
     */
    public void setCloudNetwork(CloudNetwork cloudNetwork)
    {
        this.cloudNetwork = cloudNetwork;
    }

    /**
     * Returns the wingui Config
     */
    public Document getConfig()
    {
        return config;
    }

    /**
     * Returns a simple cloudnetwork information base
     */
    public CloudNetwork getCloudNetwork()
    {
        return cloudNetwork;
    }

    /**
     * Returns the network server manager from cloudnet
     */
    public NetworkHandlerProvider getNetworkHandlerProvider()
    {
        return networkHandlerProvider;
    }

    /**
     * Returns the internal network connection to the cloudnet root
     */
    public NetworkConnection getNetworkConnection()
    {
        return networkConnection;
    }

    public String getPrefix()
    {
        return cloudNetwork.getMessages().getString("prefix");
    }

    /**
     * Returns the memory from this instance calc by Wrapper
     */
    public int getMemory()
    {
        return memory;
    }

    /**
     * Returns the shutdownTask which is default init
     */
    public Runnable getShutdownTask()
    {
        return shutdownTask;
    }

    /**
     * Returns the ServiceId from this instance
     */
    public ServiceId getServiceId()
    {
        return serviceId;
    }

    /**
     * Returns the Database Manager for the CloudNetDB functions
     */
    public DatabaseManager getDatabaseManager()
    {
        return databaseManager;
    }

    /**
     * Returns the group name from this instance
     */
    public String getGroup()
    {
        return serviceId.getGroup();
    }

    /**
     * Returns the UUID from this instance
     */
    public UUID getUniqueId()
    {
        return serviceId.getUniqueId();
    }

    /**
     * Returns the serverId (Lobby-1)
     */
    public String getServerId()
    {
        return serviceId.getServerId();
    }

    /**
     * Returns the Id (Lobby-1 -> "1")
     */
    public int getGroupInitId()
    {
        return serviceId.getId();
    }

    /**
     * Returns the wrapperid from this instance
     */
    public String getWrapperId()
    {
        return serviceId.getWrapperId();
    }

    /**
     * Returns the SimpleServerGroup of the parameter
     *
     * @param group
     */
    public SimpleServerGroup getServerGroupData(String group)
    {
        return cloudNetwork.getServerGroups().get(group);
    }

    /**
     * Returns the ProxyGroup of the parameter
     *
     * @param group
     */
    public ProxyGroup getProxyGroupData(String group)
    {
        return cloudNetwork.getProxyGroups().get(group);
    }

    /**
     * Returns the global onlineCount
     */
    public int getOnlineCount()
    {
        return cloudNetwork.getOnlineCount();
    }

    /**
     * Returns all the module properties
     * @return
     */
    public Document getModuleProperties()
    {
        return cloudNetwork.getModules();
    }

    /**
     * Returns the permissionPool of the cloudnetwork
     */
    public PermissionPool getPermissionPool()
    {
        return cloudNetwork.getModules().getObject("permissionPool", new TypeToken<PermissionPool>(){}.getType());
    }

    /**
     * Returns all active wrappers on cloudnet
     */
    public Collection<WrapperInfo> getWrappers()
    {
        return cloudNetwork.getWrappers();
    }

    /**
     * Returns the permission group from the permissions-system
     */
    public PermissionGroup getPermissionGroup(String group)
    {
        if(cloudNetwork.getModules().contains("permissionPool"))
            return ((PermissionPool)cloudNetwork.getModules().getObject("permissionPool", new TypeToken<PermissionPool>(){}.getType())).getGroups().get(group);
        return null;
    }

    /**
     * Returns one of the wrapper infos
     *
     * @param wrapperId
     */
    public WrapperInfo getWrapper(String wrapperId)
    {
        return CollectionWrapper.filter(cloudNetwork.getWrappers(), new Acceptable<WrapperInfo>() {
            @Override
            public boolean isAccepted(WrapperInfo value)
            {
                return value.getServerId().equalsIgnoreCase(wrapperId);
            }
        });
    }

    /**
     * Sends the data of the custom channel message to all proxys
     */
    public void sendCustomSubProxyMessage(String channel, String message, Document value)
    {
        networkConnection.sendPacket(new PacketOutCustomSubChannelMessage(DefaultType.BUNGEE_CORD, channel, message, value));
    }

    /**
     * Sends the data of the custom channel message to all server
     */
    public void sendCustomSubServerMessage(String channel, String message, Document value)
    {
        networkConnection.sendPacket(new PacketOutCustomSubChannelMessage(DefaultType.BUKKIT, channel, message, value));
    }

    /**
     * Sends the data of the custom channel message to one server
     */
    public void sendCustomSubServerMessage(String channel, String message, Document value, String serverName)
    {
        networkConnection.sendPacket(new PacketOutCustomSubChannelMessage(DefaultType.BUKKIT, serverName, channel, message, value));
    }

    /**
     * Sends the data of the custom channel message to proxy server
     */
    public void sendCustomSubProxyMessage(String channel, String message, Document value, String serverName)
    {
        networkConnection.sendPacket(new PacketOutCustomSubChannelMessage(DefaultType.BUNGEE_CORD, serverName, channel, message, value));
    }

    /**
     * Update the server group
     *
     * @param serverGroup
     */
    public void updateServerGroup(ServerGroup serverGroup)
    {
        networkConnection.sendPacket(new PacketOutUpdateServerGroup(serverGroup));
    }

    /**
     * Update the permission group
     */
    public void updatePermissionGroup(PermissionGroup permissionGroup)
    {
        networkConnection.sendPacket(new PacketOutUpdatePermissionGroup(permissionGroup));
    }

    /**
     * Update the proxy group
     *
     * @param proxyGroup
     */
    public void updateProxyGroup(ProxyGroup proxyGroup)
    {
        networkConnection.sendPacket(new PacketOutUpdateProxyGroup(proxyGroup));
    }

    /**
     *  Dispatch a command on cloudnet-core
     */
    public void sendCloudCommand(String commandLine)
    {
        networkConnection.sendPacket(new PacketOutExecuteCommand(commandLine));
    }

    /**
     * Dispatch a console message
     * @param output
     */
    public void dispatchConsoleMessage(String output)
    {
        networkConnection.sendPacket(new PacketOutDispatchConsoleMessage(output));
    }

    /**
     * Writes into the console of the server/proxy the command line
     * @param defaultType
     * @param serverId
     * @param commandLine
     */
    public void sendConsoleMessage(DefaultType defaultType, String serverId, String commandLine)
    {
        networkConnection.sendPacket(new PacketOutServerDispatchCommand(defaultType, serverId, commandLine));
    }

    public Map<String, SimpleServerGroup> getServerGroupMap()
    {
        return cloudNetwork.getServerGroups();
    }

    public Map<String, ProxyGroup> getProxyGroupMap()
    {
        return cloudNetwork.getProxyGroups();
    }

    /**
     * Stop a game server with the parameter of the serverId
     *
     * @param serverId
     */
    public void stopServer(String serverId)
    {
        networkConnection.sendPacket(new PacketOutStopServer(serverId));
    }

    /**
     * Stop a BungeeCord proxy server with the id @proxyId
     */
    public void stopProxy(String proxyId)
    {
        networkConnection.sendPacket(new PacketOutStopProxy(proxyId));
    }

        /*=====================================================================================*/

    /**
     * Creates a custom server log url for one server screen
     */
    public String createServerLogUrl(String serverId)
    {
        String rnd = NetworkUtils.randomString(10);
        networkConnection.sendPacket(new PacketOutCreateServerLog(rnd, serverId));
        ConnectableAddress connectableAddress = cloudConfigLoader.loadConnnection();
        return new StringBuilder(config.getBoolean("ssl") ? "https://" : "http://").append(connectableAddress.getHostName()).append(":").append(cloudNetwork.getWebPort()).append("/cloudnet/log?server=").append(rnd).substring(0);
    }

    /**
     * Start a proxy server with a group
     * @param proxyGroup
     */
    public void startProxy(ProxyGroup proxyGroup)
    {
        startProxy(proxyGroup, proxyGroup.getMemory(), new String[]{});
    }

    /**
     * Start a proxy server with a group
     * @param proxyGroup
     */
    public void startProxy(ProxyGroup proxyGroup, int memory, String[] processParameters)
    {
        startProxy(proxyGroup, memory, processParameters, null, new ArrayList<>(), new Document());
    }

    /**
     * Start a proxy server with a group
     * @param proxyGroup
     */
    public void startProxy(ProxyGroup proxyGroup, int memory, String[] processParameters, Document document)
    {
        startProxy(proxyGroup, memory, processParameters, null, new ArrayList<>(), document);
    }

    /**
     * Start a proxy server with a group
     * @param proxyGroup
     */
    public void startProxy(WrapperInfo wrapperInfo, ProxyGroup proxyGroup)
    {
        startProxy(wrapperInfo, proxyGroup, proxyGroup.getMemory(), new String[]{});
    }

    /**
     * Start a proxy server with a group
     * @param proxyGroup
     */
    public void startProxy(WrapperInfo wrapperInfo, ProxyGroup proxyGroup, int memory, String[] processParameters)
    {
        startProxy(wrapperInfo, proxyGroup, memory, processParameters, null, new ArrayList<>(), new Document());
    }

    /**
     * Start a proxy server with a group
     * @param proxyGroup
     */
    public void startProxy(WrapperInfo wrapperInfo, ProxyGroup proxyGroup, int memory, String[] processParameters, Document document)
    {
        startProxy(wrapperInfo, proxyGroup, memory, processParameters, null, new ArrayList<>(), document);
    }

    /*=====================================================================================*/

    /**
     * Start a proxy server with a group
     * @param proxyGroup
     */
    public void startProxy(ProxyGroup proxyGroup, int memory, String[] processParameters, String url, Collection<ServerInstallablePlugin> plugins, Document properties)
    {
        networkConnection.sendPacket(new PacketOutStartProxy(proxyGroup, memory, processParameters, url, plugins, properties));
    }

    /**
     * Start a proxy server with a group
     * @param proxyGroup
     */
    public void startProxy(WrapperInfo wrapperInfo, ProxyGroup proxyGroup, int memory, String[] processParameters, String url, Collection<ServerInstallablePlugin> plugins, Document properties)
    {
        networkConnection.sendPacket(new PacketOutStartProxy(wrapperInfo.getServerId(), proxyGroup, memory, processParameters, url, plugins, properties));
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup)
    {
        startGameServer(simpleServerGroup, new ServerConfig(false, "extra", new Document(), System.currentTimeMillis()));
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, String serverId)
    {
        startGameServer(simpleServerGroup, new ServerConfig(false, "extra", new Document(), System.currentTimeMillis()), serverId);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig)
    {
        startGameServer(simpleServerGroup, serverConfig, false);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, String serverId)
    {
        startGameServer(simpleServerGroup, serverConfig, false, serverId);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, boolean priorityStop)
    {
        startGameServer(simpleServerGroup, serverConfig, simpleServerGroup.getMemory(), priorityStop);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, boolean priorityStop, String serverId)
    {
        startGameServer(simpleServerGroup, serverConfig, simpleServerGroup.getMemory(), priorityStop, serverId);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop)
    {
        startGameServer(simpleServerGroup, serverConfig, memory, priorityStop, new Properties());
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop, String serverId)
    {
        startGameServer(simpleServerGroup, serverConfig, memory, priorityStop, new Properties(), serverId);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop, Properties properties)
    {
        startGameServer(simpleServerGroup, serverConfig, memory, new String[]{}, null, null, false, priorityStop, properties, null, new ArrayList<>());
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop, Properties properties, String serverId)
    {
        startGameServer(simpleServerGroup, serverConfig, memory, new String[]{}, null, null, false, priorityStop, properties, null, new ArrayList<>(), serverId);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop, Properties properties, Template template)
    {
        startGameServer(simpleServerGroup, serverConfig, memory, new String[]{}, template, null, false, priorityStop, properties, null, new ArrayList<>());
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop, Properties properties, Template template, String serverId)
    {
        startGameServer(simpleServerGroup, serverConfig, memory, new String[]{}, template, null, false, priorityStop, properties, null, new ArrayList<>(), serverId);
    }

    /*==================================================================*/

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, Template template)
    {
        startGameServer(simpleServerGroup, new ServerConfig(false, "extra", new Document(), System.currentTimeMillis()), template);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, Template template)
    {
        startGameServer(simpleServerGroup, serverConfig, false, template);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, Template template, String serverId)
    {
        startGameServer(simpleServerGroup, serverConfig, false, template, serverId);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, boolean priorityStop, Template template)
    {
        startGameServer(simpleServerGroup, serverConfig, simpleServerGroup.getMemory(), priorityStop, template);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, boolean priorityStop, Template template, String serverId)
    {
        startGameServer(simpleServerGroup, serverConfig, simpleServerGroup.getMemory(), priorityStop, template, serverId);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop, Template template)
    {
        startGameServer(simpleServerGroup, serverConfig, memory, priorityStop, new Properties(), template);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop, Template template, String serverId)
    {
        startGameServer(simpleServerGroup, serverConfig, memory, priorityStop, new Properties(), template, serverId);
    }

    /*==================================================================*/

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(WrapperInfo wrapperInfo, SimpleServerGroup simpleServerGroup)
    {
        startGameServer(wrapperInfo, simpleServerGroup, new ServerConfig(false, "extra", new Document(), System.currentTimeMillis()));
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(WrapperInfo wrapperInfo, SimpleServerGroup simpleServerGroup, ServerConfig serverConfig)
    {
        startGameServer(wrapperInfo, simpleServerGroup, serverConfig, false);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(WrapperInfo wrapperInfo, SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, boolean priorityStop)
    {
        startGameServer(wrapperInfo, simpleServerGroup, serverConfig, simpleServerGroup.getMemory(), priorityStop);
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(WrapperInfo wrapperInfo, SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop)
    {
        startGameServer(wrapperInfo, simpleServerGroup, serverConfig, memory, priorityStop, new Properties());
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(WrapperInfo wrapperInfo, SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop, Properties properties)
    {
        startGameServer(wrapperInfo, simpleServerGroup, serverConfig, memory, new String[]{}, null, null, false, priorityStop, properties, null, new ArrayList<>());
    }

    /**
     * Start a game server
     *
     * @param simpleServerGroup
     */
    public void startGameServer(WrapperInfo wrapperInfo, SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, boolean priorityStop, Properties properties, Template template)
    {
        startGameServer(wrapperInfo, simpleServerGroup, serverConfig, memory, new String[]{}, template, null, false, priorityStop, properties, null, new ArrayList<>());
    }

    /**
     * Start a new game server with full parameters
     *
     * @param simpleServerGroup
     * @param serverConfig
     * @param memory
     * @param processParameters
     * @param template
     * @param onlineMode
     * @param priorityStop
     * @param properties
     * @param url
     * @param plugins
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, String[] processParameters, Template template, String customServerName, boolean onlineMode, boolean priorityStop, Properties properties, String url, Collection<ServerInstallablePlugin> plugins)
    {
        networkConnection.sendPacket(new PacketOutStartServer(simpleServerGroup.getName(), memory, serverConfig, properties, priorityStop, processParameters, template, customServerName, onlineMode, plugins, url));
    }

    /**
     * Start a new game server with full parameters
     *
     * @param simpleServerGroup
     * @param serverConfig
     * @param memory
     * @param processParameters
     * @param template
     * @param onlineMode
     * @param priorityStop
     * @param properties
     * @param url
     * @param plugins
     */
    public void startGameServer(SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, String[] processParameters, Template template, String customServerName, boolean onlineMode, boolean priorityStop, Properties properties, String url, Collection<ServerInstallablePlugin> plugins, String serverId)
    {
        networkConnection.sendPacket(new PacketOutStartServer(simpleServerGroup.getName(), memory, serverConfig, properties, priorityStop, processParameters, template, customServerName, onlineMode, plugins, url));
    }

    /**
     * Start a new game server with full parameters
     *
     * @param simpleServerGroup
     * @param serverConfig
     * @param memory
     * @param processParameters
     * @param template
     * @param onlineMode
     * @param priorityStop
     * @param properties
     * @param url
     * @param plugins
     */
    public void startGameServer(WrapperInfo wrapperInfo, SimpleServerGroup simpleServerGroup, ServerConfig serverConfig, int memory, String[] processParameters, Template template, String customServerName, boolean onlineMode, boolean priorityStop, Properties properties, String url, Collection<ServerInstallablePlugin> plugins)
    {
        networkConnection.sendPacket(new PacketOutStartServer(wrapperInfo, simpleServerGroup.getName(), memory, serverConfig, properties, priorityStop, processParameters, template, customServerName, onlineMode, plugins, url));
    }

    /**
     * Start a new game server with full parameters
     *
     * @param simpleServerGroup
     * @param serverConfig
     * @param memory
     * @param processParameters
     * @param template
     * @param onlineMode
     * @param priorityStop
     * @param properties
     * @param url
     * @param plugins
     */
    public void startGameServer(WrapperInfo wrapperInfo, SimpleServerGroup simpleServerGroup, String serverId, ServerConfig serverConfig, int memory, String[] processParameters, Template template, String customServerName, boolean onlineMode, boolean priorityStop, Properties properties, String url, Collection<ServerInstallablePlugin> plugins)
    {
        networkConnection.sendPacket(new PacketOutStartServer(wrapperInfo, simpleServerGroup.getName(), serverId, memory, serverConfig, properties, priorityStop, processParameters, template, customServerName, onlineMode, plugins, url));
    }

    /**
     * Start a Cloud-Server with those Properties
     */
    public void startCloudServer(WrapperInfo wrapperInfo, String serverName, int memory, boolean priorityStop)
    {
        startCloudServer(wrapperInfo, serverName, new BasicServerConfig(), memory, priorityStop);
    }

    /**
     * Start a Cloud-Server with those Properties
     */
    public void startCloudServer(WrapperInfo wrapperInfo, String serverName, ServerConfig serverConfig, int memory, boolean priorityStop)
    {
        startCloudServer(wrapperInfo, serverName, serverConfig, memory, priorityStop, new String[0], new ArrayList<>(), new Properties(), ServerGroupType.BUKKIT);
    }

    /**
     * Start a Cloud-Server with those Properties
     */
    public void startCloudServer(WrapperInfo wrapperInfo, String serverName, ServerConfig serverConfig, int memory, boolean priorityStop, String[] processPreParameters, Collection<ServerInstallablePlugin> plugins,
                                 Properties properties, ServerGroupType serverGroupType)
    {
        networkConnection.sendPacket(new PacketOutStartCloudServer(wrapperInfo, serverName, serverConfig, memory, priorityStop, processPreParameters, plugins, properties, serverGroupType));
    }

    /**
     * Start a Cloud-Server with those Properties
     */
    public void startCloudServer(String serverName, int memory, boolean priorityStop)
    {
        startCloudServer(serverName, new BasicServerConfig(), memory, priorityStop);
    }

    /**
     * Start a Cloud-Server with those Properties
     */
    public void startCloudServer(String serverName, ServerConfig serverConfig, int memory, boolean priorityStop)
    {
        startCloudServer(serverName, serverConfig, memory, priorityStop, new String[0], new ArrayList<>(), new Properties(), ServerGroupType.BUKKIT);
    }

    /**
     * Start a Cloud-Server with those Properties
     */
    public void startCloudServer(String serverName, ServerConfig serverConfig, int memory, boolean priorityStop, String[] processPreParameters, Collection<ServerInstallablePlugin> plugins,
                                 Properties properties, ServerGroupType serverGroupType)
    {
        networkConnection.sendPacket(new PacketOutStartCloudServer(serverName, serverConfig, memory, priorityStop, processPreParameters, plugins, properties, serverGroupType));
    }

    /*==========================================================================*/

    /**
     * Update the CloudPlayer objective
     *
     * @param cloudPlayer
     */
    public void updatePlayer(CloudPlayer cloudPlayer)
    {
        networkConnection.sendPacket(new PacketOutUpdatePlayer(CloudPlayer.newOfflinePlayer(cloudPlayer)));
    }

    /**
     * Updates a offlinePlayer Objective on the database
     *
     * @param offlinePlayer
     */
    public void updatePlayer(OfflinePlayer offlinePlayer)
    {
        networkConnection.sendPacket(new PacketOutUpdatePlayer(offlinePlayer));
    }

    /**
     * Returns all servers on network
     */
    public Collection<ServerInfo> getServers()
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetServers(), networkConnection);
        return result.getResult().getObject("serverInfos", new TypeToken<Collection<ServerInfo>>() {
        }.getType());
    }

    /**
     * Returns the ServerInfo from all CloudGameServers
     */
    public Collection<ServerInfo> getCloudServers()
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetCloudServers(), networkConnection);
        return result.getResult().getObject("serverInfos", new TypeToken<Collection<ServerInfo>>(){}.getType());
    }

    /**
     * Returns all serverInfos from group #group
     *
     * @param group
     */
    public Collection<ServerInfo> getServers(String group)
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetServers(group), networkConnection);
        return result.getResult().getObject("serverInfos", new TypeToken<Collection<ServerInfo>>() {
        }.getType());
    }

    /**
     * Returns all proxyInfos on network
     */
    public Collection<ProxyInfo> getProxys()
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetProxys(), networkConnection);
        return result.getResult().getObject("proxyInfos", new TypeToken<Collection<ProxyInfo>>() {
        }.getType());
    }

    /**
     * Returns the ProxyInfos from all proxys in the group #group
     *
     * @param group
     */
    public Collection<ProxyInfo> getProxys(String group)
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetProxys(group), networkConnection);
        return result.getResult().getObject("proxyInfos", new TypeToken<Collection<ProxyInfo>>() {
        }.getType());
    }

    /**
     * Returns all OnlinePlayers on Network
     */
    public Collection<CloudPlayer> getOnlinePlayers()
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetPlayers(), networkConnection);
        Collection<CloudPlayer> cloudPlayers = result.getResult().getObject("players", new TypeToken<Collection<CloudPlayer>>() {
        }.getType());

        if(cloudPlayers == null) return null;

        for(CloudPlayer cloudPlayer : cloudPlayers)
        {
            cloudPlayer.setPlayerExecutor(new PlayerExecutorBridge());
        }
        return cloudPlayers;
    }

    /**
     * Retuns a online CloudPlayer on network or null if the player isn't online
     */
    public CloudPlayer getOnlinePlayer(UUID uniqueId)
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetPlayer(uniqueId), networkConnection);
        CloudPlayer cloudPlayer = result.getResult().getObject("player", CloudPlayer.TYPE);
        if(cloudPlayer == null) return null;
        cloudPlayer.setPlayerExecutor(new PlayerExecutorBridge());
        return cloudPlayer;
    }

    /**
     * Returns a offline player which registerd or null
     *
     * @param uniqueId
     */
    public OfflinePlayer getOfflinePlayer(UUID uniqueId)
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetOfflinePlayer(uniqueId), networkConnection);
        return result.getResult().getObject("player", new TypeToken<OfflinePlayer>() {
        }.getType());
    }

    /**
     * Returns a offline player which registerd or null
     *
     * @param name
     */
    public OfflinePlayer getOfflinePlayer(String name)
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetOfflinePlayer(name), networkConnection);
        return result.getResult().getObject("player", new TypeToken<OfflinePlayer>() {
        }.getType());
    }

    /**
     * Returns the ServerGroup from the name or null
     *
     * @param name
     */
    public ServerGroup getServerGroup(String name)
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetServerGroup(name), networkConnection);
        return result.getResult().getObject("serverGroup", new TypeToken<ServerGroup>() {
        }.getType());
    }

    /**
     * Returns from a registerd Player the uniqueId or null if the player doesn't exists
     */
    public UUID getPlayerUniqueId(String name)
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutNameUUID(name), networkConnection);
        return result.getResult().getObject("uniqueId", new TypeToken<UUID>() {
        }.getType());
    }

    /**
     * Returns from a registerd Player the name or null if the player doesn't exists
     */
    public String getPlayerName(UUID uniqueId)
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutNameUUID(uniqueId), networkConnection);
        return result.getResult().getString("name");
    }

    /**
     * Returns the ServerInfo from one gameServer where serverName = serverId
     */
    public ServerInfo getServerInfo(String serverName)
    {
        Result result = networkConnection.getPacketManager().sendQuery(new PacketAPIOutGetServer(serverName), networkConnection);
        return result.getResult().getObject("serverInfo", new TypeToken<ServerInfo>(){}.getType());
    }

    /*================================================================================*/

}