<template>
  <teleport to="body">
    <div v-if="visible" ref="menuHost" class="result-data-context-menu"
         :style="{ left: `${position.x}px`, top: `${position.y}px`,
                   visibility: position.ready ? 'visible' : 'hidden' }">
      <el-menu :collapse="true" :collapse-transition="false" @select="selectCommand">
        <el-sub-menu index="copy" popper-class="result-data-context-submenu" :teleported="true"
                     :show-timeout="100" :hide-timeout="220">
          <template #title>复制</template>
          <template v-if="mode === 'cells'">
            <el-menu-item index="copy-data">复制数据</el-menu-item>
            <el-menu-item index="copy-in" :disabled="!canIn">复制为 IN 语句</el-menu-item>
            <el-menu-item index="copy-update" :disabled="!canUpdate">复制为 UPDATE 语句</el-menu-item>
            <el-menu-item index="copy-delete" :disabled="!canDelete">复制为 DELETE 语句</el-menu-item>
            <el-menu-item index="copy-all">复制列名和数据</el-menu-item>
          </template>
          <template v-else>
            <el-menu-item index="copy-data">复制数据</el-menu-item>
            <el-menu-item index="copy-insert" :disabled="!canInsert">复制为 INSERT 语句</el-menu-item>
            <el-menu-item index="copy-update" :disabled="!canUpdate">复制为 UPDATE 语句</el-menu-item>
            <el-menu-item index="copy-delete" :disabled="!canDelete">复制为 DELETE 语句</el-menu-item>
          </template>
        </el-sub-menu>
        <el-menu-item v-if="mode === 'rows' && showClone" index="clone" :disabled="!canClone || cloneBusy">
          {{ cloneBusy ? "正在克隆…" : "克隆" }}
        </el-menu-item>
        <template v-if="mode === 'cells'">
          <el-menu-item index="set-null" :disabled="!canSetNull">设置为 NULL</el-menu-item>
          <el-menu-item index="compare" :disabled="!canCompare">比较</el-menu-item>
          <el-menu-item index="sum" :disabled="!canSum">求和</el-menu-item>
        </template>
      </el-menu>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue";
import { fitContextMenuPosition } from "../contextMenuPosition";

export type DataMenuCommand = "copy-data" | "copy-in" | "copy-all" | "copy-insert" | "copy-update" | "copy-delete"
  | "clone" | "set-null" | "compare" | "sum";
const props = defineProps<{ visible: boolean; x: number; y: number; mode: "cells" | "rows";
  canIn: boolean; canInsert: boolean; canUpdate: boolean; canDelete: boolean;
  canCompare: boolean; canSum: boolean; canSetNull?: boolean;
  showClone?: boolean; canClone?: boolean; cloneBusy?: boolean }>();
const emit = defineEmits<{ close: []; command: [command: DataMenuCommand] }>();
const menuHost = ref<HTMLElement>();
const position = ref({ x: 0, y: 0, ready: false });
let positioningVersion = 0;

watch([() => props.visible, () => props.x, () => props.y], async ([visible], previous) => {
  const version = ++positioningVersion;
  if (!visible) {
    position.value = { ...position.value, ready: false };
    removeListeners();
    return;
  }
  if (!previous?.[0]) addListeners();
  position.value = { x: props.x, y: props.y, ready: false };
  await nextTick();
  if (version !== positioningVersion || !props.visible) return;
  const host = menuHost.value;
  const bounds = host?.getBoundingClientRect();
  position.value = {
    ...fitContextMenuPosition(
      { x: props.x, y: props.y },
      { width: bounds?.width || 190, height: bounds?.height || 44 },
      { width: window.innerWidth, height: window.innerHeight }
    ),
    ready: true
  };
  if (!previous?.[0]) {
    (host?.querySelector(".el-sub-menu__title") as HTMLElement | null)?.focus();
  }
});
function selectCommand(index: string): void {
  if (["copy-data", "copy-in", "copy-all", "copy-insert", "copy-update", "copy-delete",
    "clone", "set-null", "compare", "sum"].includes(index)) {
    emit("command", index as DataMenuCommand); emit("close");
  }
}
function outsidePointer(event: PointerEvent): void {
  const target = event.target as Element | null;
  if (target?.closest(".result-data-context-menu, .result-data-context-submenu")) return;
  emit("close");
}
function keydown(event: KeyboardEvent): void { if (event.key === "Escape") emit("close"); }
function close(): void { emit("close"); }
function addListeners(): void { document.addEventListener("pointerdown", outsidePointer, true); document.addEventListener("keydown", keydown);
  window.addEventListener("resize", close); window.addEventListener("scroll", close, true); }
function removeListeners(): void { document.removeEventListener("pointerdown", outsidePointer, true); document.removeEventListener("keydown", keydown);
  window.removeEventListener("resize", close); window.removeEventListener("scroll", close, true); }
onBeforeUnmount(removeListeners);
</script>

<style>
.result-data-context-menu{position:fixed;z-index:4000;width:190px;max-height:calc(100vh - 16px);overflow:auto;
  border:1px solid var(--db-border-soft);border-radius:10px;background:var(--db-content);box-shadow:0 10px 30px rgba(0,0,0,.18)}
.result-data-context-menu .el-menu{border-right:0;background:transparent;padding:5px}.result-data-context-menu .el-menu--collapse{width:100%}
.result-data-context-menu .el-menu--collapse>.el-sub-menu>.el-sub-menu__title span{display:inline;width:auto;height:auto;overflow:visible;visibility:visible}
.result-data-context-menu .el-menu--collapse>.el-sub-menu>.el-sub-menu__title .el-sub-menu__icon-arrow{display:block;right:10px;margin-top:-6px}
.result-data-context-menu .el-menu-item,.result-data-context-menu .el-sub-menu__title{height:32px;border-radius:7px;line-height:32px}
.result-data-context-menu>.el-menu>.el-menu-item:first-of-type{margin-top:2px;border-top:1px solid var(--db-border-soft);border-radius:0;padding-top:2px}
.result-data-context-submenu{border-radius:10px}.result-data-context-submenu .el-menu{min-width:190px;padding:5px}
.result-data-context-submenu .el-menu-item{height:32px;border-radius:7px;line-height:32px}
</style>
