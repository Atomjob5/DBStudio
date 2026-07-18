package com.dbstudio.server;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.ProviderRegistry;
import com.dbstudio.desktop.csv.CsvService;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.persistence.QueryHistoryRepository;
import com.dbstudio.desktop.persistence.QueryHistoryRepository.QueryHistoryEntry;
import com.dbstudio.desktop.persistence.SettingsRepository;
import com.dbstudio.desktop.query.QueryExecution;
import com.dbstudio.desktop.query.QueryResultListener;
import com.dbstudio.desktop.query.QueryRunner.PageResult;
import com.dbstudio.desktop.query.ResultColumn;
import com.dbstudio.desktop.query.StatementResult;
import com.dbstudio.desktop.security.SecretStore;
import com.dbstudio.desktop.web.EditorSessionRegistry;
import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.ConnectionField;
import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.ConnectionTestResult;
import com.dbstudio.spi.DatabaseCapability;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletResponse;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1")
public final class DbStudioApiController {
    private static final List<String> SETTING_KEYS = Arrays.asList(
            "ui.theme", "result.maxRows", "result.streamBatchRows", "result.columnLayoutScope",
            "layout.leftWidth", "layout.editorHeight");

    private final ProviderRegistry providers;
    private final ConnectionProfileRepository profiles;
    private final QueryHistoryRepository history;
    private final SettingsRepository settings;
    private final SecretStore secrets;
    private final CsvService csv;
    private final WorkspaceRegistry workspaces;
    private final ConfigurableApplicationContext application;

    public DbStudioApiController(ProviderRegistry providers, ConnectionProfileRepository profiles,
                                 QueryHistoryRepository history, SettingsRepository settings,
                                 SecretStore secrets, CsvService csv, WorkspaceRegistry workspaces,
                                 ConfigurableApplicationContext application) {
        this.providers = providers; this.profiles = profiles; this.history = history;
        this.settings = settings; this.secrets = secrets; this.csv = csv;
        this.workspaces = workspaces; this.application = application;
    }

    @PutMapping("/workspaces/{workspaceId}")
    public Map<String, Object> createWorkspace(@PathVariable String workspaceId) {
        workspaces.create(workspaceId);
        return ApiPayloads.map("workspaceId", workspaceId, "reconnectSeconds", WorkspaceRegistry.RECONNECT_SECONDS);
    }

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap(@RequestParam(required = false) String workspaceId) throws SQLException {
        List<Object> providerValues = new ArrayList<Object>();
        for (DatabaseProvider provider : providers.all()) providerValues.add(providerMap(provider));
        List<Object> profileValues = new ArrayList<Object>();
        for (SavedProfile saved : profiles.findAll()) profileValues.add(profileMap(saved.profile(), saved.rememberPassword()));
        Map<String, String> settingValues = readSettings();
        Map<String, Object> result = ApiPayloads.map("providers", providerValues, "profiles", profileValues,
                "recentFiles", Collections.emptyList(), "settings", settingValues);
        if (workspaceId != null && !workspaceId.trim().isEmpty()) {
            ConnectionProfile connected = workspaces.require(workspaceId).connectedProfile();
            if (connected != null) result.put("connectedProfile", profileMap(connected, isRemembered(connected.id())));
        }
        return result;
    }

    @PostMapping("/connections/test")
    public Map<String, Object> testConnection(@RequestBody Map<String, Object> body) throws Exception {
        ConnectionProfile profile = profileFrom(body);
        char[] password = passwordFor(body, profile);
        try {
            ConnectionTestResult result = providers.require(profile.providerId()).connections().test(profile, password);
            return ApiPayloads.map("success", result.success(), "message", result.message(),
                    "serverVersion", result.serverVersion(), "latencyMs", result.latency().toMillis());
        } finally { Arrays.fill(password, '\0'); }
    }

    @PostMapping("/workspaces/{workspaceId}/connection")
    public Map<String, Object> connect(@PathVariable String workspaceId,
                                       @RequestBody Map<String, Object> body) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        ConnectionProfile profile = profileFrom(body);
        boolean remember = ApiPayloads.bool(body, "rememberPassword", false);
        char[] password = passwordFor(body, profile);
        DatabaseContext context = null;
        try {
            context = new DatabaseContext(providers.require(profile.providerId()), profile, password);
            profiles.save(profile, remember);
            if (remember) secrets.save(profile.secretRef(), password);
            else {
                try { secrets.delete(profile.secretRef()); } catch (Exception ignored) { }
            }
            workspace.connect(context);
            context = null;
            return profileMap(profile, remember);
        } finally {
            Arrays.fill(password, '\0');
            if (context != null) context.close();
        }
    }

    @DeleteMapping("/workspaces/{workspaceId}/connection")
    public Map<String, Object> disconnect(@PathVariable String workspaceId) {
        workspaces.require(workspaceId).disconnect();
        return ApiPayloads.map("disconnected", true);
    }

    @PostMapping("/workspaces/{workspaceId}/metadata/children")
    public List<Map<String, Object>> metadataChildren(@PathVariable String workspaceId,
                                                       @RequestBody Map<String, Object> body) throws SQLException {
        DatabaseContext context = workspaces.require(workspaceId).requireDatabase();
        synchronized (context.metadataSession()) {
            String kind = ApiPayloads.text(body, "kind");
            if (kind.isEmpty() || "root".equals(kind)) {
                context.resultColumnResolver().invalidate();
                List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
                for (String catalog : context.provider().metadata().listCatalogs(context.metadataSession())) {
                    nodes.add(node("catalog", catalog, false, catalog, null, null, null,
                            "数据库 " + catalog));
                }
                return nodes;
            }
            if ("catalog".equals(kind)) return metadataGroups(context.provider(), ApiPayloads.required(body, "catalog"));
            if ("group".equals(kind)) {
                String catalog = ApiPayloads.required(body, "catalog");
                DatabaseObjectType type = objectType(ApiPayloads.required(body, "objectType"));
                List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
                for (DatabaseObject object : context.provider().metadata().listObjects(
                        context.metadataSession(), catalog, type)) {
                    boolean leaf = object.type() != DatabaseObjectType.TABLE && object.type() != DatabaseObjectType.VIEW;
                    nodes.add(node("object", object.name(), leaf, catalog, object.schema(),
                            object.type().name(), object.name(), object.remarks()));
                }
                return nodes;
            }
            if ("object".equals(kind)) {
                String catalog = ApiPayloads.required(body, "catalog");
                String name = ApiPayloads.required(body, "name");
                List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
                for (ColumnInfo column : context.provider().metadata().listColumns(
                        context.metadataSession(), catalog, ApiPayloads.text(body, "schema"), name)) {
                    String detail = column.typeName() + (column.primaryKey() ? " · 主键" : "");
                    nodes.add(node("column", column.name(), true, catalog, "", "COLUMN", column.name(), detail));
                }
                return nodes;
            }
            return Collections.emptyList();
        }
    }

    @PostMapping("/workspaces/{workspaceId}/metadata/definition")
    public Map<String, Object> metadataDefinition(@PathVariable String workspaceId,
                                                   @RequestBody Map<String, Object> body) throws SQLException {
        DatabaseContext context = workspaces.require(workspaceId).requireDatabase();
        DatabaseObject object = objectFrom(body);
        synchronized (context.metadataSession()) {
            return ApiPayloads.map("definition",
                    context.provider().metadata().definition(context.metadataSession(), object));
        }
    }

    @PostMapping("/workspaces/{workspaceId}/metadata/query")
    public Map<String, Object> metadataQuery(@PathVariable String workspaceId,
                                              @RequestBody Map<String, Object> body) {
        DatabaseContext context = workspaces.require(workspaceId).requireDatabase();
        String catalog = ApiPayloads.text(body, "catalog");
        String name = ApiPayloads.required(body, "name");
        String qualified = catalog.trim().isEmpty()
                ? context.provider().dialect().quoteIdentifier(name)
                : context.provider().dialect().quoteIdentifier(catalog) + "."
                + context.provider().dialect().quoteIdentifier(name);
        return ApiPayloads.map("sql", "SELECT *\nFROM " + qualified + "\nLIMIT 1000;");
    }

    @PostMapping("/workspaces/{workspaceId}/editors")
    public Map<String, Object> createEditor(@PathVariable String workspaceId) throws SQLException {
        Workspace workspace = workspaces.require(workspaceId);
        EditorSession editor = workspace.editors().create(workspace.requireDatabase());
        return ApiPayloads.map("id", editor.id().toString(), "title", editor.title());
    }

    @PostMapping("/workspaces/{workspaceId}/editors/{editorId}/close")
    public Map<String, Object> closeEditor(@PathVariable String workspaceId, @PathVariable String editorId,
                                           @RequestBody Map<String, Object> body) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        EditorSession editor = workspace.editors().require(editorId);
        String action = ApiPayloads.text(body, "action");
        if ("check".equals(action)) {
            return ApiPayloads.map("requiresTransactionDecision", editor.transactionDirty());
        }
        if (editor.transactionDirty()) {
            if ("commit".equals(action)) editor.commit().get(30, TimeUnit.SECONDS);
            else if ("rollback".equals(action)) editor.rollback().get(30, TimeUnit.SECONDS);
            else throw new ApiException("TRANSACTION_DECISION_REQUIRED", "关闭前必须提交或回滚事务");
        }
        workspace.editors().close(editorId);
        return ApiPayloads.map("closed", true, "requiresTransactionDecision", false);
    }

    @PostMapping("/workspaces/{workspaceId}/editors/{editorId}/executions")
    public Map<String, Object> execute(@PathVariable final String workspaceId,
                                       @PathVariable final String editorId,
                                       @RequestBody Map<String, Object> body) {
        final Workspace workspace = workspaces.require(workspaceId);
        final DatabaseContext context = workspace.requireDatabase();
        final EditorSession editor = workspace.editors().require(editorId);
        final List<SqlStatement> statements = selectStatements(context.provider(), body);
        boolean stopOnError = ApiPayloads.bool(body, "stopOnError", true);

        final QueryResultListener listener = new QueryResultListener() {
            @Override public void resultStarted(int resultIndex, String sql, StatementType type, List<String> columns) {
                emitResultMetadata(workspace, editorId, resultIndex, sql, type, columns,
                        basicColumnDetails(columns));
            }
            @Override public void resultMetadata(int resultIndex, String sql, StatementType type,
                                                 List<ResultColumn> columns) {
                List<String> labels = new ArrayList<String>(columns.size());
                List<Map<String, Object>> details = new ArrayList<Map<String, Object>>(columns.size());
                for (ResultColumn column : columns) {
                    labels.add(column.label());
                    details.add(ApiPayloads.map("label", column.label(), "name", column.name(),
                            "remarks", column.remarks(), "catalog", column.catalog(), "schema", column.schema(),
                            "table", column.table(), "typeName", column.typeName()));
                }
                emitResultMetadata(workspace, editorId, resultIndex, sql, type, labels, details);
            }
            @Override public void rows(int resultIndex, List<List<String>> rows) {
                workspace.events().emit("query.rows", ApiPayloads.map(
                        "editorId", editorId, "resultIndex", resultIndex, "rows", rows));
            }
            @Override public void resultCompleted(int resultIndex, StatementResult result) {
                workspace.events().emit("query.resultComplete", ApiPayloads.map("editorId", editorId,
                        "resultIndex", resultIndex, "updateCount", result.updateCount(),
                        "truncated", result.truncated(), "durationMs", result.duration().toMillis(),
                        "errorMessage", result.errorMessage(), "complete", true));
            }
        };

        final UUID executionId = workspace.editors().execute(editor, statements, stopOnError,
                id -> workspace.events().emit("query.started", ApiPayloads.map(
                        "editorId", editorId, "executionId", id.toString())), listener,
                (id, execution, failure) -> finishExecution(workspace, context, editorId, id, execution, failure));
        return ApiPayloads.map("executionId", executionId.toString());
    }

    private void emitResultMetadata(Workspace workspace, String editorId, int resultIndex, String sql,
                                    StatementType type, List<String> columns,
                                    List<Map<String, Object>> columnDetails) {
        workspace.events().emit("query.resultMeta", ApiPayloads.map("editorId", editorId,
                "resultIndex", resultIndex, "sql", sql, "type", type.name(), "columns", columns,
                "columnDetails", columnDetails, "rows", Collections.emptyList(), "updateCount", -1,
                "truncated", false, "durationMs", 0, "complete", false));
    }

    private List<Map<String, Object>> basicColumnDetails(List<String> columns) {
        List<Map<String, Object>> details = new ArrayList<Map<String, Object>>(columns.size());
        for (String column : columns) details.add(ApiPayloads.map(
                "label", column, "name", column, "remarks", "", "catalog", "", "schema", "",
                "table", "", "typeName", ""));
        return details;
    }

    @DeleteMapping("/workspaces/{workspaceId}/executions/{executionId}")
    public Map<String, Object> cancel(@PathVariable String workspaceId, @PathVariable String executionId) {
        Workspace workspace = workspaces.require(workspaceId);
        for (EditorSession editor : workspace.editors().all()) {
            if (editor.activeExecutionId() != null && editor.activeExecutionId().toString().equals(executionId)) {
                return ApiPayloads.map("cancelled", editor.cancel());
            }
        }
        return ApiPayloads.map("cancelled", false);
    }

    @PostMapping("/workspaces/{workspaceId}/editors/{editorId}/results/{resultIndex}/page")
    public Map<String, Object> fetchResultPage(@PathVariable String workspaceId,
                                               @PathVariable String editorId,
                                               @PathVariable int resultIndex,
                                               @RequestBody Map<String, Object> body) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        EditorSession editor = workspace.editors().require(editorId);
        StatementResult source = result(editor, resultIndex);
        if (!source.hasRows() || source.type() != StatementType.QUERY) {
            throw new ApiException("RESULT_NOT_PAGEABLE", "只有只读查询结果支持继续加载数据");
        }
        int offset = integer(body, "offset", 0);
        int limit = integer(body, "limit", 1_000);
        if (offset < 0) throw new ApiException("INVALID_RESULT_OFFSET", "结果偏移量不能小于 0");
        if (offset != source.rows().size()) {
            throw new ApiException("STALE_RESULT_OFFSET", "结果数据已变化，请使用当前已加载行数继续获取");
        }
        if (limit < 1 || limit > 100_000) {
            throw new ApiException("INVALID_RESULT_LIMIT", "单次加载行数必须在 1 到 100000 之间");
        }
        PageResult page = editor.runner().fetchPage(source.sql(), offset, limit).get(120, TimeUnit.SECONDS);
        editor.appendResultRows(resultIndex, page.rows(), page.hasMore());
        return ApiPayloads.map("resultIndex", resultIndex, "offset", offset, "rows", page.rows(),
                "hasMore", page.hasMore(), "nextOffset", offset + page.rows().size());
    }

    @PostMapping("/workspaces/{workspaceId}/editors/{editorId}/transaction/{action}")
    public Map<String, Object> transaction(@PathVariable String workspaceId, @PathVariable String editorId,
                                            @PathVariable String action) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        EditorSession editor = workspace.editors().require(editorId);
        if ("commit".equals(action)) editor.commit().get(30, TimeUnit.SECONDS);
        else if ("rollback".equals(action)) editor.rollback().get(30, TimeUnit.SECONDS);
        else throw new ApiException("INVALID_TRANSACTION_ACTION", "事务操作无效");
        String message = "commit".equals(action) ? "事务已提交" : "事务已回滚";
        workspace.events().emit("transaction.status", ApiPayloads.map(
                "editorId", editorId, "dirty", false, "message", message));
        return ApiPayloads.map("dirty", false, "message", message);
    }

    @PostMapping("/workspaces/{workspaceId}/sql/format")
    public Map<String, Object> format(@PathVariable String workspaceId,
                                      @RequestBody Map<String, Object> body) {
        DatabaseProvider provider = workspaces.require(workspaceId).requireDatabase().provider();
        return ApiPayloads.map("text", provider.dialect().format(ApiPayloads.text(body, "text")));
    }

    @GetMapping("/workspaces/{workspaceId}/sql/completions")
    public List<Map<String, Object>> completions(@PathVariable String workspaceId,
                                                 @RequestParam(defaultValue = "") String prefix) {
        DatabaseProvider provider = workspaces.require(workspaceId).requireDatabase().provider();
        String upper = prefix.toUpperCase(java.util.Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (String keyword : provider.dialect().keywords()) {
            if (upper.isEmpty() || keyword.startsWith(upper)) result.add(ApiPayloads.map(
                    "label", keyword, "insertText", keyword, "detail", "MySQL 关键字", "kind", "keyword"));
        }
        return result;
    }

    @GetMapping("/history")
    public List<Map<String, Object>> queryHistory(@RequestParam(defaultValue = "200") int limit) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (QueryHistoryEntry entry : history.recent(Math.min(1000, Math.max(1, limit)))) {
            result.add(ApiPayloads.map("sql", entry.sql(), "catalog", entry.catalog(),
                    "executedAt", entry.executedAt().toString(), "durationMs", entry.durationMs(),
                    "status", entry.status(), "rowCount", entry.rowCount(),
                    "errorMessage", entry.errorMessage()));
        }
        return result;
    }

    @GetMapping("/settings")
    public Map<String, String> getSettings() throws SQLException { return readSettings(); }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> body) throws SQLException {
        String key = ApiPayloads.required(body, "key");
        String value = ApiPayloads.text(body, "value");
        if (!SETTING_KEYS.contains(key)) throw new ApiException("INVALID_SETTING", "不支持的设置项");
        if ("ui.theme".equals(key) && !Arrays.asList("system", "light", "dark").contains(value)) {
            throw new ApiException("INVALID_SETTING", "主题设置无效");
        }
        if ("result.maxRows".equals(key)) {
            try {
                int maxRows = Integer.parseInt(value);
                if (maxRows < 1 || maxRows > 100_000) throw new NumberFormatException();
                workspaces.setMaxRows(maxRows);
            } catch (NumberFormatException exception) {
                throw new ApiException("INVALID_SETTING", "结果行数必须在 1 到 100000 之间");
            }
        }
        if ("result.streamBatchRows".equals(key)) {
            try {
                int streamBatchRows = Integer.parseInt(value);
                if (streamBatchRows < 1 || streamBatchRows > 1_000) throw new NumberFormatException();
                workspaces.setStreamBatchRows(streamBatchRows);
            } catch (NumberFormatException exception) {
                throw new ApiException("INVALID_SETTING", "流式推送行数必须在 1 到 1000 之间");
            }
        }
        if ("result.columnLayoutScope".equals(key) && !Arrays.asList("result", "editor").contains(value)) {
            throw new ApiException("INVALID_SETTING", "列布局范围设置无效");
        }
        settings.put(key, value);
        return ApiPayloads.map("key", key, "value", value);
    }

    @PostMapping(value = "/workspaces/{workspaceId}/csv/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadCsv(@PathVariable String workspaceId,
                                         @RequestPart("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) throw new ApiException("EMPTY_UPLOAD", "请选择非空的 CSV 或 TSV 文件");
        Workspace workspace = workspaces.require(workspaceId);
        Path path = workspace.storeUpload(file.getOriginalFilename(), file.getInputStream());
        String delimiter = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".tsv") ? "\t" : ",";
        return ApiPayloads.map("uploadId", workspace.uploadId(path), "name", file.getOriginalFilename(),
                "delimiter", delimiter);
    }

    @PostMapping("/workspaces/{workspaceId}/csv/preview")
    public Map<String, Object> previewCsv(@PathVariable String workspaceId,
                                          @RequestBody Map<String, Object> body) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        String uploadId = ApiPayloads.required(body, "uploadId");
        Charset charset = Charset.forName(ApiPayloads.text(body, "charset").isEmpty()
                ? "UTF-8" : ApiPayloads.text(body, "charset"));
        char delimiter = delimiter(body);
        CsvService.Preview preview = csv.preview(workspace.requireUpload(uploadId), charset, delimiter, 5);
        return ApiPayloads.map("uploadId", uploadId, "name", workspace.requireUpload(uploadId).getFileName().toString(),
                "delimiter", String.valueOf(delimiter), "charset", charset.name(),
                "headers", preview.headers(), "rows", preview.rows());
    }

    @PostMapping("/workspaces/{workspaceId}/csv/imports")
    public Map<String, Object> importCsv(@PathVariable final String workspaceId,
                                         @RequestBody final Map<String, Object> body) {
        final Workspace workspace = workspaces.require(workspaceId);
        final String uploadId = ApiPayloads.required(body, "uploadId");
        final String table = ApiPayloads.required(body, "table");
        final Charset charset = Charset.forName(ApiPayloads.text(body, "charset").isEmpty()
                ? "UTF-8" : ApiPayloads.text(body, "charset"));
        final char delimiter = delimiter(body);
        final Map<String, String> mapping = stringMap(ApiPayloads.object(body, "mapping"));
        String taskId = workspace.startTask("csv.import", id -> {
            DatabaseContext context = workspace.requireDatabase();
            try (DatabaseSession session = context.openEditorSession()) {
                long rows = csv.importFile(session, context.provider().dialect(),
                        context.profile().setting("database"), table, workspace.requireUpload(uploadId),
                        charset, delimiter, mapping, count -> workspace.events().emit("task.progress",
                                ApiPayloads.map("taskId", id, "message", "已导入 " + count + " 行", "rows", count)));
                session.commit();
                return ApiPayloads.map("rows", rows);
            } finally {
                workspace.removeUpload(uploadId);
            }
        });
        return ApiPayloads.map("taskId", taskId);
    }

    @GetMapping("/workspaces/{workspaceId}/csv/export/loaded")
    public ResponseEntity<StreamingResponseBody> exportLoaded(@PathVariable String workspaceId,
                                                               @RequestParam String editorId,
                                                               @RequestParam int resultIndex) {
        Workspace workspace = workspaces.require(workspaceId);
        StatementResult result = result(workspace.editors().require(editorId), resultIndex);
        StreamingResponseBody body = output -> csv.exportLoadedResult(result,
                new OutputStreamWriter(output, StandardCharsets.UTF_8), ',');
        return csvResponse("dbstudio-result.csv", body);
    }

    @GetMapping("/workspaces/{workspaceId}/csv/export/full")
    public ResponseEntity<StreamingResponseBody> exportFull(@PathVariable String workspaceId,
                                                             @RequestParam String editorId,
                                                             @RequestParam int resultIndex) {
        final Workspace workspace = workspaces.require(workspaceId);
        final String sql = result(workspace.editors().require(editorId), resultIndex).sql();
        StreamingResponseBody body = output -> {
            DatabaseContext context = workspace.requireDatabase();
            try (DatabaseSession session = context.openEditorSession()) {
                csv.exportQuery(session, sql, new OutputStreamWriter(output, StandardCharsets.UTF_8), ',', count -> { });
            } catch (SQLException exception) {
                throw new IOException("完整导出失败：" + exception.getMessage(), exception);
            }
        };
        return csvResponse("dbstudio-full-result.csv", body);
    }

    @PostMapping("/shutdown")
    public Map<String, Object> shutdown() {
        Thread closer = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(150L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                application.close();
            }
        }, "dbstudio-shutdown");
        closer.setDaemon(false);
        closer.start();
        return ApiPayloads.map("accepted", true);
    }

    private void finishExecution(Workspace workspace, DatabaseContext context, String editorId, UUID executionId,
                                 QueryExecution execution, Throwable failure) {
        boolean failed = failure != null || (execution != null && execution.failed());
        boolean cancelled = execution != null && execution.cancelled();
        long duration = execution == null ? 0 : execution.duration().toMillis();
        EditorSession editor = workspace.editors().require(editorId);
        workspace.events().emit("query.executionComplete", ApiPayloads.map("editorId", editorId,
                "executionId", executionId.toString(), "cancelled", cancelled, "failed", failed,
                "durationMs", duration, "transactionDirty", editor.transactionDirty()));
        try {
            String sql = editor.lastSql() == null ? "" : editor.lastSql();
            String error = failure == null ? firstError(execution) : safeMessage(failure);
            history.add(new QueryHistoryEntry(context.profile().id(), context.profile().setting("database"), sql,
                    Instant.now(), duration, cancelled ? "CANCELLED" : failed ? "FAILED" : "SUCCESS",
                    execution == null ? 0 : execution.affectedRows(), error));
        } catch (Exception ignored) { }
    }

    private static String firstError(QueryExecution execution) {
        if (execution == null) return null;
        for (StatementResult result : execution.results()) if (result.failed()) return result.errorMessage();
        return null;
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName() : message;
    }

    private static List<SqlStatement> selectStatements(DatabaseProvider provider, Map<String, Object> body) {
        String scope = ApiPayloads.text(body, "scope");
        String text = ApiPayloads.text(body, "text");
        String selected = ApiPayloads.text(body, "selectedText");
        List<SqlStatement> result;
        if (!selected.trim().isEmpty()) result = provider.dialect().split(selected);
        else if ("script".equals(scope)) result = provider.dialect().split(text);
        else {
            int cursor = 0;
            Object rawCursor = body.get("cursorOffset");
            if (rawCursor instanceof Number) cursor = ((Number) rawCursor).intValue();
            Optional<SqlStatement> current = provider.dialect().currentStatement(text, cursor);
            result = current.isPresent() ? Collections.singletonList(current.get()) : Collections.<SqlStatement>emptyList();
        }
        if (result.isEmpty()) throw new ApiException("EMPTY_SQL", "没有可执行的 SQL 语句");
        return result;
    }

    private ConnectionProfile profileFrom(Map<String, Object> body) {
        UUID id;
        String rawId = ApiPayloads.text(body, "id");
        try { id = rawId.trim().isEmpty() ? UUID.randomUUID() : UUID.fromString(rawId); }
        catch (IllegalArgumentException exception) { throw new ApiException("INVALID_PROFILE_ID", "连接配置 ID 无效"); }
        Map<String, String> values = stringMap(ApiPayloads.object(body, "settings"));
        values.remove("password");
        return new ConnectionProfile(id, ApiPayloads.required(body, "providerId"),
                ApiPayloads.required(body, "name"), values, "dbstudio/" + id);
    }

    private char[] passwordFor(Map<String, Object> body, ConnectionProfile profile) throws Exception {
        String supplied = ApiPayloads.text(body, "password");
        if (!supplied.isEmpty()) return supplied.toCharArray();
        return secrets.load(profile.secretRef()).orElse(new char[0]);
    }

    private boolean isRemembered(UUID id) throws SQLException {
        for (SavedProfile saved : profiles.findAll()) if (saved.profile().id().equals(id)) return saved.rememberPassword();
        return false;
    }

    private static Map<String, String> stringMap(Map<String, Object> source) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result;
    }

    private Map<String, String> readSettings() throws SQLException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String key : SETTING_KEYS) {
            Optional<String> value = settings.get(key);
            if (value.isPresent()) result.put(key, value.get());
        }
        if (!result.containsKey("ui.theme")) result.put("ui.theme", "system");
        if (!result.containsKey("result.maxRows")) result.put("result.maxRows", "1000");
        if (!result.containsKey("result.streamBatchRows")) result.put("result.streamBatchRows", "100");
        if (!result.containsKey("result.columnLayoutScope")) result.put("result.columnLayoutScope", "result");
        return result;
    }

    private static Map<String, Object> providerMap(DatabaseProvider provider) {
        List<Object> fields = new ArrayList<Object>();
        for (ConnectionField field : provider.connectionFields()) {
            String type = "INTEGER".equals(field.type().name()) ? "NUMBER" : field.type().name();
            fields.add(ApiPayloads.map("key", field.key(), "label", field.label(), "type", type,
                    "required", field.required(), "defaultValue", field.defaultValue(),
                    "description", field.description()));
        }
        List<String> capabilities = new ArrayList<String>();
        for (DatabaseCapability capability : provider.capabilities().values()) capabilities.add(capability.name());
        return ApiPayloads.map("id", provider.id(), "displayName", provider.displayName(),
                "fields", fields, "capabilities", capabilities);
    }

    private static Map<String, Object> profileMap(ConnectionProfile profile, boolean remember) {
        return ApiPayloads.map("id", profile.id().toString(), "providerId", profile.providerId(),
                "name", profile.name(), "settings", profile.settings(), "rememberPassword", remember);
    }

    private static List<Map<String, Object>> metadataGroups(DatabaseProvider provider, String catalog) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        addGroup(result, provider, catalog, DatabaseObjectType.TABLE, DatabaseCapability.TABLES);
        addGroup(result, provider, catalog, DatabaseObjectType.VIEW, DatabaseCapability.VIEWS);
        addGroup(result, provider, catalog, DatabaseObjectType.INDEX, DatabaseCapability.INDEXES);
        addGroup(result, provider, catalog, DatabaseObjectType.TRIGGER, DatabaseCapability.TRIGGERS);
        addGroup(result, provider, catalog, DatabaseObjectType.PROCEDURE, DatabaseCapability.PROCEDURES);
        addGroup(result, provider, catalog, DatabaseObjectType.FUNCTION, DatabaseCapability.FUNCTIONS);
        return result;
    }

    private static void addGroup(List<Map<String, Object>> result, DatabaseProvider provider, String catalog,
                                 DatabaseObjectType type, DatabaseCapability capability) {
        if (provider.capabilities().supports(capability)) result.add(node("group", type.displayName(), false,
                catalog, "", type.name(), null, type.displayName()));
    }

    private static Map<String, Object> node(String kind, String label, boolean leaf, String catalog,
                                             String schema, String objectType, String name, String detail) {
        String rawId = kind + "|" + catalog + "|" + objectType + "|" + name + "|" + label;
        return ApiPayloads.map("id", UUID.nameUUIDFromBytes(rawId.getBytes(StandardCharsets.UTF_8)).toString(),
                "label", label, "kind", kind, "leaf", leaf, "catalog", catalog,
                "schema", schema, "objectType", objectType, "name", name, "detail", detail);
    }

    private static DatabaseObject objectFrom(Map<String, Object> body) {
        return new DatabaseObject(objectType(ApiPayloads.required(body, "objectType")),
                ApiPayloads.text(body, "catalog"), ApiPayloads.text(body, "schema"),
                ApiPayloads.required(body, "name"), ApiPayloads.text(body, "remarks"),
                Collections.<String, String>emptyMap());
    }

    private static DatabaseObjectType objectType(String raw) {
        try { return DatabaseObjectType.valueOf(raw); }
        catch (IllegalArgumentException exception) { throw new ApiException("INVALID_OBJECT_TYPE", "数据库对象类型无效"); }
    }

    private static char delimiter(Map<String, Object> body) {
        String value = ApiPayloads.text(body, "delimiter");
        return "\\t".equals(value) || "\t".equals(value) ? '\t' : value.isEmpty() ? ',' : value.charAt(0);
    }

    private static int integer(Map<String, Object> body, String key, int defaultValue) {
        Object value = body.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException exception) {
            throw new ApiException("INVALID_NUMBER", key + " 必须是整数");
        }
    }

    private static StatementResult result(EditorSession editor, int index) {
        QueryExecution execution = editor.lastExecution();
        if (execution == null || index < 0 || index >= execution.results().size()) {
            throw new ApiException("RESULT_NOT_FOUND", "查询结果不存在或已经过期");
        }
        return execution.results().get(index);
    }

    private static ResponseEntity<StreamingResponseBody> csvResponse(String filename, StreamingResponseBody body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
