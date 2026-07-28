<template>
  <el-drawer
    :model-value="modelValue"
    title="快捷键"
    size="520px"
    class="shortcut-settings-drawer"
    @keydown.capture="recordKey"
    @closed="stopRecording"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="shortcut-drawer-toolbar">
      <p>点击操作右侧的按键开始录制。字母和数字需要搭配修饰键。</p>
      <el-button size="small" :disabled="saving" @click="resetBindings">恢复默认</el-button>
    </div>

    <el-alert
      v-if="feedback"
      class="shortcut-feedback"
      :title="feedback"
      type="warning"
      :closable="false"
      show-icon
    />

    <section v-for="group in groupedActions" :key="group.id" class="shortcut-group">
      <h3>{{ group.label }}</h3>
      <div
        v-for="action in group.actions"
        :key="action.id"
        class="shortcut-row"
        :class="{ recording: recordingAction === action.id }"
      >
        <span class="shortcut-action-label">{{ action.label }}</span>
        <button
          class="shortcut-recorder"
          type="button"
          :aria-label="`录制${action.label}快捷键`"
          :disabled="saving"
          @click="startRecording(action.id)"
        >
          <span v-if="recordingAction === action.id" class="recording-prompt">请按下快捷键…</span>
          <kbd v-else-if="bindings[action.id]">{{ displayShortcut(bindings[action.id]) }}</kbd>
          <span v-else class="unassigned">未设置</span>
        </button>
        <el-button
          text
          size="small"
          aria-label="清除快捷键"
          :disabled="saving || !bindings[action.id]"
          @click="clearBinding(action.id)"
        >
          清除
        </el-button>
      </div>
    </section>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  SHORTCUT_ACTIONS,
  SHORTCUT_GROUPS,
  displayShortcut,
  findShortcutConflict,
  shortcutFromKeyboardEvent,
  validateShortcutBinding,
  type ShortcutActionId,
  type ShortcutBinding,
  type ShortcutBindings,
} from "../shortcuts";

const props = defineProps<{
  modelValue: boolean;
  bindings: ShortcutBindings;
  saving?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  updateBinding: [actionId: ShortcutActionId, binding: ShortcutBinding];
  resetDefaults: [];
  recording: [value: boolean];
}>();

const recordingAction = ref<ShortcutActionId | null>(null);
const feedback = ref("");

const groupedActions = computed(() => SHORTCUT_GROUPS.map((group) => ({
  ...group,
  actions: SHORTCUT_ACTIONS.filter((action) => action.group === group.id),
})));

watch(() => props.modelValue, (open) => {
  if (!open) stopRecording();
});

function startRecording(actionId: ShortcutActionId): void {
  feedback.value = "";
  recordingAction.value = actionId;
  emit("recording", true);
}

function stopRecording(): void {
  if (recordingAction.value !== null) emit("recording", false);
  recordingAction.value = null;
}

function clearBinding(actionId: ShortcutActionId): void {
  feedback.value = "";
  if (recordingAction.value === actionId) stopRecording();
  emit("updateBinding", actionId, null);
}

function resetBindings(): void {
  feedback.value = "";
  stopRecording();
  emit("resetDefaults");
}

function recordKey(event: KeyboardEvent): void {
  const actionId = recordingAction.value;
  if (!actionId) return;
  event.preventDefault();
  event.stopPropagation();
  if (event.key === "Escape" && !event.shiftKey && !event.altKey && !event.ctrlKey && !event.metaKey) {
    feedback.value = "";
    stopRecording();
    return;
  }
  if ((event.key === "Delete" || event.key === "Backspace")
      && !event.shiftKey && !event.altKey && !event.ctrlKey && !event.metaKey) {
    clearBinding(actionId);
    return;
  }
  const candidate = shortcutFromKeyboardEvent(event);
  if (!candidate) return;
  const validation = validateShortcutBinding(candidate);
  if (!validation.valid) {
    feedback.value = validation.reason;
    return;
  }
  const conflict = findShortcutConflict(props.bindings, validation.binding, actionId);
  if (conflict) {
    const conflictAction = SHORTCUT_ACTIONS.find((action) => action.id === conflict);
    feedback.value = `“${displayShortcut(validation.binding)}”已用于“${conflictAction?.label ?? conflict}”，请先清除原绑定。`;
    return;
  }
  feedback.value = "";
  emit("updateBinding", actionId, validation.binding);
  stopRecording();
}
</script>

<style scoped>
.shortcut-drawer-toolbar {
  display: flex;
  margin-bottom: 14px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.shortcut-drawer-toolbar p {
  margin: 0;
  color: var(--db-muted);
  font-size: 12px;
  line-height: 1.5;
}
.shortcut-feedback { margin-bottom: 12px; }
.shortcut-group {
  margin-bottom: 14px;
  overflow: hidden;
  border: 1px solid var(--db-border-soft);
  border-radius: 12px;
  background: var(--db-content);
}
.shortcut-group h3 {
  margin: 0;
  padding: 10px 14px;
  border-bottom: 1px solid var(--db-border-soft);
  color: var(--db-muted);
  font-size: 12px;
  font-weight: 650;
}
.shortcut-row {
  display: grid;
  min-height: 44px;
  padding: 5px 8px 5px 14px;
  align-items: center;
  grid-template-columns: minmax(140px, 1fr) 168px 48px;
  gap: 8px;
  border-bottom: 1px solid var(--db-border-soft);
}
.shortcut-row:last-child { border-bottom: 0; }
.shortcut-row.recording { background: color-mix(in srgb, var(--db-accent) 8%, transparent); }
.shortcut-action-label { min-width: 0; overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.shortcut-recorder {
  display: flex;
  min-width: 0;
  height: 30px;
  padding: 0 10px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--db-border);
  border-radius: 7px;
  color: var(--db-text);
  background: var(--db-panel);
  cursor: pointer;
}
.shortcut-recorder:hover, .shortcut-recorder:focus-visible { border-color: var(--db-accent); outline: none; }
.shortcut-recorder:disabled { opacity: .6; cursor: not-allowed; }
.shortcut-recorder kbd { overflow: hidden; font: inherit; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.recording-prompt { color: var(--db-accent); font-size: 12px; }
.unassigned { color: var(--db-muted); font-size: 12px; }
</style>
