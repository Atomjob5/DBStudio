<template>
  <el-dialog :model-value="modelValue" title="数据比较" class="result-value-compare-dialog"
             width="min(1100px, 90vw)" append-to-body destroy-on-close
             @opened="createEditor" @closed="disposeEditor"
             @update:model-value="$emit('update:modelValue', $event)">
    <div ref="container" class="result-value-diff" />
  </el-dialog>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue";
import * as monaco from "../monaco";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import { comparableValue } from "../resultGrid";

const props = defineProps<{
  modelValue: boolean;
  left: string | null;
  right: string | null;
  theme: "dark" | "light";
}>();
defineEmits<{ "update:modelValue": [value: boolean] }>();

const container = ref<HTMLElement>();
let editor: monaco.editor.IStandaloneDiffEditor | undefined;
let originalModel: monaco.editor.ITextModel | undefined;
let modifiedModel: monaco.editor.ITextModel | undefined;

if (typeof self !== "undefined") {
  (self as typeof self & { MonacoEnvironment?: object }).MonacoEnvironment ??= {
    getWorker: () => new EditorWorker()
  };
}

function createEditor(): void {
  if (!container.value || editor) return;
  editor = monaco.editor.createDiffEditor(container.value, {
    automaticLayout: true,
    readOnly: true,
    originalEditable: false,
    renderSideBySide: true,
    enableSplitViewResizing: true,
    fontFamily: '"SF Mono", Menlo, Consolas, monospace',
    fontSize: 12,
    lineHeight: 20,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    renderOverviewRuler: false,
    wordWrap: "on",
    padding: { top: 10, bottom: 10 }
  });
  updateModels();
}

function updateModels(): void {
  if (!editor) return;
  editor.setModel(null);
  originalModel?.dispose();
  modifiedModel?.dispose();
  originalModel = monaco.editor.createModel(comparableValue(props.left), "plaintext");
  modifiedModel = monaco.editor.createModel(comparableValue(props.right), "plaintext");
  editor.setModel({ original: originalModel, modified: modifiedModel });
}

function disposeEditor(): void {
  editor?.dispose();
  originalModel?.dispose();
  modifiedModel?.dispose();
  editor = undefined;
  originalModel = undefined;
  modifiedModel = undefined;
}

watch(() => [props.left, props.right], () => {
  if (props.modelValue) void nextTick(updateModels);
});
onBeforeUnmount(disposeEditor);
</script>

<style>
.result-value-compare-dialog .el-dialog__body { padding: 0; }
.result-value-diff { width: 100%; height: min(68vh, 680px); }
</style>
