<template>
  <el-dialog :model-value="modelValue" :title="dialogTitle" width="680px" append-to-body
             @update:model-value="$emit('update:modelValue', $event)">
    <el-alert v-if="family === 'blob'" title="BLOB 不在表格中展开；请选择文件作为草稿，应用时流式写入数据库。"
              type="info" :closable="false" show-icon />
    <el-input v-else v-model="textValue" type="textarea" :rows="12"
              :placeholder="family === 'raw' ? '输入 0x 开头的十六进制内容' : '输入 CLOB 文本'" />
    <div class="large-value-meta">
      <span>上限 {{ Math.round(maxBytes / 1048576) }} MiB</span>
      <span v-if="selectedFile">已选择 {{ selectedFile.name }} · {{ fileSize }}</span>
      <span v-else-if="family !== 'blob'">当前 {{ textBytes }} 字节</span>
    </div>
    <input ref="fileInput" class="large-value-file-input" type="file" @change="fileChanged" />
    <template #footer>
      <el-button v-if="canDownload" @click="download">导出原值</el-button>
      <el-button @click="fileInput?.click()">导入文件</el-button>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving || loadingOriginal" :disabled="loadingOriginal"
                 @click="save">保存草稿</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { rpc } from "../bridge/rpc";
import type { ResultMutationValue } from "../stores/resultEdits";

const props = defineProps<{
  modelValue: boolean;
  family: "raw" | "clob" | "blob";
  value: string | null;
  maxBytes: number;
  editorId: string;
  executionId: string;
  resultIndex: number;
  rowId?: string;
  columnIndex: number;
  columnName: string;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  save: [value: ResultMutationValue];
}>();
const textValue = ref("");
const selectedFile = ref<File>();
const fileInput = ref<HTMLInputElement>();
const saving = ref(false);
const loadingOriginal = ref(false);
let loadSequence = 0;
const dialogTitle = computed(() => props.family === "blob" ? `BLOB · ${props.columnName}`
  : props.family === "raw" ? `RAW · ${props.columnName}` : `CLOB · ${props.columnName}`);
const textBytes = computed(() => new TextEncoder().encode(textValue.value).byteLength);
const fileSize = computed(() => formatBytes(selectedFile.value?.size ?? 0));
const canDownload = computed(() => Boolean(props.rowId && !props.rowId.startsWith("draft:")));

watch(() => props.modelValue, async (visible) => {
  if (!visible) return;
  const sequence = ++loadSequence;
  textValue.value = props.value ?? "";
  selectedFile.value = undefined;
  if (fileInput.value) fileInput.value.value = "";
  if (!canDownload.value || props.family === "blob" || !props.rowId) return;
  loadingOriginal.value = true;
  try {
    const blob = await rpc.readResultLargeValue(props.editorId, props.executionId,
      props.resultIndex, props.rowId, props.columnIndex);
    if (sequence !== loadSequence || !props.modelValue) return;
    if (props.family === "clob") textValue.value = await blob.text();
    else textValue.value = `0x${Array.from(new Uint8Array(await blob.arrayBuffer()))
      .map((value) => value.toString(16).padStart(2, "0")).join("")}`;
  } catch (error) {
    if (sequence === loadSequence) ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    if (sequence === loadSequence) loadingOriginal.value = false;
  }
}, { immediate: true });

function fileChanged(event: Event): void {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0];
}

async function save(): Promise<void> {
  const uploadFile = selectedFile.value ?? (props.family === "clob"
    ? new File([textValue.value], `${props.columnName}.txt`, { type: "text/plain;charset=utf-8" }) : undefined);
  if (uploadFile) {
    if (uploadFile.size > props.maxBytes) { ElMessage.error("文件超过可编辑大字段上限"); return; }
    saving.value = true;
    try {
      const uploaded = await rpc.uploadResultLargeValue(props.editorId, props.executionId,
        props.resultIndex, props.columnIndex, uploadFile);
      emit("save", { kind: "largeValueToken", value: uploaded.token });
      emit("update:modelValue", false);
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : String(error)); }
    finally { saving.value = false; }
    return;
  }
  if (props.family === "blob") { ElMessage.warning("请先选择要导入的 BLOB 文件"); return; }
  if (textBytes.value > props.maxBytes) { ElMessage.error("内容超过可编辑大字段上限"); return; }
  if (props.family === "raw" && !/^(?:0x)?[0-9a-fA-F]*$/.test(textValue.value)
      || props.family === "raw" && textValue.value.replace(/^0x/i, "").length % 2 !== 0) {
    ElMessage.error("RAW 必须是偶数位十六进制内容"); return;
  }
  emit("save", { kind: "text", value: props.family === "raw" && !/^0x/i.test(textValue.value)
    ? `0x${textValue.value}` : textValue.value });
  emit("update:modelValue", false);
}

async function download(): Promise<void> {
  if (!props.rowId) return;
  try {
    await rpc.downloadResultLargeValue(props.editorId, props.executionId, props.resultIndex,
      props.rowId, props.columnIndex, `${props.columnName}.${props.family === "clob" ? "txt" : "bin"}`);
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : String(error)); }
}

function formatBytes(bytes: number): string {
  return bytes < 1024 ? `${bytes} B` : bytes < 1048576 ? `${(bytes / 1024).toFixed(1)} KiB`
    : `${(bytes / 1048576).toFixed(1)} MiB`;
}
</script>

<style scoped>
.large-value-meta { display: flex; justify-content: space-between; gap: 16px; margin-top: 10px; color: var(--db-muted); font-size: 11px; }
.large-value-file-input { display: none; }
</style>
