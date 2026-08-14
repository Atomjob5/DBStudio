<template>
  <teleport to="body">
    <div v-if="visible" ref="menuHost" class="result-header-context-menu"
         :style="{ left: `${x}px`, top: `${y}px` }">
      <el-menu :collapse="true" :collapse-transition="false" @select="selectCommand">
        <el-sub-menu index="copy" popper-class="result-header-context-submenu" :teleported="true"
                     :show-timeout="100" :hide-timeout="220">
          <template #title>复制</template>
          <el-menu-item index="copy-headers">复制列名</el-menu-item>
          <el-menu-item index="copy-headers-with-remarks">复制列名和注释</el-menu-item>
          <el-menu-item index="copy-data" :disabled="!canCopyData">复制数据</el-menu-item>
          <el-menu-item index="copy-all">复制列名和数据</el-menu-item>
          <el-menu-item index="copy-in" :disabled="!canIn">复制为 IN 语句</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="export" popper-class="result-header-context-submenu" :teleported="true"
                     :show-timeout="100" :hide-timeout="220">
          <template #title>导出为</template>
          <el-menu-item index="export-csv" :disabled="!canExportCsv">CSV</el-menu-item>
          <el-menu-item index="export-excel" :disabled="!canExportExcel">Excel</el-menu-item>
          <el-menu-item index="export-sql" :disabled="!canExportSql">SQL 文件</el-menu-item>
        </el-sub-menu>
        <el-menu-item index="sum" :disabled="!canSum">求和</el-menu-item>
        <el-menu-item index="move-left" :disabled="!canMoveLeft">移动到最左</el-menu-item>
        <el-menu-item index="move-right" :disabled="!canMoveRight">移动到最右</el-menu-item>
      </el-menu>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue";

export type HeaderMenuCommand = "copy-headers" | "copy-headers-with-remarks" | "copy-data"
  | "copy-all" | "copy-in" | "export-csv" | "export-excel" | "export-sql"
  | "sum" | "move-left" | "move-right";

const props = defineProps<{ visible: boolean; x: number; y: number; canCopyData: boolean;
  canIn?: boolean; canMoveLeft: boolean; canMoveRight: boolean; canSum: boolean;
  canExportCsv?: boolean; canExportExcel?: boolean; canExportSql?: boolean }>();
const emit = defineEmits<{ close: []; command: [command: HeaderMenuCommand] }>();
const menuHost = ref<HTMLElement>();

watch(() => props.visible, async (visible) => {
  if (!visible) { removeListeners(); return; }
  addListeners();
  await nextTick();
  (menuHost.value?.querySelector(".el-sub-menu__title") as HTMLElement | null)?.focus();
});

function selectCommand(index: string): void {
  if (["copy-headers", "copy-headers-with-remarks", "copy-data", "copy-all", "copy-in",
    "export-csv", "export-excel", "export-sql", "sum", "move-left", "move-right"].includes(index)) {
    emit("command", index as HeaderMenuCommand);
    emit("close");
  }
}

function outsidePointer(event: PointerEvent): void {
  const target = event.target as Element | null;
  if (target?.closest(".result-header-context-menu, .result-header-context-submenu")) return;
  emit("close");
}
function keydown(event: KeyboardEvent): void { if (event.key === "Escape") emit("close"); }
function closeMenu(): void { emit("close"); }
function addListeners(): void {
  document.addEventListener("pointerdown", outsidePointer, true);
  document.addEventListener("keydown", keydown);
  window.addEventListener("resize", closeMenu);
  window.addEventListener("scroll", closeMenu, true);
}
function removeListeners(): void {
  document.removeEventListener("pointerdown", outsidePointer, true);
  document.removeEventListener("keydown", keydown);
  window.removeEventListener("resize", closeMenu);
  window.removeEventListener("scroll", closeMenu, true);
}
onBeforeUnmount(removeListeners);
</script>

<style>
.result-header-context-menu {
  position: fixed; z-index: 4000; width: 180px; max-height: calc(100vh - 16px); overflow: auto;
  border: 1px solid var(--db-border-soft); border-radius: 10px;
  background: var(--db-content); box-shadow: 0 10px 30px rgba(0,0,0,.18);
}
.result-header-context-menu .el-menu { border-right: 0; background: transparent; padding: 5px; }
.result-header-context-menu .el-menu--collapse { width: 100%; }
.result-header-context-menu .el-menu--collapse > .el-sub-menu > .el-sub-menu__title span {
  display: inline; width: auto; height: auto; overflow: visible; visibility: visible;
}
.result-header-context-menu .el-menu--collapse > .el-sub-menu > .el-sub-menu__title .el-sub-menu__icon-arrow {
  display: block; right: 10px; margin-top: -6px;
}
.result-header-context-menu .el-menu-item,
.result-header-context-menu .el-sub-menu__title { height: 32px; border-radius: 7px; line-height: 32px; }
.result-header-context-submenu { border-radius: 10px; }
.result-header-context-submenu .el-menu { min-width: 180px; padding: 5px; }
.result-header-context-submenu .el-menu-item { height: 32px; border-radius: 7px; line-height: 32px; }
</style>
