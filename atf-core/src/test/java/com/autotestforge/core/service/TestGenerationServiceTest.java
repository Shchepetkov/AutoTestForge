package com.autotestforge.core.service;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.DependencyGraph;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationStatus;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ScannedProject;
import com.autotestforge.core.domain.TestFailure;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.LlmException;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.core.port.out.BuildToolPort;
import com.autotestforge.core.port.out.ProjectScannerPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import com.autotestforge.core.port.out.TestWriterPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestGenerationServiceTest {

    private static final Path PROJECT = Path.of("/tmp/demo-project");

    @Mock
    private ProjectScannerPort scanner;
    @Mock
    private AiTestGeneratorPort testGenerator;
    @Mock
    private TestWriterPort testWriter;
    @Mock
    private BuildToolPort buildToolPort;
    @Mock
    private TestValidatorPort validator;

    @InjectMocks
    private TestGenerationService service;

    private final JavaClassInfo orderService = concreteClass("OrderService");
    private final GeneratedTestFile orderServiceTest =
            new GeneratedTestFile("com.acme", "OrderServiceTest", "class OrderServiceTest {}");

    @BeforeEach
    void detectMaven() {
        when(buildToolPort.detect(PROJECT)).thenReturn(BuildTool.MAVEN);
    }

    @Test
    @DisplayName("happy path without validation: test is generated and written")
    void generateTests_shouldWriteTest_whenValidationDisabled() {
        scannerReturns(orderService);
        when(testGenerator.generate(eq(orderService), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(orderService, orderServiceTest)).thenReturn(Path.of("written/OrderServiceTest.java"));

        TestGenerationReport report = service.generateTests(request().build());

        assertThat(report.results()).hasSize(1);
        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.WRITTEN);
        assertThat(report.succeeded()).isEqualTo(1);
        verify(buildToolPort).ensureTestDependencies(PROJECT, BuildTool.MAVEN);
        verify(validator, never()).runTests(any(), any(), anyString());
    }

    @Test
    @DisplayName("dry run: nothing is written and dependencies are untouched")
    void generateTests_shouldNotTouchProject_whenDryRun() {
        scannerReturns(orderService);
        when(testGenerator.generate(eq(orderService), any())).thenReturn(orderServiceTest);

        TestGenerationReport report = service.generateTests(request().dryRun(true).build());

        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.GENERATED);
        verify(testWriter, never()).writeTest(any(), any());
        verify(buildToolPort, never()).ensureTestDependencies(any(), any());
    }

    @Test
    @DisplayName("validation passes on the first attempt")
    void generateTests_shouldReportValidated_whenTestsPassFirstTime() {
        scannerReturns(orderService);
        when(testGenerator.generate(eq(orderService), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(orderService, orderServiceTest)).thenReturn(Path.of("written/OrderServiceTest.java"));
        when(validator.runTests(PROJECT, BuildTool.MAVEN, "com.acme.OrderServiceTest"))
                .thenReturn(ValidationResult.success("BUILD SUCCESS"));

        TestGenerationReport report = service.generateTests(request().validate(true).build());

        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.VALIDATED);
        verify(testGenerator, never()).fix(any(), any(), any(), any());
    }

    @Test
    @DisplayName("self-correction loop: failing test is fixed by the LLM and re-validated")
    void generateTests_shouldSelfCorrect_whenFirstValidationFails() {
        scannerReturns(orderService);
        GeneratedTestFile fixedTest =
                new GeneratedTestFile("com.acme", "OrderServiceTest", "class OrderServiceTest { /* fixed */ }");
        ValidationResult failure = ValidationResult.failure(
                List.of(new TestFailure("com.acme.OrderServiceTest", "total_shouldFail", "boom", "trace")),
                "BUILD FAILURE");

        when(testGenerator.generate(eq(orderService), any())).thenReturn(orderServiceTest);
        when(testGenerator.fix(eq(orderService), eq(orderServiceTest), eq(failure), any())).thenReturn(fixedTest);
        when(testWriter.writeTest(eq(orderService), any())).thenReturn(Path.of("written/OrderServiceTest.java"));
        when(validator.runTests(PROJECT, BuildTool.MAVEN, "com.acme.OrderServiceTest"))
                .thenReturn(failure)
                .thenReturn(ValidationResult.success("BUILD SUCCESS"));

        TestGenerationReport report = service.generateTests(request().validate(true).maxFixAttempts(2).build());

        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.FIXED_AND_VALIDATED);
        assertThat(report.results().get(0).llmAttempts()).isEqualTo(2);
        verify(testWriter, times(2)).writeTest(eq(orderService), any());
    }

    @Test
    @DisplayName("fix attempts are bounded: still-failing test is reported as VALIDATION_FAILED")
    void generateTests_shouldGiveUp_whenFixAttemptsExhausted() {
        scannerReturns(orderService);
        ValidationResult failure = ValidationResult.failure(
                List.of(new TestFailure("com.acme.OrderServiceTest", "total_shouldFail", "boom", "trace")),
                "BUILD FAILURE");

        when(testGenerator.generate(eq(orderService), any())).thenReturn(orderServiceTest);
        when(testGenerator.fix(any(), any(), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(eq(orderService), any())).thenReturn(Path.of("written/OrderServiceTest.java"));
        when(validator.runTests(PROJECT, BuildTool.MAVEN, "com.acme.OrderServiceTest")).thenReturn(failure);

        TestGenerationReport report = service.generateTests(request().validate(true).maxFixAttempts(1).build());

        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.VALIDATION_FAILED);
        assertThat(report.failed()).isEqualTo(1);
        verify(testGenerator, times(1)).fix(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failure on one class never aborts the run for the others")
    void generateTests_shouldContinueWithNextClass_whenOneClassFails() {
        JavaClassInfo broken = concreteClass("BrokenService");
        scannerReturns(broken, orderService);
        when(testGenerator.generate(eq(broken), any())).thenThrow(new LlmException("model unreachable"));
        when(testGenerator.generate(eq(orderService), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(orderService, orderServiceTest)).thenReturn(Path.of("written/OrderServiceTest.java"));

        TestGenerationReport report = service.generateTests(request().build());

        assertThat(report.results()).hasSize(2);
        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(report.results().get(0).errorMessage()).contains("model unreachable");
        assertThat(report.results().get(1).status()).isEqualTo(GenerationStatus.WRITTEN);
    }

    @Test
    @DisplayName("interfaces, abstract classes and classes without public methods are not test targets")
    void generateTests_shouldSkipUntestableTypes() {
        JavaClassInfo iface = new JavaClassInfo("com.acme", "Repo", ClassKind.INTERFACE, false, "", "",
                List.of(), List.of(publicMethod()), List.of(), List.of(), PROJECT.resolve("Repo.java"), null);
        JavaClassInfo abstractClass = new JavaClassInfo("com.acme", "Base", ClassKind.CLASS, true, "", "",
                List.of(), List.of(publicMethod()), List.of(), List.of(), PROJECT.resolve("Base.java"), null);
        JavaClassInfo noPublicApi = new JavaClassInfo("com.acme", "Internal", ClassKind.CLASS, false, "", "",
                List.of(), List.of(), List.of(), List.of(), PROJECT.resolve("Internal.java"), null);
        scannerReturns(iface, abstractClass, noPublicApi);

        TestGenerationReport report = service.generateTests(request().build());

        assertThat(report.results()).isEmpty();
        verify(testGenerator, never()).generate(any(), any());
    }

    @Test
    @DisplayName("--classes filter matches simple and fully qualified names")
    void generateTests_shouldRespectClassFilter() {
        JavaClassInfo other = concreteClass("PriceCalculator");
        scannerReturns(orderService, other);
        when(testGenerator.generate(eq(other), any()))
                .thenReturn(new GeneratedTestFile("com.acme", "PriceCalculatorTest", "class PriceCalculatorTest {}"));
        when(testWriter.writeTest(eq(other), any())).thenReturn(Path.of("written/PriceCalculatorTest.java"));

        TestGenerationReport report = service.generateTests(
                request().includedClasses(List.of("PriceCalculator")).build());

        assertThat(report.results()).hasSize(1);
        assertThat(report.results().get(0).classFqn()).isEqualTo("com.acme.PriceCalculator");
    }

    private void scannerReturns(JavaClassInfo... classes) {
        when(scanner.scan(PROJECT))
                .thenReturn(new ScannedProject(PROJECT, List.of(classes), new DependencyGraph()));
    }

    private static TestGenerationRequest.Builder request() {
        return TestGenerationRequest.builder(PROJECT);
    }

    private static JavaClassInfo concreteClass(String name) {
        return new JavaClassInfo("com.acme", name, ClassKind.CLASS, false,
                "public class " + name + " {}", "", List.of(), List.of(publicMethod()),
                List.of(), List.of(), PROJECT.resolve(name + ".java"), null);
    }

    private static MethodInfo publicMethod() {
        return new MethodInfo("doWork", "void", List.of(), List.of(), "", List.of(), false);
    }
}
