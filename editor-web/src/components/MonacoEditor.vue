<template><div ref="container" class="monaco-host fill" /></template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import * as monaco from "monaco-editor";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import type { CompletionCandidate, CompletionResult } from "../types";
import { completionClient } from "../completion/client";
import { CompletionModelSynchronizer, isModelVersionChanged } from "../completion/modelSynchronizer";
import { completionDocumentation, truncateCompletionComment } from "../completion/presentation";

(self as typeof self & { MonacoEnvironment: object }).MonacoEnvironment = { getWorker: () => new EditorWorker() };

const props = defineProps<{ modelKey: string; initialValue: string; theme: "dark" | "light";
  completionKey: string; providerId: string; completionCandidateLimit: number }>();
const emit = defineEmits<{
  dirty: [];
  execute: [scope: "current" | "script", selection: string, cursorOffset: number];
}>();
const container = ref<HTMLElement>();
const instance = shallowRef<monaco.editor.IStandaloneCodeEditor>();
const models = new Map<string, monaco.editor.ITextModel>();
const modelKeys = new WeakMap<monaco.editor.ITextModel, string>();
const mirrorListeners = new Map<string, monaco.IDisposable>();
const modelSynchronizer = new CompletionModelSynchronizer(completionClient);
let contentListener: monaco.IDisposable | undefined;
let completionProvider: monaco.IDisposable | undefined;
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
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    smoothScrolling: true,
    fixedOverflowWidgets: true,
    renderWhitespace: "selection",
    cursorBlinking: "smooth",
    cursorSmoothCaretAnimation: "on",
    padding: { top: 10, bottom: 10 },
    tabSize: 2
  });
  completionProvider = monaco.languages.registerCompletionItemProvider("dbstudio-mysql", {
    triggerCharacters: [".", "`", "\"", " "],
    async provideCompletionItems(model, position, _context, token) {
      if (!props.completionKey || props.completionKey === "unbound") return { suggestions: [] };
      const modelKey = modelKeys.get(model);
      if (!modelKey) return { suggestions: [] };
      const word = model.getWordUntilPosition(position);
      const modelVersion = model.getVersionId();
      const cursorOffset = model.getOffsetAt(position);
      let result: CompletionResult;
      try {
        result = await modelSynchronizer.execute(modelKey, model, modelVersion,
          () => completionClient.complete(props.completionKey, props.providerId, modelKey,
            modelVersion, cursorOffset, word.word, props.completionCandidateLimit));
      } catch (error) {
        if (isModelVersionChanged(error)) return { suggestions: [] };
        throw error;
      }
      if (token.isCancellationRequested) return { suggestions: [] };
      return { incomplete: result.incomplete, suggestions: result.items.map((item, index) => ({
        label: {
          label: item.displayLabel,
          detail: item.remarks ? `  ${truncateCompletionComment(item.remarks)}` : undefined,
          description: item.typeName || item.kind.toUpperCase()
        },
        insertText: item.insertText,
        filterText: item.filterText,
        sortText: String(index).padStart(5, "0"),
        documentation: { value: completionDocumentation(item) },
        kind: completionKind(item.kind),
        range: { startLineNumber: position.lineNumber, endLineNumber: position.lineNumber, startColumn: word.startColumn, endColumn: word.endColumn }
      })) };
    }
  });
  switchModel(props.modelKey, props.initialValue);
});

function completionKind(kind: CompletionCandidate["kind"]): monaco.languages.CompletionItemKind {
  if (kind === "column") return monaco.languages.CompletionItemKind.Field;
  if (kind === "schema") return monaco.languages.CompletionItemKind.Module;
  if (kind === "view") return monaco.languages.CompletionItemKind.Interface;
  if (kind === "table") return monaco.languages.CompletionItemKind.Class;
  return monaco.languages.CompletionItemKind.Keyword;
}

watch(() => props.modelKey, (key) => switchModel(key, props.initialValue));
watch(() => props.theme, (theme) => monaco.editor.setTheme(monacoTheme(theme)));
watch(() => [props.completionKey, props.providerId], () => {
  const model = instance.value?.getModel();
  const key = model && modelKeys.get(model);
  if (model && key && isCompletionBound()) synchronizeInBackground(key, model);
});

function switchModel(key: string, value: string): void {
  if (!instance.value || !key) return;
  changingModel = true;
  let model = models.get(key);
  if (!model) {
    model = monaco.editor.createModel(value, "dbstudio-mysql", monaco.Uri.parse(`inmemory://dbstudio/${key}.sql`));
    models.set(key, model);
    modelKeys.set(model, key);
    registerMirrorListener(key, model);
  }
  instance.value.setModel(model);
  contentListener?.dispose();
  contentListener = model.onDidChangeContent(() => { if (!changingModel) emit("dirty"); });
  changingModel = false;
  if (isCompletionBound()) synchronizeInBackground(key, model);
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

function trigger(scope: "current" | "script"): void {
  const editor = instance.value;
  const model = editor?.getModel();
  if (!editor || !model) return;
  const selection = editor.getSelection();
  const selected = selection && !selection.isEmpty() ? model.getValueInRange(selection) : "";
  const position = editor.getPosition();
  emit("execute", scope, selected, position ? model.getOffsetAt(position) : 0);
}

function triggerCompletion(): void {
  instance.value?.focus();
  void instance.value?.trigger("shortcut", "editor.action.triggerSuggest", {});
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

defineExpose({ getValue, setValue, triggerExecute: trigger, triggerCompletion });

onBeforeUnmount(() => {
  contentListener?.dispose();
  completionProvider?.dispose();
  mirrorListeners.forEach((listener) => listener.dispose());
  mirrorListeners.clear();
  modelSynchronizer.releaseAll();
  instance.value?.dispose();
  models.forEach((model) => model.dispose());
});
</script>

<style scoped>.monaco-host { background: var(--db-editor-bg); }</style>
