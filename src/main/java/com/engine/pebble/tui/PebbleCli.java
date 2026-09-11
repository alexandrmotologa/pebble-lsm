package com.engine.pebble.tui;

import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleOptions;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Command-line interface and interactive diagnostics for PebbleLSM.
 */
@Command(
        name = "pebble",
        mixinStandardHelpOptions = true,
        version = "PebbleLSM 1.0.0",
        description = "Embedded LSM-Tree Key-Value Storage Engine CLI"
)
public class PebbleCli implements Callable<Integer> {

    @Option(names = {"-p", "--path"}, description = "Database storage directory path", defaultValue = "./pebble-data")
    private Path dbPath;

    @Override
    public Integer call() {
        System.out.println("Use --help to inspect available subcommands (tui, put, get, delete, scan, bench).");
        return 0;
    }

    @Command(name = "tui", description = "Launch live storage hierarchy diagnostics HUD")
    public int tui(
            @Option(names = {"--simulate"}, description = "Simulate active background write traffic", defaultValue = "false")
            boolean simulate
    ) throws Exception {
        PebbleOptions options = PebbleOptions.builder()
                .dbPath(dbPath)
                .memTableThresholdBytes(32 * 1024) // 32KB for interactive demonstration
                .l0CompactionThreshold(3)
                .build();

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            StorageVisualizer visualizer = new StorageVisualizer();

            if (simulate) {
                System.out.println("Starting background write traffic simulation...");
                Thread.ofVirtual().start(() -> {
                    long counter = 0;
                    while (true) {
                        try {
                            engine.putString(String.format("user:%06d", counter), "simulated-payload-" + counter);
                            if (counter % 500 == 0) {
                                engine.delete(String.format("user:%06d", counter - 100).getBytes());
                            }
                            counter++;
                            Thread.sleep(2);
                        } catch (Exception ignored) {
                            break;
                        }
                    }
                });
            }

            for (int i = 0; i < 30; i++) { // Run 30 update cycles
                System.out.print("\033[H\033[2J");
                System.out.flush();
                System.out.println(visualizer.render(engine.getMetrics(), options));
                Thread.sleep(500);
            }
        }
        return 0;
    }

    @Command(name = "put", description = "Store a key-value pair")
    public int put(
            @Parameters(index = "0", description = "Key") String key,
            @Parameters(index = "1", description = "Value") String value
    ) throws Exception {
        PebbleOptions options = PebbleOptions.builder().dbPath(dbPath).build();
        try (PebbleEngine engine = PebbleEngine.open(options)) {
            engine.putString(key, value);
            System.out.println("OK: stored key '" + key + "'");
        }
        return 0;
    }

    @Command(name = "get", description = "Retrieve a value by key")
    public int get(
            @Parameters(index = "0", description = "Key") String key
    ) throws Exception {
        PebbleOptions options = PebbleOptions.builder().dbPath(dbPath).build();
        try (PebbleEngine engine = PebbleEngine.open(options)) {
            Optional<String> val = engine.getString(key);
            if (val.isPresent()) {
                System.out.println("Found: " + val.get());
            } else {
                System.out.println("Key not found.");
            }
        }
        return 0;
    }

    @Command(name = "delete", description = "Delete a key (write tombstone)")
    public int delete(
            @Parameters(index = "0", description = "Key") String key
    ) throws Exception {
        PebbleOptions options = PebbleOptions.builder().dbPath(dbPath).build();
        try (PebbleEngine engine = PebbleEngine.open(options)) {
            engine.delete(key.getBytes());
            System.out.println("OK: deleted key '" + key + "'");
        }
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PebbleCli()).execute(args);
        System.exit(exitCode);
    }
}
