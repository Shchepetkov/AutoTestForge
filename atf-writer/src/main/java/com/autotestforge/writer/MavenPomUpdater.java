package com.autotestforge.writer;

import com.autotestforge.core.exception.TestWriteException;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Adds the missing test-scope dependencies (JUnit 5, Mockito, AssertJ) to the
 * target project's {@code pom.xml} using the official Maven model API.
 * When the project inherits from {@code spring-boot-starter-parent}, versions
 * are omitted so Spring Boot's dependency management stays in charge.
 */
public class MavenPomUpdater {

    private static final Logger log = LoggerFactory.getLogger(MavenPomUpdater.class);
    private static final String SUREFIRE_ARTIFACT = "maven-surefire-plugin";
    private static final String SUREFIRE_VERSION = "3.5.3";
    private static final Pattern NUMERIC_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\..*)?$");

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

        changed |= ensureJUnitRunner(model);
        if (changed) {
            writeModel(pomFile, model);
        } else {
            log.info("All required test dependencies already declared in {}", pomFile);
        }
    }

    /** Maven's old default Surefire can report success without executing Jupiter tests. */
    private boolean ensureJUnitRunner(Model model) {
        Build build = model.getBuild();
        Plugin declared = build == null ? null : findSurefire(build.getPlugins());
        Plugin managed = build == null || build.getPluginManagement() == null
                ? null : findSurefire(build.getPluginManagement().getPlugins());
        String version = declared != null && declared.getVersion() != null
                ? declared.getVersion() : managed == null ? null : managed.getVersion();
        if (version != null && !knownPreJUnit5Version(version)) {
            // Preserve modern versions and expressions resolved by the project's own properties.
            return false;
        }
        if (version == null && model.getParent() != null) {
            // An external parent may manage Surefire. Do not override corporate/Boot management
            // by guessing its effective version or fetching a parent during a file write.
            return false;
        }
        if (build == null) {
            build = new Build();
            model.setBuild(build);
        }
        if (declared == null) {
            declared = new Plugin();
            declared.setGroupId("org.apache.maven.plugins");
            declared.setArtifactId(SUREFIRE_ARTIFACT);
            build.addPlugin(declared);
        }
        declared.setVersion(SUREFIRE_VERSION);
        return true;
    }

    private Plugin findSurefire(List<Plugin> plugins) {
        return plugins.stream()
                .filter(plugin -> SUREFIRE_ARTIFACT.equals(plugin.getArtifactId())
                        && (plugin.getGroupId() == null
                        || "org.apache.maven.plugins".equals(plugin.getGroupId())))
                .findFirst().orElse(null);
    }

    private boolean knownPreJUnit5Version(String version) {
        Matcher matcher = NUMERIC_VERSION.matcher(version);
        if (!matcher.matches()) {
            return false;
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            return major < 2 || (major == 2 && minor < 22);
        } catch (NumberFormatException ignored) {
            return false;
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
