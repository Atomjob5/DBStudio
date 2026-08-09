<template><div ref="container" class="monaco-host fill" /></template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import * as monaco from "monaco-editor";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import type {
  CompletionCandidate,
  CompletionResult,
  QueryExecutionSource,
  SqlEditorSelectionAction,
  SqlTransformApplyResult,
  SqlTransformTarget,
} from "../types";
import { completionClient } from "../completion/client";
import { CompletionModelSynchronizer, isModelVersionChanged } from "../completion/modelSynchronizer";
import { completionDocumentation, truncateCompletionComment } from "../completion/presentation";
import { compileSqlSnippet, matchingSnippetCandidates } from "../completion/snippets";
import type { SqlCompletionSnippet } from "../types";

(self as typeof self & { MonacoEnvironment: object }).MonacoEnvironment = { getWorker: () => new EditorWorker() };

const props = defineProps<{ modelKey: string; initialValue: string; theme: "dark" | "light";
  completionKey: string; providerId: string; completionCandidateLimit: number;
  completionPreciseMatchingEnabled: boolean; completionSnippets: SqlCompletionSnippet[];
  minimapEnabled: boolean; wordWrapEnabled: boolean }>();
const emit = defineEmits<{
  dirty: [];
  execute: [scope: "current" | "script" | "current-new-tab", selection: string, cursorOffset: number,
    selectionStartOffset: number];
  "selection-change": [selected: boolean];
}>();
const container = ref<HTMLElement>();
const instance = shallowRef<monaco.editor.IStandaloneCodeEditor>();
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
let selectionListener: monaco.IDisposable | undefined;
let changingModel = false;

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

if (!monaco.languages.getLanguages().some((item) => item.id === "dbstudio-mysql")) {
  monaco.languages.register({ id: "dbstudio-mysql" });
  monaco.languages.setMonarchTokensProvider("dbstudio-mysql", {
    ignoreCase: true,
    keywords: ["select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "alter", "drop", "table", "view", "index", "join", "left", "right", "inner", "on", "group", "by", "order", "having", "limit", "union", "all", "distinct", "as", "and", "or", "not", "null", "is", "in", "exists", "between", "like", "case", "when", "then", "else", "end", "with", "procedure", "function", "trigger", "begin", "commit", "rollback", "explain", "show", "describe"],
    tokenizer: { root: [[/[a-zA-Z_$][\w$]*/, { cases: { "@keywords": "keyword", "@default": "identifier" } }], [/`([^`]|``)*`/, "identifier.quote"], [/--.*$/, "comment"], [/#.*$/, "comment"], [/\/\*/, "comment", "@comment"], [/'([^'\\]|\\.)*'/, "string"], [/"([^"\\]|\\.)*"/, "string"], [/\d+(\.\d+)?/, "number"]], comment: [[/[^/*]+/, "comment"], [/\*\//, "comment", "@pop"], [/[/*]/, "comment"]] }
  });
  monaco.languages.setLanguageConfiguration("dbstudio-mysql", {
    comments: {
      lineComment: "--",
      blockComment: ["/*", "*/"],
    },
  });
}

onMounted(() => {
  instance.value = monaco.editor.create(container.value!, {
    language: "dbstudio-mysql",
    theme: monacoTheme(props.theme),
    automaticLayout: true,
    fontFamily: '"SF Mono", Menlo, Consolas, monospace',
    fontSize: 13,
    lineHeight: 21,
    fontLigatures: true,
    minimap: { enabled: props.minimapEnabled },
    wordWrap: props.wordWrapEnabled ? "on" : "off",
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
watch(() => props.theme, (theme) => monaco.editor.setTheme(monacoTheme(theme)));
watch(() => props.minimapEnabled, (enabled) => instance.value?.updateOptions({ minimap: { enabled } }));
watch(() => props.wordWrapEnabled, (enabled) => instance.value?.updateOptions({ wordWrap: enabled ? "on" : "off" }));
watch(() => [props.completionKey, props.providerId], () => {
  const model = instance.value?.getModel();
  const key = model && modelKeys.get(model);
  if (model && key && isCompletionBound()) synchronizeInBackground(key, model);
});

function switchModel(key: string, value: string): void {
  if (!instance.value || !key) return;
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
  instance.value.setModel(model);
  const viewState = viewStates.get(key);
  if (viewState) instance.value.restoreViewState(viewState);
  contentListener?.dispose();
  contentListener = model.onDidChangeContent(() => { if (!changingModel) emit("dirty"); });
  changingModel = false;
  if (isCompletionBound()) synchronizeInBackground(key, model);
  emitSelectionState();
  instance.value.focus();
}

function isCompletionBound(): boolean {
  return Boolean(props.completionKey && props.completionKey !== "unbound");
}

function registerMirrorListener(key: string, model: monaco.editor.ITextModel): void {
  if (mirrorListeners.has(key)) return;
  mirrorListeners.set(key, model.onDidChangeContent((event) => {
    if (!isCompletionBound()) return;
    modelSynchronizer.change(key, event.versionId, event.changes.map((change) => ({
      rangeOffset: change.rangeOffset, rangeLength: change.rangeLength, text: change.text
    })));
  }));
}

function synchronizeInBackground(key: string, model: monaco.editor.ITextModel): void {
  modelSynchronizer.synchronizeInBackground(key, model);
}

function trigger(scope: "current" | "script" | "current-new-tab"): void {
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

function getValue(key = props.modelKey): string {
  return models.get(key)?.getValue() ?? "";
}

function setValue(value: string, key = props.modelKey): void {
  const model = models.get(key);
  if (!model || model.getValue() === value) return;
  changingModel = true;
  model.setValue(value);
  changingModel = false;
  if (isCompletionBound()) synchronizeInBackground(key, model);
}

function getModelVersion(key = props.modelKey): number | undefined {
  return models.get(key)?.getVersionId();
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
  releaseEditorSources(key);
  mirrorListeners.get(key)?.dispose();
  mirrorListeners.delete(key);
  models.delete(key);
  viewStates.delete(key);
  model.dispose();
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
  getModelVersion,
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
  contentListener?.dispose();
  completionProvider?.dispose();
  selectionListener?.dispose();
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
:global(.dbstudio-sql-result-highlight) {
  background: color-mix(in srgb, var(--db-accent) 23%, transparent);
  border-radius: 2px;
}
</style>
