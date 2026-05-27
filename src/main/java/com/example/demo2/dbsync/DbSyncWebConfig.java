package com.example.demo2.dbsync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@EnableConfigurationProperties(DbSyncProperties.class)
@Configuration
public class DbSyncWebConfig {
}
