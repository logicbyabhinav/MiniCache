package test;

import com.minicache.engine.CacheEngine;
import com.minicache.command.CommandHandler;
import com.minicache.server.MiniCacheServer;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;

public class TestLiveServer {

    public static void main(String[] args) throws Exception {
        int testPort = 6389;
        CacheEngine engine = new CacheEngine(50);
        CommandHandler handler = new CommandHandler(engine);
        MiniCacheServer server = new MiniCacheServer(testPort, handler);

        // Start server in background thread
        Thread serverThread = new Thread(server::start);
        serverThread.start();
        Thread.sleep(300); // Give socket time to bind

        try {
            // 1. Single client test
            try (Socket client = new Socket("localhost", testPort);
                 BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                 PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

                // PING
                out.print("PING\r\n");
                out.flush();
                String line = in.readLine();
                assert "+PONG".equals(line) : "Expected +PONG, got " + line;

                // SET
                out.print("SET user abhinav\r\n");
                out.flush();
                line = in.readLine();
                assert "+OK".equals(line) : "Expected +OK, got " + line;

                // GET
                out.print("GET user\r\n");
                out.flush();
                line = in.readLine(); // $7
                assert "$7".equals(line) : "Expected $7, got " + line;
                line = in.readLine(); // abhinav
                assert "abhinav".equals(line) : "Expected abhinav, got " + line;

                // TTL SET EX
                out.print("SET token secret EX 1\r\n");
                out.flush();
                line = in.readLine();
                assert "+OK".equals(line) : "Expected +OK, got " + line;

                Thread.sleep(1100);
                out.print("GET token\r\n");
                out.flush();
                line = in.readLine();
                assert "$-1".equals(line) : "Expected $-1, got " + line;
            }

            // 2. Concurrency test with 5 simultaneous client sockets
            int clientCount = 5;
            ExecutorService clients = Executors.newFixedThreadPool(clientCount);
            CountDownLatch latch = new CountDownLatch(clientCount);

            for (int i = 0; i < clientCount; i++) {
                final int id = i;
                clients.submit(() -> {
                    try (Socket c = new Socket("localhost", testPort);
                         BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()));
                         PrintWriter out = new PrintWriter(c.getOutputStream(), true)) {

                        out.print("SET client_" + id + " val_" + id + "\r\n");
                        out.flush();
                        String r = in.readLine();
                        assert "+OK".equals(r) : "Expected +OK for client " + id;

                        out.print("GET client_" + id + "\r\n");
                        out.flush();
                        in.readLine(); // length
                        String val = in.readLine();
                        assert ("val_" + id).equals(val) : "Data mismatch for client " + id;
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(5, TimeUnit.SECONDS);
            assert finished : "Concurrency test timed out!";
            clients.shutdown();

            System.out.println("LIVE_TCP_SERVER_TESTS_PASSED_100_PERCENT");
        } finally {
            server.stop();
        }
    }
}
