package com.github.euonmyoji.crossserverchat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ninja.leaping.configurate.ConfigurationOptions;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author yinyangshi
 */
public class ChatServer {
    private static final String SOCKETS_KEY = "minecraft-server-sockets";
    private static final String DEFAULT_IP = "null";
    private static ConfigurationLoader<CommentedConfigurationNode> loader = HoconConfigurationLoader.builder()
            .setPath(Paths.get("ChatServer.conf")).build();
    private static CommentedConfigurationNode cfg;
    private static volatile boolean running = true;
    private static volatile ServerSocket serverSocket;
    private static ThreadPoolExecutor coreThread = new ThreadPoolExecutor(2, 2, 10,
            TimeUnit.SECONDS, new LinkedBlockingDeque<>(), r -> new Thread(r, "Chat Thread Core"));
    private static ThreadPoolExecutor ioThread = new ThreadPoolExecutor(1, 10, 10,
            TimeUnit.SECONDS, new LinkedBlockingDeque<>(), r -> new Thread(r, "Chat Thread IO"));
    private static Set<Socket> clients = new HashSet<>();
    private static volatile Map<Object, ? extends CommentedConfigurationNode> sockets;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        try {
            cfg = loader.load(ConfigurationOptions.defaults().setShouldCopyDefaults(true));
            try {
                reload();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            if (cfg.getNode(SOCKETS_KEY).isVirtual()) {
                cfg.getNode(SOCKETS_KEY, "name", "ip").getString("127.0.0.1");
                cfg.getNode(SOCKETS_KEY, "name", "port").getInt(5210);
            }
            loader.save(cfg);
            System.out.println("初始化完成");
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        coreThread.execute(() -> {
            while (running) {
                try {
                    while (running) {
                        if (serverSocket != null) {
                            Socket socket = serverSocket.accept();
                            DataInputStream in = new DataInputStream(socket.getInputStream());
                            String s = in.readUTF();
                            if (s.startsWith("clientsAdd")) {
                                clients.add(socket);
                            } else {
                                DataInputStream datain = new DataInputStream(socket.getInputStream());
                                System.out.println(datain.readUTF());
                            }
                        } else {
                            Thread.sleep(1000);
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    sleep();
                }
            }
        });
        coreThread.execute(() -> {
            while (running) {
                if (clients.isEmpty()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignore) {
                    }
                } else {
                    clients.stream().filter(socket -> {
                        try {
                            return socket.getInputStream().available() > 0;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        return false;
                    }).forEach(socket -> ioThread.execute(() -> {
                        try {
                            DataInputStream in = new DataInputStream(socket.getInputStream());
                            String s = in.readUTF();
                            System.out.println(s);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }));
                }
            }
        });
        while (running) {
            String s = scanner.nextLine();
            if ("stop".equals(s)) {
                running = false;
                clients.forEach(socket -> {
                    if (!socket.isClosed()) {
                        try {
                            socket.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
            if ("reload".equals(s)) {
                reload();
                System.out.println("reload done");
            }
        }
        closeAllHarmfully();
    }

    private static void reload() {
        try {
            cfg = loader.load(ConfigurationOptions.defaults().setShouldCopyDefaults(true));
        } catch (IOException e) {
            e.printStackTrace();
        }
        String serverIP = cfg.getNode("server-socket", "ip").getString("null");
        int port = cfg.getNode("server-socket", "port").getInt(52016);
        try {
            if (DEFAULT_IP.equals(serverIP)) {
                serverSocket = new ServerSocket();
                serverSocket.bind(new InetSocketAddress(port));
            } else {
                serverSocket = new ServerSocket();
                serverSocket.bind(new InetSocketAddress(serverIP, port));
            }
        } catch (IOException e) {
            e.printStackTrace();
            serverSocket = null;
        }
        sockets = cfg.getNode(SOCKETS_KEY).getChildrenMap();
    }

    private static void closeAllHarmfully() {
        try {
            serverSocket.close();
            clients.forEach(socket -> {
                try {
                    socket.close();
                } catch (Throwable ignore) {
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
