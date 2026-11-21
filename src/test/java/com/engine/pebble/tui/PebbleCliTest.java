package com.engine.pebble.tui;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

public class PebbleCliTest {

    @Test
    void shouldPrintVersionWithFlags() {
        for (String flag : new String[]{"--version", "-v", "-V"}) {
            StringWriter sw = new StringWriter();
            CommandLine cmd = new CommandLine(new PebbleCli());
            cmd.setOut(new PrintWriter(sw));
            int exitCode = cmd.execute(flag);
            assertThat(exitCode).isEqualTo(0);
            assertThat(sw.toString()).contains("PebbleLSM 1.0.0");
        }
    }

    @Test
    void shouldPrintVersionWithSubcommand() {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new PebbleCli());
        cmd.setOut(new PrintWriter(sw));
        int exitCode = cmd.execute("version");
        assertThat(exitCode).isEqualTo(0);
        assertThat(sw.toString()).contains("PebbleLSM 1.0.0");
    }
}
