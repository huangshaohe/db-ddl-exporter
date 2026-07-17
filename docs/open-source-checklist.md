# Open-source release checklist

这份清单用于把当前子目录安全地发布成独立 GitHub 项目。

## 发布前必须完成

- [ ] 确认源码权利人和对外发布授权。
- [ ] 由权利人选择许可证并添加根目录 `LICENSE`；未添加许可证前，公开可见不等于开源授权。
- [ ] 确认项目名称、`com.thunisoft.tools` Maven 坐标和 Java 包名是否允许公开使用。
- [ ] 新建干净的独立仓库，只复制 `tools/kingbase-ddl-exporter`；不要直接公开当前业务单体仓库及其 Git 历史。
- [ ] 确认不包含真实密码、JDBC URL、内网 IP、业务模式/对象清单、服务名、源码路径或日志。
- [ ] 不复制项目外的 `ci/db-schema-usage.csv`、`DB_SCHEMA_USAGE.md` 或任何内部扫描产物。
- [ ] 不提交 Kingbase 或其他厂商的 JDBC 驱动，除非其许可证明确允许再分发。

## 仓库设置

- [ ] 在 `pom.xml` 补充最终仓库 URL、SCM、开发者和许可证元数据。
- [ ] 启用 GitHub Actions，并确认 Java 8/11/17/21/25 构建矩阵通过。
- [ ] 启用 Dependabot、Secret scanning、Push protection 和 Private vulnerability reporting。
- [ ] 设置默认分支保护：CI 必须通过、禁止强推、至少一次审查（多人维护时）。
- [ ] 配置 Issue/PR 模板和仓库描述、Topics、社会化预览图。

## 首次发布

- [ ] 执行 `mvn clean verify`。
- [ ] 在支持的 KingbaseES 测试环境验证原生路径、表回退、视图回退和三种导出范围。
- [ ] 更新 `CHANGELOG.md`，提交并创建与 Maven 版本一致的标签，例如 `v1.1.0`。
- [ ] 检查 GitHub Release 中的 JAR 和 `SHA256SUMS`。
- [ ] 从 Release 下载到干净环境，再验证 `--version`、网页启动和至少一次测试库导出。

## 后续增强建议

- [ ] 增加 SBOM、构建来源证明和签名发布。
- [ ] 建立 KingbaseES/JDBC 驱动兼容性矩阵。
- [ ] 用容器化或可公开测试数据库补充端到端测试（不得依赖生产环境）。
- [ ] 根据真实用户反馈评估序列、函数、物化视图和权限导出；在验证前不要扩大准确性承诺。
