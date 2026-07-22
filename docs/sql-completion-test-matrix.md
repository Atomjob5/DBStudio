# SQL 补全测试矩阵

本文档描述普通 SQL 补全的验收范围。SQL 中的 `|` 表示光标位置；执行测试时会移除该字符，并按 UTF-16 计算 Monaco/Worker 使用的 `cursorOffset`。

状态说明：

- `covered`：已有自动化回归测试。
- `negative`：验证安全降级，不得返回无关元数据。
- `todo`：当前未承诺的高级语义，保留为后续能力。

## 1. 命名空间、表和视图

| 状态 | SQL/条件 | 预期 |
|---|---|---|
| covered | `select * from |` | 默认 Schema 对象使用短名称，非默认 Schema 使用 `schema.table` |
| covered | `select * from sales.|` | 只显示 `sales`下的表和视图 |
| covered | `select * from archive.ord|` | 按前缀过滤并只插入剩余表名 |
| covered | `select * from empty.|` | 空 Schema 返回空对象候选 |
| negative | `select * from missing.|` | 不扫描其他 Schema |
| covered | `select * from orders o join ord|` | JOIN 表上下文返回对象 |
| covered | `select * from orders o, ord|` | 逗号来源返回对象 |
| covered | `update ord|` | 返回可更新对象 |
| covered | `insert into ord|` | 返回目标表 |
| covered | `delete from ord|` | 返回目标表 |
| covered | `merge into ord|` | 返回 MERGE 目标表 |
| covered | `merge into orders o using ord|` | 返回 MERGE 来源表 |
| covered | 保留字、中文对象名 | 保留数据库原始名称，不自动添加引用符 |

## 2. 字段上下文

以下普通字段位置均需支持单表、别名、表名和 `schema.table`限定形式。

| 状态 | SQL | 预期 |
|---|---|---|
| covered | `select o.| from orders o` | 返回 `orders`字段，插入字段名 |
| covered | `select | from orders` | 单来源无限定符时插入字段名 |
| covered | `where o.|` | 返回别名来源字段 |
| covered | `join customers c on o.| = c.id` | ON 子句返回对应来源字段 |
| covered | `group by o.|` | GROUP BY 返回来源字段 |
| covered | `having o.|` | HAVING 返回来源字段 |
| covered | `order by o.|` | ORDER BY 返回来源字段 |
| covered | `update orders o set o.|` | SET 返回目标表字段 |
| covered | `returning o.|` | RETURNING 返回目标表字段 |
| covered | `insert into orders (|` | 返回 INSERT 目标字段 |
| covered | `delete from orders o where o.|` | 返回 DELETE 来源字段 |
| covered | `merge ... on o.|` | 返回 MERGE 目标字段 |
| covered | `sales.orders.|` | 返回物理对象字段 |
| covered | `o.cus|` | 按字段前缀过滤，替换范围只覆盖 `cus` |
| covered | 多来源无限定符 | 显示并插入 `alias.column` |
| covered | 多来源同名 `id` | 分别保留 `o.id`与`c.id` |
| negative | `missing.|` | 未知别名不扫描所有字段 |
| negative | `from missing where |` | 未知表不扫描所有字段 |

字段候选统一要求：

- `displayLabel`和`insertText`与当前上下文一致。
- 已输入限定符后只显示并插入字段名。
- `documentationPath`保留 `schema.table.column`完整路径。
- 注释和字段类型来自元数据快照。
- MySQL、Oracle、OceanBase Oracle 均不自动插入双引号或反引号。

## 3. 光标中间补全

| 状态 | SQL | 预期 |
|---|---|---|
| covered | `select a.|* from CBSAC.CUSTOMERS a where a.ID <= 20` | 使用光标后方 FROM 建立 `a → CBSAC.CUSTOMERS` |
| covered | `select a.CUS| from CBSAC.CUSTOMERS a` | 使用后置来源并按 `CUS`过滤 |
| covered | `select | from orders o where o.id > 10` | 光标后表达式不影响 SELECT 上下文 |
| covered | 多条 SQL 中间语句 | 只分析光标所在语句 |
| covered | 光标后发生编辑 | Worker 使用对应模型版本的完整镜像 |

## 4. 查询作用域

| 状态 | SQL/结构 | 预期 |
|---|---|---|
| covered | 内外层使用同名别名 | 最近查询块别名覆盖外层 |
| covered | 相关子查询引用外层别名 | 可访问可见外层来源 |
| negative | 兄弟子查询定义同名别名 | 不得泄漏到当前子查询 |
| covered | CTE 显式列清单 | 使用显式列名 |
| covered | CTE 投影 `id as order_id` | 推导 `order_id` |
| covered | 派生表显式/隐式投影别名 | 推导派生表字段 |
| covered | 派生表 `o.*` | 展开已解析物理来源字段 |
| covered | `UNION`各分支 | 只使用光标所在分支来源 |
| covered | `MINUS`各分支 | 只使用光标所在分支来源 |
| covered | `EXCEPT`各分支 | 只使用光标所在分支来源 |
| covered | `INTERSECT`各分支 | 只使用光标所在分支来源 |
| negative | 未闭合子查询 | 只使用能够安全确认的来源 |
| todo | `ORDER BY projection_alias|` | 补全当前 SELECT 投影别名 |
| todo | `JOIN ... USING (|)` | 补全左右来源的公共字段 |
| todo | 递归 CTE 无显式列清单 | 推导递归自引用字段 |
| todo | LATERAL、表函数 | 建立特殊来源的输出字段 |

## 5. DML 目标和来源

| 状态 | SQL | 预期 |
|---|---|---|
| covered | `insert into orders (|` | 目标字段 |
| covered | `insert into orders (...) select c.| from customers c` | SELECT 部分使用来源字段 |
| covered | `update orders o set o.|` | 目标表字段 |
| covered | `delete from orders o where o.|` | DELETE 来源字段 |
| covered | `merge into orders o using customers c on o.|` | 目标字段 |
| covered | `merge into orders o using customers c on c.|` | 来源字段 |
| negative | 无法确认目标表 | 只返回安全关键字 |
| todo | MySQL 多表 UPDATE/DELETE 的目标语义 | 区分可更新目标和只读来源 |

## 6. 词法、语句边界和方言

| 状态 | 条件 | 预期 |
|---|---|---|
| covered | SQL 关键字和对象混合大小写 | 匹配不区分大小写，展示保留原始名称 |
| covered | 单引号及连续单引号 | 内容不参与语义分析 |
| covered | MySQL 反斜杠字符串转义 | 转义后的引号不结束字符串 |
| covered | `--`行注释 | 内容不参与语义分析 |
| covered | `/* ... */`块注释 | 内容不参与语义分析 |
| covered | MySQL `#`注释 | 内容不参与语义分析 |
| covered | Oracle `#name` | 按标识符处理，不按注释处理 |
| covered | MySQL 反引号标识符 | 保留引用内容 |
| covered | Oracle 双引号标识符 | 保留引用内容 |
| covered | 字符串、注释、引用标识符中的 `;` | 不作为语句边界 |
| covered | 括号内的 `;` | 不作为顶层语句边界 |
| covered | 未闭合字符串、注释、括号 | 不抛异常 |
| covered | emoji 位于光标之前 | UTF-16 offset 与 Monaco 一致 |
| negative | 字符串/注释中的 `from`、`a.` | 不产生虚假来源和字段 |

## 7. 候选排序与展示

| 状态 | 条件 | 预期 |
|---|---|---|
| covered | 完全匹配与前缀匹配同时存在 | 完全匹配优先 |
| covered | 默认和非默认 Schema 同名对象 | 默认 Schema 优先 |
| covered | 字段、表、视图、Schema、关键字 | 按类型优先级稳定排序 |
| covered | 候选限制小于 10 | 按 10 处理 |
| covered | 候选限制大于 1000 | 按 1000 处理 |
| covered | 候选被截断 | `incomplete: true` |
| covered | 80 个 Unicode 字符注释 | 不截断 |
| covered | 超过 80 个 Unicode 字符 | 追加省略号且不切断 emoji |
| covered | documentation | 完整路径、完整注释、字段类型齐全 |
| covered | 无元数据索引 | 只返回安全关键字 |
| covered | 无数据库绑定 | 返回空候选，不访问元数据 |

## 8. Worker 文档镜像和 Monaco 生命周期

| 状态 | 条件 | 预期 |
|---|---|---|
| covered | 模型首次绑定 | 一次 `model.sync`全文同步 |
| covered | 插入、删除、替换 | 发送并应用 `model.change` |
| covered | 多光标批量修改 | 使用原始 offset 倒序应用 |
| covered | 连续版本 1→2→3 | 增量消息保持顺序 |
| negative | `fromVersion`不匹配 | 返回 `MODEL_OUT_OF_SYNC` |
| negative | 修改范围越界、重叠 | 返回 `MODEL_OUT_OF_SYNC` |
| covered | 增量消息失步 | 全文同步后执行一次补全 |
| covered | 补全请求失步 | 全文同步并只重试一次 |
| covered | 补全过程模型版本变化 | 丢弃旧请求结果 |
| covered | Worker 超过 20 个模型 | 按 LRU 释放最久未访问模型 |
| covered | 编辑器卸载 | 发送 `model.release` |
| covered | 自动触发与 F6 | 使用同一 Completion Provider |
| covered | `complete`协议 | 不携带 SQL 全文 |

## 9. 大规模元数据

使用 6000 张表、11 万字段的生成快照验证：

- 补全索引保持 Schema → 对象 → 字段层级结构。
- 有限定符时只返回指定来源的字段。
- 单表上下文不混入其他对象字段。
- Monaco 单次接收候选不超过配置限制。
- 超出限制时返回 `incomplete: true`。
- 不使用不稳定的 CI 毫秒阈值作为正确性断言。

## 10. 明确不在当前范围

以下场景只要求安全降级，不要求语义补全：

- PL/SQL 局部变量、Record、Cursor、`%ROWTYPE`和 Package 成员。
- 动态 SQL 字符串中的 SQL 语义。
- Oracle 数据库链接、同义词成员展开。
- 存储过程参数、用户自定义函数返回结构。
- 临时表在当前未提交会话中的实时结构变化。

安全降级的统一标准是：不抛异常、不扫描所有字段、不返回与可见作用域无关的元数据；有明确关键字上下文时允许返回安全关键字。
