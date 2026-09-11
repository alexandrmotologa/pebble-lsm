package com.engine.pebble.server;

import com.engine.pebble.domain.batch.WriteBatch;
import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleMetrics;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded TCP server implementing the Redis Serialization Protocol (RESP).
 * Runs on Java 21 Virtual Threads to service thousands of concurrent client connections.
 */
public class RespServer implements AutoCloseable {

    private final PebbleEngine engine;
    private final int port;
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public RespServer(PebbleEngine engine, int port) throws IOException {
        this.engine = engine;
        this.port = port;
        this.serverSocket = new ServerSocket(port);
    }

    public void start() {
        Thread.ofVirtual().name("pebble-resp-acceptor-", 0).start(() -> {
            while (running.get() && !serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread.ofVirtual().name("pebble-resp-client-", 0).start(() -> handleClient(socket));
                } catch (IOException e) {
                    if (!running.get()) break;
                }
            }
        });
    }

    private void handleClient(Socket socket) {
        try (socket;
             InputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

            while (running.get() && !socket.isClosed()) {
                List<String> command = RespProtocol.readCommand(in);
                if (command == null || command.isEmpty()) {
                    break;
                }

                String cmd = command.get(0).toUpperCase();

                switch (cmd) {
                    case "PING" -> {
                        String response = (command.size() > 1) ? command.get(1) : "PONG";
                        RespProtocol.writeSimpleString(out, response);
                    }
                    case "SET" -> {
                        if (command.size() < 3) {
                            RespProtocol.writeError(out, "wrong number of arguments for 'set' command");
                            break;
                        }
                        String key = command.get(1);
                        String value = command.get(2);

                        // Parse optional EX <seconds>
                        Duration ttl = null;
                        if (command.size() >= 5 && command.get(3).equalsIgnoreCase("EX")) {
                            long sec = Long.parseLong(command.get(4));
                            ttl = Duration.ofSeconds(sec);
                        }

                        if (ttl != null) {
                            engine.putString(key, value, ttl);
                        } else {
                            engine.putString(key, value);
                        }
                        RespProtocol.writeSimpleString(out, "OK");
                    }
                    case "GET" -> {
                        if (command.size() < 2) {
                            RespProtocol.writeError(out, "wrong number of arguments for 'get' command");
                            break;
                        }
                        Optional<byte[]> val = engine.get(command.get(1).getBytes(StandardCharsets.UTF_8));
                        RespProtocol.writeBulkString(out, val.orElse(null));
                    }
                    case "DEL" -> {
                        if (command.size() < 2) {
                            RespProtocol.writeError(out, "wrong number of arguments for 'del' command");
                            break;
                        }
                        long deleted = 0;
                        for (int i = 1; i < command.size(); i++) {
                            engine.delete(command.get(i).getBytes(StandardCharsets.UTF_8));
                            deleted++;
                        }
                        RespProtocol.writeInteger(out, deleted);
                    }
                    case "MGET" -> {
                        if (command.size() < 2) {
                            RespProtocol.writeError(out, "wrong number of arguments for 'mget' command");
                            break;
                        }
                        List<byte[]> values = new ArrayList<>(command.size() - 1);
                        for (int i = 1; i < command.size(); i++) {
                            Optional<byte[]> v = engine.get(command.get(i).getBytes(StandardCharsets.UTF_8));
                            values.add(v.orElse(null));
                        }
                        RespProtocol.writeArray(out, values);
                    }
                    case "MSET" -> {
                        if (command.size() < 3 || (command.size() - 1) % 2 != 0) {
                            RespProtocol.writeError(out, "wrong number of arguments for 'mset' command");
                            break;
                        }
                        WriteBatch batch = new WriteBatch();
                        for (int i = 1; i < command.size(); i += 2) {
                            batch.putString(command.get(i), command.get(i + 1));
                        }
                        engine.write(batch);
                        RespProtocol.writeSimpleString(out, "OK");
                    }
                    case "DBSIZE" -> {
                        PebbleMetrics m = engine.getMetrics();
                        RespProtocol.writeInteger(out, m.activeMemTableSize());
                    }
                    case "FLUSHDB" -> {
                        engine.flush();
                        RespProtocol.writeSimpleString(out, "OK");
                    }
                    case "INFO" -> {
                        PebbleMetrics m = engine.getMetrics();
                        String info = "# Server\r\n" +
                                "pebble_version:1.0.0\r\n" +
                                "connected_clients:1\r\n" +
                                "# Stats\r\n" +
                                "total_writes:" + m.writesCount() + "\r\n" +
                                "total_reads:" + m.readsCount() + "\r\n" +
                                "l0_files:" + m.l0FileCount() + "\r\n" +
                                "l1_files:" + m.l1FileCount() + "\r\n" +
                                "l2_files:" + m.l2FileCount() + "\r\n" +
                                "cache_hit_ratio:" + String.format("%.2f", m.cacheHitRatio() * 100.0) + "%\r\n";
                        RespProtocol.writeBulkString(out, info.getBytes(StandardCharsets.UTF_8));
                    }
                    case "QUIT" -> {
                        RespProtocol.writeSimpleString(out, "OK");
                        return;
                    }
                    default -> RespProtocol.writeError(out, "unknown command '" + cmd + "'");
                }
            }
        } catch (Exception ignored) {
            // Client disconnect
        }
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        if (!serverSocket.isClosed()) {
            serverSocket.close();
        }
    }
}
