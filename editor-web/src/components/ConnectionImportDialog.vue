<template>
  <el-dialog :model-value="modelValue" title="批量导入数据库链接" width="min(1320px, 96vw)"
             class="connection-import-dialog" :close-on-click-modal="false" :close-on-press-escape="!busy"
             @update:model-value="$emit('update:modelValue', $event)" @closed="reset">
    <el-steps :active="step" finish-status="success" align-center class="import-steps">
      <el-step title="选择 Excel" description="下载模板并填写" />
      <el-step title="确认导入" description="校验、编辑和测试" />
      <el-step title="导入完成" description="查看处理结果" />
    </el-steps>

    <section v-if="step === 0" class="import-card upload-step">
      <div class="upload-heading">
        <span class="heading-icon"><el-icon><Document /></el-icon></span>
        <div><strong>使用 DBStudio Excel 模板</strong>
          <span>文件只在本机处理，不包含或保存数据库密码</span></div>
        <el-button :icon="Download" round :loading="templateDownloading" @click="downloadTemplate">下载导入模板</el-button>
      </div>
      <el-upload v-if="!selectedFile" class="workbook-drop" drag action="#" accept=".xlsx"
                 :auto-upload="false" :show-file-list="false" :on-change="selectFile">
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="el-upload__text">
          <strong>拖拽 Excel 到这里</strong>
          <span>或者点击从文件管理器选择，仅支持 .xlsx，最大 10 MB</span>
        </div>
      </el-upload>
      <div v-else class="upload-success" role="status" aria-label="文件上传成功">
        <span class="upload-success-icon"><el-icon><CircleCheckFilled /></el-icon></span>
        <div><strong>文件上传成功</strong><span>{{ selectedFile.name }} · {{ formatFileSize(selectedFile.size) }}</span>
          <small>文件已通过格式检查，等待导入预览</small></div>
        <el-upload action="#" accept=".xlsx" :auto-upload="false" :show-file-list="false" :on-change="selectFile">
          <el-button size="small">重新选择</el-button>
        </el-upload>
        <el-button text type="danger" size="small" @click="removeSelectedFile">移除</el-button>
      </div>
      <el-alert v-if="uploadError" class="upload-error" :title="uploadError"
                type="error" :closable="false" show-icon />
      <el-alert title="导入按“系统 + 环境 + 链接名称”判断新增或修改；修改三级名称会视为新增链接。"
                type="info" :closable="false" show-icon />
    </section>

    <template v-else-if="step === 1">
      <div class="summary-grid" aria-label="导入摘要">
        <div><span>待处理</span><strong>{{ summary.total }}</strong></div>
        <div class="create"><span>新增链接</span><strong>{{ summary.created }}</strong></div>
        <div class="update"><span>修改链接</span><strong>{{ summary.updated }}</strong></div>
        <div><span>新建系统 / 环境</span><strong>{{ summary.newSystems }} / {{ summary.newEnvironments }}</strong></div>
        <div :class="{ invalid: summary.invalid }"><span>需要修正</span><strong>{{ summary.invalid }}</strong></div>
      </div>
      <section class="import-card confirm-step">
        <header class="confirm-toolbar">
          <el-input v-model="filterText" :prefix-icon="Search" clearable placeholder="筛选系统、环境、链接或错误"
                    aria-label="筛选待导入链接" />
          <span class="toolbar-spacer" />
          <span v-if="batchTesting" class="test-progress">正在测试 {{ testedCount }} / {{ testableCount }}</span>
          <el-button :icon="Connection" :loading="batchTesting" :disabled="!testableCount || committing"
                     @click="testAll">批量测试连通性</el-button>
        </header>
        <el-table :data="filteredRows" height="440" size="small" row-key="rowId" class="compact-import-table"
                  scrollbar-always-on
                  :row-class-name="rowClassName" empty-text="没有符合条件的待导入链接">
          <el-table-column prop="sourceRow" label="行" width="46" align="center" />
          <el-table-column label="系统 / 环境" width="132" show-overflow-tooltip>
            <template #default="{ row }"><div class="primary-cell"><strong>{{ row.systemName }}</strong>
              <span>{{ row.environmentName }}</span></div></template>
          </el-table-column>
          <el-table-column label="链接信息" width="196" show-overflow-tooltip>
            <template #default="{ row }"><div class="primary-cell"><strong>{{ row.name }}</strong>
              <span>{{ providerName(row.providerId) }} · {{ endpoint(row) }}</span>
              <small v-if="row.settings.username">{{ row.settings.username }}</small></div></template>
          </el-table-column>
          <el-table-column label="密码" width="214">
            <template #default="{ row }"><div class="password-cell">
              <el-input v-model="row.password" type="password" show-password size="small"
                        autocomplete="new-password"
                        :placeholder="passwordPlaceholder(row)" aria-label="导入链接密码"
                        @input="credentialChanged(row)" />
              <el-checkbox v-model="row.rememberPassword" size="small"
                           @change="credentialChanged(row)">记住</el-checkbox>
            </div></template>
          </el-table-column>
          <el-table-column label="导入" width="112" align="center">
            <template #default="{ row }"><div class="tag-stack compact-tags">
              <el-tag :type="row.operation === 'create' ? 'success' : 'primary'"
                size="small">{{ row.operation === "create" ? "新增" : "修改" }}</el-tag>
              <small v-if="row.createsSystem || row.createsEnvironment">
                {{ [row.createsSystem ? "新建系统" : "", row.createsEnvironment ? "新建环境" : ""].filter(Boolean).join(" · ") }}
              </small>
            </div></template>
          </el-table-column>
          <el-table-column label="校验" width="142">
            <template #default="{ row }">
              <el-tooltip v-if="row.errors.length" :content="row.errors.join('；')" placement="top">
                <span class="status error"><el-icon><WarningFilled /></el-icon>{{ row.errors[0] }}</span>
              </el-tooltip>
              <span v-else class="status success"><el-icon><CircleCheck /></el-icon>校验通过</span>
            </template>
          </el-table-column>
          <el-table-column label="连通性" width="132">
            <template #default="{ row }">
              <span class="status" :class="testClass(row)">
                <el-icon v-if="row.testStatus === 'success'"><CircleCheck /></el-icon>
                <el-icon v-else-if="row.testStatus === 'failed'"><WarningFilled /></el-icon>
                {{ testLabel(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="108" fixed="right" align="center">
            <template #default="{ row }">
              <div class="row-actions">
                <IconTooltip content="编辑导入信息"><el-button text :icon="EditPen" aria-label="编辑导入信息"
                  :disabled="busy" @click="editRow(row)" /></IconTooltip>
                <IconTooltip content="测试连通性"><el-button text :icon="Connection" aria-label="测试单个链接"
                  :loading="row.testStatus === 'testing'" :disabled="busy || row.errors.length > 0"
                  @click="testRow(row)" /></IconTooltip>
                <IconTooltip content="从导入列表移除"><el-button text type="danger" :icon="Delete"
                  aria-label="删除待导入链接" :disabled="busy" @click="removeRow(row)" /></IconTooltip>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>

    <section v-else class="import-card result-step">
      <span class="result-icon"><el-icon><CircleCheck /></el-icon></span>
      <strong>数据库链接导入完成</strong>
      <p>本次新增 {{ result?.createdProfiles ?? 0 }} 个链接，修改 {{ result?.updatedProfiles ?? 0 }} 个链接，
        新建 {{ result?.createdSystems ?? 0 }} 个系统和 {{ result?.createdEnvironments ?? 0 }} 个环境。</p>
      <div class="result-tags">
        <el-tag type="success" effect="light">新增 {{ result?.createdProfiles ?? 0 }}</el-tag>
        <el-tag type="primary" effect="light">修改 {{ result?.updatedProfiles ?? 0 }}</el-tag>
      </div>
      <el-alert title="Excel 和普通配置均不保存密码；勾选记住的密码已写入系统密钥库，其余仅在本次运行内存中可用。"
                type="info" :closable="false" show-icon />
    </section>

    <template #footer>
      <div class="dialog-footer"><span>{{ footerHint }}</span><div>
        <el-button v-if="step < 2" :disabled="busy" @click="$emit('update:modelValue', false)">取消</el-button>
        <el-button v-if="step === 1" :disabled="busy" @click="step = 0">重新选择</el-button>
        <el-button v-if="step === 0" type="primary" :loading="previewing" :disabled="!selectedFile"
                   @click="previewFile">导入并预览</el-button>
        <el-button v-else-if="step === 1" type="primary" :loading="committing"
                   :disabled="!rows.length || summary.invalid > 0 || batchTesting" @click="commitImport">确认导入</el-button>
        <el-button v-else type="primary" @click="$emit('update:modelValue', false)">完成</el-button>
      </div></div>
    </template>

    <el-drawer v-model="editDrawer" title="修改待导入链接" size="520px" append-to-body
               :close-on-click-modal="false">
      <el-form v-if="editForm" label-position="top" class="edit-form">
        <div class="edit-grid">
          <el-form-item label="系统"><el-input v-model="editForm.systemName" maxlength="64" /></el-form-item>
          <el-form-item label="环境"><el-input v-model="editForm.environmentName" maxlength="64" /></el-form-item>
          <el-form-item label="链接名称"><el-input v-model="editForm.name" maxlength="128" /></el-form-item>
          <el-form-item label="数据库类型">
            <el-select v-model="editForm.providerId" style="width:100%" @change="editProviderChanged">
              <el-option v-for="provider in providers" :key="provider.id" :label="provider.displayName" :value="provider.id" />
            </el-select>
          </el-form-item>
          <el-form-item v-for="field in editableFields" :key="field.key" :label="field.label" :required="field.required">
            <el-select v-if="field.type === 'SELECT'" v-model="editForm.settings[field.key]" style="width:100%">
              <el-option v-for="option in field.options ?? []" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-switch v-else-if="field.type === 'BOOLEAN'" :model-value="editForm.settings[field.key] === 'true'"
                       @update:model-value="editForm.settings[field.key] = String($event)" />
            <el-input v-else v-model="editForm.settings[field.key]" :type="field.type === 'NUMBER' ? 'number' : 'text'"
                      :placeholder="field.description" />
          </el-form-item>
          <el-form-item class="span-two" label="密码">
            <el-input v-model="editForm.password" type="password" show-password
                      autocomplete="new-password"
                      :placeholder="passwordPlaceholder(editForm)" />
          </el-form-item>
          <el-form-item class="span-two">
            <el-checkbox v-model="editForm.rememberPassword">使用系统密钥库记住密码</el-checkbox>
          </el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="editDrawer = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存修改</el-button></template>
    </el-drawer>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { UploadFile } from "element-plus";
import {
  CircleCheck, CircleCheckFilled, Connection, Delete, Document, Download, EditPen, Search,
  UploadFilled, WarningFilled
} from "@element-plus/icons-vue";
import { rpc } from "../bridge/rpc";
import IconTooltip from "./IconTooltip.vue";
import type {
  ConnectionField, ConnectionImportPreview, ConnectionImportResult, ConnectionImportRow,
  ConnectionSystem, ConnectionEnvironment, ProviderInfo, SavedProfile
} from "../types";

const props = defineProps<{ modelValue: boolean; providers: ProviderInfo[]; systems: ConnectionSystem[];
  environments: ConnectionEnvironment[]; profiles: SavedProfile[] }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; imported: [result: ConnectionImportResult] }>();
const step = ref(0); const selectedFile = ref<File>(); const rows = ref<ConnectionImportRow[]>([]);
const result = ref<ConnectionImportResult>(); const filterText = ref("");
const previewing = ref(false); const committing = ref(false); const batchTesting = ref(false);
const templateDownloading = ref(false); const editDrawer = ref(false);
const editForm = ref<ConnectionImportRow>(); const editingRowId = ref("");
const testedCount = ref(0); const uploadError = ref("");
const busy = computed(() => previewing.value || committing.value || batchTesting.value);
const activeEditProvider = computed(() => props.providers.find((item) => item.id === editForm.value?.providerId));
const editableFields = computed<ConnectionField[]>(() => (activeEditProvider.value?.fields ?? [])
  .filter((field) => field.type !== "PASSWORD"));
const filteredRows = computed(() => {
  const search = normalize(filterText.value);
  if (!search) return rows.value;
  return rows.value.filter((row) => normalize([
    row.systemName, row.environmentName, row.name, providerName(row.providerId),
    endpoint(row), row.settings.username, ...row.errors
  ].join(" ")).includes(search));
});
const summary = computed(() => {
  const newSystems = new Set(rows.value.filter((row) => row.createsSystem).map((row) => normalize(row.systemName)));
  const newEnvironments = new Set(rows.value.filter((row) => row.createsEnvironment)
    .map((row) => `${normalize(row.systemName)}\u0000${normalize(row.environmentName)}`));
  return { total: rows.value.length, created: rows.value.filter((row) => row.operation === "create").length,
    updated: rows.value.filter((row) => row.operation === "update").length,
    invalid: rows.value.filter((row) => row.errors.length).length,
    newSystems: newSystems.size, newEnvironments: newEnvironments.size };
});
const testableCount = computed(() => rows.value.filter((row) => !row.errors.length).length);
const footerHint = computed(() => step.value === 0 ? "第 1 步，共 3 步" :
  step.value === 1 ? `${rows.value.length} 条待确认 · ${summary.value.invalid} 条需要修正` : "连接目录已刷新");

watch(() => props.modelValue, (open) => { if (open) reset(); });

function selectFile(upload: UploadFile): void {
  const file = upload.raw;
  if (!file) return;
  if (!file.name.toLowerCase().endsWith(".xlsx")) {
    selectedFile.value = undefined; uploadError.value = "仅支持 .xlsx 格式的 Excel 文件"; return;
  }
  if (file.size > 10 * 1024 * 1024) {
    selectedFile.value = undefined; uploadError.value = "Excel 文件不能超过 10 MB"; return;
  }
  selectedFile.value = file; uploadError.value = "";
}
function removeSelectedFile(): void { selectedFile.value = undefined; uploadError.value = ""; }
function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}
async function downloadTemplate(): Promise<void> {
  templateDownloading.value = true;
  try { await rpc.downloadConnectionTemplate(); }
  catch (error) { ElMessage.error(errorMessage(error)); }
  finally { templateDownloading.value = false; }
}
async function previewFile(): Promise<void> {
  if (!selectedFile.value) return;
  previewing.value = true;
  try {
    const preview = await rpc.previewConnectionWorkbook(selectedFile.value) as ConnectionImportPreview;
    rows.value = preview.rows.map((row) => ({ ...row, settings: { ...row.settings },
      errors: [...row.errors], warnings: [...row.warnings], testStatus: "untested", testMessage: "",
      password: "", rememberPassword: Boolean(row.rememberPassword) }));
    step.value = 1;
  } catch (error) {
    uploadError.value = errorMessage(error);
    ElMessage.error(uploadError.value);
  }
  finally { previewing.value = false; }
}
function providerName(providerId: string): string {
  return props.providers.find((item) => item.id === providerId)?.displayName ?? providerId;
}
function endpoint(row: any): string {
  const host = row.settings.host ?? "";
  const port = row.settings.port ? `:${row.settings.port}` : "";
  const target = row.settings.database || row.settings.service;
  return `${host}${port}${target ? ` / ${target}` : ""}` || "未填写地址";
}
function rowClassName({ row }: { row: ConnectionImportRow }): string {
  return row.errors.length ? "connection-import-row-invalid" : "";
}
function testClass(row: any): string {
  return row.testStatus === "success" ? "success" : row.testStatus === "failed" ? "error" :
    row.testStatus === "testing" ? "testing" : "muted";
}
function testLabel(row: any): string {
  if (row.testStatus === "testing") return "测试中…";
  if (row.testStatus === "success") return row.testMessage || "连接成功";
  if (row.testStatus === "failed") return row.testMessage || "连接失败";
  return "未测试";
}
function editRow(row: any): void {
  editingRowId.value = row.rowId;
  editForm.value = { ...row, settings: { ...row.settings }, errors: [...row.errors],
    warnings: [...row.warnings] };
  editDrawer.value = true;
}
function editProviderChanged(): void {
  if (!editForm.value) return;
  editForm.value.settings = defaults(activeEditProvider.value);
  editForm.value.password = ""; editForm.value.rememberPassword = false;
  editForm.value.testStatus = "untested"; editForm.value.testMessage = "";
}
function saveEdit(): void {
  if (!editForm.value) return;
  const index = rows.value.findIndex((row) => row.rowId === editingRowId.value);
  if (index < 0) return;
  rows.value[index] = { ...editForm.value, settings: { ...editForm.value.settings },
    testStatus: "untested", testMessage: "" };
  reclassifyRows(editingRowId.value);
  editDrawer.value = false;
}
function removeRow(row: any): void {
  row.password = "";
  rows.value = rows.value.filter((item) => item.rowId !== row.rowId);
  reclassifyRows();
}
function credentialChanged(row: any): void {
  row.testStatus = "untested"; row.testMessage = "";
  reclassifyRows();
}
function hasRememberedPassword(row: ConnectionImportRow): boolean {
  return row.operation === "update"
    && Boolean(props.profiles.find((profile) => profile.id === row.matchedProfileId)?.rememberPassword);
}
function passwordPlaceholder(row: any): string {
  return hasRememberedPassword(row) && row.rememberPassword ? "留空沿用已保存密码" : "输入密码";
}
async function testRow(row: any): Promise<void> {
  if (row.errors.length) return;
  row.testStatus = "testing"; row.testMessage = "";
  try {
    const payload: Record<string, unknown> = { id: row.matchedProfileId || row.profileId,
      providerId: row.providerId, name: row.name, settings: { ...row.settings } };
    if (row.password) payload.password = row.password;
    const response = await rpc.request<{ success: boolean; message: string; serverVersion?: string }>(
      "connection.test", payload, 60_000);
    row.testStatus = response.success ? "success" : "failed";
    row.testMessage = [response.message, response.serverVersion].filter(Boolean).join(" · ");
  } catch (error) {
    row.testStatus = "failed"; row.testMessage = errorMessage(error);
  }
}
async function testAll(): Promise<void> {
  const queue = rows.value.filter((row) => !row.errors.length);
  if (!queue.length) return;
  batchTesting.value = true; testedCount.value = 0;
  let cursor = 0;
  const worker = async (): Promise<void> => {
    while (cursor < queue.length) {
      const row = queue[cursor++];
      await testRow(row);
      testedCount.value++;
    }
  };
  try { await Promise.all(Array.from({ length: Math.min(4, queue.length) }, () => worker())); }
  finally { batchTesting.value = false; }
}
async function commitImport(): Promise<void> {
  if (!rows.value.length || summary.value.invalid) return;
  if (rows.value.some((row) => row.testStatus !== "success")) {
    try {
      await ElMessageBox.confirm("部分链接未测试或测试失败，仍要继续导入吗？", "确认导入", {
        type: "warning", confirmButtonText: "继续导入", cancelButtonText: "返回检查"
      });
    } catch { return; }
  }
  committing.value = true;
  try {
    const payloadRows = rows.value.map((row) => ({
      rowId: row.rowId, sourceRow: row.sourceRow, profileId: row.profileId,
      systemName: row.systemName, environmentName: row.environmentName, name: row.name,
      providerId: row.providerId, settings: { ...row.settings }, createsSystem: row.createsSystem,
      createsEnvironment: row.createsEnvironment, operation: row.operation,
      matchedProfileId: row.matchedProfileId, matchedRevision: row.matchedRevision,
      ...(row.password ? { password: row.password } : {}), rememberPassword: row.rememberPassword
    }));
    result.value = await rpc.request<ConnectionImportResult>("connection.import.commit", { rows: payloadRows }, 120_000);
    rows.value.forEach((row) => { row.password = ""; });
    step.value = 2; emit("imported", result.value);
    ElMessage.success(`成功导入 ${result.value.createdProfiles + result.value.updatedProfiles} 个数据库链接`);
  } catch (error) { ElMessage.error(errorMessage(error)); }
  finally { committing.value = false; }
}
function reclassifyRows(editedRowId = ""): void {
  const systems = new Map(props.systems.map((system) => [normalize(system.name), system]));
  const environments = new Map(props.environments.map((environment) => {
    const system = props.systems.find((item) => item.id === environment.systemId);
    return [`${normalize(system?.name)}\u0000${normalize(environment.name)}`, environment] as const;
  }));
  const profiles = new Map<string, SavedProfile[]>();
  for (const profile of props.profiles) {
    const environment = props.environments.find((item) => item.id === profile.environmentId);
    const system = props.systems.find((item) => item.id === environment?.systemId);
    const key = triple(system?.name, environment?.name, profile.name);
    profiles.set(key, [...(profiles.get(key) ?? []), profile]);
  }
  for (const row of rows.value) {
    const previousOperation = row.operation;
    const previousMatchedProfileId = row.matchedProfileId;
    const structuralErrors = row.rowId === editedRowId ? [] :
      row.errors.filter((error) => error.startsWith("不支持公式单元格"));
    row.createsSystem = !systems.has(normalize(row.systemName));
    row.createsEnvironment = !environments.has(`${normalize(row.systemName)}\u0000${normalize(row.environmentName)}`);
    const matches = profiles.get(triple(row.systemName, row.environmentName, row.name)) ?? [];
    if (matches.length === 1) {
      if (previousMatchedProfileId !== matches[0].id) {
        row.password = ""; row.rememberPassword = matches[0].rememberPassword;
      }
      row.operation = "update"; row.profileId = matches[0].id;
      row.matchedProfileId = matches[0].id; row.matchedRevision = matches[0].revision;
    } else {
      if (previousMatchedProfileId) {
        row.password = ""; row.rememberPassword = false;
      }
      row.operation = matches.length > 1 ? "update" : "create";
      row.matchedProfileId = ""; row.matchedRevision = "";
      if (matches.length === 0 && previousOperation === "update") row.profileId = crypto.randomUUID();
    }
    row.errors = [...structuralErrors, ...validateRow(row)];
    if (matches.length > 1) row.errors.push("现有目录中存在多个同名链接，无法判定修改记录");
  }
  const duplicates = new Map<string, ConnectionImportRow[]>();
  for (const row of rows.value) {
    const key = triple(row.systemName, row.environmentName, row.name);
    duplicates.set(key, [...(duplicates.get(key) ?? []), row]);
  }
  for (const values of duplicates.values()) if (values.length > 1) {
    for (const row of values) row.errors.push("导入列表中存在重复的系统、环境和链接名称");
  }
}
function validateRow(row: ConnectionImportRow): string[] {
  const errors: string[] = [];
  if (!row.systemName.trim()) errors.push("系统不能为空");
  else if (row.systemName.trim().length > 64) errors.push("系统名称不能超过64个字符");
  if (!row.environmentName.trim()) errors.push("环境不能为空");
  else if (row.environmentName.trim().length > 64) errors.push("环境名称不能超过64个字符");
  if (!row.name.trim()) errors.push("链接名称不能为空");
  else if (row.name.trim().length > 128) errors.push("链接名称不能超过128个字符");
  const provider = props.providers.find((item) => item.id === row.providerId);
  if (!provider) { errors.push("数据库类型不存在"); return errors; }
  if (row.rememberPassword && !row.password && !hasRememberedPassword(row)) {
    errors.push("勾选记住密码时必须填写密码");
  }
  for (const field of provider.fields.filter((item) => item.type !== "PASSWORD")) {
    let value = (row.settings[field.key] ?? "").trim();
    if (!value && field.required) {
      value = field.defaultValue; row.settings[field.key] = value;
    }
    if (field.required && !value) errors.push(`${field.label}不能为空`);
    if (value && field.type === "NUMBER" && (!/^[1-9]\d*$/.test(value))) {
      errors.push(`${field.label}必须是大于0的整数`);
    }
    if (value && field.type === "SELECT" && !(field.options ?? []).some((option) => option.value === value)) {
      errors.push(`${field.label}的选项无效`);
    }
  }
  return errors;
}
function defaults(provider?: ProviderInfo): Record<string, string> {
  return Object.fromEntries((provider?.fields ?? []).filter((field) => field.type !== "PASSWORD")
    .map((field) => [field.key, field.defaultValue]));
}
function triple(system?: string, environment?: string, name?: string): string {
  return `${normalize(system)}\u0000${normalize(environment)}\u0000${normalize(name)}`;
}
function normalize(value?: string): string { return (value ?? "").trim().toLocaleLowerCase(); }
function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
function reset(): void {
  rows.value.forEach((row) => { row.password = ""; });
  if (editForm.value) editForm.value.password = "";
  step.value = 0; selectedFile.value = undefined; rows.value = []; result.value = undefined;
  filterText.value = ""; previewing.value = false; committing.value = false; batchTesting.value = false;
  editDrawer.value = false; editForm.value = undefined; testedCount.value = 0; uploadError.value = "";
}
</script>

<style scoped>
.import-steps{margin:2px 0 18px;padding:0 90px}.import-card{border:1px solid var(--db-border-soft);border-radius:16px;background:color-mix(in srgb,var(--db-content) 92%,transparent);box-shadow:var(--db-shadow-sm)}
.upload-step{padding:22px}.upload-heading{display:flex;align-items:center;gap:14px;margin-bottom:18px}.upload-heading>div{min-width:0;flex:1;display:flex;flex-direction:column;gap:3px}.upload-heading strong{font-size:14px}.upload-heading span{color:var(--db-muted);font-size:11px}
.heading-icon{width:46px;height:46px;display:grid;place-items:center;border-radius:14px;background:var(--db-accent-soft);color:var(--db-accent);font-size:23px}.workbook-drop{margin-bottom:16px}.workbook-drop :deep(.el-upload-dragger){height:205px;display:flex;flex-direction:column;justify-content:center;border-radius:14px;background:var(--db-panel-soft)}
.upload-icon{font-size:44px;color:var(--db-accent)}.el-upload__text{display:flex;flex-direction:column;gap:6px}.el-upload__text strong{font-size:14px;color:var(--db-text)}.el-upload__text span{font-size:11px;color:var(--db-muted)}
.upload-success{min-height:116px;margin-bottom:16px;padding:18px;display:flex;align-items:center;gap:14px;border:1px solid color-mix(in srgb,var(--el-color-success) 42%,var(--db-border-soft));border-radius:14px;background:color-mix(in srgb,var(--el-color-success) 8%,var(--db-panel-soft))}.upload-success-icon{width:48px;height:48px;flex:0 0 auto;display:grid;place-items:center;border-radius:50%;background:color-mix(in srgb,var(--el-color-success) 16%,transparent);color:var(--el-color-success);font-size:28px}.upload-success>div{min-width:0;flex:1;display:flex;flex-direction:column;gap:3px}.upload-success strong{color:var(--el-color-success);font-size:14px}.upload-success span,.upload-success small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.upload-success span{color:var(--db-text-secondary);font-size:12px}.upload-success small{color:var(--db-muted);font-size:10px}.upload-error{margin-bottom:12px}
.summary-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px;margin-bottom:12px}.summary-grid>div{min-height:66px;padding:10px 13px;display:flex;flex-direction:column;justify-content:center;border:1px solid var(--db-border-soft);border-radius:12px;background:var(--db-panel-soft)}.summary-grid span{color:var(--db-muted);font-size:10px}.summary-grid strong{margin-top:3px;font-size:20px}.summary-grid .create strong{color:var(--el-color-success)}.summary-grid .update strong{color:var(--db-accent)}.summary-grid .invalid strong{color:var(--el-color-danger)}
.confirm-step{overflow:hidden}.confirm-toolbar{display:flex;align-items:center;gap:10px;padding:10px 12px;border-bottom:1px solid var(--db-border-soft)}.confirm-toolbar .el-input{width:300px}.toolbar-spacer{flex:1}.test-progress{color:var(--db-muted);font-size:11px}
.primary-cell{min-width:0;display:flex;flex-direction:column;gap:1px}.primary-cell strong,.primary-cell span,.primary-cell small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.primary-cell strong{font-weight:600}.primary-cell span,.primary-cell small{color:var(--db-muted);font-size:10px}.tag-stack{display:flex;align-items:center;justify-content:center;gap:3px;flex-wrap:wrap}.compact-tags{flex-direction:column;flex-wrap:nowrap}.compact-tags small{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--el-color-warning);font-size:9px}.password-cell{display:flex;align-items:center;gap:6px;white-space:nowrap}.password-cell .el-input{width:142px}.password-cell .el-checkbox{height:24px;margin-right:0}.password-cell :deep(.el-checkbox__label){padding-left:3px;font-size:10px}.row-actions{display:flex;align-items:center;justify-content:center;flex-wrap:nowrap;white-space:nowrap}.row-actions :deep(.el-button){width:28px;height:28px;padding:0;margin:0}.muted{color:var(--db-muted);font-size:10px}.status{display:inline-flex;align-items:center;gap:4px;max-width:100%;font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.status.success{color:var(--el-color-success)}.status.error{color:var(--el-color-danger)}.status.testing{color:var(--db-accent)}
:deep(.compact-import-table .el-table__cell){padding:5px 0}:deep(.compact-import-table .cell){padding:0 7px;line-height:17px}
:deep(.connection-import-row-invalid td){background:color-mix(in srgb,var(--el-color-danger) 5%,transparent)}.result-step{min-height:360px;padding:50px;display:flex;align-items:center;justify-content:center;flex-direction:column;text-align:center}.result-icon{width:72px;height:72px;display:grid;place-items:center;border-radius:50%;background:color-mix(in srgb,var(--el-color-success) 14%,transparent);color:var(--el-color-success);font-size:38px}.result-step>strong{margin-top:16px;font-size:20px}.result-step p{max-width:620px;color:var(--db-muted)}.result-tags{display:flex;gap:8px;margin-bottom:24px}.result-step .el-alert{max-width:680px}
.dialog-footer{width:100%;display:flex;align-items:center;justify-content:space-between}.dialog-footer>span{color:var(--db-muted);font-size:11px}.edit-form{padding:0 4px}.edit-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 14px}.span-two{grid-column:1/-1}
@media(max-width:850px){.import-steps{padding:0 15px}.summary-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.confirm-toolbar .el-input{width:220px}.edit-grid{grid-template-columns:1fr}.span-two{grid-column:auto}}
</style>
