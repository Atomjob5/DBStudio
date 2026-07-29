export type CopySeparator = "comma" | "tab" | "semicolon" | "pipe";
export type ResultCopyMode = "headers" | "data" | "headers-and-data";

export interface CopyColumn {
  label: string;
  index: number;
}

export interface CopyColumnWithRemarks {
  label: string;
  remarks: string;
}

const SEPARATORS: Record<CopySeparator, string> = {
  comma: ",",
  tab: "\t",
  semicolon: ";",
  pipe: "|"
};

export function separatorCharacter(separator: CopySeparator): string {
  return SEPARATORS[separator];
}

export function escapeDelimitedValue(value: string, separator: string): string {
  if (!value.includes(separator) && !value.includes("\"") && !/[\r\n]/.test(value)) return value;
  return `"${value.replace(/"/g, '""')}"`;
}

export function resultCopyText(columns: CopyColumn[], rows: Array<Array<string | null>>,
                               mode: ResultCopyMode, separator: CopySeparator): string {
  const delimiter = separatorCharacter(separator);
  const line = (values: Array<string | null | undefined>) => values
    .map((value) => escapeDelimitedValue(value === null || value === undefined ? "NULL" : value, delimiter))
    .join(delimiter);
  const output: string[] = [];
  if (mode !== "data") output.push(line(columns.map((column) => column.label)));
  if (mode !== "headers") {
    for (const row of rows) output.push(line(columns.map((column) => row[column.index])));
  }
  return output.join("\n");
}

export function resultColumnRemarksText(
  columns: CopyColumnWithRemarks[],
  separator: CopySeparator,
): string {
  const delimiter = separatorCharacter(separator);
  return columns.map((column) => {
    const remarks = columnAliasRemarks(column.remarks);
    return remarks ? `${column.label} as "${remarks.replace(/"/g, '""')}"` : column.label;
  }).join(delimiter);
}

function columnAliasRemarks(value: string): string {
  const brackets = [value.indexOf("（"), value.indexOf("(")].filter((index) => index >= 0);
  const end = brackets.length ? Math.min(...brackets) : value.length;
  return value.slice(0, end).trim();
}
