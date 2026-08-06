package com.autotestforge.cli;

import com.autotestforge.cli.config.AtfProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI entry point. Usage:
 * <pre>
 *   java -jar atf-cli.jar generate --project-path /path/to/project [--llm ollama] [--validate] [--dry-run]
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(AtfProperties.class)
public class AutoTestForgeCliApplication implements CommandLineRunner, ExitCodeGenerator {

    private final GenerateCommand generateCommand;
    private int exitCode;

    public AutoTestForgeCliApplication(GenerateCommand generateCommand) {
        this.generateCommand = generateCommand;
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(AutoTestForgeCliApplication.class, args)));
    }

    @Override
    public void run(String... args) {
        CommandLine commandLine = new CommandLine(new AtfRootCommand());
        commandLine.addSubcommand(generateCommand);
        exitCode = commandLine.execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Command(name = "atf",
            mixinStandardHelpOptions = true,
            version = "AutoTestForge 0.1.0",
            description = "AI-powered unit and integration test generator for Java projects.")
    static class AtfRootCommand implements Runnable {

        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }
}
