# dbsync（Spring Boot 2.7.x + Liquibase）

面向“已有数据库”的结构同步工具后端项目（命令行 Jar）。主要用于：

1. 连接指定数据库，导出完整库表结构的 SQL（baseline.sql）
2. 发版到生产后，用 baseline 的快照与生产库对比，生成 Liquibase changelog（diff.changelog.xml）
3. 逐 changeSet 执行更新，单个 changeSet 异常单独落日志文件，且不中断整体执行

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
- `./dbsync/baseline/baseline.changelog.xml`（Liquibase 规范 changelog）
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

- `./dbsync/diff/<yyyyMMdd-HHmmss>/diff.changelog.xml`
- `./dbsync/diff/<yyyyMMdd-HHmmss>/diff.sql`（从 diff changelog 生成的 SQL，便于审阅）

Liquibase 支持使用离线 snapshot 对比在线库：`offline:<db>?snapshot=<path>`，可参考官方文档示例：`liquibase snapshot --snapshot-format=json` 与 `liquibase diff-changelog --url=offline:...?...`（见 Liquibase 文档说明）。

### 3) 执行 diff changelog（逐 changeSet 执行）

```bash
java -jar target/demo2-0.0.1-SNAPSHOT.jar apply \
  --changelog=./dbsync/diff/<yyyyMMdd-HHmmss>/diff.changelog.xml \
  --prodUrl=jdbc:mysql://prod-host:3306/prod_db?useSSL=false&serverTimezone=Asia/Shanghai \
  --prodUsername=prod_user \
  --prodPasswordEnv=PROD_DB_PASS
```

执行特性：

- 逐 changeSet 执行
- 单 changeSet 失败会在 `./dbsync/apply/<yyyyMMdd-HHmmss>/logs/` 下生成对应 `.log`
- 失败不会中断后续 changeSet 的执行

## 依赖与约束

- Spring Boot: 2.7.18
- Liquibase: 由 Spring Boot 2.7.x 依赖管理引入（当前解析为 4.9.1）
- 仅内置 MySQL JDBC 驱动（mysql-connector-java:8.0.33）
