import { afterEach, describe, expect, it, vi } from "vitest";
import { CompletionClient } from "./client";
import type { CompletionWorkerRequest, CompletionWorkerResponse } from "./workerProtocol";

class MockWorker {
  static latest?: MockWorker;
  readonly messages: CompletionWorkerRequest[] = [];
  terminated = false;
  onmessage?: (event: MessageEvent<CompletionWorkerResponse>) => void;
  onerror?: (event: ErrorEvent) => void;

  constructor() {
    MockWorker.latest = this;
  }

  postMessage(request: CompletionWorkerRequest): void {
    this.messages.push(request);
    const value = request.type === "complete" ? { items: [], incomplete: false } : undefined;
    queueMicrotask(() => this.onmessage?.({ data: { id: request.id, value } } as MessageEvent<CompletionWorkerResponse>));
  }

  terminate(): void { this.terminated = true; }
}

class DelayedWorker {
  static latest?: DelayedWorker;
  readonly messages: CompletionWorkerRequest[] = [];
  terminated = false;
  onmessage?: (event: MessageEvent<CompletionWorkerResponse>) => void;
  onerror?: (event: ErrorEvent) => void;

  constructor() { DelayedWorker.latest = this; }

  postMessage(request: CompletionWorkerRequest): void {
    this.messages.push(request);
    if (request.type !== "clear" && request.type !== "stats") return;
    queueMicrotask(() => this.onmessage?.({ data: { id: request.id, value: undefined } } as MessageEvent<CompletionWorkerResponse>));
  }

  respond(id: string): void {
    this.onmessage?.({ data: { id, value: undefined } } as MessageEvent<CompletionWorkerResponse>);
  }

  terminate(): void { this.terminated = true; }
}

describe("CompletionClient worker protocol", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    MockWorker.latest = undefined;
    DelayedWorker.latest = undefined;
  });

  it("sends model identity and cursor data without copying SQL into completion requests", async () => {
    vi.stubGlobal("Worker", MockWorker);
    const client = new CompletionClient();
    await client.complete("cache", "oracle", "editor", 7, 19, "CUS", 100, false);
    const request = MockWorker.latest?.messages[0];
    expect(request).toMatchObject({ type: "complete", cacheKey: "cache", providerId: "oracle",
      modelKey: "editor", modelVersion: 7, cursorOffset: 19, prefix: "CUS", limit: 100 });
    expect(request).toHaveProperty("preciseMatchingEnabled", false);
    expect(request).not.toHaveProperty("sql");
  });

  it("uses the same Worker channel for sync, incremental change, and release", async () => {
    vi.stubGlobal("Worker", MockWorker);
    const client = new CompletionClient();
    await client.syncModel("editor", 1, "select 1");
    await client.changeModel("editor", 1, 2, [{ rangeOffset: 7, rangeLength: 1, text: "2" }]);
    await client.releaseModel("editor");
    expect(MockWorker.latest?.messages.map((request) => request.type))
      .toEqual(["model.sync", "model.change", "model.release"]);
  });

  it("sends executed SQL when resolving result column remarks", async () => {
    vi.stubGlobal("Worker", MockWorker);
    const client = new CompletionClient();
    await client.resolveResultColumnRemarks("oracle-cache", "oracle",
      "select * from CBSAC.CUSTOMERS", [
        { index: 0, catalog: "", schema: "", table: "", name: "ID" }
      ]);
    expect(MockWorker.latest?.messages[0]).toMatchObject({
      type: "result-columns.resolve",
      cacheKey: "oracle-cache",
      providerId: "oracle",
      sql: "select * from CBSAC.CUSTOMERS",
      columns: [{ index: 0, catalog: "", schema: "", table: "", name: "ID" }]
    });
  });

  it("uses streaming snapshots only for Oracle-compatible providers", async () => {
    vi.stubGlobal("Worker", MockWorker);
    const client = new CompletionClient();
    await client.refresh({
      cacheKey: "oracle-cache",
      providerId: "oracle",
      workspaceId: "workspace / 1",
      clientId: "client-1",
      body: { editorId: "editor-1" }
    });
    await client.refresh({
      cacheKey: "mysql-cache",
      providerId: "mysql",
      workspaceId: "workspace / 1",
      clientId: "client-1",
      body: { editorId: "editor-1" }
    });
    expect(MockWorker.latest?.messages[0]).toMatchObject({
      type: "refresh",
      providerId: "oracle",
      url: "/api/v1/workspaces/workspace%20%2F%201/metadata/completion-snapshot-stream"
    });
    expect(MockWorker.latest?.messages[1]).toMatchObject({
      type: "refresh",
      providerId: "mysql",
      url: "/api/v1/workspaces/workspace%20%2F%201/metadata/completion-snapshot"
    });
  });

  it("sends successful query context to the exact table structure worker flow", async () => {
    vi.stubGlobal("Worker", MockWorker);
    const client = new CompletionClient();
    await client.enrichQuery({
      cacheKey: "oracle-cache",
      providerId: "oracle",
      workspaceId: "workspace-1",
      clientId: "client-1",
      editorId: "editor-1",
      sql: "select count(*) from CBSAC.CUSTOMERS",
      columns: []
    });
    expect(MockWorker.latest?.messages[0]).toMatchObject({
      type: "query.enrich",
      cacheKey: "oracle-cache",
      providerId: "oracle",
      url: "/api/v1/workspaces/workspace-1/metadata/completion-table-structure",
      editorId: "editor-1",
      sql: "select count(*) from CBSAC.CUSTOMERS",
      columns: []
    });
  });

  it("routes successful Oracle DDL to targeted structure invalidation", async () => {
    vi.stubGlobal("Worker", MockWorker);
    const client = new CompletionClient();
    await client.invalidateStructure(
      "oracle-cache",
      "oceanbase-oracle",
      "alter table CBSAC.CUSTOMERS add CREATED_AT timestamp"
    );
    expect(MockWorker.latest?.messages[0]).toMatchObject({
      type: "structure.invalidate",
      cacheKey: "oracle-cache",
      providerId: "oceanbase-oracle",
      sql: "alter table CBSAC.CUSTOMERS add CREATED_AT timestamp"
    });
  });

  it("recycles the worker after clearing and ignores residual responses", async () => {
    vi.stubGlobal("Worker", DelayedWorker);
    const client = new CompletionClient();
    const pending = client.complete("cache", "oracle", "editor", 1, 0, "", 20, false);
    const pendingRefresh = client.refresh({
      cacheKey: "cache", providerId: "oracle", workspaceId: "workspace", clientId: "client",
      body: { editorId: "editor" }
    });
    const oldWorker = DelayedWorker.latest;
    const rejected = expect(pending).rejects.toMatchObject({ code: "WORKER_RESET" });
    const refreshRejected = expect(pendingRefresh).rejects.toMatchObject({ code: "WORKER_RESET" });

    await client.clear();
    await rejected;
    await refreshRejected;
    expect(oldWorker?.terminated).toBe(true);

    const staleRequest = oldWorker?.messages.find((request) => request.type === "complete");
    if (staleRequest) oldWorker?.respond(staleRequest.id);
    await client.stats();
    expect(DelayedWorker.latest).not.toBe(oldWorker);
  });

  it("recycles the worker even when clearing reports an error", async () => {
    class FailingWorker extends MockWorker {
      override postMessage(request: CompletionWorkerRequest): void {
        this.messages.push(request);
        if (request.type === "clear") queueMicrotask(() => this.onmessage?.({
          data: { id: request.id, error: { message: "清理失败", code: "CLEAR_FAILED" } }
        } as MessageEvent<CompletionWorkerResponse>));
        else if (request.type === "stats") queueMicrotask(() => this.onmessage?.({
          data: { id: request.id, value: undefined }
        } as MessageEvent<CompletionWorkerResponse>));
      }
    }
    vi.stubGlobal("Worker", FailingWorker);
    const client = new CompletionClient();
    await client.stats();
    const worker = FailingWorker.latest;
    await expect(client.clear()).rejects.toMatchObject({ code: "CLEAR_FAILED" });
    expect(worker?.terminated).toBe(true);
  });

  it("rejects pending requests and recreates the channel after a worker exception", async () => {
    class CrashingWorker extends MockWorker {
      override postMessage(request: CompletionWorkerRequest): void {
        this.messages.push(request);
        queueMicrotask(() => this.onerror?.({ message: "worker crashed" } as ErrorEvent));
      }
    }
    vi.stubGlobal("Worker", CrashingWorker);
    const client = new CompletionClient();
    const pending = client.stats();
    const oldWorker = MockWorker.latest;
    await expect(pending).rejects.toThrow("worker crashed");
    expect(oldWorker?.terminated).toBe(true);

    vi.stubGlobal("Worker", MockWorker);
    await client.stats();
    expect(MockWorker.latest).not.toBe(oldWorker);
  });
});
