package com.thunisoft.tools.ddl;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Command-line entry point for repeatable and CI-friendly exports. */
final class CommandLineApplication {
    private CommandLineApplication() {
    }

    static void runExport(String[] arguments) throws ExportFailedException {
        ExportOptions options = ExportOptions.parse(arguments);
        if (options.help) {
            printExportHelp(System.out);
            return;
        }

        String password = "";
        try {
            password = readPassword(options.passwordEnvironment);
            List<String> objectNames = Collections.emptyList();
            if (options.csv != null) {
                objectNames = UsageCsvParser.objectNames(options.csv, options.schema);
            }
            String mode = options.object != null ? "single" : options.csv != null ? "csv" : "schema";
            DdlExportService.ExportRequest request = new DdlExportService.ExportRequest(
                    options.jdbcUrl, options.username, password, options.schema, options.object, mode, objectNames);

            DdlExportService.ExportResult result;
            try (JdbcDriverLoader loadedDriver = JdbcDriverLoader.fromPath(options.driverJar, options.driverClass)) {
                result = new DdlExportService().export(loadedDriver.driver(), request, consoleProgress(System.err));
            }

            Path output = resolveOutput(options.output, result.filename);
            writeAtomically(output, result.ddl.getBytes(StandardCharsets.UTF_8), options.overwrite);
            System.out.println("DDL 导出完成：" + output);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ExportFailedException(sanitize(rootMessage(exception), password));
        }
    }

    static String version() {
        Package applicationPackage = DdlExporterApplication.class.getPackage();
        String version = applicationPackage == null ? null : applicationPackage.getImplementationVersion();
        return version == null || version.trim().isEmpty() ? "development" : version;
    }

    static void printGeneralHelp(PrintStream output) {
        output.println("DB DDL Exporter " + version());
        output.println();
        output.println("用法：");
        output.println("  java -jar db-ddl-exporter.jar [serve] [--port 18888] [--no-browser]");
        output.println("  java -jar db-ddl-exporter.jar export [选项]");
        output.println();
        output.println("命令：");
        output.println("  serve   启动仅监听 127.0.0.1 的本地网页（默认）");
        output.println("  export  不启动网页，直接从命令行导出 DDL");
        output.println();
        output.println("通用选项：");
        output.println("  -h, --help       显示帮助");
        output.println("  -V, --version    显示版本");
        output.println();
        output.println("执行 export --help 查看自动化导出的完整参数。");
    }

    private static void printExportHelp(PrintStream output) {
        output.println("命令行导出用法：");
        output.println("  java -jar db-ddl-exporter.jar export \\");
        output.println("    --driver kingbase8.jar --url jdbc:kingbase8://host:54321/database \\");
        output.println("    --user username --password-env DB_PASSWORD --schema public \\");
        output.println("    (--object table_name | --csv usage.csv | --all) [--output export.sql]");
        output.println();
        output.println("必需选项：");
        output.println("  --driver <path>        JDBC 驱动 JAR 路径");
        output.println("  --url <jdbc-url>       JDBC URL");
        output.println("  --user <username>      数据库用户名");
        output.println("  --schema <schema>      模式名");
        output.println("  --object/--csv/--all   三种导出范围必须且只能选择一种");
        output.println();
        output.println("可选项：");
        output.println("  --driver-class <name>  JDBC 驱动类；默认 com.kingbase8.Driver（兼容旧用法）");
        output.println("  --password-env <name>  从环境变量读取密码；未指定时在终端安全询问");
        output.println("  --output <path>        输出文件或已有目录；默认使用自动生成的文件名");
        output.println("  --overwrite            允许覆盖已有输出文件");
        output.println("  -h, --help             显示本帮助");
    }

    private static String readPassword(String environmentName) {
        if (environmentName != null) {
            String password = System.getenv(environmentName);
            if (password == null) {
                throw new IllegalArgumentException("环境变量未设置：" + environmentName);
            }
            return password;
        }
        Console console = System.console();
        if (console == null) {
            return "";
        }
        char[] characters = console.readPassword("数据库密码（可留空）：");
        if (characters == null) {
            return "";
        }
        try {
            return new String(characters);
        } finally {
            Arrays.fill(characters, '\0');
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

    private static String sanitize(String message, String password) {
        String value = message == null ? "未知错误" : message;
        if (password != null && !password.isEmpty()) {
            value = value.replace(password, "******");
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static DdlExportService.ProgressListener consoleProgress(final PrintStream output) {
        return new DdlExportService.ProgressListener() {
            @Override
            public void onStage(String stage) {
                output.println("[阶段] " + stage);
            }

            @Override
            public void onStart(int total) {
                output.println("[对象] 共 " + total + " 个");
            }

            @Override
            public void onProgress(int current, int total, String objectName) {
                output.println("[进度] " + current + "/" + total + " " + objectName);
            }
        };
    }

    private static Path resolveOutput(Path configured, String generatedFilename) {
        Path output = configured == null ? Paths.get(generatedFilename) : configured;
        if (Files.isDirectory(output)) {
            output = output.resolve(generatedFilename);
        }
        return output.toAbsolutePath().normalize();
    }

    static void writeAtomically(Path output, byte[] content, boolean overwrite) throws IOException {
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("输出目录不存在：" + parent);
        }
        if (!overwrite && Files.exists(output)) {
            throw new IllegalArgumentException("输出文件已存在；如需覆盖请添加 --overwrite：" + output);
        }

        Path temporary = Files.createTempFile(parent, ".ddl-export-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, content);
            if (overwrite) {
                try {
                    move(temporary, output, true, true);
                } catch (AtomicMoveNotSupportedException exception) {
                    move(temporary, output, true, false);
                }
            } else {
                // A regular move has well-defined FileAlreadyExists behavior; ATOMIC_MOVE does not.
                move(temporary, output, false, false);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void move(Path source, Path target, boolean overwrite, boolean atomic) throws IOException {
        if (overwrite && atomic) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else if (overwrite) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else if (atomic) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } else {
            Files.move(source, target);
        }
    }

    static final class ExportOptions {
        final Path driverJar;
        final String driverClass;
        final String jdbcUrl;
        final String username;
        final String schema;
        final String object;
        final Path csv;
        final boolean all;
        final Path output;
        final String passwordEnvironment;
        final boolean overwrite;
        final boolean help;

        private ExportOptions(Path driverJar, String driverClass, String jdbcUrl, String username,
                              String schema, String object, Path csv, boolean all, Path output,
                              String passwordEnvironment, boolean overwrite, boolean help) {
            this.driverJar = driverJar;
            this.driverClass = driverClass;
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.schema = schema;
            this.object = object;
            this.csv = csv;
            this.all = all;
            this.output = output;
            this.passwordEnvironment = passwordEnvironment;
            this.overwrite = overwrite;
            this.help = help;
        }

        static ExportOptions parse(String[] arguments) {
            Path driverJar = null;
            String driverClass = "com.kingbase8.Driver";
            String jdbcUrl = null;
            String username = null;
            String schema = null;
            String object = null;
            Path csv = null;
            boolean all = false;
            Path output = null;
            String passwordEnvironment = null;
            boolean overwrite = false;
            boolean help = false;

            int index = arguments.length > 0 && "export".equalsIgnoreCase(arguments[0]) ? 1 : 0;
            while (index < arguments.length) {
                String argument = arguments[index++];
                if ("--driver".equals(argument)) {
                    driverJar = Paths.get(nextValue(arguments, index++, argument));
                } else if ("--driver-class".equals(argument)) {
                    driverClass = nextValue(arguments, index++, argument);
                } else if ("--url".equals(argument)) {
                    jdbcUrl = nextValue(arguments, index++, argument);
                } else if ("--user".equals(argument)) {
                    username = nextValue(arguments, index++, argument);
                } else if ("--schema".equals(argument)) {
                    schema = nextValue(arguments, index++, argument);
                } else if ("--object".equals(argument)) {
                    object = nextValue(arguments, index++, argument);
                } else if ("--csv".equals(argument)) {
                    csv = Paths.get(nextValue(arguments, index++, argument));
                } else if ("--all".equals(argument)) {
                    all = true;
                } else if ("--output".equals(argument)) {
                    output = Paths.get(nextValue(arguments, index++, argument));
                } else if ("--password-env".equals(argument)) {
                    passwordEnvironment = nextValue(arguments, index++, argument);
                } else if ("--overwrite".equals(argument)) {
                    overwrite = true;
                } else if ("--help".equals(argument) || "-h".equals(argument)) {
                    help = true;
                } else {
                    throw new IllegalArgumentException("未知 export 参数：" + argument + "；使用 export --help 查看帮助");
                }
            }

            ExportOptions parsed = new ExportOptions(driverJar, driverClass, jdbcUrl, username, schema,
                    object, csv, all, output, passwordEnvironment, overwrite, help);
            if (!help) {
                parsed.validate();
            }
            return parsed;
        }

        private void validate() {
            require(driverJar == null ? null : driverJar.toString(), "--driver");
            require(driverClass, "--driver-class");
            require(jdbcUrl, "--url");
            require(username, "--user");
            require(schema, "--schema");
            int selections = (object == null ? 0 : 1) + (csv == null ? 0 : 1) + (all ? 1 : 0);
            if (selections != 1) {
                throw new IllegalArgumentException("必须且只能选择 --object、--csv 或 --all 其中一种");
            }
            if (!Files.isRegularFile(driverJar)) {
                throw new IllegalArgumentException("找不到 JDBC 驱动 JAR：" + driverJar);
            }
            if (csv != null && !Files.isRegularFile(csv)) {
                throw new IllegalArgumentException("找不到 CSV 文件：" + csv);
            }
        }

        private static void require(String value, String option) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("缺少必需参数 " + option);
            }
        }

        private static String nextValue(String[] arguments, int index, String option) {
            if (index >= arguments.length) {
                throw new IllegalArgumentException(option + " 后必须提供值");
            }
            String value = arguments[index];
            if (value.trim().isEmpty()) {
                throw new IllegalArgumentException(option + " 的值不能为空");
            }
            return value;
        }
    }

    static final class ExportFailedException extends Exception {
        private ExportFailedException(String message) {
            super(message);
        }
    }
}
