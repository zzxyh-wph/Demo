package com.example.demo2.dbsync;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/dbsync")
public class DbSyncController {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DbSyncProperties properties;
    private final LiquibaseDbSyncService dbSyncService;

    public DbSyncController(DbSyncProperties properties, LiquibaseDbSyncService dbSyncService) {
        this.properties = properties;
        this.dbSyncService = dbSyncService;
    }

    @PostMapping("/diffTables")
    public ResponseEntity<?> diffTables() throws Exception {
        Path outputDir = outputDir();
        DbConnectionInfo standard = toConn(properties.getStandard());
        DbConnectionInfo production = toConn(properties.getProduction());

        LiquibaseDbSyncService.StandardUpdateFiles updateFiles = dbSyncService.generateStandardUpdateSql(standard, outputDir);
        LiquibaseDbSyncService.DiffSqlFiles diffFiles = dbSyncService.diffTablesToSql(
                updateFiles.getSnapshotFile(),
                production,
                StringUtils.hasText(properties.getDiffTypes()) ? properties.getDiffTypes() : null,
                outputDir
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outputDir", outputDir.toString());
        body.put("updateSql", updateFiles.getUpdateSqlFile().toString());
        body.put("diffChangelog", diffFiles.getChangelogFile().toString());
        body.put("diffSql", diffFiles.getDiffSqlFile().toString());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/updateTables")
    public ResponseEntity<?> updateTables(@RequestParam(name = "approved") boolean approved,
                                          @RequestParam(name = "date", required = false) String date,
                                          @RequestParam(name = "file", required = false) String file) throws Exception {
        if (!approved) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "approved=true is required"));
        }

        Path outputDir = outputDir();
        Path sqlFile = resolveSqlFile(outputDir, date, file);
        if (!Files.exists(sqlFile)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "sql file not found", "file", sqlFile.toString()));
        }

        DbConnectionInfo production = toConn(properties.getProduction());
        LiquibaseDbSyncService.SqlApplyResult result = dbSyncService.applySqlFile(production, sqlFile, outputDir);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sqlFile", sqlFile.toString());
        body.put("applyDir", result.getApplyDir().toString());
        body.put("logsDir", result.getLogsDir().toString());
        body.put("total", result.getTotal());
        body.put("success", result.getSuccess());
        body.put("failed", result.getFailed());
        return ResponseEntity.ok(body);
    }

    private Path resolveSqlFile(Path outputDir, String date, String file) {
        if (StringUtils.hasText(file)) {
            Path resolved = outputDir.resolve(file).normalize().toAbsolutePath();
            if (!resolved.startsWith(outputDir)) {
                throw new IllegalArgumentException("invalid file path");
            }
            return resolved;
        }

        String day = StringUtils.hasText(date) ? date : LocalDate.now().format(DAY);
        Path resolved = outputDir.resolve("diff").resolve(day).resolve("diff-" + day + ".sql").normalize().toAbsolutePath();
        if (!resolved.startsWith(outputDir)) {
            throw new IllegalArgumentException("invalid file path");
        }
        return resolved;
    }

    private Path outputDir() {
        return Path.of(properties.getOutputDir()).toAbsolutePath().normalize();
    }

    private static DbConnectionInfo toConn(DbSyncProperties.Connection c) {
        String password = c.getPassword();
        if (!StringUtils.hasText(password) && StringUtils.hasText(c.getPasswordEnv())) {
            password = System.getenv(c.getPasswordEnv());
        }
        return new DbConnectionInfo(c.getUrl(), c.getUsername(), password, c.getSchema());
    }
}
