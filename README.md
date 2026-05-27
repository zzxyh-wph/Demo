# dbsync（Spring Boot 2.7.x + Liquibase）

面向“已有数据库”的结构同步工具后端项目（命令行 Jar + HTTP）。主要用于：

1. 连接指定数据库，导出完整库表结构的 SQL（baseline.sql）
2. 发版到生产后，用 baseline 的快照与生产库对比，生成 Liquibase changelog（diff.changelog.yaml）
3. 逐 changeSet 执行更新，单个 changeSet 异常单独落日志文件，且不中断整体执行

## 推荐流程（生成→人工审阅→批准→执行）

### 1) 部署后生成脚本（diffTables）

调用接口会做两件事：

- 从标准库生成/覆盖 `./dbsync/update.sql`（完整库表脚本）
- 与生产库对比生成当天的差异脚本 `./dbsync/diff/<yyyyMMdd>/diff-<yyyyMMdd>.sql`（默认仅输出 columns 类型差异，可通过 dbsync.diffTypes 调整）

```bash
curl -X POST http://127.0.0.1:8080/dbsync/diffTables
```

### 2) 人工审阅

人工审阅并确认 `diff-<yyyyMMdd>.sql`。

- 也可以选择手工执行该 SQL（不通过接口执行）

### 3) 审核后执行（updateTables）

```bash
curl -X POST "http://127.0.0.1:8080/dbsync/updateTables?approved=true&date=20260526"
```

执行特性：

- 按 SQL 语句逐条执行
- 单条语句失败会在 `./dbsync/apply-sql/<yyyyMMdd-HHmmss>/logs/` 下生成对应 `stmt-XXXX.log`
- 失败会跳过继续后续语句执行
- 每条语句会写入执行日志表 `dbsync_update_log`（含 tablename、old_sql、execute_sql、更新时间、状态、异常说明）

## 打包

```bash
mvn -s /workspace/maven-settings.xml -DskipTests package
```

产物：

`target/demo2-0.0.1-SNAPSHOT.jar`

## 用法

统一默认输出目录：`./dbsync`

### 1) 生成 baseline（从“基准库”导出）

```bash
java -jar target/demo2-0.0.1-SNAPSHOT.jar generate \
  --url=jdbc:mysql://127.0.0.1:3306/your_db?useSSL=false&serverTimezone=Asia/Shanghai \
  --username=your_user \
  --passwordEnv=DB_PASS
```

输出：

- `./dbsync/baseline/snapshot.json`（用于后续 diff 的离线快照）
- `./dbsync/baseline/baseline.changelog.yaml`（Liquibase 规范 changelog）
- `./dbsync/baseline/baseline.sql`（从 changelog 生成的完整建库建表 SQL）

### 2) 生成 diff changelog（baseline vs 生产库）

```bash
java -jar target/demo2-0.0.1-SNAPSHOT.jar diff \
  --baselineSnapshot=./dbsync/baseline/snapshot.json \
  --prodUrl=jdbc:mysql://prod-host:3306/prod_db?useSSL=false&serverTimezone=Asia/Shanghai \
  --prodUsername=prod_user \
  --prodPasswordEnv=PROD_DB_PASS
```

输出：

- `./dbsync/diff/<yyyyMMdd-HHmmss>/diff.changelog.yaml`
- `./dbsync/diff/<yyyyMMdd-HHmmss>/diff.sql`（从 diff changelog 生成的 SQL，便于审阅）

Liquibase 支持使用离线 snapshot 对比在线库：`offline:<db>?snapshot=<path>`，可参考官方文档示例：`liquibase snapshot --snapshot-format=json` 与 `liquibase diff-changelog --url=offline:...?...`（见 Liquibase 文档说明）。

### 3) 执行 diff changelog（逐 changeSet 执行）

```bash
java -jar target/demo2-0.0.1-SNAPSHOT.jar apply \
  --changelog=./dbsync/diff/<yyyyMMdd-HHmmss>/diff.changelog.yaml \
  --prodUrl=jdbc:mysql://prod-host:3306/prod_db?useSSL=false&serverTimezone=Asia/Shanghai \
  --prodUsername=prod_user \
  --prodPasswordEnv=PROD_DB_PASS
```

执行特性：

- 逐 changeSet 执行
- 单 changeSet 失败会在 `./dbsync/apply/<yyyyMMdd-HHmmss>/logs/` 下生成对应 `.log`
- 失败不会中断后续 changeSet 的执行

## 配置（application.yml）

需要在 `application.yml` 中配置标准库与生产库连接（建议使用环境变量注入密码）：

- `dbsync.standard.*`
- `dbsync.production.*`

## 依赖与约束

- Spring Boot: 2.7.18
- Liquibase: 由 Spring Boot 2.7.x 依赖管理引入（当前解析为 4.9.1）
- 仅内置 MySQL JDBC 驱动（mysql-connector-java:8.0.33）
