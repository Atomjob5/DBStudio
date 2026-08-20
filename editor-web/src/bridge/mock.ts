import type { MockRequestHandler } from "./rpc";
import { DEFAULT_SHORTCUT_BINDINGS, serializeShortcutBindings } from "../shortcuts";
import { DEFAULT_COLOR_SCHEMES, serializeColorSchemeSettings } from "../appearance";

const providers = [{ id: "mysql", displayName: "MySQL", capabilities: ["TABLES", "VIEWS", "PROCEDURES"], fields: [
  { key: "host", label: "主机", type: "TEXT", required: true, defaultValue: "127.0.0.1", description: "数据库主机" },
  { key: "port", label: "端口", type: "NUMBER", required: true, defaultValue: "3306", description: "端口" },
  { key: "database", label: "数据库", type: "TEXT", required: false, defaultValue: "", description: "默认数据库" },
  { key: "username", label: "用户名", type: "TEXT", required: true, defaultValue: "root", description: "用户名" },
  { key: "password", label: "密码", type: "PASSWORD", required: false, defaultValue: "", description: "密码" },
  { key: "timeoutSeconds", label: "连接超时（秒）", type: "NUMBER", required: true, defaultValue: "10", description: "连接超时" }
] }, { id: "oracle", displayName: "Oracle 19c / 21c", capabilities: ["SCHEMAS", "TABLES", "VIEWS", "PROCEDURES", "PACKAGES", "SEQUENCES"], fields: [
  { key: "host", label: "主机", type: "TEXT", required: true, defaultValue: "127.0.0.1", description: "数据库主机" },
  { key: "port", label: "端口", type: "NUMBER", required: true, defaultValue: "1521", description: "监听端口" },
  { key: "connectionMode", label: "连接方式", type: "SELECT", required: true, defaultValue: "service", description: "Oracle连接标识类型", options: [
    { value: "service", label: "Service Name" }, { value: "sid", label: "SID" }
  ] },
  { key: "service", label: "Service Name / SID", type: "TEXT", required: true, defaultValue: "ORCL", description: "服务名或SID" },
  { key: "username", label: "用户名", type: "TEXT", required: true, defaultValue: "system", description: "用户名" },
  { key: "password", label: "密码", type: "PASSWORD", required: false, defaultValue: "", description: "密码" },
  { key: "schema", label: "默认Schema", type: "TEXT", required: false, defaultValue: "", description: "默认Schema" },
  { key: "timeoutSeconds", label: "连接超时（秒）", type: "NUMBER", required: true, defaultValue: "10", description: "连接超时" }
] }, { id: "oceanbase-oracle", displayName: "OceanBase Oracle 模式", capabilities: ["SCHEMAS", "TABLES", "VIEWS", "PROCEDURES", "PACKAGES", "SEQUENCES"], fields: [
  { key: "host", label: "主机", type: "TEXT", required: true, defaultValue: "127.0.0.1", description: "数据库主机" },
  { key: "port", label: "端口", type: "NUMBER", required: true, defaultValue: "2881", description: "OceanBase端口" },
  { key: "database", label: "数据库 / 服务名", type: "TEXT", required: true, defaultValue: "", description: "Oracle模式租户服务名" },
  { key: "username", label: "用户名", type: "TEXT", required: true, defaultValue: "", description: "可包含租户及集群后缀" },
  { key: "password", label: "密码", type: "PASSWORD", required: false, defaultValue: "", description: "密码" },
  { key: "schema", label: "默认Schema", type: "TEXT", required: false, defaultValue: "", description: "默认Schema" },
  { key: "timeoutSeconds", label: "连接超时（秒）", type: "NUMBER", required: true, defaultValue: "10", description: "连接超时" }
] }];
const systems = [{ id: "system-demo", name: "核心系统", revision: "1" }];
const environments = [{ id: "environment-dev", systemId: "system-demo", name: "DEV", revision: "1" }];
const profiles = [{ id: "c5d49b11-47bc-4c64-a31e-a17633e68a73", providerId: "mysql", name: "本地开发库", environmentId: "environment-dev", revision: "1",
  settings: { host: "127.0.0.1", port: "3306", database: "eastwealthcrawler", username: "root", timeoutSeconds: "10" }, rememberPassword: true }];
let editorSequence = 0;
const editorProfiles = new Map<string, string>();
const transactionDirtyEditors = new Set<string>();
const mockJdbcSlots: Array<Record<string, unknown>> = Array.from({ length: 10 }, (_, index) => ({
  slotId: `mock-jdbc-slot-${index + 1}`, slotNumber: index + 1, stateVersion: 1,
  state: "idle", physicalConnected: index === 0, overLimit: false, historyCount: 0,
  connectionId: index === 0 ? "mock-jdbc-idle-0001" : undefined,
  profileId: index === 0 ? profiles[0].id : undefined,
  profileName: index === 0 ? profiles[0].name : undefined,
  providerId: index === 0 ? profiles[0].providerId : undefined,
  databaseName: index === 0 ? profiles[0].settings.database : undefined,
  transactionDirty: false, createdAt: index === 0 ? Date.now() - 60_000 : undefined,
  lastActiveAt: index === 0 ? Date.now() - 5_000 : undefined
}));
const mockJdbcExecutions = new Map<string, Array<Record<string, unknown>>>();
const mockSettings: Record<string, string> = {
  "ui.theme": "system",
  "result.maxRows": "1000",
  "result.streamBatchRows": "100",
  "result.clobMaxCharacters": "10000",
  "result.columnLayoutScope": "result",
  "result.copyHeaderOnDoubleClick": "true",
  "result.copySeparator": "comma",
  "result.headerSortingEnabled": "true",
  "result.headerFilteringEnabled": "true",
  "result.zebraStripesEnabled": "false",
  "result.compareHighlightMode": "identical",
  "result.compareScope": "record",
  "result.compareCaseSensitive": "false",
  "result.scrollOptimizationBufferScreens": "1",
  "connection.maxActiveSessions": "10",
  "connection.idleTimeoutMinutes": "10",
  "editor.completionCandidateLimit": "100",
  "editor.completionPreciseMatchingEnabled": "false",
  "editor.completionSnippets": "[]",
  "editor.minimapEnabled": "true",
  "editor.wordWrapEnabled": "false",
  "editor.sqlDiagnosticsEnabled": "true",
  "editor.dangerousStatementWarningEnabled": "true",
  "editor.objectInspectorOpacity": "100",
  "appearance.colorSchemes": serializeColorSchemeSettings(DEFAULT_COLOR_SCHEMES),
  "keyboard.shortcuts": serializeShortcutBindings(DEFAULT_SHORTCUT_BINDINGS),
};

function metadata(payload: Record<string, unknown>): unknown[] {
  if (payload.kind === "root") return [{ id: "catalog-demo", label: "eastwealthcrawler", kind: "catalog", leaf: false, catalog: "eastwealthcrawler" }];
  if (payload.kind === "catalog") return [{ id: "group-tables", label: "表", kind: "group", leaf: false, catalog: payload.catalog, objectType: "TABLE" }];
  if (payload.kind === "group") return ["customer", "order_item", "product"].map((name) => ({ id: `table-${name}`, label: name, kind: "object", leaf: false, catalog: payload.catalog, name, objectType: "TABLE" }));
  if (payload.kind === "object") return ["id", "name", "created_at"].map((name) => ({ id: `${payload.id}-${name}`, label: name, kind: "column", leaf: true, catalog: payload.catalog, name, detail: "VARCHAR(255)" }));
  return [];
}

export const developmentMockRequest: MockRequestHandler = async (type, payload, emit) => {
  if (type === "app.bootstrap") return { providers, systems, environments, profiles, recentFiles: [], settings: { ...mockSettings } };
  if (type === "settings.update") {
    const key = String(payload.key ?? "");
    const value = String(payload.value ?? "");
    mockSettings[key] = value;
    if (key === "connection.maxActiveSessions") {
      const maximum = Math.max(1, Math.min(100, Number(value)));
      for (let number = 1; number <= maximum; number++) {
        if (!mockJdbcSlots.some((slot) => slot.slotNumber === number)) mockJdbcSlots.push({
          slotId: `mock-jdbc-slot-${number}`, slotNumber: number, stateVersion: 1, state: "idle",
          physicalConnected: false, overLimit: false, historyCount: 0, transactionDirty: false
        });
      }
      for (let index = mockJdbcSlots.length - 1; index >= 0; index--) {
        const slot = mockJdbcSlots[index];
        if (Number(slot.slotNumber) <= maximum) { slot.overLimit = false; continue; }
        if (slot.physicalConnected) slot.overLimit = true; else mockJdbcSlots.splice(index, 1);
      }
      mockJdbcSlots.sort((left, right) => Number(left.slotNumber) - Number(right.slotNumber));
    }
    return { key, value };
  }
  if (type === "connection.catalog") return { systems, environments, profiles };
  if (type === "connection.system.create") { const value = { id: crypto.randomUUID(), name: String(payload.name), revision: String(Date.now()) }; systems.push(value); return value; }
  if (type === "connection.system.update") { const value = systems.find((item) => item.id === payload.id); if (value) { value.name = String(payload.name); value.revision = String(Date.now()); } return value; }
  if (type === "connection.system.delete") { const index = systems.findIndex((item) => item.id === payload.id); if (index >= 0) { const systemId = systems[index].id; systems.splice(index, 1); for (let i = environments.length - 1; i >= 0; i--) if (environments[i].systemId === systemId) environments.splice(i, 1); } return { deleted: true }; }
  if (type === "connection.environment.create") { const value = { id: crypto.randomUUID(), systemId: String(payload.systemId), name: String(payload.name), revision: String(Date.now()) }; environments.push(value); return value; }
  if (type === "connection.environment.update") { const value = environments.find((item) => item.id === payload.id); if (value) { value.name = String(payload.name); value.revision = String(Date.now()); } return value; }
  if (type === "connection.environment.delete") { const index = environments.findIndex((item) => item.id === payload.id); if (index >= 0) environments.splice(index, 1); return { deleted: true }; }
  if (type === "connection.profile.create" || type === "connection.profile.update") {
    const value = { ...payload, id: String(payload.id ?? crypto.randomUUID()), revision: String(Date.now()), rememberPassword: Boolean(payload.rememberPassword) } as typeof profiles[number];
    const index = profiles.findIndex((item) => item.id === value.id); if (index >= 0) profiles[index] = value; else profiles.push(value); return value;
  }
  if (type === "connection.profile.move") {
    const value = profiles.find((item) => item.id === payload.id);
    if (value) value.environmentId = String(payload.environmentId);
    return value;
  }
  if (type === "connection.profile.clone") {
    const source = profiles.find((item) => item.id === payload.id);
    if (!source) throw new Error("数据库链接不存在或已删除");
    const suffix = /^(.*) - 副本(?: (\d+))?$/.exec(source.name.trim());
    const root = suffix?.[1]?.trim() || source.name.trim();
    const used = new Set(profiles.filter((item) => item.environmentId === source.environmentId)
      .map((item) => item.name.trim().toLocaleLowerCase()));
    let name = `${root} - 副本`;
    for (let sequence = 2; used.has(name.toLocaleLowerCase()); sequence++) name = `${root} - 副本 ${sequence}`;
    const profile = { ...source, id: crypto.randomUUID(), name, settings: { ...source.settings },
      revision: String(Date.now()) };
    profiles.push(profile);
    return { profile, passwordStatus: source.rememberPassword ? "copied" : "not-remembered" };
  }
  if (type === "connection.profile.delete") { const index = profiles.findIndex((item) => item.id === payload.id); if (index >= 0) profiles.splice(index, 1); return { deleted: true }; }
  if (type === "connection.import.template" || type === "connection.export" || type === "result.export") return {};
  if (type === "connection.import.preview") return { filename: (payload.file as File | undefined)?.name ?? "connections.xlsx",
    summary: { total: 1, created: 1, updated: 0, invalid: 0, newSystems: 1, newEnvironments: 1 },
    rows: [{ rowId: crypto.randomUUID(), sourceRow: 2, profileId: crypto.randomUUID(),
      systemName: "导入示例系统", environmentName: "DEV", name: "导入示例库", providerId: "mysql",
      settings: { host: "127.0.0.1", port: "3306", database: "demo", username: "root", timeoutSeconds: "10" },
      createsSystem: true, createsEnvironment: true, operation: "create", matchedProfileId: "",
      matchedRevision: "", errors: [], warnings: [], rememberPassword: false }] };
  if (type === "connection.import.commit") {
    const rows = Array.isArray(payload.rows) ? payload.rows as Array<Record<string, unknown>> : [];
    return { createdSystems: 1, createdEnvironments: 1,
      createdProfiles: rows.filter((row) => row.operation === "create").length,
      updatedProfiles: rows.filter((row) => row.operation === "update").length,
      rows: rows.map((row) => ({ rowId: row.rowId, sourceRow: row.sourceRow, profileId: row.profileId,
        operation: row.operation, name: row.name })) };
  }
  if (type === "connection.test") return { success: true, message: "连接成功", serverVersion: "MySQL 8.4.9" };
  if (type === "jdbc.connections.list") return { maximum: Number(mockSettings["connection.maxActiveSessions"]),
    activeCount: mockJdbcSlots.filter((item) => item.physicalConnected).length, overLimitCount: 0,
    slots: mockJdbcSlots.map((item) => ({ ...item })), generatedAt: Date.now() };
  if (type === "jdbc.connections.executions") return {
    executions: (mockJdbcExecutions.get(String(payload.slotId)) ?? []).map(({ sql: _sql, ...item }) => ({ ...item }))
  };
  if (type === "jdbc.connections.execution") {
    const execution = (mockJdbcExecutions.get(String(payload.slotId)) ?? [])
      .find((item) => item.executionId === payload.executionId);
    if (!execution) throw new Error("SQL 执行记录不存在或已经过期");
    return { execution: { ...execution } };
  }
  if (type === "jdbc.connections.probe") {
    const connection = mockJdbcSlots.find((item) => item.slotId === payload.slotId && item.physicalConnected);
    if (!connection) throw new Error("该槽位当前没有物理 JDBC 连接");
    connection.state = "idle"; connection.stateVersion = Number(connection.stateVersion) + 1;
    connection.lastProbeLatencyMs = 8; connection.lastActiveAt = Date.now(); connection.message = "探活成功";
    emit("jdbc.connections.changed", { updatedAt: Date.now() });
    return { slot: { ...connection } };
  }
  if (type === "jdbc.connections.abort") {
    const connection = mockJdbcSlots.find((item) => item.slotId === payload.slotId && item.physicalConnected);
    if (!connection) throw new Error("该槽位当前没有物理 JDBC 连接");
    connection.state = "disconnected"; connection.stateVersion = Number(connection.stateVersion) + 1;
    connection.physicalConnected = false; connection.disconnectedAt = Date.now(); connection.message = "连接已强制断开";
    if (connection.editorId) emit("jdbc.connectionAborted", { editorId: connection.editorId,
      executionId: connection.executionId, transactionLost: connection.transactionDirty,
      resultChangesLost: false, message: "连接已被任务管理器强制断开" });
    const history = mockJdbcExecutions.get(String(connection.slotId)) ?? [];
    const running = history.find((item) => item.executionId === connection.executionId && item.status === "running");
    if (running) { running.status = "connection-aborted"; running.completedAt = Date.now(); running.durationMs = 0; }
    emit("jdbc.connections.changed", { updatedAt: Date.now() });
    return { accepted: true, slot: { ...connection } };
  }
  if (type === "jdbc.connections.cleanup") {
    let clearedExecutions = 0;
    for (const [slotId, history] of mockJdbcExecutions) {
      const active = history.filter((item) => item.status === "running");
      clearedExecutions += history.length - active.length; mockJdbcExecutions.set(slotId, active);
    }
    for (const slot of mockJdbcSlots) {
      slot.historyCount = (mockJdbcExecutions.get(String(slot.slotId)) ?? []).length;
      if (!slot.physicalConnected && (slot.state === "disconnected" || slot.state === "error")) {
        slot.state = "idle"; slot.connectionId = undefined; slot.message = undefined; slot.disconnectedAt = undefined;
      }
      slot.stateVersion = Number(slot.stateVersion) + 1;
    }
    emit("jdbc.connections.changed", { updatedAt: Date.now() });
    return { clearedExecutions, slots: mockJdbcSlots.map((item) => ({ ...item })) };
  }
  if (type === "editor.create") { const profile = profiles.find((item) => item.id === payload.profileId); const id = crypto.randomUUID();
    if (profile) editorProfiles.set(id, profile.id);
    return { id, title: `查询 ${++editorSequence}`, connection: profile, connectionState: profile ? "suspended" : "unbound" }; }
  if (type === "editor.bind") { const profile = profiles.find((item) => item.id === payload.profileId);
    if (profile) editorProfiles.set(String(payload.editorId), profile.id);
    return { connection: profile, connectionState: "suspended" }; }
  if (type === "editor.unbind") {
    const editorId = String(payload.editorId);
    editorProfiles.delete(editorId); transactionDirtyEditors.delete(editorId);
    return { connectionState: "unbound" };
  }
  if (type === "metadata.completionNamespaces") {
    const profileId = String(payload.profileId ?? editorProfiles.get(String(payload.editorId)) ?? profiles[0]?.id ?? "");
    return { providerId: "mysql", sourceProfileId: profileId, namespaces: [
      { key: "catalog:eastwealthcrawler", catalog: "eastwealthcrawler", schema: "", label: "eastwealthcrawler",
        kind: "catalog", current: true, system: false },
      { key: "catalog:information_schema", catalog: "information_schema", schema: "", label: "information_schema",
        kind: "catalog", current: false, system: true }
    ] };
  }
  if (type === "metadata.completionSnapshot") {
    const debugWindow = window as Window & { __DBSTUDIO_MOCK_COUNTS__?: Record<string, number> };
    const counts = debugWindow.__DBSTUDIO_MOCK_COUNTS__ ?? (debugWindow.__DBSTUDIO_MOCK_COUNTS__ = {});
    counts[type] = (counts[type] ?? 0) + 1;
    const profileId = String(payload.profileId ?? editorProfiles.get(String(payload.editorId)) ?? profiles[0]?.id ?? "");
    emit("metadata.completionProgress", { loadId: payload.loadId, phase: "discovering", completed: 1, total: 1,
      message: "已扫描 eastwealthcrawler", sourceProfileId: profileId, environmentId: "environment-dev" });
    emit("metadata.completionProgress", { loadId: payload.loadId, phase: "loading", completed: 3, total: 3,
      message: "eastwealthcrawler.product", sourceProfileId: profileId, environmentId: "environment-dev" });
    const tableNames = ["customer", "order_item", "product", "sales_order", "sales_order_item"];
    const columns: Record<string, string[]> = {
      sales_order: ["order_id", "customer_id", "created_at"],
      sales_order_item: ["order_id", "product_id", "quantity"]
    };
    return { formatVersion: 1, providerId: "mysql", sourceProfileId: profileId,
      generatedAt: new Date().toISOString(), defaultNamespaceKey: "catalog:eastwealthcrawler",
      selectedNamespaceKeys: ["catalog:eastwealthcrawler"], namespaces: [{ key: "catalog:eastwealthcrawler",
        catalog: "eastwealthcrawler", schema: "", label: "eastwealthcrawler", objects: tableNames.map((name) => ({
          name, kind: "table", remarks: "", columns: (columns[name] ?? []).map((column) => ({
            name: column, typeName: "VARCHAR", remarks: ""
          }))
        })) }] };
  }
  if (type === "sql.complete") return [];
  if (type === "sql.format") return { text: payload.text };
  if (type === "sql.diagnostics") return {
    modelVersion: payload.modelVersion,
    providerId: editorProfiles.has(String(payload.editorId)) ? "mysql" : "",
    diagnostics: []
  };
  if (type === "sql.compact") return { text: payload.text };
  if (type === "metadata.children") return metadata(payload);
  if (type === "metadata.objectSection") {
    const object = { catalog: String(payload.catalog || "eastwealthcrawler"), schema: String(payload.schema || ""),
      name: String(payload.name), type: String(payload.name).includes("view") ? "VIEW" : "TABLE", remarks: "Mock 对象",
      qualifiedName: `${String(payload.catalog || "eastwealthcrawler")}.${String(payload.name)}` };
    if (payload.section === "columns") return { object, supported: true, items: [
      { name: "id", typeName: "BIGINT", length: 20, precision: 20, scale: 0, nullable: false, defaultValue: null,
        primaryKey: true, autoIncrement: true, generated: false, remarks: "主键", ordinal: 1 },
      { name: "name", typeName: "VARCHAR", length: 255, precision: 0, scale: 0, nullable: true, defaultValue: null,
        primaryKey: false, autoIncrement: false, generated: false, remarks: "名称", ordinal: 2 }
    ] };
    if (payload.section === "indexes") return { object, supported: object.type !== "VIEW", items: [
      { name: "PRIMARY", primary: true, unique: true, type: "BTREE", status: "VALID", visible: true,
        partitioned: false, tablespace: "", columns: [{ name: "id", expression: "", direction: "ASC", ordinal: 1 }] }
    ] };
    return { object, supported: object.type !== "VIEW", items: [], nextPageToken: "" };
  }
  if (type === "metadata.objectDdlStream") {
    const object = { catalog: String(payload.catalog || "eastwealthcrawler"), schema: String(payload.schema || ""),
      name: String(payload.name), type: "TABLE", remarks: "Mock 对象", qualifiedName: `${String(payload.catalog || "eastwealthcrawler")}.${String(payload.name)}` };
    const ddl = `CREATE TABLE \`${object.name}\` (\n  \`id\` BIGINT NOT NULL PRIMARY KEY,\n  \`name\` VARCHAR(255)\n);\n-- DDL_END`;
    return { events: [{ type: "begin", object }, { type: "chunk", sequence: 0, text: ddl },
      { type: "complete", characters: ddl.length, bytes: new TextEncoder().encode(ddl).length }] };
  }
  if (type === "metadata.generateQuery") return { sql: `SELECT * FROM \`${payload.name}\` LIMIT 1000;` };
  if (type === "history.list") return [];
  if (type === "query.execute") {
    const editorId = String(payload.editorId); const executionId = crypto.randomUUID();
    let jdbc = mockJdbcSlots.find((item) => item.editorId === editorId && item.physicalConnected);
    if (!jdbc) {
      const profile = profiles.find((item) => item.id === editorProfiles.get(editorId)) ?? profiles[0];
      jdbc = mockJdbcSlots.find((item) => !item.physicalConnected && item.state === "idle") ?? mockJdbcSlots[0];
      Object.assign(jdbc, { connectionId: crypto.randomUUID(), physicalConnected: true,
        profileId: profile.id, profileName: profile.name, providerId: profile.providerId,
        databaseName: profile.settings.database, state: "busy", editorId,
        editorTitle: "当前查询", executionId, transactionDirty: false,
        createdAt: Date.now(), lastActiveAt: Date.now() });
    } else {
      jdbc.state = "busy"; jdbc.editorId = editorId; jdbc.executionId = executionId;
      jdbc.stateVersion = Number(jdbc.stateVersion) + 1;
    }
    const history = mockJdbcExecutions.get(String(jdbc.slotId)) ?? [];
    history.unshift({ executionId, workspaceId: "mock-workspace", workspaceName: "Mock 工作区",
      editorId, editorTitle: "当前查询", profileId: jdbc.profileId, profileName: jdbc.profileName,
      providerId: jdbc.providerId, databaseName: jdbc.databaseName, schemaName: "",
      startedAt: Date.now(), status: "running", rowCount: 0, sql: String(payload.text ?? "") });
    history.splice(10); mockJdbcExecutions.set(String(jdbc.slotId), history);
    jdbc.historyCount = history.length; jdbc.lastExecutionAt = history[0].startedAt;
    emit("jdbc.connections.changed", { updatedAt: Date.now() });
    const sqlText = String(payload.text ?? "");
    const wideResult = sqlText.includes("wide_result");
    const specialResult = /\bcolor_scheme_test\b/i.test(sqlText);
    const resultColumns = specialResult ? ["id", "status", "payload"]
      : wideResult ? Array.from({ length: 30 }, (_, index) => `column_${index + 1}`) : ["id", "name"];
    const resultDetails = specialResult ? resultColumns.map((label, index) => ({
      label, name: label, remarks: "", catalog: "demo", schema: "", table: "sample",
      typeName: index === 0 ? "BIGINT" : index === 1 ? "VARCHAR" : "VARBINARY",
      jdbcType: index === 0 ? -5 : index === 1 ? 12 : -3, quotedLabel: `\`${label}\``
    })) : wideResult ? resultColumns.map((label, index) => ({
      label, name: label,
      remarks: index === 0
        ? "用于验证字段筛选宽度约束的超长中文注释 Long field remark that must never expand the selector dropdown"
        : "",
      catalog: "demo", schema: "",
      table: "sample", typeName: index === 0 ? "BIGINT" : "VARCHAR",
      jdbcType: index === 0 ? -5 : 12, quotedLabel: `\`${label}\``
    })) : [
      { label: "id", name: "id", remarks: "记录编号", catalog: "demo", schema: "", table: "sample", typeName: "BIGINT", jdbcType: -5, quotedLabel: "`id`" },
      { label: "name", name: "name", remarks: "产品名称", catalog: "demo", schema: "", table: "sample", typeName: "VARCHAR", jdbcType: 12, quotedLabel: "`name`" }
    ];
    window.setTimeout(() => {
      emit("editor.connectionState", { editorId, state: "active" });
      emit("query.started", { editorId, executionId });
      const editableForUpdate = /\bfor\s+update\b/i.test(String(payload.text ?? ""));
      if (editableForUpdate) transactionDirtyEditors.add(editorId);
      emit("query.resultMeta", { editorId, executionId, resultIndex: 0, sql: payload.text, type: "QUERY", columns: resultColumns,
        columnDetails: resultDetails, mutationTarget: { qualifiedName: "`demo`.`sample`", columns: [
          { resultIndex: 0, name: "id", quotedName: "`id`", jdbcType: -5,
            typeFamily: "number", editable: true, nullable: false, autoIncrement: true },
          { resultIndex: 1, name: "name", quotedName: "`name`", jdbcType: 12,
            typeFamily: "text", editable: true, nullable: true }
        ], uniqueKeys: [{ name: "PRIMARY", primary: true, resultColumnIndices: [0] }],
          editableForUpdate, mode: editableForUpdate ? "editable" : "readOnly",
          reasonCode: editableForUpdate ? "" : "FOR_UPDATE_REQUIRED",
          reason: editableForUpdate ? "" : "需要显式执行单表 FOR UPDATE 查询",
          lockMode: editableForUpdate ? "WAIT" : "NONE", updateSupported: editableForUpdate,
          insertSupported: editableForUpdate, deleteSupported: editableForUpdate },
        updateCount: -1, truncated: false, durationMs: 0 });
      const rows = specialResult
        ? Array.from({ length: 20 }, (_, index) => [String(index + 1), index % 2 ? null : "active", index % 2 ? `0xA${index.toString(16).padStart(3, "0")}` : "0xA1B2"])
        : Array.from({ length: 200 }, (_, index) => wideResult
          ? resultColumns.map((_, column) => column === 0 ? String(index + 1) : `R${index + 1} C${column + 1}`)
          : [String(index + 1), `Apple Studio ${index + 1} ✨`]);
      const rowIds = rows.map(() => crypto.randomUUID());
      emit("query.rows", { editorId, executionId, resultIndex: 0, rowIds: rowIds.slice(0, 100), rows: rows.slice(0, 100) });
      emit("query.rows", { editorId, executionId, resultIndex: 0, rowIds: rowIds.slice(100), rows: rows.slice(100) });
      emit("query.resultComplete", { editorId, executionId, resultIndex: 0, durationMs: 38, truncated: true });
      emit("query.executionComplete", { editorId, executionId, cancelled: false, failed: false, durationMs: 38,
        transactionDirty: transactionDirtyEditors.has(editorId), resultChangesDirty: false });
      const recorded = history.find((item) => item.executionId === executionId);
      if (recorded) { recorded.status = "success"; recorded.completedAt = Date.now(); recorded.durationMs = 38; }
      jdbc!.state = editableForUpdate ? "transaction" : "idle";
      jdbc!.transactionDirty = editableForUpdate; jdbc!.executionId = undefined;
      jdbc!.stateVersion = Number(jdbc!.stateVersion) + 1; jdbc!.lastActiveAt = Date.now();
      emit("jdbc.connections.changed", { updatedAt: Date.now() });
    }, 0);
    return { executionId };
  }
  if (type === "query.fetchRows") {
    const editorId = String(payload.editorId ?? "");
    const executionId = String(payload.executionId ?? crypto.randomUUID());
    emit("query.pageStarted", {
      editorId, executionId, resultIndex: Number(payload.resultIndex ?? 0)
    });
    const offset = Number(payload.offset ?? 0); const limit = Number(payload.limit ?? 100);
    const total = 350; const end = Math.min(total, offset + limit);
    const rows = Array.from({ length: Math.max(0, end - offset) }, (_, index) => {
      const id = offset + index + 1; return [String(id), `Apple Studio ${id} ✨`];
    });
    return { executionId, resultIndex: Number(payload.resultIndex ?? 0), offset, rows,
      hasMore: end < total, nextOffset: end, cancelled: false };
  }
  if (type === "query.cloneLargeValues") {
    const sources = Array.isArray(payload.sources) ? payload.sources as Array<Record<string, unknown>> : [];
    return { values: sources.map((source) => ({
      cloneId: String(source.cloneId), columnIndex: Number(source.columnIndex),
      token: crypto.randomUUID(), size: 0, typeFamily: "blob"
    })) };
  }
  if (type === "query.applyChanges") {
    transactionDirtyEditors.add(String(payload.editorId));
    if (Array.isArray(payload.operations)) {
      return { appliedOperationIds: payload.operations.map((operation: any) => operation.operationId),
        rowPatches: payload.operations.map((operation: any) => ({ operationId: operation.operationId,
          kind: operation.kind, rowIndex: operation.rowIndex ?? -1,
          rowId: operation.kind === "insert" ? crypto.randomUUID() : operation.rowId,
          row: operation.kind === "insert" ? ["201", "新增记录"] : operation.kind === "update"
            ? [String(Number(operation.rowIndex ?? 0) + 1),
              operation.values?.find((item: any) => item.columnIndex === 1)?.value?.value ?? "已更新"] : [] })),
        transactionDirty: true, resultChangesDirty: true };
    }
    return { rows: payload.rows, transactionDirty: true, resultChangesDirty: true };
  }
  if (type === "query.previewChanges") return { previews: (payload.operations as any[] ?? []).map((operation) => ({
    operationId: operation.operationId,
    sql: operation.kind === "insert" ? "INSERT INTO `demo`.`sample` (`id`, `name`) VALUES (?, ?)"
      : operation.kind === "delete" ? "DELETE FROM `demo`.`sample` WHERE `id` = ?"
        : "UPDATE `demo`.`sample` SET `name` = ? WHERE `id` = ?",
    binds: operation.values?.map((item: any) => item.value?.kind === "null" ? "NULL" : item.value?.value ?? "DEFAULT") ?? []
  })) };
  if (type === "transaction.commit" || type === "transaction.rollback") {
    const message = type === "transaction.commit" ? "事务已提交" : "事务已回滚";
    transactionDirtyEditors.delete(String(payload.editorId));
    emit("transaction.status", { editorId: payload.editorId, dirty: false,
      resultChangesDirty: false, message });
    return { dirty: false, resultChangesDirty: false, message };
  }
  if (type === "query.cancel") return { cancelled: true };
  return {};
};
