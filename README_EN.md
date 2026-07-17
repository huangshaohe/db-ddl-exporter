# DB DDL Exporter

[简体中文](./README.md)

A local-first, runtime-dependency-free DDL exporter for multiple JDBC databases. It provides guided browser and CLI workflows for KingbaseES, PostgreSQL, MySQL/MariaDB, SQL Server, Oracle, and generic JDBC drivers.

> This repository does not have an open-source license yet. Before publishing it, complete the license and ownership items in the [open-source release checklist](./docs/open-source-checklist.md).

## Highlights

- One executable JAR, compatible with Java 8 and newer runtimes.
- Guided browser UI with database presets, connection testing, configuration summaries, CSV analysis, live SSE progress, and automatic downloads.
- Non-server CLI mode with environment-based password input.
- Export one object, every visible table/view in a schema, or objects listed in a generic `schema,object` CSV.
- Prefer native `DBMS_METADATA.GET_DDL` for KingbaseES; use JDBC metadata for other databases and `information_schema.views` where available.
- Reuse one connection for batch jobs and report per-object failures without stopping the batch.
- Validate and load the selected JDBC driver in an isolated class loader, then close it and remove temporary copies.
- Never bundle a database driver; users choose a trusted, compatible, properly licensed driver.

## Browser quick start

Prepare Java 8+, the application JAR, and a trusted JDBC driver JAR for the target database.

```bash
java -jar db-ddl-exporter.jar
```

The app opens `http://127.0.0.1:18888/`. On Windows, place the JAR next to `start.bat` and double-click it. On macOS/Linux, run `./start.sh`.

```bash
# Custom port without opening a browser
java -jar db-ddl-exporter.jar serve --port 18889 --no-browser

java -jar db-ddl-exporter.jar --help
```

The browser fills common driver classes and URL templates. For CLI backward compatibility, the default driver class remains `com.kingbase8.Driver`; pass `--driver-class` for other databases.

## Database compatibility

| Database | Current path | Level |
| --- | --- | --- |
| KingbaseES | Native `DBMS_METADATA.GET_DDL` with JDBC fallback | Recommended |
| PostgreSQL | JDBC table metadata and `information_schema` views | Basic |
| MySQL / MariaDB | JDBC table metadata and `information_schema` views | Basic |
| SQL Server | JDBC table metadata and `information_schema` views | Basic |
| Oracle | JDBC table metadata; view support depends on driver and grants | Experimental |
| Other JDBC databases | Standard JDBC metadata | Experimental |

Basic support covers ordinary tables, columns, keys, indexes, and readable view definitions. Vendor-specific objects still require a dedicated adapter.

A typical KingbaseES URL looks like:

```text
jdbc:kingbase8://database.example.com:54321/database_name
```

## CLI export

Choose exactly one scope: `--object`, `--csv`, or `--all`.

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

If `--password-env` is omitted, an interactive terminal prompts for the password; a non-interactive process uses an empty password. There is deliberately no `--password` option, which prevents secrets from appearing in process lists and shell history. Existing output files are protected unless `--overwrite` is passed.

Run `java -jar db-ddl-exporter.jar export --help` for all options.

## CSV format

CSV files need at least `schema` and `object` columns. Header matching is case-insensitive, and UTF-8 BOMs, quoted commas, and multiline fields are supported.

```csv
schema,object,evidence,source_files
public,orders,entity,src/Order.java
public,order_items,mapper-sql,src/OrderMapper.java
reporting,daily_sales,mapper-sql,src/ReportMapper.java
```

See [`examples/db-schema-usage.example.csv`](./examples/db-schema-usage.example.csv). A source-code inventory is only a candidate list; compare it with `information_schema.tables` before exporting.

## Accuracy and safety

Native `DBMS_METADATA.GET_DDL` is the preferred path. The table fallback covers ordinary columns, defaults, nullability, primary/foreign keys, indexes, and comments, but advanced features such as partitioning, expression indexes, triggers, and storage parameters require manual review. The view fallback requires review of ownership, grants, comments, and dependency order.

The web server binds only to `127.0.0.1`, uses a random CSRF token, and does not persist form values or passwords. It only reads metadata and attempts a session-level `search_path` change. JDBC drivers are executable code, so only load drivers from trusted sources. Review generated SQL in an isolated test database before using it elsewhere.

See [`SECURITY.md`](./SECURITY.md) and [`docs/architecture.md`](./docs/architecture.md) for details.

## Development

```bash
mvn clean verify
java -jar target/db-ddl-exporter.jar --version
```

The artifact is `target/db-ddl-exporter.jar`. Runtime Java code has no third-party dependencies; JUnit is test-only. Vendored UI icon notices are in [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md). See [`CONTRIBUTING.md`](./CONTRIBUTING.md) before contributing.

## Disclaimer

This independent project is not officially affiliated with any database vendor. Product names and marks belong to their respective owners.
