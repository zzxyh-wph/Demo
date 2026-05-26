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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class LiquibaseDbSyncService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

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

    private void writeDiffChangelog(Database referenceDb, Database prodDb, Path diffChangelog) throws Exception {
        CompareControl.SchemaComparison[] schemaComparisons = new CompareControl.SchemaComparison[]{
                new CompareControl.SchemaComparison(referenceDb.getDefaultSchema(), prodDb.getDefaultSchema())
        };
        DiffOutputControl diffOutputControl = new DiffOutputControl(false, false, false, schemaComparisons);
        CommandLineUtils.doDiffToChangeLog(diffChangelog.toString(), referenceDb, prodDb, diffOutputControl, null, null, schemaComparisons);
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
}
