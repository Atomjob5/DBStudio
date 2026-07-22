<template>
  <el-dialog :model-value="modelValue" :title="profile ? '编辑数据库链接' : '新增数据库链接'" width="720px"
             class="connection-dialog" :close-on-click-modal="false" @update:model-value="$emit('update:modelValue', $event)">
    <main class="connection-form-area">
      <div class="form-heading"><strong>{{ profile ? profile.name : "配置数据库链接" }}</strong>
        <span>密码只会保存在内存或系统密钥库中，不会写入本地数据库。</span></div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="所属环境" prop="environmentId">
            <el-cascader v-model="form.environmentId" :options="environmentOptions" :props="environmentProps"
                         :show-all-levels="true" filterable style="width:100%" placeholder="选择系统 / 环境" />
          </el-form-item>
          <el-form-item label="链接名称" prop="name"><el-input v-model="form.name" placeholder="例如：核心系统 DEV" /></el-form-item>
          <el-form-item label="数据库类型" prop="providerId">
            <el-select v-model="form.providerId" style="width:100%" @change="resetProviderFields">
              <el-option v-for="provider in providers" :key="provider.id" :label="provider.displayName" :value="provider.id" />
            </el-select>
          </el-form-item>
          <el-form-item v-for="field in activeProvider?.fields" :key="field.key" :label="field.label"
                        :prop="`settings.${field.key}`" :required="field.required">
            <el-input-number v-if="field.type === 'NUMBER'" :model-value="numberValue(field.key)" :min="1"
                             controls-position="right" style="width:100%" @update:model-value="setNumber(field.key, $event)" />
            <el-switch v-else-if="field.type === 'BOOLEAN'" :model-value="form.settings[field.key] === 'true'"
                       @update:model-value="form.settings[field.key] = String($event)" />
            <el-select v-else-if="field.type === 'SELECT'" v-model="form.settings[field.key]" style="width:100%">
              <el-option v-for="option in field.options ?? []" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-input v-else-if="field.type === 'PASSWORD'" v-model="form.password" type="password" show-password
                      :placeholder="profile?.rememberPassword ? '留空则继续使用系统密钥库中的密码' : field.description" />
            <el-input v-else v-model="form.settings[field.key]" :placeholder="field.description" />
          </el-form-item>
          <el-form-item class="remember-row"><el-switch v-model="form.rememberPassword" active-text="使用系统密钥库记住密码" /></el-form-item>
        </div>
      </el-form>
      <el-alert v-if="status" class="connection-status" :title="status" :type="statusType" :closable="false" show-icon />
    </main>
    <template #footer><div class="connection-footer"><span>保存后不会自动切换当前编辑标签。</span><div>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button :loading="testing" @click="testConnection">测试连接</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </div></div></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import type { CascaderProps, FormInstance, FormRules } from "element-plus";
import { rpc } from "../bridge/rpc";
import type { ConnectionEnvironment, ConnectionInput, ConnectionSystem, ProviderInfo, SavedProfile } from "../types";

const props = defineProps<{ modelValue: boolean; providers: ProviderInfo[]; systems: ConnectionSystem[];
  environments: ConnectionEnvironment[]; profile?: SavedProfile; initialEnvironmentId?: string }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; saved: [profile: SavedProfile] }>();
const formRef = ref<FormInstance>(); const testing = ref(false); const saving = ref(false);
const status = ref(""); const statusType = ref<"success" | "error" | "info">("info");
const form = reactive<ConnectionInput>({ providerId: "", name: "", environmentId: "", settings: {}, password: "", rememberPassword: false });
const activeProvider = computed(() => props.providers.find((item) => item.id === form.providerId));
const environmentProps: CascaderProps = { emitPath: false, checkStrictly: false };
const environmentOptions = computed(() => props.systems.map((system) => ({ value: system.id, label: system.name,
  children: props.environments.filter((item) => item.systemId === system.id).map((environment) => ({ value: environment.id, label: environment.name })) })));
const rules: FormRules = { name: [{ required: true, message: "请输入链接名称", trigger: "blur" }],
  environmentId: [{ required: true, message: "请选择所属环境", trigger: "change" }],
  providerId: [{ required: true, message: "请选择数据库类型", trigger: "change" }] };

watch(() => props.modelValue, (opened) => { if (opened) load(); });
function defaults(provider?: ProviderInfo): Record<string, string> { return Object.fromEntries((provider?.fields ?? [])
  .filter((field) => field.type !== "PASSWORD").map((field) => [field.key, field.defaultValue])); }
function load(): void {
  const profile = props.profile;
  form.providerId = profile?.providerId ?? props.providers[0]?.id ?? "";
  form.name = profile?.name ?? ""; form.environmentId = profile?.environmentId ?? props.initialEnvironmentId ?? props.environments[0]?.id ?? "";
  form.settings = { ...defaults(activeProvider.value), ...(profile?.settings ?? {}) };
  form.password = ""; form.rememberPassword = profile?.rememberPassword ?? false; status.value = "";
}
function resetProviderFields(): void { form.settings = defaults(activeProvider.value); form.password = ""; }
function numberValue(key: string): number { return Number.parseInt(form.settings[key] ?? "0", 10) || 0; }
function setNumber(key: string, value: number | undefined): void { form.settings[key] = String(value ?? ""); }
async function validate(): Promise<boolean> {
  if (!await formRef.value?.validate().catch(() => false)) return false;
  for (const field of activeProvider.value?.fields ?? []) {
    const value = field.type === "PASSWORD" ? form.password : form.settings[field.key];
    if (field.required && !value && !(field.type === "PASSWORD" && props.profile?.rememberPassword)) {
      ElMessage.warning(`请输入${field.label}`); return false;
    }
  }
  return true;
}
function payload(): Record<string, unknown> { return { id: props.profile?.id, providerId: form.providerId, name: form.name,
  environmentId: form.environmentId, settings: { ...form.settings }, ...(form.password !== "" ? { password: form.password } : {}),
  rememberPassword: form.rememberPassword }; }
async function testConnection(): Promise<void> {
  if (!await validate()) return; testing.value = true; status.value = "正在测试连接…"; statusType.value = "info";
  try { const result = await rpc.request<{ success: boolean; message: string; serverVersion: string }>("connection.test", payload());
    status.value = `${result.message}${result.serverVersion ? ` · ${result.serverVersion}` : ""}`; statusType.value = result.success ? "success" : "error";
  } catch (error) { status.value = error instanceof Error ? error.message : String(error); statusType.value = "error"; }
  finally { testing.value = false; }
}
async function save(): Promise<void> {
  if (!await validate()) return; saving.value = true;
  try { const saved = await rpc.request<SavedProfile>(props.profile ? "connection.profile.update" : "connection.profile.create", payload(), 60_000);
    emit("saved", saved); emit("update:modelValue", false); form.password = ""; ElMessage.success("数据库链接已保存");
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : String(error)); }
  finally { saving.value = false; }
}
</script>

<style scoped>
.connection-form-area { padding: 4px 6px; }
.form-heading { display:flex; flex-direction:column; gap:3px; margin-bottom:14px; }
.form-heading strong { font-size:14px; }.form-heading span,.connection-footer>span { color:var(--db-muted); font-size:11px; }
.form-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); column-gap:14px; }
.form-grid :deep(.el-form-item) { margin-bottom:12px; }.remember-row { grid-column:1/-1; }
.connection-status { margin-top:8px; }.connection-footer { width:100%; display:flex; align-items:center; justify-content:space-between; }
</style>
