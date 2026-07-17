package com.thunisoft.tools.ddl;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.util.Collections;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlExportServiceTest {
    @Test
    void validatesRequestsBeforeOpeningAConnection() {
        RecordingDriver driver = new RecordingDriver();
        DdlExportService.ExportRequest request = new DdlExportService.ExportRequest(
                "https://not-jdbc", "demo", "secret", "public", "orders",
                "single", Collections.<String>emptyList());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DdlExportService().export(driver, request));

        assertTrue(error.getMessage().contains("jdbc:"));
        assertFalse(driver.called);
    }

    @Test
    void reportsWhenDriverDoesNotAcceptTheJdbcUrl() {
        RecordingDriver driver = new RecordingDriver();
        DdlExportService.ExportRequest request = new DdlExportService.ExportRequest(
                "jdbc:unsupported", "demo", "", "public", "orders",
                "single", Collections.<String>emptyList());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DdlExportService().export(driver, request));

        assertTrue(error.getMessage().contains("不接受这个 JDBC URL"));
        assertTrue(driver.called);
    }

    private static final class RecordingDriver implements Driver {
        private boolean called;

        @Override
        public Connection connect(String url, Properties info) {
            called = true;
            return null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return false;
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
