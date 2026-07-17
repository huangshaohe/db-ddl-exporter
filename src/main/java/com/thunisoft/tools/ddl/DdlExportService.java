package com.thunisoft.tools.ddl;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class DdlExportService {
    ConnectionTestResult testConnection(Driver driver, String jdbcUrl, String username, String password)
            throws Exception {
        require(jdbcUrl, "JDBC URL");
        require(username, "用户名");
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalArgumentException("JDBC URL 必须以 jdbc: 开头");
        }
        if (jdbcUrl.length() > 2048) {
            throw new IllegalArgumentException("JDBC URL 过长");
        }

        Properties properties = connectionProperties(username, password);
        long startedAt = System.nanoTime();
        Connection connection = driver.connect(jdbcUrl, properties);
        if (connection == null) {
            throw new IllegalArgumentException("所选驱动不接受这个 JDBC URL，请检查数据库类型、驱动和连接地址");
        }
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            long elapsedMs = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
            return new ConnectionTestResult(
                    valueOrUnknown(metadata.getDatabaseProductName()),
                    valueOrUnknown(metadata.getDatabaseProductVersion()),
                    valueOrUnknown(metadata.getDriverName()) + " " + valueOrUnknown(metadata.getDriverVersion()),
                    elapsedMs);
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The connection test itself has already completed.
            }
        }
    }

    ExportResult export(Driver driver, ExportRequest request) throws Exception {
        return export(driver, request, ProgressListener.NONE);
    }

    ExportResult export(Driver driver, ExportRequest request, ProgressListener progress) throws Exception {
        validate(request);
        progress.onStage("正在连接数据库");
        Properties properties = connectionProperties(request.username, request.password);

        Connection connection = driver.connect(request.jdbcUrl, properties);
        if (connection == null) {
            throw new IllegalArgumentException("所选驱动不接受这个 JDBC URL，请检查驱动类型和连接地址");
        }

        try {
            try {
                connection.setReadOnly(true);
            } catch (SQLException ignored) {
                // Some Kingbase deployments do not allow this JDBC hint. All SQL below is still read-only.
            }
            progress.onStage("数据库连接成功，正在读取对象信息");
            setSearchPath(connection, request.schemaName);
            String databaseDescription = databaseDescription(connection);
            boolean supportsNativeKingbaseDdl = isKingbase(connection);

            if (request.isSingle()) {
                ResolvedTable table = resolveTable(connection, request.schemaName, request.tableName);
                progress.onStart(1);
                progress.onProgress(0, 1, "正在处理：" + table.tableName);
                ExportResult result = exportSingle(connection, table, databaseDescription,
                        supportsNativeKingbaseDdl);
                progress.onProgress(1, 1, "已完成：" + table.tableName);
                return result;
            }
            ObjectSelection selection = selectBatchObjects(connection, request);
            return exportBatch(connection, request, selection, databaseDescription,
                    supportsNativeKingbaseDdl, progress);
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nothing useful can be done here.
            }
        }
    }

    private Properties connectionProperties(String username, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", username == null ? "" : username);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("connectTimeout", "10");
        return properties;
    }

    private void validate(ExportRequest request) {
        require(request.jdbcUrl, "JDBC URL");
        require(request.username, "用户名");
        require(request.schemaName, "模式名");
        if (request.isSingle()) {
            require(request.tableName, "对象名");
        }
        if (!request.jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalArgumentException("JDBC URL 必须以 jdbc: 开头");
        }
        if (request.jdbcUrl.length() > 2048) {
            throw new IllegalArgumentException("JDBC URL 过长");
        }
        validateObjectName(request.schemaName, "模式名");
        if (request.isSingle()) {
            validateObjectName(request.tableName, "对象名");
        }
        if (!request.isSingle() && !request.isCsv() && !request.isSchema()) {
            throw new IllegalArgumentException("不支持的导出模式");
        }
    }

    private ExportResult exportSingle(Connection connection, ResolvedTable table, String databaseDescription,
                                      boolean supportsNativeKingbaseDdl) throws SQLException {
        if (supportsNativeKingbaseDdl) {
            try {
                String nativeDdl = nativeKingbaseDdl(connection, table);
                return new ExportResult(fileName(table), nativeDocument(nativeDdl, table, databaseDescription));
            } catch (SQLException nativeFailure) {
                String fallback = fallbackDdl(connection, table, databaseDescription,
                        "Kingbase 原生接口不可用：" + compactError(nativeFailure));
                return new ExportResult(fileName(table), fallback);
            }
        }
        return new ExportResult(fileName(table), fallbackDdl(connection, table, databaseDescription,
                "当前数据库使用标准 JDBC 元数据导出"));
    }

    private void require(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("请填写" + label);
        }
    }

    private void validateObjectName(String value, String label) {
        if (value.length() > 128 || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + "不合法");
        }
    }

    private ResolvedTable resolveTable(Connection connection, String requestedSchema, String requestedTable)
            throws SQLException {
        ResolvedTable relaxed = null;
        for (ResolvedTable candidate : listSchemaObjects(connection, requestedSchema)) {
            if (candidate.tableName.equals(requestedTable)) {
                return candidate;
            }
            if (candidate.tableName.equalsIgnoreCase(requestedTable)) {
                if (relaxed != null && !relaxed.tableName.equals(candidate.tableName)) {
                    throw new IllegalArgumentException(
                            "忽略大小写后匹配到多个对象，请按数据库中的大小写准确填写模式名和对象名");
                }
                relaxed = candidate;
            }
        }
        if (relaxed != null) {
            return relaxed;
        }
        throw new IllegalArgumentException("找不到表或视图 " + requestedSchema + "." + requestedTable
                + "，请检查数据库、模式名、对象名以及账号权限");
    }

    private ObjectSelection selectBatchObjects(Connection connection, ExportRequest request) throws SQLException {
        List<ResolvedTable> available = listSchemaObjects(connection, request.schemaName);
        if (available.isEmpty()) {
            throw new IllegalArgumentException("模式 " + request.schemaName + " 下没有当前账号可见的表或视图");
        }
        if (request.isSchema()) {
            sortObjects(available);
            return new ObjectSelection(available, Collections.<String>emptyList(), available.size());
        }

        Map<String, ResolvedTable> exact = new LinkedHashMap<String, ResolvedTable>();
        Map<String, ResolvedTable> relaxed = new LinkedHashMap<String, ResolvedTable>();
        Set<String> ambiguous = new LinkedHashSet<String>();
        for (ResolvedTable object : available) {
            exact.put(object.tableName, object);
            String key = object.tableName.toLowerCase(java.util.Locale.ROOT);
            if (relaxed.containsKey(key)) {
                ambiguous.add(key);
            } else {
                relaxed.put(key, object);
            }
        }

        Map<String, ResolvedTable> selected = new LinkedHashMap<String, ResolvedTable>();
        List<String> missing = new ArrayList<String>();
        for (String requestedName : request.objectNames) {
            ResolvedTable object = exact.get(requestedName);
            if (object == null) {
                String key = requestedName.toLowerCase(java.util.Locale.ROOT);
                if (!ambiguous.contains(key)) {
                    object = relaxed.get(key);
                }
            }
            if (object == null) {
                missing.add(requestedName);
            } else {
                selected.put(object.tableName, object);
            }
        }
        List<ResolvedTable> objects = new ArrayList<ResolvedTable>(selected.values());
        sortObjects(objects);
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("CSV 中模式 " + request.schemaName
                    + " 的对象在数据库中都不存在或当前账号均不可见");
        }
        return new ObjectSelection(objects, missing, request.objectNames.size());
    }

    private List<ResolvedTable> listSchemaObjects(Connection connection, String schema) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, ResolvedTable> objects = new LinkedHashMap<String, ResolvedTable>();
        String currentCatalog = connection.getCatalog();
        String[] types = new String[]{"TABLE", "VIEW"};
        Set<String> namespaceCandidates = new LinkedHashSet<String>();
        namespaceCandidates.add(schema);
        namespaceCandidates.add(schema.toUpperCase(Locale.ROOT));
        namespaceCandidates.add(schema.toLowerCase(Locale.ROOT));

        for (String namespace : namespaceCandidates) {
            collectMetadataObjects(metadata, objects, currentCatalog, namespace, schema, types);
            collectMetadataObjects(metadata, objects, namespace, null, schema, types);
            collectMetadataObjects(metadata, objects, null, namespace, schema, types);
        }
        List<ResolvedTable> result = new ArrayList<ResolvedTable>(objects.values());
        sortObjects(result);
        return result;
    }

    private void collectMetadataObjects(DatabaseMetaData metadata, Map<String, ResolvedTable> target,
                                        String catalog, String schemaPattern, String requestedNamespace,
                                        String[] types) {
        try (ResultSet result = metadata.getTables(catalog, schemaPattern, "%", types)) {
            while (result.next()) {
                String tableCatalog = result.getString("TABLE_CAT");
                String tableSchema = result.getString("TABLE_SCHEM");
                String displayNamespace = hasText(tableSchema) ? tableSchema
                        : hasText(tableCatalog) ? tableCatalog : requestedNamespace;
                if (!displayNamespace.equalsIgnoreCase(requestedNamespace)) {
                    continue;
                }
                ResolvedTable object = validateObjectType(new ResolvedTable(
                        tableCatalog, tableSchema, displayNamespace,
                        result.getString("TABLE_NAME"), result.getString("TABLE_TYPE"),
                        identifierQuote(metadata)));
                target.put(displayNamespace.toLowerCase(Locale.ROOT) + "\u0000"
                        + object.tableName.toLowerCase(Locale.ROOT), object);
            }
        } catch (SQLException ignored) {
            // JDBC drivers disagree about whether a database namespace is a catalog or schema.
            // The caller intentionally tries the common combinations above.
        }
    }

    private void sortObjects(List<ResolvedTable> objects) {
        Collections.sort(objects, new Comparator<ResolvedTable>() {
            @Override
            public int compare(ResolvedTable left, ResolvedTable right) {
                if (left.isView() != right.isView()) {
                    return left.isView() ? 1 : -1;
                }
                return left.tableName.compareToIgnoreCase(right.tableName);
            }
        });
    }

    private ExportResult exportBatch(Connection connection, ExportRequest request, ObjectSelection selection,
                                     String databaseDescription, boolean supportsNativeKingbaseDdl,
                                     ProgressListener progress) throws SQLException {
        List<String> documents = new ArrayList<String>();
        List<String> failures = new ArrayList<String>();
        for (String missing : selection.missing) {
            failures.add(missing + "：数据库中未找到或当前账号不可见");
        }

        int nativeCount = 0;
        int fallbackCount = 0;
        int completed = selection.missing.size();
        progress.onStart(selection.requestedCount);
        progress.onProgress(completed, selection.requestedCount,
                completed > 0 ? "已跳过不存在的对象" : "准备导出");
        for (ResolvedTable object : selection.objects) {
            progress.onStage("正在处理：" + object.tableName);
            if (supportsNativeKingbaseDdl) {
                try {
                    String nativeDdl = nativeKingbaseDdl(connection, object);
                    documents.add(nativeDocument(nativeDdl, object, databaseDescription));
                    nativeCount++;
                } catch (SQLException nativeFailure) {
                    try {
                        documents.add(fallbackDdl(connection, object, databaseDescription,
                                "Kingbase 原生接口不可用：" + compactError(nativeFailure)));
                        fallbackCount++;
                    } catch (Exception fallbackFailure) {
                        failures.add(object.tableName + "：" + safeExceptionMessage(fallbackFailure));
                    }
                }
            } else {
                try {
                    documents.add(fallbackDdl(connection, object, databaseDescription,
                            "当前数据库使用标准 JDBC 元数据导出"));
                    fallbackCount++;
                } catch (Exception fallbackFailure) {
                    failures.add(object.tableName + "：" + safeExceptionMessage(fallbackFailure));
                }
            }
            completed++;
            progress.onProgress(completed, selection.requestedCount, "已完成：" + object.tableName);
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("没有成功导出任何对象；首个错误："
                    + (failures.isEmpty() ? "未知" : failures.get(0)));
        }

        StringBuilder output = new StringBuilder()
                .append("-- DB DDL Exporter 批量导出\n")
                .append("-- 模式：").append(request.schemaName).append("\n")
                .append("-- 请求对象数：").append(selection.requestedCount).append("\n")
                .append("-- 成功对象数：").append(documents.size()).append("（原生 ")
                .append(nativeCount).append("，回退 ").append(fallbackCount).append("）\n")
                .append("-- 未找到或失败数：").append(failures.size()).append("\n")
                .append("-- 导出时间：")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n");
        if (!failures.isEmpty()) {
            output.append("--\n-- 未找到/失败清单：\n");
            for (String failure : failures) {
                output.append("--   ").append(safeSqlComment(failure)).append("\n");
            }
        }
        output.append("\n");
        for (String document : documents) {
            output.append("-- ============================================================================\n")
                    .append(document).append('\n');
        }

        progress.onStage("DDL 已生成，正在合并 SQL 文件");
        String filename = safeFilePart(request.schemaName) + "_batch_ddl.sql";
        return new ExportResult(filename, output.toString());
    }

    private String safeExceptionMessage(Exception exception) {
        String message = exception.getMessage();
        return safeSqlComment(message == null ? exception.getClass().getSimpleName() : message);
    }

    private ResolvedTable querySingleTable(Connection connection, String sql, String schema, String table,
                                            boolean rejectMultiple) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                ResolvedTable found = null;
                while (result.next()) {
                    if (found != null && rejectMultiple) {
                        throw new IllegalArgumentException("忽略大小写后匹配到多个对象，请按数据库中的大小写准确填写模式名和对象名");
                    }
                    found = new ResolvedTable(result.getString(1), result.getString(2), result.getString(3));
                }
                return found;
            }
        }
    }

    private ResolvedTable validateObjectType(ResolvedTable table) {
        String type = table.tableType == null ? "" : table.tableType.toUpperCase();
        if (!type.contains("TABLE") && !type.contains("VIEW")) {
            throw new IllegalArgumentException(table.schemaName + "." + table.tableName
                    + " 的对象类型暂不支持：" + table.tableType);
        }
        return table;
    }

    private void setSearchPath(Connection connection, String schema) {
        String sql = "SET search_path TO " + quoteIdentifier(schema);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ignored) {
            // The three-argument GET_DDL call below can still work on versions supporting cross-schema export.
        }
    }

    private String nativeKingbaseDdl(Connection connection, ResolvedTable table) throws SQLException {
        String objectType = table.isView() ? "VIEW" : "TABLE";
        SQLException firstFailure;
        try {
            String ddl = queryDdl(connection,
                    "SELECT dbms_metadata.get_ddl(?, ?)", objectType, table.tableName, null);
            if (hasText(ddl)) {
                return ddl;
            }
            firstFailure = new SQLException("DBMS_METADATA.GET_DDL 返回空内容");
        } catch (SQLException failure) {
            firstFailure = failure;
        }

        try {
            String ddl = queryDdl(connection,
                    "SELECT dbms_metadata.get_ddl(?, ?, ?)", objectType, table.tableName, table.schemaName);
            if (hasText(ddl)) {
                return ddl;
            }
            throw new SQLException("DBMS_METADATA.GET_DDL 返回空内容");
        } catch (SQLException secondFailure) {
            secondFailure.addSuppressed(firstFailure);
            throw secondFailure;
        }
    }

    private String queryDdl(Connection connection, String sql, String objectType, String table, String schema)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, objectType);
            statement.setString(2, table);
            if (schema != null) {
                statement.setString(3, schema);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private String nativeDocument(String ddl, ResolvedTable table, String databaseDescription) {
        StringBuilder output = documentHeader(table, databaseDescription, "Kingbase DBMS_METADATA.GET_DDL（原生）");
        output.append(ensureSemicolon(ddl.trim())).append('\n');
        return output.toString();
    }

    private String fallbackDdl(Connection connection, ResolvedTable table, String databaseDescription,
                               String fallbackReason) throws SQLException {
        if (table.isView()) {
            return fallbackViewDdl(connection, table, databaseDescription, fallbackReason);
        }
        DatabaseMetaData metadata = connection.getMetaData();
        List<Column> columns = readColumns(metadata, table);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("已找到表，但当前账号无法读取列元数据："
                    + table.schemaName + "." + table.tableName);
        }

        PrimaryKey primaryKey = readPrimaryKey(metadata, table);
        List<ForeignKey> foreignKeys = readForeignKeys(metadata, table);
        List<Index> indexes = readIndexes(metadata, table, primaryKey);
        String tableComment = readTableComment(metadata, table);

        StringBuilder output = documentHeader(table, databaseDescription, "JDBC 基础元数据回退");
        output.append("-- 导出路径：标准 JDBC 元数据。\n")
                .append("-- 此结果适合补齐普通表/字段/主外键/普通索引；分区、表达式索引、触发器、存储参数等需人工核对。\n")
                .append("-- 说明：").append(safeSqlComment(fallbackReason)).append("\n\n");

        output.append("CREATE TABLE ").append(qualified(table)).append(" (\n");
        List<String> definitions = new ArrayList<String>();
        for (Column column : columns) {
            StringBuilder definition = new StringBuilder("    ")
                    .append(quoteIdentifier(table, column.name)).append(' ').append(column.typeDeclaration);
            if (hasText(column.defaultValue)) {
                definition.append(" DEFAULT ").append(column.defaultValue);
            }
            if (!column.nullable) {
                definition.append(" NOT NULL");
            }
            definitions.add(definition.toString());
        }
        if (primaryKey != null && !primaryKey.columns.isEmpty()) {
            definitions.add("    " + namedConstraint(table, primaryKey.name) + "PRIMARY KEY ("
                    + joinQuoted(table, primaryKey.columns) + ")");
        }
        for (ForeignKey foreignKey : foreignKeys) {
            StringBuilder definition = new StringBuilder("    ")
                    .append(namedConstraint(table, foreignKey.name)).append("FOREIGN KEY (")
                    .append(joinQuoted(table, foreignKey.localColumns)).append(") REFERENCES ")
                    .append(quoteIdentifier(table, foreignKey.referenceSchema)).append('.')
                    .append(quoteIdentifier(table, foreignKey.referenceTable)).append(" (")
                    .append(joinQuoted(table, foreignKey.referenceColumns)).append(')');
            appendRule(definition, " ON UPDATE ", foreignKey.updateRule);
            appendRule(definition, " ON DELETE ", foreignKey.deleteRule);
            definitions.add(definition.toString());
        }
        output.append(join(definitions, ",\n")).append("\n);\n\n");

        for (Index index : indexes) {
            output.append("CREATE ");
            if (index.unique) {
                output.append("UNIQUE ");
            }
            output.append("INDEX ").append(quoteIdentifier(table, index.name)).append(" ON ")
                    .append(qualified(table)).append(" (");
            List<String> indexColumns = new ArrayList<String>();
            for (IndexColumn column : index.columns) {
                String value = quoteIdentifier(table, column.name);
                if ("A".equalsIgnoreCase(column.direction)) {
                    value += " ASC";
                } else if ("D".equalsIgnoreCase(column.direction)) {
                    value += " DESC";
                }
                indexColumns.add(value);
            }
            output.append(join(indexColumns, ", ")).append(')');
            if (hasText(index.filterCondition)) {
                output.append(" WHERE ").append(index.filterCondition);
            }
            output.append(";\n");
        }
        if (!indexes.isEmpty()) {
            output.append('\n');
        }

        if (hasText(tableComment)) {
            output.append("COMMENT ON TABLE ").append(qualified(table)).append(" IS ")
                    .append(quoteLiteral(tableComment)).append(";\n");
        }
        for (Column column : columns) {
            if (hasText(column.comment)) {
                output.append("COMMENT ON COLUMN ").append(qualified(table)).append('.')
                        .append(quoteIdentifier(table, column.name)).append(" IS ")
                        .append(quoteLiteral(column.comment)).append(";\n");
            }
        }
        return output.toString();
    }

    private String fallbackViewDdl(Connection connection, ResolvedTable view, String databaseDescription,
                                   String fallbackReason) throws SQLException {
        String definition = null;
        String sql = "SELECT view_definition FROM information_schema.views "
                + "WHERE table_schema = ? AND table_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, view.schemaName);
            statement.setString(2, view.tableName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    definition = result.getString(1);
                }
            }
        }
        if (!hasText(definition)) {
            throw new IllegalArgumentException("已找到视图，但当前账号无法读取视图定义："
                    + view.schemaName + "." + view.tableName);
        }

        StringBuilder output = documentHeader(view, databaseDescription, "information_schema 视图元数据回退");
        output.append("-- 导出路径：information_schema 视图元数据。\n")
                .append("-- 请在测试库核对依赖对象、对象所有者、权限和注释。\n")
                .append("-- 说明：").append(safeSqlComment(fallbackReason)).append("\n\n")
                .append("CREATE OR REPLACE VIEW ").append(qualified(view)).append(" AS\n")
                .append(ensureSemicolon(definition.trim())).append('\n');
        return output.toString();
    }

    private List<Column> readColumns(DatabaseMetaData metadata, ResolvedTable table) throws SQLException {
        List<Column> columns = new ArrayList<Column>();
        try (ResultSet result = metadata.getColumns(
                table.catalogName, table.metadataSchemaName, table.tableName, null)) {
            while (result.next()) {
                String typeName = result.getString("TYPE_NAME");
                int jdbcType = result.getInt("DATA_TYPE");
                int size = result.getInt("COLUMN_SIZE");
                int scale = result.getInt("DECIMAL_DIGITS");
                columns.add(new Column(
                        result.getString("COLUMN_NAME"),
                        typeDeclaration(typeName, jdbcType, size, scale),
                        result.getString("COLUMN_DEF"),
                        result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        result.getString("REMARKS"),
                        result.getInt("ORDINAL_POSITION")));
            }
        }
        Collections.sort(columns, new Comparator<Column>() {
            @Override
            public int compare(Column left, Column right) {
                return Integer.compare(left.position, right.position);
            }
        });
        return columns;
    }

    private PrimaryKey readPrimaryKey(DatabaseMetaData metadata, ResolvedTable table) throws SQLException {
        List<OrderedName> columns = new ArrayList<OrderedName>();
        String name = null;
        try (ResultSet result = metadata.getPrimaryKeys(
                table.catalogName, table.metadataSchemaName, table.tableName)) {
            while (result.next()) {
                name = result.getString("PK_NAME");
                columns.add(new OrderedName(result.getString("COLUMN_NAME"), result.getShort("KEY_SEQ")));
            }
        }
        if (columns.isEmpty()) {
            return null;
        }
        sortOrderedNames(columns);
        return new PrimaryKey(name, names(columns));
    }

    private List<ForeignKey> readForeignKeys(DatabaseMetaData metadata, ResolvedTable table) throws SQLException {
        Map<String, ForeignKey> foreignKeys = new LinkedHashMap<String, ForeignKey>();
        int unnamed = 0;
        try (ResultSet result = metadata.getImportedKeys(
                table.catalogName, table.metadataSchemaName, table.tableName)) {
            while (result.next()) {
                String name = result.getString("FK_NAME");
                String key = name;
                if (!hasText(key)) {
                    key = "#unnamed-" + (++unnamed);
                }
                ForeignKey foreignKey = foreignKeys.get(key);
                if (foreignKey == null) {
                    String referenceSchema = result.getString("PKTABLE_SCHEM");
                    if (!hasText(referenceSchema)) {
                        referenceSchema = table.schemaName;
                    }
                    foreignKey = new ForeignKey(name,
                            referenceSchema, result.getString("PKTABLE_NAME"),
                            result.getShort("UPDATE_RULE"), result.getShort("DELETE_RULE"));
                    foreignKeys.put(key, foreignKey);
                }
                short sequence = result.getShort("KEY_SEQ");
                foreignKey.local.add(new OrderedName(result.getString("FKCOLUMN_NAME"), sequence));
                foreignKey.reference.add(new OrderedName(result.getString("PKCOLUMN_NAME"), sequence));
            }
        }
        for (ForeignKey foreignKey : foreignKeys.values()) {
            sortOrderedNames(foreignKey.local);
            sortOrderedNames(foreignKey.reference);
            foreignKey.localColumns = names(foreignKey.local);
            foreignKey.referenceColumns = names(foreignKey.reference);
        }
        return new ArrayList<ForeignKey>(foreignKeys.values());
    }

    private List<Index> readIndexes(DatabaseMetaData metadata, ResolvedTable table, PrimaryKey primaryKey) {
        Map<String, Index> indexes = new LinkedHashMap<String, Index>();
        Set<String> primaryNames = new LinkedHashSet<String>();
        if (primaryKey != null && hasText(primaryKey.name)) {
            primaryNames.add(primaryKey.name);
        }
        try (ResultSet result = metadata.getIndexInfo(
                table.catalogName, table.metadataSchemaName, table.tableName, false, false)) {
            while (result.next()) {
                if (result.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String name = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (!hasText(name) || !hasText(column) || primaryNames.contains(name)) {
                    continue;
                }
                Index index = indexes.get(name);
                if (index == null) {
                    index = new Index(name, !result.getBoolean("NON_UNIQUE"), result.getString("FILTER_CONDITION"));
                    indexes.put(name, index);
                }
                index.columns.add(new IndexColumn(column, result.getString("ASC_OR_DESC"),
                        result.getShort("ORDINAL_POSITION")));
            }
        } catch (SQLException ignored) {
            return Collections.emptyList();
        }
        for (Index index : indexes.values()) {
            Collections.sort(index.columns, new Comparator<IndexColumn>() {
                @Override
                public int compare(IndexColumn left, IndexColumn right) {
                    return Short.compare(left.position, right.position);
                }
            });
        }
        return new ArrayList<Index>(indexes.values());
    }

    private String readTableComment(DatabaseMetaData metadata, ResolvedTable table) {
        try (ResultSet result = metadata.getTables(
                table.catalogName, table.metadataSchemaName, table.tableName, null)) {
            return result.next() ? result.getString("REMARKS") : null;
        } catch (SQLException ignored) {
            return null;
        }
    }

    private String typeDeclaration(String typeName, int jdbcType, int size, int scale) {
        String base = hasText(typeName) ? typeName : jdbcTypeName(jdbcType);
        switch (jdbcType) {
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.BINARY:
            case Types.VARBINARY:
                return size > 0 && size < Integer.MAX_VALUE ? base + "(" + size + ")" : base;
            case Types.NUMERIC:
            case Types.DECIMAL:
                if (size > 0 && scale > 0) {
                    return base + "(" + size + "," + scale + ")";
                }
                return size > 0 ? base + "(" + size + ")" : base;
            case Types.TIME:
            case Types.TIMESTAMP:
            case Types.TIME_WITH_TIMEZONE:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return scale > 0 ? base + "(" + scale + ")" : base;
            default:
                return base;
        }
    }

    private String jdbcTypeName(int jdbcType) {
        try {
            return java.sql.JDBCType.valueOf(jdbcType).getName();
        } catch (IllegalArgumentException ignored) {
            return "OTHER";
        }
    }

    private void appendRule(StringBuilder builder, String prefix, short rule) {
        switch (rule) {
            case DatabaseMetaData.importedKeyCascade:
                builder.append(prefix).append("CASCADE");
                break;
            case DatabaseMetaData.importedKeySetNull:
                builder.append(prefix).append("SET NULL");
                break;
            case DatabaseMetaData.importedKeySetDefault:
                builder.append(prefix).append("SET DEFAULT");
                break;
            case DatabaseMetaData.importedKeyRestrict:
                builder.append(prefix).append("RESTRICT");
                break;
            case DatabaseMetaData.importedKeyNoAction:
                builder.append(prefix).append("NO ACTION");
                break;
            default:
                break;
        }
    }

    private String databaseDescription(Connection connection) {
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            return metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion();
        } catch (SQLException ignored) {
            return "未知";
        }
    }

    private boolean isKingbase(Connection connection) {
        try {
            String name = connection.getMetaData().getDatabaseProductName();
            return name != null && name.toLowerCase(Locale.ROOT).contains("kingbase");
        } catch (SQLException ignored) {
            return false;
        }
    }

    private String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "未知" : value.trim();
    }

    private StringBuilder documentHeader(ResolvedTable table, String databaseDescription, String method) {
        return new StringBuilder()
                .append("-- DDL Exporter 生成文件\n")
                .append("-- 对象：").append(table.schemaName).append('.').append(table.tableName).append("\n")
                .append("-- 数据库：").append(safeSqlComment(databaseDescription)).append("\n")
                .append("-- 导出方式：").append(method).append("\n")
                .append("-- 导出时间：")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n\n");
    }

    private String fileName(ResolvedTable table) {
        return safeFilePart(table.schemaName) + "_" + safeFilePart(table.tableName) + "_ddl.sql";
    }

    private String safeFilePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String compactError(SQLException exception) {
        SQLException current = exception;
        StringBuilder value = new StringBuilder();
        while (current != null && value.length() < 800) {
            if (value.length() > 0) {
                value.append(" | ");
            }
            value.append(current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage());
            current = current.getNextException();
        }
        return value.toString();
    }

    private String qualified(ResolvedTable table) {
        return quoteIdentifier(table, table.schemaName) + "." + quoteIdentifier(table, table.tableName);
    }

    private String quoteIdentifier(String value) {
        return quoteIdentifier("\"", value);
    }

    private String quoteIdentifier(ResolvedTable table, String value) {
        return quoteIdentifier(table.identifierQuote, value);
    }

    private String quoteIdentifier(String quote, String value) {
        String open = hasText(quote) ? quote.trim() : "\"";
        String close = "[".equals(open) ? "]" : open;
        return open + value.replace(close, close + close) + close;
    }

    private String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String namedConstraint(ResolvedTable table, String name) {
        return hasText(name) ? "CONSTRAINT " + quoteIdentifier(table, name) + " " : "";
    }

    private String joinQuoted(ResolvedTable table, List<String> values) {
        List<String> quoted = new ArrayList<String>();
        for (String value : values) {
            quoted.add(quoteIdentifier(table, value));
        }
        return join(quoted, ", ");
    }

    private String identifierQuote(DatabaseMetaData metadata) {
        try {
            String quote = metadata.getIdentifierQuoteString();
            return hasText(quote) ? quote.trim() : "\"";
        } catch (SQLException ignored) {
            return "\"";
        }
    }

    private String ensureSemicolon(String value) {
        return value.endsWith(";") ? value : value + ";";
    }

    private String safeSqlComment(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ").replace("--", "- -");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String join(List<String> values, String delimiter) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                result.append(delimiter);
            }
            result.append(values.get(i));
        }
        return result.toString();
    }

    private void sortOrderedNames(List<OrderedName> values) {
        Collections.sort(values, new Comparator<OrderedName>() {
            @Override
            public int compare(OrderedName left, OrderedName right) {
                return Short.compare(left.position, right.position);
            }
        });
    }

    private List<String> names(List<OrderedName> values) {
        List<String> names = new ArrayList<String>();
        for (OrderedName value : values) {
            names.add(value.name);
        }
        return names;
    }

    static final class ExportRequest {
        final String jdbcUrl;
        final String username;
        final String password;
        final String schemaName;
        final String tableName;
        final String exportMode;
        final List<String> objectNames;

        ExportRequest(String jdbcUrl, String username, String password, String schemaName, String tableName,
                      String exportMode, List<String> objectNames) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.exportMode = exportMode == null || exportMode.isEmpty() ? "single" : exportMode;
            this.objectNames = objectNames == null ? Collections.<String>emptyList() : objectNames;
        }

        private boolean isSingle() {
            return "single".equals(exportMode);
        }

        private boolean isCsv() {
            return "csv".equals(exportMode);
        }

        private boolean isSchema() {
            return "schema".equals(exportMode);
        }
    }

    static final class ExportResult {
        final String filename;
        final String ddl;

        ExportResult(String filename, String ddl) {
            this.filename = filename;
            this.ddl = ddl;
        }
    }

    static final class ConnectionTestResult {
        final String product;
        final String version;
        final String driver;
        final long elapsedMs;

        ConnectionTestResult(String product, String version, String driver, long elapsedMs) {
            this.product = product;
            this.version = version;
            this.driver = driver;
            this.elapsedMs = elapsedMs;
        }
    }

    interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
            @Override
            public void onStage(String stage) {
            }

            @Override
            public void onStart(int total) {
            }

            @Override
            public void onProgress(int current, int total, String objectName) {
            }
        };

        void onStage(String stage);

        void onStart(int total);

        void onProgress(int current, int total, String objectName);
    }

    private static final class ObjectSelection {
        final List<ResolvedTable> objects;
        final List<String> missing;
        final int requestedCount;

        private ObjectSelection(List<ResolvedTable> objects, List<String> missing, int requestedCount) {
            this.objects = objects;
            this.missing = missing;
            this.requestedCount = requestedCount;
        }
    }

    private static final class ResolvedTable {
        final String catalogName;
        final String metadataSchemaName;
        final String schemaName;
        final String tableName;
        final String tableType;
        final String identifierQuote;

        private ResolvedTable(String schemaName, String tableName, String tableType) {
            this(null, schemaName, schemaName, tableName, tableType, "\"");
        }

        private ResolvedTable(String catalogName, String metadataSchemaName, String schemaName,
                              String tableName, String tableType, String identifierQuote) {
            this.catalogName = catalogName;
            this.metadataSchemaName = metadataSchemaName;
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.tableType = tableType;
            this.identifierQuote = identifierQuote;
        }

        private boolean isView() {
            return tableType != null && tableType.toUpperCase().contains("VIEW");
        }
    }

    private static final class Column {
        final String name;
        final String typeDeclaration;
        final String defaultValue;
        final boolean nullable;
        final String comment;
        final int position;

        private Column(String name, String typeDeclaration, String defaultValue, boolean nullable,
                       String comment, int position) {
            this.name = name;
            this.typeDeclaration = typeDeclaration;
            this.defaultValue = defaultValue;
            this.nullable = nullable;
            this.comment = comment;
            this.position = position;
        }
    }

    private static final class OrderedName {
        final String name;
        final short position;

        private OrderedName(String name, short position) {
            this.name = name;
            this.position = position;
        }
    }

    private static final class PrimaryKey {
        final String name;
        final List<String> columns;

        private PrimaryKey(String name, List<String> columns) {
            this.name = name;
            this.columns = columns;
        }
    }

    private static final class ForeignKey {
        final String name;
        final String referenceSchema;
        final String referenceTable;
        final short updateRule;
        final short deleteRule;
        final List<OrderedName> local = new ArrayList<OrderedName>();
        final List<OrderedName> reference = new ArrayList<OrderedName>();
        List<String> localColumns;
        List<String> referenceColumns;

        private ForeignKey(String name, String referenceSchema, String referenceTable,
                           short updateRule, short deleteRule) {
            this.name = name;
            this.referenceSchema = referenceSchema;
            this.referenceTable = referenceTable;
            this.updateRule = updateRule;
            this.deleteRule = deleteRule;
        }
    }

    private static final class Index {
        final String name;
        final boolean unique;
        final String filterCondition;
        final List<IndexColumn> columns = new ArrayList<IndexColumn>();

        private Index(String name, boolean unique, String filterCondition) {
            this.name = name;
            this.unique = unique;
            this.filterCondition = filterCondition;
        }
    }

    private static final class IndexColumn {
        final String name;
        final String direction;
        final short position;

        private IndexColumn(String name, String direction, short position) {
            this.name = name;
            this.direction = direction;
            this.position = position;
        }
    }
}
