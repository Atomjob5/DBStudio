import type { CompletionCacheStats, CompletionCacheSummary, CompletionResult } from "../types";
import type { CompletionTextChange } from "./documentMirror";

export type CompletionWorkerRequest =
  | { id: string; type: "inspect"; cacheKey: string; providerId: string }
  | { id: string; type: "refresh"; cacheKey: string; providerId: string; url: string; clientId: string; body: Record<string, unknown> }
  | { id: string; type: "model.sync"; modelKey: string; version: number; text: string }
  | { id: string; type: "model.change"; modelKey: string; fromVersion: number; toVersion: number; changes: CompletionTextChange[] }
  | { id: string; type: "model.release"; modelKey: string }
  | { id: string; type: "complete"; cacheKey: string; providerId: string; modelKey: string;
      modelVersion: number; cursorOffset: number; prefix: string; limit: number }
  | { id: string; type: "stats" }
  | { id: string; type: "clear" };

export type CompletionWorkerValue = CompletionCacheSummary | CompletionResult
  | Omit<CompletionCacheStats, "loadingCount"> | undefined;

export interface CompletionWorkerResponse {
  id: string;
  value?: CompletionWorkerValue;
  error?: { message: string; code?: string };
}
