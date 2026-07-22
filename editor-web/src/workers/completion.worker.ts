/// <reference lib="webworker" />

import { buildCompletionIndex, resolveCompletion } from "../sqlCompletion";
import type { CompletionIndex } from "../sqlCompletion";
import type { CompletionCacheSummary, CompletionSnapshot } from "../types";
import type { CompletionWorkerRequest, CompletionWorkerResponse } from "../completion/workerProtocol";
import { CompletionDocumentMirror } from "../completion/documentMirror";

interface StoredSnapshot {
  cacheKey: string;
  snapshot: CompletionSnapshot;
  summary: CompletionCacheSummary;
}

const DATABASE_NAME = "dbstudio-sql-completion";
const STORE_NAME = "snapshots";
const DATABASE_VERSION = 1;
const FORMAT_VERSION = 1;
const MAX_MEMORY_INDEXES = 2;
const indexes = new Map<string, CompletionIndex>();
const documents = new CompletionDocumentMirror(20);

self.onmessage = (event: MessageEvent<CompletionWorkerRequest>) => {
  void handle(event.data).then(
    (value) => post({ id: event.data.id, value }),
    (error: unknown) => post({ id: event.data.id, error: workerError(error) })
  );
};

async function handle(request: CompletionWorkerRequest) {
  if (request.type === "model.sync") {
    documents.sync(request.modelKey, request.version, request.text);
    return undefined;
  }
  if (request.type === "model.change") {
    documents.change(request.modelKey, request.fromVersion, request.toVersion, request.changes);
    return undefined;
  }
  if (request.type === "model.release") {
    documents.release(request.modelKey);
    return undefined;
  }
  if (request.type === "inspect") {
    const stored = await loadValid(request.cacheKey, request.providerId);
    if (!stored) return undefined;
    remember(request.cacheKey, buildCompletionIndex(stored.snapshot));
    return stored.summary;
  }
  if (request.type === "refresh") {
    const response = await fetch(request.url, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: { "Content-Type": "application/json", "X-DBStudio-Client-Id": request.clientId },
      body: JSON.stringify(request.body)
    });
    const data = await response.json().catch(() => ({})) as CompletionSnapshot & { message?: string; code?: string };
    if (!response.ok) throw Object.assign(new Error(data.message || `补全快照请求失败（${response.status}）`), { code: data.code });
    const snapshot = validateSnapshot(data, request.providerId);
    const index = buildCompletionIndex(snapshot);
    const summary = summarize(snapshot);
    const stored: StoredSnapshot = { cacheKey: request.cacheKey, snapshot, summary };
    await put(stored);
    remember(request.cacheKey, index);
    return summary;
  }
  if (request.type === "complete") {
    const sql = documents.read(request.modelKey, request.modelVersion);
    if (!Number.isInteger(request.cursorOffset) || request.cursorOffset < 0 || request.cursorOffset > sql.length) {
      throw Object.assign(new Error("补全光标位置与编辑器模型不一致"), { code: "MODEL_OUT_OF_SYNC" });
    }
    let index = indexes.get(request.cacheKey);
    if (!index) {
      const stored = await loadValid(request.cacheKey, request.providerId);
      if (stored) {
        index = buildCompletionIndex(stored.snapshot);
        remember(request.cacheKey, index);
      }
    } else touch(request.cacheKey, index);
    return resolveCompletion(index, { providerId: request.providerId, sql, cursorOffset: request.cursorOffset,
      prefix: request.prefix, limit: request.limit });
  }
  if (request.type === "clear") {
    indexes.clear();
    await clearStore();
    return undefined;
  }
  const records = await allRecords();
  const stats = { environmentCount: 0, suggestionCount: 0, estimatedBytes: 0 };
  for (const record of records) {
    try {
      validateSnapshot(record.snapshot, String(record.snapshot?.providerId ?? ""));
      if (!record.summary || record.summary.providerId !== record.snapshot.providerId
        || !Number.isFinite(record.summary.objectCount) || !Number.isFinite(record.summary.columnCount)
        || !Number.isFinite(record.summary.estimatedBytes)) throw new Error("补全缓存统计损坏");
    } catch {
      await remove(record.cacheKey);
      indexes.delete(record.cacheKey);
      continue;
    }
    stats.environmentCount += 1;
    stats.suggestionCount += record.summary.objectCount + record.summary.columnCount;
    stats.estimatedBytes += record.summary.estimatedBytes;
  }
  return stats;
}

function validateSnapshot(value: unknown, providerId: string): CompletionSnapshot {
  if (!value || typeof value !== "object") throw new Error("补全缓存数据格式无效");
  const snapshot = value as CompletionSnapshot;
  if (snapshot.formatVersion !== FORMAT_VERSION || snapshot.providerId !== providerId
    || !Array.isArray(snapshot.selectedNamespaceKeys) || !Array.isArray(snapshot.namespaces)) {
    throw new Error("补全缓存版本或数据库类型不匹配");
  }
  for (const namespace of snapshot.namespaces) {
    if (!namespace || typeof namespace.key !== "string" || !Array.isArray(namespace.objects)) throw new Error("补全命名空间数据损坏");
    for (const object of namespace.objects) {
      if (!object || typeof object.name !== "string" || !Array.isArray(object.columns)) throw new Error("补全对象数据损坏");
      if (object.kind !== "table" && object.kind !== "view") throw new Error("补全对象类型无效");
      for (const column of object.columns) {
        if (!column || typeof column.name !== "string" || typeof column.typeName !== "string"
          || typeof column.remarks !== "string") throw new Error("补全字段数据损坏");
      }
    }
  }
  return snapshot;
}

function summarize(snapshot: CompletionSnapshot): CompletionCacheSummary {
  let objectCount = 0;
  let columnCount = 0;
  for (const namespace of snapshot.namespaces) {
    objectCount += namespace.objects.length;
    for (const object of namespace.objects) columnCount += object.columns.length;
  }
  return {
    providerId: snapshot.providerId,
    sourceProfileId: snapshot.sourceProfileId,
    generatedAt: snapshot.generatedAt,
    selectedNamespaceKeys: [...snapshot.selectedNamespaceKeys],
    objectCount,
    columnCount,
    estimatedBytes: new Blob([JSON.stringify(snapshot)]).size
  };
}

async function loadValid(cacheKey: string, providerId: string): Promise<StoredSnapshot | undefined> {
  const stored = await get(cacheKey);
  if (!stored) return undefined;
  try {
    validateSnapshot(stored.snapshot, providerId);
    if (!stored.summary || stored.summary.providerId !== providerId
      || !Number.isFinite(stored.summary.objectCount) || !Number.isFinite(stored.summary.columnCount)
      || !Number.isFinite(stored.summary.estimatedBytes)) throw new Error("补全缓存统计损坏");
    return stored;
  } catch {
    await remove(cacheKey);
    indexes.delete(cacheKey);
    return undefined;
  }
}

function remember(key: string, index: CompletionIndex): void {
  indexes.delete(key);
  indexes.set(key, index);
  while (indexes.size > MAX_MEMORY_INDEXES) indexes.delete(indexes.keys().next().value as string);
}

function touch(key: string, index: CompletionIndex): void {
  indexes.delete(key);
  indexes.set(key, index);
}

function post(response: CompletionWorkerResponse): void {
  self.postMessage(response);
}

function workerError(error: unknown): { message: string; code?: string } {
  if (error instanceof Error) return { message: error.message, code: (error as Error & { code?: string }).code };
  return { message: String(error) };
}

let databasePromise: Promise<IDBDatabase> | undefined;
function database(): Promise<IDBDatabase> {
  databasePromise ??= new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(STORE_NAME)) database.createObjectStore(STORE_NAME, { keyPath: "cacheKey" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("无法打开补全缓存"));
  });
  return databasePromise;
}

async function transaction<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await database();
  return new Promise<T>((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, mode);
    const request = operation(tx.objectStore(STORE_NAME));
    let result: T;
    request.onsuccess = () => { result = request.result; };
    request.onerror = () => reject(request.error ?? new Error("补全缓存操作失败"));
    tx.oncomplete = () => resolve(result);
    tx.onabort = () => reject(tx.error ?? new Error("补全缓存事务失败"));
  });
}

function get(key: string): Promise<StoredSnapshot | undefined> {
  return transaction("readonly", (store) => store.get(key));
}

function put(value: StoredSnapshot): Promise<IDBValidKey> {
  return transaction("readwrite", (store) => store.put(value));
}

function remove(key: string): Promise<undefined> {
  return transaction("readwrite", (store) => store.delete(key)) as Promise<undefined>;
}

function clearStore(): Promise<undefined> {
  return transaction("readwrite", (store) => store.clear()) as Promise<undefined>;
}

function allRecords(): Promise<StoredSnapshot[]> {
  return transaction("readonly", (store) => store.getAll());
}
