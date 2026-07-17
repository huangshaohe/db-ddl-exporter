package com.thunisoft.tools.ddl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

final class UsageCsvParser {
    private static final int MAX_OBJECTS = 10000;
    private static final int MAX_CSV_BYTES = 10 * 1024 * 1024;

    private UsageCsvParser() {
    }

    static List<String> objectNames(Path path, String requestedSchema) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("找不到 CSV 文件：" + path);
        }
        if (Files.size(path) > MAX_CSV_BYTES) {
            throw new IllegalArgumentException("CSV 文件不能超过 10 MB");
        }
        return objectNames(Files.readAllBytes(path), requestedSchema);
    }

    static List<String> objectNames(byte[] content, String requestedSchema) {
        ParsedUsage usage = parseUsage(content);

        Set<String> names = new LinkedHashSet<String>();
        for (int rowIndex = 1; rowIndex < usage.records.size(); rowIndex++) {
            List<String> row = usage.records.get(rowIndex);
            if (usage.schemaColumn >= row.size() || usage.objectColumn >= row.size()) {
                continue;
            }
            String schema = row.get(usage.schemaColumn).trim();
            String object = row.get(usage.objectColumn).trim();
            if (schema.equalsIgnoreCase(requestedSchema) && !object.isEmpty()) {
                names.add(object);
                if (names.size() > MAX_OBJECTS) {
                    throw new IllegalArgumentException("CSV 中目标对象超过 " + MAX_OBJECTS + " 个，请拆分后导出");
                }
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("CSV 中没有模式 " + requestedSchema + " 的对象记录");
        }
        return new ArrayList<String>(names);
    }

    static List<SchemaCount> schemaCounts(byte[] content) {
        ParsedUsage usage = parseUsage(content);
        Map<String, Set<String>> grouped = new LinkedHashMap<String, Set<String>>();
        for (int rowIndex = 1; rowIndex < usage.records.size(); rowIndex++) {
            List<String> row = usage.records.get(rowIndex);
            if (usage.schemaColumn >= row.size() || usage.objectColumn >= row.size()) {
                continue;
            }
            String schema = row.get(usage.schemaColumn).trim();
            String object = row.get(usage.objectColumn).trim();
            if (schema.isEmpty() || object.isEmpty()) {
                continue;
            }
            Set<String> objects = grouped.get(schema);
            if (objects == null) {
                objects = new LinkedHashSet<String>();
                grouped.put(schema, objects);
            }
            objects.add(object);
        }
        List<SchemaCount> counts = new ArrayList<SchemaCount>();
        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            counts.add(new SchemaCount(entry.getKey(), entry.getValue().size()));
        }
        if (counts.isEmpty()) {
            throw new IllegalArgumentException("CSV 中没有有效的模式和对象记录");
        }
        return counts;
    }

    private static ParsedUsage parseUsage(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("请选择 db-schema-usage.csv 文件");
        }
        if (content.length > MAX_CSV_BYTES) {
            throw new IllegalArgumentException("CSV 文件不能超过 10 MB");
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
            text = text.substring(1);
        }
        List<List<String>> records = parseCsv(text);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("CSV 文件为空");
        }
        int schemaColumn = columnIndex(records.get(0), "schema");
        int objectColumn = columnIndex(records.get(0), "object");
        if (schemaColumn < 0 || objectColumn < 0) {
            throw new IllegalArgumentException("CSV 必须包含 schema 和 object 两列");
        }
        return new ParsedUsage(records, schemaColumn, objectColumn);
    }

    private static int columnIndex(List<String> header, String expected) {
        for (int i = 0; i < header.size(); i++) {
            if (expected.equals(header.get(i).trim().toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        return -1;
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quoted) {
                if (value == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(value);
                }
            } else if (value == '"' && field.length() == 0) {
                quoted = true;
            } else if (value == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (value == '\n' || value == '\r') {
                if (value == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                if (!isBlankRow(row)) {
                    rows.add(row);
                }
                row = new ArrayList<String>();
            } else {
                field.append(value);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV 存在未闭合的双引号");
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (!isBlankRow(row)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static boolean isBlankRow(List<String> row) {
        for (String value : row) {
            if (!value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static final class SchemaCount {
        final String schema;
        final int count;

        private SchemaCount(String schema, int count) {
            this.schema = schema;
            this.count = count;
        }
    }

    private static final class ParsedUsage {
        final List<List<String>> records;
        final int schemaColumn;
        final int objectColumn;

        private ParsedUsage(List<List<String>> records, int schemaColumn, int objectColumn) {
            this.records = records;
            this.schemaColumn = schemaColumn;
            this.objectColumn = objectColumn;
        }
    }
}
