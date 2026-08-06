package com.autotestforge.web;

import com.autotestforge.web.config.AtfProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Web entry point: REST API ({@code /api/generation}) plus a single-page UI
 * served from {@code /}.
 */
@SpringBootApplication
@EnableConfigurationProperties(AtfProperties.class)
public class AutoTestForgeWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoTestForgeWebApplication.class, args);
    }
}
