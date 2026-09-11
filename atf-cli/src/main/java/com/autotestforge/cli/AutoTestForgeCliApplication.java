package com.autotestforge.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.Arrays;
import java.util.List;

/**
 * CLI entry point. Usage:
 * <pre>
 *   java -jar atf-cli.jar scan     --project-path /path/to/project
 *   java -jar atf-cli.jar generate --project-path /path/to/project [--llm ollama] [--validate] [--dry-run]
 * </pre>
 * The hexagon is wired by {@code atf-spring}'s auto-configuration.
 */
@SpringBootApplication
public class AutoTestForgeCliApplication implements CommandLineRunner, ExitCodeGenerator {

    private final GenerateCommand generateCommand;
    private final ScanCommand scanCommand;
    private int exitCode;

    public AutoTestForgeCliApplication(GenerateCommand generateCommand, ScanCommand scanCommand) {
        this.generateCommand = generateCommand;
        this.scanCommand = scanCommand;
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(AutoTestForgeCliApplication.class, args)));
    }

    @Override
    public void run(String... args) {
        CommandLine commandLine = new CommandLine(new AtfRootCommand());
        commandLine.addSubcommand(generateCommand);
        commandLine.addSubcommand(scanCommand);
        exitCode = commandLine.execute(withoutSpringProperties(args));
    }

    /**
     * {@code --atf.*}, {@code --spring.*} and {@code --logging.*} arguments are
     * consumed by Spring Boot as configuration overrides; picocli must not see them.
     */
    static String[] withoutSpringProperties(String... args) {
        return Arrays.stream(args)
                .filter(arg -> !SPRING_PROPERTY_PREFIXES.stream().anyMatch(arg::startsWith))
                .toArray(String[]::new);
    }

    private static final List<String> SPRING_PROPERTY_PREFIXES =
            List.of("--atf.", "--spring.", "--logging.", "--server.", "--management.");

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Command(name = "atf",
            mixinStandardHelpOptions = true,
            version = "AutoTestForge 0.2.0",
            description = "AI-powered unit and integration test generator for Java projects.")
    static class AtfRootCommand implements Runnable {

        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }
}
