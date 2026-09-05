/**
 * Immutable, array-compatible row storage.  A result update shares complete
 * chunks and copies only the tail chunk plus the small chunk index.  The
 * array proxy keeps the existing component API (`rows[index]`, `map`, spread,
 * and `length`) while avoiding materialising every row on each append.
 */
export const RESULT_ROW_CHUNK_SIZE = 1024;

type Chunk<T> = ReadonlyArray<T>;

const snapshots = new WeakMap<Array<unknown>, ResultRowsSnapshot<unknown>>();
const views = new WeakMap<object, Array<unknown>>();

function immutableValue<T>(value: T): T {
  if (!Array.isArray(value)) return value;
  return Object.freeze([...value]) as T;
}

function indexProperty(property: PropertyKey): number | undefined {
  if (typeof property !== "string" || !/^(?:0|[1-9]\d*)$/.test(property)) return undefined;
  const index = Number(property);
  return Number.isSafeInteger(index) ? index : undefined;
}

export class ResultRowsSnapshot<T> implements Iterable<T> {
  readonly version: number;
  readonly length: number;
  get size(): number { return this.length; }
  private constructor(private readonly chunks: ReadonlyArray<Chunk<T>>, length: number, version: number) {
    this.length = length;
    this.version = version;
  }

  static empty<T>(): ResultRowsSnapshot<T> {
    return new ResultRowsSnapshot<T>([], 0, 0);
  }

  static from<T>(values: Iterable<T>): ResultRowsSnapshot<T> {
    const chunks: Array<Chunk<T>> = [];
    let current: T[] = [];
    let length = 0;
    for (const value of values) {
      current.push(immutableValue(value));
      length++;
      if (current.length === RESULT_ROW_CHUNK_SIZE) {
        chunks.push(Object.freeze(current));
        current = [];
      }
    }
    if (current.length) chunks.push(Object.freeze(current));
    return new ResultRowsSnapshot(chunks, length, 0);
  }

  get(index: number): T | undefined {
    if (!Number.isInteger(index) || index < 0 || index >= this.length) return undefined;
    const chunk = this.chunks[Math.floor(index / RESULT_ROW_CHUNK_SIZE)];
    return chunk[index % RESULT_ROW_CHUNK_SIZE];
  }

  readRange(start: number, endExclusive: number): T[] {
    const from = Math.max(0, Math.min(this.length, Math.trunc(start)));
    const to = Math.max(from, Math.min(this.length, Math.trunc(endExclusive)));
    const result: T[] = new Array(to - from);
    for (let index = from; index < to; index++) result[index - from] = this.get(index) as T;
    return result;
  }

  forEach(callback: (value: T, index: number) => void): void {
    for (let index = 0; index < this.length; index++) callback(this.get(index) as T, index);
  }

  append(values: Iterable<T>): ResultRowsSnapshot<T> {
    const additions = Array.from(values, immutableValue);
    if (!additions.length) return this;
    const chunks = this.chunks.slice() as Array<Chunk<T>>;
    let cursor = 0;
    if (chunks.length && chunks[chunks.length - 1].length < RESULT_ROW_CHUNK_SIZE) {
      const tail = [...chunks[chunks.length - 1]];
      const available = RESULT_ROW_CHUNK_SIZE - tail.length;
      tail.push(...additions.slice(0, available));
      chunks[chunks.length - 1] = Object.freeze(tail);
      cursor = Math.min(available, additions.length);
    }
    while (cursor < additions.length) {
      chunks.push(Object.freeze(additions.slice(cursor, cursor + RESULT_ROW_CHUNK_SIZE)));
      cursor += RESULT_ROW_CHUNK_SIZE;
    }
    return new ResultRowsSnapshot(chunks, this.length + additions.length, this.version + 1);
  }

  update(updates: ReadonlyMap<number, T>): ResultRowsSnapshot<T> {
    if (!updates.size) return this;
    const chunks = this.chunks.slice() as Array<Chunk<T>>;
    const changed = new Map<number, T[]>();
    for (const [index, value] of updates) {
      if (!Number.isInteger(index) || index < 0 || index >= this.length) continue;
      const chunkIndex = Math.floor(index / RESULT_ROW_CHUNK_SIZE);
      let chunk = changed.get(chunkIndex);
      if (!chunk) {
        chunk = [...chunks[chunkIndex]];
        changed.set(chunkIndex, chunk);
      }
      chunk[index % RESULT_ROW_CHUNK_SIZE] = immutableValue(value);
    }
    if (!changed.size) return this;
    for (const [index, chunk] of changed) chunks[index] = Object.freeze(chunk);
    return new ResultRowsSnapshot(chunks, this.length, this.version + 1);
  }

  remove(index: number): ResultRowsSnapshot<T> {
    if (!Number.isInteger(index) || index < 0 || index >= this.length) return this;
    const values = this.toArray();
    values.splice(index, 1);
    const rebuilt = ResultRowsSnapshot.from(values);
    return new ResultRowsSnapshot(rebuilt.chunks, rebuilt.length, this.version + 1);
  }

  toArray(): T[] {
    const values: T[] = new Array(this.length);
    let offset = 0;
    for (const chunk of this.chunks) {
      for (const value of chunk) values[offset++] = value;
    }
    return values;
  }

  [Symbol.iterator](): Iterator<T> {
    let index = 0;
    return {
      next: (): IteratorResult<T> => index < this.length
        ? { value: this.get(index++) as T, done: false }
        : { value: undefined as unknown as T, done: true }
    };
  }
}

export function snapshotFor<T>(values: Array<T>): ResultRowsSnapshot<T> {
  return (snapshots.get(values as Array<unknown>) as ResultRowsSnapshot<T> | undefined)
    ?? ResultRowsSnapshot.from(values);
}

export function viewFor<T>(snapshot: ResultRowsSnapshot<T>): Array<T> {
  const existing = views.get(snapshot as unknown as object) as Array<T> | undefined;
  if (existing) return existing;
  const target: T[] = [];
  const view = new Proxy(target, {
    get: (array, property, receiver) => {
      if (property === "length") return snapshot.length;
      if (property === "toJSON") return () => snapshot.toArray();
      const index = indexProperty(property);
      if (index !== undefined) return snapshot.get(index);
      return Reflect.get(array, property, receiver);
    },
    has: (array, property) => {
      const index = indexProperty(property);
      return index === undefined ? Reflect.has(array, property) : index < snapshot.length;
    },
    ownKeys: (array) => {
      // Object inspection is uncommon for result rows. Keeping virtual keys
      // bounded avoids turning a large result into another full index array.
      if (snapshot.length > 4096) return ["length"];
      return ["length", ...Array.from({ length: snapshot.length }, (_, index) => String(index))];
    },
    getOwnPropertyDescriptor: (array, property) => {
      const index = indexProperty(property);
      if (index === undefined) return Reflect.getOwnPropertyDescriptor(array, property);
      if (index >= snapshot.length) return undefined;
      return { value: snapshot.get(index), enumerable: true, configurable: true, writable: false };
    },
    set: () => false,
    defineProperty: () => false,
    deleteProperty: () => false
  });
  snapshots.set(view as Array<unknown>, snapshot as ResultRowsSnapshot<unknown>);
  views.set(snapshot as unknown as object, view as Array<unknown>);
  return view;
}

export function rowsView<T>(values: Iterable<T>): Array<T> {
  return viewFor(ResultRowsSnapshot.from(values));
}
