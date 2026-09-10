import type { Component } from "vue";

export type ReleaseNoteTone = "emerald" | "blue" | "amber" | "purple" | "rose" | "cyan";
export type ReleaseNoteIcon = "continue" | "structure" | "tooltip" | "copy" | "editor" | "timeline";

export interface ReleaseNoteItem {
  id: string;
  category: string;
  title: string;
  description: string;
  tone: ReleaseNoteTone;
  icon: ReleaseNoteIcon;
}

export interface ReleaseNotesRelease {
  version: string;
  title: string;
  publishedAt: string;
  summary: string;
  items: ReleaseNoteItem[];
}

// Keep the extension type available to consumers that provide custom icons.
export type ReleaseNoteIconComponent = Component;

/**
 * The release manifest is intentionally independent from the build version.
 * Append new entries in ascending semantic-version order. A future release
 * can add its items without changing the backend protocol or Maven version.
 */
export const RELEASE_NOTES_RELEASES: ReleaseNotesRelease[] = [{
  version: "1.1.0",
  title: "这次更新，查询体验更顺手了",
  publishedAt: "2026 年 9 月",
  summary: "解决了一些已知问题，向下滚动，看看这次有哪些变化。",
  items: [
    {
      id: "continue-on-error",
      category: "执行控制",
      title: "脚本遇到错误，也能继续跑下去",
      description: "新增“遇到错误继续执行”选项。批量脚本中某条语句失败后，可以继续执行后续语句，结果和失败位置仍会清晰保留。",
      tone: "emerald",
      icon: "continue"
    },
    {
      id: "clean-structure-tail",
      category: "对象浏览",
      title: "表结构展示更容易",
      description: "优化表结构查询的末位空白列，查看最后一列更加容易调整宽度。",
      tone: "blue",
      icon: "structure"
    },
    {
      id: "delayed-tooltips",
      category: "交互细节",
      title: "工具提示不再抢着出现",
      description: "鼠标停留片刻后才显示工具提示，快速移动鼠标时不会被连续提示打扰。",
      tone: "amber",
      icon: "tooltip"
    },
    {
      id: "copy-select-and-condition",
      category: "结果操作",
      title: "复制 SQL，多了两种顺手方式",
      description: "结果行可以直接复制为 SELECT 语句，也可以复制为字段等值条件，排查数据时少写几步 SQL。",
      tone: "purple",
      icon: "copy"
    },
    {
      id: "editor-content-recovery",
      category: "编辑器",
      title: "打开 SQL 内容更可靠",
      description: "修复打开 SQL 文件时内容偶发丢失的问题，文件与标签页之间的内容同步更加稳定。",
      tone: "rose",
      icon: "editor"
    },
    {
      id: "execution-timeline",
      category: "执行反馈",
      title: "执行过程，加入新动画",
      description: "新增 SQL 执行时间线加载状态，从准备到返回结果的过程更清楚，等待时也能知道查询进行到哪一步。",
      tone: "cyan",
      icon: "timeline"
    }
  ]
}, {
  version: "1.1.1",
  title: "修复了一些已知的体验问题",
  publishedAt: "2026 年 9 月",
  summary: "新增执行计划、结果筛选和彩虹括号，优化查询性能，并修复 SQL 实时诊断中的识别问题。",
  items: [
    {
      id: "execution-plan",
      category: "执行计划",
      title: "查看 SQL 如何执行",
      description: "新增执行计划查看功能，支持 MySQL、Oracle 和 OceanBase Oracle 模式。通过树形或文本视图查看计划和算子详情，帮助分析查询。",
      tone: "blue",
      icon: "structure"
    },
    {
      id: "result-filter",
      category: "结果筛选",
      title: "从表头快速筛选数据",
      description: "结果集表头菜单新增筛选操作，可仅保留或排除选中的值，更方便地定位需要查看的数据。",
      tone: "purple",
      icon: "copy"
    },
    {
      id: "rainbow-brackets",
      category: "编辑器",
      title: "用颜色区分括号层级",
      description: "新增彩虹括号功能，可在设置中开启，让嵌套 SQL 的括号层级更容易辨认。",
      tone: "amber",
      icon: "editor"
    },
    {
      id: "result-performance",
      category: "查询性能",
      title: "结果加载更流畅",
      description: "优化结果集文本排序、流式数据推送和下一页加载性能，改善处理大量查询结果时的操作体验。",
      tone: "cyan",
      icon: "timeline"
    },
    {
      id: "task-history",
      category: "执行记录",
      title: "任务和历史更清楚",
      description: "优化任务管理器与查询历史的展示，查询历史时间统一按北京时间显示。",
      tone: "emerald",
      icon: "tooltip"
    },
    {
      id: "sql-diagnostics",
      category: "SQL 诊断",
      title: "减少对象识别误报",
      description: "修复 SQL 实时诊断对原生表和同义词的识别问题，减少对象存在却被提示无法识别的情况。",
      tone: "rose",
      icon: "continue"
    }
  ]
}];

export const CURRENT_RELEASE: ReleaseNotesRelease = RELEASE_NOTES_RELEASES[RELEASE_NOTES_RELEASES.length - 1];

export const RELEASE_NOTES_STORAGE_PREFIX = "dbstudio.release-notes.seen.";
export const RELEASE_NOTES_LAST_SEEN_VERSION_KEY = "dbstudio.release-notes.last-seen-version";

export function releaseNotesStorageKey(version: string): string {
  return `${RELEASE_NOTES_STORAGE_PREFIX}v${version}`;
}

export function hasSeenRelease(version: string): boolean {
  try {
    return window.localStorage.getItem(releaseNotesStorageKey(version)) === "1";
  } catch {
    // Privacy mode, disabled storage, and quota/security errors must not stop startup.
    return false;
  }
}

export function markReleaseSeen(version: string): void {
  try {
    window.localStorage.setItem(releaseNotesStorageKey(version), "1");
  } catch {
    // The application keeps a session-level fallback in App.vue.
  }
}

export function compareReleaseVersions(left: string, right: string): number {
  const leftParts = left.split(".").map((part) => Number.parseInt(part, 10) || 0);
  const rightParts = right.split(".").map((part) => Number.parseInt(part, 10) || 0);
  for (let index = 0; index < 3; index += 1) {
    const difference = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

export function getLastSeenReleaseVersion(): string | undefined {
  try {
    const version = window.localStorage.getItem(RELEASE_NOTES_LAST_SEEN_VERSION_KEY);
    return version || undefined;
  } catch {
    return undefined;
  }
}

function highestLegacySeenVersion(): string | undefined {
  try {
    const storage = window.localStorage;
    let highest: string | undefined;
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (!key?.startsWith(RELEASE_NOTES_STORAGE_PREFIX)) continue;
      if (storage.getItem(key) !== "1") continue;
      const version = key.slice(RELEASE_NOTES_STORAGE_PREFIX.length);
      if (!/^v\d+\.\d+\.\d+$/.test(version)) continue;
      const normalizedVersion = version.slice(1);
      if (!highest || compareReleaseVersions(normalizedVersion, highest) > 0) highest = normalizedVersion;
    }
    return highest;
  } catch {
    // Privacy mode, disabled storage, and quota/security errors must not stop startup.
    return undefined;
  }
}

/**
 * Returns one merged batch for the next workspace entry. Existing per-version
 * markers are used as a migration path for installations created before the
 * last-seen cursor was introduced.
 */
export function getPendingReleaseNotes(releases: ReleaseNotesRelease[] = RELEASE_NOTES_RELEASES): ReleaseNotesRelease[] {
  const ordered = [...releases].sort((left, right) => compareReleaseVersions(left.version, right.version));
  const latest = ordered.at(-1);
  if (!latest) return [];

  const lastSeen = getLastSeenReleaseVersion() ?? highestLegacySeenVersion();
  if (!lastSeen) return hasSeenRelease(latest.version) ? [] : [latest];

  return ordered.filter((release) => compareReleaseVersions(release.version, lastSeen) > 0
    && !hasSeenRelease(release.version));
}

export function markReleaseNotesBatchSeen(releases: ReleaseNotesRelease[]): void {
  const ordered = [...releases].sort((left, right) => compareReleaseVersions(left.version, right.version));
  if (!ordered.length) return;
  ordered.forEach((release) => markReleaseSeen(release.version));
  try {
    window.localStorage.setItem(RELEASE_NOTES_LAST_SEEN_VERSION_KEY, ordered.at(-1)!.version);
  } catch {
    // The application keeps a session-level fallback in App.vue.
  }
}
