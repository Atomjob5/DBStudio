import type { CompletionCandidate } from "../types";

export function truncateCompletionComment(value: string, maximum = 80): string {
  const characters = Array.from(value);
  return characters.length <= maximum ? value : `${characters.slice(0, maximum).join("")}…`;
}

export function completionDocumentation(item: CompletionCandidate): string {
  if (item.kind === "snippet") {
    return [`**SQL片段：${item.displayLabel}**`, item.remarks, `\`\`\`sql\n${item.insertText}\n\`\`\``]
      .filter(Boolean).join("\n\n");
  }
  return [`**${item.documentationPath}**`, item.typeName ? `类型：${item.typeName}` : "", item.remarks]
    .filter(Boolean).join("\n\n");
}
