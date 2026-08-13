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
    <div class="shortcut-settings-content">
      <section class="shortcut-settings-section shortcut-overview">
        <div class="shortcut-section-heading">
          <div>
            <strong>录制说明</strong>
            <span>点击操作右侧的按键开始录制。字母和数字需要搭配修饰键。</span>
          </div>
          <el-button
            class="shortcut-reset-button"
            size="small"
            :disabled="saving"
            @click="resetBindings"
          >
            恢复默认
          </el-button>
        </div>
      </section>

      <el-alert
        v-if="feedback"
        class="shortcut-feedback"
        :title="feedback"
        type="warning"
        :closable="false"
        show-icon
      />

      <section
        v-for="group in groupedActions"
        :key="group.id"
        class="shortcut-settings-section shortcut-group"
      >
        <div class="shortcut-section-heading">
          <div>
            <strong>{{ group.label }}</strong>
            <span>{{ group.description }} · {{ group.actions.length }} 项</span>
          </div>
        </div>
        <div class="shortcut-rows">
          <div
            v-for="action in group.actions"
            :key="action.id"
            class="shortcut-row"
            :class="{ recording: recordingAction === action.id }"
          >
            <span class="shortcut-action-label" :title="action.label">{{ action.label }}</span>
            <button
              class="shortcut-recorder"
              type="button"
              :aria-label="`录制${action.label}快捷键`"
              :disabled="saving"
              @click="startRecording(action.id)"
            >
              <span v-if="recordingAction === action.id" class="recording-prompt">请按下快捷键…</span>
              <kbd v-else-if="bindings[action.id]" :title="displayShortcut(bindings[action.id])">
                {{ displayShortcut(bindings[action.id]) }}
              </kbd>
              <span v-else class="unassigned">未设置</span>
            </button>
            <el-button
              class="shortcut-clear-button"
              text
              size="small"
              aria-label="清除快捷键"
              :disabled="saving || !bindings[action.id]"
              @click="clearBinding(action.id)"
            >
              清除
            </el-button>
          </div>
        </div>
      </section>
    </div>
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
  type ShortcutGroupId,
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

const GROUP_DESCRIPTIONS: Record<ShortcutGroupId, string> = {
  mainToolbar: "文件、查询、事务与全局操作",
  workspaceSidebar: "对象与连接面板",
  sqlEditor: "编辑、排版与补全",
  resultSet: "结果复制、布局、比较与导出",
  statusBar: "结果数据加载",
};

const groupedActions = computed(() => SHORTCUT_GROUPS.map((group) => ({
  ...group,
  description: GROUP_DESCRIPTIONS[group.id],
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
.shortcut-settings-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.shortcut-settings-section {
  padding: 16px;
  border: 1px solid var(--db-border-soft);
  border-radius: 14px;
  background: var(--db-content);
  background: color-mix(in srgb, var(--db-content) 82%, transparent);
}
.shortcut-section-heading {
  display: flex;
  margin-bottom: 12px;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.shortcut-section-heading > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}
.shortcut-section-heading strong {
  overflow: hidden;
  font-size: 14px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.shortcut-section-heading span {
  overflow: hidden;
  color: var(--db-muted);
  font-size: 11px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.shortcut-overview .shortcut-section-heading { margin-bottom: 0; align-items: center; }
.shortcut-reset-button { flex: none; }
.shortcut-feedback {
  margin: 0;
  padding: 8px 10px;
  border-radius: 10px;
}
.shortcut-feedback :deep(.el-alert__content) { min-width: 0; padding: 0; }
.shortcut-feedback :deep(.el-alert__title) {
  overflow: hidden;
  font-size: 12px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.shortcut-row {
  display: grid;
  min-height: 40px;
  padding: 4px 0;
  align-items: center;
  grid-template-columns: minmax(136px, 1fr) minmax(0, 160px) 44px;
  gap: 8px;
  border-bottom: 1px solid var(--db-border-soft);
}
.shortcut-row:last-child { border-bottom: 0; }
.shortcut-row.recording {
  margin-inline: -6px;
  padding-inline: 6px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--db-accent) 8%, transparent);
}
.shortcut-action-label { min-width: 0; overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.shortcut-recorder {
  display: flex;
  width: 100%;
  min-width: 0;
  height: 30px;
  padding: 0 10px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--db-control-border);
  border-radius: 7px;
  color: var(--db-text);
  background: var(--db-control-bg);
  cursor: pointer;
  transition: border-color 120ms ease, background-color 120ms ease;
}
.shortcut-recorder:hover { background: var(--db-control-hover); }
.shortcut-recorder:hover, .shortcut-recorder:focus-visible { border-color: var(--db-accent); outline: none; }
.shortcut-row.recording .shortcut-recorder {
  border-color: var(--db-accent);
  background: color-mix(in srgb, var(--db-accent) 10%, var(--db-control-bg));
}
.shortcut-recorder:disabled { opacity: .6; cursor: not-allowed; }
.shortcut-recorder kbd {
  min-width: 0;
  overflow: hidden;
  font-family: "SFMono-Regular", Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recording-prompt { color: var(--db-accent); font-size: 12px; }
.unassigned { color: var(--db-muted); font-size: 12px; }
.shortcut-clear-button { width: 44px; margin-left: 0; padding-inline: 5px; }
</style>
