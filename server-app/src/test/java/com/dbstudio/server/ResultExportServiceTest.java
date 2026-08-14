package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.desktop.csv.CsvService;
import com.dbstudio.desktop.query.ResultMutationTarget;
import com.dbstudio.desktop.query.StatementResult;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ResultExportServiceTest {
    private final ResultExportService service = new ResultExportService(new CsvService());

    @Test
    void writesExcelValuesAsRawTextAndLeavesNullCellsBlank() throws Exception {
        StatementResult result = result();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeLoaded(result, output, "excel", Arrays.asList(0, 1), Arrays.asList(0, 1, 2), dialect());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals("001", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("a,b\nline", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals("=SUM(A1)", workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK,
                    workbook.getSheetAt(0).getRow(2).getCell(2).getCellType());
            assertEquals("@", workbook.getSheetAt(0).getRow(1).getCell(0).getCellStyle().getDataFormatString());
        }
    }

    @Test
    void writesInsertWithoutCatalogOrSchemaUsingPhysicalNames() throws Exception {
        StatementResult result = result();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeLoaded(result, output, "sql", Arrays.asList(0), Arrays.asList(0, 1), dialect());

        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("insert into sample(id, name) values(001, 'a,b\nline');\n", text);
        assertTrue(!text.contains("catalog") && !text.contains("schema"));
    }

    private StatementResult result() {
        List<ResultMutationTarget.Column> columns = Arrays.asList(
                new ResultMutationTarget.Column(0, "id", "`id`", Types.BIGINT),
                new ResultMutationTarget.Column(1, "name", "`name`", Types.VARCHAR),
                new ResultMutationTarget.Column(2, "formula", "`formula`", Types.VARCHAR));
        ResultMutationTarget target = new ResultMutationTarget("`catalog`.`schema`.`sample`", columns,
                Collections.<ResultMutationTarget.Key>emptyList());
        return new StatementResult("select", StatementType.QUERY,
                Arrays.asList("id", "name", "formula"), Collections.emptyList(), target,
                Arrays.asList(Arrays.asList("001", "a,b\nline", "=SUM(A1)"), Arrays.asList("002", "plain", null)),
                -1, false, Duration.ZERO, null);
    }

    private SqlDialect dialect() {
        return new SqlDialect() {
            @Override public String id() { return "mysql"; }
            @Override public String quoteIdentifier(String identifier) { return "`" + identifier + "`"; }
            @Override public Set<String> keywords() { return Collections.emptySet(); }
            @Override public List<SqlStatement> split(String script) { return Collections.emptyList(); }
            @Override public Optional<SqlStatement> currentStatement(String script, int cursorOffset) { return Optional.empty(); }
            @Override public StatementType classify(String sql) { return StatementType.OTHER; }
            @Override public String format(String sql) { return sql; }
        };
    }
}
