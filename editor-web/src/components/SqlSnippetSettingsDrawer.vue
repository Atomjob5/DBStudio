<template>
  <el-drawer
    :model-value="modelValue"
    title="SQL片段"
    size="560px"
    class="sql-snippet-settings-drawer"
    @closed="cancelEdit"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template v-if="draft">
      <div class="snippet-editor-heading">
        <div>
          <strong>{{ editingExisting ? "编辑片段" : "新增片段" }}</strong>
          <span>完整输入提示词后，可从 SQL 补全中展开片段。</span>
        </div>
      </div>
      <el-alert v-if="validationMessage" :title="validationMessage" type="warning"
                :closable="false" show-icon />
      <el-form label-position="top" class="snippet-form">
        <el-form-item label="提示词" required>
          <el-input v-model="draft.trigger" maxlength="64" show-word-limit
                    placeholder="例如 sf" @input="validationMessage = ''" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="draft.remarks" maxlength="200" show-word-limit
                    placeholder="例如 通用查询" @input="validationMessage = ''" />
        </el-form-item>
        <el-form-item label="SQL片段" required>
          <el-input v-model="draft.sql" type="textarea" :rows="12"
                    placeholder="例如 select * from" @input="validationMessage = ''" />
        </el-form-item>
      </el-form>
      <div class="snippet-editor-actions">
        <el-button :disabled="saving" @click="cancelEdit">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDraft">保存</el-button>
      </div>
    </template>

    <template v-else>
      <div class="snippet-drawer-toolbar">
        <p>提示词忽略大小写且不能重复，SQL片段在所有编辑标签中生效。</p>
        <el-button type="primary" size="small" :icon="Plus"
                   :disabled="saving || snippets.length >= MAX_SQL_SNIPPETS" @click="startCreate">
          新增片段
        </el-button>
      </div>
      <el-empty v-if="!snippets.length" description="暂无 SQL 片段" :image-size="72" />
      <div v-else class="snippet-list">
        <article v-for="snippet in snippets" :key="snippet.id" class="snippet-card">
          <div class="snippet-card-content">
            <div class="snippet-card-title">
              <code>{{ snippet.trigger }}</code>
              <span v-if="snippet.remarks">{{ snippet.remarks }}</span>
            </div>
            <pre>{{ snippet.sql }}</pre>
          </div>
          <div class="snippet-card-actions">
            <el-button text size="small" :disabled="saving" @click="startEdit(snippet)">编辑</el-button>
            <el-button text type="danger" size="small" :disabled="saving"
                       :aria-label="`删除片段${snippet.trigger}`" @click="removeSnippet(snippet.id)">删除</el-button>
          </div>
        </article>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Plus } from "@element-plus/icons-vue";
import type { SqlCompletionSnippet } from "../types";
import {
  MAX_SQL_SNIPPETS,
  validateSqlCompletionSnippet,
  validateSqlCompletionSnippets,
} from "../completion/snippets";

const props = defineProps<{
  modelValue: boolean;
  snippets: SqlCompletionSnippet[];
  saving?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  updateSnippets: [value: SqlCompletionSnippet[]];
}>();

const draft = ref<SqlCompletionSnippet>();
const validationMessage = ref("");
const editingExisting = computed(() =>
  Boolean(draft.value && props.snippets.some((item) => item.id === draft.value?.id)));

watch(() => props.modelValue, (open) => {
  if (!open) cancelEdit();
});

function startCreate(): void {
  validationMessage.value = "";
  draft.value = { id: crypto.randomUUID(), trigger: "", remarks: "", sql: "" };
}

function startEdit(value: SqlCompletionSnippet): void {
  validationMessage.value = "";
  draft.value = { ...value };
}

function cancelEdit(): void {
  validationMessage.value = "";
  draft.value = undefined;
}

function saveDraft(): void {
  if (!draft.value) return;
  const value = { ...draft.value, trigger: draft.value.trigger.trim() };
  const error = validateSqlCompletionSnippet(value, props.snippets);
  if (error) {
    validationMessage.value = error;
    return;
  }
  const index = props.snippets.findIndex((item) => item.id === value.id);
  const next = props.snippets.map((item) => ({ ...item }));
  if (index >= 0) next[index] = value;
  else next.push(value);
  const collectionError = validateSqlCompletionSnippets(next);
  if (collectionError) {
    validationMessage.value = collectionError;
    return;
  }
  emit("updateSnippets", next);
  cancelEdit();
}

function removeSnippet(id: string): void {
  emit("updateSnippets", props.snippets.filter((item) => item.id !== id).map((item) => ({ ...item })));
}
</script>

<style scoped>
.snippet-drawer-toolbar {
  display: flex;
  margin-bottom: 14px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}
.snippet-drawer-toolbar p {
  margin: 0;
  color: var(--db-muted);
  font-size: 12px;
  line-height: 1.5;
}
.snippet-list { display: flex; flex-direction: column; gap: 10px; }
.snippet-card {
  display: flex;
  min-width: 0;
  padding: 12px 10px 12px 14px;
  align-items: flex-start;
  gap: 10px;
  border: 1px solid var(--db-border-soft);
  border-radius: 11px;
  background: var(--db-content);
}
.snippet-card-content { min-width: 0; flex: 1; }
.snippet-card-title { display: flex; min-width: 0; align-items: center; gap: 9px; }
.snippet-card-title code {
  padding: 2px 7px;
  border-radius: 5px;
  color: var(--db-accent);
  background: color-mix(in srgb, var(--db-accent) 10%, transparent);
  font-size: 12px;
  font-weight: 650;
}
.snippet-card-title span {
  overflow: hidden;
  color: var(--db-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.snippet-card pre {
  max-height: 76px;
  margin: 9px 0 0;
  overflow: hidden;
  color: var(--db-muted);
  font-family: "SF Mono", Menlo, Consolas, monospace;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.snippet-card-actions { display: flex; flex: none; }
.snippet-editor-heading { margin-bottom: 14px; }
.snippet-editor-heading div { display: flex; flex-direction: column; gap: 4px; }
.snippet-editor-heading strong { font-size: 14px; }
.snippet-editor-heading span { color: var(--db-muted); font-size: 12px; }
.snippet-form { margin-top: 14px; }
.snippet-form :deep(textarea) {
  font-family: "SF Mono", Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
}
.snippet-editor-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
