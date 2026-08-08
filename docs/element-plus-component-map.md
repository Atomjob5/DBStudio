# Element Plus component map

DBStudio uses Element Plus before custom UI code. IDE styling is implemented with component props, slots and CSS variables; no Element Plus source is copied or modified.

| Capability | Implementation |
| --- | --- |
| Application shell | `el-container`, `el-header`, `el-main`, `el-footer` |
| Resizable workbench | `el-splitter`, `el-splitter-panel` |
| Toolbar and menus | `el-button`, `el-button-group`, `el-dropdown`, `el-tooltip`, `el-icon` |
| Connection management | `el-dialog`, `el-form`, `el-input`, `el-input-number`, `el-select`, `el-switch`, `el-alert` |
| Lazy metadata explorer | `el-tree` with lazy loading and an `el-dropdown` context menu |
| SQL tabs | `el-tabs`, `el-tab-pane` |
| Large query results | `ResultVirtualGrid` with native scrolling, cell renderers and a shared browser tooltip |
| History | `el-drawer`, `el-table`, `el-pagination`, `el-tag` |
| CSV import | `el-dialog`, `el-steps`, `el-upload` interaction styling, `el-table`, `el-select`, `el-progress` |
| Settings | `el-drawer`, `el-form`, `el-input-number`, `el-radio-group`, `el-alert` |
| Feedback | `ElMessage`, `ElMessageBox`, `ElNotification`, `el-result`, `el-empty`, `v-loading` |

The Apple-inspired appearance is implemented with Element Plus theme variables, component props and slots. The toolbar, connection form, settings and result actions remain compositions of official components; they are not replacements for Element Plus primitives. See `apple-ui-style.md` for the visual-layer and accessibility contract.

## Approved custom code

### MonacoEditor

- Element Plus evaluated: `el-input` and `el-input` textarea.
- Reason: Element Plus does not provide a code editor, text model switching, SQL tokenization, completion providers or editor commands.
- Compatibility scope: one Monaco instance switches models for all tabs. Chrome is the primary target; current Firefox, Edge and Safari use the documented file-system fallback.
- Tests: model/session state is covered by editor and query store tests; Playwright covers rendering and keyboard execution.

### REST/WebSocket client and background task tracker

- Element Plus evaluated: none; this is transport rather than UI.
- Reason: the code implements authenticated REST requests, ordered WebSocket events, timeouts and browser downloads.
- Compatibility scope: responses, errors, unsolicited events, reconnects and event-before-response task completion.
- Tests: `rpc.test.ts` and Spring Boot security/API tests cover both sides of the protocol.

### Status bar layout

- Element Plus evaluated: `el-footer` is used as the owning component.
- Reason: the inner text alignment is application-specific status content, so only CSS flex layout is needed; it is not a reusable replacement for an Element Plus component.
- Tests: exercised by the application smoke flow and theme verification.

`ResultVirtualGrid` is the approved result-grid implementation. It owns row/column virtualization and native scrolling while the surrounding result panel owns sorting, filtering, selection and mutation actions.
