<template><div ref="container" class="object-ddl-monaco" /></template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as monaco from "monaco-editor";

const props = defineProps<{ value: string }>();
const container = ref<HTMLElement>();
let editor: monaco.editor.IStandaloneCodeEditor | undefined;
let model: monaco.editor.ITextModel | undefined;
let rendered = "";

onMounted(() => {
  model = monaco.editor.createModel("", "sql",
    monaco.Uri.parse(`inmemory://dbstudio/object-ddl/${crypto.randomUUID()}.sql`));
  editor = monaco.editor.create(container.value!, {
    model, readOnly: true, domReadOnly: true, automaticLayout: true, minimap: { enabled: false },
    wordWrap: "off", scrollBeyondLastLine: false, fontSize: 12, lineHeight: 19,
    renderLineHighlight: "none", folding: true, stickyScroll: { enabled: false }, padding: { top: 8, bottom: 8 },
  });
  update(props.value);
});
watch(() => props.value, update);

function update(value: string): void {
  if (!model || value === rendered) return;
  if (value.startsWith(rendered)) {
    const end = model.getFullModelRange().getEndPosition();
    model.applyEdits([{ range: new monaco.Range(end.lineNumber, end.column, end.lineNumber, end.column),
      text: value.slice(rendered.length) }]);
  } else model.setValue(value);
  rendered = value;
}

onBeforeUnmount(() => { editor?.dispose(); model?.dispose(); });
</script>

<style scoped>.object-ddl-monaco { flex: 1; min-height: 0; width: 100%; background: var(--db-editor-bg); }</style>
