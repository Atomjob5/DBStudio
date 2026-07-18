import type { MockRequestHandler } from "./rpc";

const providers = [{ id: "mysql", displayName: "MySQL", capabilities: ["TABLES", "VIEWS", "PROCEDURES"], fields: [
  { key: "host", label: "主机", type: "TEXT", required: true, defaultValue: "127.0.0.1", description: "数据库主机" },
  { key: "port", label: "端口", type: "NUMBER", required: true, defaultValue: "3306", description: "端口" },
  { key: "database", label: "数据库", type: "TEXT", required: false, defaultValue: "", description: "默认数据库" },
  { key: "username", label: "用户名", type: "TEXT", required: true, defaultValue: "root", description: "用户名" },
  { key: "password", label: "密码", type: "PASSWORD", required: false, defaultValue: "", description: "密码" },
  { key: "timeoutSeconds", label: "连接超时（秒）", type: "NUMBER", required: true, defaultValue: "10", description: "连接超时" }
] }];
const systems = [{ id: "system-demo", name: "核心系统", revision: "1" }];
const environments = [{ id: "environment-dev", systemId: "system-demo", name: "DEV", revision: "1" }];
const profiles = [{ id: "c5d49b11-47bc-4c64-a31e-a17633e68a73", providerId: "mysql", name: "本地开发库", environmentId: "environment-dev", revision: "1",
  settings: { host: "127.0.0.1", port: "3306", database: "eastwealthcrawler", username: "root", timeoutSeconds: "10" }, rememberPassword: true }];
let editorSequence = 0;

function metadata(payload: Record<string, unknown>): unknown[] {
  if (payload.kind === "root") return [{ id: "catalog-demo", label: "eastwealthcrawler", kind: "catalog", leaf: false, catalog: "eastwealthcrawler" }];
  if (payload.kind === "catalog") return [{ id: "group-tables", label: "表", kind: "group", leaf: false, catalog: payload.catalog, objectType: "TABLE" }];
  if (payload.kind === "group") return ["customer", "order_item", "product"].map((name) => ({ id: `table-${name}`, label: name, kind: "object", leaf: false, catalog: payload.catalog, name, objectType: "TABLE" }));
  if (payload.kind === "object") return ["id", "name", "created_at"].map((name) => ({ id: `${payload.id}-${name}`, label: name, kind: "column", leaf: true, catalog: payload.catalog, name, detail: "VARCHAR(255)" }));
  return [];
}

export const developmentMockRequest: MockRequestHandler = async (type, payload, emit) => {
  if (type === "app.bootstrap") return { providers, systems, environments, profiles, recentFiles: [], settings: { "ui.theme": "system", "result.maxRows": "1000", "result.streamBatchRows": "100", "result.columnLayoutScope": "result", "result.copyHeaderOnDoubleClick": "true", "result.copySeparator": "comma", "connection.maxActiveSessions": "10", "connection.idleTimeoutMinutes": "10" } };
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
  if (type === "editor.create") { const profile = profiles.find((item) => item.id === payload.profileId);
    return { id: crypto.randomUUID(), title: `查询 ${++editorSequence}`, connection: profile, connectionState: profile ? "suspended" : "unbound" }; }
  if (type === "editor.bind") return { connection: profiles.find((item) => item.id === payload.profileId), connectionState: "suspended" };
  if (type === "editor.unbind") return { connectionState: "unbound" };
  if (type === "sql.complete") return [];
  if (type === "sql.format") return { text: payload.text };
  if (type === "metadata.children") return metadata(payload);
  if (type === "metadata.generateQuery") return { sql: `SELECT * FROM \`${payload.name}\` LIMIT 1000;` };
  if (type === "history.list") return [];
  if (type === "query.execute") {
    const editorId = String(payload.editorId); const executionId = crypto.randomUUID();
    window.setTimeout(() => {
      emit("editor.connectionState", { editorId, state: "active" });
      emit("query.started", { editorId, executionId });
      emit("query.resultMeta", { editorId, resultIndex: 0, sql: payload.text, type: "QUERY", columns: ["id", "name"],
        columnDetails: [
          { label: "id", name: "id", remarks: "记录编号", catalog: "demo", schema: "", table: "sample", typeName: "BIGINT" },
          { label: "name", name: "name", remarks: "产品名称", catalog: "demo", schema: "", table: "sample", typeName: "VARCHAR" }
        ], updateCount: -1, truncated: false, durationMs: 0 });
      const rows = Array.from({ length: 200 }, (_, index) => [String(index + 1), `Apple Studio ${index + 1} ✨`]);
      emit("query.rows", { editorId, resultIndex: 0, rows: rows.slice(0, 100) });
      emit("query.rows", { editorId, resultIndex: 0, rows: rows.slice(100) });
      emit("query.resultComplete", { editorId, resultIndex: 0, durationMs: 38, truncated: true });
      emit("query.executionComplete", { editorId, executionId, cancelled: false, failed: false, durationMs: 38, transactionDirty: false });
    }, 0);
    return { executionId };
  }
  if (type === "query.fetchRows") {
    const offset = Number(payload.offset ?? 0); const limit = Number(payload.limit ?? 100);
    const total = 350; const end = Math.min(total, offset + limit);
    const rows = Array.from({ length: Math.max(0, end - offset) }, (_, index) => {
      const id = offset + index + 1; return [String(id), `Apple Studio ${id} ✨`];
    });
    return { resultIndex: Number(payload.resultIndex ?? 0), offset, rows, hasMore: end < total, nextOffset: end };
  }
  return {};
};
