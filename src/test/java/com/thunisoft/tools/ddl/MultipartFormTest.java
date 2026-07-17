package com.thunisoft.tools.ddl;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultipartFormTest {
    @Test
    void parsesTextRawPasswordAndBinaryFile() throws Exception {
        String boundary = "----ddl-exporter-test-boundary";
        byte[] binary = new byte[]{0, 1, 2, 13, 10, 3, (byte) 255};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, boundary, "schemaName", null, "  public  ".getBytes(StandardCharsets.UTF_8));
        part(body, boundary, "password", null, " pass with spaces ".getBytes(StandardCharsets.UTF_8));
        part(body, boundary, "driverJar", "driver.jar", binary);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));

        MultipartForm form = MultipartForm.parse(new ByteArrayInputStream(body.toByteArray()),
                "multipart/form-data; boundary=\"" + boundary + "\"");

        assertEquals("public", form.text("schemaName"));
        assertEquals(" pass with spaces ", form.rawText("password"));
        assertEquals("driver.jar", form.filename("driverJar"));
        assertArrayEquals(binary, form.bytes("driverJar"));
    }

    @Test
    void rejectsNonMultipartContent() {
        assertThrows(IllegalArgumentException.class,
                () -> MultipartForm.parse(new ByteArrayInputStream(new byte[0]), "application/json"));
    }

    private static void part(ByteArrayOutputStream body, String boundary, String name, String filename,
                             byte[] content) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        String disposition = "Content-Disposition: form-data; name=\"" + name + "\"";
        if (filename != null) {
            disposition += "; filename=\"" + filename + "\"";
        }
        body.write((disposition + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        body.write(content);
        body.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
    }
}
