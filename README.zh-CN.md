<p align="center">
  <img src="editor-web/public/assets/branding/dbstudio-cat-only.png" width="96" alt="DBStudio 标志">
</p>

<h1 align="center">DBStudio</h1>

<p align="center">
  一款基于 Web 技术构建的本地数据库管理工具。
</p>

<p align="center">
  <a href="README.md">English</a> · 简体中文
</p>

## 项目简介

DBStudio 将浏览器端工作台与本地 Java 服务相结合，提供现代化的 SQL 编辑和数据库管理体验。应用完全运行在本机，通过系统浏览器打开，并将前端与后端打包为单个可执行 JAR。

项目目前支持 MySQL 8、Oracle 19c/21c，以及 Oracle 兼容模式的 OceanBase。数据库差异通过 SPI 隔离，因此可以在不修改编辑器工作台的情况下扩展新的数据库提供器。

> DBStudio 正在积极开发中，首个稳定版本发布前，接口、存储格式和功能可能发生变化。

## 开发方式

DBStudio 采用 **Vibe Coding** 方式开发，主要编程模型为 **GPT-5.6 Sol**。项目方向、需求定义、代码审查、测试和发布决策仍由开发者主导，AI 主要协助功能实现、重构、问题排查、文档编写和测试生成。

## 功能亮点

- 基于 Monaco 的 SQL 编辑器，支持方言感知的智能补全、实时诊断、格式化、代码片段和快捷键
- 数据库对象浏览，按数据库能力展示表、视图、索引、约束、触发器、存储过程、函数、包、序列、同义词和类型
- 流式 SQL 执行，支持取消任务、执行历史、耗时反馈和事务控制
- 虚拟化结果表格，支持分页、筛选、复制、数据对比、大字段查看和可编辑结果集
- CSV 数据导入、查询结果导出，以及基于 Excel 的数据库连接导入与导出
- 多工作区、编辑器恢复、连接分组、外观设置和明暗主题
- 通过 macOS 钥匙串或 Windows 凭据管理器保护本地数据库密码
- 可扩展的数据库提供器架构，隔离连接、元数据、SQL 方言和能力差异

## 支持的数据库

| 数据库 | 驱动/提供器 | 说明 |
| --- | --- | --- |
| MySQL 8.x | MySQL Connector/J | 支持目录元数据、存储程序、执行计划和结果编辑 |
| Oracle 19c / 21c | Oracle JDBC | 支持 Service Name、SID、Schema 元数据和 Oracle 对象类型 |
| OceanBase | OceanBase Client | 支持 Oracle 兼容模式 |

具体功能会因数据库能力和 JDBC 驱动能力而有所不同。

## 一条 SQL 的执行过程

每个 SQL 编辑标签都拥有独立的 JDBC 会话和单线程执行队列。不同标签可以并行执行，但同一个标签不会同时使用同一条数据库连接执行两个操作。

```mermaid
flowchart LR
    A["Monaco 编辑器<br/>选区、当前语句或完整脚本"]
    B["Vue + RpcClient<br/>发送 REST 执行请求"]
    C["REST 控制器<br/>校验并按数据库方言拆分 SQL"]
    D["编辑器会话<br/>分配 executionId 并串行调度"]
    E["QueryRunner + JDBC<br/>执行语句并读取结果"]
    F["工作区 WebSocket<br/>有序、有界的事件队列"]
    G["Pinia 查询状态<br/>增量更新结果"]
    H["ResultVirtualGrid<br/>虚拟化渲染"]

    A --> B --> C --> D --> E
    E -->|"元数据与分批结果行"| F --> G --> H
```

完整执行流程如下：

1. 前端将选中的 SQL、光标所在语句或完整脚本发送到 `POST /api/v1/workspaces/{workspaceId}/editors/{editorId}/executions`。
2. 服务端校验工作区和编辑标签，使用当前数据库方言选择或拆分语句，分配 `executionId` 后立即响应，不等待查询完成。
3. 当前标签的 `QueryRunner` 通过 JDBC 按顺序执行语句，处理多结果集、影响行数、展示行数上限、任务取消和事务状态。
4. 后端先发送结果元数据，再按配置分批推送结果行。WebSocket 事件顺序为 `query.started → query.resultMeta → query.rows → query.resultComplete → query.executionComplete`。
5. 工作区使用有界事件队列；浏览器消费速度不足时，队列会将压力传递回 JDBC 读取线程，避免结果数据无限堆积。
6. Pinia 查询状态在每批数据到达时替换执行和结果引用，使虚拟表格可以在完整结果读取完成前先展示首批数据。

取消任务会调用 JDBC `Statement.cancel()`。数据修改语句会将当前编辑标签标记为“事务未提交”；提交和回滚也进入同一条串行队列，避免与正在执行的 SQL 发生竞争。

## 本地优先与安全设计

DBStudio 服务仅绑定随机的 `127.0.0.1` 端口。启动时会打开包含一次性令牌的认证地址，将令牌交换为 HttpOnly、`SameSite=Strict` Cookie，并从浏览器地址栏移除令牌。

前端资源已嵌入可执行 JAR，运行时不需要安装 Node.js。保存的密码不会写入 SQLite 或浏览器存储；在支持的操作系统上，密码由系统原生凭据存储保护。

## 环境要求

构建 DBStudio 需要：

- JDK 8 或更高版本
- Node.js 20.19 或更高版本及 npm
- Docker，仅在运行 MySQL 集成测试时需要

打包后的应用只需要 Java 8 或更高版本的 JRE。

## 构建项目

克隆仓库并执行完整验证构建：

```shell
git clone <你的仓库地址>
cd dbstudio
./mvnw clean verify
```

Maven Reactor 会安装前端依赖、运行 Java 和 Vue 测试、构建 Web 应用、检查 Java 8 API 兼容性，并生成：

```text
server-app/target/dbstudio-server.jar
```

## 运行项目

```shell
java -jar server-app/target/dbstudio-server.jar
```

DBStudio 会选择可用的本地端口，并在默认浏览器中打开已认证的工作区。使用 **更多 → 退出 DBStudio** 可以回滚未提交事务、关闭活动会话并安全停止本地服务。

## 前端开发

```shell
cd editor-web
npm ci
npm run dev
```

访问 `http://127.0.0.1:5173/?mock=1` 可使用仅限开发环境的模拟通信层；生产构建会忽略该参数。

常用前端命令：

```shell
npm run build
npm run test
npm run test:e2e
```

## 测试

运行完整测试：

```shell
./mvnw clean verify
```

单元测试和服务端安全测试不需要 Docker。MySQL 集成测试通过 Testcontainers 使用固定版本的 MySQL 8.0 和 8.4 镜像，覆盖连接、元数据、DDL/DML、事务、存储程序、结果编辑和 CSV 流程。

## 项目架构

| 模块 | 职责 |
| --- | --- |
| `database-spi` | 数据库无关的接口、能力、元数据、方言和领域类型 |
| `database-mysql` | MySQL 连接、元数据和 SQL 方言实现 |
| `database-oracle-common` | Oracle 兼容数据库共享的元数据和方言逻辑 |
| `database-oracle` | Oracle JDBC 提供器 |
| `database-oceanbase-oracle` | OceanBase Oracle 兼容提供器 |
| `application-core` | 会话、查询执行、事务、持久化、CSV 和凭据存储 |
| `editor-web` | Vue 3、TypeScript、Element Plus、Pinia 和 Monaco 前端 |
| `server-app` | 本地 Spring Boot REST/WebSocket 服务和可执行 JAR 打包 |

## 第三方库

DBStudio 使用了下列主要直接依赖或显式固定版本的第三方库。准确版本以 [`pom.xml`](pom.xml)、[`editor-web/package.json`](editor-web/package.json) 和 [`editor-web/package-lock.json`](editor-web/package-lock.json) 为准；传递依赖分别遵循其上游许可证。

### 后端与数据库访问

| 第三方库 | 用途 | 来源 | 许可证 |
| --- | --- | --- | --- |
| Spring Boot | 本地 REST/WebSocket 服务和应用打包 | [spring-projects/spring-boot](https://github.com/spring-projects/spring-boot) | Apache-2.0 |
| MySQL Connector/J | MySQL JDBC 连接 | [mysql/mysql-connector-j](https://github.com/mysql/mysql-connector-j) | GPL-2.0 + Universal FOSS Exception 1.0 |
| Oracle JDBC | Oracle JDBC 连接 | [Oracle JDBC](https://www.oracle.com/database/technologies/appdev/jdbc.html) | Oracle Free Use Terms and Conditions |
| OceanBase Connector/J | OceanBase JDBC 连接 | [oceanbase/obconnector-j](https://github.com/oceanbase/obconnector-j) | LGPL-2.1 |
| Alibaba Druid | SQL 解析和方言支持 | [alibaba/druid](https://github.com/alibaba/druid) | Apache-2.0 |
| SQLite JDBC | 应用本地数据持久化 | [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | Apache-2.0 |
| Jackson Databind | JSON 序列化与映射 | [FasterXML/jackson-databind](https://github.com/FasterXML/jackson-databind) | Apache-2.0 |
| Apache Commons CSV | CSV 导入与导出 | [apache/commons-csv](https://github.com/apache/commons-csv) | Apache-2.0 |
| Apache Commons Lang | 数据与导出链路所需的基础工具 | [apache/commons-lang](https://github.com/apache/commons-lang) | Apache-2.0 |
| Apache POI | 数据库连接 Excel 导入与导出 | [apache/poi](https://github.com/apache/poi) | Apache-2.0 |
| JNA | macOS 钥匙串与 Windows 凭据管理器集成 | [java-native-access/jna](https://github.com/java-native-access/jna) | LGPL-2.1-or-later 或 Apache-2.0 |
| SLF4J | 日志接口 | [qos-ch/slf4j](https://github.com/qos-ch/slf4j) | MIT |

### 前端

| 第三方库 | 用途 | 来源 | 许可证 |
| --- | --- | --- | --- |
| Vue | 前端应用框架 | [vuejs/core](https://github.com/vuejs/core) | MIT |
| Element Plus 和 Icons | UI 组件与图标 | [element-plus/element-plus](https://github.com/element-plus/element-plus) | MIT |
| Pinia | 前端状态管理 | [vuejs/pinia](https://github.com/vuejs/pinia) | MIT |
| Monaco Editor | SQL 编辑器 | [microsoft/monaco-editor](https://github.com/microsoft/monaco-editor) | MIT |
| Vite 和 Vue Plugin | 前端开发与生产构建 | [vitejs/vite](https://github.com/vitejs/vite)、[vitejs/vite-plugin-vue](https://github.com/vitejs/vite-plugin-vue) | MIT |
| TypeScript | 静态类型检查 | [microsoft/TypeScript](https://github.com/microsoft/TypeScript) | Apache-2.0 |
| unplugin-vue-components | Vue 组件自动导入 | [unplugin/unplugin-vue-components](https://github.com/unplugin/unplugin-vue-components) | MIT |

### 测试

| 第三方库 | 用途 | 来源 | 许可证 |
| --- | --- | --- | --- |
| JUnit 5 | Java 单元测试与集成测试 | [junit-team/junit5](https://github.com/junit-team/junit5) | EPL-2.0 |
| Testcontainers for Java | 基于 MySQL 容器的集成测试 | [testcontainers/testcontainers-java](https://github.com/testcontainers/testcontainers-java) | MIT |
| Vitest | 前端单元测试 | [vitest-dev/vitest](https://github.com/vitest-dev/vitest) | MIT |
| Vue Test Utils | Vue 组件测试 | [vuejs/test-utils](https://github.com/vuejs/test-utils) | MIT |
| happy-dom | 单元测试浏览器环境 | [capricorn86/happy-dom](https://github.com/capricorn86/happy-dom) | MIT |
| Playwright | 端到端浏览器测试 | [microsoft/playwright](https://github.com/microsoft/playwright) | Apache-2.0 |

## 参与贡献

欢迎提交 Issue 和 Pull Request。提交修改前请运行 `./mvnw clean verify`，并尽量将数据库专属行为保留在对应的提供器模块中。
