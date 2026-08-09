import type { CompletionCacheStats, CompletionCacheSummary, CompletionResult, ResolvedResultColumnRemark,
  QueryColumn, ResultColumnRemarkLookup } from "../types";
import type { CompletionWorkerRequest, CompletionWorkerResponse, CompletionWorkerValue } from "./workerProtocol";
import type { CompletionTextChange } from "./documentMirror";

type Pending = { resolve: (value: CompletionWorkerValue) => void; reject: (error: Error) => void };

export class CompletionClient {
  private worker?: Worker;
  private readonly pending = new Map<string, Pending>();
  private workerGeneration = 0;

  inspect(cacheKey: string, providerId: string): Promise<CompletionCacheSummary | undefined> {
    return this.send({ id: crypto.randomUUID(), type: "inspect", cacheKey, providerId }) as Promise<CompletionCacheSummary | undefined>;
  }

  refresh(options: { cacheKey: string; providerId: string; workspaceId: string; clientId: string; body: Record<string, unknown> }): Promise<CompletionCacheSummary> {
    const streaming = options.providerId === "oracle" || options.providerId === "oceanbase-oracle";
    const endpoint = streaming ? "completion-snapshot-stream" : "completion-snapshot";
    const url = `/api/v1/workspaces/${encodeURIComponent(options.workspaceId)}/metadata/${endpoint}`;
    return this.send({ id: crypto.randomUUID(), type: "refresh", cacheKey: options.cacheKey,
      providerId: options.providerId, url, clientId: options.clientId, body: options.body }) as Promise<CompletionCacheSummary>;
  }

  syncModel(modelKey: string, version: number, text: string): Promise<void> {
    return this.send({ id: crypto.randomUUID(), type: "model.sync", modelKey, version, text }) as Promise<void>;
  }

  changeModel(modelKey: string, fromVersion: number, toVersion: number,
              changes: CompletionTextChange[]): Promise<void> {
    return this.send({ id: crypto.randomUUID(), type: "model.change", modelKey, fromVersion, toVersion,
      changes }) as Promise<void>;
  }

  releaseModel(modelKey: string): Promise<void> {
    return this.send({ id: crypto.randomUUID(), type: "model.release", modelKey }) as Promise<void>;
  }

  complete(cacheKey: string, providerId: string, modelKey: string, modelVersion: number,
           cursorOffset: number, prefix: string, limit: number,
           preciseMatchingEnabled: boolean): Promise<CompletionResult> {
    return this.send({ id: crypto.randomUUID(), type: "complete", cacheKey, providerId, modelKey,
      modelVersion, cursorOffset, prefix, limit, preciseMatchingEnabled }) as Promise<CompletionResult>;
  }

  resolveResultColumnRemarks(cacheKey: string, providerId: string, sql: string,
                             columns: ResultColumnRemarkLookup[]): Promise<ResolvedResultColumnRemark[]> {
    return this.send({ id: crypto.randomUUID(), type: "result-columns.resolve", cacheKey, providerId,
      sql, columns }) as Promise<ResolvedResultColumnRemark[]>;
  }

  enrichQuery(options: { cacheKey: string; providerId: string; workspaceId: string; clientId: string;
                         editorId: string; sql: string; columns: QueryColumn[] }): Promise<void> {
    const url = `/api/v1/workspaces/${encodeURIComponent(options.workspaceId)}/metadata/completion-table-structure`;
    return this.send({ id: crypto.randomUUID(), type: "query.enrich", cacheKey: options.cacheKey,
      providerId: options.providerId, url, clientId: options.clientId, editorId: options.editorId,
      sql: options.sql, columns: options.columns }) as Promise<void>;
  }

  invalidateStructure(cacheKey: string, providerId: string, sql: string): Promise<void> {
    return this.send({ id: crypto.randomUUID(), type: "structure.invalidate",
      cacheKey, providerId, sql }) as Promise<void>;
  }

  stats(): Promise<Omit<CompletionCacheStats, "loadingCount">> {
    return this.send({ id: crypto.randomUUID(), type: "stats" }) as Promise<Omit<CompletionCacheStats, "loadingCount">>;
  }

  async clear(): Promise<void> {
    try {
      await this.send({ id: crypto.randomUUID(), type: "clear" });
    } finally {
      // Clearing the stores drops references, but the Worker can retain its expanded
      // V8 heap and document mirror. Recycle it so the next request starts with a
      // genuinely empty isolate. This also runs when clearing fails halfway through.
      this.terminateWorker();
    }
  }

  private send(request: CompletionWorkerRequest): Promise<CompletionWorkerValue> {
    const worker = this.ensureWorker();
    return new Promise((resolve, reject) => {
      this.pending.set(request.id, { resolve, reject });
      worker.postMessage(request);
    });
  }

  private ensureWorker(): Worker {
    if (this.worker) return this.worker;
    const worker = new Worker(new URL("../workers/completion.worker.ts", import.meta.url), { type: "module" });
    this.workerGeneration += 1;
    if (import.meta.env?.DEV) console.debug("[completion] worker-created", { generation: this.workerGeneration });
    worker.onmessage = (event: MessageEvent<CompletionWorkerResponse>) => {
      const pending = this.pending.get(event.data.id);
      if (!pending) return;
      this.pending.delete(event.data.id);
      if (event.data.error) pending.reject(Object.assign(new Error(event.data.error.message), { code: event.data.error.code }));
      else pending.resolve(event.data.value);
    };
    worker.onerror = (event) => {
      if (this.worker !== worker) return;
      const error = new Error(event.message || "SQL补全工作线程异常");
      this.terminateWorker(error, worker);
    };
    this.worker = worker;
    return worker;
  }

  private terminateWorker(reason?: Error, expectedWorker?: Worker): void {
    const worker = this.worker;
    if (expectedWorker && worker !== expectedWorker) return;
    this.worker = undefined;
    if (worker) {
      worker.onmessage = null;
      worker.onerror = null;
      worker.terminate();
    }
    if (!reason) {
      if (import.meta.env?.DEV) console.debug("[completion] worker-recycled", { generation: this.workerGeneration });
      reason = Object.assign(new Error("SQL补全工作线程已重置"), { code: "WORKER_RESET" });
    }
    for (const pending of this.pending.values()) pending.reject(reason);
    this.pending.clear();
  }
}

export const completionClient = new CompletionClient();
