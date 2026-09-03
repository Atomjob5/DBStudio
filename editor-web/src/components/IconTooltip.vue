<template>
  <span class="icon-tooltip-trigger" v-bind="$attrs">
    <el-tooltip
      :content="content"
      :placement="placement"
      :disabled="disabled"
      :show-after="showAfter"
      :hide-after="hideAfter"
      :enterable="enterable"
    >
      <slot />
    </el-tooltip>
  </span>
</template>

<script setup lang="ts">
import type { Placement } from "element-plus";

defineOptions({ name: "IconTooltip", inheritAttrs: false });

withDefaults(defineProps<{
  content: string;
  placement?: Placement;
  disabled?: boolean;
  showAfter?: number;
  hideAfter?: number;
  enterable?: boolean;
}>(), {
  placement: "top",
  disabled: false,
  showAfter: 1000,
  hideAfter: 0,
  enterable: false,
});
</script>

<style>
.icon-tooltip-trigger { display: inline-flex; }
</style>
