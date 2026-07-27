export interface ContextMenuPoint {
  x: number;
  y: number;
}

export interface ContextMenuSize {
  width: number;
  height: number;
}

export function fitContextMenuPosition(requested: ContextMenuPoint, menu: ContextMenuSize,
                                       viewport: ContextMenuSize, margin = 8): ContextMenuPoint {
  const safeMargin = Math.max(0, margin);
  const maxX = Math.max(safeMargin, viewport.width - Math.max(0, menu.width) - safeMargin);
  const maxY = Math.max(safeMargin, viewport.height - Math.max(0, menu.height) - safeMargin);
  return {
    x: Math.max(safeMargin, Math.min(requested.x, maxX)),
    y: Math.max(safeMargin, Math.min(requested.y, maxY))
  };
}
