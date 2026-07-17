export type FieldType = "TEXT" | "NUMBER" | "PASSWORD" | "BOOLEAN";
export type ThemePreference = "system" | "light" | "dark";
export type ResolvedTheme = "light" | "dark";

export interface ConnectionField {
  key: string;
  label: string;
  type: FieldType;
  required: boolean;
  defaultValue: string;
  description: string;
}

export interface ProviderInfo {
  id: string;
  displayName: string;
  fields: ConnectionField[];
  capabilities: string[];
}

export interface SavedProfile {
  id: string;
  providerId: string;
  name: string;
  settings: Record<string, string>;
  rememberPassword: boolean;
}

export interface BootstrapResponse {
  providers: ProviderInfo[];
  profiles: SavedProfile[];
  recentFiles: string[];
  settings: Record<string, string>;
  connectedProfile?: SavedProfile;
}

export interface ConnectionInput {
  id?: string;
  providerId: string;
  name: string;
  settings: Record<string, string>;
  password: string;
  rememberPassword: boolean;
}

export interface MetadataNode {
  id: string;
  label: string;
  kind: "catalog" | "group" | "object" | "column";
  leaf: boolean;
  catalog?: string;
  schema?: string;
  objectType?: string;
  name?: string;
  remarks?: string;
  detail?: string;
}

export interface Suggestion {
  label: string;
  insertText: string;
  detail: string;
  kind: "keyword" | "table" | "column" | "function";
}

export interface EditorTab {
  id: string;
  title: string;
  content: string;
  filePath?: string;
  fileHandle?: FileSystemFileHandle;
  dirty: boolean;
  transactionDirty: boolean;
  busy: boolean;
}

export interface QueryResult {
  resultIndex: number;
  sql: string;
  type: string;
  columns: string[];
  rows: Array<Array<string | null>>;
  updateCount: number;
  truncated: boolean;
  durationMs: number;
  errorMessage?: string;
  complete: boolean;
}

export interface QueryExecutionState {
  executionId: string;
  editorId: string;
  results: QueryResult[];
  busy: boolean;
  cancelled: boolean;
  failed: boolean;
  durationMs: number;
}

export interface HistoryEntry {
  sql: string;
  catalog?: string;
  executedAt: string;
  durationMs: number;
  status: string;
  rowCount: number;
  errorMessage?: string;
}

export interface CsvPreview {
  uploadId: string;
  name: string;
  delimiter: string;
  charset: string;
  headers: string[];
  rows: string[][];
}
