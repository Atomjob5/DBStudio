package com.dbstudio.server;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.ProviderRegistry;
import com.dbstudio.desktop.completion.CompletionSnapshotService;
import com.dbstudio.desktop.completion.CompletionSnapshotService.ColumnSnapshot;
import com.dbstudio.desktop.completion.CompletionSnapshotService.NamespaceSnapshot;
import com.dbstudio.desktop.completion.CompletionSnapshotService.ObjectSnapshot;
import com.dbstudio.desktop.completion.CompletionSnapshotService.Snapshot;
import com.dbstudio.desktop.csv.CsvService;
import com.dbstudio.desktop.logging.SqlLogSupport;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.persistence.ConnectionCatalogRepository;
import com.dbstudio.desktop.persistence.ConnectionCatalogRepository.EnvironmentEntry;
import com.dbstudio.desktop.persistence.ConnectionCatalogRepository.SystemEntry;
import com.dbstudio.desktop.persistence.QueryHistoryRepository;
import com.dbstudio.desktop.persistence.QueryHistoryRepository.QueryHistoryEntry;
import com.dbstudio.desktop.persistence.SettingsRepository;
import com.dbstudio.desktop.persistence.WorkspaceRepository;
import com.dbstudio.desktop.query.QueryExecution;
import com.dbstudio.desktop.query.QueryResultListener;
import com.dbstudio.desktop.query.QueryRunner.PageResult;
import com.dbstudio.desktop.query.ResultColumn;
import com.dbstudio.desktop.query.ResultMutationTarget;
import com.dbstudio.desktop.query.StatementResult;
import com.dbstudio.desktop.security.SecretStore;
import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.CompletionColumnComments;
import com.dbstudio.spi.CompletionMetadataListener;
import com.dbstudio.spi.ConnectionField;
import com.dbstudio.spi.ConnectionFieldOption;
import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.ConnectionTestResult;
import com.dbstudio.spi.DatabaseCapability;
import com.dbstudio.spi.DatabaseNamespace;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DBStudio 本地 HTTP API。
 *
 * <p>控制器只负责鉴权后的参数校验、Workspace 编排和事件映射，数据库会话的创建、事务和
 * 结果流式读取由核心服务完成。日志记录请求的业务维度和 SQL 指纹，不输出密码、令牌或完整 SQL。</p>
 */
@RestController
@RequestMapping("/api/v1")
public final class DbStudioApiController {
    private static final Logger LOG = LoggerFactory.getLogger(DbStudioApiController.class);
    private static final List<String> SETTING_KEYS = Arrays.asList(
            "ui.theme", "result.maxRows", "result.streamBatchRows", "result.columnLayoutScope",
            "result.copyHeaderOnDoubleClick", "result.copySeparator",
            "result.headerSortingEnabled", "result.headerFilteringEnabled", "result.showColumnRemarksInHeader",
            "statusBar.showSelectedColumnRemarks",
            "connection.maxActiveSessions", "connection.autoCommit", "connection.idleTimeoutMinutes",
            "connection.transactionDisconnectRollbackMinutes",
            "editor.completionCandidateLimit",
            "layout.leftWidth", "layout.editorHeight");

    private final ProviderRegistry providers;
    private final ConnectionProfileRepository profiles;
    private final ConnectionCatalogRepository catalog;
    private final QueryHistoryRepository history;
    private final SettingsRepository settings;
    private final SecretStore secrets;
    private final CsvService csv;
    private final WorkspaceRegistry workspaces;
    private final ConfigurableApplicationContext application;
    private final WorkspaceRepository workspaceRepository;
    private final ApplicationRunLifecycle runLifecycle;
    private final ObjectMapper objectMapper;
    private final CompletionSnapshotService completionSnapshots = new CompletionSnapshotService();

    public DbStudioApiController(ProviderRegistry providers, ConnectionProfileRepository profiles,
                                 ConnectionCatalogRepository catalog,
                                 QueryHistoryRepository history, SettingsRepository settings,
                                 SecretStore secrets, CsvService csv, WorkspaceRegistry workspaces,
                                 ConfigurableApplicationContext application,
                                 WorkspaceRepository workspaceRepository, ApplicationRunLifecycle runLifecycle,
                                 ObjectMapper objectMapper) {
        this.providers = providers; this.profiles = profiles; this.catalog = catalog; this.history = history;
        this.settings = settings; this.secrets = secrets; this.csv = csv;
        this.workspaces = workspaces; this.application = application;
        this.workspaceRepository = workspaceRepository; this.runLifecycle = runLifecycle;
        this.objectMapper = objectMapper;
    }

    @PutMapping("/workspaces/{workspaceId}")
    public Map<String, Object> createWorkspace(@PathVariable String workspaceId) {
        WorkspaceRegistry.WorkspaceRegistration registration = workspaces.create(workspaceId);
        return ApiPayloads.map("workspaceId", workspaceId, "created", registration.created());
    }

    @PostMapping("/workspaces/{workspaceId}/restore")
    public Map<String, Object> restoreWorkspace(@PathVariable String workspaceId,
                                                @RequestBody Map<String, Object> body) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        Object rawEditors = body == null ? null : body.get("editors");
        if (!(rawEditors instanceof List)) throw new ApiException("INVALID_REQUEST", "editors 必须是数组");
        List<Object> restored = new ArrayList<Object>();
        for (Object raw : (List<?>) rawEditors) {
            if (!(raw instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) raw;
            String editorId = ApiPayloads.required(value, "editorId");
            EditorSession editor;
            try { editor = workspace.editors().create(UUID.fromString(editorId)); }
            catch (IllegalArgumentException exception) {
                throw new ApiException("INVALID_EDITOR_ID", "查询标签 ID 无效", exception);
            }
            String profileId = ApiPayloads.text(value, "profileId");
            if (profileId.trim().isEmpty()) {
                restored.add(ApiPayloads.map("editorId", editorId, "recoveryStatus", "restored",
                        "connectionState", "unbound"));
                continue;
            }
            try {
                SavedProfile binding = bindEditor(workspace, editor, profileId, Collections.<String, Object>emptyMap());
                restored.add(ApiPayloads.map("editorId", editorId, "recoveryStatus", "restored",
                        "connectionState", "suspended", "connection", profileMap(binding)));
            } catch (ApiException exception) {
                if ("PROFILE_NOT_FOUND".equals(exception.getCode())) {
                    restored.add(ApiPayloads.map("editorId", editorId, "recoveryStatus", "profileUnavailable",
                            "connectionState", "unbound", "message", SqlLogSupport.sanitizeMessage(exception.getMessage())));
                } else if ("PASSWORD_REQUIRED".equals(exception.getCode())
                        || "CONNECTION_FAILED".equals(exception.getCode())) {
                    restored.add(ApiPayloads.map("editorId", editorId, "recoveryStatus", "passwordRequired",
                            "connectionState", "credentials-required", "message",
                            SqlLogSupport.sanitizeMessage(exception.getMessage())));
                } else throw exception;
            }
        }
        return ApiPayloads.map("editors", restored);
    }

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap(@RequestParam(required = false) String workspaceId) throws SQLException {
        List<Object> providerValues = new ArrayList<Object>();
        for (DatabaseProvider provider : providers.all()) providerValues.add(providerMap(provider));
        List<Object> profileValues = new ArrayList<Object>();
        for (SavedProfile saved : profiles.findAll()) profileValues.add(profileMap(saved));
        List<Object> systemValues = new ArrayList<Object>();
        for (SystemEntry system : catalog.systems()) systemValues.add(systemMap(system));
        List<Object> environmentValues = new ArrayList<Object>();
        for (EnvironmentEntry environment : catalog.environments()) environmentValues.add(environmentMap(environment));
        Map<String, String> settingValues = readSettings();
        return ApiPayloads.map("providers", providerValues, "profiles", profileValues,
                "systems", systemValues, "environments", environmentValues,
                "recentFiles", Collections.emptyList(), "settings", settingValues);
    }

    @GetMapping("/connections/catalog")
    public Map<String, Object> connectionCatalog() throws SQLException {
        List<Object> systems = new ArrayList<Object>();
        for (SystemEntry value : catalog.systems()) systems.add(systemMap(value));
        List<Object> environments = new ArrayList<Object>();
        for (EnvironmentEntry value : catalog.environments()) environments.add(environmentMap(value));
        List<Object> profileValues = new ArrayList<Object>();
        for (SavedProfile value : profiles.findAll()) profileValues.add(profileMap(value));
        return ApiPayloads.map("systems", systems, "environments", environments, "profiles", profileValues);
    }

    @PostMapping("/connection-systems")
    public Map<String, Object> createConnectionSystem(@RequestBody Map<String, Object> body) throws SQLException {
        SystemEntry created = catalog.createSystem(catalogName(body));
        connectionsChanged();
        return systemMap(created);
    }

    @PutMapping("/connection-systems/{id}")
    public Map<String, Object> renameConnectionSystem(@PathVariable String id,
                                                       @RequestBody Map<String, Object> body) throws SQLException {
        SystemEntry updated = catalog.renameSystem(id, catalogName(body));
        connectionsChanged();
        return systemMap(updated);
    }

    @DeleteMapping("/connection-systems/{id}")
    public Map<String, Object> deleteConnectionSystem(@PathVariable String id) throws SQLException {
        catalog.deleteSystem(id); connectionsChanged(); return ApiPayloads.map("deleted", true);
    }

    @PostMapping("/connection-environments")
    public Map<String, Object> createConnectionEnvironment(@RequestBody Map<String, Object> body) throws SQLException {
        EnvironmentEntry created = catalog.createEnvironment(
                ApiPayloads.required(body, "systemId"), catalogName(body));
        connectionsChanged();
        return environmentMap(created);
    }

    @PutMapping("/connection-environments/{id}")
    public Map<String, Object> renameConnectionEnvironment(@PathVariable String id,
                                                            @RequestBody Map<String, Object> body) throws SQLException {
        EnvironmentEntry updated = catalog.renameEnvironment(id, catalogName(body));
        connectionsChanged();
        return environmentMap(updated);
    }

    @DeleteMapping("/connection-environments/{id}")
    public Map<String, Object> deleteConnectionEnvironment(@PathVariable String id) throws SQLException {
        catalog.deleteEnvironment(id); connectionsChanged(); return ApiPayloads.map("deleted", true);
    }

    @PostMapping("/workspaces/{workspaceId}/connection-profiles")
    public Map<String, Object> createConnectionProfile(@PathVariable String workspaceId,
                                                        @RequestBody Map<String, Object> body) throws Exception {
        return saveConnectionProfile(workspaceId, null, body);
    }

    @PutMapping("/workspaces/{workspaceId}/connection-profiles/{profileId}")
    public Map<String, Object> updateConnectionProfile(@PathVariable String workspaceId,
                                                        @PathVariable String profileId,
                                                        @RequestBody Map<String, Object> body) throws Exception {
        return saveConnectionProfile(workspaceId, profileId, body);
    }

    @PutMapping("/workspaces/{workspaceId}/connection-profiles/{profileId}/location")
    public Map<String, Object> moveConnectionProfile(@PathVariable String workspaceId,
                                                      @PathVariable String profileId,
                                                      @RequestBody Map<String, Object> body) throws Exception {
        workspaces.require(workspaceId);
        UUID id = profileId(profileId);
        SavedProfile current = profiles.find(id).orElseThrow(
                () -> new ApiException("PROFILE_NOT_FOUND", "数据库链接不存在或已删除"));
        String environmentId = ApiPayloads.required(body, "environmentId");
        if (!catalog.findEnvironment(environmentId).isPresent()) {
            throw new ApiException("ENVIRONMENT_NOT_FOUND", "连接环境不存在或已删除");
        }
        if (!environmentId.equals(current.environmentId())) {
            profiles.moveToEnvironment(id, environmentId);
            connectionsChanged();
        }
        SavedProfile moved = profiles.find(id).orElseThrow(
                () -> new ApiException("PROFILE_NOT_FOUND", "数据库链接不存在或已删除"));
        return profileMap(moved);
    }

    @DeleteMapping("/workspaces/{workspaceId}/connection-profiles/{profileId}")
    public Map<String, Object> deleteConnectionProfile(@PathVariable String workspaceId,
                                                        @PathVariable String profileId) throws Exception {
        profiles.softDelete(profileId(profileId));
        connectionsChanged();
        return ApiPayloads.map("deleted", true);
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

    @PostMapping("/workspaces/{workspaceId}/metadata/children")
    public List<Map<String, Object>> metadataChildren(@PathVariable String workspaceId,
                                                       @RequestBody Map<String, Object> body) throws SQLException {
        Workspace workspace = workspaces.require(workspaceId);
        DatabaseContext context = databaseFor(workspace, body);
        synchronized (context.metadataSession()) {
            String kind = ApiPayloads.text(body, "kind");
            if (kind.isEmpty() || "root".equals(kind)) {
                context.resultColumnResolver().invalidate();
                List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
                for (DatabaseNamespace namespace : context.provider().metadata().listNamespaces(context.metadataSession())) {
                    String namespaceKind = namespace.kind().name().toLowerCase(java.util.Locale.ROOT);
                    nodes.add(node(namespaceKind, namespace.label(), false, namespace.catalog(), namespace.schema(),
                            null, null, namespace.kind() == com.dbstudio.spi.NamespaceKind.SCHEMA
                                    ? "Schema " + namespace.label() : "数据库 " + namespace.label()));
                }
                return nodes;
            }
            if ("catalog".equals(kind) || "schema".equals(kind)) {
                return metadataGroups(context.provider(), ApiPayloads.text(body, "catalog"),
                        ApiPayloads.text(body, "schema"));
            }
            if ("group".equals(kind)) {
                String catalog = ApiPayloads.text(body, "catalog");
                String schema = ApiPayloads.text(body, "schema");
                DatabaseObjectType type = objectType(ApiPayloads.required(body, "objectType"));
                List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
                for (DatabaseObject object : context.provider().metadata().listObjects(
                        context.metadataSession(), new DatabaseNamespace(catalog, schema,
                                schema.isEmpty() ? catalog : schema,
                                schema.isEmpty() ? com.dbstudio.spi.NamespaceKind.CATALOG
                                        : com.dbstudio.spi.NamespaceKind.SCHEMA, false), type)) {
                    boolean leaf = object.type() != DatabaseObjectType.TABLE && object.type() != DatabaseObjectType.VIEW;
                    nodes.add(node("object", object.name(), leaf, object.catalog(), object.schema(),
                            object.type().name(), object.name(), object.remarks()));
                }
                return nodes;
            }
            if ("object".equals(kind)) {
                String catalog = ApiPayloads.text(body, "catalog");
                String schema = ApiPayloads.text(body, "schema");
                String name = ApiPayloads.required(body, "name");
                List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
                for (ColumnInfo column : context.provider().metadata().listColumns(
                        context.metadataSession(), catalog, schema, name)) {
                    String detail = column.typeName() + (column.primaryKey() ? " · 主键" : "");
                    nodes.add(node("column", column.name(), true, catalog, schema, "COLUMN", column.name(), detail));
                }
                return nodes;
            }
            return Collections.emptyList();
        }
    }

    @PostMapping("/workspaces/{workspaceId}/metadata/definition")
    public Map<String, Object> metadataDefinition(@PathVariable String workspaceId,
                                                   @RequestBody Map<String, Object> body) throws SQLException {
        DatabaseContext context = databaseFor(workspaces.require(workspaceId), body);
        DatabaseObject object = objectFrom(body);
        synchronized (context.metadataSession()) {
            return ApiPayloads.map("definition",
                    context.provider().metadata().definition(context.metadataSession(), object));
        }
    }

    @PostMapping("/workspaces/{workspaceId}/metadata/completion-namespaces")
    public Map<String, Object> completionNamespaces(@PathVariable String workspaceId,
                                                     @RequestBody Map<String, Object> body) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        SavedProfile saved = completionProfile(workspace, body);
        char[] password = completionPassword(workspace, saved);
        try {
            DatabaseProvider provider = providers.require(saved.profile().providerId());
            try (DatabaseContext temporary = new DatabaseContext(provider, saved.profile(), password)) {
                List<Object> values = new ArrayList<Object>();
                for (DatabaseNamespace namespace : provider.metadata().listCompletionNamespaces(temporary.metadataSession())) {
                    values.add(completionNamespaceMap(namespace));
                }
                return ApiPayloads.map("providerId", provider.id(), "sourceProfileId", saved.profile().id().toString(),
                        "namespaces", values);
            } catch (SQLException exception) {
                throw new ApiException("METADATA_LOAD_FAILED",
                        "获取数据库Schema失败：" + safeMessage(exception), exception);
            }
        } finally { Arrays.fill(password, '\0'); }
    }

    @PostMapping("/workspaces/{workspaceId}/metadata/completion-snapshot")
    public Map<String, Object> completionSnapshot(@PathVariable String workspaceId,
                                                   @RequestBody Map<String, Object> body) throws Exception {
        final Workspace workspace = workspaces.require(workspaceId);
        final String loadId = ApiPayloads.required(body, "loadId");
        LOG.info("补全快照请求开始 workspace={} loadId={}", workspaceId, loadId);
        final SavedProfile saved = completionProfile(workspace, body);
        char[] password = completionPassword(workspace, saved);
        try {
            DatabaseProvider provider = providers.require(saved.profile().providerId());
            try (DatabaseContext temporary = new DatabaseContext(provider, saved.profile(), password)) {
                DatabaseSession session = temporary.metadataSession();
                final String sourceProfileId = saved.profile().id().toString();
                workspace.events().emit("metadata.completionProgress", ApiPayloads.map(
                        "loadId", loadId, "phase", "discovering", "completed", 0,
                        "total", 1, "message", "正在校验可见Schema…",
                        "sourceProfileId", sourceProfileId, "environmentId", saved.environmentId()));
                List<DatabaseNamespace> available = provider.metadata().listCompletionNamespaces(session);
                List<DatabaseNamespace> selected = selectedCompletionNamespaces(body, available);
                workspace.events().emit("metadata.completionProgress", ApiPayloads.map(
                        "loadId", loadId, "phase", "discovering", "completed", 1,
                        "total", 1, "message", "Schema范围已确认",
                        "sourceProfileId", sourceProfileId, "environmentId", saved.environmentId()));
                Snapshot snapshot;
                synchronized (session) {
                    snapshot = completionSnapshots.build(provider, session, sourceProfileId, selected,
                            new CompletionSnapshotService.ProgressListener() {
                                @Override public void progress(String phase, int completed, int total, String message) {
                                    workspace.events().emit("metadata.completionProgress", ApiPayloads.map(
                                            "loadId", loadId, "phase", phase, "completed", completed,
                                            "total", total, "message", message,
                                            "sourceProfileId", sourceProfileId,
                                            "environmentId", saved.environmentId()));
                                }
                            });
                }
                LOG.info("补全快照请求完成 workspace={} loadId={} namespaces={}", workspaceId, loadId,
                        snapshot.namespaces().size());
                return completionSnapshotMap(snapshot);
            } catch (SQLException exception) {
                LOG.warn("补全快照请求失败 workspace={} loadId={} reason={}", workspaceId, loadId,
                        safeMessage(exception), exception);
                throw new ApiException("METADATA_LOAD_FAILED",
                        "获取数据库补全信息失败：" + safeMessage(exception), exception);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @PostMapping(value = "/workspaces/{workspaceId}/metadata/completion-snapshot-stream",
            produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> completionSnapshotStream(
            @PathVariable String workspaceId, @RequestBody final Map<String, Object> body) throws Exception {
        final Workspace workspace = workspaces.require(workspaceId);
        final String loadId = ApiPayloads.required(body, "loadId");
        final SavedProfile saved = completionProfile(workspace, body);
        final DatabaseProvider provider = providers.require(saved.profile().providerId());
        if (!provider.metadata().supportsStreamingCompletionMetadata()) {
            throw new ApiException("UNSUPPORTED_OPERATION", "当前数据库类型不支持流式补全元数据");
        }
        final char[] password = completionPassword(workspace, saved);
        StreamingResponseBody stream = output -> {
            NdjsonWriter writer = new NdjsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8),
                    objectMapper);
            boolean began = false;
            try (DatabaseContext temporary = new DatabaseContext(provider, saved.profile(), password)) {
                DatabaseSession session = temporary.metadataSession();
                List<DatabaseNamespace> available = provider.metadata().listCompletionNamespaces(session);
                final List<DatabaseNamespace> selected = selectedCompletionNamespaces(body, available);
                final String generation = UUID.randomUUID().toString();
                final String sourceProfileId = saved.profile().id().toString();
                final String generatedAt = Instant.now().toString();
                final CompletionProgressEmitter progress = new CompletionProgressEmitter(
                        workspace, loadId, sourceProfileId, saved.environmentId());
                final List<String> selectedKeys = new ArrayList<String>(selected.size());
                final List<Object> namespaceValues = new ArrayList<Object>(selected.size());
                String defaultNamespaceKey = "";
                for (DatabaseNamespace namespace : selected) {
                    String key = CompletionSnapshotService.namespaceKey(namespace.catalog(), namespace.schema());
                    selectedKeys.add(key);
                    namespaceValues.add(ApiPayloads.map("key", key, "catalog", namespace.catalog(),
                            "schema", namespace.schema(), "label", namespace.label()));
                    if (defaultNamespaceKey.isEmpty() && namespace.current()) defaultNamespaceKey = key;
                }
                final String resolvedDefaultNamespaceKey = defaultNamespaceKey;
                writer.line(ApiPayloads.map("type", "begin", "generation", generation,
                        "metadata", ApiPayloads.map("formatVersion", 2, "providerId", provider.id(),
                                "sourceProfileId", sourceProfileId, "generatedAt", generatedAt,
                                "defaultNamespaceKey", resolvedDefaultNamespaceKey,
                                "selectedNamespaceKeys", selectedKeys, "namespaces", namespaceValues)));
                began = true;
                progress.emit("objects", 0, "正在读取表、视图和备注…");
                final long[] counts = new long[] { 0L, 0L };
                final String[] warning = new String[] { "" };
                synchronized (session) {
                    provider.metadata().streamCompletionMetadata(session, selected,
                            new LinkedHashSet<DatabaseObjectType>(Arrays.asList(
                                    DatabaseObjectType.TABLE, DatabaseObjectType.VIEW)),
                            new CompletionMetadataListener() {
                                @Override public void objects(List<DatabaseObject> objects) throws SQLException {
                                    writerLine(writer, objectStreamRecord("objects", objects));
                                    counts[0] += objects.size();
                                    progress.emit("objects", counts[0], "表、视图已可用于补全");
                                }

                                @Override public void supplementalTables(List<DatabaseObject> objects)
                                        throws SQLException {
                                    writerLine(writer, objectStreamRecord("tables", objects));
                                    counts[0] += objects.size();
                                    progress.emit("tables", counts[0], "已补充遗漏表");
                                }

                                @Override public void columns(List<CompletionColumnComments> groups)
                                        throws SQLException {
                                    List<Object> values = new ArrayList<Object>(groups.size());
                                    long added = 0;
                                    for (CompletionColumnComments group : groups) {
                                        List<Object> columns = new ArrayList<Object>(group.columns().size());
                                        for (ColumnInfo column : group.columns()) {
                                            columns.add(Arrays.<Object>asList(column.name(), column.remarks()));
                                        }
                                        added += columns.size();
                                        values.add(ApiPayloads.map("namespaceKey",
                                                CompletionSnapshotService.namespaceKey(group.catalog(), group.schema()),
                                                "objectName", group.objectName(), "columns", columns));
                                    }
                                    writerLine(writer, ApiPayloads.map("type", "columns", "values", values));
                                    counts[1] += added;
                                    progress.emit("columns", counts[1], "正在同步字段备注…");
                                }

                                @Override public void warning(String phase, String message) throws SQLException {
                                    warning[0] = message;
                                    writerLine(writer, ApiPayloads.map("type", "warning",
                                            "phase", phase, "message", message));
                                }
                            });
                }
                Map<String, Object> summary = ApiPayloads.map("providerId", provider.id(),
                        "sourceProfileId", sourceProfileId, "generatedAt", generatedAt,
                        "selectedNamespaceKeys", selectedKeys, "objectCount", counts[0],
                        "columnCount", counts[1], "estimatedBytes", writer.bytes());
                if (!warning[0].isEmpty()) summary.put("warning", warning[0]);
                writer.line(ApiPayloads.map("type", "complete", "summary", summary));
                workspace.events().emit("metadata.completionProgress", ApiPayloads.map(
                        "loadId", loadId, "phase", "completed", "completed", counts[1],
                        "total", counts[1], "message", warning[0].isEmpty() ? "补全缓存已生成" : warning[0],
                        "sourceProfileId", sourceProfileId, "environmentId", saved.environmentId()));
            } catch (Exception exception) {
                LOG.warn("流式补全快照失败 workspace={} loadId={} reason={}",
                        workspaceId, loadId, safeMessage(exception), exception);
                if (began) {
                    try {
                        writer.line(ApiPayloads.map("type", "error", "phase", "stream",
                                "message", "获取数据库补全信息失败：" + safeMessage(exception)));
                    } catch (IOException ignored) { /* 客户端已断开。 */ }
                } else if (exception instanceof IOException) {
                    throw (IOException) exception;
                } else {
                    throw new IOException("获取数据库补全信息失败：" + safeMessage(exception), exception);
                }
            } finally {
                Arrays.fill(password, '\0');
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    @PostMapping("/workspaces/{workspaceId}/metadata/completion-table-structure")
    public Map<String, Object> completionTableStructure(@PathVariable String workspaceId,
                                                        @RequestBody Map<String, Object> body) throws SQLException {
        DatabaseContext context = databaseFor(workspaces.require(workspaceId), body);
        String providerId = context.provider().id();
        if (!"oracle".equals(providerId) && !"oceanbase-oracle".equals(providerId)) {
            throw new ApiException("UNSUPPORTED_OPERATION", "当前数据库类型不需要延迟加载字段结构");
        }
        String schema = ApiPayloads.required(body, "schema");
        String table = ApiPayloads.required(body, "table");
        List<Object> columns = new ArrayList<Object>();
        DatabaseSession session = context.metadataSession();
        synchronized (session) {
            for (ColumnInfo column : context.provider().metadata().listCompletionColumns(
                    session, "", schema, table)) {
                columns.add(ApiPayloads.map("name", column.name(), "typeName", column.typeName(),
                        "ordinal", column.ordinal()));
            }
        }
        return ApiPayloads.map("schema", schema, "table", table, "columns", columns);
    }

    @PostMapping("/workspaces/{workspaceId}/metadata/query")
    public Map<String, Object> metadataQuery(@PathVariable String workspaceId,
                                              @RequestBody Map<String, Object> body) {
        DatabaseContext context = databaseFor(workspaces.require(workspaceId), body);
        DatabaseObject object = objectFrom(body);
        return ApiPayloads.map("sql", context.provider().dialect().previewQuery(object, 1000));
    }

    @PostMapping("/workspaces/{workspaceId}/editors")
    public Map<String, Object> createEditor(@PathVariable String workspaceId,
                                            @RequestBody(required = false) Map<String, Object> body) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        EditorSession editor = workspace.editors().create();
        String requestedProfile = body == null ? "" : ApiPayloads.text(body, "profileId");
        if (!requestedProfile.isEmpty()) bindEditor(workspace, editor, requestedProfile, body);
        Map<String, Object> result = ApiPayloads.map("id", editor.id().toString(), "title", editor.title(),
                "connectionState", editor.bound() ? "ready" : "unbound");
        SavedProfile binding = workspace.binding(editor);
        if (binding != null) result.put("connection", profileMap(binding));
        LOG.info("创建编辑器 workspace={} editor={} bound={}", workspaceId, editor.id(), editor.bound());
        return result;
    }

    @PutMapping("/workspaces/{workspaceId}/editors/{editorId}/connection")
    public Map<String, Object> bindEditorConnection(@PathVariable String workspaceId,
                                                     @PathVariable String editorId,
                                                     @RequestBody Map<String, Object> body) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        EditorSession editor = workspace.editors().require(editorId);
        resolveTransactionBeforeSwitch(workspace, editor, ApiPayloads.text(body, "transactionAction"));
        SavedProfile binding = bindEditor(workspace, editor, ApiPayloads.required(body, "profileId"), body);
        LOG.info("编辑器绑定数据库 workspace={} editor={} profile={}", workspaceId, editorId, binding.profile().id());
        return ApiPayloads.map("connection", profileMap(binding), "connectionState", "ready");
    }

    @DeleteMapping("/workspaces/{workspaceId}/editors/{editorId}/connection")
    public Map<String, Object> unbindEditorConnection(@PathVariable String workspaceId,
                                                       @PathVariable String editorId,
                                                       @RequestParam(defaultValue = "") String transactionAction) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        EditorSession editor = workspace.editors().require(editorId);
        resolveTransactionBeforeSwitch(workspace, editor, transactionAction);
        workspace.unbind(editor);
        LOG.info("编辑器解绑数据库 workspace={} editor={}", workspaceId, editorId);
        return ApiPayloads.map("connectionState", "unbound");
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
            if ("commit".equals(action)) workspace.commit(editor).get(30, TimeUnit.SECONDS);
            else if ("rollback".equals(action)) workspace.rollback(editor).get(30, TimeUnit.SECONDS);
            else throw new ApiException("TRANSACTION_DECISION_REQUIRED", "关闭前必须提交或回滚事务");
        }
        workspace.closeEditor(editorId);
        workspaceRepository.removeEditor(workspaceId, editorId);
        return ApiPayloads.map("closed", true, "requiresTransactionDecision", false);
    }

    @PostMapping("/workspaces/{workspaceId}/editors/{editorId}/executions")
    public Map<String, Object> execute(@PathVariable final String workspaceId,
                                       @PathVariable final String editorId,
                                       @RequestBody Map<String, Object> body) {
        final Workspace workspace = workspaces.require(workspaceId);
        final EditorSession editor = workspace.editors().require(editorId);
        if (!workspace.events().connected()) throw new ApiException(
                "EVENT_CHANNEL_REQUIRED", "事件通道尚未连接，请等待重连后再执行SQL");
        ensureEditorContext(workspace, editor);
        final DatabaseContext context = workspace.requireEditorDatabase(editor);
        final List<SqlStatement> statements = selectStatements(context.provider(), body);
        boolean stopOnError = ApiPayloads.bool(body, "stopOnError", true);
        LOG.info("SQL执行请求开始 workspace={} editor={} statements={} stopOnError={}",
                workspaceId, editorId, statements.size(), stopOnError);

        final QueryResultListener listener = new QueryResultListener() {
            @Override public void resultStarted(int resultIndex, String sql, StatementType type, List<String> columns) {
                emitResultMetadata(workspace, editorId, resultIndex, sql, type, columns,
                        basicColumnDetails(columns), null, context.provider().dialect().id());
            }
            @Override public void resultMetadata(int resultIndex, String sql, StatementType type,
                                                 List<ResultColumn> columns, ResultMutationTarget mutationTarget) {
                List<String> labels = new ArrayList<String>(columns.size());
                List<Map<String, Object>> details = new ArrayList<Map<String, Object>>(columns.size());
                for (ResultColumn column : columns) {
                    labels.add(column.label());
                    details.add(ApiPayloads.map("label", column.label(), "name", column.name(),
                            "remarks", column.remarks(), "catalog", column.catalog(), "schema", column.schema(),
                            "table", column.table(), "typeName", column.typeName(), "jdbcType", column.jdbcType(),
                            "quotedLabel", column.quotedLabel()));
                }
                emitResultMetadata(workspace, editorId, resultIndex, sql, type, labels, details,
                        mutationTargetPayload(mutationTarget), context.provider().dialect().id());
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

        final UUID executionId = workspace.execute(editor, statements, stopOnError,
                id -> workspace.events().emit("query.started", ApiPayloads.map(
                        "editorId", editorId, "executionId", id.toString())), listener,
                (id, execution, failure) -> finishExecution(workspace, context, editorId, id, execution, failure));
        LOG.info("SQL执行任务已创建 workspace={} editor={} execution={}", workspaceId, editorId, executionId);
        return ApiPayloads.map("executionId", executionId.toString());
    }

    private void emitResultMetadata(Workspace workspace, String editorId, int resultIndex, String sql,
                                    StatementType type, List<String> columns,
                                    List<Map<String, Object>> columnDetails, Map<String, Object> mutationTarget,
                                    String dialectId) {
        workspace.events().emit("query.resultMeta", ApiPayloads.map("editorId", editorId,
                "resultIndex", resultIndex, "sql", sql, "type", type.name(), "columns", columns,
                "columnDetails", columnDetails, "mutationTarget", mutationTarget,
                "dialectId", dialectId,
                "rows", Collections.emptyList(), "updateCount", -1,
                "truncated", false, "durationMs", 0, "complete", false));
    }

    private List<Map<String, Object>> basicColumnDetails(List<String> columns) {
        List<Map<String, Object>> details = new ArrayList<Map<String, Object>>(columns.size());
        for (String column : columns) details.add(ApiPayloads.map(
                "label", column, "name", column, "remarks", "", "catalog", "", "schema", "",
                "table", "", "typeName", "", "jdbcType", java.sql.Types.VARCHAR,
                "quotedLabel", column));
        return details;
    }

    private Map<String, Object> mutationTargetPayload(ResultMutationTarget target) {
        if (target == null) return null;
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        for (ResultMutationTarget.Column column : target.columns()) {
            columns.add(ApiPayloads.map("resultIndex", column.resultIndex(), "name", column.name(),
                    "quotedName", column.quotedName(), "jdbcType", column.jdbcType()));
        }
        List<Map<String, Object>> keys = new ArrayList<Map<String, Object>>();
        for (ResultMutationTarget.Key key : target.uniqueKeys()) {
            keys.add(ApiPayloads.map("name", key.name(), "primary", key.primary(),
                    "resultColumnIndices", key.resultColumnIndices()));
        }
        return ApiPayloads.map("qualifiedName", target.qualifiedName(), "columns", columns,
                "uniqueKeys", keys);
    }

    @DeleteMapping("/workspaces/{workspaceId}/executions/{executionId}")
    public Map<String, Object> cancel(@PathVariable String workspaceId, @PathVariable String executionId) {
        Workspace workspace = workspaces.require(workspaceId);
        for (EditorSession editor : workspace.editors().all()) {
            if (editor.activeExecutionId() != null && editor.activeExecutionId().toString().equals(executionId)) {
                boolean cancelled = editor.cancel();
                LOG.info("取消SQL执行 workspace={} editor={} execution={} cancelled={}", workspaceId,
                        editor.id(), executionId, cancelled);
                return ApiPayloads.map("cancelled", cancelled);
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
        if (!workspace.events().connected()) throw new ApiException(
                "EVENT_CHANNEL_REQUIRED", "事件通道尚未连接，请等待重连后再加载结果");
        ensureEditorContext(workspace, editor);
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
        String rawExecutionId = ApiPayloads.text(body, "executionId").trim();
        final UUID executionId;
        try {
            executionId = rawExecutionId.isEmpty() ? UUID.randomUUID() : UUID.fromString(rawExecutionId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException("INVALID_EXECUTION_ID", "分页执行编号无效");
        }
        PageResult page = workspace.fetchPage(editor, executionId, source.sql(), offset, limit,
                () -> workspace.events().emit("query.pageStarted", ApiPayloads.map(
                        "editorId", editorId, "executionId", executionId.toString(),
                        "resultIndex", resultIndex))).get(120, TimeUnit.SECONDS);
        if (!page.cancelled()) editor.appendResultRows(resultIndex, page.rows(), page.hasMore());
        LOG.info("结果分页完成 workspace={} editor={} execution={} resultIndex={} offset={} rows={} hasMore={} cancelled={}",
                workspaceId, editorId, executionId, resultIndex, offset, page.rows().size(),
                page.hasMore(), page.cancelled());
        return ApiPayloads.map("executionId", executionId.toString(), "resultIndex", resultIndex,
                "offset", offset, "rows", page.rows(), "hasMore", page.hasMore(),
                "nextOffset", offset + page.rows().size(), "cancelled", page.cancelled());
    }

    @PostMapping("/workspaces/{workspaceId}/editors/{editorId}/transaction/{action}")
    public Map<String, Object> transaction(@PathVariable String workspaceId, @PathVariable String editorId,
                                            @PathVariable String action) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        EditorSession editor = workspace.editors().require(editorId);
        ensureEditorContext(workspace, editor);
        if ("commit".equals(action)) workspace.commit(editor).get(30, TimeUnit.SECONDS);
        else if ("rollback".equals(action)) workspace.rollback(editor).get(30, TimeUnit.SECONDS);
        else throw new ApiException("INVALID_TRANSACTION_ACTION", "事务操作无效");
        String message = "commit".equals(action) ? "事务已提交" : "事务已回滚";
        workspace.events().emit("transaction.status", ApiPayloads.map(
                "editorId", editorId, "dirty", false, "state", "none", "message", message));
        workspaceRepository.updateTransactionState(workspaceId, editorId, "none");
        LOG.info("事务操作完成 workspace={} editor={} action={}", workspaceId, editorId, action);
        return ApiPayloads.map("dirty", false, "message", message);
    }

    @PostMapping("/workspaces/{workspaceId}/sql/format")
    public Map<String, Object> format(@PathVariable String workspaceId,
                                      @RequestBody Map<String, Object> body) {
        DatabaseProvider provider = databaseFor(workspaces.require(workspaceId), body).provider();
        return ApiPayloads.map("text", provider.dialect().format(ApiPayloads.text(body, "text")));
    }

    @GetMapping("/workspaces/{workspaceId}/sql/completions")
    public List<Map<String, Object>> completions(@PathVariable String workspaceId,
                                                 @RequestParam(defaultValue = "") String prefix,
                                                 @RequestParam(defaultValue = "") String editorId) {
        Workspace workspace = workspaces.require(workspaceId);
        if (editorId.isEmpty()) throw new ApiException("EDITOR_REQUIRED", "SQL补全需要编辑标签上下文");
        DatabaseProvider provider = workspace.requireEditorDatabase(workspace.editors().require(editorId)).provider();
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
        if ("result.copyHeaderOnDoubleClick".equals(key) && !Arrays.asList("true", "false").contains(value)) {
            throw new ApiException("INVALID_SETTING", "双击复制列名设置无效");
        }
        if (("result.headerSortingEnabled".equals(key) || "result.headerFilteringEnabled".equals(key)
                || "result.showColumnRemarksInHeader".equals(key)
                || "statusBar.showSelectedColumnRemarks".equals(key))
                && !Arrays.asList("true", "false").contains(value)) {
            throw new ApiException("INVALID_SETTING", "开关设置无效");
        }
        if ("result.copySeparator".equals(key)
                && !Arrays.asList("comma", "tab", "semicolon", "pipe").contains(value)) {
            throw new ApiException("INVALID_SETTING", "复制分隔符设置无效");
        }
        if ("connection.maxActiveSessions".equals(key)) {
            try {
                int maximum = Integer.parseInt(value);
                if (maximum < 1 || maximum > 100) throw new NumberFormatException();
                workspaces.setMaxActiveSessions(maximum);
            } catch (NumberFormatException exception) {
                throw new ApiException("INVALID_SETTING", "最大活动链接数必须在 1 到 100 之间");
            }
        }
        if ("connection.autoCommit".equals(key)) {
            if (!Arrays.asList("true", "false").contains(value)) {
                throw new ApiException("INVALID_SETTING", "自动提交事务设置无效");
            }
            workspaces.setAutoCommit(Boolean.parseBoolean(value));
        }
        if ("connection.idleTimeoutMinutes".equals(key)) {
            try {
                int minutes = Integer.parseInt(value);
                if (minutes < 1 || minutes > 1_440) throw new NumberFormatException();
                workspaces.setIdleTimeoutMinutes(minutes);
            } catch (NumberFormatException exception) {
                throw new ApiException("INVALID_SETTING", "空闲链接回收时间必须在 1 到 1440 分钟之间");
            }
        }
        if ("connection.transactionDisconnectRollbackMinutes".equals(key)) {
            try {
                int minutes = Integer.parseInt(value);
                if (minutes < 1 || minutes > 1_440) throw new NumberFormatException();
                workspaces.setTransactionRollbackMinutes(minutes);
            } catch (NumberFormatException exception) {
                throw new ApiException("INVALID_SETTING", "事务断连回滚时间必须在 1 到 1440 分钟之间");
            }
        }
        if ("editor.completionCandidateLimit".equals(key)) {
            try {
                int limit = Integer.parseInt(value);
                if (limit < 10 || limit > 1_000) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw new ApiException("INVALID_SETTING", "补全候选词数量必须在 10 到 1000 之间");
            }
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
        LOG.info("CSV文件已上传 workspace={} name={} bytes={}", workspaceId, file.getOriginalFilename(), file.getSize());
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
        LOG.info("CSV预览完成 workspace={} uploadId={} rows={} columns={}", workspaceId, uploadId,
                preview.rows().size(), preview.headers().size());
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
            String editorId = ApiPayloads.required(body, "editorId");
            EditorSession editor = workspace.editors().require(editorId);
            DatabaseContext context = workspace.requireEditorDatabase(editor);
            try (DatabaseSession session = context.openEditorSession()) {
                long rows = csv.importFile(session, context.provider().dialect(),
                        context.profile().setting("database"), context.profile().setting("schema"), table,
                        workspace.requireUpload(uploadId),
                        charset, delimiter, mapping, count -> workspace.events().emit("task.progress",
                                ApiPayloads.map("taskId", id, "message", "已导入 " + count + " 行", "rows", count)));
                session.commit();
                LOG.info("CSV导入任务完成 workspace={} task={} editor={} rows={}", workspaceId, id, editorId, rows);
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
        final EditorSession editor = workspace.editors().require(editorId);
        final String sql = result(editor, resultIndex).sql();
        StreamingResponseBody body = output -> {
            DatabaseContext context = workspace.requireEditorDatabase(editor);
            try (DatabaseSession session = context.openEditorSession()) {
                LOG.info("CSV完整导出开始 workspace={} editor={} resultIndex={} sqlFingerprint={}", workspaceId,
                        editorId, resultIndex, SqlLogSupport.fingerprint(sql));
                csv.exportQuery(session, sql, new OutputStreamWriter(output, StandardCharsets.UTF_8), ',', count -> { });
            } catch (SQLException exception) {
                throw new IOException("完整导出失败：" + SqlLogSupport.sanitizeMessage(exception.getMessage()), exception);
            }
        };
        return csvResponse("dbstudio-full-result.csv", body);
    }

    @PostMapping("/shutdown")
    public Map<String, Object> shutdown() {
        runLifecycle.requestNormalExit();
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
        LOG.info("SQL执行完成 workspace={} editor={} execution={} failed={} cancelled={} durationMs={} results={}",
                workspace.id(), editorId, executionId, failed, cancelled, duration,
                execution == null ? 0 : execution.results().size());
        try { workspaceRepository.updateTransactionState(workspace.id(), editorId,
                editor.transactionDirty() ? "active" : "none"); } catch (SQLException ignored) { }
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
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName()
                : SqlLogSupport.sanitizeMessage(message);
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

    private Map<String, Object> saveConnectionProfile(String workspaceId, String forcedId,
                                                       Map<String, Object> body) throws Exception {
        Workspace workspace = workspaces.require(workspaceId);
        Map<String, Object> values = new LinkedHashMap<String, Object>(body);
        if (forcedId != null) values.put("id", forcedId);
        ConnectionProfile profile = profileFrom(values);
        String environmentId = ApiPayloads.required(values, "environmentId");
        if (!catalog.findEnvironment(environmentId).isPresent()) {
            throw new ApiException("ENVIRONMENT_NOT_FOUND", "连接环境不存在或已删除");
        }
        boolean remember = ApiPayloads.bool(values, "rememberPassword", false);
        char[] password = passwordForWorkspace(workspace, values, profile, false);
        try {
            profiles.save(profile, remember, environmentId);
            if (remember && password != null) secrets.save(profile.secretRef(), password);
            else if (!remember) {
                try { secrets.delete(profile.secretRef()); } catch (Exception ignored) { }
            }
            if (password != null) workspace.cachePassword(profile.id(), password);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
        SavedProfile saved = profiles.find(profile.id()).orElseThrow(
                () -> new ApiException("PROFILE_NOT_FOUND", "数据库链接保存失败"));
        connectionsChanged();
        return profileMap(saved);
    }

    private SavedProfile bindEditor(Workspace workspace, EditorSession editor, String rawProfileId,
                                    Map<String, Object> body) throws Exception {
        UUID id = profileId(rawProfileId);
        SavedProfile saved = profiles.find(id).orElseThrow(
                () -> new ApiException("PROFILE_NOT_FOUND", "数据库链接不存在或已删除"));
        String key = Workspace.bindingKey(saved);
        DatabaseContext context = workspace.context(key);
        if (context == null) {
            char[] password = passwordForWorkspace(workspace, body, saved.profile(), true);
            if (password == null) throw new ApiException("PASSWORD_REQUIRED", "该链接需要输入数据库密码");
            DatabaseContext created = null;
            try {
                created = new DatabaseContext(providers.require(saved.profile().providerId()), saved.profile(), password);
                context = workspace.registerContext(key, created);
                created = null;
                workspace.cachePassword(id, password);
                if (ApiPayloads.bool(body, "rememberPassword", saved.rememberPassword())) {
                    secrets.save(saved.profile().secretRef(), password);
                }
            } catch (SQLException exception) {
                throw new ApiException("CONNECTION_FAILED", "连接数据库失败：" + safeMessage(exception));
            } finally {
                Arrays.fill(password, '\0');
                if (created != null) created.close();
            }
        }
        try {
            workspace.bind(editor, saved, context);
            return saved;
        } catch (RuntimeException exception) {
            workspace.discardUnusedContext(key);
            throw exception;
        }
    }

    private char[] passwordForWorkspace(Workspace workspace, Map<String, Object> body,
                                        ConnectionProfile profile, boolean required) throws Exception {
        if (body.containsKey("password")) return ApiPayloads.text(body, "password").toCharArray();
        char[] cached = workspace.cachedPassword(profile.id());
        if (cached != null) return cached;
        Optional<char[]> remembered = secrets.load(profile.secretRef());
        if (remembered.isPresent()) return remembered.get();
        return required ? null : null;
    }

    private void resolveTransactionBeforeSwitch(Workspace workspace, EditorSession editor, String action) throws Exception {
        if (editor.activeExecutionId() != null) throw new ApiException("QUERY_BUSY", "查询执行期间不能切换数据库链接");
        if (!editor.transactionDirty()) return;
        if ("commit".equals(action)) workspace.commit(editor).get(30, TimeUnit.SECONDS);
        else if ("rollback".equals(action)) workspace.rollback(editor).get(30, TimeUnit.SECONDS);
        else throw new ApiException("TRANSACTION_DECISION_REQUIRED", "切换链接前必须提交或回滚事务");
    }

    private DatabaseContext databaseFor(Workspace workspace, Map<String, Object> body) {
        String editorId = ApiPayloads.text(body, "editorId");
        if (editorId.isEmpty()) throw new ApiException("EDITOR_REQUIRED", "数据库操作需要编辑标签上下文");
        EditorSession editor = workspace.editors().require(editorId);
        editor.touch();
        ensureEditorContext(workspace, editor);
        return workspace.requireEditorDatabase(editor);
    }

    private void ensureEditorContext(Workspace workspace, EditorSession editor) {
        if (editor.hasContext()) return;
        SavedProfile saved = workspace.binding(editor);
        if (saved == null) throw new ApiException("NOT_CONNECTED", "当前编辑标签尚未选择数据库链接");
        try { bindEditor(workspace, editor, saved.profile().id().toString(), Collections.<String,Object>emptyMap()); }
        catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw new ApiException("CONNECTION_REOPEN_FAILED", safeMessage(exception), exception); }
    }

    private void connectionsChanged() {
        workspaces.broadcast("connections.changed", ApiPayloads.map("revision", Instant.now().toString()));
    }

    private static UUID profileId(String raw) {
        try { return UUID.fromString(raw); }
        catch (Exception exception) { throw new ApiException("INVALID_PROFILE_ID", "连接配置 ID 无效"); }
    }

    private static String catalogName(Map<String, Object> body) {
        String value = ApiPayloads.required(body, "name").trim();
        if (value.length() > 64) throw new ApiException("INVALID_CATALOG_NAME", "名称不能超过64个字符");
        return value;
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
        if (!result.containsKey("result.copyHeaderOnDoubleClick")) result.put("result.copyHeaderOnDoubleClick", "true");
        if (!result.containsKey("result.copySeparator")) result.put("result.copySeparator", "comma");
        if (!result.containsKey("result.headerSortingEnabled")) result.put("result.headerSortingEnabled", "true");
        if (!result.containsKey("result.headerFilteringEnabled")) result.put("result.headerFilteringEnabled", "true");
        if (!result.containsKey("result.showColumnRemarksInHeader")) result.put("result.showColumnRemarksInHeader", "false");
        if (!result.containsKey("statusBar.showSelectedColumnRemarks")) result.put("statusBar.showSelectedColumnRemarks", "true");
        if (!result.containsKey("connection.maxActiveSessions")) result.put("connection.maxActiveSessions", "10");
        if (!result.containsKey("connection.autoCommit")) result.put("connection.autoCommit", "false");
        if (!result.containsKey("editor.completionCandidateLimit")) result.put("editor.completionCandidateLimit", "100");
        if (!result.containsKey("connection.idleTimeoutMinutes")) result.put("connection.idleTimeoutMinutes", "10");
        if (!result.containsKey("connection.transactionDisconnectRollbackMinutes")) {
            result.put("connection.transactionDisconnectRollbackMinutes", "10");
        }
        return result;
    }

    private static Map<String, Object> providerMap(DatabaseProvider provider) {
        List<Object> fields = new ArrayList<Object>();
        for (ConnectionField field : provider.connectionFields()) {
            String type = "INTEGER".equals(field.type().name()) ? "NUMBER" : field.type().name();
            List<Object> options = new ArrayList<Object>();
            for (ConnectionFieldOption option : field.options()) {
                options.add(ApiPayloads.map("value", option.value(), "label", option.label()));
            }
            fields.add(ApiPayloads.map("key", field.key(), "label", field.label(), "type", type,
                    "required", field.required(), "defaultValue", field.defaultValue(),
                    "description", field.description(), "options", options));
        }
        List<String> capabilities = new ArrayList<String>();
        for (DatabaseCapability capability : provider.capabilities().values()) capabilities.add(capability.name());
        return ApiPayloads.map("id", provider.id(), "displayName", provider.displayName(),
                "fields", fields, "capabilities", capabilities);
    }

    private static Map<String, Object> profileMap(SavedProfile saved) {
        ConnectionProfile profile = saved.profile();
        return ApiPayloads.map("id", profile.id().toString(), "providerId", profile.providerId(),
                "name", profile.name(), "settings", profile.settings(),
                "rememberPassword", saved.rememberPassword(), "environmentId", saved.environmentId(),
                "revision", saved.revision());
    }

    private SavedProfile completionProfile(Workspace workspace, Map<String, Object> body) throws SQLException {
        String editorId = ApiPayloads.text(body, "editorId");
        String rawProfileId = ApiPayloads.text(body, "profileId");
        if (editorId.isEmpty() == rawProfileId.isEmpty()) {
            throw new ApiException("INVALID_COMPLETION_SOURCE", "补全信息必须且只能指定编辑标签或数据库链接");
        }
        if (!editorId.isEmpty()) {
            SavedProfile saved = workspace.binding(workspace.editors().require(editorId));
            if (saved == null) throw new ApiException("NOT_CONNECTED", "当前编辑标签尚未选择数据库链接");
            return saved;
        }
        return profiles.find(profileId(rawProfileId)).orElseThrow(
                () -> new ApiException("PROFILE_NOT_FOUND", "数据库链接不存在或已删除"));
    }

    private char[] completionPassword(Workspace workspace, SavedProfile saved) throws Exception {
        char[] password = workspace.cachedPassword(saved.profile().id());
        if (password == null) password = secrets.load(saved.profile().secretRef()).orElse(null);
        if (password == null) throw new ApiException("PASSWORD_REQUIRED", "获取补全信息需要数据库密码");
        return password;
    }

    private static List<DatabaseNamespace> selectedCompletionNamespaces(
            Map<String, Object> body, List<DatabaseNamespace> available) {
        Object raw = body.get("selectedNamespaces");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw new ApiException("INVALID_COMPLETION_NAMESPACE", "至少选择一个Schema用于SQL补全");
        }
        Map<String, DatabaseNamespace> allowed = new LinkedHashMap<String, DatabaseNamespace>();
        for (DatabaseNamespace namespace : available) allowed.put(namespaceIdentity(
                namespace.catalog(), namespace.schema()), namespace);
        List<DatabaseNamespace> selected = new ArrayList<DatabaseNamespace>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) throw new ApiException("INVALID_COMPLETION_NAMESPACE", "Schema选择格式无效");
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) item;
            String identity = namespaceIdentity(ApiPayloads.text(value, "catalog"), ApiPayloads.text(value, "schema"));
            DatabaseNamespace namespace = allowed.get(identity);
            if (namespace == null) throw new ApiException("INVALID_COMPLETION_NAMESPACE", "选择了不可访问的Schema");
            if (seen.add(identity)) selected.add(namespace);
        }
        return selected;
    }

    private static String namespaceIdentity(String catalog, String schema) {
        return (catalog == null ? "" : catalog).toUpperCase(Locale.ROOT) + '\u0000'
                + (schema == null ? "" : schema).toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> completionNamespaceMap(DatabaseNamespace value) {
        return ApiPayloads.map("key", CompletionSnapshotService.namespaceKey(value.catalog(), value.schema()),
                "catalog", value.catalog(), "schema", value.schema(), "label", value.label(),
                "kind", value.kind().name().toLowerCase(Locale.ROOT), "current", value.current(),
                "system", value.system());
    }

    private static Map<String, Object> completionSnapshotMap(Snapshot snapshot) {
        List<Object> namespaces = new ArrayList<Object>();
        for (NamespaceSnapshot namespace : snapshot.namespaces()) {
            List<Object> objects = new ArrayList<Object>();
            for (ObjectSnapshot object : namespace.objects()) {
                List<Object> columns = new ArrayList<Object>();
                for (ColumnSnapshot column : object.columns()) {
                    columns.add(ApiPayloads.map("name", column.name(), "typeName", column.typeName(),
                            "remarks", column.remarks()));
                }
                objects.add(ApiPayloads.map("name", object.name(), "kind", object.kind(),
                        "remarks", object.remarks(), "columns", columns));
            }
            namespaces.add(ApiPayloads.map("key", namespace.key(), "catalog", namespace.catalog(),
                    "schema", namespace.schema(), "label", namespace.label(), "objects", objects));
        }
        return ApiPayloads.map("formatVersion", snapshot.formatVersion(), "providerId", snapshot.providerId(),
                "sourceProfileId", snapshot.sourceProfileId(), "generatedAt", snapshot.generatedAt(),
                "defaultNamespaceKey", snapshot.defaultNamespaceKey(),
                "selectedNamespaceKeys", snapshot.selectedNamespaceKeys(), "namespaces", namespaces);
    }

    private static Map<String, Object> objectStreamRecord(String type, List<DatabaseObject> objects) {
        List<Object> values = new ArrayList<Object>(objects.size());
        for (DatabaseObject object : objects) {
            values.add(ApiPayloads.map("namespaceKey",
                    CompletionSnapshotService.namespaceKey(object.catalog(), object.schema()),
                    "catalog", object.catalog(), "schema", object.schema(), "name", object.name(),
                    "kind", object.type() == DatabaseObjectType.VIEW ? "view" : "table",
                    "remarks", object.remarks()));
        }
        return ApiPayloads.map("type", type, "values", values);
    }

    private static void writerLine(NdjsonWriter writer, Map<String, Object> value) throws SQLException {
        try {
            writer.line(value);
        } catch (IOException exception) {
            throw new SQLException("补全元数据流已断开", exception);
        }
    }

    private static Map<String, Object> systemMap(SystemEntry value) {
        return ApiPayloads.map("id", value.id(), "name", value.name(), "revision", value.revision());
    }

    private static Map<String, Object> environmentMap(EnvironmentEntry value) {
        return ApiPayloads.map("id", value.id(), "systemId", value.systemId(),
                "name", value.name(), "revision", value.revision());
    }

    private static List<Map<String, Object>> metadataGroups(DatabaseProvider provider, String catalog, String schema) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        addGroup(result, provider, catalog, schema, DatabaseObjectType.TABLE, DatabaseCapability.TABLES);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.VIEW, DatabaseCapability.VIEWS);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.INDEX, DatabaseCapability.INDEXES);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.CONSTRAINT, DatabaseCapability.CONSTRAINTS);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.TRIGGER, DatabaseCapability.TRIGGERS);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.SEQUENCE, DatabaseCapability.SEQUENCES);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.SYNONYM, DatabaseCapability.SYNONYMS);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.TYPE, DatabaseCapability.TYPES);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.PROCEDURE, DatabaseCapability.PROCEDURES);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.FUNCTION, DatabaseCapability.FUNCTIONS);
        addGroup(result, provider, catalog, schema, DatabaseObjectType.PACKAGE, DatabaseCapability.PACKAGES);
        return result;
    }

    private static final class NdjsonWriter {
        private final OutputStreamWriter writer;
        private final ObjectMapper mapper;
        private long bytes;

        private NdjsonWriter(OutputStreamWriter writer, ObjectMapper mapper) {
            this.writer = writer;
            this.mapper = mapper;
        }

        private void line(Map<String, Object> value) throws IOException {
            String json = mapper.writeValueAsString(value);
            writer.write(json);
            writer.write('\n');
            writer.flush();
            bytes += json.getBytes(StandardCharsets.UTF_8).length + 1L;
        }

        private long bytes() { return bytes; }
    }

    private static final class CompletionProgressEmitter {
        private static final long INTERVAL_NANOS = 250_000_000L;
        private final Workspace workspace;
        private final String loadId;
        private final String sourceProfileId;
        private final String environmentId;
        private String phase = "";
        private long lastEmission;

        private CompletionProgressEmitter(Workspace workspace, String loadId,
                                          String sourceProfileId, String environmentId) {
            this.workspace = workspace;
            this.loadId = loadId;
            this.sourceProfileId = sourceProfileId;
            this.environmentId = environmentId;
        }

        private void emit(String nextPhase, long completed, String message) {
            long now = System.nanoTime();
            if (nextPhase.equals(phase) && completed > 0 && now - lastEmission < INTERVAL_NANOS) return;
            phase = nextPhase;
            lastEmission = now;
            workspace.events().emit("metadata.completionProgress", ApiPayloads.map(
                    "loadId", loadId, "phase", nextPhase, "completed", completed, "total", 0,
                    "message", message, "sourceProfileId", sourceProfileId,
                    "environmentId", environmentId));
        }
    }

    private static void addGroup(List<Map<String, Object>> result, DatabaseProvider provider, String catalog, String schema,
                                 DatabaseObjectType type, DatabaseCapability capability) {
        if (provider.capabilities().supports(capability)) result.add(node("group", type.displayName(), false,
                catalog, schema, type.name(), null, type.displayName()));
    }

    private static Map<String, Object> node(String kind, String label, boolean leaf, String catalog,
                                             String schema, String objectType, String name, String detail) {
        String rawId = kind + "|" + catalog + "|" + schema + "|" + objectType + "|" + name + "|" + label;
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
