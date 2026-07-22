import type { CompletionCacheStats, CompletionCacheSummary, CompletionResult } from "../types";
import type { CompletionWorkerRequest, CompletionWorkerResponse, CompletionWorkerValue } from "./workerProtocol";

type Pending = { resolve: (value: CompletionWorkerValue) => void; reject: (error: Error) => void };

class CompletionClient {
  private worker?: Worker;
  private readonly pending = new Map<string, Pending>();

  inspect(cacheKey: string, providerId: string): Promise<CompletionCacheSummary | undefined> {
    return this.send({ id: crypto.randomUUID(), type: "inspect", cacheKey, providerId }) as Promise<CompletionCacheSummary | undefined>;
  }

  refresh(options: { cacheKey: string; providerId: string; workspaceId: string; clientId: string; body: Record<string, unknown> }): Promise<CompletionCacheSummary> {
    const url = `/api/v1/workspaces/${encodeURIComponent(options.workspaceId)}/metadata/completion-snapshot`;
    return this.send({ id: crypto.randomUUID(), type: "refresh", cacheKey: options.cacheKey,
      providerId: options.providerId, url, clientId: options.clientId, body: options.body }) as Promise<CompletionCacheSummary>;
  }

  complete(cacheKey: string, providerId: string, sql: string, prefix: string, limit: number): Promise<CompletionResult> {
    return this.send({ id: crypto.randomUUID(), type: "complete", cacheKey, providerId, sql, prefix, limit }) as Promise<CompletionResult>;
  }

  stats(): Promise<Omit<CompletionCacheStats, "loadingCount">> {
    return this.send({ id: crypto.randomUUID(), type: "stats" }) as Promise<Omit<CompletionCacheStats, "loadingCount">>;
  }

  clear(): Promise<void> {
    return this.send({ id: crypto.randomUUID(), type: "clear" }) as Promise<void>;
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
    worker.onmessage = (event: MessageEvent<CompletionWorkerResponse>) => {
      const pending = this.pending.get(event.data.id);
      if (!pending) return;
      this.pending.delete(event.data.id);
      if (event.data.error) pending.reject(Object.assign(new Error(event.data.error.message), { code: event.data.error.code }));
      else pending.resolve(event.data.value);
    };
    worker.onerror = (event) => {
      const error = new Error(event.message || "SQL补全工作线程异常");
      for (const pending of this.pending.values()) pending.reject(error);
      this.pending.clear();
      worker.terminate();
      this.worker = undefined;
    };
    this.worker = worker;
    return worker;
  }
}

export const completionClient = new CompletionClient();
