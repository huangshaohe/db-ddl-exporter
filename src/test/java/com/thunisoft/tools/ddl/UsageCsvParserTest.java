package com.thunisoft.tools.ddl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageCsvParserTest {
    @Test
    void parsesBomQuotedFieldsAndDeduplicatesObjects() {
        String csv = "\ufeffSchema,Object,evidence,source_files\r\n"
                + "public,orders,entity,src/Order.java\r\n"
                + "PUBLIC,orders,mapper,src/OrderMapper.java\r\n"
                + "public,customers,mapper,\"first line\nsecond line\"\r\n";

        List<String> names = UsageCsvParser.objectNames(csv.getBytes(StandardCharsets.UTF_8), "public");

        assertEquals(2, names.size());
        assertEquals("orders", names.get(0));
        assertEquals("customers", names.get(1));
    }

    @Test
    void returnsUniqueCountsForEverySchema() {
        String csv = "schema,object\nalpha,one\nalpha,one\nalpha,two\nbeta,three\n";

        List<UsageCsvParser.SchemaCount> counts =
                UsageCsvParser.schemaCounts(csv.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, counts.size());
        assertEquals("alpha", counts.get(0).schema);
        assertEquals(2, counts.get(0).count);
        assertEquals("beta", counts.get(1).schema);
        assertEquals(1, counts.get(1).count);
    }

    @Test
    void rejectsMissingColumnsAndUnclosedQuotes() {
        IllegalArgumentException columns = assertThrows(IllegalArgumentException.class,
                () -> UsageCsvParser.schemaCounts("name,value\na,b\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(columns.getMessage().contains("schema 和 object"));

        assertThrows(IllegalArgumentException.class,
                () -> UsageCsvParser.schemaCounts("schema,object\npublic,\"orders"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsOversizedCsvBeforeParsing() {
        byte[] content = new byte[10 * 1024 * 1024 + 1];
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UsageCsvParser.schemaCounts(content));
        assertTrue(error.getMessage().contains("10 MB"));
    }
}
