package com.autotestforge.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web entry point: REST API ({@code /api/generation}, {@code /api/scan},
 * {@code /api/capabilities}, {@code /api/mcp/tools}) plus a single-page UI
 * served from {@code /}. The hexagon is wired by {@code atf-spring}'s
 * auto-configuration.
 */
@SpringBootApplication
public class AutoTestForgeWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoTestForgeWebApplication.class, args);
    }
}
