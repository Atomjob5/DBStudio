<template>
  <el-drawer :model-value="modelValue" title="配色方案" size="700px" class="appearance-scheme-drawer"
             @update:model-value="drawerChanged">
    <div class="appearance-scheme-content">
      <div class="appearance-mode-row">
        <div>
          <strong>编辑{{ mode === "dark" ? "深色" : "亮色" }}方案</strong>
          <span>亮色和深色分别保存，当前草稿只在保存后应用</span>
        </div>
        <el-radio-group v-model="mode" size="small" aria-label="配色方案模式">
          <el-radio-button value="light"><Sunny /> 亮色</el-radio-button>
          <el-radio-button value="dark"><Moon /> 深色</el-radio-button>
        </el-radio-group>
      </div>

      <section class="appearance-section">
        <div class="appearance-section-heading">
          <div><strong>热门预设</strong><span>点击后覆盖当前{{ mode === "dark" ? "深色" : "亮色" }}草稿，可继续微调</span></div>
          <el-tag v-if="activeScheme.presetId === 'custom'" size="small" type="info">自定义</el-tag>
        </div>
        <div class="appearance-presets">
          <button v-for="preset in presets" :key="preset.id" type="button" class="appearance-preset"
                  :class="{ active: activeScheme.presetId === preset.id }" @click="selectPreset(preset.id)">
            <span class="appearance-preset-swatch" :style="presetSwatch(preset.scheme)">
              <i /><i /><i />
            </span>
            <span class="appearance-preset-copy"><strong>{{ preset.label }}</strong><small>{{ preset.description }}</small></span>
            <el-icon v-if="activeScheme.presetId === preset.id"><Check /></el-icon>
          </button>
        </div>
      </section>

      <section class="appearance-section appearance-preview-section">
        <div class="appearance-section-heading"><div><strong>实时预览</strong><span>修改任意细节后立即查看效果</span></div></div>
        <div class="appearance-preview" :style="previewVariables">
          <div class="appearance-preview-editor">
            <div class="preview-gutter"><span>1</span><span>2</span><span>3</span><span>4</span></div>
            <pre><span class="preview-keyword">SELECT</span> <span class="preview-identifier">user_name</span>, <span class="preview-string">'active'</span>
<span class="preview-keyword">FROM</span> <span class="preview-quoted">`users`</span> <span class="preview-keyword">WHERE</span> id = <span class="preview-number">42</span>;
<span class="preview-comment">-- 查询当前用户</span></pre>
          </div>
          <div class="preview-result" role="table" aria-label="结果集预览">
            <div class="preview-result-row preview-result-header"><span>id</span><span>status</span><span>payload</span></div>
            <div class="preview-result-row"><span>42</span><span>active</span><span>0xA1B2</span></div>
            <div class="preview-result-row preview-result-selected"><span>43</span><span class="preview-null">NULL</span><span>0xC3D4</span></div>
          </div>
        </div>
      </section>

      <section class="appearance-section">
        <div class="appearance-section-heading"><div><strong>编辑器</strong><span>字体、尺寸与编辑器基础颜色</span></div></div>
        <div class="appearance-fields appearance-fields-three">
          <label>字体<select :value="activeScheme.editor.fontFamily" @change="updateFont('editor', $event)"><option v-for="font in fonts" :key="font.id" :value="font.id">{{ font.label }}</option></select></label>
          <label>字号<el-input-number :model-value="activeScheme.editor.fontSize" :min="10" :max="24" size="small" controls-position="right" @update:model-value="activeScheme.editor.fontSize = $event ?? 13; markCustom()" /></label>
          <label>行高<el-input-number :model-value="activeScheme.editor.lineHeight" :min="14" :max="36" size="small" controls-position="right" @update:model-value="activeScheme.editor.lineHeight = $event ?? 21; markCustom()" /></label>
        </div>
        <div class="appearance-fields appearance-fields-four">
          <ColorField label="背景" :value="activeScheme.editor.background" @update="updateColor('editor', 'background', $event)" />
          <ColorField label="普通文本" :value="activeScheme.editor.foreground" @update="updateColor('editor', 'foreground', $event)" />
          <ColorField label="选区" :value="activeScheme.editor.selection" @update="updateColor('editor', 'selection', $event)" />
          <ColorField label="当前行" :value="activeScheme.editor.lineHighlight" @update="updateColor('editor', 'lineHighlight', $event)" />
        </div>
      </section>

      <section class="appearance-section">
        <div class="appearance-section-heading"><div><strong>语法颜色</strong><span>每类文字可单独调整颜色、粗体和斜体</span></div></div>
        <div class="appearance-token-list">
          <div v-for="token in editorTokens" :key="token.key" class="appearance-token-row">
            <span class="appearance-token-label">{{ token.label }}</span>
            <ColorField :label="`${token.label}颜色`" :value="styleFor('editor', token.key).color" @update="updateStyle('editor', token.key, 'color', $event)" />
            <el-checkbox :model-value="styleFor('editor', token.key).bold" :aria-label="`${token.label}粗体`"
                         @update:model-value="updateStyle('editor', token.key, 'bold', $event === true)">粗体</el-checkbox>
            <el-checkbox :model-value="styleFor('editor', token.key).italic" :aria-label="`${token.label}斜体`"
                         @update:model-value="updateStyle('editor', token.key, 'italic', $event === true)">斜体</el-checkbox>
          </div>
        </div>
      </section>

      <section class="appearance-section">
        <div class="appearance-section-heading"><div><strong>结果集</strong><span>表格文字、表头、特殊值及选中状态</span></div></div>
        <div class="appearance-fields appearance-fields-two">
          <label>字体<select :value="activeScheme.result.fontFamily" @change="updateFont('result', $event)"><option v-for="font in fonts" :key="font.id" :value="font.id">{{ font.label }}</option></select></label>
          <label>字号<el-input-number :model-value="activeScheme.result.fontSize" :min="10" :max="24" size="small" controls-position="right" @update:model-value="activeScheme.result.fontSize = $event ?? 12; markCustom()" /></label>
        </div>
        <div class="appearance-fields appearance-fields-four">
          <ColorField label="表格背景" :value="activeScheme.result.background" @update="updateColor('result', 'background', $event)" />
          <ColorField label="表头背景" :value="activeScheme.result.headerBackground" @update="updateColor('result', 'headerBackground', $event)" />
          <ColorField label="选中背景" :value="activeScheme.result.selectionBackground" @update="updateColor('result', 'selectionBackground', $event)" />
          <ColorField label="选中边框" :value="activeScheme.result.selectionBorder" @update="updateColor('result', 'selectionBorder', $event)" />
        </div>
        <div class="appearance-token-list">
          <div v-for="token in resultTokens" :key="token.key" class="appearance-token-row">
            <span class="appearance-token-label">{{ token.label }}</span>
            <ColorField :label="`${token.label}颜色`" :value="styleFor('result', token.key).color" @update="updateStyle('result', token.key, 'color', $event)" />
            <el-checkbox :model-value="styleFor('result', token.key).bold" :aria-label="`${token.label}粗体`"
                         @update:model-value="updateStyle('result', token.key, 'bold', $event === true)">粗体</el-checkbox>
            <el-checkbox :model-value="styleFor('result', token.key).italic" :aria-label="`${token.label}斜体`"
                         @update:model-value="updateStyle('result', token.key, 'italic', $event === true)">斜体</el-checkbox>
          </div>
        </div>
      </section>
    </div>
    <template #footer>
      <div class="appearance-drawer-footer">
        <span>保存后将应用到当前{{ mode === "dark" ? "深色" : "亮色" }}配置</span>
        <div><el-button @click="drawerChanged(false)">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></div>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, ref, watch } from "vue";
import { Check, Moon, Sunny } from "@element-plus/icons-vue";
import type { ColorSchemeSettings, AppearanceMode, ModeColorScheme, FontFamilyId, TextStyle } from "../appearance";
import { applyPreset, cloneColorSchemes, COLOR_SCHEME_PRESETS, FONT_FAMILY_OPTIONS, fontFamilyCss } from "../appearance";

const ColorField = defineComponent({
  name: "ColorField",
  props: { label: { type: String, required: true }, value: { type: String, required: true } },
  emits: ["update"],
  setup(props, { emit }) {
    return () => h("label", { class: "appearance-color-field" }, [
      h("span", props.label),
      h("span", { class: "appearance-color-control" }, [
        h("input", { type: "color", value: props.value, "aria-label": props.label,
          onInput: (event: Event) => emit("update", (event.target as HTMLInputElement).value) }),
        h("code", props.value),
      ]),
    ]);
  },
});

const props = defineProps<{ modelValue: boolean; schemes: ColorSchemeSettings; saving?: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; save: [value: ColorSchemeSettings]; cancel: [] }>();
const mode = ref<AppearanceMode>("light");
const draft = ref<ColorSchemeSettings>(cloneColorSchemes(props.schemes));
const fonts = FONT_FAMILY_OPTIONS;
const editorTokens: Array<{ key: keyof Pick<ModeColorScheme["editor"], "keyword" | "identifier" | "string" | "number" | "comment" | "quotedIdentifier">; label: string }> = [
  { key: "keyword", label: "关键字" }, { key: "identifier", label: "普通标识符" }, { key: "string", label: "字符串" },
  { key: "number", label: "数字" }, { key: "comment", label: "注释" }, { key: "quotedIdentifier", label: "引用标识符" },
];
const resultTokens: Array<{ key: keyof Pick<ModeColorScheme["result"], "cell" | "header" | "nullValue" | "binaryValue" | "rowNumber">; label: string }> = [
  { key: "cell", label: "普通值" }, { key: "header", label: "表头" }, { key: "nullValue", label: "NULL" },
  { key: "binaryValue", label: "二进制值" }, { key: "rowNumber", label: "行号" },
];
const presets = computed(() => COLOR_SCHEME_PRESETS.filter((item) => item.mode === mode.value));
const activeScheme = computed(() => draft.value[mode.value]);
const previewVariables = computed(() => {
  const scheme = activeScheme.value;
  const editor = scheme.editor;
  const result = scheme.result;
  return {
    "--preview-editor-bg": editor.background, "--preview-editor-fg": editor.foreground,
    "--preview-editor-font": fontFamilyCss(editor.fontFamily), "--preview-editor-size": `${editor.fontSize}px`,
    "--preview-editor-line": `${editor.lineHeight}px`, "--preview-keyword": editor.keyword.color,
    "--preview-keyword-weight": editor.keyword.bold ? "700" : "400", "--preview-keyword-style": editor.keyword.italic ? "italic" : "normal",
    "--preview-identifier": editor.identifier.color, "--preview-string": editor.string.color,
    "--preview-number": editor.number.color, "--preview-comment": editor.comment.color, "--preview-quoted": editor.quotedIdentifier.color,
    "--preview-result-bg": result.background, "--preview-result-header-bg": result.headerBackground,
    "--preview-result-font": fontFamilyCss(result.fontFamily), "--preview-result-size": `${result.fontSize}px`,
    "--preview-result-cell": result.cell.color, "--preview-result-header": result.header.color,
    "--preview-result-header-weight": result.header.bold ? "700" : "400", "--preview-result-header-style": result.header.italic ? "italic" : "normal",
    "--preview-result-null": result.nullValue.color, "--preview-result-binary": result.binaryValue.color,
    "--preview-result-selection": result.selectionBackground, "--preview-result-border": result.selectionBorder,
  };
});

watch(() => props.schemes, (value) => { draft.value = cloneColorSchemes(value); }, { deep: true });
watch(() => props.modelValue, (open) => {
  if (open) {
    draft.value = cloneColorSchemes(props.schemes);
    mode.value = "light";
  }
});

function styleFor(section: "editor" | "result", key: string): TextStyle {
  return (activeScheme.value[section] as unknown as Record<string, TextStyle>)[key];
}

function markCustom(): void { activeScheme.value.presetId = "custom"; }
function updateFont(section: "editor" | "result", event: Event): void {
  const value = (event.target as HTMLSelectElement).value as FontFamilyId;
  activeScheme.value[section].fontFamily = value;
  markCustom();
}
function updateColor(section: "editor" | "result", key: string, value: string): void {
  (activeScheme.value[section] as unknown as Record<string, unknown>)[key] = value;
  if (section === "editor" || section === "result") markCustom();
}
function updateStyle(section: "editor" | "result", key: string, property: keyof TextStyle, value: string | boolean): void {
  const item = styleFor(section, key);
  if (item) item[property] = value as never;
  markCustom();
}
function selectPreset(id: string): void { draft.value = applyPreset(draft.value, mode.value, id); }
function presetSwatch(scheme: ModeColorScheme): Record<string, string> {
  return { background: scheme.editor.background, color: scheme.editor.foreground, "--swatch-accent": scheme.editor.keyword.color, "--swatch-string": scheme.editor.string.color, "--swatch-comment": scheme.editor.comment.color };
}
function save(): void { emit("save", cloneColorSchemes(draft.value)); }
function drawerChanged(value: boolean): void {
  if (!value) {
    draft.value = cloneColorSchemes(props.schemes);
    mode.value = "light";
    emit("cancel");
  }
  emit("update:modelValue", value);
}
</script>

<style scoped>
.appearance-scheme-content { display: flex; flex-direction: column; gap: 20px; padding-bottom: 12px; }
.appearance-mode-row, .appearance-section-heading, .appearance-drawer-footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.appearance-mode-row > div, .appearance-section-heading > div { display: flex; flex-direction: column; gap: 4px; }
.appearance-mode-row strong, .appearance-section-heading strong { font-size: 14px; font-weight: 650; }
.appearance-mode-row span, .appearance-section-heading span, .appearance-drawer-footer > span { color: var(--db-muted); font-size: 11px; }
.appearance-mode-row :deep(.el-radio-button__inner) { display: inline-flex; align-items: center; gap: 4px; }
.appearance-section { display: flex; flex-direction: column; gap: 12px; }
.appearance-presets { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.appearance-preset { display: flex; min-width: 0; align-items: center; gap: 8px; padding: 8px; border: 1px solid var(--db-border-soft); border-radius: 10px; background: var(--db-panel-soft); color: var(--db-text); text-align: left; cursor: pointer; }
.appearance-preset:hover, .appearance-preset.active { border-color: var(--db-accent); background: var(--db-accent-soft); }
.appearance-preset-swatch { display: flex; width: 34px; height: 34px; flex: none; flex-direction: column; justify-content: center; gap: 3px; padding: 6px; overflow: hidden; border-radius: 7px; box-shadow: inset 0 0 0 1px color-mix(in srgb, currentColor 18%, transparent); }
.appearance-preset-swatch i { display: block; width: 65%; height: 3px; border-radius: 3px; background: var(--swatch-accent); }
.appearance-preset-swatch i:nth-child(2) { width: 86%; background: var(--swatch-string); }.appearance-preset-swatch i:nth-child(3) { width: 48%; background: var(--swatch-comment); }
.appearance-preset-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 3px; }.appearance-preset-copy strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.appearance-preset-copy small { overflow: hidden; color: var(--db-muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.appearance-preset > .el-icon { color: var(--db-accent); }
.appearance-preview { display: grid; grid-template-columns: 1.15fr .85fr; min-height: 180px; overflow: hidden; border: 1px solid var(--db-border-soft); border-radius: 10px; background: var(--preview-editor-bg); color: var(--preview-editor-fg); box-shadow: var(--db-shadow-sm); }
.appearance-preview-editor { display: flex; min-width: 0; padding: 14px 10px 14px 0; font-family: var(--preview-editor-font); font-size: var(--preview-editor-size); line-height: var(--preview-editor-line); }.appearance-preview-editor pre { min-width: 0; margin: 0; overflow: auto; white-space: pre; }.preview-gutter { display: flex; width: 30px; flex: none; flex-direction: column; padding-right: 8px; color: color-mix(in srgb, var(--preview-editor-fg) 45%, transparent); text-align: right; user-select: none; }.preview-keyword { color: var(--preview-keyword); font-weight: var(--preview-keyword-weight); font-style: var(--preview-keyword-style); }.preview-identifier { color: var(--preview-identifier); }.preview-string { color: var(--preview-string); }.preview-number { color: var(--preview-number); }.preview-comment { color: var(--preview-comment); font-style: italic; }.preview-quoted { color: var(--preview-quoted); }
.preview-result { display: flex; min-width: 0; flex-direction: column; justify-content: center; padding: 14px 10px; background: var(--preview-result-bg); font-family: var(--preview-result-font); font-size: var(--preview-result-size); }.preview-result-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); min-height: 30px; align-items: center; border-bottom: 1px solid color-mix(in srgb, var(--preview-result-header) 18%, transparent); }.preview-result-row span { min-width: 0; padding: 0 7px; overflow: hidden; color: var(--preview-result-cell); text-overflow: ellipsis; white-space: nowrap; }.preview-result-header { background: var(--preview-result-header-bg); }.preview-result-header span { color: var(--preview-result-header); font-weight: var(--preview-result-header-weight); font-style: var(--preview-result-header-style); }.preview-result-selected { outline: 1px solid var(--preview-result-border); background: var(--preview-result-selection); }.preview-null { color: var(--preview-result-null) !important; font-style: italic; }.preview-result-row span:last-child { color: var(--preview-result-binary); }
.appearance-fields { display: grid; gap: 10px; }.appearance-fields-three { grid-template-columns: 1.4fr .8fr .8fr; }.appearance-fields-two { grid-template-columns: 1.4fr .8fr; }.appearance-fields-four { grid-template-columns: repeat(4, minmax(0, 1fr)); }.appearance-fields label { display: flex; min-width: 0; flex-direction: column; gap: 5px; color: var(--db-muted); font-size: 11px; }.appearance-fields select { width: 100%; height: 28px; padding: 0 7px; border: 1px solid var(--db-border); border-radius: 7px; background: var(--db-control-bg); color: var(--db-text); outline: 0; }.appearance-fields :deep(.el-input-number) { width: 100%; }.appearance-token-list { display: flex; flex-direction: column; gap: 5px; }.appearance-token-row { display: grid; grid-template-columns: minmax(95px, 1fr) minmax(125px, 1.3fr) 56px 56px; min-height: 34px; align-items: center; gap: 8px; padding: 3px 0; border-bottom: 1px solid var(--db-border-soft); }.appearance-token-label { font-size: 12px; }.appearance-color-field { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 6px; color: var(--db-muted); font-size: 11px; }.appearance-color-control { display: inline-flex; min-width: 0; align-items: center; gap: 4px; }.appearance-color-control input { width: 24px; height: 24px; padding: 0; border: 0; border-radius: 6px; background: transparent; cursor: pointer; }.appearance-color-control code { color: var(--db-text-secondary); font-size: 10px; }.appearance-token-row :deep(.el-checkbox) { margin-right: 0; }.appearance-drawer-footer { width: 100%; }
@media (max-width: 620px) { .appearance-presets { grid-template-columns: repeat(2, minmax(0, 1fr)); }.appearance-preview { grid-template-columns: 1fr; }.appearance-fields-four { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
