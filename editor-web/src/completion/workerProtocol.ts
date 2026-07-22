import type { CompletionCacheStats, CompletionCacheSummary, CompletionResult } from "../types";

export type CompletionWorkerRequest =
  | { id: string; type: "inspect"; cacheKey: string; providerId: string }
  | { id: string; type: "refresh"; cacheKey: string; providerId: string; url: string; clientId: string; body: Record<string, unknown> }
  | { id: string; type: "complete"; cacheKey: string; providerId: string; sql: string; prefix: string; limit: number }
  | { id: string; type: "stats" }
  | { id: string; type: "clear" };

export type CompletionWorkerValue = CompletionCacheSummary | CompletionResult
  | Omit<CompletionCacheStats, "loadingCount"> | undefined;

export interface CompletionWorkerResponse {
  id: string;
  value?: CompletionWorkerValue;
  error?: { message: string; code?: string };
}
