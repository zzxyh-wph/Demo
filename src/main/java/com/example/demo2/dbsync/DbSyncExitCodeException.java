package com.example.demo2.dbsync;

import org.springframework.boot.ExitCodeGenerator;

public class DbSyncExitCodeException extends RuntimeException implements ExitCodeGenerator {
    private final int exitCode;

    public DbSyncExitCodeException(int exitCode, Throwable cause) {
        super(cause);
        this.exitCode = exitCode;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
