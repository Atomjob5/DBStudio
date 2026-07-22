import { describe, expect, it, vi } from "vitest";
import { CompletionModelSynchronizer, isModelVersionChanged } from "./modelSynchronizer";

function createClient() {
  return {
    syncModel: vi.fn<(...args: [string, number, string]) => Promise<void>>().mockResolvedValue(undefined),
    changeModel: vi.fn<(...args: [string, number, number, Array<{ rangeOffset: number; rangeLength: number; text: string }>]) => Promise<void>>()
      .mockResolvedValue(undefined),
    releaseModel: vi.fn<(...args: [string]) => Promise<void>>().mockResolvedValue(undefined)
  };
}

function createModel(version = 1, text = "select 1") {
  return {
    version,
    text,
    getVersionId() { return this.version; },
    getValue() { return this.text; }
  };
}

function outOfSync(): Error {
  return Object.assign(new Error("out of sync"), { code: "MODEL_OUT_OF_SYNC" });
}

describe("CompletionModelSynchronizer", () => {
  it("sends one full sync and reuses it while the model version is unchanged", async () => {
    const client = createClient();
    const synchronizer = new CompletionModelSynchronizer(client);
    const model = createModel();
    await synchronizer.ensure("editor", model);
    await synchronizer.ensure("editor", model);
    expect(client.syncModel).toHaveBeenCalledTimes(1);
    expect(client.syncModel).toHaveBeenCalledWith("editor", 1, "select 1");
  });

  it("serializes consecutive incremental changes with matching versions", async () => {
    const client = createClient();
    const synchronizer = new CompletionModelSynchronizer(client);
    const model = createModel();
    await synchronizer.ensure("editor", model);
    model.version = 2;
    synchronizer.change("editor", 2, [{ rangeOffset: 7, rangeLength: 1, text: "2" }]);
    model.version = 3;
    synchronizer.change("editor", 3, [{ rangeOffset: 8, rangeLength: 0, text: ";" }]);
    await synchronizer.ensure("editor", model);
    expect(client.changeModel.mock.calls.map((call) => call.slice(0, 3)))
      .toEqual([["editor", 1, 2], ["editor", 2, 3]]);
  });

  it("recovers from an incremental change mismatch and retries completion once", async () => {
    const client = createClient();
    const synchronizer = new CompletionModelSynchronizer(client);
    const model = createModel();
    await synchronizer.ensure("editor", model);
    client.changeModel.mockRejectedValueOnce(outOfSync());
    model.version = 2;
    model.text = "select 2";
    synchronizer.change("editor", 2, [{ rangeOffset: 7, rangeLength: 1, text: "2" }]);
    const operation = vi.fn().mockResolvedValue("completed");
    await expect(synchronizer.execute("editor", model, 2, operation)).resolves.toBe("completed");
    expect(client.syncModel).toHaveBeenCalledTimes(2);
    expect(client.syncModel).toHaveBeenLastCalledWith("editor", 2, "select 2");
    expect(operation).toHaveBeenCalledTimes(1);
  });

  it("recovers from a completion mismatch but never retries more than once", async () => {
    const client = createClient();
    const synchronizer = new CompletionModelSynchronizer(client);
    const model = createModel();
    const successful = vi.fn().mockRejectedValueOnce(outOfSync()).mockResolvedValueOnce("completed");
    await expect(synchronizer.execute("editor", model, 1, successful)).resolves.toBe("completed");
    expect(successful).toHaveBeenCalledTimes(2);
    expect(client.syncModel).toHaveBeenCalledTimes(2);

    const alwaysFails = vi.fn().mockRejectedValue(outOfSync());
    await expect(synchronizer.execute("editor", model, 1, alwaysFails)).rejects
      .toMatchObject({ code: "MODEL_OUT_OF_SYNC" });
    expect(alwaysFails).toHaveBeenCalledTimes(2);
  });

  it("discards work when the model version changes during synchronization", async () => {
    const client = createClient();
    let resolveSync: (() => void) | undefined;
    client.syncModel.mockImplementationOnce(() => new Promise<void>((resolve) => { resolveSync = resolve; }));
    const synchronizer = new CompletionModelSynchronizer(client);
    const model = createModel();
    const operation = vi.fn().mockResolvedValue("unused");
    const pending = synchronizer.execute("editor", model, 1, operation);
    model.version = 2;
    resolveSync?.();
    const error = await pending.then(() => undefined, (reason: unknown) => reason);
    expect(isModelVersionChanged(error)).toBe(true);
    expect(operation).not.toHaveBeenCalled();
  });

  it("discards a completed operation if the model changed while it was running", async () => {
    const client = createClient();
    const synchronizer = new CompletionModelSynchronizer(client);
    const model = createModel();
    let resolveOperation: ((value: string) => void) | undefined;
    const operation = vi.fn(() => new Promise<string>((resolve) => { resolveOperation = resolve; }));
    const pending = synchronizer.execute("editor", model, 1, operation);
    await Promise.resolve();
    model.version = 2;
    resolveOperation?.("stale");
    const error = await pending.then(() => undefined, (reason: unknown) => reason);
    expect(isModelVersionChanged(error)).toBe(true);
  });

  it("releases every synchronized model", async () => {
    const client = createClient();
    const synchronizer = new CompletionModelSynchronizer(client);
    await synchronizer.ensure("one", createModel());
    await synchronizer.ensure("two", createModel());
    synchronizer.releaseAll();
    expect(client.releaseModel).toHaveBeenCalledTimes(2);
    expect(client.releaseModel).toHaveBeenCalledWith("one");
    expect(client.releaseModel).toHaveBeenCalledWith("two");
  });
});
