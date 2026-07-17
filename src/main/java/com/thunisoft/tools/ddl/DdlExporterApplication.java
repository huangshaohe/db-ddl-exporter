package com.thunisoft.tools.ddl;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DdlExporterApplication {
    private static final int DEFAULT_PORT = 18888;
    private static final String INDEX_HTML_TEMPLATE = new String(loadResource("/index.html"), StandardCharsets.UTF_8);
    private static final DdlExportService EXPORT_SERVICE = new DdlExportService();
    private static final ConcurrentMap<String, ExportProgress> PROGRESS_JOBS =
            new ConcurrentHashMap<String, ExportProgress>();

    private DdlExporterApplication() {
    }

    public static void main(String[] args) {
        try {
            if (args.length > 0 && "export".equalsIgnoreCase(args[0])) {
                CommandLineApplication.runExport(args);
                return;
            }
            ServerOptions options = ServerOptions.parse(args);
            if (options.help) {
                CommandLineApplication.printGeneralHelp(System.out);
                return;
            }
            if (options.version) {
                System.out.println("DB DDL Exporter " + CommandLineApplication.version());
                return;
            }
            startServer(options.port, options.openBrowser);
        } catch (CommandLineApplication.ExportFailedException exception) {
            System.err.println("导出失败：" + exception.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException exception) {
            System.err.println("参数错误：" + exception.getMessage());
            System.err.println("使用 --help 或 export --help 查看帮助。");
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("启动失败：" + rootMessage(exception));
            System.exit(1);
        }
    }

    private static void startServer(int port, boolean shouldOpenBrowser) throws IOException {
        String csrfToken = randomToken();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        HttpServer server = HttpServer.create(address, 0);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/", new IndexHandler(csrfToken));
        server.createContext("/assets/", new AssetHandler());
        server.createContext("/health", new HealthHandler());
        server.createContext("/api/test-connection", new TestConnectionHandler(csrfToken));
        server.createContext("/api/csv-summary", new CsvSummaryHandler(csrfToken));
        server.createContext("/api/progress", new ProgressHandler(csrfToken));
        server.createContext("/api/export", new ExportHandler(csrfToken));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop(0);
                executor.shutdownNow();
            }
        }));
        server.start();

        String url = "http://127.0.0.1:" + port + "/";
        System.out.println("DB DDL Exporter 已启动：" + url);
        System.out.println("仅监听本机地址。关闭本窗口即可停止工具。");
        if (shouldOpenBrowser) {
            openBrowser(url);
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1024 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("端口必须是 1024-65535 之间的数字，例如：18888");
        }
    }

    private static void openBrowser(final String url) {
        Thread opener = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(500L);
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI.create(url));
                    }
                } catch (Exception ignored) {
                    // The URL is printed for environments where auto-open is not available.
                }
            }
        }, "browser-opener");
        opener.setDaemon(true);
        opener.start();
    }

    private static final class IndexHandler implements HttpHandler {
        private final byte[] page;

        private IndexHandler(String csrfToken) {
            this.page = INDEX_HTML_TEMPLATE.replace("${CSRF_TOKEN}", csrfToken).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
                sendText(exchange, 404, "text/plain; charset=UTF-8", "Not Found");
                return;
            }
            noCache(exchange.getResponseHeaders());
            sendBytes(exchange, 200, "text/html; charset=UTF-8", page);
        }
    }

    private static final class AssetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    || !path.matches("/assets/icons/[a-z0-9-]+\\.svg")) {
                sendText(exchange, 404, "text/plain; charset=UTF-8", "Not Found");
                return;
            }
            byte[] asset;
            try {
                asset = loadResource(path);
            } catch (IllegalStateException missing) {
                sendText(exchange, 404, "text/plain; charset=UTF-8", "Not Found");
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "public, max-age=86400, immutable");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Cross-Origin-Resource-Policy", "same-origin");
            sendBytes(exchange, 200, "image/svg+xml; charset=UTF-8", asset);
        }
    }

    private static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "text/plain; charset=UTF-8", "Method Not Allowed");
                return;
            }
            noCache(exchange.getResponseHeaders());
            sendText(exchange, 200, "text/plain; charset=UTF-8", "ok");
        }
    }

    private static final class TestConnectionHandler implements HttpHandler {
        private final String csrfToken;

        private TestConnectionHandler(String csrfToken) {
            this.csrfToken = csrfToken;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            noCache(exchange.getResponseHeaders());
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonError(exchange, 405, "只支持 POST 请求");
                return;
            }

            String password = "";
            try {
                MultipartForm form = MultipartForm.parse(exchange.getRequestBody(),
                        exchange.getRequestHeaders().getFirst("Content-Type"));
                if (!constantTimeEquals(csrfToken, form.text("csrfToken"))) {
                    throw new IllegalArgumentException("页面校验已失效，请刷新页面后重试");
                }
                password = form.rawText("password");
                try (JdbcDriverLoader loadedDriver = JdbcDriverLoader.fromUpload(
                        form.bytes("driverJar"), form.filename("driverJar"), form.text("driverClass"))) {
                    DdlExportService.ConnectionTestResult result = EXPORT_SERVICE.testConnection(
                            loadedDriver.driver(), form.text("jdbcUrl"), form.text("username"), password);
                    String json = new StringBuilder("{\"ok\":true,\"product\":\"")
                            .append(jsonEscape(result.product))
                            .append("\",\"version\":\"").append(jsonEscape(result.version))
                            .append("\",\"driver\":\"").append(jsonEscape(result.driver))
                            .append("\",\"elapsedMs\":").append(result.elapsedMs)
                            .append('}').toString();
                    sendText(exchange, 200, "application/json; charset=UTF-8", json);
                }
            } catch (IllegalArgumentException exception) {
                sendJsonError(exchange, 400, sanitize(exception.getMessage(), password));
            } catch (Throwable exception) {
                sendJsonError(exchange, 500, sanitize(rootMessage(exception), password));
            }
        }
    }

    private static final class ExportHandler implements HttpHandler {
        private final String csrfToken;

        private ExportHandler(String csrfToken) {
            this.csrfToken = csrfToken;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            noCache(exchange.getResponseHeaders());
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonError(exchange, 405, "只支持 POST 请求");
                return;
            }

            String password = "";
            ExportProgress progress = null;
            try {
                MultipartForm form = MultipartForm.parse(exchange.getRequestBody(),
                        exchange.getRequestHeaders().getFirst("Content-Type"));
                if (!constantTimeEquals(csrfToken, form.text("csrfToken"))) {
                    throw new IllegalArgumentException("页面校验已失效，请刷新页面后重试");
                }
                String jobId = form.text("jobId");
                if (!jobId.matches("[A-Za-z0-9-]{16,80}")) {
                    throw new IllegalArgumentException("导出任务编号不合法，请刷新页面后重试");
                }
                progress = progressFor(jobId);
                byte[] driverJar = form.bytes("driverJar");
                String driverClass = form.text("driverClass");
                String schemaName = form.text("schemaName");
                String exportMode = form.text("exportMode");
                List<String> objectNames = Collections.emptyList();
                if ("csv".equals(exportMode)) {
                    objectNames = UsageCsvParser.objectNames(form.bytes("usageCsv"), schemaName);
                }

                password = form.rawText("password");
                DdlExportService.ExportRequest request = new DdlExportService.ExportRequest(
                        form.text("jdbcUrl"), form.text("username"), password,
                        schemaName, form.text("tableName"), exportMode, objectNames);
                final ExportProgress jobProgress = progress;
                DdlExportService.ExportResult result;
                try (JdbcDriverLoader loadedDriver = JdbcDriverLoader.fromUpload(
                        driverJar, form.filename("driverJar"), driverClass)) {
                    result = EXPORT_SERVICE.export(loadedDriver.driver(), request,
                            new DdlExportService.ProgressListener() {
                                @Override
                                public void onStage(String stage) {
                                    jobProgress.stage(stage);
                                }

                                @Override
                                public void onStart(int total) {
                                    jobProgress.start(total);
                                }

                                @Override
                                public void onProgress(int current, int total, String objectName) {
                                    jobProgress.update(current, total, objectName);
                                }
                            });
                }
                progress.complete("DDL 文件已生成，正在下载");

                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Disposition", "attachment; filename=\"" + result.filename + "\"");
                sendBytes(exchange, 200, "application/sql; charset=UTF-8",
                        result.ddl.getBytes(StandardCharsets.UTF_8));
            } catch (IllegalArgumentException exception) {
                String message = sanitize(exception.getMessage(), password);
                failProgress(progress, message);
                sendJsonError(exchange, 400, message);
            } catch (Throwable exception) {
                String message = rootMessage(exception);
                message = sanitize(message, password);
                failProgress(progress, message);
                sendJsonError(exchange, 500, message);
            }
        }
    }

    private static final class CsvSummaryHandler implements HttpHandler {
        private final String csrfToken;

        private CsvSummaryHandler(String csrfToken) {
            this.csrfToken = csrfToken;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            noCache(exchange.getResponseHeaders());
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonError(exchange, 405, "只支持 POST 请求");
                return;
            }
            try {
                MultipartForm form = MultipartForm.parse(exchange.getRequestBody(),
                        exchange.getRequestHeaders().getFirst("Content-Type"));
                if (!constantTimeEquals(csrfToken, form.text("csrfToken"))) {
                    throw new IllegalArgumentException("页面校验已失效，请刷新页面后重试");
                }
                List<UsageCsvParser.SchemaCount> counts = UsageCsvParser.schemaCounts(form.bytes("usageCsv"));
                StringBuilder json = new StringBuilder("{\"schemas\":[");
                for (int i = 0; i < counts.size(); i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    UsageCsvParser.SchemaCount count = counts.get(i);
                    json.append("{\"name\":\"").append(jsonEscape(count.schema))
                            .append("\",\"count\":").append(count.count).append('}');
                }
                json.append("]}");
                sendText(exchange, 200, "application/json; charset=UTF-8", json.toString());
            } catch (IllegalArgumentException exception) {
                sendJsonError(exchange, 400, exception.getMessage());
            } catch (Throwable exception) {
                sendJsonError(exchange, 500, rootMessage(exception));
            }
        }
    }

    private static final class ProgressHandler implements HttpHandler {
        private final String csrfToken;

        private ProgressHandler(String csrfToken) {
            this.csrfToken = csrfToken;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            noCache(exchange.getResponseHeaders());
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonError(exchange, 405, "只支持 GET 请求");
                return;
            }
            String token = queryParameter(exchange.getRequestURI(), "csrfToken");
            String jobId = queryParameter(exchange.getRequestURI(), "jobId");
            if (!constantTimeEquals(csrfToken, token)
                    || jobId == null || !jobId.matches("[A-Za-z0-9-]{16,80}")) {
                sendJsonError(exchange, 403, "进度任务校验失败");
                return;
            }

            ExportProgress progress = progressFor(jobId);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=UTF-8");
            headers.set("Connection", "keep-alive");
            headers.set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);

            boolean completed = false;
            try (OutputStream output = exchange.getResponseBody()) {
                long version = -1L;
                while (true) {
                    ProgressSnapshot snapshot = progress.await(version, 15000L);
                    version = snapshot.version;
                    String event = "event: progress\ndata: " + snapshot.toJson() + "\n\n";
                    output.write(event.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    if (snapshot.done) {
                        completed = true;
                        break;
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                if (completed) {
                    PROGRESS_JOBS.remove(jobId, progress);
                }
            }
        }
    }

    private static ExportProgress progressFor(String jobId) {
        long now = System.currentTimeMillis();
        if (PROGRESS_JOBS.size() > 100) {
            for (java.util.Map.Entry<String, ExportProgress> entry : PROGRESS_JOBS.entrySet()) {
                if (entry.getValue().isExpired(now)) {
                    PROGRESS_JOBS.remove(entry.getKey(), entry.getValue());
                }
            }
        }
        ExportProgress existing = PROGRESS_JOBS.get(jobId);
        if (existing != null) {
            return existing;
        }
        ExportProgress created = new ExportProgress();
        ExportProgress raced = PROGRESS_JOBS.putIfAbsent(jobId, created);
        return raced == null ? created : raced;
    }

    private static void failProgress(ExportProgress progress, String message) {
        if (progress != null) {
            progress.fail(message);
        }
    }

    private static String queryParameter(URI uri, String expectedName) {
        String query = uri.getRawQuery();
        if (query == null) {
            return "";
        }
        String[] values = query.split("&");
        for (String value : values) {
            int equals = value.indexOf('=');
            String rawName = equals >= 0 ? value.substring(0, equals) : value;
            if (expectedName.equals(urlDecode(rawName))) {
                return urlDecode(equals >= 0 ? value.substring(equals + 1) : "");
            }
        }
        return "";
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName() : message;
    }

    private static String sanitize(String message, String password) {
        String value = message == null ? "未知错误" : message;
        if (password != null && !password.isEmpty()) {
            value = value.replace(password, "******");
        }
        return value.replace("\r", " ").replace("\n", " ");
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            token.append(String.format("%02x", value & 0xff));
        }
        return token.toString();
    }

    private static void sendJsonError(HttpExchange exchange, int status, String message) throws IOException {
        String json = "{\"error\":\"" + jsonEscape(message) + "\"}";
        sendText(exchange, status, "application/json; charset=UTF-8", json);
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }

    private static void noCache(Headers headers) {
        headers.set("Cache-Control", "no-store, max-age=0");
        headers.set("Pragma", "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'");
    }

    private static void sendText(HttpExchange exchange, int status, String contentType, String value)
            throws IOException {
        sendBytes(exchange, status, contentType, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] value)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, value.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(value);
        }
    }

    private static final class ExportProgress {
        private int current;
        private int total;
        private String message = "等待导出任务启动";
        private boolean done;
        private boolean error;
        private long version;
        private long updatedAt = System.currentTimeMillis();

        synchronized void stage(String stage) {
            this.message = stage;
            changed();
        }

        synchronized void start(int total) {
            this.total = Math.max(0, total);
            this.current = 0;
            this.message = total > 0 ? "已发现 " + total + " 个待处理对象" : "正在读取对象";
            changed();
        }

        synchronized void update(int current, int total, String objectName) {
            this.current = Math.max(0, current);
            this.total = Math.max(0, total);
            this.message = objectName == null || objectName.isEmpty() ? "正在导出" : objectName;
            changed();
        }

        synchronized void complete(String message) {
            if (total > 0) {
                current = total;
            }
            this.message = message;
            this.done = true;
            this.error = false;
            changed();
        }

        synchronized void fail(String message) {
            this.message = message;
            this.done = true;
            this.error = true;
            changed();
        }

        synchronized ProgressSnapshot await(long previousVersion, long timeoutMillis) throws InterruptedException {
            if (version == previousVersion && !done) {
                wait(timeoutMillis);
            }
            return new ProgressSnapshot(current, total, message, done, error, version);
        }

        synchronized boolean isExpired(long now) {
            return now - updatedAt > 30L * 60L * 1000L;
        }

        private void changed() {
            version++;
            updatedAt = System.currentTimeMillis();
            notifyAll();
        }
    }

    private static final class ProgressSnapshot {
        final int current;
        final int total;
        final String message;
        final boolean done;
        final boolean error;
        final long version;

        private ProgressSnapshot(int current, int total, String message, boolean done, boolean error, long version) {
            this.current = current;
            this.total = total;
            this.message = message;
            this.done = done;
            this.error = error;
            this.version = version;
        }

        private String toJson() {
            return new StringBuilder("{\"current\":").append(current)
                    .append(",\"total\":").append(total)
                    .append(",\"message\":\"").append(jsonEscape(message == null ? "" : message))
                    .append("\",\"done\":").append(done)
                    .append(",\"error\":").append(error)
                    .append('}').toString();
        }
    }

    private static byte[] loadResource(String path) {
        try (InputStream input = DdlExporterApplication.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("找不到页面资源：" + path);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("读取页面资源失败", exception);
        }
    }

    static final class ServerOptions {
        final int port;
        final boolean openBrowser;
        final boolean help;
        final boolean version;

        private ServerOptions(int port, boolean openBrowser, boolean help, boolean version) {
            this.port = port;
            this.openBrowser = openBrowser;
            this.help = help;
            this.version = version;
        }

        static ServerOptions parse(String[] arguments) {
            int port = DEFAULT_PORT;
            boolean openBrowser = true;
            boolean help = false;
            boolean version = false;
            int index = arguments.length > 0 && "serve".equalsIgnoreCase(arguments[0]) ? 1 : 0;
            if (index == 0 && arguments.length == 1 && arguments[0].matches("[0-9]+")) {
                return new ServerOptions(parsePort(arguments[0]), true, false, false);
            }
            while (index < arguments.length) {
                String argument = arguments[index++];
                if ("--port".equals(argument) || "-p".equals(argument)) {
                    if (index >= arguments.length) {
                        throw new IllegalArgumentException("--port 后必须提供端口号");
                    }
                    port = parsePort(arguments[index++]);
                } else if ("--no-browser".equals(argument)) {
                    openBrowser = false;
                } else if ("--help".equals(argument) || "-h".equals(argument)) {
                    help = true;
                } else if ("--version".equals(argument) || "-V".equals(argument)) {
                    version = true;
                } else {
                    throw new IllegalArgumentException("未知参数：" + argument + "；使用 --help 查看帮助");
                }
            }
            return new ServerOptions(port, openBrowser, help, version);
        }
    }
}
