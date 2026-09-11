package com.engine.pebble.server;

import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RespServerTest {

    @TempDir
    Path tempDir;

    @Test
    void testRedisRespServerOperations() throws Exception {
        Path dbPath = tempDir.resolve("resp-db");
        PebbleOptions options = PebbleOptions.builder().dbPath(dbPath).build();

        try (PebbleEngine engine = PebbleEngine.open(options);
             RespServer server = new RespServer(engine, 0)) { // Port 0 for random free port

            server.start();
            int port = server.getPort();

            try (Socket client = new Socket("127.0.0.1", port);
                 OutputStream out = client.getOutputStream();
                 BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {

                // 1. Test PING
                out.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertThat(in.readLine()).isEqualTo("+PONG");

                // 2. Test SET
                out.write("*3\r\n$3\r\nSET\r\n$4\r\nuser\r\n$5\r\nAlex!\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertThat(in.readLine()).isEqualTo("+OK");

                // 3. Test GET
                out.write("*2\r\n$3\r\nGET\r\n$4\r\nuser\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertThat(in.readLine()).isEqualTo("$5");
                assertThat(in.readLine()).isEqualTo("Alex!");

                // 4. Test MSET (batch write)
                out.write("*5\r\n$4\r\nMSET\r\n$2\r\nk1\r\n$2\r\nv1\r\n$2\r\nk2\r\n$2\r\nv2\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertThat(in.readLine()).isEqualTo("+OK");

                // 5. Test MGET
                out.write("*3\r\n$4\r\nMGET\r\n$2\r\nk1\r\n$2\r\nk2\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertThat(in.readLine()).isEqualTo("*2");
                assertThat(in.readLine()).isEqualTo("$2");
                assertThat(in.readLine()).isEqualTo("v1");
                assertThat(in.readLine()).isEqualTo("$2");
                assertThat(in.readLine()).isEqualTo("v2");

                // 6. Test DEL
                out.write("*3\r\n$3\r\nDEL\r\n$2\r\nk1\r\n$2\r\nk2\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertThat(in.readLine()).isEqualTo(":2");
            }
        }
    }
}
