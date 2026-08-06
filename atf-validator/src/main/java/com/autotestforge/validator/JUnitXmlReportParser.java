package com.autotestforge.validator;

import com.autotestforge.core.domain.TestFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Parses JUnit XML reports produced by Maven Surefire
 * ({@code target/surefire-reports}) and Gradle ({@code build/test-results}).
 * Both tools emit the same {@code TEST-<class>.xml} schema, so one parser
 * covers Maven and Gradle targets.
 */
public class JUnitXmlReportParser {

    private static final Logger log = LoggerFactory.getLogger(JUnitXmlReportParser.class);

    private static final int MAX_DETAIL_CHARS = 2_000;

    /** Finds and parses the report of {@code testClassFqn} anywhere under the project. */
    public List<TestFailure> parseFailures(Path projectRoot, String testClassFqn) {
        List<TestFailure> failures = new ArrayList<>();
        for (Path report : findReports(projectRoot, testClassFqn)) {
            failures.addAll(parseReport(report));
        }
        return failures;
    }

    private List<Path> findReports(Path projectRoot, String testClassFqn) {
        String reportName = "TEST-" + testClassFqn + ".xml";
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(reportName))
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to search for test reports under {}", projectRoot, e);
            return List.of();
        }
    }

    private List<TestFailure> parseReport(Path report) {
        List<TestFailure> failures = new ArrayList<>();
        try {
            Document document = secureDocumentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(report.toFile());
            NodeList testCases = document.getElementsByTagName("testcase");
            for (int i = 0; i < testCases.getLength(); i++) {
                Element testCase = (Element) testCases.item(i);
                extractProblem(testCase).ifPresent(failures::add);
            }
        } catch (Exception e) {
            log.warn("Failed to parse test report {}", report, e);
        }
        return failures;
    }

    private java.util.Optional<TestFailure> extractProblem(Element testCase) {
        Element problem = firstChild(testCase, "failure")
                .or(() -> firstChild(testCase, "error"))
                .orElse(null);
        if (problem == null) {
            return java.util.Optional.empty();
        }
        String detail = problem.getTextContent();
        if (detail != null && detail.length() > MAX_DETAIL_CHARS) {
            detail = detail.substring(0, MAX_DETAIL_CHARS) + "... (truncated)";
        }
        return java.util.Optional.of(new TestFailure(
                testCase.getAttribute("classname"),
                testCase.getAttribute("name"),
                problem.getAttribute("message"),
                detail));
    }

    private java.util.Optional<Element> firstChild(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        return children.getLength() == 0
                ? java.util.Optional.empty()
                : java.util.Optional.of((Element) children.item(0));
    }

    private DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
