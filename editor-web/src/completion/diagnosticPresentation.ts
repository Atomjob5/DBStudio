import type { SqlDiagnostic, SqlQuickFix } from "../types";
import { completionDialect } from "./sqlDialect";
import { lexStatementAt } from "./sqlLexer";

export const SQL_DIAGNOSTIC_MARKER_OWNER = "dbstudio.sqlDiagnostics";

export interface DiagnosticRunIdentity {
  key: string;
  version: number;
  providerId: string;
  completionKey: string;
  completionRevision: string;
  completionMetadataReady: boolean;
}

export class SqlDiagnosticScheduler {
  private timer: ReturnType<typeof setTimeout> | undefined;
  private sequence = 0;

  schedule(task: (sequence: number) => void, delay = 400): number {
    this.sequence += 1;
    const scheduledSequence = this.sequence;
    if (this.timer !== undefined) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = undefined;
      task(scheduledSequence);
    }, delay);
    return scheduledSequence;
  }

  invalidate(): void {
    this.sequence += 1;
    if (this.timer !== undefined) clearTimeout(this.timer);
    this.timer = undefined;
  }

  isCurrent(sequence: number): boolean {
    return sequence === this.sequence;
  }
}

export function sameDiagnosticRun(left: DiagnosticRunIdentity, right: DiagnosticRunIdentity): boolean {
  return left.key === right.key && left.version === right.version && left.providerId === right.providerId
    && left.completionKey === right.completionKey && left.completionRevision === right.completionRevision
    && left.completionMetadataReady === right.completionMetadataReady;
}

export function filterServerSqlDiagnostics(sql: string, providerId: string, dangerousWarningEnabled: boolean,
                                           server: SqlDiagnostic[], local: SqlDiagnostic[]): SqlDiagnostic[] {
  return server.filter((diagnostic) => (dangerousWarningEnabled || diagnostic.code !== "SQL_DML_WITHOUT_WHERE")
    && !shadowedByLocalStructure(sql, providerId, diagnostic, local));
}

export function mergeSqlDiagnostics(local: SqlDiagnostic[], server: SqlDiagnostic[]): SqlDiagnostic[] {
  const seen = new Set<string>();
  return [...local, ...server].filter((diagnostic) => {
    const key = `${diagnostic.code}\u0000${diagnostic.startOffset}\u0000${diagnostic.endOffset}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function applicableSqlQuickFixes(diagnostics: SqlDiagnostic[], quickFixes: SqlQuickFix[],
                                        markerCodes: Set<string>, requestedStart: number,
                                        requestedEnd: number): SqlQuickFix[] {
  const selected = diagnostics.filter((diagnostic) => markerCodes.has(diagnostic.code)
    && rangesTouch(diagnostic.startOffset, diagnostic.endOffset, requestedStart, requestedEnd));
  return quickFixes.filter((fix) => selected.some((diagnostic) =>
    diagnostic.code === fix.diagnosticCode && quickFixMatchesDiagnostic(fix, diagnostic)));
}

export function sqlDiagnosticMarker<T extends object>(diagnostic: SqlDiagnostic, range: T,
                                                       errorSeverity: number, warningSeverity: number): T & {
  severity: number;
  message: string;
  source: string;
  code: string;
} {
  return {
    ...range,
    severity: diagnostic.severity === "error" ? errorSeverity : warningSeverity,
    message: diagnostic.message,
    source: "DBStudio SQL 诊断",
    code: diagnostic.code,
  };
}

function shadowedByLocalStructure(sql: string, providerId: string, server: SqlDiagnostic,
                                  local: SqlDiagnostic[]): boolean {
  if (server.code !== "SQL_SYNTAX_ERROR") return false;
  return local.some((diagnostic) => diagnostic.category === "syntax"
    && sameSqlStatement(sql, providerId, diagnostic.startOffset, server.startOffset));
}

function sameSqlStatement(sql: string, providerId: string, left: number, right: number): boolean {
  const statement = lexStatementAt(sql, left, completionDialect(providerId));
  return right >= statement.start && right <= statement.end;
}

function rangesTouch(leftStart: number, leftEnd: number, rightStart: number, rightEnd: number): boolean {
  return leftStart <= rightEnd && rightStart <= leftEnd;
}

function quickFixMatchesDiagnostic(fix: SqlQuickFix, diagnostic: SqlDiagnostic): boolean {
  if (fix.diagnosticCode === "SQL_UNCLOSED_STRING" || fix.diagnosticCode === "SQL_UNCLOSED_COMMENT"
      || fix.diagnosticCode === "SQL_UNMATCHED_PARENTHESIS") return true;
  return fix.edits.some((edit) => rangesTouch(edit.startOffset, edit.endOffset,
    diagnostic.startOffset, diagnostic.endOffset));
}
