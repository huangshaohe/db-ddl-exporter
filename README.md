# DB DDL Exporter

[English](./README_EN.md)

一个本地优先、零运行时依赖的多数据库 DDL 导出工具。通过可替换的 JDBC 驱动连接 KingbaseES、PostgreSQL、MySQL/MariaDB、SQL Server、Oracle 或其他 JDBC 数据库；既可使用浏览器图形界面，也可在终端或 CI 中直接导出。

> 当前仓库尚未添加开源许可证。正式公开前，请先完成[开源发布清单](./docs/open-source-checklist.md)中的许可证与权利人确认。

## 主要能力

- 单个可执行 JAR，兼容 Java 8 及更高版本。
- 三步引导式网页：数据库预设、驱动选择、连接测试、配置摘要、CSV 模式统计、实时 SSE 进度和自动下载。
- 命令行模式：适合脚本、计划任务和 CI，支持安全读取环境变量中的密码。
- 三种范围：单个表/视图、整个模式、或通用 `schema,object` CSV 清单。
- KingbaseES 优先使用 `DBMS_METADATA.GET_DDL`；其他数据库使用 JDBC 元数据，视图定义在可用时读取 `information_schema.views`。
- 批量任务复用一个连接；单个对象失败不会中断其他对象，失败摘要写入 SQL 文件头。
- JDBC 驱动按请求隔离加载，校验 JAR 和驱动类，使用结束后关闭并删除临时副本。
- 不把 JDBC 驱动打包进项目，使用者自行选择与数据库版本及授权匹配的驱动。

## 快速开始：本地网页

准备 Java 8+、本项目 JAR，以及来自数据库厂商可信来源的 JDBC 驱动 JAR。

```bash
java -jar db-ddl-exporter.jar
```

浏览器会打开 `http://127.0.0.1:18888/`。Windows 也可以把 JAR 与 `start.bat` 放在同一目录后双击启动；macOS/Linux 可运行 `./start.sh`。

常用启动参数：

```bash
# 指定端口，不自动打开浏览器
java -jar db-ddl-exporter.jar serve --port 18889 --no-browser

# 查看全部命令
java -jar db-ddl-exporter.jar --help
```

网页会根据数据库类型自动填写常见驱动类、端口与 URL 模板。CLI 为兼容旧用法，默认驱动类仍为 `com.kingbase8.Driver`；连接其他数据库时请显式传入 `--driver-class`。

## 数据库兼容性

| 数据库 | 当前导出路径 | 支持级别 |
| --- | --- | --- |
| KingbaseES | 原生 `DBMS_METADATA.GET_DDL` + JDBC 回退 | 推荐 |
| PostgreSQL | JDBC 表元数据 + `information_schema` 视图 | 基础支持 |
| MySQL / MariaDB | JDBC 表元数据 + `information_schema` 视图 | 基础支持 |
| SQL Server | JDBC 表元数据 + `information_schema` 视图 | 基础支持 |
| Oracle | JDBC 表元数据；视图定义依赖驱动/权限 | 实验性 |
| 其他 JDBC 数据库 | 标准 JDBC 元数据 | 实验性 |

“基础支持”覆盖普通表、字段、主外键、普通索引和可读取的视图定义，不承诺还原触发器、存储过程、分区、权限和全部厂商扩展。

KingbaseES 常见连接格式如下：

```text
jdbc:kingbase8://database.example.com:54321/database_name
```

## 命令行自动化

命令行模式不会启动 HTTP 服务。三种导出范围必须选择一个：`--object`、`--csv` 或 `--all`。

```bash
export DB_PASSWORD='your-password'

java -jar db-ddl-exporter.jar export \
  --driver ./kingbase8.jar \
  --url jdbc:kingbase8://database.example.com:54321/example \
  --user app_user \
  --password-env DB_PASSWORD \
  --schema public \
  --all \
  --output ./exports/
```

只导出一个对象：

```bash
java -jar db-ddl-exporter.jar export \
  --driver ./kingbase8.jar \
  --url jdbc:kingbase8://database.example.com:54321/example \
  --user app_user --schema public --object orders
```

若不提供 `--password-env`，程序会在交互式终端中安全询问密码；无交互终端时使用空密码。为避免密码出现在进程列表和 shell 历史中，本工具故意不提供 `--password` 参数。默认不覆盖已有输出文件，如确需覆盖可添加 `--overwrite`。

完整参数见：

```bash
java -jar db-ddl-exporter.jar export --help
```

## CSV 清单格式

CSV 至少需要 `schema` 和 `object` 两列，列名不区分大小写；其他证据列可自由保留。支持 UTF-8 BOM、双引号、逗号和字段内换行。

```csv
schema,object,evidence,source_files
public,orders,entity,src/Order.java
public,order_items,mapper-sql,src/OrderMapper.java
reporting,daily_sales,mapper-sql,src/ReportMapper.java
```

完整示例见 [`examples/db-schema-usage.example.csv`](./examples/db-schema-usage.example.csv)。网页会按模式展示去重后的对象数；命令行通过 `--csv <path> --schema <name>` 只导出选定模式。

CSV 通常来自源码静态扫描，并不等于数据库真实目录。导出前应与 `information_schema.tables` 交叉验证：

```sql
SELECT table_schema, table_name, table_type
FROM information_schema.tables
WHERE table_schema NOT IN ('sys_catalog', 'information_schema')
ORDER BY table_schema, table_name;
```

## 导出精度

| 路径 | 覆盖范围 | 注意事项 |
| --- | --- | --- |
| Kingbase `DBMS_METADATA.GET_DDL` | KingbaseES 原生表/视图定义 | 推荐；需要数据库已安装扩展且账号有调用权限 |
| JDBC 表回退 | 列、默认值、非空、主外键、普通索引、表和列注释 | 分区、表达式索引、触发器、存储参数等需人工复核 |
| `information_schema` 视图回退 | `CREATE OR REPLACE VIEW` 与查询定义 | 所有者、权限、注释及依赖顺序需人工复核 |

原生导出失败的原因会作为 SQL 注释记录。生成结果应先在隔离的测试库审阅和验证，不应直接在生产库执行。

可由 DBA 检查 `dbms_metadata` 扩展状态：

```sql
SELECT name, default_version, installed_version
FROM sys_available_extensions
WHERE name = 'dbms_metadata';
```

安装扩展属于数据库变更，本工具不会自动执行。

## 安全边界

- 网页服务固定监听 `127.0.0.1`，带随机 CSRF 令牌和严格的浏览器安全响应头。
- 密码仅用于当前连接，不写日志或磁盘；网页不会保存连接表单。
- 代码只查询元数据并尝试设置会话级 `search_path`，不会创建、修改或删除数据库对象。
- JDBC 驱动是可执行 Java 代码。请只加载可信来源的驱动；JAR 格式校验不能证明驱动本身安全。
- 单次 HTTP 请求最大 50 MB，CSV 最大 10 MB，单个 CSV 模式最多 10,000 个对象。
- 项目不附带任何数据库 JDBC 驱动。公开发布或再分发驱动前必须单独确认其许可证。

更多设计说明见 [`docs/architecture.md`](./docs/architecture.md)，安全问题报告方式见 [`SECURITY.md`](./SECURITY.md)。

## 开发

```bash
mvn clean verify
java -jar target/db-ddl-exporter.jar --version
```

构建产物为 `target/db-ddl-exporter.jar`。运行时没有第三方 Java 依赖；JUnit 仅用于测试。页面图标来源及许可证见 [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md)。

贡献前请阅读 [`CONTRIBUTING.md`](./CONTRIBUTING.md)。版本变化记录在 [`CHANGELOG.md`](./CHANGELOG.md)。

## 声明

本项目是独立工具，与文中数据库厂商不存在官方隶属关系。KingbaseES、PostgreSQL、MySQL、MariaDB、Oracle、SQL Server 及相关名称和标识可能是其各自权利人的商标。
