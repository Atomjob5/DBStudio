import { describe, expect, it } from "vitest";
import { fitContextMenuPosition } from "./contextMenuPosition";

describe("fitContextMenuPosition", () => {
  it("keeps the menu anchored to the mouse when it fits in the viewport", () => {
    expect(fitContextMenuPosition(
      { x: 708, y: 775 }, { width: 190, height: 44 }, { width: 1920, height: 1169 }
    )).toEqual({ x: 708, y: 775 });
  });

  it("only shifts the menu by its measured size at viewport edges", () => {
    expect(fitContextMenuPosition(
      { x: 1918, y: 1167 }, { width: 190, height: 44 }, { width: 1920, height: 1169 }
    )).toEqual({ x: 1722, y: 1117 });
  });
});
