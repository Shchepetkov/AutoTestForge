package com.autotestforge.validator;

import com.autotestforge.core.domain.TestFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JUnitXmlReportParserTest {

    @TempDir
    Path projectRoot;

    private final JUnitXmlReportParser parser = new JUnitXmlReportParser();

    @Test
    @DisplayName("failures and errors are extracted from a surefire report")
    void parseFailures_shouldExtractFailuresAndErrors() throws IOException {
        writeReport("target/surefire-reports/TEST-com.acme.OrderServiceTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.OrderServiceTest" tests="3" failures="1" errors="1">
                    <testcase classname="com.acme.OrderServiceTest" name="find_shouldReturnOrder_whenIdIsValid">
                        <failure message="expected: 42 but was: 0" type="AssertionFailedError">stack trace here</failure>
                    </testcase>
                    <testcase classname="com.acme.OrderServiceTest" name="find_shouldThrow_whenIdIsNegative">
                        <error message="NullPointerException" type="java.lang.NullPointerException">npe trace</error>
                    </testcase>
                    <testcase classname="com.acme.OrderServiceTest" name="find_shouldPass"/>
                </testsuite>
                """);

        List<TestFailure> failures = parser.parseFailures(projectRoot, "com.acme.OrderServiceTest");

        assertThat(failures).hasSize(2);
        assertThat(failures.get(0).testMethod()).isEqualTo("find_shouldReturnOrder_whenIdIsValid");
        assertThat(failures.get(0).message()).contains("expected: 42");
        assertThat(failures.get(0).detail()).contains("stack trace here");
        assertThat(failures.get(1).message()).contains("NullPointerException");
    }

    @Test
    @DisplayName("Gradle test-results reports are found as well")
    void parseFailures_shouldFindGradleReports() throws IOException {
        writeReport("build/test-results/test/TEST-com.acme.PriceCalculatorTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.PriceCalculatorTest" tests="1" failures="1">
                    <testcase classname="com.acme.PriceCalculatorTest" name="total_shouldSum">
                        <failure message="boom">trace</failure>
                    </testcase>
                </testsuite>
                """);

        List<TestFailure> failures = parser.parseFailures(projectRoot, "com.acme.PriceCalculatorTest");

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).testClass()).isEqualTo("com.acme.PriceCalculatorTest");
    }

    @Test
    @DisplayName("missing reports produce an empty failure list, not an exception")
    void parseFailures_shouldReturnEmptyList_whenNoReportExists() {
        assertThat(parser.parseFailures(projectRoot, "com.acme.MissingTest")).isEmpty();
    }

    private void writeReport(String relativePath, String content) throws IOException {
        Path report = projectRoot.resolve(relativePath);
        Files.createDirectories(report.getParent());
        Files.writeString(report, content);
    }
}
