# SQL 实时诊断与安全快速修复

SQL 编辑器默认启用实时诊断，支持 `mysql`、`oracle` 与 `oceanbase-oracle`。诊断只提供建议，不会阻止执行；未带顶层 `WHERE` 的 `UPDATE`/`DELETE` 仍由执行前二次确认提供最终保护。

## 分析链路

内容变化后 Monaco 立即清除旧 Marker，并在停止输入 400 ms 后并行发起两类分析：

- 补全 Worker 复用当前文档镜像、词法分析、CTE/数据源作用域和浏览器补全缓存，检查未闭合结构、未知对象、未知限定字段、歧义字段及缺少 JOIN 条件。
- `POST /api/v1/workspaces/{workspaceId}/sql/diagnostics` 读取编辑标签的逻辑连接绑定，选择对应 Provider，并使用 Druid 1.2.28 解析每条语句及检查无 WHERE 风险。该接口不会创建、唤醒或探测 JDBC 会话。

两端偏移均为 UTF-16 半开区间，与 Java `String`、JavaScript 字符串和 Monaco Model 保持一致。响应回显 `modelVersion` 和 `providerId`；只有模型版本、Provider、补全缓存键及缓存版本均未变化时才应用结果。服务端不可用时仍保留本地诊断。

## 设置与生效时机

| 设置键 | 默认值 | 说明 |
| --- | --- | --- |
| `editor.sqlDiagnosticsEnabled` | `true` | 控制全部实时诊断、Marker 与 Quick Fix；关闭或解绑数据库时立即清除。 |
| `editor.dangerousStatementWarningEnabled` | `true` | 同时控制无 WHERE 编辑器警告和执行前二次确认；不影响语法、结构与元数据诊断。 |

切换标签、Provider、数据库或补全缓存刷新后会重新诊断。分析只读取现有补全缓存；缓存缺失、加载中、Schema 范围不完整、字段列表未加载或来源不唯一时，会抑制相关元数据警告，避免把过期缓存当成数据库事实。

## Quick Fix 边界

灯泡菜单和 Monaco 默认的 `Mod+.` 可执行以下安全编辑，所有修改进入编辑器同一撤销栈并触发现有 dirty/草稿保存流程：

- 补齐可唯一确定的引号、反引号、块注释结束符或右括号。
- 将歧义字段限定为用户选择的 `alias.column`。
- 使用同一命名空间或数据源内、按补全匹配规则排序的最多三个对象/字段候选替换未知名称。

无 WHERE、缺少 JOIN 条件和通用解析异常不会自动生成 SQL，以免猜测业务条件或改变语义。

## 资源与日志

诊断文本会发送到本机回环地址上的 DBStudio 服务用于 Druid 解析，不会发送到数据库。服务端日志仅记录 Provider、字符数、模型版本和诊断数量，不记录 SQL 内容。大脚本与较大的补全缓存会增加浏览器 Worker 的 CPU/内存消耗，因此采用固定防抖、文档镜像和版本淘汰控制重复工作。
