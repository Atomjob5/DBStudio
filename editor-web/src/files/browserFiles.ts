type PickerWindow = Window & {
  showOpenFilePicker?: (options?: unknown) => Promise<FileSystemFileHandle[]>;
  showSaveFilePicker?: (options?: unknown) => Promise<FileSystemFileHandle>;
};

type PermissionHandle = FileSystemFileHandle & {
  queryPermission(options?: { mode: "read" | "readwrite" }): Promise<PermissionState>;
  requestPermission(options?: { mode: "read" | "readwrite" }): Promise<PermissionState>;
};

const DB_NAME = "dbstudio-browser-files";
const STORE_NAME = "recent-sql";

export interface OpenedSqlFile {
  name: string;
  content: string;
  handle?: FileSystemFileHandle;
}

export async function openSqlFile(): Promise<OpenedSqlFile | undefined> {
  const picker = window as PickerWindow;
  if (picker.showOpenFilePicker) {
    const [handle] = await picker.showOpenFilePicker({ multiple: false, types: [{
      description: "SQL 文件", accept: { "text/plain": [".sql"] }
    }] }).catch((error) => cancelled(error, [] as FileSystemFileHandle[]));
    if (!handle) return;
    const file = await handle.getFile();
    await rememberHandle(handle);
    return { name: file.name, content: await file.text(), handle };
  }
  const file = await chooseWithInput(".sql,text/plain");
  return file ? { name: file.name, content: await file.text() } : undefined;
}

export async function saveSqlFile(content: string, suggestedName: string,
                                  current?: FileSystemFileHandle, saveAs = false): Promise<{ name: string; handle?: FileSystemFileHandle } | undefined> {
  const picker = window as PickerWindow;
  let handle = saveAs ? undefined : current;
  if (handle && !await writable(handle)) handle = undefined;
  if (!handle && picker.showSaveFilePicker) {
    handle = await picker.showSaveFilePicker({ suggestedName: suggestedName.endsWith(".sql") ? suggestedName : `${suggestedName}.sql`,
      types: [{ description: "SQL 文件", accept: { "text/plain": [".sql"] } }] }).catch((error) => cancelled(error, undefined));
  }
  if (handle) {
    const writer = await handle.createWritable();
    await writer.write(content);
    await writer.close();
    await rememberHandle(handle);
    return { name: handle.name, handle };
  }
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = suggestedName.endsWith(".sql") ? suggestedName : `${suggestedName}.sql`;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
  return { name: anchor.download };
}

export async function chooseCsvFile(): Promise<File | undefined> {
  const picker = window as PickerWindow;
  if (picker.showOpenFilePicker) {
    const [handle] = await picker.showOpenFilePicker({ multiple: false, types: [{
      description: "CSV / TSV 文件", accept: { "text/csv": [".csv", ".tsv", ".txt"] }
    }] }).catch((error) => cancelled(error, [] as FileSystemFileHandle[]));
    return handle ? handle.getFile() : undefined;
  }
  return chooseWithInput(".csv,.tsv,text/csv,text/tab-separated-values");
}

export async function recentSqlFiles(): Promise<Array<{ name: string; handle: FileSystemFileHandle }>> {
  const database = await openDatabase();
  return new Promise((resolve) => {
    const request = database.transaction(STORE_NAME, "readonly").objectStore(STORE_NAME).getAll();
    request.onsuccess = () => resolve((request.result as Array<{ name: string; handle: FileSystemFileHandle; openedAt: number }>)
      .sort((a, b) => b.openedAt - a.openedAt).slice(0, 20).map(({ name, handle }) => ({ name, handle })));
    request.onerror = () => resolve([]);
  });
}

export async function openRecentSql(handle: FileSystemFileHandle): Promise<OpenedSqlFile | undefined> {
  const permitted = await readable(handle);
  if (!permitted) return;
  const file = await handle.getFile();
  await rememberHandle(handle);
  return { name: file.name, content: await file.text(), handle };
}

async function rememberHandle(handle: FileSystemFileHandle): Promise<void> {
  const database = await openDatabase();
  await new Promise<void>((resolve) => {
    const request = database.transaction(STORE_NAME, "readwrite").objectStore(STORE_NAME)
      .put({ name: handle.name, handle, openedAt: Date.now() });
    request.onsuccess = () => resolve();
    request.onerror = () => resolve();
  });
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE_NAME, { keyPath: "name" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function readable(handle: FileSystemFileHandle): Promise<boolean> {
  const permission = handle as PermissionHandle;
  if (!permission.queryPermission) return true;
  if (await permission.queryPermission({ mode: "read" }) === "granted") return true;
  return await permission.requestPermission({ mode: "read" }) === "granted";
}

async function writable(handle: FileSystemFileHandle): Promise<boolean> {
  const permission = handle as PermissionHandle;
  if (!permission.queryPermission) return true;
  if (await permission.queryPermission({ mode: "readwrite" }) === "granted") return true;
  return await permission.requestPermission({ mode: "readwrite" }) === "granted";
}

function chooseWithInput(accept: string): Promise<File | undefined> {
  return new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = accept;
    input.onchange = () => resolve(input.files?.[0]);
    input.click();
  });
}

function cancelled<T>(error: unknown, fallback: T): T {
  if (error instanceof DOMException && error.name === "AbortError") return fallback;
  throw error;
}
