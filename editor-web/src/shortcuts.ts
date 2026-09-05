export const SHORTCUT_GROUPS = [
  { id: "mainToolbar", label: "主工具栏" },
  { id: "workspaceSidebar", label: "工作区侧栏" },
  { id: "sqlEditor", label: "SQL 编辑器" },
  { id: "resultSet", label: "结果集" },
  { id: "statusBar", label: "状态栏" },
] as const;

export type ShortcutGroupId = (typeof SHORTCUT_GROUPS)[number]["id"];

export const SHORTCUT_ACTIONS = [
  { id: "file.newQuery", group: "mainToolbar", label: "新建查询", defaultBinding: null },
  { id: "file.openSql", group: "mainToolbar", label: "打开 SQL", defaultBinding: null },
  { id: "file.saveSql", group: "mainToolbar", label: "保存", defaultBinding: null },
  { id: "query.executeCurrent", group: "mainToolbar", label: "执行当前语句", defaultBinding: "F8" },
  { id: "query.executeCurrentNewTab", group: "mainToolbar", label: "在新结果集执行当前语句", defaultBinding: null },
  { id: "query.executeAll", group: "mainToolbar", label: "执行全部语句", defaultBinding: "F7" },
  { id: "query.explain", group: "mainToolbar", label: "查看执行计划", defaultBinding: null },
  { id: "query.cancel", group: "mainToolbar", label: "取消执行", defaultBinding: "Shift+Escape" },
  { id: "transaction.commit", group: "mainToolbar", label: "提交", defaultBinding: null },
  { id: "transaction.rollback", group: "mainToolbar", label: "回滚", defaultBinding: null },
  { id: "data.import", group: "mainToolbar", label: "导入", defaultBinding: null },
  { id: "history.open", group: "mainToolbar", label: "历史", defaultBinding: null },
  { id: "settings.open", group: "mainToolbar", label: "设置", defaultBinding: null },
  { id: "ui.toggleTheme", group: "mainToolbar", label: "切换主题", defaultBinding: null },
  { id: "app.exit", group: "mainToolbar", label: "退出", defaultBinding: null },
  { id: "workspace.objects", group: "workspaceSidebar", label: "数据库对象", defaultBinding: null },
  { id: "workspace.connections", group: "workspaceSidebar", label: "连接管理", defaultBinding: null },
  { id: "workspace.refreshObjects", group: "workspaceSidebar", label: "刷新对象树", defaultBinding: null },
  { id: "editor.toggleMinimap", group: "sqlEditor", label: "切换 Minimap", defaultBinding: null },
  { id: "editor.toggleWordWrap", group: "sqlEditor", label: "切换自动换行", defaultBinding: null },
  { id: "editor.format", group: "sqlEditor", label: "格式化 SQL", defaultBinding: null },
  { id: "editor.compact", group: "sqlEditor", label: "压缩 SQL", defaultBinding: null },
  { id: "editor.uppercase", group: "sqlEditor", label: "转换大写", defaultBinding: null },
  { id: "editor.lowercase", group: "sqlEditor", label: "转换小写", defaultBinding: null },
  { id: "editor.toggleLineComment", group: "sqlEditor", label: "单行注释", defaultBinding: null },
  { id: "editor.toggleBlockComment", group: "sqlEditor", label: "全部注释", defaultBinding: null },
  { id: "editor.complete", group: "sqlEditor", label: "触发 SQL 补全", defaultBinding: null },
  { id: "result.toggleEditMode", group: "resultSet", label: "编辑模式", defaultBinding: null },
  { id: "result.toggleSingleRecord", group: "resultSet", label: "单个记录查看", defaultBinding: null },
  { id: "result.toggleRecordComparison", group: "resultSet", label: "比较记录", defaultBinding: null },
  { id: "result.restoreLayout", group: "resultSet", label: "复原列布局", defaultBinding: null },
  { id: "result.copySelection", group: "resultSet", label: "复制选择", defaultBinding: null },
  { id: "result.loadNext", group: "statusBar", label: "下一页数据", defaultBinding: null },
  { id: "result.loadAll", group: "statusBar", label: "获取全部数据", defaultBinding: null },
] as const;

export type ShortcutActionId = (typeof SHORTCUT_ACTIONS)[number]["id"];
export type ShortcutBinding = string | null;
export type ShortcutBindings = Record<ShortcutActionId, ShortcutBinding>;

const ACTION_IDS = new Set<string>(SHORTCUT_ACTIONS.map((action) => action.id));

export const DEFAULT_SHORTCUT_BINDINGS = Object.freeze(
  Object.fromEntries(SHORTCUT_ACTIONS.map((action) => [action.id, action.defaultBinding])) as ShortcutBindings,
);

const RESERVED_BINDINGS = new Set([
  "Mod+C",
  "Mod+V",
  "Mod+X",
  "Mod+Z",
  "Mod+Shift+Z",
  "Mod+Y",
  "Mod+A",
  "Mod+F",
]);

const DANGEROUS_LEGACY_BINDINGS = new Set([
  "F5",
  "Mod+N",
  "Mod+O",
  "Mod+S",
  "Mod+Shift+S",
  "Mod+R",
  "Mod+Shift+C",
]);

const MODIFIER_ORDER = ["Mod", "Alt", "Shift"] as const;
const NAMED_KEYS = new Set([
  "Escape",
  "Enter",
  "Tab",
  "Space",
  "ArrowUp",
  "ArrowDown",
  "ArrowLeft",
  "ArrowRight",
  "Home",
  "End",
  "PageUp",
  "PageDown",
  "Delete",
  "Backspace",
  "Minus",
  "Equal",
  "BracketLeft",
  "BracketRight",
  "Backslash",
  "Semicolon",
  "Quote",
  "Comma",
  "Period",
  "Slash",
  "Backquote",
]);

const CODE_KEYS: Record<string, string> = {
  Space: "Space",
  Minus: "Minus",
  Equal: "Equal",
  BracketLeft: "BracketLeft",
  BracketRight: "BracketRight",
  Backslash: "Backslash",
  Semicolon: "Semicolon",
  Quote: "Quote",
  Comma: "Comma",
  Period: "Period",
  Slash: "Slash",
  Backquote: "Backquote",
};

const KEY_LABELS: Record<string, string> = {
  Escape: "Esc",
  Space: "空格",
  ArrowUp: "↑",
  ArrowDown: "↓",
  ArrowLeft: "←",
  ArrowRight: "→",
  PageUp: "Page Up",
  PageDown: "Page Down",
  Backspace: "Backspace",
  Delete: "Delete",
  Minus: "-",
  Equal: "=",
  BracketLeft: "[",
  BracketRight: "]",
  Backslash: "\\",
  Semicolon: ";",
  Quote: "'",
  Comma: ",",
  Period: ".",
  Slash: "/",
  Backquote: "`",
};

function cloneDefaults(): ShortcutBindings {
  return { ...DEFAULT_SHORTCUT_BINDINGS };
}

function normalizeKey(key: string, code = ""): string | null {
  if (/^F(?:[1-9]|1[0-2])$/i.test(key)) return key.toUpperCase();
  if (/^[a-z]$/i.test(key)) return key.toUpperCase();
  if (/^[0-9]$/.test(key)) return key;
  if (key === " " || key === "Spacebar") return "Space";
  if (NAMED_KEYS.has(key)) return key;
  if (CODE_KEYS[code]) return CODE_KEYS[code];
  return null;
}

export function shortcutFromKeyboardEvent(
  event: Pick<KeyboardEvent, "key" | "code" | "metaKey" | "ctrlKey" | "altKey" | "shiftKey">,
): string | null {
  if (["Control", "Meta", "Alt", "Shift"].includes(event.key)) return null;
  const key = normalizeKey(event.key, event.code);
  if (!key) return null;
  const parts: string[] = [];
  if (event.metaKey || event.ctrlKey) parts.push("Mod");
  if (event.altKey) parts.push("Alt");
  if (event.shiftKey) parts.push("Shift");
  parts.push(key);
  return parts.join("+");
}

export function normalizeShortcutBinding(value: unknown): string | null {
  if (typeof value !== "string" || !value.trim()) return null;
  const rawParts = value.trim().split("+").map((part) => part.trim()).filter(Boolean);
  const keyPart = rawParts.at(-1);
  if (!keyPart) return null;
  const key = normalizeKey(keyPart === "Esc" ? "Escape" : keyPart);
  if (!key) return null;
  const modifiers = new Set<string>();
  for (const part of rawParts.slice(0, -1)) {
    const normalized = /^(ctrl|control|cmd|command|meta|mod)$/i.test(part)
      ? "Mod"
      : /^alt$/i.test(part)
        ? "Alt"
        : /^shift$/i.test(part)
          ? "Shift"
          : null;
    if (!normalized || modifiers.has(normalized)) return null;
    modifiers.add(normalized);
  }
  return [...MODIFIER_ORDER.filter((modifier) => modifiers.has(modifier)), key].join("+");
}

export type ShortcutValidationResult =
  | { valid: true; binding: string }
  | { valid: false; reason: string };

export function validateShortcutBinding(value: unknown): ShortcutValidationResult {
  const binding = normalizeShortcutBinding(value);
  if (!binding) return { valid: false, reason: "无法识别该快捷键" };
  if (RESERVED_BINDINGS.has(binding)) {
    return { valid: false, reason: "该组合是系统编辑快捷键，不能用于业务操作" };
  }
  const parts = binding.split("+");
  const key = parts.at(-1)!;
  const modifierCount = parts.length - 1;
  if (/^F(?:[1-9]|1[0-2])$/.test(key)) return { valid: true, binding };
  if (key === "Escape") {
    return binding === "Shift+Escape"
      ? { valid: true, binding }
      : { valid: false, reason: "仅支持将 Shift+Esc 绑定为业务快捷键" };
  }
  if (["Enter", "Tab", "Space"].includes(key) && modifierCount === 0) {
    return { valid: false, reason: "该按键必须与 Ctrl/Command、Alt 或 Shift 组合使用" };
  }
  if (/^[A-Z0-9]$/.test(key) && modifierCount === 0) {
    return { valid: false, reason: "字母和数字必须与 Ctrl/Command、Alt 或 Shift 组合使用" };
  }
  if (modifierCount === 0) {
    return { valid: false, reason: "该按键必须包含修饰键" };
  }
  return { valid: true, binding };
}

export function isReservedShortcut(binding: string): boolean {
  return RESERVED_BINDINGS.has(binding);
}

export function isDangerousLegacyShortcut(binding: string): boolean {
  return DANGEROUS_LEGACY_BINDINGS.has(binding);
}

export function findShortcutConflict(
  bindings: ShortcutBindings,
  binding: string,
  excludedAction?: ShortcutActionId,
): ShortcutActionId | null {
  const match = SHORTCUT_ACTIONS.find(
    (action) => action.id !== excludedAction && bindings[action.id] === binding,
  );
  return match?.id ?? null;
}

export function actionForShortcut(
  bindings: ShortcutBindings,
  binding: string,
): ShortcutActionId | null {
  return SHORTCUT_ACTIONS.find((action) => bindings[action.id] === binding)?.id ?? null;
}

export function parseShortcutBindings(raw: unknown): ShortcutBindings {
  let source: unknown = raw;
  if (typeof raw === "string") {
    try {
      source = JSON.parse(raw);
    } catch {
      return cloneDefaults();
    }
  }
  if (!source || typeof source !== "object" || Array.isArray(source)) return cloneDefaults();
  const record = source as Record<string, unknown>;
  const bindings = cloneDefaults();
  for (const action of SHORTCUT_ACTIONS) {
    if (!(action.id in record)) continue;
    if (record[action.id] === null) {
      bindings[action.id] = null;
      continue;
    }
    const validation = validateShortcutBinding(record[action.id]);
    bindings[action.id] = validation.valid ? validation.binding : action.defaultBinding;
  }
  const used = new Set<string>();
  for (const action of SHORTCUT_ACTIONS) {
    const binding = bindings[action.id];
    if (!binding) continue;
    if (used.has(binding)) {
      bindings[action.id] = action.defaultBinding && !used.has(action.defaultBinding)
        ? action.defaultBinding
        : null;
    }
    if (bindings[action.id]) used.add(bindings[action.id]!);
  }
  return bindings;
}

export function serializeShortcutBindings(bindings: ShortcutBindings): string {
  const ordered = Object.fromEntries(SHORTCUT_ACTIONS.map((action) => [action.id, bindings[action.id] ?? null]));
  return JSON.stringify(ordered);
}

export function displayShortcut(binding: ShortcutBinding, mac = isMacPlatform()): string {
  if (!binding) return "未设置";
  const parts = binding.split("+");
  if (mac) {
    return parts
      .map((part) => part === "Mod" ? "⌘" : part === "Alt" ? "⌥" : part === "Shift" ? "⇧" : KEY_LABELS[part] ?? part)
      .join("");
  }
  return parts
    .map((part) => part === "Mod" ? "Ctrl" : part === "Alt" ? "Alt" : part === "Shift" ? "Shift" : KEY_LABELS[part] ?? part)
    .join("+");
}

export function shortcutTooltip(
  label: string,
  actionId: ShortcutActionId,
  bindings: ShortcutBindings,
): string {
  const binding = bindings[actionId];
  return binding ? `${label} · ${displayShortcut(binding)}` : label;
}

export function isShortcutActionId(value: string): value is ShortcutActionId {
  return ACTION_IDS.has(value);
}

function isMacPlatform(): boolean {
  if (typeof navigator === "undefined") return false;
  return /Mac|iPhone|iPad|iPod/i.test(navigator.platform);
}
