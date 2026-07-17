# Changelog

本项目遵循语义化版本。日期使用 `YYYY-MM-DD`。

## [Unreleased]

### Added

- 全新的三步引导式网页，支持数据库预设、连接测试、配置摘要与响应式布局。
- 新增 PostgreSQL、MySQL/MariaDB、Oracle、SQL Server 与通用 JDBC 入口。
- 新增本地静态图标资源与第三方资源说明，页面保持离线可用。

### Changed

- 项目展示名称调整为 DB DDL Exporter；KingbaseES 作为原生高保真适配器保留。
- 非 KingbaseES 数据库直接使用标准 JDBC 元数据路径，不再尝试 Kingbase 专用 SQL。

## [1.1.0] - 2026-07-17

### Added

- 新增 `export` 命令行模式，支持单对象、CSV 和整模式导出。
- 新增环境变量/终端密码输入、原子写入和默认防覆盖策略。
- 新增 JDBC 驱动 JAR 完整性、类存在性及隔离加载校验。
- 新增 14 个自动化测试、跨 JDK GitHub Actions CI 和标签发布流程。
- 新增中英文文档、脱敏 CSV 示例、贡献指南、安全策略和开源发布清单。

### Changed

- 网页与 CLI 共用驱动加载生命周期，临时驱动副本在使用后清理。
- 增加浏览器安全响应头、CSV 大小上限和 JAR 版本元数据。
- 启动方式兼容原有位置端口参数，同时支持 `serve --port` 与 `--no-browser`。

## [1.0.0]

- 初始本地网页版本：单对象、CSV、整模式导出，原生/回退 DDL 与 SSE 进度。
