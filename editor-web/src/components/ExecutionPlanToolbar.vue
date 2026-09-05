<template>
  <div class="execution-plan-toolbar" aria-label="执行计划操作">
    <Transition name="result-action-burst">
      <div v-if="mode === 'tree'" class="result-action-burst execution-plan-burst"
           style="--result-action-burst-width: 58px" role="group" aria-label="计划展开操作">
        <span class="result-action-burst-item execution-plan-burst-item">
          <IconTooltip content="展开全部" :show-after="1000">
            <el-button text :icon="Expand" aria-label="展开全部" @click="expandAll" />
          </IconTooltip>
        </span>
        <span class="result-action-burst-item execution-plan-burst-item">
          <IconTooltip content="折叠全部" :show-after="1000">
            <el-button text :icon="Fold" aria-label="折叠全部" @click="collapseAll" />
          </IconTooltip>
        </span>
      </div>
    </Transition>
    <IconTooltip :content="mode === 'tree' ? '切换到原始文本' : '切换到树形表格'" :show-after="1000">
      <el-button text class="execution-plan-view-toggle"
                 :icon="mode === 'tree' ? Document : Grid"
                 :aria-label="mode === 'tree' ? '切换到原始文本' : '切换到树形表格'"
                 :aria-pressed="mode === 'tree'"
                 :disabled="mode === 'text' && !plan.nodes.length"
                 @click="toggleMode" />
    </IconTooltip>
    <IconTooltip content="复制完整计划" :show-after="1000">
      <el-button text :icon="CopyDocument" aria-label="复制完整计划" @click="copy" />
    </IconTooltip>
  </div>
</template>

<script setup lang="ts">
import { toRefs } from "vue";
import { ElMessage } from "element-plus";
import { CopyDocument, Document, Expand, Fold, Grid } from "@element-plus/icons-vue";
import type { ExecutionPlan } from "../types";
import { writeClipboardText } from "../clipboard";
import { planViewState } from "../planViewState";
import IconTooltip from "./IconTooltip.vue";

const props = defineProps<{ plan: ExecutionPlan }>();
const { mode, collapsed } = toRefs(planViewState(props.plan));

function expandAll(): void {
  collapsed.value.clear();
}

function collapseAll(): void {
  collapsed.value.clear();
  for (const node of props.plan.nodes) collapsed.value.add(node.id);
}

function toggleMode(): void {
  if (mode.value === "text" && !props.plan.nodes.length) return;
  mode.value = mode.value === "tree" ? "text" : "tree";
}

async function copy(): Promise<void> {
  try {
    await writeClipboardText(props.plan.rawText);
    ElMessage.success("已复制完整计划");
  } catch {
    ElMessage.error("复制失败");
  }
}
</script>

<style scoped>
.execution-plan-toolbar {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex: none;
}
.execution-plan-toolbar :deep(.el-button) {
  width: 28px;
  min-height: 28px;
  padding: 0;
  color: var(--db-muted);
}
.execution-plan-toolbar :deep(.el-button:hover),
.execution-plan-toolbar :deep(.el-button:focus-visible) { color: var(--db-accent); }
.execution-plan-burst { flex: none; }
.execution-plan-burst-item { width: 28px; }
</style>
