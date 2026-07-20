<template>
  <main class="workspace-chooser" aria-label="选择工作空间">
    <section class="workspace-window">
      <header class="workspace-hero">
        <div class="app-mark">D</div>
        <div><p>DBStudio</p><h1>选择工作空间</h1><span>每个空间独立保存SQL标签与数据库会话。</span></div>
        <el-button type="primary" round :icon="Plus" @click="openCreate">新建空间</el-button>
      </header>
      <el-scrollbar class="workspace-list" v-loading="loading">
        <el-empty v-if="!workspaces.length && !loading" description="还没有工作空间">
          <el-button type="primary" round @click="openCreate">创建第一个工作空间</el-button>
        </el-empty>
        <article v-for="workspace in workspaces" :key="workspace.id" class="workspace-card"
                 :class="{ occupied: workspace.state === 'in-use' }">
          <button class="workspace-open" :disabled="workspace.state === 'in-use'" @click="$emit('open', workspace)">
            <span class="workspace-icon"><FolderOpened /></span>
            <span class="workspace-copy"><strong>{{ workspace.name }}</strong>
              <small>{{ workspace.lastOpenedAt ? `上次打开 ${formatTime(workspace.lastOpenedAt)}` : "尚未打开" }}</small>
            </span>
            <span class="workspace-flags">
              <el-tag v-if="workspace.state === 'in-use'" size="small" type="info" effect="light">已在其他窗口打开</el-tag>
              <el-tag v-else-if="workspace.recoveryState === 'transaction-protected'" size="small" type="warning" effect="light">事务可恢复</el-tag>
              <el-tag v-else-if="workspace.recoveryState !== 'none'" size="small" type="warning" effect="light">有待恢复内容</el-tag>
              <span v-else class="open-arrow">›</span>
            </span>
          </button>
          <el-dropdown trigger="click" @command="(command: string) => manage(command, workspace)">
            <el-button text circle :icon="MoreFilled" aria-label="管理工作空间" />
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item command="rename" :icon="EditPen">重命名</el-dropdown-item>
              <el-dropdown-item command="delete" :icon="Delete" divided :disabled="workspace.state === 'in-use'">删除</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
        </article>
      </el-scrollbar>
      <footer><span>本机工作空间</span><el-button text :icon="Refresh" @click="$emit('refresh')">刷新</el-button></footer>
    </section>
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建工作空间' : '重命名工作空间'"
               width="420px" append-to-body @closed="name = ''">
      <el-form @submit.prevent="submit"><el-form-item label="名称">
        <el-input ref="nameInput" v-model="name" maxlength="80" show-word-limit placeholder="例如：订单系统开发" @keyup.enter="submit" />
      </el-form-item></el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!name.trim()" @click="submit">{{ dialogMode === "create" ? "创建" : "保存" }}</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<script setup lang="ts">
import { nextTick, ref } from "vue";
import { Delete, EditPen, FolderOpened, MoreFilled, Plus, Refresh } from "@element-plus/icons-vue";
import type { WorkspaceSummary } from "../types";

defineProps<{ workspaces: WorkspaceSummary[]; loading: boolean }>();
const emit = defineEmits<{ open: [workspace: WorkspaceSummary]; create: [name: string];
  rename: [workspace: WorkspaceSummary, name: string]; delete: [workspace: WorkspaceSummary]; refresh: [] }>();
const dialogVisible = ref(false); const dialogMode = ref<"create" | "rename">("create");
const selected = ref<WorkspaceSummary>(); const name = ref(""); const nameInput = ref<{ focus(): void }>();
function openCreate(): void { dialogMode.value = "create"; selected.value = undefined; name.value = ""; openDialog(); }
function manage(command: string, workspace: WorkspaceSummary): void {
  if (command === "delete") emit("delete", workspace);
  else if (command === "rename") { dialogMode.value = "rename"; selected.value = workspace; name.value = workspace.name; openDialog(); }
}
function openDialog(): void { dialogVisible.value = true; void nextTick(() => nameInput.value?.focus()); }
function submit(): void {
  const value = name.value.trim(); if (!value) return;
  if (dialogMode.value === "create") emit("create", value);
  else if (selected.value) emit("rename", selected.value, value);
  dialogVisible.value = false;
}
function formatTime(value: string): string {
  const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
</script>

<style scoped>
.workspace-chooser { display:grid; min-height:100%; place-items:center; padding:32px; background:var(--db-bg); }
.workspace-window { display:flex; width:min(760px,100%); height:min(680px,calc(100vh - 64px)); flex-direction:column; overflow:hidden;
  border:1px solid var(--db-border); border-radius:22px; background:var(--db-content); box-shadow:0 24px 70px rgba(0,0,0,.12); }
.workspace-hero { display:grid; grid-template-columns:48px 1fr auto; gap:16px; align-items:center; padding:26px 28px 22px;
  border-bottom:1px solid var(--db-border-soft); }
.app-mark { display:grid; width:44px; height:44px; place-items:center; border-radius:12px; color:white; font-size:20px; font-weight:700;
  background:linear-gradient(145deg,#2997ff,#0066cc); box-shadow:0 6px 18px rgba(0,113,227,.24); }
.workspace-hero p,.workspace-hero h1 { margin:0; }.workspace-hero p{color:var(--db-muted);font-size:12px}.workspace-hero h1{font-size:24px;line-height:1.25}.workspace-hero span{color:var(--db-muted);font-size:12px}
.workspace-list { flex:1; padding:14px 18px; }.workspace-card { display:flex; align-items:center; margin:5px 0; border-radius:13px; transition:background 120ms; }.workspace-card:hover{background:var(--db-control-hover)}
.workspace-open { display:flex; min-width:0; flex:1; align-items:center; gap:13px; padding:12px; border:0; color:inherit; text-align:left; background:transparent; cursor:pointer; }.workspace-open:disabled{cursor:not-allowed;opacity:.62}
.workspace-icon { display:grid; width:34px; height:34px; place-items:center; border-radius:9px; color:var(--db-accent); background:color-mix(in srgb,var(--db-accent) 11%,transparent); }.workspace-icon svg{width:18px}
.workspace-copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:3px}.workspace-copy strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px}.workspace-copy small{color:var(--db-muted);font-size:11px}.workspace-flags{flex:none}.open-arrow{color:var(--db-muted);font-size:24px}
footer{display:flex;align-items:center;justify-content:space-between;padding:10px 24px;border-top:1px solid var(--db-border-soft);color:var(--db-muted);font-size:11px}
@media(max-width:640px){.workspace-chooser{padding:0}.workspace-window{height:100vh;border:0;border-radius:0}.workspace-hero{grid-template-columns:44px 1fr}.workspace-hero>.el-button{grid-column:1/-1}.workspace-flags{display:none}}
</style>
