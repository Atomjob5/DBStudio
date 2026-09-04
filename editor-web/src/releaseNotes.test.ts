import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  compareReleaseVersions,
  CURRENT_RELEASE,
  getPendingReleaseNotes,
  getLastSeenReleaseVersion,
  hasSeenRelease,
  markReleaseNotesBatchSeen,
  markReleaseSeen,
  RELEASE_NOTES_LAST_SEEN_VERSION_KEY,
  releaseNotesStorageKey,
  type ReleaseNotesRelease
} from "./releaseNotes";

const originalStorageDescriptor = Object.getOwnPropertyDescriptor(window, "localStorage");

function restoreStorage(): void {
  if (originalStorageDescriptor) Object.defineProperty(window, "localStorage", originalStorageDescriptor);
  else delete (window as unknown as { localStorage?: Storage }).localStorage;
}

function fixtureRelease(version: string, itemCount = 1): ReleaseNotesRelease {
  return {
    ...CURRENT_RELEASE,
    version,
    publishedAt: `2026 年 ${version.split(".")[1]} 月`,
    items: CURRENT_RELEASE.items.slice(0, itemCount).map((item, index) => ({
      ...item,
      id: `${version}-item-${index}`
    }))
  };
}

function fixtureReleases(): ReleaseNotesRelease[] {
  return [fixtureRelease("1.3.0"), fixtureRelease("1.1.0"), fixtureRelease("1.2.0")];
}

describe("release notes storage and selection", () => {
  beforeEach(() => {
    Object.defineProperty(window, "localStorage", { configurable: true, value: window.sessionStorage });
    window.localStorage.clear();
  });

  afterEach(() => restoreStorage());

  it("isolates the seen marker by release version", () => {
    expect(releaseNotesStorageKey("1.1.0")).toBe("dbstudio.release-notes.seen.v1.1.0");
    expect(hasSeenRelease("1.1.0")).toBe(false);

    markReleaseSeen("1.1.0");
    expect(hasSeenRelease("1.1.0")).toBe(true);
    expect(hasSeenRelease("1.2.0")).toBe(false);
  });

  it("compares standard release versions numerically", () => {
    expect(compareReleaseVersions("1.10.0", "1.3.0")).toBeGreaterThan(0);
    expect(compareReleaseVersions("1.3.1", "1.3.0")).toBeGreaterThan(0);
    expect(compareReleaseVersions("2.0.0", "10.0.0")).toBeLessThan(0);
    expect(compareReleaseVersions("1.2", "1.2.0")).toBe(0);
  });

  it("shows only the latest release for a fresh installation", () => {
    const releases = fixtureReleases();
    expect(getPendingReleaseNotes(releases).map((release) => release.version)).toEqual(["1.3.0"]);
  });

  it("merges every release after the highest legacy marker", () => {
    const releases = fixtureReleases();
    markReleaseSeen("1.1.0");

    expect(getPendingReleaseNotes(releases).map((release) => release.version)).toEqual(["1.2.0", "1.3.0"]);
  });

  it("uses the cursor for partial reads and returns nothing after the whole batch is marked", () => {
    const releases = fixtureReleases();
    window.localStorage.setItem(RELEASE_NOTES_LAST_SEEN_VERSION_KEY, "1.2.0");
    markReleaseSeen("1.2.0");

    expect(getLastSeenReleaseVersion()).toBe("1.2.0");
    expect(getPendingReleaseNotes(releases).map((release) => release.version)).toEqual(["1.3.0"]);

    markReleaseNotesBatchSeen([releases.find((release) => release.version === "1.3.0")!]);
    expect(window.localStorage.getItem(releaseNotesStorageKey("1.3.0"))).toBe("1");
    expect(window.localStorage.getItem(RELEASE_NOTES_LAST_SEEN_VERSION_KEY)).toBe("1.3.0");
    expect(getPendingReleaseNotes(releases)).toEqual([]);
  });

  it("marks an unsorted merged batch in ascending order and advances the cursor", () => {
    const releases = fixtureReleases();
    markReleaseNotesBatchSeen([releases[0]!, releases[2]!]);

    expect(hasSeenRelease("1.2.0")).toBe(true);
    expect(hasSeenRelease("1.3.0")).toBe(true);
    expect(window.localStorage.getItem(RELEASE_NOTES_LAST_SEEN_VERSION_KEY)).toBe("1.3.0");
  });

  it("does not let unavailable storage interrupt selection or dismissal", () => {
    const releases = fixtureReleases();
    Object.defineProperty(window, "localStorage", {
      configurable: true,
      get() { throw new Error("storage disabled"); }
    });

    expect(getPendingReleaseNotes(releases).map((release) => release.version)).toEqual(["1.3.0"]);
    expect(() => markReleaseSeen("1.1.0")).not.toThrow();
    expect(() => markReleaseNotesBatchSeen(releases)).not.toThrow();
  });
});
