import { afterEach, describe, expect, it, vi } from "vitest";
import type { SqlDiagnostic, SqlQuickFix } from "../types";
import {
  applicableSqlQuickFixes,
  filterServerSqlDiagnostics,
  mergeSqlDiagnostics,
  sameDiagnosticRun,
  sqlDiagnosticMarker,
  SqlDiagnosticScheduler,
  SQL_DIAGNOSTIC_MARKER_OWNER,
} from "./diagnosticPresentation";

function diagnostic(code: string, startOffset: number, endOffset: number,
                    category: SqlDiagnostic["category"] = "syntax",
                    severity: SqlDiagnostic["severity"] = "error"): SqlDiagnostic {
  return { code, category, severity, message: `${code} message`, startOffset, endOffset };
}

describe("SQL diagnostic presentation", () => {
  afterEach(() => vi.useRealTimers());

  it("debounces for 400 ms and invalidates an in-flight generation", () => {
    vi.useFakeTimers();
    const scheduler = new SqlDiagnosticScheduler();
    const calls: number[] = [];
    scheduler.schedule((sequence) => calls.push(sequence));
    vi.advanceTimersByTime(399);
    scheduler.schedule((sequence) => calls.push(sequence));
    vi.advanceTimersByTime(399);
    expect(calls).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(calls).toHaveLength(1);
    expect(scheduler.isCurrent(calls[0])).toBe(true);
    scheduler.invalidate();
    expect(scheduler.isCurrent(calls[0])).toBe(false);
  });

  it("treats model, provider and completion cache changes as stale", () => {
    const snapshot = { key: "editor-1", version: 3, providerId: "mysql", completionKey: "cache-1",
      completionRevision: "ready:1", completionMetadataReady: true };
    expect(sameDiagnosticRun(snapshot, { ...snapshot })).toBe(true);
    for (const changed of [
      { version: 4 }, { providerId: "oracle" }, { completionKey: "cache-2" },
      { completionRevision: "ready:2" }, { completionMetadataReady: false }, { key: "editor-2" },
    ]) expect(sameDiagnosticRun(snapshot, { ...snapshot, ...changed })).toBe(false);
  });

  it("keeps local results on server failure paths and filters optional risk warnings", () => {
    const sql = "select ';'; update orders set state='x'";
    const local = [diagnostic("SQL_UNCLOSED_STRING", 8, 9)];
    const server = [
      diagnostic("SQL_SYNTAX_ERROR", 9, 10),
      diagnostic("SQL_DML_WITHOUT_WHERE", sql.indexOf("update"), sql.indexOf("update") + 6,
        "risk", "warning"),
    ];
    expect(mergeSqlDiagnostics(local, filterServerSqlDiagnostics(sql, "mysql", false, server, local)))
      .toEqual(local);
    expect(mergeSqlDiagnostics(local, [])).toEqual(local);
  });

  it("deduplicates diagnostics and maps marker severity, hover text and owner", () => {
    const warning = diagnostic("SQL_UNKNOWN_OBJECT", 2, 5, "semantic", "warning");
    expect(mergeSqlDiagnostics([warning], [{ ...warning, message: "server duplicate" }])).toEqual([warning]);
    expect(SQL_DIAGNOSTIC_MARKER_OWNER).toBe("dbstudio.sqlDiagnostics");
    expect(sqlDiagnosticMarker(warning, { startLineNumber: 1, startColumn: 3,
      endLineNumber: 1, endColumn: 6 }, 8, 4)).toEqual({
      startLineNumber: 1, startColumn: 3, endLineNumber: 1, endColumn: 6,
      severity: 4, message: warning.message, source: "DBStudio SQL 诊断", code: warning.code,
    });
  });

  it("returns only Quick Fixes that touch the requested diagnostic range", () => {
    const diagnostics = [diagnostic("SQL_UNKNOWN_OBJECT", 10, 15, "semantic", "warning")];
    const fixes: SqlQuickFix[] = [
      { diagnosticCode: "SQL_UNKNOWN_OBJECT", title: "near", isPreferred: true,
        edits: [{ startOffset: 10, endOffset: 15, text: "orders" }] },
      { diagnosticCode: "SQL_UNKNOWN_OBJECT", title: "far", isPreferred: false,
        edits: [{ startOffset: 30, endOffset: 35, text: "archive" }] },
    ];
    expect(applicableSqlQuickFixes(diagnostics, fixes, new Set(["SQL_UNKNOWN_OBJECT"]), 10, 15))
      .toEqual([fixes[0]]);
  });
});
