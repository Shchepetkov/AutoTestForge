package com.autotestforge.core.service;

import com.autotestforge.core.domain.BuildTool;
import com.autotestforge.core.domain.ClassGenerationResult;
import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.DependencyGraph;
import com.autotestforge.core.domain.ExternalContextSnippet;
import com.autotestforge.core.domain.ExternalTestContext;
import com.autotestforge.core.domain.FieldDependency;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationContext;
import com.autotestforge.core.domain.GenerationStatus;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ProgressListener;
import com.autotestforge.core.domain.ScanPreview;
import com.autotestforge.core.domain.ScannedProject;
import com.autotestforge.core.domain.TestFailure;
import com.autotestforge.core.domain.TestGenerationReport;
import com.autotestforge.core.domain.TestGenerationRequest;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.LlmException;
import com.autotestforge.core.exception.TestWriteException;
import com.autotestforge.core.port.out.AiTestGeneratorPort;
import com.autotestforge.core.port.out.BuildToolPort;
import com.autotestforge.core.port.out.ExternalContextPort;
import com.autotestforge.core.port.out.ProjectScannerPort;
import com.autotestforge.core.port.out.TestValidatorPort;
import com.autotestforge.core.port.out.TestWriterPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    @Mock
    private ExternalContextPort externalContextPort;

    @InjectMocks
    private TestGenerationService service;

    private final JavaClassInfo orderService = concreteClass("OrderService");
    private final GeneratedTestFile orderServiceTest =
            new GeneratedTestFile("com.acme", "OrderServiceTest", "class OrderServiceTest {}");

    @BeforeEach
    void defaults() {
        lenient().when(buildToolPort.detect(PROJECT)).thenReturn(BuildTool.MAVEN);
        lenient().when(externalContextPort.fetchContext(any(), any())).thenReturn(ExternalTestContext.empty());
        lenient().when(testWriter.locateExistingTest(any(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("happy path without validation: test is generated and written")
    void generateTests_shouldWriteTest_whenValidationDisabled() {
        scannerReturns(orderService);
        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(orderService, orderServiceTest)).thenReturn(Path.of("written/OrderServiceTest.java"));

        TestGenerationReport report = service.generateTests(request().build());

        assertThat(report.results()).hasSize(1);
        ClassGenerationResult result = report.results().get(0);
        assertThat(result.status()).isEqualTo(GenerationStatus.WRITTEN);
        assertThat(result.testSource()).isEqualTo("class OrderServiceTest {}");
        assertThat(report.succeeded()).isEqualTo(1);
        verify(buildToolPort).ensureTestDependencies(PROJECT, BuildTool.MAVEN);
        verify(validator, never()).runTests(any(), any(), anyString(), any());
        verify(externalContextPort).onRunFinished(any());
    }

    @Test
    @DisplayName("external context and related project types are handed to the LLM generator")
    void generateTests_shouldPassGenerationContextToGenerator() {
        JavaClassInfo repository = new JavaClassInfo("com.acme", "OrderRepository", ClassKind.INTERFACE, false,
                "public interface OrderRepository {}", "", List.of(), List.of(publicMethod()), List.of(), List.of(),
                PROJECT.resolve("OrderRepository.java"), null);
        JavaClassInfo order = new JavaClassInfo("com.acme", "Order", ClassKind.RECORD, false,
                "public record Order(long id) {}", "", List.of(), List.of(), List.of(), List.of(),
                PROJECT.resolve("Order.java"), null);
        JavaClassInfo serviceWithDeps = new JavaClassInfo("com.acme", "OrderService", ClassKind.CLASS, false,
                "public class OrderService {}", "", List.of(),
                List.of(new MethodInfo("find", "java.util.Optional<com.acme.Order>", List.of(), List.of(), "",
                        List.of(), false)),
                List.of(new FieldDependency("repository", "com.acme.OrderRepository", true)),
                List.of(), PROJECT.resolve("OrderService.java"), null);
        DependencyGraph graph = new DependencyGraph();
        graph.addDependency("com.acme.OrderService", "com.acme.OrderRepository");
        when(scanner.scan(PROJECT)).thenReturn(
                new ScannedProject(PROJECT, List.of(serviceWithDeps, repository, order), graph));
        ExternalTestContext context = new ExternalTestContext(List.of(
                new ExternalContextSnippet("confluence", "Order rules", "VIP orders receive expedited handling")));
        when(externalContextPort.fetchContext(eq(serviceWithDeps), any())).thenReturn(context);
        when(testGenerator.generate(eq(serviceWithDeps), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(eq(serviceWithDeps), any())).thenReturn(Path.of("written/OrderServiceTest.java"));

        service.generateTests(request().build());

        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(testGenerator).generate(eq(serviceWithDeps), eq(null), captor.capture());
        assertThat(captor.getValue().externalContext()).isEqualTo(context);
        assertThat(captor.getValue().relatedTypes())
                .extracting(JavaClassInfo::className)
                .containsExactly("OrderRepository", "Order");
    }

    @Test
    @DisplayName("inline snippets from the request are merged with fetched external context")
    void generateTests_shouldMergeInlineSnippets() {
        scannerReturns(orderService);
        ExternalContextSnippet inline = new ExternalContextSnippet("uploaded-file", "zephyr.xml", "TC-1: cancel order");
        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(any(), any())).thenReturn(Path.of("written/OrderServiceTest.java"));

        service.generateTests(request()
                .externalContext(new com.autotestforge.core.domain.ExternalContextRequest(
                        true, null, List.of(), List.of(), List.of(inline)))
                .build());

        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(testGenerator).generate(eq(orderService), any(), captor.capture());
        assertThat(captor.getValue().externalContext().snippets()).containsExactly(inline);
    }

    @Test
    @DisplayName("dry run: nothing is written and dependencies are untouched, but the source is reported")
    void generateTests_shouldNotTouchProject_whenDryRun() {
        scannerReturns(orderService);
        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);

        TestGenerationReport report = service.generateTests(request().dryRun(true).build());

        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.GENERATED);
        assertThat(report.results().get(0).testSource()).contains("class OrderServiceTest");
        verify(testWriter, never()).writeTest(any(), any());
        verify(buildToolPort, never()).ensureTestDependencies(any(), any());
    }

    @Test
    @DisplayName("output directory: tests are written outside the project and never validated")
    void generateTests_shouldWriteToOutputDir_whenConfigured() {
        scannerReturns(orderService);
        Path outputDir = Path.of("/tmp/generated");
        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTestTo(outputDir, PROJECT, orderService, orderServiceTest))
                .thenReturn(outputDir.resolve("src/test/java/com/acme/OrderServiceTest.java"));

        TestGenerationReport report = service.generateTests(
                request().outputDir(outputDir).validate(true).build());

        ClassGenerationResult result = report.results().get(0);
        assertThat(result.status()).isEqualTo(GenerationStatus.GENERATED);
        assertThat(result.writtenPath()).isEqualTo(outputDir.resolve("src/test/java/com/acme/OrderServiceTest.java"));
        verify(testWriter, never()).writeTest(any(), any());
        verify(buildToolPort, never()).ensureTestDependencies(any(), any());
        verify(validator, never()).runTests(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("existing tests are kept and reported as SKIPPED unless overwriting is requested")
    void generateTests_shouldSkipClassesWithExistingTests() {
        scannerReturns(orderService);
        Path existing = PROJECT.resolve("src/test/java/com/acme/OrderServiceTest.java");
        when(testWriter.locateExistingTest(orderService, "OrderServiceTest")).thenReturn(Optional.of(existing));

        TestGenerationReport report = service.generateTests(request().build());

        ClassGenerationResult result = report.results().get(0);
        assertThat(result.status()).isEqualTo(GenerationStatus.SKIPPED);
        assertThat(result.testClassFqn()).isEqualTo("com.acme.OrderServiceTest");
        assertThat(result.writtenPath()).isEqualTo(existing);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        assertThat(report.succeeded()).isZero();
        verify(testGenerator, never()).generate(any(), any(), any());
    }

    @Test
    @DisplayName("overwrite: existing tests are regenerated")
    void generateTests_shouldRegenerate_whenOverwriteRequested() {
        scannerReturns(orderService);
        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(orderService, orderServiceTest)).thenReturn(Path.of("written/OrderServiceTest.java"));

        TestGenerationReport report = service.generateTests(request().overwriteExisting(true).build());

        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.WRITTEN);
        verify(testWriter, never()).locateExistingTest(any(), anyString());
    }

    @Test
    @DisplayName("validation passes on the first attempt")
    void generateTests_shouldReportValidated_whenTestsPassFirstTime() {
        scannerReturns(orderService);
        Path written = Path.of("written/OrderServiceTest.java");
        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(orderService, orderServiceTest)).thenReturn(written);
        when(validator.runTests(PROJECT, BuildTool.MAVEN, "com.acme.OrderServiceTest", written))
                .thenReturn(ValidationResult.success("BUILD SUCCESS"));

        TestGenerationReport report = service.generateTests(request().validate(true).build());

        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.VALIDATED);
        verify(testGenerator, never()).fix(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("self-correction loop: failing test is fixed by the LLM, re-validated and reported to the listener")
    void generateTests_shouldSelfCorrect_whenFirstValidationFails() {
        scannerReturns(orderService);
        GeneratedTestFile fixedTest =
                new GeneratedTestFile("com.acme", "OrderServiceTest", "class OrderServiceTest { /* fixed */ }");
        ValidationResult failure = ValidationResult.failure(
                List.of(new TestFailure("com.acme.OrderServiceTest", "total_shouldFail", "boom", "trace")),
                "BUILD FAILURE");
        RecordingListener listener = new RecordingListener();

        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);
        when(testGenerator.fix(eq(orderService), eq(orderServiceTest), eq(failure), any(), any())).thenReturn(fixedTest);
        when(testWriter.writeTest(eq(orderService), any())).thenReturn(Path.of("written/OrderServiceTest.java"));
        when(validator.runTests(eq(PROJECT), eq(BuildTool.MAVEN), eq("com.acme.OrderServiceTest"), any()))
                .thenReturn(failure)
                .thenReturn(ValidationResult.success("BUILD SUCCESS"));

        TestGenerationReport report = service.generateTests(
                request().validate(true).maxFixAttempts(2).progressListener(listener).build());

        ClassGenerationResult result = report.results().get(0);
        assertThat(result.status()).isEqualTo(GenerationStatus.FIXED_AND_VALIDATED);
        assertThat(result.llmAttempts()).isEqualTo(2);
        assertThat(result.testSource()).contains("/* fixed */");
        assertThat(listener.selfCorrections.get()).isEqualTo(1);
        verify(testWriter, times(2)).writeTest(eq(orderService), any());
    }

    @Test
    @DisplayName("fix attempts are bounded: still-failing test is reported as VALIDATION_FAILED")
    void generateTests_shouldGiveUp_whenFixAttemptsExhausted() {
        scannerReturns(orderService);
        ValidationResult failure = ValidationResult.failure(
                List.of(new TestFailure("com.acme.OrderServiceTest", "total_shouldFail", "boom", "trace")),
                "BUILD FAILURE");

        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);
        when(testGenerator.fix(any(), any(), any(), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(eq(orderService), any())).thenReturn(Path.of("written/OrderServiceTest.java"));
        when(validator.runTests(eq(PROJECT), eq(BuildTool.MAVEN), eq("com.acme.OrderServiceTest"), any()))
                .thenReturn(failure);

        TestGenerationReport report = service.generateTests(request().validate(true).maxFixAttempts(1).build());

        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.VALIDATION_FAILED);
        assertThat(report.results().get(0).errorMessage()).contains("total_shouldFail");
        assertThat(report.failed()).isEqualTo(1);
        verify(testGenerator, times(1)).fix(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("compilation failures without structured report surface the first error line")
    void generateTests_shouldReportCompilationError_whenNoStructuredFailures() {
        scannerReturns(orderService);
        ValidationResult compileFailure = ValidationResult.failure(List.of(),
                "[INFO] Compiling\n[ERROR] OrderServiceTest.java:[12,5] cannot find symbol\n[ERROR] BUILD FAILURE");
        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(eq(orderService), any())).thenReturn(Path.of("written/OrderServiceTest.java"));
        when(validator.runTests(eq(PROJECT), eq(BuildTool.MAVEN), eq("com.acme.OrderServiceTest"), any()))
                .thenReturn(compileFailure);

        TestGenerationReport report = service.generateTests(request().validate(true).maxFixAttempts(0).build());

        assertThat(report.results().get(0).errorMessage()).contains("cannot find symbol");
    }

    @Test
    @DisplayName("a failure on one class never aborts the run for the others")
    void generateTests_shouldContinueWithNextClass_whenOneClassFails() {
        JavaClassInfo broken = concreteClass("BrokenService");
        scannerReturns(broken, orderService);
        when(testGenerator.generate(eq(broken), any(), any())).thenThrow(new LlmException("model unreachable"));
        when(testGenerator.generate(eq(orderService), any(), any())).thenReturn(orderServiceTest);
        when(testWriter.writeTest(orderService, orderServiceTest)).thenReturn(Path.of("written/OrderServiceTest.java"));

        TestGenerationReport report = service.generateTests(request().build());

        assertThat(report.results()).hasSize(2);
        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(report.results().get(0).errorMessage()).contains("model unreachable");
        assertThat(report.results().get(1).status()).isEqualTo(GenerationStatus.WRITTEN);
    }

    @Test
    @DisplayName("unexpected runtime errors are captured per class as well")
    void generateTests_shouldCaptureUnexpectedErrors() {
        scannerReturns(orderService);
        when(testGenerator.generate(eq(orderService), any(), any())).thenThrow(new IllegalStateException("kaboom"));

        TestGenerationReport report = service.generateTests(request().build());

        assertThat(report.results().get(0).status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(report.results().get(0).errorMessage()).contains("Unexpected error: kaboom");
    }

    @Test
    @DisplayName("interfaces, abstract classes, framework config and classes without public methods are not targets")
    void generateTests_shouldSkipUntestableTypes() {
        JavaClassInfo iface = new JavaClassInfo("com.acme", "Repo", ClassKind.INTERFACE, false, "", "",
                List.of(), List.of(publicMethod()), List.of(), List.of(), PROJECT.resolve("Repo.java"), null);
        JavaClassInfo abstractClass = new JavaClassInfo("com.acme", "Base", ClassKind.CLASS, true, "", "",
                List.of(), List.of(publicMethod()), List.of(), List.of(), PROJECT.resolve("Base.java"), null);
        JavaClassInfo noPublicApi = new JavaClassInfo("com.acme", "Internal", ClassKind.CLASS, false, "", "",
                List.of(), List.of(), List.of(), List.of(), PROJECT.resolve("Internal.java"), null);
        JavaClassInfo config = new JavaClassInfo("com.acme", "AppConfig", ClassKind.CLASS, false, "", "",
                List.of("Configuration"), List.of(publicMethod()), List.of(), List.of(),
                PROJECT.resolve("AppConfig.java"), "Configuration");
        JavaClassInfo app = new JavaClassInfo("com.acme", "App", ClassKind.CLASS, false, "", "",
                List.of("SpringBootApplication"), List.of(publicMethod()), List.of(), List.of(),
                PROJECT.resolve("App.java"), null);
        scannerReturns(iface, abstractClass, noPublicApi, config, app);

        TestGenerationReport report = service.generateTests(request().build());

        assertThat(report.results()).isEmpty();
        verify(testGenerator, never()).generate(any(), any(), any());
    }

    @Test
    @DisplayName("class filters accept simple names, fully qualified names and wildcards; excludes win")
    void generateTests_shouldRespectClassFilters() {
        JavaClassInfo calculator = concreteClass("PriceCalculator");
        JavaClassInfo mailer = concreteClass("MailService");
        scannerReturns(orderService, calculator, mailer);
        when(testGenerator.generate(any(), any(), any())).thenAnswer(invocation -> {
            JavaClassInfo target = invocation.getArgument(0);
            return new GeneratedTestFile("com.acme", target.className() + "Test", "class X {}");
        });
        when(testWriter.writeTest(any(), any())).thenReturn(Path.of("written/Test.java"));

        TestGenerationReport report = service.generateTests(request()
                .includedClasses(List.of("*Service", "com.acme.PriceCalculator"))
                .excludedClasses(List.of("MailService"))
                .build());

        assertThat(report.results())
                .extracting(ClassGenerationResult::classFqn)
                .containsExactly("com.acme.OrderService", "com.acme.PriceCalculator");
    }

    @Test
    @DisplayName("parallel mode processes classes concurrently but keeps results in scan order and serializes validation")
    void generateTests_shouldProcessInParallel_whenParallelismGreaterThanOne() throws Exception {
        JavaClassInfo a = concreteClass("AlphaService");
        JavaClassInfo b = concreteClass("BetaService");
        JavaClassInfo c = concreteClass("GammaService");
        scannerReturns(a, b, c);
        CountDownLatch bothStarted = new CountDownLatch(2);
        Set<String> workerThreads = ConcurrentHashMap.newKeySet();
        AtomicInteger concurrentValidations = new AtomicInteger();
        AtomicInteger maxConcurrentValidations = new AtomicInteger();

        when(testGenerator.generate(any(), any(), any())).thenAnswer(invocation -> {
            JavaClassInfo target = invocation.getArgument(0);
            workerThreads.add(Thread.currentThread().getName());
            bothStarted.countDown();
            bothStarted.await(5, TimeUnit.SECONDS);   // proves at least two classes are generated concurrently
            return new GeneratedTestFile("com.acme", target.className() + "Test", "class X {}");
        });
        when(testWriter.writeTest(any(), any())).thenAnswer(invocation -> {
            GeneratedTestFile test = invocation.getArgument(1);
            return Path.of("written/" + test.className() + ".java");
        });
        when(validator.runTests(eq(PROJECT), eq(BuildTool.MAVEN), anyString(), any())).thenAnswer(invocation -> {
            int now = concurrentValidations.incrementAndGet();
            maxConcurrentValidations.accumulateAndGet(now, Math::max);
            Thread.sleep(30);
            concurrentValidations.decrementAndGet();
            return ValidationResult.success("ok");
        });

        TestGenerationReport report = service.generateTests(request().parallelism(3).validate(true).build());

        assertThat(report.results()).extracting(ClassGenerationResult::classFqn)
                .containsExactly("com.acme.AlphaService", "com.acme.BetaService", "com.acme.GammaService");
        assertThat(report.results()).allMatch(result -> result.status() == GenerationStatus.VALIDATED);
        assertThat(workerThreads.size()).isGreaterThanOrEqualTo(2);
        assertThat(maxConcurrentValidations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an interrupted run stops after the class in flight and returns the partial report")
    void generateTests_shouldStopEarly_whenInterrupted() {
        JavaClassInfo second = concreteClass("SecondService");
        scannerReturns(orderService, second);
        when(testGenerator.generate(eq(orderService), any(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return orderServiceTest;
        });
        when(testWriter.writeTest(orderService, orderServiceTest)).thenReturn(Path.of("written/OrderServiceTest.java"));

        TestGenerationReport report;
        try {
            report = service.generateTests(request().build());
        } finally {
            Thread.interrupted();   // clear the flag for the test runner
        }

        assertThat(report.results()).extracting(ClassGenerationResult::classFqn)
                .containsExactly("com.acme.OrderService");
        verify(testGenerator, never()).generate(eq(second), any(), any());
    }

    @Test
    @DisplayName("scan preview lists every type with eligibility, skip reasons and existing tests")
    void previewTargets_shouldDescribeEveryType() {
        JavaClassInfo iface = new JavaClassInfo("com.acme", "Repo", ClassKind.INTERFACE, false, "", "",
                List.of(), List.of(publicMethod()), List.of(), List.of(), PROJECT.resolve("Repo.java"), null);
        JavaClassInfo calculator = concreteClass("PriceCalculator");
        scannerReturns(orderService, iface, calculator);
        Path existing = PROJECT.resolve("src/test/java/com/acme/OrderServiceTest.java");
        when(testWriter.locateExistingTest(orderService, "OrderServiceTest")).thenReturn(Optional.of(existing));

        ScanPreview preview = service.previewTargets(request().excludedClasses(List.of("Price*")).build());

        assertThat(preview.buildTool()).isEqualTo(BuildTool.MAVEN);
        assertThat(preview.classes()).hasSize(3);
        assertThat(preview.eligibleCount()).isEqualTo(1);
        assertThat(preview.withExistingTests()).isEqualTo(1);
        assertThat(preview.classes().get(0).eligible()).isTrue();
        assertThat(preview.classes().get(0).existingTest()).isEqualTo(existing);
        assertThat(preview.classes().get(1).skipReason()).isEqualTo("interface");
        assertThat(preview.classes().get(2).skipReason()).isEqualTo("excluded by filter");
        verify(testGenerator, never()).generate(any(), any(), any());
    }

    @Test
    @DisplayName("scan preview survives a project without a supported build file")
    void previewTargets_shouldTolerateUnknownBuildTool() {
        scannerReturns(orderService);
        when(buildToolPort.detect(PROJECT)).thenThrow(new TestWriteException("no build file"));

        ScanPreview preview = service.previewTargets(request().build());

        assertThat(preview.buildTool()).isNull();
        assertThat(preview.classes()).hasSize(1);
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

    private static final class RecordingListener implements ProgressListener {
        private final AtomicInteger selfCorrections = new AtomicInteger();

        @Override
        public void onSelfCorrection(String classFqn, int round, int maxRounds, int failureCount) {
            selfCorrections.incrementAndGet();
        }
    }
}
