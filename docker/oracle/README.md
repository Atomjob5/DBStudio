# DBStudio 本机 Oracle 测试库

该环境针对 Apple Silicon，使用原生 ARM64 的 `gvenzl/oracle-free:23.26.2`。Oracle Database Free 从23.5开始提供ARM镜像。

## 启动

```bash
cd docker/oracle
docker compose -f compose.yml up -d
docker compose -f compose.yml logs -f oracle
```

日志出现 `DATABASE IS READY TO USE` 且容器状态为`healthy`后即可连接。首次下载和初始化通常需要数分钟。

## DBStudio连接参数

- Provider：Oracle
- 主机：`127.0.0.1`
- 端口：`15211`
- 连接方式：`Service Name`
- Service Name：`FREEPDB1`
- 用户名：`CBSAC`、`CBSCM`或`CBSLN`
- 密码：`DbStudio123`
- 默认Schema：与用户名相同

SYSTEM测试账号同样使用密码`DbStudio123`。

`CBSAC`有25张普通表，`CBSCM`和`CBSLN`各有21张普通表；三个Schema还包含视图、索引、序列、同义词、触发器、过程、函数和包。`CBSAC.TXN_LEDGER`包含60000行。

`CBSAC.DATA_TYPE_SHOWCASE`及配套表覆盖该镜像可用的Oracle SQL内建类型族：

- 数值：`NUMBER`及`DECIMAL/NUMERIC/INTEGER/INT/SMALLINT`别名、`FLOAT/REAL/DOUBLE PRECISION`、`BINARY_FLOAT/BINARY_DOUBLE`。
- 字符：`CHAR/VARCHAR/VARCHAR2/NCHAR/NVARCHAR2`。
- 时间：`DATE`、三种`TIMESTAMP`、两种`INTERVAL`。
- 二进制和定位：`RAW/LONG RAW/ROWID/UROWID`。
- 大对象和旧式类型：`CLOB/NCLOB/BLOB/BFILE/LONG`。
- 半结构化与新类型：`XMLTYPE/JSON/BOOLEAN/VECTOR`。
- 对象关系类型：对象、`REF`、`VARRAY`、嵌套表和`ANYDATA`。

## 验证

```bash
docker compose -f compose.yml exec -T oracle \
  sqlplus -s system/DbStudio123@//localhost:1521/FREEPDB1 \
  @/container-entrypoint-initdb.d/900_verify.sql
```

## 重新初始化

初始化脚本只在空数据卷上执行。需要完全重建时：

```bash
docker compose -f compose.yml down -v
docker compose -f compose.yml up -d
```

`docker compose down -v`会永久删除该测试库数据。
