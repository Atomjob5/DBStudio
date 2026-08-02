export type ExecutionDurationLevel = "normal" | "warning" | "danger";

export interface ExecutionDurationParts {
  totalMs: number;
  minutes: number;
  seconds: number;
  milliseconds: number;
  showMinutes: boolean;
  showSeconds: boolean;
  level: ExecutionDurationLevel;
}

export function executionDurationParts(elapsedMs: number): ExecutionDurationParts {
  const totalMs = Math.max(0, Math.floor(Number.isFinite(elapsedMs) ? elapsedMs : 0));
  return {
    totalMs,
    minutes: Math.floor(totalMs / 60_000),
    seconds: Math.floor(totalMs / 1_000) % 60,
    milliseconds: totalMs % 1_000,
    showMinutes: totalMs >= 60_000,
    showSeconds: totalMs >= 1_000,
    level: totalMs >= 60_000 ? "danger" : totalMs >= 10_000 ? "warning" : "normal"
  };
}

export function executionDurationText(elapsedMs: number): string {
  const parts = executionDurationParts(elapsedMs);
  const values: string[] = [];
  if (parts.showMinutes) values.push(`${parts.minutes}m`);
  if (parts.showSeconds) values.push(`${parts.seconds}s`);
  values.push(`${parts.milliseconds}ms`);
  return values.join(" ");
}
