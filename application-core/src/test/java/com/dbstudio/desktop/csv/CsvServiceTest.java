package com.dbstudio.desktop.csv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dbstudio.desktop.query.StatementResult;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvServiceTest {
    @TempDir Path directory;
    private final CsvService service = new CsvService();

    @Test
    void roundTripsQuotesNewlinesAndNullMarker() throws Exception {
        Path file = directory.resolve("result.csv");
        StatementResult result = new StatementResult("select", StatementType.QUERY,
                Arrays.asList("id", "text"), Arrays.asList(
                Arrays.asList("1", "a,\"b\"\nline"), Arrays.asList("2", null)),
                -1, false, Duration.ZERO, null);
        service.exportLoadedResult(result, file, StandardCharsets.UTF_8, ',');

        CsvService.Preview preview = service.preview(file, StandardCharsets.UTF_8, ',', 10);
        assertEquals(Arrays.asList("id", "text"), preview.headers());
        assertEquals("a,\"b\"\nline", preview.rows().get(0).get(1));
        assertEquals(CsvService.NULL_VALUE, preview.rows().get(1).get(1));
    }

    @Test
    void failedBatchRollsBackEntireImport() throws Exception {
        Path file = directory.resolve("duplicate.csv");
        Files.write(file, "id,name\n1,A\n1,B\n".getBytes(StandardCharsets.UTF_8));
        try (final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE target(id INTEGER PRIMARY KEY, name TEXT)");
                connection.commit();
            }
            DatabaseSession session = new DatabaseSession() {
                @Override public Connection jdbcConnection() { return connection; }
                @Override public String currentCatalog() { return ""; }
                @Override public void close() { }
            };
            LinkedHashMap<String, String> mapping = new LinkedHashMap<String, String>();
            mapping.put("id", "id"); mapping.put("name", "name");
            assertThrows(Exception.class, () -> service.importFile(
                    session, quotedDialect(), "", "target", file, StandardCharsets.UTF_8, ',', mapping));
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM target")) {
                rows.next();
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    private SqlDialect quotedDialect() {
        return new SqlDialect() {
            @Override public String id() { return "test"; }
            @Override public String quoteIdentifier(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
            @Override public Set<String> keywords() { return Collections.emptySet(); }
            @Override public List<SqlStatement> split(String script) { return Collections.emptyList(); }
            @Override public Optional<SqlStatement> currentStatement(String script, int cursorOffset) { return Optional.empty(); }
            @Override public StatementType classify(String sql) { return StatementType.OTHER; }
            @Override public String format(String sql) { return sql; }
        };
    }
}
