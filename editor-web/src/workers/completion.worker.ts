/// <reference lib="webworker" />

import {
  applyCompletionStructure,
  buildCompletionIndex,
  createCompletionIndex,
  mergeCompletionColumns,
  resetCompletionStructure,
  resolveChangedPhysicalTable,
  resolveCompletion,
  resolveResultColumnRemarks,
  resolveSinglePhysicalTable,
  upsertCompletionObjects
} from "../sqlCompletion";
import type { CompletionIndex, CompletionObjectDelta } from "../sqlCompletion";
import type {
  CompletionCacheStats,
  CompletionCacheSummary,
  CompletionManifest,
  CompletionSnapshot,
  QueryColumn
} from "../types";
import type { CompletionWorkerRequest, CompletionWorkerResponse } from "../completion/workerProtocol";
import { CompletionDocumentMirror } from "../completion/documentMirror";

interface LegacyStoredSnapshot {
  cacheKey: string;
  snapshot: CompletionSnapshot;
  summary: CompletionCacheSummary;
}

interface StoredCompletionObject {
  key: string;
  generationKey: string;
  cacheKey: string;
  generation: string;
  namespaceKey: string;
  catalog: string;
  schema: string;
  name: string;
  kind: "table" | "view";
  remarks: string;
  columns: Array<{ name: string; remarks: string }>;
}

interface StoredTableStructure {
  key: string;
  cacheKey: string;
  schema: string;
  table: string;
  loadedAt: string;
  columns: Array<{ name: string; typeName: string; ordinal: number }>;
}

interface MemoryIndex {
  index: CompletionIndex;
  providerId: string;
  estimatedBytes: number;
}

interface StreamMetadata {
  formatVersion: 2;
  providerId: string;
  sourceProfileId: string;
  generatedAt: string;
  defaultNamespaceKey: string;
  selectedNamespaceKeys: string[];
  namespaces: Array<{ key: string; catalog: string; schema: string; label: string }>;
}

type StreamRecord =
  | { type: "begin"; generation: string; metadata: StreamMetadata }
  | { type: "objects" | "tables"; values: CompletionObjectDelta[] }
  | { type: "columns"; values: Array<{ namespaceKey: string; objectName: string;
      columns: Array<[string, string]> }> }
  | { type: "warning"; phase: string; message: string }
  | { type: "complete"; summary: CompletionCacheSummary }
  | { type: "error"; phase: string; message: string };

const DATABASE_NAME = "dbstudio-sql-completion";
const LEGACY_STORE = "snapshots";
const MANIFEST_STORE = "completionManifests";
const OBJECT_STORE = "completionObjects";
const STRUCTURE_STORE = "completionStructures";
const DATABASE_VERSION = 2;
const LEGACY_FORMAT_VERSION = 1;
const MAX_MEMORY_INDEXES = 2;
const MAX_MEMORY_BYTES = 128 * 1024 * 1024;
const indexes = new Map<string, MemoryIndex>();
const loadedStructures = new Map<string, Set<string>>();
const refreshControllers = new Map<string, AbortController>();
const enrichmentControllers = new Map<string, AbortController>();
const mutationQueues = new Map<string, Promise<unknown>>();
const documents = new CompletionDocumentMirror(20);
let invalidationEpoch = 0;

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
    return inspectCache(request.cacheKey, request.providerId);
  }
  if (request.type === "refresh") {
    if (isOracleCompatible(request.providerId)) refreshControllers.get(request.cacheKey)?.abort();
    const epoch = invalidationEpoch;
    return enqueueMutation(request.cacheKey, () => isOracleCompatible(request.providerId)
      ? refreshStreaming(request.cacheKey, request.providerId, request.url, request.clientId, request.body, epoch)
      : refreshLegacy(request.cacheKey, request.providerId, request.url, request.clientId, request.body, epoch));
  }
  if (request.type === "complete") {
    const sql = documents.read(request.modelKey, request.modelVersion);
    if (!Number.isInteger(request.cursorOffset) || request.cursorOffset < 0 || request.cursorOffset > sql.length) {
      throw Object.assign(new Error("补全光标位置与编辑器模型不一致"), { code: "MODEL_OUT_OF_SYNC" });
    }
    const entry = await completionIndex(request.cacheKey, request.providerId);
    return resolveCompletion(entry?.index, { providerId: request.providerId, sql,
      cursorOffset: request.cursorOffset, prefix: request.prefix, limit: request.limit });
  }
  if (request.type === "result-columns.resolve") {
    const entry = await completionIndex(request.cacheKey, request.providerId);
    return resolveResultColumnRemarks(entry?.index, request.providerId, request.sql, request.columns);
  }
  if (request.type === "query.enrich") {
    if (!isOracleCompatible(request.providerId)) return undefined;
    const epoch = invalidationEpoch;
    await enqueueMutation(request.cacheKey, () => enrichSingleTable(request.cacheKey,
      request.providerId, request.url, request.clientId, request.editorId, request.sql, request.columns, epoch));
    return undefined;
  }
  if (request.type === "structure.invalidate") {
    if (!isOracleCompatible(request.providerId)) return undefined;
    await enqueueMutation(request.cacheKey, () => invalidateChangedTable(
      request.cacheKey, request.providerId, request.sql));
    return undefined;
  }
  if (request.type === "clear") {
    invalidationEpoch += 1;
    for (const controller of refreshControllers.values()) controller.abort();
    for (const controller of enrichmentControllers.values()) controller.abort();
    refreshControllers.clear();
    enrichmentControllers.clear();
    await Promise.allSettled([...mutationQueues.values()]);
    indexes.clear();
    loadedStructures.clear();
    await clearAllStores();
    return undefined;
  }
  return cacheStats();
}

async function inspectCache(cacheKey: string, providerId: string): Promise<CompletionCacheSummary | undefined> {
  if (isOracleCompatible(providerId)) {
    await idbDelete(LEGACY_STORE, cacheKey);
    const manifest = await idbGet<CompletionManifest>(MANIFEST_STORE, cacheKey);
    if (!validManifest(manifest, providerId)) {
      if (manifest) await deleteCache(cacheKey);
      return undefined;
    }
    await loadStreamingIndex(manifest);
    if (!refreshControllers.has(cacheKey)) {
      void deleteInactiveGenerations(cacheKey, manifest.activeGeneration).catch(() => undefined);
    }
    return manifestSummary(manifest);
  }
  const stored = await loadValidLegacy(cacheKey, providerId);
  if (!stored) return undefined;
  remember(cacheKey, { index: buildCompletionIndex(stored.snapshot), providerId,
    estimatedBytes: stored.summary.estimatedBytes });
  return stored.summary;
}

async function refreshLegacy(cacheKey: string, providerId: string, url: string, clientId: string,
                             body: Record<string, unknown>, epoch: number): Promise<CompletionCacheSummary> {
  ensureCurrentEpoch(epoch);
  const response = await fetch(url, {
    method: "POST", credentials: "same-origin", cache: "no-store",
    headers: { "Content-Type": "application/json", "X-DBStudio-Client-Id": clientId },
    body: JSON.stringify(body)
  });
  const data = await response.json().catch(() => ({})) as CompletionSnapshot & { message?: string; code?: string };
  if (!response.ok) throw Object.assign(new Error(data.message || `补全快照请求失败（${response.status}）`),
    { code: data.code });
  ensureCurrentEpoch(epoch);
  const snapshot = validateLegacySnapshot(data, providerId);
  const summary = summarizeLegacy(snapshot);
  await idbPut(LEGACY_STORE, { cacheKey, snapshot, summary } satisfies LegacyStoredSnapshot);
  ensureCurrentEpoch(epoch);
  remember(cacheKey, { index: buildCompletionIndex(snapshot), providerId,
    estimatedBytes: summary.estimatedBytes });
  return summary;
}

async function refreshStreaming(cacheKey: string, providerId: string, url: string, clientId: string,
                                body: Record<string, unknown>, epoch: number): Promise<CompletionCacheSummary> {
  ensureCurrentEpoch(epoch);
  await idbDelete(LEGACY_STORE, cacheKey);
  refreshControllers.get(cacheKey)?.abort();
  const controller = new AbortController();
  refreshControllers.set(cacheKey, controller);
  const previous = indexes.get(cacheKey);
  const previousManifest = await idbGet<CompletionManifest>(MANIFEST_STORE, cacheKey);
  let generation = "";
  let completed = false;
  let manifest: CompletionManifest | undefined;
  let index: CompletionIndex | undefined;
  const records = new Map<string, StoredCompletionObject>();
  try {
    const response = await fetch(url, {
      method: "POST", credentials: "same-origin", cache: "no-store", signal: controller.signal,
      headers: { "Content-Type": "application/json", "X-DBStudio-Client-Id": clientId },
      body: JSON.stringify(body)
    });
    if (!response.ok) {
      const data = await response.json().catch(() => ({})) as { message?: string; code?: string };
      throw Object.assign(new Error(data.message || `补全快照请求失败（${response.status}）`), { code: data.code });
    }
    if (!response.body) throw new Error("浏览器不支持流式补全响应");
    await readNdjson(response.body, async (record) => {
      ensureCurrentEpoch(epoch);
      if (record.type === "begin") {
        validateStreamMetadata(record.metadata, providerId);
        generation = record.generation;
        manifest = {
          ...record.metadata, cacheKey, activeGeneration: generation,
          objectCount: 0, columnCount: 0, estimatedBytes: 0
        };
        index = createCompletionIndex(record.metadata.defaultNamespaceKey, record.metadata.namespaces);
        remember(cacheKey, { index, providerId, estimatedBytes: 0 });
        return;
      }
      if (!manifest || !index || !generation) throw new Error("补全流缺少begin记录");
      if (record.type === "objects" || record.type === "tables") {
        upsertCompletionObjects(index, record.values);
        const changed: StoredCompletionObject[] = [];
        for (const value of record.values) {
          const key = objectRecordKey(cacheKey, generation, value.namespaceKey, value.name);
          const existing = records.get(key);
          const stored: StoredCompletionObject = existing ?? {
            key, generationKey: generationKey(cacheKey, generation), cacheKey, generation,
            namespaceKey: value.namespaceKey, catalog: value.catalog, schema: value.schema,
            name: value.name, kind: value.kind, remarks: value.remarks, columns: []
          };
          stored.kind = value.kind;
          stored.remarks = value.remarks;
          records.set(key, stored);
          changed.push(stored);
        }
        await idbPutMany(OBJECT_STORE, changed);
        return;
      }
      if (record.type === "columns") {
        const changed: StoredCompletionObject[] = [];
        for (const group of record.values) {
          const key = objectRecordKey(cacheKey, generation, group.namespaceKey, group.objectName);
          const stored = records.get(key);
          if (!stored) continue;
          const columns = group.columns.map(([name, remarks]) => ({ name, remarks, typeName: "" }));
          mergeCompletionColumns(index, group.namespaceKey, group.objectName, columns);
          const byName = new Map(stored.columns.map((column) => [normalize(column.name), column]));
          for (const [name, remarks] of group.columns) byName.set(normalize(name), { name, remarks });
          stored.columns = [...byName.values()];
          changed.push(stored);
        }
        await idbPutMany(OBJECT_STORE, changed);
        return;
      }
      if (record.type === "warning") {
        manifest.warning = record.message;
        return;
      }
      if (record.type === "error") throw new Error(record.message);
      if (record.type !== "complete") throw new Error("未知的补全流记录");
      const summary = validateStreamSummary(record.summary, providerId);
      manifest = { ...manifest, ...summary, cacheKey, activeGeneration: generation, formatVersion: 2,
        defaultNamespaceKey: manifest.defaultNamespaceKey, namespaces: manifest.namespaces,
        warning: summary.warning ?? manifest.warning };
      await commitManifest(manifest);
      ensureCurrentEpoch(epoch);
      loadedStructures.set(cacheKey, new Set());
      remember(cacheKey, { index, providerId, estimatedBytes: manifest.estimatedBytes });
      completed = true;
      if (previousManifest?.activeGeneration && previousManifest.activeGeneration !== generation) {
        void deleteGeneration(cacheKey, previousManifest.activeGeneration);
      }
    });
    if (!completed || !manifest) throw new Error("补全元数据流未正常完成");
    return manifestSummary(manifest);
  } catch (error) {
    if (generation && !completed) await deleteGeneration(cacheKey, generation).catch(() => undefined);
    if (epoch === invalidationEpoch) {
      if (previous) remember(cacheKey, previous); else indexes.delete(cacheKey);
    }
    throw error;
  } finally {
    if (refreshControllers.get(cacheKey) === controller) refreshControllers.delete(cacheKey);
  }
}

async function enrichSingleTable(cacheKey: string, providerId: string, url: string, clientId: string,
                                 editorId: string, sql: string, columns: QueryColumn[],
                                 epoch: number): Promise<void> {
  ensureCurrentEpoch(epoch);
  const entry = await completionIndex(cacheKey, providerId);
  if (!entry) return;
  const target = resolveSinglePhysicalTable(entry.index, providerId, sql, columns);
  if (!target) return;
  const key = structureKey(cacheKey, target.schema, target.table);
  const loaded = loadedStructures.get(cacheKey) ?? new Set<string>();
  loadedStructures.set(cacheKey, loaded);
  if (loaded.has(key)) return;
  const existing = await idbGet<StoredTableStructure>(STRUCTURE_STORE, key);
  if (existing) {
    ensureCurrentEpoch(epoch);
    applyCompletionStructure(entry.index, existing.schema, existing.table, existing.columns);
    loaded.add(key);
    return;
  }
  const controller = new AbortController();
  enrichmentControllers.set(cacheKey, controller);
  try {
    const response = await fetch(url, {
      method: "POST", credentials: "same-origin", cache: "no-store", signal: controller.signal,
      headers: { "Content-Type": "application/json", "X-DBStudio-Client-Id": clientId },
      body: JSON.stringify({ editorId, schema: target.schema, table: target.table })
    });
    const data = await response.json().catch(() => ({})) as {
      schema?: string;
      table?: string;
      columns?: Array<{ name: string; typeName: string; ordinal: number }>;
      message?: string;
      code?: string;
    };
    if (!response.ok) throw Object.assign(new Error(data.message || `字段结构请求失败（${response.status}）`),
      { code: data.code });
    if (!Array.isArray(data.columns)) throw new Error("字段结构响应格式无效");
    ensureCurrentEpoch(epoch);
    const structure: StoredTableStructure = {
      key, cacheKey, schema: data.schema || target.schema, table: data.table || target.table,
      loadedAt: new Date().toISOString(),
      columns: data.columns.map((column) => ({
        name: String(column.name ?? ""), typeName: String(column.typeName ?? ""),
        ordinal: Number(column.ordinal) || 0
      }))
    };
    if (!applyCompletionStructure(entry.index, structure.schema, structure.table, structure.columns)) return;
    await idbPut(STRUCTURE_STORE, structure);
    ensureCurrentEpoch(epoch);
    loaded.add(key);
    entry.estimatedBytes += storedBytes(structure);
    remember(cacheKey, entry);
  } finally {
    if (enrichmentControllers.get(cacheKey) === controller) enrichmentControllers.delete(cacheKey);
  }
}

async function invalidateChangedTable(cacheKey: string, providerId: string, sql: string): Promise<void> {
  const entry = await completionIndex(cacheKey, providerId);
  if (!entry) return;
  const target = resolveChangedPhysicalTable(entry.index, providerId, sql);
  if (!target) return;
  const manifest = await idbGet<CompletionManifest>(MANIFEST_STORE, cacheKey);
  if (!validManifest(manifest, providerId)) return;
  const namespace = manifest.namespaces.find((value) => normalize(value.schema || value.catalog)
    === normalize(target.schema));
  if (!namespace) return;
  const object = await idbGet<StoredCompletionObject>(OBJECT_STORE,
    objectRecordKey(cacheKey, manifest.activeGeneration, namespace.key, target.table));
  if (!object || !resetCompletionStructure(entry.index, target.schema, target.table, object.columns)) return;
  const key = structureKey(cacheKey, target.schema, target.table);
  const structure = await idbGet<StoredTableStructure>(STRUCTURE_STORE, key);
  await idbDelete(STRUCTURE_STORE, key);
  loadedStructures.get(cacheKey)?.delete(key);
  if (structure) entry.estimatedBytes = Math.max(0, entry.estimatedBytes - storedBytes(structure));
  remember(cacheKey, entry);
}

async function completionIndex(cacheKey: string, providerId: string): Promise<MemoryIndex | undefined> {
  const existing = indexes.get(cacheKey);
  if (existing) {
    touch(cacheKey, existing);
    return existing;
  }
  if (isOracleCompatible(providerId)) {
    await idbDelete(LEGACY_STORE, cacheKey);
    const manifest = await idbGet<CompletionManifest>(MANIFEST_STORE, cacheKey);
    if (!validManifest(manifest, providerId)) return undefined;
    return loadStreamingIndex(manifest);
  }
  const stored = await loadValidLegacy(cacheKey, providerId);
  if (!stored) return undefined;
  const entry = { index: buildCompletionIndex(stored.snapshot), providerId,
    estimatedBytes: stored.summary.estimatedBytes };
  remember(cacheKey, entry);
  return entry;
}

async function loadStreamingIndex(manifest: CompletionManifest): Promise<MemoryIndex> {
  const index = createCompletionIndex(manifest.defaultNamespaceKey, manifest.namespaces);
  const objects = await idbGetAllByIndex<StoredCompletionObject>(
    OBJECT_STORE, "generationKey", generationKey(manifest.cacheKey, manifest.activeGeneration));
  for (const object of objects) {
    upsertCompletionObjects(index, [{ namespaceKey: object.namespaceKey, catalog: object.catalog,
      schema: object.schema, name: object.name, kind: object.kind, remarks: object.remarks }]);
    mergeCompletionColumns(index, object.namespaceKey, object.name,
      object.columns.map((column) => ({ ...column, typeName: "" })));
  }
  const structures = await idbGetAllByIndex<StoredTableStructure>(STRUCTURE_STORE, "cacheKey", manifest.cacheKey);
  const loaded = new Set<string>();
  let estimatedBytes = manifest.estimatedBytes;
  for (const structure of structures) {
    if (applyCompletionStructure(index, structure.schema, structure.table, structure.columns)) {
      loaded.add(structure.key);
      estimatedBytes += storedBytes(structure);
    }
  }
  loadedStructures.set(manifest.cacheKey, loaded);
  const entry = { index, providerId: manifest.providerId, estimatedBytes };
  remember(manifest.cacheKey, entry);
  return entry;
}

async function readNdjson(stream: ReadableStream<Uint8Array>,
                          consume: (record: StreamRecord) => Promise<void>): Promise<void> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      let newline = buffer.indexOf("\n");
      while (newline >= 0) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        if (line) await consume(JSON.parse(line) as StreamRecord);
        newline = buffer.indexOf("\n");
      }
      if (done) break;
    }
    if (buffer.trim()) await consume(JSON.parse(buffer) as StreamRecord);
  } finally {
    reader.releaseLock();
  }
}

function validateStreamMetadata(value: StreamMetadata, providerId: string): void {
  if (!value || value.formatVersion !== 2 || value.providerId !== providerId
    || !Array.isArray(value.selectedNamespaceKeys) || !Array.isArray(value.namespaces)) {
    throw new Error("流式补全缓存版本或数据库类型不匹配");
  }
  for (const namespace of value.namespaces) {
    if (!namespace || typeof namespace.key !== "string" || typeof namespace.label !== "string") {
      throw new Error("流式补全命名空间数据损坏");
    }
  }
}

function validateStreamSummary(value: CompletionCacheSummary, providerId: string): CompletionCacheSummary {
  if (!value || value.providerId !== providerId || !Array.isArray(value.selectedNamespaceKeys)
    || !Number.isFinite(value.objectCount) || !Number.isFinite(value.columnCount)
    || !Number.isFinite(value.estimatedBytes)) throw new Error("流式补全统计格式无效");
  return value;
}

function validManifest(value: CompletionManifest | undefined, providerId: string): value is CompletionManifest {
  return Boolean(value && value.formatVersion === 2 && value.providerId === providerId
    && value.cacheKey && value.activeGeneration && Array.isArray(value.namespaces)
    && Array.isArray(value.selectedNamespaceKeys));
}

function manifestSummary(manifest: CompletionManifest): CompletionCacheSummary {
  return {
    providerId: manifest.providerId, sourceProfileId: manifest.sourceProfileId,
    generatedAt: manifest.generatedAt, selectedNamespaceKeys: [...manifest.selectedNamespaceKeys],
    objectCount: manifest.objectCount, columnCount: manifest.columnCount,
    estimatedBytes: manifest.estimatedBytes, warning: manifest.warning
  };
}

function validateLegacySnapshot(value: unknown, providerId: string): CompletionSnapshot {
  if (!value || typeof value !== "object") throw new Error("补全缓存数据格式无效");
  const snapshot = value as CompletionSnapshot;
  if (snapshot.formatVersion !== LEGACY_FORMAT_VERSION || snapshot.providerId !== providerId
    || !Array.isArray(snapshot.selectedNamespaceKeys) || !Array.isArray(snapshot.namespaces)) {
    throw new Error("补全缓存版本或数据库类型不匹配");
  }
  for (const namespace of snapshot.namespaces) {
    if (!namespace || typeof namespace.key !== "string" || !Array.isArray(namespace.objects)) {
      throw new Error("补全命名空间数据损坏");
    }
    for (const object of namespace.objects) {
      if (!object || typeof object.name !== "string" || !Array.isArray(object.columns)
        || object.kind !== "table" && object.kind !== "view") throw new Error("补全对象数据损坏");
      for (const column of object.columns) {
        if (!column || typeof column.name !== "string" || typeof column.typeName !== "string"
          || typeof column.remarks !== "string") throw new Error("补全字段数据损坏");
      }
    }
  }
  return snapshot;
}

function summarizeLegacy(snapshot: CompletionSnapshot): CompletionCacheSummary {
  let objectCount = 0;
  let columnCount = 0;
  for (const namespace of snapshot.namespaces) {
    objectCount += namespace.objects.length;
    for (const object of namespace.objects) columnCount += object.columns.length;
  }
  return {
    providerId: snapshot.providerId, sourceProfileId: snapshot.sourceProfileId,
    generatedAt: snapshot.generatedAt, selectedNamespaceKeys: [...snapshot.selectedNamespaceKeys],
    objectCount, columnCount, estimatedBytes: new Blob([JSON.stringify(snapshot)]).size
  };
}

async function loadValidLegacy(cacheKey: string, providerId: string): Promise<LegacyStoredSnapshot | undefined> {
  const stored = await idbGet<LegacyStoredSnapshot>(LEGACY_STORE, cacheKey);
  if (!stored) return undefined;
  try {
    validateLegacySnapshot(stored.snapshot, providerId);
    if (!stored.summary || stored.summary.providerId !== providerId) throw new Error("补全缓存统计损坏");
    return stored;
  } catch {
    await idbDelete(LEGACY_STORE, cacheKey);
    return undefined;
  }
}

async function cacheStats(): Promise<Omit<CompletionCacheStats, "loadingCount">> {
  const manifests = await idbGetAll<CompletionManifest>(MANIFEST_STORE);
  const legacy = await idbGetAll<LegacyStoredSnapshot>(LEGACY_STORE);
  const structures = await idbGetAll<StoredTableStructure>(STRUCTURE_STORE);
  const stats = { environmentCount: 0, suggestionCount: 0, estimatedBytes: 0 };
  for (const manifest of manifests) {
    if (!validManifest(manifest, manifest.providerId)) continue;
    stats.environmentCount += 1;
    stats.suggestionCount += manifest.objectCount + manifest.columnCount;
    stats.estimatedBytes += manifest.estimatedBytes;
  }
  for (const record of legacy) {
    try {
      validateLegacySnapshot(record.snapshot, record.snapshot.providerId);
      if (isOracleCompatible(record.snapshot.providerId)) {
        await idbDelete(LEGACY_STORE, record.cacheKey);
        continue;
      }
      stats.environmentCount += 1;
      stats.suggestionCount += record.summary.objectCount + record.summary.columnCount;
      stats.estimatedBytes += record.summary.estimatedBytes;
    } catch {
      await idbDelete(LEGACY_STORE, record.cacheKey);
    }
  }
  for (const structure of structures) {
    stats.estimatedBytes += storedBytes(structure);
  }
  return stats;
}

function remember(key: string, entry: MemoryIndex): void {
  indexes.delete(key);
  indexes.set(key, entry);
  let bytes = [...indexes.values()].reduce((total, value) => total + value.estimatedBytes, 0);
  while (indexes.size > MAX_MEMORY_INDEXES || bytes > MAX_MEMORY_BYTES && indexes.size > 1) {
    const oldest = indexes.keys().next().value as string;
    const removed = indexes.get(oldest);
    indexes.delete(oldest);
    loadedStructures.delete(oldest);
    bytes -= removed?.estimatedBytes ?? 0;
  }
}

function touch(key: string, entry: MemoryIndex): void {
  indexes.delete(key);
  indexes.set(key, entry);
}

function enqueueMutation<T>(key: string, operation: () => Promise<T>): Promise<T> {
  const previous = mutationQueues.get(key) ?? Promise.resolve();
  const next = previous.catch(() => undefined).then(operation);
  mutationQueues.set(key, next);
  void next.finally(() => {
    if (mutationQueues.get(key) === next) mutationQueues.delete(key);
  }).catch(() => undefined);
  return next;
}

function post(response: CompletionWorkerResponse): void {
  self.postMessage(response);
}

function workerError(error: unknown): { message: string; code?: string } {
  if (error instanceof Error) return { message: error.message, code: (error as Error & { code?: string }).code };
  return { message: String(error) };
}

function isOracleCompatible(providerId: string): boolean {
  return providerId === "oracle" || providerId === "oceanbase-oracle";
}

function generationKey(cacheKey: string, generation: string): string {
  return `${cacheKey}\u0000${generation}`;
}

function objectRecordKey(cacheKey: string, generation: string, namespaceKey: string, name: string): string {
  return `${generationKey(cacheKey, generation)}\u0000${normalize(namespaceKey)}\u0000${normalize(name)}`;
}

function structureKey(cacheKey: string, schema: string, table: string): string {
  return `${cacheKey}\u0000${normalize(schema)}\u0000${normalize(table)}`;
}

function normalize(value: string): string {
  return value.toLocaleLowerCase();
}

function ensureCurrentEpoch(epoch: number): void {
  if (epoch !== invalidationEpoch) throw new Error("补全缓存操作已失效");
}

function storedBytes(value: unknown): number {
  return new Blob([JSON.stringify(value)]).size;
}

let databasePromise: Promise<IDBDatabase> | undefined;
function database(): Promise<IDBDatabase> {
  databasePromise ??= new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(LEGACY_STORE)) db.createObjectStore(LEGACY_STORE, { keyPath: "cacheKey" });
      if (!db.objectStoreNames.contains(MANIFEST_STORE)) db.createObjectStore(MANIFEST_STORE, { keyPath: "cacheKey" });
      if (!db.objectStoreNames.contains(OBJECT_STORE)) {
        const store = db.createObjectStore(OBJECT_STORE, { keyPath: "key" });
        store.createIndex("generationKey", "generationKey");
        store.createIndex("cacheKey", "cacheKey");
      }
      if (!db.objectStoreNames.contains(STRUCTURE_STORE)) {
        const store = db.createObjectStore(STRUCTURE_STORE, { keyPath: "key" });
        store.createIndex("cacheKey", "cacheKey");
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("无法打开补全缓存"));
  });
  return databasePromise;
}

async function idbGet<T>(storeName: string, key: IDBValidKey): Promise<T | undefined> {
  const db = await database();
  return new Promise<T | undefined>((resolve, reject) => {
    const request = db.transaction(storeName, "readonly").objectStore(storeName).get(key);
    request.onsuccess = () => resolve(request.result as T | undefined);
    request.onerror = () => reject(request.error ?? new Error("读取补全缓存失败"));
  });
}

async function idbGetAll<T>(storeName: string): Promise<T[]> {
  const db = await database();
  return new Promise<T[]>((resolve, reject) => {
    const request = db.transaction(storeName, "readonly").objectStore(storeName).getAll();
    request.onsuccess = () => resolve(request.result as T[]);
    request.onerror = () => reject(request.error ?? new Error("读取补全缓存失败"));
  });
}

async function idbGetAllByIndex<T>(storeName: string, indexName: string, key: IDBValidKey): Promise<T[]> {
  const db = await database();
  return new Promise<T[]>((resolve, reject) => {
    const request = db.transaction(storeName, "readonly").objectStore(storeName).index(indexName).getAll(key);
    request.onsuccess = () => resolve(request.result as T[]);
    request.onerror = () => reject(request.error ?? new Error("读取补全缓存索引失败"));
  });
}

async function idbPut(storeName: string, value: unknown): Promise<void> {
  await idbPutMany(storeName, [value]);
}

async function idbPutMany(storeName: string, values: unknown[]): Promise<void> {
  if (!values.length) return;
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(storeName, "readwrite");
    const store = transaction.objectStore(storeName);
    for (const value of values) store.put(value);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("写入补全缓存失败"));
    transaction.onabort = () => reject(transaction.error ?? new Error("补全缓存事务失败"));
  });
}

async function idbDelete(storeName: string, key: IDBValidKey): Promise<void> {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(storeName, "readwrite");
    transaction.objectStore(storeName).delete(key);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("删除补全缓存失败"));
  });
}

async function commitManifest(manifest: CompletionManifest): Promise<void> {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction([MANIFEST_STORE, STRUCTURE_STORE], "readwrite");
    transaction.objectStore(MANIFEST_STORE).put(manifest);
    const request = transaction.objectStore(STRUCTURE_STORE).index("cacheKey").openKeyCursor(manifest.cacheKey);
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      transaction.objectStore(STRUCTURE_STORE).delete(cursor.primaryKey);
      cursor.continue();
    };
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("切换补全缓存代次失败"));
    transaction.onabort = () => reject(transaction.error ?? new Error("补全缓存代次事务失败"));
  });
}

async function deleteGeneration(cacheKey: string, generation: string): Promise<void> {
  await deleteByIndex(OBJECT_STORE, "generationKey", generationKey(cacheKey, generation));
}

async function deleteInactiveGenerations(cacheKey: string, activeGeneration: string): Promise<void> {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(OBJECT_STORE, "readwrite");
    const store = transaction.objectStore(OBJECT_STORE);
    const request = store.index("cacheKey").openCursor(cacheKey);
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      const value = cursor.value as StoredCompletionObject;
      if (value.generation !== activeGeneration) cursor.delete();
      cursor.continue();
    };
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("清理旧补全缓存代次失败"));
  });
}

async function deleteByIndex(storeName: string, indexName: string, key: IDBValidKey): Promise<void> {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(storeName, "readwrite");
    const store = transaction.objectStore(storeName);
    const request = store.index(indexName).openKeyCursor(key);
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      store.delete(cursor.primaryKey);
      cursor.continue();
    };
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("清理补全缓存失败"));
  });
}

async function deleteCache(cacheKey: string): Promise<void> {
  await Promise.all([
    idbDelete(LEGACY_STORE, cacheKey),
    idbDelete(MANIFEST_STORE, cacheKey),
    deleteByIndex(OBJECT_STORE, "cacheKey", cacheKey),
    deleteByIndex(STRUCTURE_STORE, "cacheKey", cacheKey)
  ]);
  indexes.delete(cacheKey);
  loadedStructures.delete(cacheKey);
}

async function clearAllStores(): Promise<void> {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const names = [LEGACY_STORE, MANIFEST_STORE, OBJECT_STORE, STRUCTURE_STORE];
    const transaction = db.transaction(names, "readwrite");
    for (const name of names) transaction.objectStore(name).clear();
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("清理补全缓存失败"));
  });
}
