import { describe, expect, it } from "vitest";
import { RESULT_ROW_CHUNK_SIZE, ResultRowsSnapshot, viewFor } from "./resultRows";

describe("ResultRowsSnapshot", () => {
  it("shares immutable chunks across append snapshots while preserving array access", () => {
    const initial = viewFor(ResultRowsSnapshot.from(Array.from({ length: RESULT_ROW_CHUNK_SIZE + 2 }, (_, i) => i)));
    const next = viewFor(ResultRowsSnapshot.from(initial).append([RESULT_ROW_CHUNK_SIZE + 2, RESULT_ROW_CHUNK_SIZE + 3]));
    expect(initial.length).toBe(RESULT_ROW_CHUNK_SIZE + 2);
    expect(next.length).toBe(RESULT_ROW_CHUNK_SIZE + 4);
    expect(next[0]).toBe(0);
    expect(next[next.length - 1]).toBe(RESULT_ROW_CHUNK_SIZE + 3);
    expect([...initial].at(-1)).toBe(RESULT_ROW_CHUNK_SIZE + 1);
    expect(next.map((value) => value).slice(-2)).toEqual([RESULT_ROW_CHUNK_SIZE + 2, RESULT_ROW_CHUNK_SIZE + 3]);
  });

  it("updates only the requested values and leaves the old view unchanged", () => {
    const oldRows = viewFor(ResultRowsSnapshot.from([["a"], ["b"]]));
    const updated = viewFor(ResultRowsSnapshot.from(oldRows).update(new Map([[1, ["changed"]]])));
    expect(oldRows[1]).toEqual(["b"]);
    expect(updated[1]).toEqual(["changed"]);
    expect(JSON.stringify(updated)).toBe('[["a"],["changed"]]');
  });
});
