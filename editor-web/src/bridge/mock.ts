import type { MockRequestHandler } from "./rpc";

const providers = [{ id: "mysql", displayName: "MySQL", capabilities: ["TABLES", "VIEWS", "PROCEDURES"], fields: [
  { key: "host", label: "主机", type: "TEXT", required: true, defaultValue: "127.0.0.1", description: "数据库主机" },
  { key: "port", label: "端口", type: "NUMBER", required: true, defaultValue: "3306", description: "端口" },
  { key: "database", label: "数据库", type: "TEXT", required: false, defaultValue: "", description: "默认数据库" },
  { key: "username", label: "用户名", type: "TEXT", required: true, defaultValue: "root", description: "用户名" },
  { key: "password", label: "密码", type: "PASSWORD", required: false, defaultValue: "", description: "密码" },
  { key: "timeoutSeconds", label: "连接超时（秒）", type: "NUMBER", required: true, defaultValue: "10", description: "连接超时" }
] }];
const profiles = [{ id: "c5d49b11-47bc-4c64-a31e-a17633e68a73", providerId: "mysql", name: "本地开发库", settings: { host: "127.0.0.1", port: "3306", database: "eastwealthcrawler", username: "root", timeoutSeconds: "10" }, rememberPassword: true }];
let editorSequence = 0;

function metadata(payload: Record<string, unknown>): unknown[] {
  if (payload.kind === "root") return [{ id: "catalog-demo", label: "eastwealthcrawler", kind: "catalog", leaf: false, catalog: "eastwealthcrawler" }];
  if (payload.kind === "catalog") return [{ id: "group-tables", label: "表", kind: "group", leaf: false, catalog: payload.catalog, objectType: "TABLE" }];
  if (payload.kind === "group") return ["customer", "order_item", "product"].map((name) => ({ id: `table-${name}`, label: name, kind: "object", leaf: false, catalog: payload.catalog, name, objectType: "TABLE" }));
  if (payload.kind === "object") return ["id", "name", "created_at"].map((name) => ({ id: `${payload.id}-${name}`, label: name, kind: "column", leaf: true, catalog: payload.catalog, name, detail: "VARCHAR(255)" }));
  return [];
}

export const developmentMockRequest: MockRequestHandler = async (type, payload, emit) => {
  if (type === "app.bootstrap") return { providers, profiles, recentFiles: [], settings: { "ui.theme": "system", "result.maxRows": "1000", "result.streamBatchRows": "100" } };
  if (type === "connection.test") return { success: true, message: "连接成功", serverVersion: "MySQL 8.4.9" };
  if (type === "connection.connect") return { ...payload, id: payload.id ?? profiles[0].id, rememberPassword: Boolean(payload.rememberPassword) };
  if (type === "editor.create") return { id: crypto.randomUUID(), title: `查询 ${++editorSequence}` };
  if (type === "sql.complete") return [];
  if (type === "sql.format") return { text: payload.text };
  if (type === "metadata.children") return metadata(payload);
  if (type === "metadata.generateQuery") return { sql: `SELECT * FROM \`${payload.name}\` LIMIT 1000;` };
  if (type === "history.list") return [];
  if (type === "query.execute") {
    const editorId = String(payload.editorId); const executionId = crypto.randomUUID();
    window.setTimeout(() => {
      emit("query.started", { editorId, executionId });
      emit("query.resultMeta", { editorId, resultIndex: 0, sql: payload.text, type: "QUERY", columns: ["id", "name"], updateCount: -1, truncated: false, durationMs: 0 });
      const rows = Array.from({ length: 200 }, (_, index) => [String(index + 1), `Apple Studio ${index + 1} ✨`]);
      emit("query.rows", { editorId, resultIndex: 0, rows: rows.slice(0, 100) });
      emit("query.rows", { editorId, resultIndex: 0, rows: rows.slice(100) });
      emit("query.resultComplete", { editorId, resultIndex: 0, durationMs: 38, truncated: false });
      emit("query.executionComplete", { editorId, executionId, cancelled: false, failed: false, durationMs: 38, transactionDirty: false });
    }, 0);
    return { executionId };
  }
  return {};
};
