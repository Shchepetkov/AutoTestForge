package com.autotestforge.writer;

import com.autotestforge.core.exception.TestWriteException;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adds the missing test-scope dependencies (JUnit 5, Mockito, AssertJ) to the
 * target project's {@code pom.xml} using the official Maven model API.
 * When the project inherits from {@code spring-boot-starter-parent}, versions
 * are omitted so Spring Boot's dependency management stays in charge.
 */
public class MavenPomUpdater {

    private static final Logger log = LoggerFactory.getLogger(MavenPomUpdater.class);

    public void ensureTestDependencies(Path projectRoot) {
        Path pomFile = projectRoot.resolve("pom.xml");
        Model model = readModel(pomFile);

        Set<String> declared = model.getDependencies().stream()
                .map(dependency -> dependency.getGroupId() + ":" + dependency.getArtifactId())
                .collect(Collectors.toSet());
        boolean versionsManaged = hasSpringBootParent(model);

        boolean changed = false;
        for (TestDependency required : TestDependency.REQUIRED) {
            if (declared.contains(required.groupId() + ":" + required.artifactId())) {
                continue;
            }
            Dependency dependency = new Dependency();
            dependency.setGroupId(required.groupId());
            dependency.setArtifactId(required.artifactId());
            dependency.setScope("test");
            if (!versionsManaged) {
                dependency.setVersion(required.version());
            }
            model.addDependency(dependency);
            changed = true;
            log.info("Adding test dependency {} to {}", required.coordinates(), pomFile);
        }

        if (changed) {
            writeModel(pomFile, model);
        } else {
            log.info("All required test dependencies already declared in {}", pomFile);
        }
    }

    private boolean hasSpringBootParent(Model model) {
        return model.getParent() != null
                && "org.springframework.boot".equals(model.getParent().getGroupId())
                && "spring-boot-starter-parent".equals(model.getParent().getArtifactId());
    }

    private Model readModel(Path pomFile) {
        try (Reader reader = Files.newBufferedReader(pomFile)) {
            return new MavenXpp3Reader().read(reader);
        } catch (Exception e) {
            throw new TestWriteException("Failed to read " + pomFile, e);
        }
    }

    private void writeModel(Path pomFile, Model model) {
        try (Writer writer = Files.newBufferedWriter(pomFile)) {
            new MavenXpp3Writer().write(writer, model);
        } catch (Exception e) {
            throw new TestWriteException("Failed to write " + pomFile, e);
        }
    }
}
