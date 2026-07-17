# Contributing

感谢你改进 Kingbase DDL Exporter。提交代码前请先说明要解决的问题，并尽量保持以下设计原则：Java 8 兼容、单 JAR、运行时零第三方依赖、本地优先，以及默认保护数据库凭据和已有文件。

## 本地验证

```bash
mvn clean verify
java -jar target/kingbase-ddl-exporter.jar --help
java -jar target/kingbase-ddl-exporter.jar export --help
```

新增解析、参数、安全边界或 DDL 生成逻辑时，应同步增加测试。测试不得依赖真实生产数据库、真实账号、专有 JDBC 驱动或互联网。

## 提交内容

- 不要提交密码、JDBC URL、内网 IP、数据库对象清单、业务源码路径或日志。
- 不要提交未经授权再分发的 JDBC 驱动 JAR。
- 示例必须使用虚构主机、账号、模式和对象名。
- 保持变更聚焦；如果行为或命令行接口发生变化，请更新 README 和 CHANGELOG。
- 生成的 SQL 必须使用标识符/字符串转义，并把异常文本安全地写入注释。

## Pull Request

PR 描述应包含问题、方案、兼容性影响和验证方式。涉及数据库版本差异的修复，请说明已验证的 KingbaseES/JDBC 驱动版本，但不要附带敏感连接信息。
