<template>
  <el-dialog :model-value="modelValue" title="导入 CSV / TSV" width="820px" class="csv-dialog" :close-on-click-modal="false"
             @update:model-value="$emit('update:modelValue', $event)">
    <el-steps :active="step" finish-status="success" align-center class="csv-steps">
      <el-step title="选择文件" /><el-step title="预览与映射" /><el-step title="导入" />
    </el-steps>
    <div class="csv-body">
      <template v-if="step === 0">
        <div class="file-hero">
          <span class="file-icon"><el-icon><Document /></el-icon></span>
          <div><strong>{{ preview?.name ?? "选择 CSV 或 TSV 文件" }}</strong><span>文件内容只在本机解析，不会上传到网络</span></div>
          <el-upload action="#" :auto-upload="false" :show-file-list="false" :before-upload="rejectBrowserUpload">
            <el-button round @click.stop.prevent="choose">选择文件…</el-button>
          </el-upload>
        </div>
        <el-form label-position="top" class="csv-options">
          <el-form-item label="本地文件" class="span-two"><el-input :model-value="preview?.name" readonly placeholder="尚未选择文件" /></el-form-item>
          <el-form-item label="文本编码"><el-select v-model="charset"><el-option v-for="item in ['UTF-8','GB18030','UTF-16LE']" :key="item" :value="item" /></el-select></el-form-item>
          <el-form-item label="字段分隔符"><el-radio-group v-model="delimiter"><el-radio-button value=",">逗号</el-radio-button><el-radio-button value="\t">制表符</el-radio-button></el-radio-group></el-form-item>
        </el-form>
      </template>
      <template v-else>
        <el-form label-position="top">
          <el-form-item label="目标表"><el-input v-model="table" placeholder="例如 dbstudio_test_customer" /></el-form-item>
        </el-form>
        <div class="table-section-title"><strong>字段映射</strong><span>留空目标字段可跳过导入</span></div>
        <el-table :data="mappingRows" size="small" max-height="190">
          <el-table-column prop="source" label="源字段" />
          <el-table-column label="目标字段"><template #default="{ row }"><el-input v-model="row.target" /></template></el-table-column>
        </el-table>
        <div class="table-section-title preview-title"><strong>数据预览</strong><span>显示文件前 5 行</span></div>
        <el-table :data="previewRows" size="small" max-height="180">
          <el-table-column v-for="header in preview?.headers" :key="header" :prop="header" :label="header" show-overflow-tooltip />
        </el-table>
      </template>
    </div>
    <div v-if="importing" class="import-progress"><span>正在批量导入并维护事务一致性…</span><el-progress :percentage="100" :indeterminate="true" :duration="2" /></div>
    <template #footer>
      <div class="csv-footer"><span>{{ step === 0 ? "第 1 步，共 3 步" : "第 2 步，共 3 步" }}</span><div>
        <el-button @click="$emit('update:modelValue', false)">取消</el-button>
        <el-button v-if="step === 1" @click="step = 0">上一步</el-button>
        <el-button v-if="step === 0" type="primary" :disabled="!preview" :loading="loading" @click="loadPreview">预览文件</el-button>
        <el-button v-else type="primary" :loading="importing" :disabled="!table" @click="runImport">开始导入</el-button>
      </div></div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Document } from "@element-plus/icons-vue";
import { rpc } from "../bridge/rpc";
import { tasks } from "../bridge/tasks";
import { chooseCsvFile } from "../files/browserFiles";
import type { CsvPreview } from "../types";

const props = defineProps<{ modelValue: boolean; editorId?: string }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; imported: [rows: number] }>();
const step = ref(0); const charset = ref("UTF-8"); const delimiter = ref(","); const table = ref("");
const loading = ref(false); const importing = ref(false); const preview = ref<CsvPreview>();
const mappingRows = ref<Array<{ source: string; target: string }>>([]);
const previewRows = computed(() => (preview.value?.rows ?? []).map((row) => Object.fromEntries((preview.value?.headers ?? []).map((header, index) => [header, row[index]]))));
watch(() => props.modelValue, (open) => { if (open) { step.value = 0; table.value = ""; preview.value = undefined; } });

async function choose(): Promise<void> {
  try {
    const file = await chooseCsvFile();
    if (!file) return;
    const selected = await rpc.uploadCsv(file);
    preview.value = { uploadId: selected.uploadId, name: selected.name ?? file.name, delimiter: selected.delimiter ?? ",", charset: charset.value, headers: [], rows: [] };
    delimiter.value = preview.value.delimiter;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : String(error)); }
}
function rejectBrowserUpload(): boolean { return false; }
async function loadPreview(): Promise<void> {
  if (!preview.value) return;
  loading.value = true;
  try {
    preview.value = await rpc.request<CsvPreview>("csv.preview", { uploadId: preview.value.uploadId, charset: charset.value, delimiter: delimiter.value });
    mappingRows.value = preview.value.headers.map((source) => ({ source, target: source }));
    step.value = 1;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally { loading.value = false; }
}
async function runImport(): Promise<void> {
  if (!preview.value) return;
  importing.value = true;
  try {
    const mapping = Object.fromEntries(mappingRows.value.filter((item) => item.target.trim()).map((item) => [item.source, item.target.trim()]));
    if (!props.editorId) throw new Error("当前编辑标签尚未选择数据库链接");
    const result = await tasks.request<{ rows: number }>("csv.import", { editorId: props.editorId, uploadId: preview.value.uploadId, charset: charset.value, delimiter: delimiter.value, table: table.value.trim(), mapping });
    if (!result) return;
    ElMessage.success(`成功导入 ${result.rows} 行`);
    emit("imported", result.rows); emit("update:modelValue", false);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally { importing.value = false; }
}
</script>

<style scoped>
.csv-steps { margin: 4px 0 18px; padding: 0 50px; }
.csv-body {
  min-height: 330px;
  padding: 18px;
  border: 1px solid var(--db-border-soft);
  border-radius: 14px;
  background: var(--db-content);
  background: color-mix(in srgb, var(--db-content) 84%, transparent);
}
.file-hero {
  min-height: 92px;
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 15px;
  margin-bottom: 18px;
  border: 1px solid var(--db-border-soft);
  border-radius: 13px;
  background: var(--db-panel-soft);
}
.file-icon { width: 46px; height: 46px; display: grid; place-items: center; flex: none; border-radius: 13px; background: var(--db-accent-soft); color: var(--db-accent); font-size: 23px; }
.file-hero > div { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 3px; }
.file-hero strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 650; }
.file-hero span { color: var(--db-muted); font-size: 11px; }
.csv-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 14px; }
.span-two { grid-column: 1 / -1; }
.table-section-title { display: flex; align-items: baseline; justify-content: space-between; margin: 6px 0 7px; }
.table-section-title strong { font-size: 12px; font-weight: 650; }
.table-section-title span { color: var(--db-muted); font-size: 10px; }
.preview-title { margin-top: 15px; }
.csv-body :deep(.el-table) { border: 1px solid var(--db-border-soft); border-radius: 10px; overflow: hidden; }
.import-progress { margin-top: 12px; }
.import-progress span { display: block; margin-bottom: 6px; color: var(--db-muted); font-size: 11px; }
.csv-footer { width: 100%; display: flex; align-items: center; justify-content: space-between; }
.csv-footer > span { color: var(--db-muted); font-size: 11px; }
</style>
