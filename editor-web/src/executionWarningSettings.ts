export const DEFAULT_EXECUTION_WARNING_MINUTES = [10, 30] as const;
export const MIN_EXECUTION_WARNING_MINUTES = 1;
export const MAX_EXECUTION_WARNING_MINUTES = 1_440;
export const MAX_EXECUTION_WARNING_COUNT = 20;

export function normalizeExecutionWarningMinutes(value: readonly unknown[]): number[] {
  return [...new Set(value
    .filter((item): item is number => typeof item === "number"
      && Number.isInteger(item)
      && item >= MIN_EXECUTION_WARNING_MINUTES
      && item <= MAX_EXECUTION_WARNING_MINUTES))]
    .sort((left, right) => left - right)
    .slice(0, MAX_EXECUTION_WARNING_COUNT);
}

export function parseExecutionWarningMinutes(value?: string): number[] {
  if (value === undefined) return [...DEFAULT_EXECUTION_WARNING_MINUTES];
  try {
    const parsed: unknown = JSON.parse(value);
    if (!Array.isArray(parsed) || parsed.length > MAX_EXECUTION_WARNING_COUNT
        || parsed.some((item) => typeof item !== "number"
          || !Number.isInteger(item)
          || item < MIN_EXECUTION_WARNING_MINUTES
          || item > MAX_EXECUTION_WARNING_MINUTES)) {
      return [...DEFAULT_EXECUTION_WARNING_MINUTES];
    }
    return normalizeExecutionWarningMinutes(parsed);
  } catch {
    return [...DEFAULT_EXECUTION_WARNING_MINUTES];
  }
}

export function serializeExecutionWarningMinutes(value: readonly unknown[]): string {
  return JSON.stringify(normalizeExecutionWarningMinutes(value));
}
