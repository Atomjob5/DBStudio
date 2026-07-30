export type FieldType = "TEXT" | "NUMBER" | "PASSWORD" | "BOOLEAN" | "SELECT";
export type ThemePreference = "system" | "light" | "dark";
export type ResolvedTheme = "light" | "dark";

export interface SqlTransformRange {
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
}

export interface SqlTransformTarget {
  modelKey: string;
  text: string;
  range: SqlTransformRange;
  versionId: number;
  selected: boolean;
  cursorOffset: number;
}

export type SqlTransformApplyResult = "applied" | "unchanged" | "stale" | "missing";
export type SqlEditorSelectionAction = "uppercase" | "lowercase" | "lineComment" | "blockComment";

export interface ConnectionField {
  key: string;
  label: string;
  type: FieldType;
  required: boolean;
  defaultValue: string;
  description: string;
  options?: Array<{ value: string; label: string }>;
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
export type EditorConnectionState = "unbound" | "ready" | "active" | "suspended" | "credentials-required" | "unavailable";
export type TransportState = "connecting" | "ready" | "reconnecting" | "recovering" | "offline";
export type TransactionState = "none" | "active" | "disconnected-protected" | "auto-rolled-back" | "lost";
export type WorkspaceState = "available" | "in-use" | "disconnected" | "disconnected-transaction";
export type RecoveryState = "none" | "unsaved-content" | "transaction-protected" | "transaction-rolled-back";
export interface WorkspaceSummary {
  id: string;
  name: string;
  createdAt: string;
  updatedAt: string;
  lastOpenedAt?: string;
  state: WorkspaceState;
  recoveryState: RecoveryState;
  unsavedEditorCount: number;
  transactionCount: number;
}
export interface RecoveredEditor {
  id: string;
  title: string;
  content: string;
  dirty: boolean;
  sortOrder: number;
  fileName?: string;
  filePath?: string;
  active: boolean;
  transactionState: TransactionState;
  connectionState: EditorConnectionState;
  connection?: EditorConnectionBinding;
}
export interface WorkspaceRecoverySummary {
  message: string;
  unsavedEditorCount: number;
  transactionCount: number;
  transactionRecoverable: boolean;
}
export interface WorkspaceOpenResponse {
  workspaceId: string;
  workspace?: WorkspaceSummary;
  recoveryDecisionRequired?: boolean;
  processRestarted?: boolean;
  transactionRolledBack?: boolean;
  recovery?: WorkspaceRecoverySummary;
  editors?: RecoveredEditor[];
}
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
  kind: "catalog" | "schema" | "group" | "object" | "column";
  leaf: boolean;
  catalog?: string;
  schema?: string;
  objectType?: string;
  name?: string;
  remarks?: string;
  detail?: string;
}

export interface CompletionNamespaceDescriptor {
  key: string;
  catalog: string;
  schema: string;
  label: string;
  kind: "catalog" | "schema";
  current: boolean;
  system: boolean;
}

export interface CompletionNamespacesResponse {
  providerId: string;
  sourceProfileId: string;
  namespaces: CompletionNamespaceDescriptor[];
}

export interface CompletionColumnSnapshot {
  name: string;
  typeName: string;
  remarks: string;
}

export interface CompletionObjectSnapshot {
  name: string;
  kind: "table" | "view";
  remarks: string;
  columns: CompletionColumnSnapshot[];
}

export interface CompletionNamespaceSnapshot {
  key: string;
  catalog: string;
  schema: string;
  label: string;
  objects: CompletionObjectSnapshot[];
}

export type CompletionCacheState = "empty" | "loading" | "ready" | "error";
export interface CompletionSnapshot {
  formatVersion: 1;
  providerId: string;
  sourceProfileId: string;
  generatedAt: string;
  defaultNamespaceKey: string;
  selectedNamespaceKeys: string[];
  namespaces: CompletionNamespaceSnapshot[];
}

export interface CompletionCacheSummary {
  providerId: string;
  sourceProfileId: string;
  generatedAt: string;
  selectedNamespaceKeys: string[];
  objectCount: number;
  columnCount: number;
  estimatedBytes: number;
  warning?: string;
}

export interface CompletionManifest extends CompletionCacheSummary {
  formatVersion: 2;
  cacheKey: string;
  activeGeneration: string;
  defaultNamespaceKey: string;
  namespaces: Array<{
    key: string;
    catalog: string;
    schema: string;
    label: string;
  }>;
}

export interface CompletionCandidate {
  displayLabel: string;
  documentationPath: string;
  insertText: string;
  filterText: string;
  kind: "keyword" | "schema" | "table" | "view" | "column" | "snippet";
  remarks: string;
  typeName: string;
}

export interface CompletionResult {
  items: CompletionCandidate[];
  incomplete: boolean;
}

export interface SqlCompletionSnippet {
  id: string;
  trigger: string;
  remarks: string;
  sql: string;
}

export interface ResultColumnRemarkLookup {
  index: number;
  catalog: string;
  schema: string;
  table: string;
  name: string;
}

export interface ResolvedResultColumnRemark {
  index: number;
  remarks: string;
}
export interface CompletionProgress {
  loadId: string;
  phase: "discovering" | "loading";
  completed: number;
  total: number;
  message: string;
  sourceProfileId?: string;
  environmentId?: string;
}
export interface CompletionCache {
  key: string;
  label: string;
  state: CompletionCacheState;
  hasSnapshot: boolean;
  summary?: CompletionCacheSummary;
  loadId?: string;
  sourceProfileId?: string;
  generatedAt?: string;
  progress?: CompletionProgress;
  error?: string;
  notice?: "loading" | "success" | "error";
  startedAt?: number;
}

export interface CompletionCacheStats {
  environmentCount: number;
  suggestionCount: number;
  estimatedBytes: number;
  loadingCount: number;
}

export interface EditorTab {
  id: string;
  title: string;
  content: string;
  filePath?: string;
  fileHandle?: FileSystemFileHandle;
  dirty: boolean;
  transactionDirty: boolean;
  resultChangesDirty?: boolean;
  transactionState?: TransactionState;
  busy: boolean;
  activeExecutionId?: string;
  executionPhase: "idle" | "starting" | "running" | "cancelling";
  transactionOperation: "idle" | "committing" | "rolling-back";
  connection?: EditorConnectionBinding;
  connectionState: EditorConnectionState;
}

export interface QueryResult {
  resultIndex: number;
  sql: string;
  type: string;
  columns: string[];
  columnDetails?: QueryColumn[];
  mutationTarget?: QueryMutationTarget;
  dialectId?: string;
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
  jdbcType?: number;
  quotedLabel?: string;
}

export interface SelectedResultColumn {
  label: string;
  name: string;
  remarks: string;
  typeName: string;
  catalog: string;
  schema: string;
  table: string;
}

export interface StatusBarTask {
  id: string;
  kind: string;
  label: string;
  message: string;
  state: "running" | "success" | "error";
  completed?: number;
  total?: number;
  startedAt: number;
  updatedAt: number;
  dismissible?: boolean;
}

export interface StatusBarSystemItem {
  id: string;
  label: string;
  message: string;
  tone: "neutral" | "running" | "success" | "warning" | "error";
  updatedAt: number;
  dismissible?: boolean;
}

export interface QueryMutationColumn {
  resultIndex: number;
  name: string;
  quotedName: string;
  jdbcType: number;
}

export interface QueryMutationKey {
  name: string;
  primary: boolean;
  resultColumnIndices: number[];
}

export interface QueryMutationTarget {
  qualifiedName: string;
  columns: QueryMutationColumn[];
  uniqueKeys: QueryMutationKey[];
  editableForUpdate?: boolean;
}

export interface QueryExecutionState {
  executionId: string;
  editorId: string;
  results: QueryResult[];
  busy: boolean;
  cancelled: boolean;
  failed: boolean;
  durationMs: number;
  historical?: boolean;
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
