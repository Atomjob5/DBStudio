# 执行计划验证记录

验证日期：2026-09-05。计划仅使用 EXPLAIN，不使用 EXPLAIN ANALYZE，也不实际执行目标 DML。

## 已验证范围

- 前端：菜单末尾入口、追加并选中计划标签、普通执行替换结果、数据与计划切换、树形/原始文本切换、复制、缺失指标与真实零值、展开和节点选择状态保留。浏览器模拟通信端到端测试通过并检查截图。
- 结果生命周期：计划类型在取消后保留、关闭结果后的迟到事件被忽略、不同编辑器之间的结果隔离。数据操作工具栏不显示于计划标签。
- MySQL 8.0.46 / 8.4.9：实际数据库验证单表、JOIN、子查询、聚合、CTE，以及 INSERT、UPDATE、DELETE 的 JSON 计划获取；解释后业务数据不变，已有未提交 INSERT 仍可回滚。
- Provider 样本测试：MySQL JSON 层级和缺失指标；OceanBase 文本层级、EXTENDED 条件与无法解析时保留原文；允许语句类型与多语句/不支持类型拒绝。
- Oracle 模拟会话测试：自动提交和已有事务两种状态下的成功、权限错误、读取失败、取消；局部回滚/保存点清理，不提交用户事务。任务开始前取消不触碰事务。
- QueryRunner：专用计划路径不会执行目标 DELETE，保留未提交数据和事务脏状态；执行开始前取消有效。
- 服务端真实 MySQL 接口/WebSocket 回归和会话生命周期测试通过：计划事件携带独立类型与 executionId、保留旧结果、解释 UPDATE 不修改业务数据，数据分页接口返回 PLAN_NOT_DATA。首次容器初始化超时，增加测试启动等待时间后重跑通过。
- 最终前端类型检查和生产构建通过。主界面额外回归首次 68 项通过、10 项准备阶段超过默认 10 秒；将 hookTimeout 提高到 60 秒后，这 10 项全部通过。相关结果组件、快捷键、通信与状态测试通过。

## 真实 Oracle / OceanBase 尚未完成

本次没有配置 `DBSTUDIO_TEST_ORACLE_PASSWORD` 和 `DBSTUDIO_TEST_OCEANBASE_ORACLE_PASSWORD`，对应端点测试跳过。模拟测试不是数据库兼容性结论。

后续需要在实际支持版本和专用测试账号下完成：

- 单表、JOIN、子查询、聚合、CTE 和各数据库支持的 DML；权限不足、语法错误、计划格式差异。
- 已有未提交修改时，成功、失败、取消后的业务数据、自动提交状态和事务归属。
- Oracle 唯一 STATEMENT_ID 的计划记录清理、PLAN_TABLE 与 DBMS_XPLAN 权限差异；不自动创建对象。
- OceanBase EXPLAIN EXTENDED 的实际版本格式和节点缩进兼容性。

## 可重复测试

```sh
./mvnw -pl server-app -am test -Dexec.skip=true -Dtest='*ExecutionPlanAdapterTest,QueryRunnerTest,WorkspaceSessionLifecycleTest,QueryWebSocketIntegrationTest,MySqlIntegrationTest,*EndpointRegressionTest' -Dsurefire.failIfNoSpecifiedTests=false
```

在 `editor-web` 目录运行：

```sh
npm run test -- src/components/ExecutionPlanView.test.ts src/components/ResultPanel.test.ts src/stores/stores.test.ts src/shortcuts.test.ts src/bridge/rpc.test.ts src/components/AppStatusBar.test.ts --testTimeout=60000 --maxWorkers=1
npm run build
npx playwright test e2e/mock-bridge.spec.ts -g 'shows execution plans as independent result tabs'
```

数据库集成测试依赖 Docker；启动缓慢或无可用端点时，应明确记录未完成，不使用历史报告代替本次结果。
