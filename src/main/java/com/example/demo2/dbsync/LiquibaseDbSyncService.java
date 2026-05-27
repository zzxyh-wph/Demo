package com.example.demo2.dbsync;

import liquibase.CatalogAndSchema;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.visitor.ChangeExecListener;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.exception.DatabaseException;
import liquibase.lockservice.LockService;
import liquibase.lockservice.LockServiceFactory;
import liquibase.resource.ResourceAccessor;
import liquibase.integration.commandline.CommandLineUtils;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import liquibase.serializer.SnapshotSerializer;
import liquibase.serializer.SnapshotSerializerFactory;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotControl;
import liquibase.snapshot.SnapshotGeneratorFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LiquibaseDbSyncService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String UPDATE_LOG_TABLE = "dbsync_update_log";

    private static final Pattern ALTER_TABLE_PATTERN = Pattern.compile("(?i)\\balter\\s+table\\s+`?([a-zA-Z0-9_]+)`?");
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile("(?i)\\bcreate\\s+table\\s+`?([a-zA-Z0-9_]+)`?");
    private static final Pattern DROP_TABLE_PATTERN = Pattern.compile("(?i)\\bdrop\\s+table\\s+`?([a-zA-Z0-9_]+)`?");
    private static final Pattern COLUMN_PATTERN = Pattern.compile("(?i)\\b(add\\s+column|add|modify\\s+column|modify|change\\s+column|change|drop\\s+column|drop)\\s+`?([a-zA-Z0-9_]+)`?(?:\\s+`?([a-zA-Z0-9_]+)`?)?");

    public BaselineFiles generateBaseline(DbConnectionInfo source, Path outputDir) throws Exception {
        Path baselineDir = outputDir.resolve("baseline");
        Files.createDirectories(baselineDir);

        Path snapshotFile = baselineDir.resolve("snapshot.json");
        Path baselineChangelog = baselineDir.resolve("baseline.changelog.yaml");
        Path baselineSql = baselineDir.resolve("baseline.sql");

        Database sourceDb = openJdbcDatabase(source);
        try {
            writeSnapshot(sourceDb, snapshotFile);
            writeGenerateChangelog(sourceDb, baselineChangelog);
        } finally {
            try {
                sourceDb.close();
            } catch (Exception ignored) {
            }
        }

        writeSqlFromChangelog(baselineChangelog, baselineSql, "offline:mysql");

        return new BaselineFiles(baselineDir, snapshotFile, baselineChangelog, baselineSql);
    }

    public StandardUpdateFiles generateStandardUpdateSql(DbConnectionInfo standard, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);

        Path standardDir = outputDir.resolve("standard");
        Files.createDirectories(standardDir);

        Path snapshotFile = standardDir.resolve("snapshot.json");
        Path changelogFile = standardDir.resolve("standard.changelog.yaml");
        Path updateSqlFile = outputDir.resolve("update.sql");

        Database sourceDb = openJdbcDatabase(standard);
        try {
            writeSnapshot(sourceDb, snapshotFile);
            writeGenerateChangelog(sourceDb, changelogFile);
        } finally {
            try {
                sourceDb.close();
            } catch (Exception ignored) {
            }
        }

        writeSqlFromChangelog(changelogFile, updateSqlFile, "offline:mysql");
        return new StandardUpdateFiles(standardDir, snapshotFile, changelogFile, updateSqlFile);
    }

    public DiffSqlFiles diffTablesToSql(Path standardSnapshotFile, DbConnectionInfo prod, String diffTypes, Path outputDir) throws Exception {
        String day = LocalDate.now().format(DAY);
        Path dayDir = outputDir.resolve("diff").resolve(day);
        Files.createDirectories(dayDir);

        Path diffChangelog = dayDir.resolve("diff.changelog.yaml");
        Path diffSql = dayDir.resolve("diff-" + day + ".sql");

        ResourceAccessor resourceAccessor = new CompositeResourceAccessor(
                new FileSystemResourceAccessor(standardSnapshotFile.getParent().toFile()),
                new ClassLoaderResourceAccessor()
        );
        Database referenceDb = openOfflineSnapshotDatabase(standardSnapshotFile, resourceAccessor);

        Database prodDb = openJdbcDatabase(prod);
        try {
            writeDiffChangelog(referenceDb, prodDb, diffChangelog, diffTypes);
        } finally {
            try {
                referenceDb.close();
            } catch (Exception ignored) {
            }
            try {
                prodDb.close();
            } catch (Exception ignored) {
            }
        }

        writeSqlFromChangelog(diffChangelog, diffSql, prod.getUrl(), prod.getUsername(), prod.getPassword(), prod.getDefaultSchema());
        return new DiffSqlFiles(dayDir, diffChangelog, diffSql);
    }

    public DiffFiles diffWithProduction(Path baselineSnapshotFile, DbConnectionInfo prod, Path outputDir) throws Exception {
        Path diffDir = outputDir.resolve("diff").resolve(LocalDateTime.now().format(TS));
        Files.createDirectories(diffDir);

        Path diffChangelog = diffDir.resolve("diff.changelog.yaml");
        Path diffSql = diffDir.resolve("diff.sql");

        ResourceAccessor resourceAccessor = new CompositeResourceAccessor(
                new FileSystemResourceAccessor(baselineSnapshotFile.getParent().toFile()),
                new ClassLoaderResourceAccessor()
        );
        Database referenceDb = openOfflineSnapshotDatabase(baselineSnapshotFile, resourceAccessor);

        Database prodDb = openJdbcDatabase(prod);
        try {
            writeDiffChangelog(referenceDb, prodDb, diffChangelog);
        } finally {
            try {
                referenceDb.close();
            } catch (Exception ignored) {
            }
            try {
                prodDb.close();
            } catch (Exception ignored) {
            }
        }

        writeSqlFromChangelog(diffChangelog, diffSql, prod.getUrl(), prod.getUsername(), prod.getPassword(), prod.getDefaultSchema());

        return new DiffFiles(diffDir, diffChangelog, diffSql);
    }

    public SqlApplyResult applySqlFile(DbConnectionInfo prod, Path sqlFile, Path outputDir) throws Exception {
        String ts = LocalDateTime.now().format(TS);
        Path applyDir = outputDir.resolve("apply-sql").resolve(ts);
        Path logsDir = applyDir.resolve("logs");
        Files.createDirectories(logsDir);

        String content = Files.readString(sqlFile, StandardCharsets.UTF_8);
        List<String> statements = splitStatements(content);

        int success = 0;
        int failed = 0;

        try (Connection connection = DriverManager.getConnection(prod.getUrl(), prod.getUsername(), prod.getPassword())) {
            connection.setAutoCommit(true);
            ensureUpdateLogTable(connection);
            for (int i = 0; i < statements.size(); i++) {
                String statement = statements.get(i).trim();
                if (statement.isEmpty()) {
                    continue;
                }
                String idx = String.format("%04d", i + 1);
                Path logFile = logsDir.resolve("stmt-" + idx + ".log");
                String tableName = parseTableName(statement);
                String oldSql = tryGetOldColumnSql(connection, statement, tableName);
                try (Statement st = connection.createStatement()) {
                    st.execute(statement);
                    success++;
                    insertUpdateLog(connection, tableName, oldSql, statement, 1, null);
                } catch (SQLException e) {
                    failed++;
                    Files.writeString(logFile, statement + "\n\n" + e.getClass().getName() + ": " + e.getMessage() + "\n", StandardCharsets.UTF_8);
                    insertUpdateLog(connection, tableName, oldSql, statement, 0, e.getMessage());
                }
            }
        }

        return new SqlApplyResult(applyDir, logsDir, statements.size(), success, failed, UPDATE_LOG_TABLE);
    }

    public ApplyResult applyChangelogIndividually(Path changelogFile, DbConnectionInfo prod, Path outputDir) throws Exception {
        Path applyDir = outputDir.resolve("apply").resolve(LocalDateTime.now().format(TS));
        Path applyLogDir = applyDir.resolve("logs");
        Files.createDirectories(applyLogDir);

        ResourceAccessor resourceAccessor = new CompositeResourceAccessor(
                new FileSystemResourceAccessor(changelogFile.getParent().toFile()),
                new ClassLoaderResourceAccessor()
        );

        Database prodDb = openJdbcDatabase(prod);
        try {
            LockService lockService = LockServiceFactory.getInstance().getLockService(prodDb);
            lockService.waitForLock();

            Liquibase liquibase = new Liquibase(changelogFile.getFileName().toString(), resourceAccessor, prodDb);

            List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
            int success = 0;
            int failed = 0;
            for (ChangeSet changeSet : unrun) {
                Path singleLog = applyLogDir.resolve(safeFileName(changeSet.getId() + "-" + changeSet.getAuthor()) + ".log");
                try {
                    changeSet.execute(liquibase.getDatabaseChangeLog(), new NoopChangeExecListener(), prodDb);
                    prodDb.commit();
                    success++;
                } catch (Exception e) {
                    prodDb.rollback();
                    failed++;
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(singleLog.toFile()))) {
                        out.write((e.getClass().getName() + ": " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
                        e.printStackTrace(new PrintStream(out));
                    }
                }
            }
            try {
                lockService.releaseLock();
            } catch (Exception ignored) {
            }
            return new ApplyResult(applyDir, applyLogDir, unrun.size(), success, failed);
        } finally {
            try {
                prodDb.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Database openJdbcDatabase(DbConnectionInfo info) throws Exception {
        Connection connection = DriverManager.getConnection(info.getUrl(), info.getUsername(), info.getPassword());
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        if (info.getDefaultSchema() != null && !info.getDefaultSchema().isBlank()) {
            database.setDefaultSchemaName(info.getDefaultSchema());
        }
        return database;
    }

    private Database openOfflineSnapshotDatabase(Path snapshotFile, ResourceAccessor resourceAccessor) throws DatabaseException {
        String url = "offline:mysql?snapshot=" + snapshotFile.toAbsolutePath();
        DatabaseConnection offlineConnection = new OfflineConnection(url, resourceAccessor);
        return DatabaseFactory.getInstance().findCorrectDatabaseImplementation(offlineConnection);
    }

    private void writeSnapshot(Database database, Path outputFile) throws Exception {
        CatalogAndSchema schema = database.getDefaultSchema();
        DatabaseSnapshot snapshot = SnapshotGeneratorFactory.getInstance().createSnapshot(schema, database, new SnapshotControl(database));
        SnapshotSerializer serializer = SnapshotSerializerFactory.getInstance().getSerializer("json");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile.toFile()))) {
            serializer.write(snapshot, out);
        }
    }

    private void writeGenerateChangelog(Database database, Path changelogFile) throws Exception {
        CatalogAndSchema[] schemas = new CatalogAndSchema[]{database.getDefaultSchema()};
        CompareControl.SchemaComparison[] schemaComparisons = new CompareControl.SchemaComparison[]{
                new CompareControl.SchemaComparison(database.getDefaultSchema(), database.getDefaultSchema())
        };
        DiffOutputControl diffOutputControl = new DiffOutputControl(false, false, false, schemaComparisons);
        CommandLineUtils.doGenerateChangeLog(changelogFile.toString(), database, schemas, null, "dbsync (generated)", null, null, diffOutputControl);
    }

    private void writeDiffChangelog(Database referenceDb, Database prodDb, Path diffChangelog, String diffTypes) throws Exception {
        CompareControl.SchemaComparison[] schemaComparisons = new CompareControl.SchemaComparison[]{
                new CompareControl.SchemaComparison(referenceDb.getDefaultSchema(), prodDb.getDefaultSchema())
        };
        DiffOutputControl diffOutputControl = new DiffOutputControl(false, false, false, schemaComparisons);
        CommandLineUtils.doDiffToChangeLog(diffChangelog.toString(), referenceDb, prodDb, diffOutputControl, null, diffTypes, schemaComparisons);
    }

    private void writeDiffChangelog(Database referenceDb, Database prodDb, Path diffChangelog) throws Exception {
        writeDiffChangelog(referenceDb, prodDb, diffChangelog, null);
    }

    private void writeSqlFromChangelog(Path changelogFile, Path outputSqlFile, String offlineUrl) throws Exception {
        ResourceAccessor resourceAccessor = new CompositeResourceAccessor(
                new FileSystemResourceAccessor(changelogFile.getParent().toFile()),
                new ClassLoaderResourceAccessor()
        );
        DatabaseConnection offlineConn = new OfflineConnection(offlineUrl, resourceAccessor);
        Database offlineDb = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(offlineConn);
        try {
            try (Liquibase liquibase = new Liquibase(changelogFile.getFileName().toString(), resourceAccessor, offlineDb);
                 Writer out = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(outputSqlFile.toFile())), StandardCharsets.UTF_8)) {
                liquibase.update(new Contexts(), new LabelExpression(), out);
            }
        } finally {
            try {
                offlineDb.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void writeSqlFromChangelog(Path changelogFile, Path outputSqlFile, String url, String username, String password, String defaultSchema) throws Exception {
        ResourceAccessor resourceAccessor = new CompositeResourceAccessor(
                new FileSystemResourceAccessor(changelogFile.getParent().toFile()),
                new ClassLoaderResourceAccessor()
        );
        Database prodDb = openJdbcDatabase(new DbConnectionInfo(url, username, password, defaultSchema));
        try {
            try (Liquibase liquibase = new Liquibase(changelogFile.getFileName().toString(), resourceAccessor, prodDb);
                 Writer out = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(outputSqlFile.toFile())), StandardCharsets.UTF_8)) {
                liquibase.update(new Contexts(), new LabelExpression(), out);
            }
        } finally {
            try {
                prodDb.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String safeFileName(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                current.append(c);
                continue;
            }

            if (inBlockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    current.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }

            if (!inSingleQuote && !inDoubleQuote) {
                if (c == '-' && next == '-') {
                    inLineComment = true;
                    current.append(c).append(next);
                    i++;
                    continue;
                }
                if (c == '/' && next == '*') {
                    inBlockComment = true;
                    current.append(c).append(next);
                    i++;
                    continue;
                }
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
                continue;
            }

            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        if (current.length() > 0) {
            statements.add(current.toString());
        }
        return statements;
    }

    private static void ensureUpdateLogTable(Connection connection) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS " + UPDATE_LOG_TABLE + " ("
                + "logid BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "tablename VARCHAR(256) NOT NULL DEFAULT '',"
                + "old_sql TEXT NULL,"
                + "execute_sql TEXT NOT NULL,"
                + "`update` DATETIME NOT NULL,"
                + "status TINYINT NOT NULL,"
                + "message TEXT NULL"
                + ") ENGINE=InnoDB";
        try (Statement st = connection.createStatement()) {
            st.execute(ddl);
        }
    }

    private static void insertUpdateLog(Connection connection,
                                        String tableName,
                                        String oldSql,
                                        String executeSql,
                                        int status,
                                        String message) throws SQLException {
        String safeTable = tableName == null ? "" : tableName;
        String safeOld = oldSql;
        String safeMsg = message;
        String sql = "INSERT INTO " + UPDATE_LOG_TABLE + " (tablename, old_sql, execute_sql, `update`, status, message) "
                + "VALUES (?, ?, ?, NOW(), ?, ?)";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, safeTable);
            ps.setString(2, safeOld);
            ps.setString(3, executeSql);
            ps.setInt(4, status);
            ps.setString(5, safeMsg);
            ps.executeUpdate();
        }
    }

    private static String parseTableName(String statement) {
        Matcher m1 = ALTER_TABLE_PATTERN.matcher(statement);
        if (m1.find()) {
            return m1.group(1);
        }
        Matcher m2 = CREATE_TABLE_PATTERN.matcher(statement);
        if (m2.find()) {
            return m2.group(1);
        }
        Matcher m3 = DROP_TABLE_PATTERN.matcher(statement);
        if (m3.find()) {
            return m3.group(1);
        }
        return "";
    }

    private static String tryGetOldColumnSql(Connection connection, String statement, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }
        Matcher m = COLUMN_PATTERN.matcher(statement);
        if (!m.find()) {
            return null;
        }

        String op = m.group(1).toLowerCase();
        String col1 = m.group(2);

        String targetCol;
        if (op.startsWith("change")) {
            targetCol = col1;
        } else if (op.startsWith("drop")) {
            targetCol = col1;
        } else if (op.startsWith("modify")) {
            targetCol = col1;
        } else {
            return null;
        }

        String query = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA "
                + "FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (var ps = connection.prepareStatement(query)) {
            ps.setString(1, tableName);
            ps.setString(2, targetCol);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String columnType = rs.getString("COLUMN_TYPE");
                String isNullable = rs.getString("IS_NULLABLE");
                String columnDefault = rs.getString("COLUMN_DEFAULT");
                String extra = rs.getString("EXTRA");

                StringBuilder sb = new StringBuilder();
                sb.append("`").append(targetCol).append("` ").append(columnType);
                if ("NO".equalsIgnoreCase(isNullable)) {
                    sb.append(" NOT NULL");
                } else {
                    sb.append(" NULL");
                }
                if (columnDefault != null) {
                    sb.append(" DEFAULT '").append(columnDefault.replace("'", "''")).append("'");
                }
                if (extra != null && !extra.isBlank()) {
                    sb.append(" ").append(extra);
                }
                return sb.toString();
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private static class NoopChangeExecListener implements ChangeExecListener {
        @Override
        public void willRun(ChangeSet changeSet, liquibase.changelog.DatabaseChangeLog databaseChangeLog, Database database, ChangeSet.RunStatus runStatus) {
        }

        @Override
        public void ran(ChangeSet changeSet, liquibase.changelog.DatabaseChangeLog databaseChangeLog, Database database, ChangeSet.ExecType execType) {
        }

        @Override
        public void willRollback(ChangeSet changeSet, liquibase.changelog.DatabaseChangeLog databaseChangeLog, Database database) {
        }

        @Override
        public void rolledBack(ChangeSet changeSet, liquibase.changelog.DatabaseChangeLog databaseChangeLog, Database database) {
        }

        @Override
        public void preconditionFailed(liquibase.exception.PreconditionFailedException exception, liquibase.precondition.core.PreconditionContainer.FailOption failOption) {
        }

        @Override
        public void preconditionErrored(liquibase.exception.PreconditionErrorException exception, liquibase.precondition.core.PreconditionContainer.ErrorOption errorOption) {
        }

        @Override
        public void willRun(liquibase.change.Change change, ChangeSet changeSet, liquibase.changelog.DatabaseChangeLog databaseChangeLog, Database database) {
        }

        @Override
        public void ran(liquibase.change.Change change, ChangeSet changeSet, liquibase.changelog.DatabaseChangeLog databaseChangeLog, Database database) {
        }

        @Override
        public void runFailed(ChangeSet changeSet, liquibase.changelog.DatabaseChangeLog databaseChangeLog, Database database, Exception exception) {
        }

        @Override
        public void rollbackFailed(ChangeSet changeSet, liquibase.changelog.DatabaseChangeLog databaseChangeLog, Database database, Exception exception) {
        }
    }

    public static class BaselineFiles {
        private final Path baselineDir;
        private final Path snapshotFile;
        private final Path changelogFile;
        private final Path baselineSqlFile;

        public BaselineFiles(Path baselineDir, Path snapshotFile, Path changelogFile, Path baselineSqlFile) {
            this.baselineDir = baselineDir;
            this.snapshotFile = snapshotFile;
            this.changelogFile = changelogFile;
            this.baselineSqlFile = baselineSqlFile;
        }

        public Path getBaselineDir() {
            return baselineDir;
        }

        public Path getSnapshotFile() {
            return snapshotFile;
        }

        public Path getChangelogFile() {
            return changelogFile;
        }

        public Path getBaselineSqlFile() {
            return baselineSqlFile;
        }
    }

    public static class DiffFiles {
        private final Path diffDir;
        private final Path changelogFile;
        private final Path diffSqlFile;

        public DiffFiles(Path diffDir, Path changelogFile, Path diffSqlFile) {
            this.diffDir = diffDir;
            this.changelogFile = changelogFile;
            this.diffSqlFile = diffSqlFile;
        }

        public Path getDiffDir() {
            return diffDir;
        }

        public Path getChangelogFile() {
            return changelogFile;
        }

        public Path getDiffSqlFile() {
            return diffSqlFile;
        }
    }

    public static class ApplyResult {
        private final Path applyDir;
        private final Path logsDir;
        private final int total;
        private final int success;
        private final int failed;

        public ApplyResult(Path applyDir, Path logsDir, int total, int success, int failed) {
            this.applyDir = applyDir;
            this.logsDir = logsDir;
            this.total = total;
            this.success = success;
            this.failed = failed;
        }

        public Path getApplyDir() {
            return applyDir;
        }

        public Path getLogsDir() {
            return logsDir;
        }

        public int getTotal() {
            return total;
        }

        public int getSuccess() {
            return success;
        }

        public int getFailed() {
            return failed;
        }
    }

    public static class StandardUpdateFiles {
        private final Path standardDir;
        private final Path snapshotFile;
        private final Path changelogFile;
        private final Path updateSqlFile;

        public StandardUpdateFiles(Path standardDir, Path snapshotFile, Path changelogFile, Path updateSqlFile) {
            this.standardDir = standardDir;
            this.snapshotFile = snapshotFile;
            this.changelogFile = changelogFile;
            this.updateSqlFile = updateSqlFile;
        }

        public Path getStandardDir() {
            return standardDir;
        }

        public Path getSnapshotFile() {
            return snapshotFile;
        }

        public Path getChangelogFile() {
            return changelogFile;
        }

        public Path getUpdateSqlFile() {
            return updateSqlFile;
        }
    }

    public static class DiffSqlFiles {
        private final Path diffDir;
        private final Path changelogFile;
        private final Path diffSqlFile;

        public DiffSqlFiles(Path diffDir, Path changelogFile, Path diffSqlFile) {
            this.diffDir = diffDir;
            this.changelogFile = changelogFile;
            this.diffSqlFile = diffSqlFile;
        }

        public Path getDiffDir() {
            return diffDir;
        }

        public Path getChangelogFile() {
            return changelogFile;
        }

        public Path getDiffSqlFile() {
            return diffSqlFile;
        }
    }

    public static class SqlApplyResult {
        private final Path applyDir;
        private final Path logsDir;
        private final int total;
        private final int success;
        private final int failed;
        private final String logTable;

        public SqlApplyResult(Path applyDir, Path logsDir, int total, int success, int failed, String logTable) {
            this.applyDir = applyDir;
            this.logsDir = logsDir;
            this.total = total;
            this.success = success;
            this.failed = failed;
            this.logTable = logTable;
        }

        public Path getApplyDir() {
            return applyDir;
        }

        public Path getLogsDir() {
            return logsDir;
        }

        public int getTotal() {
            return total;
        }

        public int getSuccess() {
            return success;
        }

        public int getFailed() {
            return failed;
        }

        public String getLogTable() {
            return logTable;
        }
    }
}
