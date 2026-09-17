package com.autotestforge.writer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenPomUpdaterTest {

    @TempDir
    Path projectRoot;

    private final MavenPomUpdater updater = new MavenPomUpdater();

    @Test
    @DisplayName("missing test dependencies are added with explicit versions and test scope")
    void ensureTestDependencies_shouldAddMissingDependenciesWithVersions() throws IOException {
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        updater.ensureTestDependencies(projectRoot);

        String updated = Files.readString(projectRoot.resolve("pom.xml"));
        assertThat(updated)
                .contains("<artifactId>junit-jupiter</artifactId>")
                .contains("<artifactId>mockito-core</artifactId>")
                .contains("<artifactId>mockito-junit-jupiter</artifactId>")
                .contains("<artifactId>assertj-core</artifactId>")
                .contains("<scope>test</scope>")
                .contains("<version>5.10.2</version>")
                .contains("<artifactId>maven-surefire-plugin</artifactId>")
                .contains("<version>3.5.3</version>");

        updater.ensureTestDependencies(projectRoot);
        assertThat(Files.readString(projectRoot.resolve("pom.xml"))).isEqualTo(updated);
    }

    @Test
    @DisplayName("Spring Boot projects get dependencies without versions (managed by the parent BOM)")
    void ensureTestDependencies_shouldOmitVersions_whenSpringBootParentPresent() throws IOException {
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.5.3</version>
                    </parent>
                    <groupId>com.acme</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        updater.ensureTestDependencies(projectRoot);

        String updated = Files.readString(projectRoot.resolve("pom.xml"));
        assertThat(updated)
                .contains("<artifactId>junit-jupiter</artifactId>")
                .doesNotContain("<version>5.10.2</version>")
                .doesNotContain("<artifactId>maven-surefire-plugin</artifactId>");
    }

    @Test
    void shouldPreserveManagedSurefireVersion() throws IOException {
        writePom(pomWithBuild("""
                <pluginManagement><plugins><plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                </plugin></plugins></pluginManagement>
                """));

        updater.ensureTestDependencies(projectRoot);

        String updated = Files.readString(projectRoot.resolve("pom.xml"));
        assertThat(updated).contains("<version>3.2.5</version>")
                .doesNotContain("<version>3.5.3</version>");
        assertThat(updated.split("<artifactId>maven-surefire-plugin</artifactId>", -1)).hasSize(2);
    }

    @Test
    void shouldUpgradeObsoleteRunnerWithoutDiscardingConfiguration() throws IOException {
        writePom(pomWithBuild("""
                <plugins><plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>2.12.4</version>
                    <configuration><argLine>-Xmx512m</argLine></configuration>
                </plugin></plugins>
                """));

        updater.ensureTestDependencies(projectRoot);

        String updated = Files.readString(projectRoot.resolve("pom.xml"));
        assertThat(updated).contains("<version>3.5.3</version>")
                .contains("<argLine>-Xmx512m</argLine>")
                .doesNotContain("<version>2.12.4</version>");
        assertThat(updated.split("<artifactId>maven-surefire-plugin</artifactId>", -1)).hasSize(2);
    }

    @Test
    void shouldPreservePropertyBasedRunnerVersion() throws IOException {
        writePom(pomWithBuild("""
                <plugins><plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>${surefire.version}</version>
                </plugin></plugins>
                """));

        updater.ensureTestDependencies(projectRoot);

        assertThat(Files.readString(projectRoot.resolve("pom.xml")))
                .contains("<version>${surefire.version}</version>")
                .doesNotContain("<version>3.5.3</version>");
    }

    private String pomWithBuild(String build) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId><artifactId>demo</artifactId><version>1.0</version>
                    <build>%s</build>
                </project>
                """.formatted(build);
    }

    @Test
    @DisplayName("already declared dependencies are not duplicated")
    void ensureTestDependencies_shouldNotDuplicateExistingDependencies() throws IOException {
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.9.0</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);

        updater.ensureTestDependencies(projectRoot);

        String updated = Files.readString(projectRoot.resolve("pom.xml"));
        assertThat(updated.split("<artifactId>junit-jupiter</artifactId>", -1)).hasSize(2);
        assertThat(updated).contains("<version>5.9.0</version>");
    }

    private void writePom(String content) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), content);
    }
}
