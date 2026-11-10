package com.minicache.server;

import com.minicache.command.CommandHandler;
import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiniCacheServer {

    private final int port;
    private final CommandHandler commandHandler;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool;
    private volatile boolean running;

    public MiniCacheServer(int port, CommandHandler handler) {
        this.port = port;
        this.commandHandler = handler;
        this.threadPool = Executors.newCachedThreadPool();
    }

    public void start() {
        this.running = true;
        try {
            this.serverSocket = new ServerSocket(this.port);
            System.out.println("MiniCache server listening on port " + this.port + "...");
        } catch (IOException e) {
            System.err.println("Could not listen on port " + port + ": " + e.getMessage());
            return;
        }
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                threadPool.submit(() -> handleClient(socket));
            } catch (IOException e) {
                if (!running) {
                    break;
                }
            }

        }
    }

    public void handleClient(Socket socket) {
        BufferedReader in = null;
        PrintWriter out = null;
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            String line;
            while ((line = in.readLine()) != null) {
                String response = commandHandler.handle(line);
                out.print(response);
                out.flush();
            }
        } catch (IOException e) {
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception e) {
            }
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
            }

        }
    }

    public void stop() {
        this.running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
           
        }
        threadPool.shutdown();
    }
}
