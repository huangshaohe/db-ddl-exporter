package com.thunisoft.tools.ddl;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.Locale;
import java.util.jar.JarFile;

/**
 * Loads a JDBC driver from an isolated JAR and restores the thread context class loader on close.
 */
final class JdbcDriverLoader implements AutoCloseable {
    static final long MAX_DRIVER_BYTES = 50L * 1024L * 1024L;

    private final Path jarPath;
    private final boolean deleteOnClose;
    private final URLClassLoader classLoader;
    private final ClassLoader originalContextClassLoader;
    private final Driver driver;

    private JdbcDriverLoader(Path jarPath, boolean deleteOnClose, URLClassLoader classLoader,
                             ClassLoader originalContextClassLoader, Driver driver) {
        this.jarPath = jarPath;
        this.deleteOnClose = deleteOnClose;
        this.classLoader = classLoader;
        this.originalContextClassLoader = originalContextClassLoader;
        this.driver = driver;
    }

    static JdbcDriverLoader fromUpload(byte[] content, String filename, String driverClass) throws IOException {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("请选择 JDBC 驱动 JAR 文件");
        }
        if (content.length > MAX_DRIVER_BYTES) {
            throw new IllegalArgumentException("JDBC 驱动 JAR 不能超过 50 MB");
        }
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("驱动文件必须是 .jar");
        }

        Path temporaryJar = Files.createTempFile("ddl-exporter-driver-", ".jar");
        boolean loaded = false;
        try {
            Files.write(temporaryJar, content);
            JdbcDriverLoader result = fromPath(temporaryJar, driverClass, true);
            loaded = true;
            return result;
        } finally {
            if (!loaded) {
                try {
                    Files.deleteIfExists(temporaryJar);
                } catch (IOException ignored) {
                    temporaryJar.toFile().deleteOnExit();
                }
            }
        }
    }

    static JdbcDriverLoader fromPath(Path path, String driverClass) throws IOException {
        return fromPath(path, driverClass, false);
    }

    private static JdbcDriverLoader fromPath(Path path, String driverClass, boolean deleteOnClose)
            throws IOException {
        validateDriverClass(driverClass);
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("找不到 JDBC 驱动 JAR：" + path);
        }
        long size = Files.size(path);
        if (size == 0L) {
            throw new IllegalArgumentException("JDBC 驱动 JAR 是空文件");
        }
        if (size > MAX_DRIVER_BYTES) {
            throw new IllegalArgumentException("JDBC 驱动 JAR 不能超过 50 MB");
        }

        String classEntry = driverClass.replace('.', '/') + ".class";
        try (JarFile jar = new JarFile(path.toFile())) {
            if (jar.getJarEntry(classEntry) == null) {
                throw new IllegalArgumentException("驱动 JAR 中找不到指定驱动类：" + driverClass);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件不是有效的 JAR，或 JAR 已损坏：" + path.getFileName(),
                    exception);
        }

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        DriverClassLoader loader = new DriverClassLoader(
                new URL[]{path.toUri().toURL()}, JdbcDriverLoader.class.getClassLoader(), driverClass);
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class<?> loadedClass = Class.forName(driverClass, true, loader);
            Object instance = loadedClass.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Driver)) {
                throw new IllegalArgumentException(driverClass + " 没有实现 java.sql.Driver");
            }
            return new JdbcDriverLoader(path, deleteOnClose, loader, original, (Driver) instance);
        } catch (IllegalArgumentException exception) {
            closeFailedLoad(loader, original);
            throw exception;
        } catch (ReflectiveOperationException exception) {
            closeFailedLoad(loader, original);
            throw new IllegalArgumentException("无法实例化 JDBC 驱动类 " + driverClass + "："
                    + rootMessage(exception), exception);
        } catch (LinkageError error) {
            closeFailedLoad(loader, original);
            throw new IllegalArgumentException("JDBC 驱动与当前 Java 版本不兼容：" + rootMessage(error), error);
        }
    }

    Driver driver() {
        return driver;
    }

    @Override
    public void close() {
        Thread.currentThread().setContextClassLoader(originalContextClassLoader);
        try {
            classLoader.close();
        } catch (IOException ignored) {
            // The driver has already finished its work.
        }
        if (deleteOnClose) {
            try {
                Files.deleteIfExists(jarPath);
            } catch (IOException ignored) {
                jarPath.toFile().deleteOnExit();
            }
        }
    }

    private static void validateDriverClass(String driverClass) {
        if (driverClass == null || driverClass.trim().isEmpty()) {
            throw new IllegalArgumentException("请填写 JDBC 驱动类");
        }
        if (!driverClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            throw new IllegalArgumentException("JDBC 驱动类名称不合法");
        }
    }

    private static void closeFailedLoad(URLClassLoader loader, ClassLoader original) {
        Thread.currentThread().setContextClassLoader(original);
        try {
            loader.close();
        } catch (IOException ignored) {
            // Nothing useful can be done here.
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    /** Loads the requested driver class child-first to avoid a classpath collision with the host application. */
    private static final class DriverClassLoader extends URLClassLoader {
        private final String driverClass;

        private DriverClassLoader(URL[] urls, ClassLoader parent, String driverClass) {
            super(urls, parent);
            this.driverClass = driverClass;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!driverClass.equals(name)) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = findClass(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }
}
