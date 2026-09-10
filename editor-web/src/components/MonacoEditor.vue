<template><div class="monaco-shell fill"><div ref="container" class="monaco-host fill" />
  <ObjectInspectorHost ref="objectInspectors" :opacity="objectInspectorOpacity"
    @update:opacity="$emit('update:objectInspectorOpacity', $event)"
    @save-opacity="$emit('saveObjectInspectorOpacity', $event)" /></div></template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import * as monaco from "../monaco";
import ObjectInspectorHost, { type ObjectInspectorOpenRequest } from "./ObjectInspectorHost.vue";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import type {
  CompletionCandidate,
  CompletionResult,
  QueryExecutionSource,
  LocalSqlDiagnostics,
  SqlDiagnostic,
  SqlDiagnosticsResponse,
  SqlEditorSelectionAction,
  SqlQuickFix,
  SqlTransformApplyResult,
  SqlTransformTarget,
} from "../types";
import { completionClient } from "../completion/client";
import { CompletionModelSynchronizer, isModelVersionChanged } from "../completion/modelSynchronizer";
import { completionDocumentation, truncateCompletionComment } from "../completion/presentation";
import { compileSqlSnippet, matchingSnippetCandidates } from "../completion/snippets";
import type { SqlCompletionSnippet } from "../types";
import { resolveSqlObjectReference } from "../objectReference";
import type { ModeColorScheme, TextStyle } from "../appearance";
import { DEFAULT_COLOR_SCHEMES, fontFamilyCss } from "../appearance";
import { rpc } from "../bridge/rpc";
import {
  applicableSqlQuickFixes,
  filterServerSqlDiagnostics,
  mergeSqlDiagnostics,
  sameDiagnosticRun,
  sqlDiagnosticMarker,
  SqlDiagnosticScheduler,
  SQL_DIAGNOSTIC_MARKER_OWNER,
} from "../completion/diagnosticPresentation";
import type { DiagnosticRunIdentity } from "../completion/diagnosticPresentation";

(self as typeof self & { MonacoEnvironment: object }).MonacoEnvironment = { getWorker: () => new EditorWorker() };

const props = withDefaults(defineProps<{ modelKey: string; initialValue: string; theme: "dark" | "light"; appearance?: ModeColorScheme;
  completionKey: string; providerId: string; completionCandidateLimit: number;
  completionRevision: string; completionMetadataReady: boolean;
  completionPreciseMatchingEnabled: boolean; completionSnippets: SqlCompletionSnippet[];
  minimapEnabled: boolean; wordWrapEnabled: boolean; rainbowBracketsEnabled: boolean; diagnosticsEnabled: boolean;
  dangerousStatementWarningEnabled: boolean; editorId: string; connectionDisplay: string;
  defaultCatalog?: string; defaultSchema?: string; objectInspectorOpacity: number }>(), {
  appearance: () => DEFAULT_COLOR_SCHEMES.light,
  completionRevision: "",
  completionMetadataReady: false,
  diagnosticsEnabled: true,
  dangerousStatementWarningEnabled: true,
});
const emit = defineEmits<{
  dirty: [change: { editorId: string; content: string }];
  execute: [scope: "current" | "script" | "current-new-tab" | "explain", selection: string, cursorOffset: number,
    selectionStartOffset: number];
  "selection-change": [selected: boolean];
  "update:objectInspectorOpacity": [value: number];
  saveObjectInspectorOpacity: [value: number];
}>();
const container = ref<HTMLElement>();
const instance = shallowRef<monaco.editor.IStandaloneCodeEditor>();
const objectInspectors = ref<InstanceType<typeof ObjectInspectorHost>>();
const models = new Map<string, monaco.editor.ITextModel>();
const modelKeys = new WeakMap<monaco.editor.ITextModel, string>();
const viewStates = new Map<string, monaco.editor.ICodeEditorViewState>();
const mirrorListeners = new Map<string, monaco.IDisposable>();
interface SourceAnchor extends QueryExecutionSource { modelKey: string; decorationId: string; }
const sourceAnchors = new Map<string, Map<string, SourceAnchor>>();
let resultHighlight: { executionId: string; modelKey: string; decorationId: string } | undefined;
const modelSynchronizer = new CompletionModelSynchronizer(completionClient);
let contentListener: monaco.IDisposable | undefined;
let completionProvider: monaco.IDisposable | undefined;
let codeActionProvider: monaco.IDisposable | undefined;
let selectionListener: monaco.IDisposable | undefined;
let changingModel = false;
let objectHoverDecoration: string[] = [];
let objectMouseMove: monaco.IDisposable | undefined;
let objectMouseDown: monaco.IDisposable | undefined;
let objectScroll: monaco.IDisposable | undefined;
const diagnosticStates = new Map<string, { version: number; diagnostics: SqlDiagnostic[]; quickFixes: SqlQuickFix[] }>();
const diagnosticScheduler = new SqlDiagnosticScheduler();

monaco.editor.defineTheme("dbstudio-apple-light", {
  base: "vs",
  inherit: true,
  rules: [
    { token: "keyword", foreground: "9B2393", fontStyle: "bold" },
    { token: "string", foreground: "C41A16" },
    { token: "number", foreground: "1C00CF" },
    { token: "comment", foreground: "6C7986", fontStyle: "italic" },
    { token: "identifier.quote", foreground: "0F68A0" }
  ],
  colors: {
    "editor.background": "#FFFFFF",
    "editor.foreground": "#1D1D1F",
    "editorLineNumber.foreground": "#A1A1A6",
    "editorLineNumber.activeForeground": "#6E6E73",
    "editor.selectionBackground": "#B8D9F8",
    "editor.inactiveSelectionBackground": "#DCECFB",
    "editor.lineHighlightBackground": "#F5F5F7",
    "editorCursor.foreground": "#0071E3",
    "editorIndentGuide.background1": "#E5E5EA",
    "editorIndentGuide.activeBackground1": "#C7C7CC"
  }
});

monaco.editor.defineTheme("dbstudio-apple-dark", {
  base: "vs-dark",
  inherit: true,
  rules: [
    { token: "keyword", foreground: "FC5FA3", fontStyle: "bold" },
    { token: "string", foreground: "FC6A5D" },
    { token: "number", foreground: "D0BF69" },
    { token: "comment", foreground: "7F8C98", fontStyle: "italic" },
    { token: "identifier.quote", foreground: "5DD8FF" }
  ],
  colors: {
    "editor.background": "#111113",
    "editor.foreground": "#F5F5F7",
    "editorLineNumber.foreground": "#636366",
    "editorLineNumber.activeForeground": "#A1A1A6",
    "editor.selectionBackground": "#264F78",
    "editor.inactiveSelectionBackground": "#1C3A57",
    "editor.lineHighlightBackground": "#19191C",
    "editorCursor.foreground": "#2997FF",
    "editorIndentGuide.background1": "#2C2C2E",
    "editorIndentGuide.activeBackground1": "#48484A"
  }
});

function monacoTheme(theme: "dark" | "light"): string {
  return theme === "dark" ? "dbstudio-apple-dark" : "dbstudio-apple-light";
}

function tokenFontStyle(value: TextStyle): string {
  return [value.bold ? "bold" : "", value.italic ? "italic" : ""].filter(Boolean).join(" ");
}

function appearanceTheme(theme: "dark" | "light", scheme: ModeColorScheme): string {
  const id = `dbstudio-appearance-${theme}`;
  const editor = scheme.editor;
  monaco.editor.defineTheme(id, {
    base: theme === "dark" ? "vs-dark" : "vs",
    inherit: true,
    rules: [
      { token: "keyword", foreground: editor.keyword.color.slice(1), fontStyle: tokenFontStyle(editor.keyword) },
      { token: "identifier", foreground: editor.identifier.color.slice(1), fontStyle: tokenFontStyle(editor.identifier) },
      { token: "string", foreground: editor.string.color.slice(1), fontStyle: tokenFontStyle(editor.string) },
      { token: "number", foreground: editor.number.color.slice(1), fontStyle: tokenFontStyle(editor.number) },
      { token: "comment", foreground: editor.comment.color.slice(1), fontStyle: tokenFontStyle(editor.comment) },
      { token: "identifier.quote", foreground: editor.quotedIdentifier.color.slice(1), fontStyle: tokenFontStyle(editor.quotedIdentifier) },
    ],
    colors: {
      "editor.background": editor.background,
      "editor.foreground": editor.foreground,
      "editorLineNumber.foreground": editor.lineNumber,
      "editorLineNumber.activeForeground": editor.activeLineNumber,
      "editor.selectionBackground": editor.selection,
      "editor.inactiveSelectionBackground": editor.selection,
      "editor.lineHighlightBackground": editor.lineHighlight,
      "editorCursor.foreground": editor.cursor,
    },
  });
  return id;
}

if (!monaco.languages.getLanguages().some((item) => item.id === "dbstudio-mysql")) {
  monaco.languages.register({ id: "dbstudio-mysql" });
  monaco.languages.setMonarchTokensProvider("dbstudio-mysql", {
    ignoreCase: true,
    keywords: ["select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "alter", "drop", "table", "view", "index", "join", "left", "right", "inner", "on", "group", "by", "order", "having", "limit", "union", "all", "distinct", "as", "and", "or", "not", "null", "is", "in", "exists", "between", "like", "case", "when", "then", "else", "end", "with", "procedure", "function", "trigger", "begin", "commit", "rollback", "explain", "show", "describe"],
    tokenizer: { root: [[/[a-zA-Z_$][\w$]*/, { cases: { "@keywords": "keyword", "@default": "identifier" } }], [/`([^`]|``)*`/, "identifier.quote"], [/--.*$/, "comment"], [/#.*$/, "comment"], [/\/\*/, "comment", "@comment"], [/'([^'\\]|\\.)*'/, "string"], [/"([^"\\]|\\.)*"/, "string"], [/\d+(\.\d+)?/, "number"]], comment: [[/[^/*]+/, "comment"], [/\*\//, "comment", "@pop"], [/[/*]/, "comment"]] }
  });
  monaco.languages.setLanguageConfiguration("dbstudio-mysql", {
    // Preserve the existing SQL auto-closing behavior when declaring bracket pairs.
    autoClosingPairs: [],
    brackets: [["(", ")"], ["[", "]"], ["{", "}"]],
    colorizedBracketPairs: [["(", ")"], ["[", "]"], ["{", "}"]],
    comments: {
      lineComment: "--",
      blockComment: ["/*", "*/"],
    },
  });
}

onMounted(() => {
  instance.value = monaco.editor.create(container.value!, {
    language: "dbstudio-mysql",
    theme: appearanceTheme(props.theme, props.appearance),
    automaticLayout: true,
    fontFamily: fontFamilyCss(props.appearance.editor.fontFamily),
    fontSize: props.appearance.editor.fontSize,
    lineHeight: props.appearance.editor.lineHeight,
    fontLigatures: true,
    minimap: { enabled: props.minimapEnabled },
    wordWrap: props.wordWrapEnabled ? "on" : "off",
    bracketPairColorization: { enabled: props.rainbowBracketsEnabled, independentColorPoolPerBracketType: false },
    guides: { bracketPairs: false },
    scrollBeyondLastLine: false,
    smoothScrolling: true,
    fixedOverflowWidgets: true,
    renderWhitespace: "selection",
    cursorBlinking: "smooth",
    cursorSmoothCaretAnimation: "on",
    padding: { top: 10, bottom: 10 },
    tabSize: 2,
    comments: {
      insertSpace: true,
      ignoreEmptyLines: true,
    },
  });
  selectionListener = instance.value.onDidChangeCursorSelection(emitSelectionState);
  objectMouseMove = instance.value.onMouseMove(handleObjectHover);
  objectMouseDown = instance.value.onMouseDown(handleObjectClick);
  objectScroll = instance.value.onDidScrollChange(() => objectInspectors.value?.closeTransient());
  completionProvider = monaco.languages.registerCompletionItemProvider("dbstudio-mysql", {
    triggerCharacters: [".", "`", "\"", " "],
    async provideCompletionItems(model, position, _context, token) {
      const word = model.getWordUntilPosition(position);
      const snippetWord = snippetWordAt(model, position);
      const snippetEntry: CompletionEntry[] = matchingSnippetCandidates(
        props.completionSnippets,
        snippetWord.word,
      ).flatMap((snippet) => {
        const compilation = compileSqlSnippet(snippet.insertText);
        if (!compilation.ok) return [];
        return [{
          item: snippet,
          range: completionRange(position, snippetWord.startColumn, position.column),
          insertText: compilation.compilation.hasVariables
            ? compilation.compilation.insertText : undefined,
          insertAsSnippet: compilation.compilation.hasVariables,
        }];
      });
      if (!props.completionKey || props.completionKey === "unbound") {
        return { suggestions: completionSuggestions(snippetEntry) };
      }
      const modelKey = modelKeys.get(model);
      if (!modelKey) return { suggestions: completionSuggestions(snippetEntry) };
      const modelVersion = model.getVersionId();
      const cursorOffset = model.getOffsetAt(position);
      let result: CompletionResult;
      try {
        result = await modelSynchronizer.execute(modelKey, model, modelVersion,
          () => completionClient.complete(props.completionKey, props.providerId, modelKey,
            modelVersion, cursorOffset, word.word, props.completionCandidateLimit,
            props.completionPreciseMatchingEnabled));
      } catch (error) {
        if (isModelVersionChanged(error)) return { suggestions: completionSuggestions(snippetEntry) };
        if (snippetEntry.length) return { suggestions: completionSuggestions(snippetEntry) };
        throw error;
      }
      if (token.isCancellationRequested) return { suggestions: [] };
      const databaseEntries = result.items.map((item) => ({
        item: !props.completionPreciseMatchingEnabled && word.word
          ? { ...item, filterText: word.word } : item,
        range: completionRange(position, word.startColumn, word.endColumn),
      }));
      return {
        incomplete: result.incomplete,
        suggestions: completionSuggestions([...snippetEntry, ...databaseEntries]),
      };
    }
  });
  codeActionProvider = monaco.languages.registerCodeActionProvider("dbstudio-mysql", {
    provideCodeActions(model, range, context) {
      const key = modelKeys.get(model);
      const state = key ? diagnosticStates.get(key) : undefined;
      if (!key || !state || state.version !== model.getVersionId()) {
        return { actions: [], dispose: () => undefined };
      }
      const requestedStart = model.getOffsetAt(range.getStartPosition());
      const requestedEnd = model.getOffsetAt(range.getEndPosition());
      const markerCodes = new Set(context.markers.map((marker) => markerCode(marker.code)).filter(Boolean));
      const actions = applicableSqlQuickFixes(state.diagnostics, state.quickFixes, markerCodes,
        requestedStart, requestedEnd)
        .map((fix): monaco.languages.CodeAction => ({
          title: fix.title,
          kind: "quickfix",
          isPreferred: fix.isPreferred,
          diagnostics: context.markers.filter((marker) => markerCode(marker.code) === fix.diagnosticCode),
          edit: { edits: fix.edits.map((edit) => ({
            resource: model.uri,
            textEdit: { range: offsetRange(model, edit.startOffset, edit.endOffset), text: edit.text },
            versionId: model.getVersionId(),
          })) },
        }));
      return { actions, dispose: () => undefined };
    },
  }, { providedCodeActionKinds: ["quickfix"] });
  switchModel(props.modelKey, props.initialValue);
});

interface CompletionEntry {
  item: CompletionCandidate;
  range: monaco.IRange;
  insertText?: string;
  insertAsSnippet?: boolean;
}

function completionSuggestions(entries: CompletionEntry[]): monaco.languages.CompletionItem[] {
  return entries.map(({ item, range, insertText, insertAsSnippet }, index) => ({
        label: {
          label: item.displayLabel,
          detail: item.remarks ? `  ${truncateCompletionComment(item.remarks)}` : undefined,
          description: item.typeName || item.kind.toUpperCase()
        },
        insertText: insertText ?? item.insertText,
        insertTextRules: insertAsSnippet
          ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet : undefined,
        filterText: item.filterText,
        sortText: String(index).padStart(5, "0"),
        documentation: { value: completionDocumentation(item) },
        kind: completionKind(item.kind),
        range,
      }));
}

function completionRange(position: monaco.Position, startColumn: number, endColumn: number): monaco.IRange {
  return {
    startLineNumber: position.lineNumber,
    endLineNumber: position.lineNumber,
    startColumn,
    endColumn,
  };
}

function snippetWordAt(model: monaco.editor.ITextModel, position: monaco.Position): {
  word: string;
  startColumn: number;
} {
  const beforeCursor = model.getLineContent(position.lineNumber).slice(0, position.column - 1);
  const match = beforeCursor.match(/[\p{L}\p{N}_$]+$/u);
  return {
    word: match?.[0] ?? "",
    startColumn: match ? position.column - match[0].length : position.column,
  };
}

function completionKind(kind: CompletionCandidate["kind"]): monaco.languages.CompletionItemKind {
  if (kind === "snippet") return monaco.languages.CompletionItemKind.Snippet;
  if (kind === "column") return monaco.languages.CompletionItemKind.Field;
  if (kind === "schema") return monaco.languages.CompletionItemKind.Module;
  if (kind === "view") return monaco.languages.CompletionItemKind.Interface;
  if (kind === "table") return monaco.languages.CompletionItemKind.Class;
  return monaco.languages.CompletionItemKind.Keyword;
}

watch(() => props.modelKey, (key) => switchModel(key, props.initialValue));
watch(() => props.theme, (theme) => {
  if (!instance.value) return;
  monaco.editor.setTheme(appearanceTheme(theme, props.appearance));
});
watch(() => props.appearance, (scheme) => {
  if (!instance.value) return;
  monaco.editor.setTheme(appearanceTheme(props.theme, scheme));
  instance.value.updateOptions({
    fontFamily: fontFamilyCss(scheme.editor.fontFamily),
    fontSize: scheme.editor.fontSize,
    lineHeight: scheme.editor.lineHeight,
  });
}, { deep: true });
watch(() => props.minimapEnabled, (enabled) => instance.value?.updateOptions({ minimap: { enabled } }));
watch(() => props.wordWrapEnabled, (enabled) => instance.value?.updateOptions({ wordWrap: enabled ? "on" : "off" }));
watch(() => props.rainbowBracketsEnabled, (enabled) => {
  instance.value?.updateOptions({ bracketPairColorization: { enabled, independentColorPoolPerBracketType: false } });
  // Explicitly created models keep their own colorization options.
  models.forEach((model) => model.updateOptions({
    bracketColorizationOptions: { enabled, independentColorPoolPerBracketType: false },
  }));
});
watch(() => [props.completionKey, props.providerId, props.completionRevision, props.completionMetadataReady], () => {
  const model = instance.value?.getModel();
  const key = model && modelKeys.get(model);
  if (model && key && isCompletionBound()) {
    synchronizeInBackground(key, model);
    scheduleDiagnostics(key, model, 0);
  } else clearAllDiagnostics();
});
watch(() => props.diagnosticsEnabled, (enabled) => {
  if (!enabled) { clearAllDiagnostics(); return; }
  const model = instance.value?.getModel();
  const key = model && modelKeys.get(model);
  if (model && key) scheduleDiagnostics(key, model, 0);
});
watch(() => props.dangerousStatementWarningEnabled, () => {
  const model = instance.value?.getModel();
  const key = model && modelKeys.get(model);
  if (model && key) scheduleDiagnostics(key, model, 0);
});

function scheduleDiagnostics(key: string, model: monaco.editor.ITextModel, delay = 400): void {
  clearModelDiagnostics(key, model);
  diagnosticScheduler.invalidate();
  if (!props.diagnosticsEnabled || !isCompletionBound() || props.providerId === "generic"
      || instance.value?.getModel() !== model) return;
  const snapshot: DiagnosticRunIdentity = {
    key,
    version: model.getVersionId(),
    providerId: props.providerId,
    completionKey: props.completionKey,
    completionRevision: props.completionRevision,
    completionMetadataReady: props.completionMetadataReady,
  };
  diagnosticScheduler.schedule((sequence) => void runDiagnostics(model, snapshot, sequence), delay);
}

async function runDiagnostics(model: monaco.editor.ITextModel,
                              snapshot: DiagnosticRunIdentity,
                              sequence: number): Promise<void> {
  const localRequest = modelSynchronizer.execute(snapshot.key, model, snapshot.version,
    () => completionClient.diagnose(snapshot.completionKey, snapshot.providerId, snapshot.key, snapshot.version,
      snapshot.completionMetadataReady));
  const serverRequest = rpc.request<SqlDiagnosticsResponse>("sql.diagnostics", {
    editorId: snapshot.key, text: model.getValue(), modelVersion: snapshot.version,
  });
  const [localResult, serverResult] = await Promise.allSettled([localRequest, serverRequest]);
  const current: DiagnosticRunIdentity = { key: props.modelKey, version: model.getVersionId(),
    providerId: props.providerId, completionKey: props.completionKey,
    completionRevision: props.completionRevision,
    completionMetadataReady: props.completionMetadataReady };
  if (!diagnosticScheduler.isCurrent(sequence) || instance.value?.getModel() !== model
      || !sameDiagnosticRun(snapshot, current) || !props.diagnosticsEnabled) return;

  const local: LocalSqlDiagnostics = localResult.status === "fulfilled"
    ? localResult.value : { diagnostics: [], quickFixes: [] };
  const server = serverResult.status === "fulfilled"
      && serverResult.value.modelVersion === snapshot.version
      && serverResult.value.providerId === snapshot.providerId
    ? serverResult.value.diagnostics : [];
  const filteredServer = filterServerSqlDiagnostics(model.getValue(), snapshot.providerId,
    props.dangerousStatementWarningEnabled, server, local.diagnostics);
  const diagnostics = mergeSqlDiagnostics(local.diagnostics, filteredServer);
  diagnosticStates.set(snapshot.key, { version: snapshot.version, diagnostics,
    quickFixes: local.quickFixes });
  monaco.editor.setModelMarkers(model, SQL_DIAGNOSTIC_MARKER_OWNER, diagnostics.map((diagnostic) =>
    sqlDiagnosticMarker(diagnostic, offsetRange(model, diagnostic.startOffset, diagnostic.endOffset),
      monaco.MarkerSeverity.Error, monaco.MarkerSeverity.Warning)));
}

function clearModelDiagnostics(key: string, model: monaco.editor.ITextModel): void {
  monaco.editor.setModelMarkers(model, SQL_DIAGNOSTIC_MARKER_OWNER, []);
  diagnosticStates.delete(key);
}

function clearAllDiagnostics(): void {
  diagnosticScheduler.invalidate();
  for (const [key, model] of models) clearModelDiagnostics(key, model);
}

function offsetRange(model: monaco.editor.ITextModel, rawStart: number, rawEnd: number): monaco.Range {
  const startOffset = Math.max(0, Math.min(model.getValueLength(), rawStart));
  const endOffset = Math.max(startOffset, Math.min(model.getValueLength(), rawEnd));
  const start = model.getPositionAt(startOffset);
  const end = model.getPositionAt(endOffset === startOffset
    ? Math.min(model.getValueLength(), startOffset + 1) : endOffset);
  return new monaco.Range(start.lineNumber, start.column, end.lineNumber, end.column);
}

function markerCode(value: string | { value: string } | undefined): string {
  return typeof value === "string" ? value : value?.value ?? "";
}

function switchModel(key: string, value: string): void {
  if (!instance.value || !key) return;
  clearObjectHover(); objectInspectors.value?.closeTransient();
  const previousModel = instance.value.getModel();
  const previousKey = previousModel && modelKeys.get(previousModel);
  if (previousKey) {
    const state = instance.value.saveViewState();
    if (state) viewStates.set(previousKey, state);
  }
  changingModel = true;
  let model = models.get(key);
  if (!model) {
    model = monaco.editor.createModel(value, "dbstudio-mysql", monaco.Uri.parse(`inmemory://dbstudio/${key}.sql`));
    models.set(key, model);
    modelKeys.set(model, key);
    registerMirrorListener(key, model);
  }
  model.updateOptions({
    bracketColorizationOptions: { enabled: props.rainbowBracketsEnabled, independentColorPoolPerBracketType: false },
  });
  instance.value.setModel(model);
  const viewState = viewStates.get(key);
  if (viewState) instance.value.restoreViewState(viewState);
  contentListener?.dispose();
  contentListener = model.onDidChangeContent(() => {
    if (!changingModel) emit("dirty", { editorId: key, content: model.getValue() });
  });
  changingModel = false;
  if (isCompletionBound()) {
    synchronizeInBackground(key, model);
    scheduleDiagnostics(key, model, 0);
  } else clearModelDiagnostics(key, model);
  emitSelectionState();
  instance.value.focus();
}

function isCompletionBound(): boolean {
  return Boolean(props.completionKey && props.completionKey !== "unbound");
}

function registerMirrorListener(key: string, model: monaco.editor.ITextModel): void {
  if (mirrorListeners.has(key)) return;
  mirrorListeners.set(key, model.onDidChangeContent((event) => {
    clearModelDiagnostics(key, model);
    if (isCompletionBound()) {
      modelSynchronizer.change(key, event.versionId, event.changes.map((change) => ({
        rangeOffset: change.rangeOffset, rangeLength: change.rangeLength, text: change.text
      })));
      if (instance.value?.getModel() === model) scheduleDiagnostics(key, model);
    }
  }));
}

function synchronizeInBackground(key: string, model: monaco.editor.ITextModel): void {
  modelSynchronizer.synchronizeInBackground(key, model);
}

function trigger(scope: "current" | "script" | "current-new-tab" | "explain"): void {
  const editor = instance.value;
  const model = editor?.getModel();
  if (!editor || !model) return;
  const selection = editor.getSelection();
  const selected = selection && !selection.isEmpty() ? model.getValueInRange(selection) : "";
  const position = editor.getPosition();
  emit("execute", scope, selected, position ? model.getOffsetAt(position) : 0,
    selection && !selection.isEmpty() ? model.getOffsetAt(selection.getStartPosition()) : 0);
}

function triggerCompletion(): void {
  instance.value?.focus();
  void instance.value?.trigger("shortcut", "editor.action.triggerSuggest", {});
}

const selectionActionCommands: Record<SqlEditorSelectionAction, string> = {
  uppercase: "editor.action.transformToUppercase",
  lowercase: "editor.action.transformToLowercase",
  lineComment: "editor.action.commentLine",
  blockComment: "editor.action.blockComment",
};

function emitSelectionState(): void {
  const selection = instance.value?.getSelection();
  emit("selection-change", Boolean(selection && !selection.isEmpty()));
}

function runSelectionAction(action: SqlEditorSelectionAction): boolean {
  const editor = instance.value;
  const model = editor?.getModel();
  const selection = editor?.getSelection();
  if (!editor || !model || !selection || selection.isEmpty()) {
    emitSelectionState();
    return false;
  }
  const selectedText = model.getValueInRange(selection);
  if ((action === "uppercase" && selectedText === selectedText.toLocaleUpperCase())
      || (action === "lowercase" && selectedText === selectedText.toLocaleLowerCase())) {
    editor.focus();
    return false;
  }
  editor.focus();
  editor.trigger("dbstudio.selectionAction", selectionActionCommands[action], null);
  return true;
}

function getValue(key = props.modelKey): string | undefined {
  return models.get(key)?.getValue();
}

function setValue(value: string, key = props.modelKey): void {
  const model = models.get(key);
  if (!model || model.getValue() === value) return;
  changingModel = true;
  model.setValue(value);
  changingModel = false;
  if (isCompletionBound()) synchronizeInBackground(key, model);
}

function sourceKey(source: Pick<QueryExecutionSource, "sql" | "startOffset" | "endOffset">): string {
  return `${source.startOffset}:${source.endOffset}:${source.sql}`;
}

function clearResultHighlight(): void {
  if (!resultHighlight) return;
  const model = models.get(resultHighlight.modelKey);
  model?.deltaDecorations([resultHighlight.decorationId], []);
  resultHighlight = undefined;
}

function registerExecutionSources(executionId: string, sources: QueryExecutionSource[], key = props.modelKey): void {
  releaseExecutionSources(executionId);
  const model = models.get(key);
  if (!model) return;
  const valid = sources.filter((source) => Number.isInteger(source.startOffset)
    && Number.isInteger(source.endOffset) && source.startOffset >= 0
    && source.endOffset > source.startOffset && source.endOffset <= model.getValue().length
    && model.getValue().slice(source.startOffset, source.endOffset) === source.sql);
  if (!valid.length) return;
  const decorations = valid.map((source) => {
    const start = model.getPositionAt(source.startOffset);
    const end = model.getPositionAt(source.endOffset);
    return {
      range: {
        startLineNumber: start.lineNumber, startColumn: start.column,
        endLineNumber: end.lineNumber, endColumn: end.column,
      },
      options: { stickiness: monaco.editor.TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges },
    };
  });
  const ids = model.deltaDecorations([], decorations);
  const anchors = new Map<string, SourceAnchor>();
  valid.forEach((source, index) => anchors.set(sourceKey(source), {
    ...source, modelKey: key, decorationId: ids[index]
  }));
  sourceAnchors.set(executionId, anchors);
}

function releaseExecutionSources(executionId: string): void {
  const anchors = sourceAnchors.get(executionId);
  if (!anchors) return;
  const byModel = new Map<string, string[]>();
  anchors.forEach((anchor) => byModel.set(anchor.modelKey,
    [...(byModel.get(anchor.modelKey) ?? []), anchor.decorationId]));
  byModel.forEach((ids, key) => models.get(key)?.deltaDecorations(ids, []));
  sourceAnchors.delete(executionId);
  if (resultHighlight?.executionId === executionId) clearResultHighlight();
}

function releaseEditorSources(key = props.modelKey): void {
  for (const [executionId, anchors] of sourceAnchors) {
    const ids = [...anchors.values()].filter((anchor) => anchor.modelKey === key)
      .map((anchor) => anchor.decorationId);
    if (!ids.length) continue;
    models.get(key)?.deltaDecorations(ids, []);
    const remaining = new Map([...anchors].filter(([, anchor]) => anchor.modelKey !== key));
    if (remaining.size) sourceAnchors.set(executionId, remaining);
    else sourceAnchors.delete(executionId);
  }
  if (resultHighlight?.modelKey === key) clearResultHighlight();
}

function releaseModel(key: string): void {
  const model = models.get(key);
  if (!model || instance.value?.getModel() === model) return;
  objectInspectors.value?.sourceReleased(key);
  releaseEditorSources(key);
  mirrorListeners.get(key)?.dispose();
  mirrorListeners.delete(key);
  clearModelDiagnostics(key, model);
  models.delete(key);
  viewStates.delete(key);
  model.dispose();
}

function platformModifier(event: MouseEvent): boolean {
  const mac = /Mac|iPhone|iPad/.test(navigator.platform);
  return mac ? event.metaKey : event.ctrlKey;
}

function objectAt(position: monaco.Position) {
  const model = instance.value?.getModel();
  if (!model) return null;
  return resolveSqlObjectReference(model.getValue(), model.getOffsetAt(position), props.providerId, {
    catalog: props.defaultCatalog, schema: props.defaultSchema,
  });
}

function handleObjectHover(event: monaco.editor.IEditorMouseEvent): void {
  const browserEvent = event.event.browserEvent;
  if (!event.target.position || !platformModifier(browserEvent)) { clearObjectHover(); return; }
  const reference = objectAt(event.target.position);
  if (!reference) { clearObjectHover(); return; }
  const model = instance.value?.getModel(); if (!model) return;
  const start = model.getPositionAt(reference.range.start), end = model.getPositionAt(reference.range.end);
  objectHoverDecoration = model.deltaDecorations(objectHoverDecoration, [{ range: new monaco.Range(
    start.lineNumber, start.column, end.lineNumber, end.column), options: {
      inlineClassName: "dbstudio-object-link", stickiness: monaco.editor.TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
    } }]);
}

function handleObjectClick(event: monaco.editor.IEditorMouseEvent): void {
  const browserEvent = event.event.browserEvent;
  if (!event.target.position || !platformModifier(browserEvent)) return;
  const reference = objectAt(event.target.position); if (!reference || !props.editorId) return;
  browserEvent.preventDefault(); browserEvent.stopPropagation(); clearObjectHover();
  const request: ObjectInspectorOpenRequest = { reference, editorId: props.editorId, modelKey: props.modelKey,
    connectionDisplay: props.connectionDisplay, x: browserEvent.clientX, y: browserEvent.clientY };
  objectInspectors.value?.open(request);
}

function clearObjectHover(): void {
  const model = instance.value?.getModel();
  if (model && objectHoverDecoration.length) model.deltaDecorations(objectHoverDecoration, []);
  objectHoverDecoration = [];
}

function highlightExecutionSource(executionId: string, sql: string, startOffset?: number,
                                  endOffset?: number, options: { reveal?: boolean } = {}): "highlighted" | "stale" | "missing" {
  clearResultHighlight();
  if (startOffset === undefined || endOffset === undefined) return "missing";
  const anchors = sourceAnchors.get(executionId);
  const anchor = anchors?.get(sourceKey({ sql, startOffset, endOffset }));
  if (!anchor) return "missing";
  const model = models.get(props.modelKey);
  const range = model?.getDecorationRange(anchor.decorationId);
  if (!model || !range) return "missing";
  if (model.getValueInRange(range) !== sql) return "stale";
  const ids = model.deltaDecorations([], [{ range, options: {
    className: "dbstudio-sql-result-highlight",
    stickiness: monaco.editor.TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
  } }]);
  resultHighlight = { executionId, modelKey: props.modelKey, decorationId: ids[0] };
  if (options.reveal && instance.value?.getModel() === model) {
    instance.value.revealRangeInCenterIfOutsideViewport(range, monaco.editor.ScrollType.Smooth);
  }
  return "highlighted";
}

function captureSqlTransformTarget(key = props.modelKey): SqlTransformTarget | undefined {
  const editor = instance.value;
  const model = models.get(key);
  if (!editor || !model || editor.getModel() !== model) return undefined;
  const selection = editor.getSelection();
  const selected = Boolean(selection && !selection.isEmpty());
  const range = selected && selection ? selection : model.getFullModelRange();
  const position = editor.getPosition();
  return {
    modelKey: key,
    text: model.getValueInRange(range),
    range: {
      startLineNumber: range.startLineNumber,
      startColumn: range.startColumn,
      endLineNumber: range.endLineNumber,
      endColumn: range.endColumn,
    },
    versionId: model.getVersionId(),
    selected,
    cursorOffset: position ? model.getOffsetAt(position) : 0,
  };
}

function applySqlTransform(target: SqlTransformTarget, replacement: string): SqlTransformApplyResult {
  const model = models.get(target.modelKey);
  if (!model) return "missing";
  if (model.getVersionId() !== target.versionId) return "stale";
  if (target.text === replacement) return "unchanged";
  const edit = { range: target.range, text: replacement, forceMoveMarkers: true };
  const editor = instance.value;
  const active = editor?.getModel() === model;
  const selection = transformedSelection(target, replacement);
  if (active && editor) {
    editor.pushUndoStop();
    editor.executeEdits("dbstudio.sqlTransform", [edit], () => [selection]);
    editor.pushUndoStop();
    editor.focus();
  } else {
    model.pushStackElement();
    model.pushEditOperations([], [edit], () => null);
    model.pushStackElement();
  }
  return "applied";
}

function transformedSelection(target: SqlTransformTarget, replacement: string): monaco.Selection {
  if (target.selected) {
    const end = positionAfterText(target.range.startLineNumber, target.range.startColumn, replacement);
    return new monaco.Selection(
      target.range.startLineNumber,
      target.range.startColumn,
      end.lineNumber,
      end.column,
    );
  }
  const cursor = positionAfterText(1, 1, replacement.slice(0, Math.min(target.cursorOffset, replacement.length)));
  return new monaco.Selection(cursor.lineNumber, cursor.column, cursor.lineNumber, cursor.column);
}

function positionAfterText(startLineNumber: number, startColumn: number, text: string): {
  lineNumber: number;
  column: number;
} {
  const lines = text.split(/\r\n|\r|\n/);
  return lines.length === 1
    ? { lineNumber: startLineNumber, column: startColumn + lines[0].length }
    : { lineNumber: startLineNumber + lines.length - 1, column: lines.at(-1)!.length + 1 };
}

defineExpose({
  getValue,
  setValue,
  triggerExecute: trigger,
  triggerCompletion,
  runSelectionAction,
  captureSqlTransformTarget,
  applySqlTransform,
  registerExecutionSources,
  releaseExecutionSources,
  releaseEditorSources,
  releaseModel,
  highlightExecutionSource,
  clearResultHighlight,
});

onBeforeUnmount(() => {
  clearAllDiagnostics();
  contentListener?.dispose();
  completionProvider?.dispose();
  codeActionProvider?.dispose();
  selectionListener?.dispose();
  objectMouseMove?.dispose(); objectMouseDown?.dispose(); objectScroll?.dispose(); clearObjectHover();
  mirrorListeners.forEach((listener) => listener.dispose());
  mirrorListeners.clear();
  modelSynchronizer.releaseAll();
  clearResultHighlight();
  sourceAnchors.forEach((anchors) => anchors.forEach((anchor) => {
    models.get(anchor.modelKey)?.deltaDecorations([anchor.decorationId], []);
  }));
  sourceAnchors.clear();
  viewStates.clear();
  instance.value?.dispose();
  models.forEach((model) => model.dispose());
});
</script>

<style scoped>
.monaco-host { background: var(--db-editor-bg); }
.monaco-shell { position: relative; min-width: 0; min-height: 0; }
:global(.dbstudio-object-link) { color: var(--db-accent) !important; text-decoration: underline; cursor: pointer !important; font-weight: 650; }
:global(.dbstudio-sql-result-highlight) {
  background: color-mix(in srgb, var(--db-accent) 23%, transparent);
  border-radius: 2px;
}
</style>
