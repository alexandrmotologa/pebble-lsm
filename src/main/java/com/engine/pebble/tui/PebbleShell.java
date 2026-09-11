package com.engine.pebble.tui;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleOptions;
import com.engine.pebble.iterator.StorageEntry;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Interactive REPL shell with command history, auto-completion, and formatted outputs.
 */
public class PebbleShell {

    public static void run(Path dbPath) {
        PebbleOptions options = PebbleOptions.builder().dbPath(dbPath).build();

        System.out.println("================================================================================");
        System.out.println(" PebbleLSM Interactive Shell (v1.0.0)");
        System.out.println(" Connected to: " + dbPath.toAbsolutePath());
        System.out.println(" Type 'help' for command list or 'exit' to quit.");
        System.out.println("================================================================================");

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            Terminal terminal = null;
            LineReader reader = null;

            try {
                terminal = TerminalBuilder.builder().system(true).build();
                Completer completer = new StringsCompleter("put", "get", "delete", "scan", "stats", "compact", "flush", "help", "exit", "quit");
                reader = LineReaderBuilder.builder()
                        .terminal(terminal)
                        .completer(completer)
                        .build();
            } catch (Exception ignored) {
                // Fallback for non-interactive environments
            }

            BufferedReader fallbackReader = (reader == null) ? new BufferedReader(new InputStreamReader(System.in)) : null;

            while (true) {
                String line;
                try {
                    if (reader != null) {
                        line = reader.readLine("pebble> ");
                    } else {
                        System.out.print("pebble> ");
                        System.out.flush();
                        line = fallbackReader.readLine();
                        if (line == null) break;
                    }
                } catch (Exception e) {
                    break;
                }

                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    System.out.println("Exiting PebbleLSM shell.");
                    break;
                }

                handleCommand(engine, options, line);
            }

        } catch (Exception e) {
            System.err.println("Fatal shell error: " + e.getMessage());
        }
    }

    private static void handleCommand(PebbleEngine engine, PebbleOptions options, String line) {
        String[] parts = line.split("\\s+", 4);
        String cmd = parts[0].toLowerCase();

        try {
            long startNs = System.nanoTime();

            switch (cmd) {
                case "put" -> {
                    if (parts.length < 3) {
                        System.out.println("Usage: put <key> <value> [ttlSeconds]");
                        return;
                    }
                    String key = parts[1];
                    String value = parts[2];
                    if (parts.length >= 4) {
                        long ttlSec = Long.parseLong(parts[3]);
                        engine.putString(key, value, Duration.ofSeconds(ttlSec));
                        System.out.printf("OK (TTL: %ds, took %.2f ms)\n", ttlSec, elapsedMs(startNs));
                    } else {
                        engine.putString(key, value);
                        System.out.printf("OK (took %.2f ms)\n", elapsedMs(startNs));
                    }
                }
                case "get" -> {
                    if (parts.length < 2) {
                        System.out.println("Usage: get <key>");
                        return;
                    }
                    String key = parts[1];
                    Optional<String> val = engine.getString(key);
                    if (val.isPresent()) {
                        System.out.println(val.get());
                    } else {
                        System.out.println("(nil)");
                    }
                }
                case "delete", "del" -> {
                    if (parts.length < 2) {
                        System.out.println("Usage: delete <key>");
                        return;
                    }
                    engine.delete(parts[1].getBytes());
                    System.out.printf("OK (deleted, took %.2f ms)\n", elapsedMs(startNs));
                }
                case "scan" -> {
                    String from = (parts.length >= 2) ? parts[1] : null;
                    String to = (parts.length >= 3) ? parts[2] : null;

                    List<StorageEntry> entries = new ArrayList<>();
                    try (var scanner = engine.scan(
                            from != null ? ByteSlice.of(from) : null,
                            to != null ? ByteSlice.of(to) : null)) {
                        while (scanner.hasNext()) {
                            entries.add(scanner.next());
                        }
                    }

                    renderScanTable(entries);
                    System.out.printf("%d records found (took %.2f ms)\n", entries.size(), elapsedMs(startNs));
                }
                case "stats" -> {
                    StorageVisualizer visualizer = new StorageVisualizer();
                    System.out.println(visualizer.render(engine.getMetrics(), options));
                }
                case "compact" -> {
                    System.out.println("Triggering manual Leveled Compaction...");
                    engine.compact();
                    System.out.printf("Compaction completed (took %.2f ms)\n", elapsedMs(startNs));
                }
                case "flush" -> {
                    System.out.println("Flushing active MemTable to Level 0 SSTable...");
                    engine.flush();
                    System.out.printf("Flush completed (took %.2f ms)\n", elapsedMs(startNs));
                }
                case "help" -> {
                    System.out.println("Available commands:");
                    System.out.println("  put <key> <value> [ttlSec]   - Store a key-value pair with optional TTL");
                    System.out.println("  get <key>                    - Retrieve value by key");
                    System.out.println("  delete <key>                 - Delete key (write tombstone)");
                    System.out.println("  scan [fromKey] [toKey]       - Range scan entries in tabular format");
                    System.out.println("  stats                        - Display live LSM metrics & level breakdown");
                    System.out.println("  compact                      - Trigger manual Leveled Compaction");
                    System.out.println("  flush                        - Flush in-memory active MemTable to disk");
                    System.out.println("  exit / quit                  - Exit shell");
                }
                default -> System.out.println("Unknown command: " + cmd + ". Type 'help' for command list.");
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private static void renderScanTable(List<StorageEntry> entries) {
        if (entries.isEmpty()) {
            System.out.println("(empty set)");
            return;
        }

        System.out.println("+----------------------+------------------------------------------------+");
        System.out.printf("| %-20s | %-46s |\n", "KEY", "VALUE");
        System.out.println("+----------------------+------------------------------------------------+");

        for (StorageEntry e : entries) {
            String keyStr = truncate(e.key().toUtf8String(), 20);
            String valStr = truncate(e.value().toUtf8String(), 46);
            System.out.printf("| %-20s | %-46s |\n", keyStr, valStr);
        }

        System.out.println("+----------------------+------------------------------------------------+");
    }

    private static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    private static double elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000.0;
    }
}
