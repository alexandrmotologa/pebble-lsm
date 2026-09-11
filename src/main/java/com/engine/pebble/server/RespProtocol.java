package com.engine.pebble.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encoder and decoder for the Redis Serialization Protocol (RESP).
 */
public final class RespProtocol {

    private RespProtocol() {}

    public static List<String> readCommand(InputStream in) throws IOException {
        int firstByte = in.read();
        if (firstByte == -1) {
            return null; // EOF
        }

        if (firstByte != '*') {
            // Simple inline command format (e.g. "PING\r\n")
            StringBuilder sb = new StringBuilder();
            sb.append((char) firstByte);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') sb.append((char) b);
            }
            String[] parts = sb.toString().trim().split("\\s+");
            List<String> list = new ArrayList<>();
            for (String p : parts) {
                if (!p.isEmpty()) list.add(p);
            }
            return list;
        }

        // RESP Array: *<count>\r\n
        int count = readInteger(in);
        List<String> command = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            int prefix = in.read();
            if (prefix != '$') {
                throw new IOException("Expected '$' bulk string prefix, got: " + (char) prefix);
            }
            int len = readInteger(in);
            if (len == -1) {
                command.add(null);
            } else {
                byte[] data = in.readNBytes(len);
                // Consume trailing \r\n
                in.read(); // \r
                in.read(); // \n
                command.add(new String(data, StandardCharsets.UTF_8));
            }
        }

        return command;
    }

    private static int readInteger(InputStream in) throws IOException {
        int b;
        int result = 0;
        boolean negative = false;

        b = in.read();
        if (b == '-') {
            negative = true;
            b = in.read();
        }

        while (b != '\r') {
            if (b == -1) throw new EOFException("Unexpected EOF while reading integer");
            if (b >= '0' && b <= '9') {
                result = (result * 10) + (b - '0');
            }
            b = in.read();
        }
        in.read(); // Consume \n
        return negative ? -result : result;
    }

    public static void writeSimpleString(OutputStream out, String text) throws IOException {
        out.write(('+' + text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void writeError(OutputStream out, String message) throws IOException {
        out.write(("-ERR " + message + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void writeInteger(OutputStream out, long value) throws IOException {
        out.write((':' + String.valueOf(value) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void writeBulkString(OutputStream out, byte[] data) throws IOException {
        if (data == null) {
            out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
            out.write(('$' + String.valueOf(data.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    public static void writeArray(OutputStream out, List<byte[]> items) throws IOException {
        if (items == null) {
            out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
            out.write(('*' + String.valueOf(items.size()) + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (byte[] item : items) {
                if (item == null) {
                    out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(('$' + String.valueOf(item.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(item);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        out.flush();
    }
}
