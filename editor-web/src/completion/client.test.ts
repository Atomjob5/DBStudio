import { afterEach, describe, expect, it, vi } from "vitest";
import { CompletionClient } from "./client";
import type { CompletionWorkerRequest, CompletionWorkerResponse } from "./workerProtocol";

class MockWorker {
  static latest?: MockWorker;
  readonly messages: CompletionWorkerRequest[] = [];
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

  terminate(): void { }
}

describe("CompletionClient worker protocol", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    MockWorker.latest = undefined;
  });

  it("sends model identity and cursor data without copying SQL into completion requests", async () => {
    vi.stubGlobal("Worker", MockWorker);
    const client = new CompletionClient();
    await client.complete("cache", "oracle", "editor", 7, 19, "CUS", 100);
    const request = MockWorker.latest?.messages[0];
    expect(request).toMatchObject({ type: "complete", cacheKey: "cache", providerId: "oracle",
      modelKey: "editor", modelVersion: 7, cursorOffset: 19, prefix: "CUS", limit: 100 });
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
});
