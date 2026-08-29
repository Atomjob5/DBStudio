/*
 * Monaco's package root imports every bundled language and language-service
 * worker. DBStudio registers its SQL dialect itself, so keep the complete
 * editor feature set while loading only the SQL contribution used by the DDL
 * viewer.
 */
import "monaco-editor/esm/vs/editor/editor.all.js";
import "monaco-editor/esm/vs/base/browser/ui/codicons/codicon/codicon.css";
import "monaco-editor/esm/vs/base/browser/ui/codicons/codicon/codicon-modifiers.css";
import "monaco-editor/esm/vs/basic-languages/sql/sql.contribution.js";

export * from "monaco-editor/esm/vs/editor/editor.api.js";
