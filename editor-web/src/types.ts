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
  environmentId: string;
  revision: string;
}

export interface ConnectionSystem { id: string; name: string; revision: string; }
export interface ConnectionEnvironment { id: string; systemId: string; name: string; revision: string; }
export type EditorConnectionState = "unbound" | "active" | "suspended";
export interface EditorConnectionBinding extends SavedProfile {
  unavailable?: boolean;
  stale?: boolean;
}

export interface ConnectionCatalog {
  systems: ConnectionSystem[];
  environments: ConnectionEnvironment[];
  profiles: SavedProfile[];
}

export interface BootstrapResponse {
  providers: ProviderInfo[];
  profiles: SavedProfile[];
  systems?: ConnectionSystem[];
  environments?: ConnectionEnvironment[];
  recentFiles: string[];
  settings: Record<string, string>;
}

export interface ConnectionInput {
  id?: string;
  providerId: string;
  name: string;
  environmentId: string;
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
  connection?: EditorConnectionBinding;
  connectionState: EditorConnectionState;
}

export interface QueryResult {
  resultIndex: number;
  sql: string;
  type: string;
  columns: string[];
  columnDetails?: QueryColumn[];
  rows: Array<Array<string | null>>;
  updateCount: number;
  truncated: boolean;
  durationMs: number;
  errorMessage?: string;
  complete: boolean;
}

export interface QueryColumn {
  label: string;
  name: string;
  remarks: string;
  catalog: string;
  schema: string;
  table: string;
  typeName: string;
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
