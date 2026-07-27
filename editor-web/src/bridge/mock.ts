import type { MockRequestHandler } from "./rpc";

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

function metadata(payload: Record<string, unknown>): unknown[] {
  if (payload.kind === "root") return [{ id: "catalog-demo", label: "eastwealthcrawler", kind: "catalog", leaf: false, catalog: "eastwealthcrawler" }];
  if (payload.kind === "catalog") return [{ id: "group-tables", label: "表", kind: "group", leaf: false, catalog: payload.catalog, objectType: "TABLE" }];
  if (payload.kind === "group") return ["customer", "order_item", "product"].map((name) => ({ id: `table-${name}`, label: name, kind: "object", leaf: false, catalog: payload.catalog, name, objectType: "TABLE" }));
  if (payload.kind === "object") return ["id", "name", "created_at"].map((name) => ({ id: `${payload.id}-${name}`, label: name, kind: "column", leaf: true, catalog: payload.catalog, name, detail: "VARCHAR(255)" }));
  return [];
}

export const developmentMockRequest: MockRequestHandler = async (type, payload, emit) => {
  if (type === "app.bootstrap") return { providers, systems, environments, profiles, recentFiles: [], settings: { "ui.theme": "system", "result.maxRows": "1000", "result.streamBatchRows": "100", "result.columnLayoutScope": "result", "result.copyHeaderOnDoubleClick": "true", "result.copySeparator": "comma", "result.headerSortingEnabled": "true", "result.headerFilteringEnabled": "true", "result.scrollOptimizationEnabled": "false", "result.scrollOptimizationBufferScreens": "1", "connection.maxActiveSessions": "10", "connection.idleTimeoutMinutes": "10" } };
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
  if (type === "connection.profile.delete") { const index = profiles.findIndex((item) => item.id === payload.id); if (index >= 0) profiles.splice(index, 1); return { deleted: true }; }
  if (type === "connection.test") return { success: true, message: "连接成功", serverVersion: "MySQL 8.4.9" };
  if (type === "editor.create") { const profile = profiles.find((item) => item.id === payload.profileId); const id = crypto.randomUUID();
    if (profile) editorProfiles.set(id, profile.id);
    return { id, title: `查询 ${++editorSequence}`, connection: profile, connectionState: profile ? "suspended" : "unbound" }; }
  if (type === "editor.bind") { const profile = profiles.find((item) => item.id === payload.profileId);
    if (profile) editorProfiles.set(String(payload.editorId), profile.id);
    return { connection: profile, connectionState: "suspended" }; }
  if (type === "editor.unbind") { editorProfiles.delete(String(payload.editorId)); return { connectionState: "unbound" }; }
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
  if (type === "metadata.children") return metadata(payload);
  if (type === "metadata.generateQuery") return { sql: `SELECT * FROM \`${payload.name}\` LIMIT 1000;` };
  if (type === "history.list") return [];
  if (type === "query.execute") {
    const editorId = String(payload.editorId); const executionId = crypto.randomUUID();
    const wideResult = String(payload.text ?? "").includes("wide_result");
    const resultColumns = wideResult ? Array.from({ length: 30 }, (_, index) => `column_${index + 1}`) : ["id", "name"];
    const resultDetails = wideResult ? resultColumns.map((label, index) => ({
      label, name: label, remarks: index === 0 ? "记录编号" : "", catalog: "demo", schema: "",
      table: "sample", typeName: index === 0 ? "BIGINT" : "VARCHAR",
      jdbcType: index === 0 ? -5 : 12, quotedLabel: `\`${label}\``
    })) : [
      { label: "id", name: "id", remarks: "记录编号", catalog: "demo", schema: "", table: "sample", typeName: "BIGINT", jdbcType: -5, quotedLabel: "`id`" },
      { label: "name", name: "name", remarks: "产品名称", catalog: "demo", schema: "", table: "sample", typeName: "VARCHAR", jdbcType: 12, quotedLabel: "`name`" }
    ];
    window.setTimeout(() => {
      emit("editor.connectionState", { editorId, state: "active" });
      emit("query.started", { editorId, executionId });
      emit("query.resultMeta", { editorId, resultIndex: 0, sql: payload.text, type: "QUERY", columns: resultColumns,
        columnDetails: resultDetails, mutationTarget: { qualifiedName: "`demo`.`sample`", columns: [
          { resultIndex: 0, name: "id", quotedName: "`id`", jdbcType: -5 },
          { resultIndex: 1, name: "name", quotedName: "`name`", jdbcType: 12 }
        ], uniqueKeys: [{ name: "PRIMARY", primary: true, resultColumnIndices: [0] }] },
        updateCount: -1, truncated: false, durationMs: 0 });
      const rows = Array.from({ length: 200 }, (_, index) => wideResult
        ? resultColumns.map((_, column) => column === 0 ? String(index + 1) : `R${index + 1} C${column + 1}`)
        : [String(index + 1), `Apple Studio ${index + 1} ✨`]);
      emit("query.rows", { editorId, resultIndex: 0, rows: rows.slice(0, 100) });
      emit("query.rows", { editorId, resultIndex: 0, rows: rows.slice(100) });
      emit("query.resultComplete", { editorId, resultIndex: 0, durationMs: 38, truncated: true });
      emit("query.executionComplete", { editorId, executionId, cancelled: false, failed: false, durationMs: 38, transactionDirty: false });
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
  if (type === "query.cancel") return { cancelled: true };
  return {};
};
