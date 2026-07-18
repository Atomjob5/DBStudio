export type CopySeparator = "comma" | "tab" | "semicolon" | "pipe";
export type ResultCopyMode = "headers" | "data" | "headers-and-data";

export interface CopyColumn {
  label: string;
  index: number;
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
