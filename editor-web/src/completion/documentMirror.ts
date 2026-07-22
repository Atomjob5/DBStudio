export interface CompletionTextChange {
  rangeOffset: number;
  rangeLength: number;
  text: string;
}

interface MirroredDocument {
  version: number;
  text: string;
}

export class CompletionDocumentMirror {
  private readonly documents = new Map<string, MirroredDocument>();

  constructor(private readonly maximumDocuments = 20) { }

  sync(modelKey: string, version: number, text: string): void {
    this.remember(modelKey, { version, text });
  }

  change(modelKey: string, fromVersion: number, toVersion: number, changes: CompletionTextChange[]): void {
    const current = this.documents.get(modelKey);
    if (!current || current.version !== fromVersion) throw modelOutOfSync(modelKey);
    let text = current.text;
    const ordered = [...changes].sort((left, right) => right.rangeOffset - left.rangeOffset);
    let previousStart = text.length;
    for (const change of ordered) {
      if (!Number.isInteger(change.rangeOffset) || !Number.isInteger(change.rangeLength)
        || change.rangeOffset < 0 || change.rangeLength < 0
        || change.rangeOffset + change.rangeLength > text.length
        || change.rangeOffset + change.rangeLength > previousStart) throw modelOutOfSync(modelKey);
      text = `${text.slice(0, change.rangeOffset)}${change.text}${text.slice(change.rangeOffset + change.rangeLength)}`;
      previousStart = change.rangeOffset;
    }
    this.remember(modelKey, { version: toVersion, text });
  }

  read(modelKey: string, version: number): string {
    const current = this.documents.get(modelKey);
    if (!current || current.version !== version) throw modelOutOfSync(modelKey);
    this.remember(modelKey, current);
    return current.text;
  }

  release(modelKey: string): void {
    this.documents.delete(modelKey);
  }

  get size(): number {
    return this.documents.size;
  }

  private remember(modelKey: string, document: MirroredDocument): void {
    this.documents.delete(modelKey);
    this.documents.set(modelKey, document);
    while (this.documents.size > this.maximumDocuments) {
      this.documents.delete(this.documents.keys().next().value as string);
    }
  }
}

function modelOutOfSync(modelKey: string): Error {
  return Object.assign(new Error(`编辑器模型 ${modelKey} 需要重新同步`), { code: "MODEL_OUT_OF_SYNC" });
}
