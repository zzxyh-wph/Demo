package com.example.demo2.dbsync;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Component
public class DbSyncApplicationRunner implements ApplicationRunner {
    private final LiquibaseDbSyncService dbSyncService;

    public DbSyncApplicationRunner(LiquibaseDbSyncService dbSyncService) {
        this.dbSyncService = dbSyncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> nonOptionArgs = args.getNonOptionArgs();
            if (nonOptionArgs.isEmpty() || "help".equalsIgnoreCase(nonOptionArgs.get(0))) {
                printHelp();
                return;
            }

            String command = nonOptionArgs.get(0);
            Path outputDir = Path.of(getOption(args, "outputDir").orElse("./dbsync")).toAbsolutePath().normalize();

            if ("generate".equalsIgnoreCase(command)) {
                DbConnectionInfo source = connFromArgs(args, "url", "username", "password", "passwordEnv", "schema");
                dbSyncService.generateBaseline(source, outputDir);
                return;
            }

            if ("diff".equalsIgnoreCase(command)) {
                Path baselineSnapshot = Path.of(getOption(args, "baselineSnapshot").orElse("./dbsync/baseline/snapshot.json")).toAbsolutePath().normalize();
                DbConnectionInfo prod = connFromArgs(args, "prodUrl", "prodUsername", "prodPassword", "prodPasswordEnv", "prodSchema");
                dbSyncService.diffWithProduction(baselineSnapshot, prod, outputDir);
                return;
            }

            if ("apply".equalsIgnoreCase(command)) {
                Path changelog = Path.of(getOption(args, "changelog").orElseThrow()).toAbsolutePath().normalize();
                DbConnectionInfo prod = connFromArgs(args, "prodUrl", "prodUsername", "prodPassword", "prodPasswordEnv", "prodSchema");
                dbSyncService.applyChangelogIndividually(changelog, prod, outputDir);
                return;
            }

            printHelp();
        } catch (Exception e) {
            throw new DbSyncExitCodeException(1, e);
        }
    }

    private static DbConnectionInfo connFromArgs(ApplicationArguments args,
                                                String urlKey,
                                                String userKey,
                                                String passKey,
                                                String passEnvKey,
                                                String schemaKey) {
        String url = getOption(args, urlKey).orElseThrow();
        String username = getOption(args, userKey).orElseThrow();
        String password = getOption(args, passKey)
                .orElseGet(() -> getOption(args, passEnvKey).map(System::getenv).orElse(null));
        String schema = getOption(args, schemaKey).orElse(null);
        return new DbConnectionInfo(url, username, password, schema);
    }

    private static Optional<String> getOption(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return Optional.empty();
        }
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.get(0));
    }

    private static void printHelp() {
        String msg = String.join("\n",
                "",
                "dbsync commands:",
                "  generate --url=... --username=... [--password=...|--passwordEnv=ENV] [--schema=...] [--outputDir=./dbsync]",
                "  diff --baselineSnapshot=./dbsync/baseline/snapshot.json --prodUrl=... --prodUsername=... [--prodPassword=...|--prodPasswordEnv=ENV] [--prodSchema=...] [--outputDir=./dbsync]",
                "  apply --changelog=./dbsync/diff/<ts>/diff.changelog.yaml --prodUrl=... --prodUsername=... [--prodPassword=...|--prodPasswordEnv=ENV] [--prodSchema=...] [--outputDir=./dbsync]",
                ""
        );
        System.out.println(msg);
    }
}
