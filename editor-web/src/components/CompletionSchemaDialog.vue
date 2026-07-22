<template>
  <el-dialog :model-value="modelValue" :title="refresh ? '刷新 SQL 补全缓存' : '选择 SQL 补全 Schema'"
             width="560px" :close-on-click-modal="false" destroy-on-close
             @close="cancel">
    <p class="schema-intro">
      {{ refresh ? "请选择本次要重新缓存的 Schema。刷新失败时会继续使用旧缓存。"
        : "仅缓存选中的表、视图和字段；以后可从数据库对象面板手工刷新。" }}
    </p>
    <el-input v-model="filter" clearable placeholder="筛选 Schema" aria-label="筛选补全Schema" />
    <div class="schema-list">
      <section v-if="regularNamespaces.length">
        <div class="group-title"><strong>业务 Schema</strong><span>首次默认选择</span></div>
        <el-checkbox-group v-model="selectedKeys">
          <el-checkbox v-for="namespace in regularNamespaces" :key="namespace.key" :value="namespace.key">
            <span>{{ namespace.label }}</span><small v-if="namespace.current">当前</small>
          </el-checkbox>
        </el-checkbox-group>
      </section>
      <section v-if="systemNamespaces.length">
        <div class="group-title"><strong>系统 Schema</strong><span>默认不选择</span></div>
        <el-checkbox-group v-model="selectedKeys">
          <el-checkbox v-for="namespace in systemNamespaces" :key="namespace.key" :value="namespace.key">
            <span>{{ namespace.label }}</span><small v-if="namespace.current">当前</small>
          </el-checkbox>
        </el-checkbox-group>
      </section>
      <el-empty v-if="!regularNamespaces.length && !systemNamespaces.length" :image-size="48" description="没有匹配的 Schema" />
    </div>
    <template #footer>
      <el-button @click="cancel">取消</el-button>
      <el-button type="primary" :disabled="selectedKeys.length === 0" data-testid="confirm-completion-schemas"
                 @click="confirm">开始缓存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { CompletionNamespaceDescriptor } from "../types";

const props = defineProps<{ modelValue: boolean; namespaces: CompletionNamespaceDescriptor[];
  initialSelectedKeys: string[]; refresh: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean];
  confirm: [namespaces: CompletionNamespaceDescriptor[]]; cancel: [] }>();
const selectedKeys = ref<string[]>([]);
const filter = ref("");
let decisionMade = false;

const visibleNamespaces = computed(() => {
  const term = filter.value.trim().toLocaleLowerCase();
  return term ? props.namespaces.filter((item) => item.label.toLocaleLowerCase().includes(term)) : props.namespaces;
});
const regularNamespaces = computed(() => visibleNamespaces.value.filter((item) => !item.system));
const systemNamespaces = computed(() => visibleNamespaces.value.filter((item) => item.system));

watch(() => props.modelValue, (opened) => {
  if (!opened) return;
  decisionMade = false;
  selectedKeys.value = [...props.initialSelectedKeys];
  filter.value = "";
}, { immediate: true });

function confirm(): void {
  const selected = new Set(selectedKeys.value);
  const namespaces = props.namespaces.filter((item) => selected.has(item.key));
  if (!namespaces.length) return;
  decisionMade = true;
  emit("confirm", namespaces);
  emit("update:modelValue", false);
}

function cancel(): void {
  if (decisionMade) { decisionMade = false; return; }
  decisionMade = true;
  emit("cancel");
  emit("update:modelValue", false);
}
</script>

<style scoped>
.schema-intro { margin: -4px 0 14px; color: var(--db-muted); font-size: 13px; line-height: 1.55; }
.schema-list { max-height: 420px; margin-top: 12px; overflow: auto; border: 1px solid var(--db-border-soft); border-radius: 12px; }
.schema-list section + section { border-top: 1px solid var(--db-border-soft); }
.group-title { display: flex; justify-content: space-between; padding: 11px 14px 7px; }
.group-title strong { font-size: 13px; }
.group-title span { color: var(--db-muted); font-size: 11px; }
.schema-list :deep(.el-checkbox-group) { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 0 14px 10px; gap: 2px 12px; }
.schema-list :deep(.el-checkbox) { min-width: 0; margin-right: 0; }
.schema-list :deep(.el-checkbox__label) { display: flex; min-width: 0; align-items: center; gap: 6px; overflow: hidden; }
.schema-list :deep(.el-checkbox__label > span) { overflow: hidden; text-overflow: ellipsis; }
.schema-list small { flex: none; padding: 1px 5px; border-radius: 5px; color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
</style>
