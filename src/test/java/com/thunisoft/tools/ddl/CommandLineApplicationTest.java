package com.thunisoft.tools.ddl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLineApplicationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesSchemaExportOptions() throws Exception {
        Path driver = temporaryDirectory.resolve("driver.jar");
        Files.write(driver, new byte[]{1});
        CommandLineApplication.ExportOptions options = CommandLineApplication.ExportOptions.parse(new String[]{
                "export", "--driver", driver.toString(), "--url", "jdbc:kingbase8://localhost/demo",
                "--user", "demo", "--schema", "public", "--all", "--output", "out.sql", "--overwrite"
        });

        assertEquals(driver.toString(), options.driverJar.toString());
        assertEquals("com.kingbase8.Driver", options.driverClass);
        assertEquals("public", options.schema);
        assertTrue(options.all);
        assertTrue(options.overwrite);
        assertFalse(options.help);
    }

    @Test
    void requiresExactlyOneExportSelection() {
        String[] base = {"export", "--driver", temporaryDirectory.resolve("missing.jar").toString(), "--url", "jdbc:test",
                "--user", "demo", "--schema", "public"};
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineApplication.ExportOptions.parse(base));

        String[] conflicting = {"export", "--driver", temporaryDirectory.resolve("missing.jar").toString(), "--url", "jdbc:test",
                "--user", "demo", "--schema", "public", "--all", "--object", "orders"};
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineApplication.ExportOptions.parse(conflicting));
    }

    @Test
    void parsesServerOptionsAndLegacyPort() {
        DdlExporterApplication.ServerOptions options = DdlExporterApplication.ServerOptions.parse(
                new String[]{"serve", "--port", "19999", "--no-browser"});
        assertEquals(19999, options.port);
        assertFalse(options.openBrowser);

        assertEquals(18889, DdlExporterApplication.ServerOptions.parse(new String[]{"18889"}).port);
        assertThrows(IllegalArgumentException.class,
                () -> DdlExporterApplication.ServerOptions.parse(new String[]{"--port", "80"}));
    }

    @Test
    void protectsExistingOutputUnlessOverwriteIsExplicit() throws Exception {
        Path output = temporaryDirectory.resolve("export.sql");
        Files.write(output, "original".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class,
                () -> CommandLineApplication.writeAtomically(
                        output, "replacement".getBytes(StandardCharsets.UTF_8), false));
        assertEquals("original", new String(Files.readAllBytes(output), StandardCharsets.UTF_8));

        CommandLineApplication.writeAtomically(
                output, "replacement".getBytes(StandardCharsets.UTF_8), true);
        assertEquals("replacement", new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
    }
}
