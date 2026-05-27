package com.example.demo2.dbsync;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dbsync")
public class DbSyncProperties {
    private String outputDir = "./dbsync";
    private Connection standard = new Connection();
    private Connection production = new Connection();
    private String diffTypes = "columns";

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public Connection getStandard() {
        return standard;
    }

    public void setStandard(Connection standard) {
        this.standard = standard;
    }

    public Connection getProduction() {
        return production;
    }

    public void setProduction(Connection production) {
        this.production = production;
    }

    public String getDiffTypes() {
        return diffTypes;
    }

    public void setDiffTypes(String diffTypes) {
        this.diffTypes = diffTypes;
    }

    public static class Connection {
        private String url;
        private String username;
        private String password;
        private String passwordEnv;
        private String schema;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getPasswordEnv() {
            return passwordEnv;
        }

        public void setPasswordEnv(String passwordEnv) {
            this.passwordEnv = passwordEnv;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }
    }
}
