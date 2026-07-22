import type { CompletionTextChange } from "./documentMirror";

export interface CompletionMirrorClient {
  syncModel(modelKey: string, version: number, text: string): Promise<void>;
  changeModel(modelKey: string, fromVersion: number, toVersion: number,
              changes: CompletionTextChange[]): Promise<void>;
  releaseModel(modelKey: string): Promise<void>;
}

export interface CompletionTextModel {
  getVersionId(): number;
  getValue(): string;
}

interface MirrorState {
  version: number;
  pending: Promise<void>;
}

export class CompletionModelSynchronizer {
  private readonly states = new Map<string, MirrorState>();

  constructor(private readonly client: CompletionMirrorClient) { }

  change(modelKey: string, toVersion: number, changes: CompletionTextChange[]): void {
    const current = this.states.get(modelKey);
    if (!current) return;
    const next: MirrorState = {
      version: toVersion,
      pending: current.pending.then(() => this.client.changeModel(modelKey, current.version, toVersion, changes))
    };
    this.states.set(modelKey, next);
    void next.pending.catch(() => {
      if (this.states.get(modelKey) === next) this.states.delete(modelKey);
    });
  }

  async ensure(modelKey: string, model: CompletionTextModel, force = false): Promise<void> {
    const version = model.getVersionId();
    const current = this.states.get(modelKey);
    if (!force && current?.version === version) {
      await current.pending;
      return;
    }
    const next: MirrorState = { version, pending: this.client.syncModel(modelKey, version, model.getValue()) };
    this.states.set(modelKey, next);
    void next.pending.catch(() => {
      if (this.states.get(modelKey) === next) this.states.delete(modelKey);
    });
    await next.pending;
  }

  async execute<T>(modelKey: string, model: CompletionTextModel, expectedVersion: number,
                   operation: () => Promise<T>): Promise<T> {
    try {
      await this.ensure(modelKey, model);
      ensureVersion(model, expectedVersion);
      const result = await operation();
      ensureVersion(model, expectedVersion);
      return result;
    } catch (error) {
      if (!isModelOutOfSync(error) || model.getVersionId() !== expectedVersion) throw error;
      await this.ensure(modelKey, model, true);
      ensureVersion(model, expectedVersion);
      const result = await operation();
      ensureVersion(model, expectedVersion);
      return result;
    }
  }

  synchronizeInBackground(modelKey: string, model: CompletionTextModel): void {
    void this.ensure(modelKey, model).catch(() => undefined);
  }

  releaseAll(): void {
    for (const modelKey of this.states.keys()) {
      void this.client.releaseModel(modelKey).catch(() => undefined);
    }
    this.states.clear();
  }
}

export function isModelVersionChanged(error: unknown): boolean {
  return error instanceof Error && (error as Error & { code?: string }).code === "MODEL_VERSION_CHANGED";
}

function ensureVersion(model: CompletionTextModel, expectedVersion: number): void {
  if (model.getVersionId() !== expectedVersion) {
    throw Object.assign(new Error("补全期间编辑器模型已发生变化"), { code: "MODEL_VERSION_CHANGED" });
  }
}

function isModelOutOfSync(error: unknown): boolean {
  return error instanceof Error && (error as Error & { code?: string }).code === "MODEL_OUT_OF_SYNC";
}
