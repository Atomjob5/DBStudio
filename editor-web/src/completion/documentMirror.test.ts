import { describe, expect, it } from "vitest";
import { CompletionDocumentMirror } from "./documentMirror";

describe("CompletionDocumentMirror", () => {
  it("synchronizes and applies multi-cursor changes using original offsets", () => {
    const mirror = new CompletionDocumentMirror();
    mirror.sync("model", 1, "select aa, bb");
    mirror.change("model", 1, 2, [
      { rangeOffset: 7, rangeLength: 2, text: "alpha" },
      { rangeOffset: 11, rangeLength: 2, text: "beta" }
    ]);
    expect(mirror.read("model", 2)).toBe("select alpha, beta");
  });

  it("rejects version mismatches and recovers through a full sync", () => {
    const mirror = new CompletionDocumentMirror();
    mirror.sync("model", 3, "select 1");
    expect(() => mirror.change("model", 2, 4, [])).toThrowError(expect.objectContaining({ code: "MODEL_OUT_OF_SYNC" }));
    mirror.sync("model", 4, "select 2");
    expect(mirror.read("model", 4)).toBe("select 2");
  });

  it("applies insertions, deletions, replacements, and UTF-16 offsets", () => {
    const mirror = new CompletionDocumentMirror();
    mirror.sync("model", 1, "😀 select old_value");
    mirror.change("model", 1, 2, [
      { rangeOffset: "😀 select ".length, rangeLength: "old".length, text: "new" }
    ]);
    mirror.change("model", 2, 3, [{ rangeOffset: "😀".length, rangeLength: 1, text: "" }]);
    mirror.change("model", 3, 4, [{ rangeOffset: "😀".length, rangeLength: 0, text: "\n" }]);
    expect(mirror.read("model", 4)).toBe("😀\nselect new_value");
  });

  it.each([
    { changes: [{ rangeOffset: -1, rangeLength: 0, text: "" }] },
    { changes: [{ rangeOffset: 100, rangeLength: 0, text: "" }] },
    { changes: [{ rangeOffset: 1, rangeLength: -1, text: "" }] },
    { changes: [
      { rangeOffset: 1, rangeLength: 3, text: "a" },
      { rangeOffset: 2, rangeLength: 1, text: "b" }
    ] }
  ])("rejects invalid or overlapping change ranges", ({ changes }) => {
    const mirror = new CompletionDocumentMirror();
    mirror.sync("model", 1, "select 1");
    expect(() => mirror.change("model", 1, 2, changes))
      .toThrowError(expect.objectContaining({ code: "MODEL_OUT_OF_SYNC" }));
  });

  it("releases models and evicts the least recently used mirror", () => {
    const mirror = new CompletionDocumentMirror(2);
    mirror.sync("one", 1, "one");
    mirror.sync("two", 1, "two");
    mirror.read("one", 1);
    mirror.sync("three", 1, "three");
    expect(() => mirror.read("two", 1)).toThrowError(expect.objectContaining({ code: "MODEL_OUT_OF_SYNC" }));
    mirror.release("one");
    expect(() => mirror.read("one", 1)).toThrowError(expect.objectContaining({ code: "MODEL_OUT_OF_SYNC" }));
    expect(mirror.size).toBe(1);
  });
});
