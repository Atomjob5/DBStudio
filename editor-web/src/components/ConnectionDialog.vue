<template>
  <el-dialog :model-value="modelValue" title="连接数据库" width="720px" class="connection-dialog" :close-on-click-modal="false"
             @update:model-value="$emit('update:modelValue', $event)">
    <div class="connection-layout">
      <aside class="saved-connections">
        <div class="saved-heading">
          <div><strong>已保存连接</strong><span>{{ profiles.length }} 个配置</span></div>
          <el-tooltip content="新建连接"><el-button text circle :icon="Plus" aria-label="新建连接" @click="newProfile" /></el-tooltip>
        </div>
        <el-scrollbar class="profile-scroll">
          <el-menu v-if="profiles.length" :default-active="selectedProfileId" class="profile-menu" @select="loadProfile">
            <el-menu-item v-for="profile in profiles" :key="profile.id" :index="profile.id">
              <el-icon><Connection /></el-icon>
              <div class="profile-label"><strong>{{ profile.name }}</strong><span>{{ profile.settings.host ?? profile.providerId }}</span></div>
            </el-menu-item>
          </el-menu>
          <el-empty v-else description="暂无保存的连接" :image-size="34">
            <template #image><el-icon><Coin /></el-icon></template>
          </el-empty>
        </el-scrollbar>
      </aside>

      <main class="connection-form-area">
        <div class="form-heading">
          <strong>{{ selectedProfileId ? "编辑连接" : "新建连接" }}</strong>
          <span>密码只会保存在内存或系统密钥库中</span>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="default">
          <div class="form-grid">
            <el-form-item label="名称" prop="name">
              <el-input v-model="form.name" placeholder="例如：本地开发库" />
            </el-form-item>
            <el-form-item label="数据库类型" prop="providerId">
              <el-select v-model="form.providerId" style="width: 100%" @change="resetProviderFields">
                <el-option v-for="provider in providers" :key="provider.id" :label="provider.displayName" :value="provider.id" />
              </el-select>
            </el-form-item>
            <el-form-item v-for="field in activeProvider?.fields" :key="field.key" :label="field.label"
                          :prop="`settings.${field.key}`" :required="field.required">
              <el-input-number v-if="field.type === 'NUMBER'" :model-value="numberValue(field.key)" :min="1"
                               controls-position="right" style="width: 100%"
                               @update:model-value="setNumber(field.key, $event)" />
              <el-switch v-else-if="field.type === 'BOOLEAN'" :model-value="form.settings[field.key] === 'true'"
                         @update:model-value="form.settings[field.key] = String($event)" />
              <el-input v-else-if="field.type === 'PASSWORD'" v-model="form.password" type="password" show-password
                        :placeholder="selectedRemembered ? '留空则使用系统密钥库中的密码' : field.description" />
              <el-input v-else v-model="form.settings[field.key]" :placeholder="field.description" />
            </el-form-item>
            <el-form-item class="remember-row">
              <el-switch v-model="form.rememberPassword" active-text="使用系统密钥库记住密码" />
            </el-form-item>
          </div>
        </el-form>
        <el-alert v-if="status" class="connection-status" :title="status" :type="statusType" :closable="false" show-icon />
      </main>
    </div>
    <template #footer>
      <div class="connection-footer">
        <span>连接超时不会阻塞其他编辑标签</span>
        <div><el-button @click="$emit('update:modelValue', false)">取消</el-button>
          <el-button :loading="testing" @click="testConnection">测试连接</el-button>
          <el-button type="primary" :loading="connecting" @click="connect">连接</el-button></div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Coin, Connection, Plus } from "@element-plus/icons-vue";
import type { FormInstance, FormRules } from "element-plus";
import { rpc } from "../bridge/rpc";
import type { ConnectionInput, ProviderInfo, SavedProfile } from "../types";

const props = defineProps<{ modelValue: boolean; providers: ProviderInfo[]; profiles: SavedProfile[] }>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  connected: [profile: SavedProfile];
}>();

const formRef = ref<FormInstance>();
const selectedProfileId = ref("");
const testing = ref(false);
const connecting = ref(false);
const status = ref("");
const statusType = ref<"success" | "error" | "info">("info");
const form = reactive<ConnectionInput>({ providerId: "", name: "", settings: {}, password: "", rememberPassword: false });
const activeProvider = computed(() => props.providers.find((item) => item.id === form.providerId));
const selectedRemembered = computed(() => props.profiles.find((item) => item.id === selectedProfileId.value)?.rememberPassword ?? false);
const rules: FormRules = {
  name: [{ required: true, message: "请输入连接名称", trigger: "blur" }],
  providerId: [{ required: true, message: "请选择数据库类型", trigger: "change" }]
};

watch(() => props.modelValue, (opened) => {
  if (opened && !form.providerId && props.providers.length) {
    form.providerId = props.providers[0].id;
    resetProviderFields();
  }
});

function defaults(provider?: ProviderInfo): Record<string, string> {
  return Object.fromEntries((provider?.fields ?? []).filter((field) => field.type !== "PASSWORD")
    .map((field) => [field.key, field.defaultValue]));
}

function resetProviderFields(): void {
  form.settings = defaults(activeProvider.value);
  form.password = "";
}

function newProfile(): void {
  selectedProfileId.value = "";
  form.id = undefined;
  form.name = "";
  form.providerId = props.providers[0]?.id ?? "";
  form.rememberPassword = false;
  resetProviderFields();
  status.value = "";
}

function loadProfile(value: string): void {
  const profile = props.profiles.find((item) => item.id === value);
  if (!profile) return;
  selectedProfileId.value = value;
  form.id = profile.id;
  form.providerId = profile.providerId;
  form.name = profile.name;
  form.settings = { ...defaults(props.providers.find((item) => item.id === profile.providerId)), ...profile.settings };
  form.password = "";
  form.rememberPassword = profile.rememberPassword;
  status.value = "";
}

function numberValue(key: string): number { return Number.parseInt(form.settings[key] ?? "0", 10) || 0; }
function setNumber(key: string, value: number | undefined): void { form.settings[key] = String(value ?? ""); }

async function validate(): Promise<boolean> {
  if (!await formRef.value?.validate().catch(() => false)) return false;
  for (const field of activeProvider.value?.fields ?? []) {
    const value = field.type === "PASSWORD" ? form.password : form.settings[field.key];
    if (field.required && !value && !(field.type === "PASSWORD" && selectedRemembered.value)) {
      ElMessage.warning(`请输入${field.label}`);
      return false;
    }
  }
  return true;
}

async function testConnection(): Promise<void> {
  if (!await validate()) return;
  testing.value = true;
  status.value = "正在测试连接…";
  statusType.value = "info";
  try {
    const result = await rpc.request<{ success: boolean; message: string; serverVersion: string }>("connection.test", { ...form, settings: { ...form.settings } });
    status.value = `${result.message}${result.serverVersion ? ` · ${result.serverVersion}` : ""}`;
    statusType.value = result.success ? "success" : "error";
  } catch (error) {
    status.value = error instanceof Error ? error.message : String(error);
    statusType.value = "error";
  } finally { testing.value = false; }
}

async function connect(): Promise<void> {
  if (!await validate()) return;
  connecting.value = true;
  status.value = "正在连接…";
  statusType.value = "info";
  try {
    const profile = await rpc.request<SavedProfile>("connection.connect", { ...form, settings: { ...form.settings } }, 60_000);
    emit("connected", profile);
    emit("update:modelValue", false);
    form.password = "";
  } catch (error) {
    status.value = error instanceof Error ? error.message : String(error);
    statusType.value = "error";
  } finally { connecting.value = false; }
}
</script>

<style scoped>
.connection-layout {
  height: 406px;
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--db-border-soft);
  border-radius: 14px;
  background: var(--db-content);
}
.saved-connections { min-width: 0; display: flex; flex-direction: column; border-right: 1px solid var(--db-border-soft); background: var(--db-panel-soft); }
.saved-heading { min-height: 54px; padding: 9px 8px 8px 12px; display: flex; align-items: center; justify-content: space-between; }
.saved-heading > div,
.form-heading { display: flex; flex-direction: column; gap: 2px; }
.saved-heading strong,
.form-heading strong { font-size: 13px; font-weight: 650; }
.saved-heading span,
.form-heading span { color: var(--db-muted); font-size: 11px; }
.profile-scroll { flex: 1; min-height: 0; }
.profile-menu { border: 0; background: transparent; }
.profile-menu :deep(.el-menu-item) {
  height: 46px;
  margin: 2px 6px;
  padding: 0 9px !important;
  border-radius: 9px;
  color: var(--db-text-secondary);
  line-height: normal;
}
.profile-menu :deep(.el-menu-item:hover) { background: var(--db-control-hover); }
.profile-menu :deep(.el-menu-item.is-active) { background: var(--db-accent-soft); color: var(--db-text); }
.profile-label { min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.profile-label strong,
.profile-label span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.profile-label strong { font-size: 12px; font-weight: 600; line-height: 16px; }
.profile-label span { color: var(--db-muted); font-size: 10px; line-height: 14px; }
.saved-connections :deep(.el-empty) { padding: 48px 10px; }
.saved-connections :deep(.el-empty__image) { width: auto; height: auto; color: var(--db-muted); font-size: 30px; }
.saved-connections :deep(.el-empty__description p) { font-size: 11px; }
.connection-form-area { min-width: 0; padding: 15px 18px; overflow: auto; }
.form-heading { margin-bottom: 12px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 14px; }
.form-grid :deep(.el-form-item) { margin-bottom: 11px; }
.form-grid :deep(.el-form-item__label) { height: 21px; margin: 0; line-height: 18px; }
.remember-row { grid-column: 1 / -1; margin: 1px 0 0 !important; }
.connection-status { margin-top: 8px; }
.connection-footer { width: 100%; display: flex; align-items: center; justify-content: space-between; }
.connection-footer > span { color: var(--db-muted); font-size: 11px; }

@media (max-width: 760px) {
  .connection-layout { grid-template-columns: 160px minmax(0, 1fr); }
  .connection-form-area { padding-inline: 14px; }
}
</style>
