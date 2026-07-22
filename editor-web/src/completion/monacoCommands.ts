export interface CompletionCommandEditor {
  addCommand(keybinding: number, handler: () => void): string | null;
  trigger(source: string, handlerId: string, payload: unknown): Promise<void> | void;
}

export function registerCompletionShortcut(editor: CompletionCommandEditor, f6Keybinding: number): void {
  editor.addCommand(f6Keybinding, () => {
    void editor.trigger("keyboard", "editor.action.triggerSuggest", {});
  });
}
