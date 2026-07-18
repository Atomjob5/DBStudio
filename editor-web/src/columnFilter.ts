import type { QueryColumn } from "./types";

export interface ColumnOption extends QueryColumn {
  index: number;
}

export function matchesColumnQuery(column: QueryColumn, query: string): boolean {
  const groups = query.split(/[,，]+/).map((group) => group.trim()).filter(Boolean);
  if (!groups.length) return true;
  const haystack = `${column.label} ${column.name} ${column.remarks}`.toLocaleLowerCase();
  return groups.some((group) => group.split(/\s+/).filter(Boolean)
    .every((term) => haystack.includes(term.toLocaleLowerCase())));
}

export function resultColumnOptions(columns: string[], details?: QueryColumn[]): ColumnOption[] {
  return columns.map((label, index) => {
    const detail = details?.[index];
    return {
      index,
      label: detail?.label || label,
      name: detail?.name || label,
      remarks: detail?.remarks || "",
      catalog: detail?.catalog || "",
      schema: detail?.schema || "",
      table: detail?.table || "",
      typeName: detail?.typeName || ""
    };
  });
}
