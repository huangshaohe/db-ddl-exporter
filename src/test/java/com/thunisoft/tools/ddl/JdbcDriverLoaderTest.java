package com.thunisoft.tools.ddl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDriverLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsDriverFromSelectedJarWithAnIsolatedClassLoader() throws Exception {
        Path jar = jarContaining(FakeDriver.class);
        ClassLoader original = Thread.currentThread().getContextClassLoader();

        try (JdbcDriverLoader loader = JdbcDriverLoader.fromPath(jar, FakeDriver.class.getName())) {
            assertTrue(loader.driver() instanceof Driver);
            assertNotSame(FakeDriver.class.getClassLoader(), loader.driver().getClass().getClassLoader());
            assertNotSame(original, Thread.currentThread().getContextClassLoader());
        }

        assertTrue(Files.exists(jar));
        assertTrue(original == Thread.currentThread().getContextClassLoader());
    }

    @Test
    void rejectsInvalidJarAndMissingDriverClass() throws Exception {
        Path invalid = temporaryDirectory.resolve("invalid.jar");
        Files.write(invalid, new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class,
                () -> JdbcDriverLoader.fromPath(invalid, FakeDriver.class.getName()));

        Path valid = jarContaining(FakeDriver.class);
        assertThrows(IllegalArgumentException.class,
                () -> JdbcDriverLoader.fromPath(valid, "example.MissingDriver"));
    }

    private Path jarContaining(Class<?> type) throws IOException {
        String entryName = type.getName().replace('.', '/') + ".class";
        Path jar = temporaryDirectory.resolve("driver-" + System.nanoTime() + ".jar");
        try (InputStream input = type.getClassLoader().getResourceAsStream(entryName);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            if (input == null) {
                throw new IOException("Missing test class bytes: " + entryName);
            }
            output.putNextEntry(new JarEntry(entryName));
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            output.closeEntry();
        }
        return jar;
    }

    public static final class FakeDriver implements Driver {
        public FakeDriver() {
        }

        @Override
        public Connection connect(String url, Properties info) {
            return null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
