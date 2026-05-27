package com.example.demo2.dbsync;

public class DbConnectionInfo {
    private final String url;
    private final String username;
    private final String password;
    private final String defaultSchema;

    public DbConnectionInfo(String url, String username, String password, String defaultSchema) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.defaultSchema = defaultSchema;
    }

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getDefaultSchema() {
        return defaultSchema;
    }
}
