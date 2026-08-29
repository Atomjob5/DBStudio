import type { ResolvedTheme } from "./types";

export type AppearanceMode = "light" | "dark";
export type FontFamilyId = "system-ui" | "system-mono" | "sf-mono" | "menlo" | "monaco" | "consolas" | "jetbrains-mono";
export type ResultLoadingAnimation = "dbstudio" | "wave-physics" | "sql-timeline";

export interface TextStyle {
  color: string;
  bold: boolean;
  italic: boolean;
}

export interface EditorColorScheme {
  fontFamily: FontFamilyId;
  fontSize: number;
  lineHeight: number;
  background: string;
  foreground: string;
  keyword: TextStyle;
  identifier: TextStyle;
  string: TextStyle;
  number: TextStyle;
  comment: TextStyle;
  quotedIdentifier: TextStyle;
  lineNumber: string;
  activeLineNumber: string;
  cursor: string;
  selection: string;
  lineHighlight: string;
}

export interface ResultColorScheme {
  loadingAnimation: ResultLoadingAnimation;
  fontFamily: FontFamilyId;
  fontSize: number;
  background: string;
  stripeBackground: string;
  headerBackground: string;
  cell: TextStyle;
  header: TextStyle;
  nullValue: TextStyle;
  binaryValue: TextStyle;
  rowNumber: TextStyle;
  selectionBackground: string;
  selectionBorder: string;
  compareHighlightBackground: string;
}

export interface ModeColorScheme {
  presetId: string;
  editor: EditorColorScheme;
  result: ResultColorScheme;
}

export interface ColorSchemeSettings {
  version: 3;
  light: ModeColorScheme;
  dark: ModeColorScheme;
}

export interface FontFamilyOption {
  id: FontFamilyId;
  label: string;
  css: string;
}

export const FONT_FAMILY_OPTIONS: FontFamilyOption[] = [
  { id: "system-ui", label: "界面字体", css: "-apple-system, BlinkMacSystemFont, \"SF Pro Text\", \"PingFang SC\", \"Segoe UI\", sans-serif" },
  { id: "system-mono", label: "系统等宽", css: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace" },
  { id: "sf-mono", label: "SF Mono", css: '"SF Mono", SFMono-Regular, Menlo, Monaco, Consolas, monospace' },
  { id: "menlo", label: "Menlo", css: "Menlo, Monaco, Consolas, monospace" },
  { id: "monaco", label: "Monaco", css: "Monaco, Menlo, Consolas, monospace" },
  { id: "consolas", label: "Consolas", css: "Consolas, Menlo, Monaco, monospace" },
  { id: "jetbrains-mono", label: "JetBrains Mono", css: '"JetBrains Mono", Menlo, Monaco, Consolas, monospace' },
];

const fontCss = (id: FontFamilyId): string => FONT_FAMILY_OPTIONS.find((item) => item.id === id)?.css
  ?? FONT_FAMILY_OPTIONS[0].css;

const style = (color: string, bold = false, italic = false): TextStyle => ({ color, bold, italic });
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

function editor(values: Partial<EditorColorScheme>): EditorColorScheme {
  return {
    fontFamily: "sf-mono", fontSize: 13, lineHeight: 21,
    background: "#FFFFFF", foreground: "#1D1D1F",
    keyword: style("#9B2393", true), identifier: style("#1D1D1F"), string: style("#C41A16"),
    number: style("#1C00CF"), comment: style("#6C7986", false, true), quotedIdentifier: style("#0F68A0"),
    lineNumber: "#A1A1A6", activeLineNumber: "#6E6E73", cursor: "#0071E3",
    selection: "#B8D9F8", lineHighlight: "#F5F5F7", ...values,
  };
}

function result(values: Partial<ResultColorScheme>): ResultColorScheme {
  return {
    loadingAnimation: "dbstudio", fontFamily: "system-ui", fontSize: 12, background: "#FFFFFF", stripeBackground: "#F7F7F9",
    headerBackground: "#F5F5F7",
    cell: style("#1D1D1F"), header: style("#6E6E73", true), nullValue: style("#AF52DE", false, true),
    binaryValue: style("#B25000"), rowNumber: style("#86868B"), selectionBackground: "#DCECFB",
    selectionBorder: "#0071E3", compareHighlightBackground: "#FFF0B3", ...values,
  };
}

const makeScheme = (presetId: string, mode: AppearanceMode, values: {
  editor?: Partial<EditorColorScheme>;
  result?: Partial<ResultColorScheme>;
} = {}): ModeColorScheme => {
  const dark = mode === "dark";
  return {
    presetId,
    editor: editor({ ...(dark ? {
      fontFamily: "sf-mono", background: "#111113", foreground: "#F5F5F7", keyword: style("#FC5FA3", true),
      identifier: style("#F5F5F7"), string: style("#FC6A5D"), number: style("#D0BF69"),
      comment: style("#7F8C98", false, true), quotedIdentifier: style("#5DD8FF"), lineNumber: "#636366",
      activeLineNumber: "#A1A1A6", cursor: "#2997FF", selection: "#264F78", lineHighlight: "#19191C",
    } : {}), ...(values.editor ?? {}) }),
    result: result({ ...(dark ? {
      background: "#151517", stripeBackground: "#1B1B1E", headerBackground: "#1C1C1E", cell: style("#F5F5F7"),
      header: style("#A1A1A6", true), nullValue: style("#BF5AF2", false, true), binaryValue: style("#FF9F0A"),
      rowNumber: style("#7D7D83"), selectionBackground: "#264F78", selectionBorder: "#2997FF",
      compareHighlightBackground: "#554515",
    } : {}), ...(values.result ?? {}) }),
  };
};

export const DEFAULT_COLOR_SCHEMES: ColorSchemeSettings = {
  version: 3,
  light: makeScheme("dbstudio-light", "light"),
  dark: makeScheme("dbstudio-dark", "dark"),
};

export interface ColorSchemePreset {
  id: string;
  label: string;
  description: string;
  mode: AppearanceMode;
  scheme: ModeColorScheme;
}

const lightPresets: ColorSchemePreset[] = [
  { id: "vs-light", label: "VS Light+", description: "清晰、通用的开发环境", mode: "light", scheme: makeScheme("vs-light", "light", { editor: { keyword: style("#0000FF", false), string: style("#A31515"), number: style("#098658"), comment: style("#008000", false, true), quotedIdentifier: style("#001080") }, result: { header: style("#0451A5", true), nullValue: style("#AF00DB", false, true), binaryValue: style("#A31515"), compareHighlightBackground: "#FFF4CE" } }) },
  { id: "github-light", label: "GitHub Light", description: "GitHub 经典亮色", mode: "light", scheme: makeScheme("github-light", "light", { editor: { background: "#FFFFFF", foreground: "#24292F", keyword: style("#CF222E", true), string: style("#0A3069"), number: style("#0550AE"), comment: style("#6E7781", false, true), quotedIdentifier: style("#953800") }, result: { background: "#FFFFFF", headerBackground: "#F6F8FA", cell: style("#24292F"), header: style("#57606A", true), nullValue: style("#8250DF", false, true), binaryValue: style("#9A6700"), compareHighlightBackground: "#FFF8C5" } }) },
  { id: "solarized-light", label: "Solarized Light", description: "低对比度护眼配色", mode: "light", scheme: makeScheme("solarized-light", "light", { editor: { background: "#FDF6E3", foreground: "#657B83", keyword: style("#859900", true), string: style("#2AA198"), number: style("#D33682"), comment: style("#93A1A1", false, true), quotedIdentifier: style("#268BD2"), selection: "#EEE8D5", lineHighlight: "#F5EFD9" }, result: { background: "#FDF6E3", headerBackground: "#EEE8D5", cell: style("#657B83"), header: style("#586E75", true), nullValue: style("#6C71C4", false, true), binaryValue: style("#CB4B16"), compareHighlightBackground: "#EFE4B0" } }) },
  { id: "one-light", label: "Atom One Light", description: "柔和高可读亮色", mode: "light", scheme: makeScheme("one-light", "light", { editor: { background: "#FAFAFA", foreground: "#383A42", keyword: style("#A626A4", true), string: style("#50A14F"), number: style("#986801"), comment: style("#A0A1A7", false, true), quotedIdentifier: style("#0184BB") }, result: { background: "#FAFAFA", headerBackground: "#EAEAEB", cell: style("#383A42"), header: style("#696C77", true), nullValue: style("#A626A4", false, true), binaryValue: style("#986801"), compareHighlightBackground: "#F4E7B2" } }) },
  { id: "quiet-light", label: "Quiet Light", description: "简洁安静的编辑体验", mode: "light", scheme: makeScheme("quiet-light", "light", { editor: { background: "#F5F5F5", foreground: "#333333", keyword: style("#AA0D91", true), string: style("#C41A16"), number: style("#1C00CF"), comment: style("#8E908C", false, true), quotedIdentifier: style("#0451A5") }, result: { background: "#F5F5F5", headerBackground: "#E5E5E5", cell: style("#333333"), header: style("#555555", true), nullValue: style("#AF52DE", false, true), binaryValue: style("#A67F59"), compareHighlightBackground: "#F2E6B8" } }) },
  { id: "intellij-light", label: "IntelliJ Light", description: "IDEA 默认亮色风格", mode: "light", scheme: makeScheme("intellij-light", "light", { editor: { background: "#FFFFFF", foreground: "#000000", keyword: style("#0033B3", true), string: style("#067D17"), number: style("#1750EB"), comment: style("#8C8C8C", false, true), quotedIdentifier: style("#871094") }, result: { background: "#FFFFFF", headerBackground: "#F2F2F2", cell: style("#000000"), header: style("#555555", true), nullValue: style("#871094", false, true), binaryValue: style("#9E880D"), compareHighlightBackground: "#FFF1B8" } }) },
];

const darkPresets: ColorSchemePreset[] = [
  { id: "dark-plus", label: "Dark+", description: "VS Code 默认深色", mode: "dark", scheme: makeScheme("dark-plus", "dark", { editor: { background: "#1E1E1E", foreground: "#D4D4D4", keyword: style("#569CD6", true), string: style("#CE9178"), number: style("#B5CEA8"), comment: style("#6A9955", false, true), quotedIdentifier: style("#9CDCFE"), lineNumber: "#858585", activeLineNumber: "#C6C6C6", cursor: "#AEAFAD", selection: "#264F78", lineHighlight: "#2A2D2E" }, result: { background: "#1E1E1E", headerBackground: "#252526", cell: style("#D4D4D4"), header: style("#9CDCFE", true), nullValue: style("#C586C0", false, true), binaryValue: style("#DCDCAA"), rowNumber: style("#858585"), selectionBackground: "#264F78", selectionBorder: "#569CD6", compareHighlightBackground: "#4B4628" } }) },
  { id: "one-dark-pro", label: "One Dark Pro", description: "Atom/VS Code 热门深色", mode: "dark", scheme: makeScheme("one-dark-pro", "dark", { editor: { background: "#282C34", foreground: "#ABB2BF", keyword: style("#C678DD", true), string: style("#98C379"), number: style("#D19A66"), comment: style("#5C6370", false, true), quotedIdentifier: style("#E06C75"), lineNumber: "#4B5263", activeLineNumber: "#ABB2BF", cursor: "#528BFF", selection: "#3E4451", lineHighlight: "#2C323C" }, result: { background: "#282C34", headerBackground: "#21252B", cell: style("#ABB2BF"), header: style("#61AFEF", true), nullValue: style("#C678DD", false, true), binaryValue: style("#D19A66"), rowNumber: style("#5C6370"), selectionBackground: "#3E4451", selectionBorder: "#528BFF", compareHighlightBackground: "#3F4938" } }) },
  { id: "dracula", label: "Dracula", description: "高对比度紫色深色", mode: "dark", scheme: makeScheme("dracula", "dark", { editor: { background: "#282A36", foreground: "#F8F8F2", keyword: style("#FF79C6", true), string: style("#F1FA8C"), number: style("#BD93F9"), comment: style("#6272A4", false, true), quotedIdentifier: style("#8BE9FD"), lineNumber: "#6272A4", activeLineNumber: "#F8F8F2", cursor: "#F8F8F0", selection: "#44475A", lineHighlight: "#2E303E" }, result: { background: "#282A36", headerBackground: "#44475A", cell: style("#F8F8F2"), header: style("#8BE9FD", true), nullValue: style("#BD93F9", false, true), binaryValue: style("#FFB86C"), rowNumber: style("#6272A4"), selectionBackground: "#44475A", selectionBorder: "#FF79C6", compareHighlightBackground: "#4D4934" } }) },
  { id: "monokai", label: "Monokai", description: "经典高饱和深色", mode: "dark", scheme: makeScheme("monokai", "dark", { editor: { background: "#272822", foreground: "#F8F8F2", keyword: style("#F92672", true), string: style("#E6DB74"), number: style("#AE81FF"), comment: style("#75715E", false, true), quotedIdentifier: style("#66D9EF"), lineNumber: "#90908A", activeLineNumber: "#F8F8F2", cursor: "#F8F8F0", selection: "#49483E", lineHighlight: "#2D2E27" }, result: { background: "#272822", headerBackground: "#3E3D32", cell: style("#F8F8F2"), header: style("#A6E22E", true), nullValue: style("#AE81FF", false, true), binaryValue: style("#FD971F"), rowNumber: style("#75715E"), selectionBackground: "#49483E", selectionBorder: "#F92672", compareHighlightBackground: "#4A482F" } }) },
  { id: "nord", label: "Nord", description: "冷静舒适的蓝灰色", mode: "dark", scheme: makeScheme("nord", "dark", { editor: { background: "#2E3440", foreground: "#D8DEE9", keyword: style("#81A1C1", true), string: style("#A3BE8C"), number: style("#B48EAD"), comment: style("#616E88", false, true), quotedIdentifier: style("#88C0D0"), lineNumber: "#616E88", activeLineNumber: "#D8DEE9", cursor: "#88C0D0", selection: "#434C5E", lineHighlight: "#353C4A" }, result: { background: "#2E3440", headerBackground: "#3B4252", cell: style("#D8DEE9"), header: style("#88C0D0", true), nullValue: style("#B48EAD", false, true), binaryValue: style("#EBCB8B"), rowNumber: style("#616E88"), selectionBackground: "#434C5E", selectionBorder: "#88C0D0", compareHighlightBackground: "#424A3A" } }) },
  { id: "solarized-dark", label: "Solarized Dark", description: "低对比度护眼配色", mode: "dark", scheme: makeScheme("solarized-dark", "dark", { editor: { background: "#002B36", foreground: "#839496", keyword: style("#859900", true), string: style("#2AA198"), number: style("#D33682"), comment: style("#586E75", false, true), quotedIdentifier: style("#268BD2"), lineNumber: "#586E75", activeLineNumber: "#93A1A1", cursor: "#B58900", selection: "#073642", lineHighlight: "#06313B" }, result: { background: "#002B36", headerBackground: "#073642", cell: style("#839496"), header: style("#93A1A1", true), nullValue: style("#6C71C4", false, true), binaryValue: style("#CB4B16"), rowNumber: style("#586E75"), selectionBackground: "#073642", selectionBorder: "#2AA198", compareHighlightBackground: "#3F492B" } }) },
];

export const COLOR_SCHEME_PRESETS = [...lightPresets, ...darkPresets];

export function cloneColorSchemes(value: ColorSchemeSettings): ColorSchemeSettings {
  return clone(value);
}

export function applyPreset(settings: ColorSchemeSettings, mode: AppearanceMode, presetId: string): ColorSchemeSettings {
  const preset = COLOR_SCHEME_PRESETS.find((item) => item.mode === mode && item.id === presetId);
  if (!preset) return cloneColorSchemes(settings);
  const next = cloneColorSchemes(settings);
  const loadingAnimation = next[mode].result.loadingAnimation;
  next[mode] = clone(preset.scheme);
  next[mode].result.loadingAnimation = loadingAnimation;
  return next;
}

const isHex = (value: unknown): value is string => typeof value === "string" && /^#[0-9A-Fa-f]{6}$/.test(value);
const validFont = (value: unknown): value is FontFamilyId => FONT_FAMILY_OPTIONS.some((item) => item.id === value);
const exactKeys = (value: unknown, keys: string[]): boolean => !!value && typeof value === "object"
  && Object.keys(value as Record<string, unknown>).sort().join("|") === keys.slice().sort().join("|");
const validStyle = (value: unknown): value is TextStyle => {
  if (!exactKeys(value, ["color", "bold", "italic"])) return false;
  const item = value as TextStyle;
  return isHex(item.color) && typeof item.bold === "boolean" && typeof item.italic === "boolean";
};
const validEditor = (value: unknown): value is EditorColorScheme => {
  if (!exactKeys(value, ["fontFamily", "fontSize", "lineHeight", "background", "foreground", "keyword", "identifier", "string", "number", "comment", "quotedIdentifier", "lineNumber", "activeLineNumber", "cursor", "selection", "lineHighlight"])) return false;
  const item = value as EditorColorScheme;
  return validFont(item.fontFamily) && Number.isInteger(item.fontSize) && item.fontSize >= 10 && item.fontSize <= 24
    && Number.isInteger(item.lineHeight) && item.lineHeight >= 14 && item.lineHeight <= 36
    && [item.background, item.foreground, item.lineNumber, item.activeLineNumber, item.cursor,
      item.selection, item.lineHighlight].every(isHex)
    && [item.keyword, item.identifier, item.string, item.number, item.comment, item.quotedIdentifier].every(validStyle);
};
const validResult = (value: unknown): value is ResultColorScheme => {
  if (!exactKeys(value, ["loadingAnimation", "fontFamily", "fontSize", "background", "stripeBackground", "headerBackground", "cell", "header", "nullValue", "binaryValue", "rowNumber", "selectionBackground", "selectionBorder", "compareHighlightBackground"])) return false;
  const item = value as ResultColorScheme;
  return (item.loadingAnimation === "dbstudio" || item.loadingAnimation === "wave-physics"
    || item.loadingAnimation === "sql-timeline")
    && validFont(item.fontFamily) && Number.isInteger(item.fontSize) && item.fontSize >= 10 && item.fontSize <= 24
    && [item.background, item.stripeBackground, item.headerBackground, item.selectionBackground,
      item.selectionBorder, item.compareHighlightBackground].every(isHex)
    && [item.cell, item.header, item.nullValue, item.binaryValue, item.rowNumber].every(validStyle);
};

export function isValidColorSchemeSettings(value: unknown): value is ColorSchemeSettings {
  if (!exactKeys(value, ["version", "light", "dark"])) return false;
  const item = value as ColorSchemeSettings;
  return item.version === 3 && exactKeys(item.light, ["presetId", "editor", "result"]) && exactKeys(item.dark, ["presetId", "editor", "result"])
    && typeof item.light.presetId === "string" && typeof item.dark.presetId === "string"
    && validEditor(item.light.editor) && validEditor(item.dark.editor)
    && validResult(item.light.result) && validResult(item.dark.result);
}

export function parseColorSchemeSettings(value?: string): ColorSchemeSettings {
  if (!value) return cloneColorSchemes(DEFAULT_COLOR_SCHEMES);
  try {
    const parsed: unknown = migrateColorSchemeSettings(JSON.parse(value));
    return isValidColorSchemeSettings(parsed) ? cloneColorSchemes(parsed) : cloneColorSchemes(DEFAULT_COLOR_SCHEMES);
  } catch {
    return cloneColorSchemes(DEFAULT_COLOR_SCHEMES);
  }
}

function migrateColorSchemeSettings(value: unknown): unknown {
  if (!value || typeof value !== "object") return value;
  const item = value as Record<string, unknown>;
  if (item.version !== 1 && item.version !== 2) return value;
  const next = clone(item) as Record<string, unknown>;
  for (const mode of ["light", "dark"] as const) {
    const modeValue = next[mode] as Record<string, unknown> | undefined;
    const resultValue = modeValue?.result as Record<string, unknown> | undefined;
    if (!resultValue) return value;
    if (item.version === 1) {
      resultValue.stripeBackground = mode === "dark" ? "#1B1B1E" : "#F7F7F9";
      resultValue.compareHighlightBackground = mode === "dark" ? "#554515" : "#FFF0B3";
    }
    resultValue.loadingAnimation = "dbstudio";
  }
  next.version = 3;
  return next;
}

export function serializeColorSchemeSettings(value: ColorSchemeSettings): string {
  return JSON.stringify(value);
}

export function fontFamilyCss(id: FontFamilyId): string {
  return fontCss(id);
}

export function applyColorSchemeCss(mode: ResolvedTheme, scheme: ModeColorScheme): void {
  const root = document.documentElement;
  const editor = scheme.editor;
  const result = scheme.result;
  const editorStyles = {
    "--db-editor-bg": editor.background,
    "--db-editor-foreground": editor.foreground,
    "--db-editor-font-family": fontFamilyCss(editor.fontFamily),
    "--db-editor-font-size": `${editor.fontSize}px`,
    "--db-editor-line-height": `${editor.lineHeight}px`,
    "--db-editor-keyword": editor.keyword.color,
    "--db-editor-string": editor.string.color,
    "--db-editor-number": editor.number.color,
    "--db-editor-comment": editor.comment.color,
    "--db-editor-quoted-identifier": editor.quotedIdentifier.color,
    "--db-editor-line-number": editor.lineNumber,
    "--db-editor-active-line-number": editor.activeLineNumber,
    "--db-editor-cursor": editor.cursor,
    "--db-editor-selection": editor.selection,
    "--db-editor-line-highlight": editor.lineHighlight,
  };
  const resultStyles = {
    "--db-result-bg": result.background,
    "--db-result-stripe-bg": result.stripeBackground,
    "--db-result-header-bg": result.headerBackground,
    "--db-result-font-family": fontFamilyCss(result.fontFamily),
    "--db-result-font-size": `${result.fontSize}px`,
    "--db-result-cell-color": result.cell.color,
    "--db-result-cell-font-weight": result.cell.bold ? "700" : "400",
    "--db-result-cell-font-style": result.cell.italic ? "italic" : "normal",
    "--db-result-header-color": result.header.color,
    "--db-result-header-font-weight": result.header.bold ? "600" : "400",
    "--db-result-header-font-style": result.header.italic ? "italic" : "normal",
    "--db-result-null-color": result.nullValue.color,
    "--db-result-null-font-weight": result.nullValue.bold ? "700" : "400",
    "--db-result-null-font-style": result.nullValue.italic ? "italic" : "normal",
    "--db-result-binary-color": result.binaryValue.color,
    "--db-result-binary-font-weight": result.binaryValue.bold ? "700" : "400",
    "--db-result-binary-font-style": result.binaryValue.italic ? "italic" : "normal",
    "--db-result-row-number-color": result.rowNumber.color,
    "--db-result-row-number-font-weight": result.rowNumber.bold ? "700" : "400",
    "--db-result-row-number-font-style": result.rowNumber.italic ? "italic" : "normal",
    "--db-result-selection-bg": result.selectionBackground,
    "--db-result-selection-border": result.selectionBorder,
    "--db-result-compare-highlight-bg": result.compareHighlightBackground,
  };
  for (const [key, value] of Object.entries({ ...editorStyles, ...resultStyles })) root.style.setProperty(key, value);
  root.dataset.appearanceMode = mode;
}
