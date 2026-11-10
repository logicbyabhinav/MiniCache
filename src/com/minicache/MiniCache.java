package com.minicache;

import com.minicache.engine.CacheEngine;
import com.minicache.command.CommandHandler;
import com.minicache.server.MiniCacheServer;

public class MiniCache {

    public static final int DEFAULT_PORT = 6379;
    public static final int DEFAULT_CAPACITY = 10000;

    public static void main(String[] args) {
        System.out.println("Starting MiniCache v1.0.0 (in-memory Redis-compatible engine)...");

        int port = DEFAULT_PORT;
        int capacity = DEFAULT_CAPACITY;

        CacheEngine engine = new CacheEngine(capacity);
        CommandHandler handler = new CommandHandler(engine);
        MiniCacheServer server = new MiniCacheServer(port, handler);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down MiniCache daemon gracefully...");
            server.stop();
        }));

        server.start();
    }
}