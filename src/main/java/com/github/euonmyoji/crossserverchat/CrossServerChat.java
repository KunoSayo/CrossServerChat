package com.github.euonmyoji.crossserverchat;

import com.google.inject.Inject;
import ninja.leaping.configurate.ConfigurationOptions;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.game.state.GameStoppingServerEvent;
import org.spongepowered.api.event.message.MessageChannelEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.serializer.TextSerializers;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

/**
 * @author yinyangshi
 */
@Plugin(id = "crossserverchat", name = "Cross Server Chat", version = "0.1.0", authors = "yinyangshi",
        description = "Cross-server chat")
public class CrossServerChat {
    private static Logger logger;
    @Inject
    @DefaultConfig(sharedRoot = true)
    public ConfigurationLoader<CommentedConfigurationNode> loader;
    private CommentedConfigurationNode cfg;
    private ServerSocket serverSocket;
    private String serverName;

    private Map<Object, ? extends CommentedConfigurationNode> sockets;

    private static final String SOCKETS_KEY = "sockets";
    private volatile boolean running = true;

    @Inject
    public void setLogger(Logger l) {
        logger = l;
    }

    @Listener
    public void onPreInit(GamePreInitializationEvent event) {
        try {
            cfg = loader.load(ConfigurationOptions.defaults().setShouldCopyDefaults(true));
            reload();
            if (cfg.getNode(SOCKETS_KEY).isVirtual()) {
                cfg.getNode(SOCKETS_KEY, "name", "ip").getString("127.0.0.1");
                cfg.getNode(SOCKETS_KEY, "name", "port").getInt(5209);
            }
            loader.save(cfg);
        } catch (IOException e) {
            logger.warn("cfg error!", e);
        }
    }

    @Listener
    public void onStarted(GameStartedServerEvent event) {
        running = true;
        Task.builder().async().name("CrossServerChat - accept socket").execute(() -> {
            int times = 0;
            while (running) {
                if (serverSocket != null) {
                    try (Socket socket = serverSocket.accept(); DataInputStream in = new DataInputStream(socket.getInputStream())) {
                        Sponge.getServer().getBroadcastChannel().send(TextSerializers.JSON.deserialize(in.readUTF()));
                        times = 0;
                    } catch (IOException e) {
                        logger.warn("accept socket error, error times:" + ++times, e.getMessage());
                        if (times > 5) {
                            logger.warn("accept socket error times is so big, close server socket! (reload to enable)");
                            closeServerSocket();
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignore) {
                        }
                    }
                } else {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignore) {
                    }
                }
            }
        }).submit(this);

        Sponge.getCommandManager().register(this, CommandSpec.builder().permission("crossserverchat.command.reload")
                .executor((src, args) -> {
                    try {
                        reload();
                        src.sendMessage(Text.of("[跨服聊天]重载完成"));

                    } catch (IOException e) {
                        src.sendMessage(Text.of("[跨服聊天]重载失败！"));
                        logger.warn("error about config", e);
                    }
                    return CommandResult.success();
                }).build(), "cscreload");
    }


    @Listener
    public void onStopping(GameStoppingServerEvent event) {
        closeServerSocket();
        running = false;
    }

    private static final String DEFAULT_IP = "null";

    @Listener(order = Order.POST)
    public void onChat(MessageChannelEvent.Chat event) {
        Task.builder().async().execute(() -> sockets.forEach((o, node) -> {
            String serverIP = node.getNode("ip").getString();
            int port = node.getNode("port").getInt();
            try (Socket socket = new Socket(serverIP, port); DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                out.writeUTF(TextSerializers.JSON.serialize(Text.builder()
                        .append(TextSerializers.FORMATTING_CODE.deserialize(serverName))
                        .append(event.getMessage()).build()));
            } catch (ConnectException e) {
                logger.debug(e.getMessage());
            } catch (IOException e) {
                logger.warn("send a socket error", e.getMessage());
            }
        })).submit(this);
    }

    private void reload() throws IOException {
        cfg = loader.load();
        String serverIP = cfg.getNode("server-socket", "ip").getString("null");
        int port = cfg.getNode("server-socket", "port").getInt(5209);
        serverName = cfg.getNode("server-socket", "name").getString("");
        closeServerSocket();
        try {
            serverSocket = new ServerSocket();
            if (DEFAULT_IP.equals(serverIP)) {
                serverSocket.bind(new InetSocketAddress(port));
            } else {
                serverSocket.bind(new InetSocketAddress(serverIP, port));
            }
        } catch (IOException e) {
            logger.warn("create a server socket error", e);
        }
        sockets = cfg.getNode(SOCKETS_KEY).getChildrenMap();
    }

    private void closeServerSocket() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
                serverSocket = null;
            } catch (IOException e) {
                logger.warn("close server socket error", e);
            }
        }
    }
}
