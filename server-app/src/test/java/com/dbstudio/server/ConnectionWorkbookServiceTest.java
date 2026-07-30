package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.desktop.ProviderRegistry;
import com.dbstudio.desktop.persistence.AppDatabase;
import com.dbstudio.desktop.persistence.ConnectionCatalogRepository;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository;
import com.dbstudio.desktop.security.MemorySecretStore;
import com.dbstudio.desktop.security.SecretStore;
import com.dbstudio.desktop.security.SecretStoreException;
import com.dbstudio.spi.ConnectionProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class ConnectionWorkbookServiceTest {
    @TempDir Path directory;
    private AppDatabase database;
    private ConnectionProfileRepository profiles;
    private ConnectionCatalogRepository catalog;
    private MemorySecretStore secrets;
    private ConnectionWorkbookService service;

    @BeforeEach
    void setUp() throws Exception {
        database = new AppDatabase(directory);
        profiles = new ConnectionProfileRepository(database, new ObjectMapper());
        catalog = new ConnectionCatalogRepository(database);
        secrets = new MemorySecretStore();
        service = new ConnectionWorkbookService(database, new ProviderRegistry(), profiles, catalog, secrets);
    }

    @AfterEach
    void tearDown() throws Exception {
        secrets.close();
        database.close();
    }

    @Test
    void createsPasswordFreeTemplateAndRoundTripsNewConnections() throws Exception {
        byte[] template = service.template();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            assertTrue(workbook.getSheet("数据库链接") != null);
            assertTrue(workbook.getSheet("填写说明") != null);
            String headers = rowText(workbook.getSheet("数据库链接").getRow(0));
            assertTrue(headers.contains("主机 (host)"));
            assertFalse(headers.contains("密码"));
            assertFalse(headers.toLowerCase().contains("secret"));
            assertTrue(sheetText(workbook.getSheet("填写说明")).contains("必填"));
        }

        MockMultipartFile file = workbook("订单系统", "DEV", "订单库", "MySQL",
                "10.0.0.8", "3307", false);
        Map<String, Object> preview = service.preview(file);
        List<Map<String, Object>> rows = rows(preview);
        assertEquals(1, rows.size());
        assertEquals("create", rows.get(0).get("operation"));
        assertEquals(true, rows.get(0).get("createsSystem"));
        assertEquals(true, rows.get(0).get("createsEnvironment"));
        assertTrue(((List<?>) rows.get(0).get("errors")).isEmpty());

        Map<String, Object> imported = service.commit(rows);
        assertEquals(1, imported.get("createdSystems"));
        assertEquals(1, imported.get("createdEnvironments"));
        assertEquals(1, imported.get("createdProfiles"));
        assertEquals("10.0.0.8", profiles.findAll().get(0).profile().setting("host"));
        assertFalse(profiles.findAll().get(0).rememberPassword());
    }

    @Test
    void remembersReplacesAndRemovesImportedPasswordsWithoutPuttingThemInExcel() throws Exception {
        List<Map<String, Object>> createdRows = rows(service.preview(workbook(
                "订单系统", "DEV", "订单库", "MySQL", "10.0.0.8", "3306", false)));
        createdRows.get(0).put("password", "first-password");
        createdRows.get(0).put("rememberPassword", true);
        service.commit(createdRows);

        ConnectionProfile created = profiles.findAll().get(0).profile();
        assertTrue(profiles.find(created.id()).get().rememberPassword());
        char[] remembered = secrets.load(created.secretRef()).get();
        try { assertEquals("first-password", new String(remembered)); }
        finally { java.util.Arrays.fill(remembered, '\0'); }

        List<Map<String, Object>> updatedRows = rows(service.preview(workbook(
                "订单系统", "DEV", "订单库", "MySQL", "10.0.0.9", "3306", false)));
        assertEquals(true, updatedRows.get(0).get("rememberPassword"));
        updatedRows.get(0).put("password", "second-password");
        service.commit(updatedRows);
        char[] replaced = secrets.load(created.secretRef()).get();
        try { assertEquals("second-password", new String(replaced)); }
        finally { java.util.Arrays.fill(replaced, '\0'); }

        List<Map<String, Object>> forgottenRows = rows(service.preview(workbook(
                "订单系统", "DEV", "订单库", "MySQL", "10.0.0.10", "3306", false)));
        forgottenRows.get(0).put("rememberPassword", false);
        service.commit(forgottenRows);
        assertFalse(profiles.find(created.id()).get().rememberPassword());
        assertFalse(secrets.load(created.secretRef()).isPresent());
    }

    @Test
    void rejectsRememberingANewConnectionWithoutAPassword() throws Exception {
        List<Map<String, Object>> importRows = rows(service.preview(workbook(
                "订单系统", "DEV", "订单库", "MySQL", "10.0.0.8", "3306", false)));
        importRows.get(0).put("rememberPassword", true);

        ApiException error = assertThrows(ApiException.class, () -> service.commit(importRows));

        assertEquals("INVALID_CONNECTION_IMPORT", error.getCode());
        assertTrue(profiles.findAll().isEmpty());
    }

    @Test
    void rollsBackDatabaseAndPreviouslyWrittenSecretsWhenKeychainWriteFails() throws Exception {
        FailingSecretStore failingSecrets = new FailingSecretStore(2);
        service = new ConnectionWorkbookService(
                database, new ProviderRegistry(), profiles, catalog, failingSecrets);
        int originalSystems = catalog.systems().size();
        List<Map<String, Object>> importRows = new ArrayList<Map<String, Object>>(rows(
                service.preview(workbook("订单系统", "DEV", "订单库", "MySQL",
                        "10.0.0.8", "3306", false))));
        importRows.addAll(rows(service.preview(workbook(
                "订单系统", "DEV", "报表库", "MySQL", "10.0.0.9", "3306", false))));
        importRows.get(0).put("password", "first-password");
        importRows.get(0).put("rememberPassword", true);
        importRows.get(1).put("password", "second-password");
        importRows.get(1).put("rememberPassword", true);

        ApiException error = assertThrows(ApiException.class, () -> service.commit(importRows));

        assertEquals("CONNECTION_IMPORT_SECRET_FAILED", error.getCode());
        assertTrue(profiles.findAll().isEmpty());
        assertEquals(originalSystems, catalog.systems().size());
        assertTrue(failingSecrets.empty());
    }

    @Test
    void matchesByThreeLevelNameAndPreservesRememberedPasswordFlag() throws Exception {
        ConnectionCatalogRepository.SystemEntry system = catalog.createSystem("订单系统");
        ConnectionCatalogRepository.EnvironmentEntry environment =
                catalog.createEnvironment(system.id(), "DEV");
        UUID id = UUID.randomUUID();
        String secretRef = "legacy-secret/" + id;
        profiles.save(new ConnectionProfile(id, "mysql", "订单库",
                settings("127.0.0.1", "3306"), secretRef), true, environment.id());

        List<Map<String, Object>> rows = rows(service.preview(workbook(
                " 订单系统 ", "dev", "订单库", "mysql", "10.0.0.9", "3308", false)));
        assertEquals("update", rows.get(0).get("operation"));
        assertEquals(id.toString(), rows.get(0).get("matchedProfileId"));

        Map<String, Object> imported = service.commit(rows);
        assertEquals(1, imported.get("updatedProfiles"));
        assertEquals("10.0.0.9", profiles.find(id).get().profile().setting("host"));
        assertTrue(profiles.find(id).get().rememberPassword());
        assertEquals(secretRef, profiles.find(id).get().profile().secretRef());
    }

    @Test
    void rejectsFormulaAndDuplicateRowsWithoutSavingAnything() throws Exception {
        int originalSystems = catalog.systems().size();
        MockMultipartFile file = workbook("订单系统", "DEV", "订单库", "MySQL",
                "127.0.0.1", "3306", true);
        Map<String, Object> preview = service.preview(file);
        List<Map<String, Object>> rows = rows(preview);
        assertEquals(2, rows.size());
        assertTrue(((List<?>) rows.get(0).get("errors")).toString().contains("重复"));
        assertTrue(((List<?>) rows.get(1).get("errors")).toString().contains("公式"));

        ApiException error = assertThrows(ApiException.class, () -> service.commit(rows));
        assertEquals("INVALID_CONNECTION_IMPORT", error.getCode());
        assertTrue(profiles.findAll().isEmpty());
        assertEquals(originalSystems, catalog.systems().size());
    }

    @Test
    void rejectsStaleUpdatesAndExportsOnlyRequestedProfiles() throws Exception {
        ConnectionCatalogRepository.SystemEntry system = catalog.createSystem("订单系统");
        ConnectionCatalogRepository.EnvironmentEntry environment =
                catalog.createEnvironment(system.id(), "DEV");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        profiles.save(new ConnectionProfile(firstId, "mysql", "订单库",
                settings("127.0.0.1", "3306"), "dbstudio/" + firstId), false, environment.id());
        profiles.save(new ConnectionProfile(secondId, "mysql", "报表库",
                settings("127.0.0.2", "3306"), "dbstudio/" + secondId), false, environment.id());

        List<Map<String, Object>> rows = rows(service.preview(workbook(
                "订单系统", "DEV", "订单库", "MySQL", "10.0.0.10", "3306", false)));
        profiles.save(new ConnectionProfile(firstId, "mysql", "订单库",
                settings("127.0.0.3", "3306"), "dbstudio/" + firstId), false, environment.id());
        ApiException stale = assertThrows(ApiException.class, () -> service.commit(rows));
        assertEquals("CONNECTION_IMPORT_STALE", stale.getCode());

        byte[] exported = service.exportWorkbook("selected",
                Collections.singletonList(secondId.toString()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            Sheet sheet = workbook.getSheet("数据库链接");
            assertEquals(1, sheet.getLastRowNum());
            assertEquals("报表库", sheet.getRow(1).getCell(2).getStringCellValue());
            assertFalse(rowText(sheet.getRow(0)).contains("密码"));
        }
    }

    private MockMultipartFile workbook(String system, String environment, String name,
                                       String provider, String host, String port,
                                       boolean duplicateWithFormula) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.template()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet("数据库链接");
            Map<String, Integer> columns = headerColumns(sheet.getRow(0));
            fill(sheet.createRow(1), columns, system, environment, name, provider, host, port);
            if (duplicateWithFormula) {
                Row duplicate = sheet.createRow(2);
                fill(duplicate, columns, system, environment, name, provider, host, port);
                duplicate.getCell(columns.get("host")).setCellFormula("\"127.0.0.1\"");
            }
            workbook.write(output);
            return new MockMultipartFile("file", "connections.xlsx",
                    ConnectionWorkbookService.CONTENT_TYPE, output.toByteArray());
        }
    }

    private static void fill(Row row, Map<String, Integer> columns, String system,
                             String environment, String name, String provider,
                             String host, String port) {
        row.createCell(columns.get("系统")).setCellValue(system);
        row.createCell(columns.get("环境")).setCellValue(environment);
        row.createCell(columns.get("链接名称")).setCellValue(name);
        row.createCell(columns.get("数据库类型")).setCellValue(provider);
        row.createCell(columns.get("host")).setCellValue(host);
        row.createCell(columns.get("port")).setCellValue(port);
        row.createCell(columns.get("database")).setCellValue("orders");
        row.createCell(columns.get("username")).setCellValue("root");
        row.createCell(columns.get("timeoutSeconds")).setCellValue("10");
    }

    private static Map<String, String> settings(String host, String port) {
        Map<String, String> result = new java.util.LinkedHashMap<String, String>();
        result.put("host", host);
        result.put("port", port);
        result.put("database", "orders");
        result.put("username", "root");
        result.put("timeoutSeconds", "10");
        return result;
    }

    private static Map<String, Integer> headerColumns(Row header) {
        Map<String, Integer> result = new java.util.LinkedHashMap<String, Integer>();
        DataFormatter formatter = new DataFormatter();
        for (Cell cell : header) {
            String value = formatter.formatCellValue(cell);
            int open = value.lastIndexOf('(');
            int close = value.lastIndexOf(')');
            result.put(open >= 0 && close > open ? value.substring(open + 1, close) : value,
                    cell.getColumnIndex());
        }
        return result;
    }

    private static String rowText(Row row) {
        StringBuilder result = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        for (Cell cell : row) result.append(formatter.formatCellValue(cell)).append('|');
        return result.toString();
    }

    private static String sheetText(Sheet sheet) {
        StringBuilder result = new StringBuilder();
        for (Row row : sheet) result.append(rowText(row));
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> preview) {
        return (List<Map<String, Object>>) preview.get("rows");
    }

    private static final class FailingSecretStore implements SecretStore {
        private final Map<String, char[]> values = new LinkedHashMap<String, char[]>();
        private final int failingSave;
        private int saves;

        private FailingSecretStore(int failingSave) {
            this.failingSave = failingSave;
        }

        @Override
        public void save(String reference, char[] secret) throws SecretStoreException {
            saves++;
            if (saves == failingSave) throw new SecretStoreException("模拟密钥库写入失败");
            values.put(reference, Arrays.copyOf(secret, secret.length));
        }

        @Override
        public Optional<char[]> load(String reference) {
            char[] value = values.get(reference);
            return value == null ? Optional.<char[]>empty()
                    : Optional.of(Arrays.copyOf(value, value.length));
        }

        @Override
        public void delete(String reference) {
            char[] value = values.remove(reference);
            if (value != null) Arrays.fill(value, '\0');
        }

        private boolean empty() {
            return values.isEmpty();
        }
    }
}
