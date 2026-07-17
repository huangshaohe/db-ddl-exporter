package com.thunisoft.tools.ddl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class MultipartForm {
    private static final int MAX_REQUEST_BYTES = 50 * 1024 * 1024;

    private final Map<String, Part> parts;

    private MultipartForm(Map<String, Part> parts) {
        this.parts = Collections.unmodifiableMap(parts);
    }

    static MultipartForm parse(InputStream input, String contentType) throws IOException {
        String boundary = boundaryFrom(contentType);
        byte[] body = readLimited(input, MAX_REQUEST_BYTES);
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        Map<String, Part> result = new LinkedHashMap<String, Part>();

        int cursor = indexOf(body, marker, 0);
        if (cursor < 0) {
            throw new IllegalArgumentException("无法识别上传表单边界");
        }
        cursor += marker.length;

        while (cursor < body.length) {
            if (startsWith(body, cursor, new byte[]{'-', '-'})) {
                break;
            }
            cursor = skipCrLf(body, cursor);
            int headerEnd = indexOf(body, new byte[]{'\r', '\n', '\r', '\n'}, cursor);
            if (headerEnd < 0) {
                throw new IllegalArgumentException("上传表单头不完整");
            }

            String headers = new String(body, cursor, headerEnd - cursor, StandardCharsets.ISO_8859_1);
            int contentStart = headerEnd + 4;
            int nextBoundary = indexOf(body, marker, contentStart);
            if (nextBoundary < 0) {
                throw new IllegalArgumentException("上传表单内容不完整");
            }
            int contentEnd = nextBoundary;
            if (contentEnd >= 2 && body[contentEnd - 2] == '\r' && body[contentEnd - 1] == '\n') {
                contentEnd -= 2;
            }

            String disposition = headerValue(headers, "Content-Disposition");
            String name = dispositionParameter(disposition, "name");
            String filename = dispositionParameter(disposition, "filename");
            if (name != null && !name.isEmpty()) {
                byte[] content = new byte[contentEnd - contentStart];
                System.arraycopy(body, contentStart, content, 0, content.length);
                result.put(name, new Part(filename, content));
            }
            cursor = nextBoundary + marker.length;
        }
        return new MultipartForm(result);
    }

    String text(String name) {
        Part part = parts.get(name);
        return part == null ? "" : new String(part.content, StandardCharsets.UTF_8).trim();
    }

    String rawText(String name) {
        Part part = parts.get(name);
        return part == null ? "" : new String(part.content, StandardCharsets.UTF_8);
    }

    byte[] bytes(String name) {
        Part part = parts.get(name);
        return part == null ? new byte[0] : part.content;
    }

    String filename(String name) {
        Part part = parts.get(name);
        return part == null ? "" : part.filename;
    }

    private static String boundaryFrom(String contentType) {
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")) {
            throw new IllegalArgumentException("请求必须使用 multipart/form-data");
        }
        String[] fragments = contentType.split(";");
        for (String fragment : fragments) {
            String value = fragment.trim();
            if (value.startsWith("boundary=")) {
                String boundary = value.substring("boundary=".length());
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                if (!boundary.isEmpty()) {
                    return boundary;
                }
            }
        }
        throw new IllegalArgumentException("请求中缺少 multipart boundary");
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                throw new IllegalArgumentException("上传内容超过 50 MB，请选择正确的 JDBC 驱动 JAR");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String headerValue(String headers, String headerName) {
        String[] lines = headers.split("\\r\\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(headerName)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    private static String dispositionParameter(String disposition, String parameter) {
        String prefix = parameter + "=";
        String[] fragments = disposition.split(";");
        for (String fragment : fragments) {
            String value = fragment.trim();
            if (value.startsWith(prefix)) {
                value = value.substring(prefix.length());
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    private static int skipCrLf(byte[] source, int offset) {
        if (offset + 1 < source.length && source[offset] == '\r' && source[offset + 1] == '\n') {
            return offset + 2;
        }
        return offset;
    }

    private static boolean startsWith(byte[] source, int offset, byte[] target) {
        if (offset + target.length > source.length) {
            return false;
        }
        for (int i = 0; i < target.length; i++) {
            if (source[offset + i] != target[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] source, byte[] target, int from) {
        outer:
        for (int i = Math.max(0, from); i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static final class Part {
        private final String filename;
        private final byte[] content;

        private Part(String filename, byte[] content) {
            this.filename = filename == null ? "" : filename;
            this.content = content;
        }
    }
}
