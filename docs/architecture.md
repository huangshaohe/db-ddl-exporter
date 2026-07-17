# Architecture

DB DDL Exporter 保持单模块和零运行时第三方依赖，使用 JDK 自带 HTTP、JDBC、文件和并发 API。

## 运行路径

```text
浏览器表单 ──multipart──> 本地 HTTP 处理器 ─┐
                                             ├─> 驱动 JAR 校验/隔离加载
CLI 参数 + CSV ──────────────────────────────┘           │
                                                         v
                                               DdlExportService
                                              /        |         \
                                DBMS_METADATA   JDBC 表元数据   视图定义
                                              \        |         /
                                               SQL 文档与失败摘要
```

网页模式固定绑定 `127.0.0.1`。CSRF 令牌在进程启动时随机生成，上传请求和 SSE 进度订阅都必须携带令牌。上传体受总大小限制，驱动写到操作系统临时目录后加载，并在请求结束时关闭类加载器和删除临时文件。

CLI 模式不启动网络服务。它从本地路径加载驱动，使用与网页相同的 `DdlExportService`，然后先写同目录临时文件再移动为最终 SQL。默认拒绝覆盖已有文件。

## DDL 策略

1. 用 `information_schema.tables` 解析对象的真实模式、名称和表/视图类型。
2. KingbaseES 连接尝试 `DBMS_METADATA.GET_DDL`，先使用当前 `search_path` 的两参数形式，再尝试三参数形式；其他数据库跳过厂商专用调用。
3. 表回退读取 JDBC `DatabaseMetaData`；视图回退读取 `information_schema.views`。
4. 批量导出先处理表、再处理视图；每个对象独立失败，最终在文件头汇总。

回退路径有意保持保守，不声称覆盖分区、触发器、表达式索引、权限或所有厂商扩展。

## 设计约束

- Java 8 字节码，以覆盖仍在使用旧版 JRE 的离线 Windows 环境。
- 运行时无第三方依赖，降低分发体积和供应链面。
- 不捆绑任何数据库驱动，避免版本冲突及许可证问题。
- 不提供执行生成 SQL 的能力，保持“读取与导出”边界。
