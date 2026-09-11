package com.autotestforge.core.service;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.DependencyGraph;
import com.autotestforge.core.domain.FieldDependency;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ParameterInfo;
import com.autotestforge.core.domain.ScannedProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelatedTypesResolverTest {

    private static final Path PROJECT = Path.of("/repo");

    private final JavaClassInfo repository = type("com.acme.repo", "OrderRepository", ClassKind.INTERFACE);
    private final JavaClassInfo order = type("com.acme.model", "Order", ClassKind.RECORD);
    private final JavaClassInfo money = type("com.acme.model", "Money", ClassKind.RECORD);
    private final JavaClassInfo pricingError = type("com.acme.service", "PricingException", ClassKind.CLASS);
    private final JavaClassInfo unrelated = type("com.acme.other", "Unrelated", ClassKind.CLASS);

    @Test
    @DisplayName("collaborators come first, then types mentioned in signatures (generics, params, throws)")
    void resolve_shouldCollectCollaboratorsAndSignatureTypes() {
        JavaClassInfo service = new JavaClassInfo("com.acme.service", "OrderService", ClassKind.CLASS, false,
                "", "", List.of(),
                List.of(new MethodInfo("find", "java.util.Optional<com.acme.model.Order>",
                                List.of(new ParameterInfo("id", "long")), List.of(), "", List.of(), false),
                        new MethodInfo("price", "Money",
                                List.of(new ParameterInfo("order", "Order")), List.of("PricingException"),
                                "", List.of(), false)),
                List.of(new FieldDependency("repository", "com.acme.repo.OrderRepository", true)),
                List.of("com.acme.model.Order", "com.acme.model.Money"),
                PROJECT.resolve("OrderService.java"), null);
        DependencyGraph graph = new DependencyGraph();
        graph.addDependency(service.fullyQualifiedName(), repository.fullyQualifiedName());
        ScannedProject project = new ScannedProject(PROJECT,
                List.of(service, repository, order, money, pricingError, unrelated), graph);

        List<JavaClassInfo> related = RelatedTypesResolver.resolve(service, project, 8);

        assertThat(related).extracting(JavaClassInfo::className)
                .containsExactly("OrderRepository", "Order", "Money", "PricingException");
    }

    @Test
    @DisplayName("the number of related types is capped and the class itself is never included")
    void resolve_shouldCapResults() {
        JavaClassInfo service = new JavaClassInfo("com.acme.service", "OrderService", ClassKind.CLASS, false,
                "", "", List.of(),
                List.of(new MethodInfo("self", "OrderService", List.of(), List.of(), "", List.of(), false),
                        new MethodInfo("a", "com.acme.model.Order", List.of(), List.of(), "", List.of(), false),
                        new MethodInfo("b", "com.acme.model.Money", List.of(), List.of(), "", List.of(), false)),
                List.of(), List.of(), PROJECT.resolve("OrderService.java"), null);
        ScannedProject project = new ScannedProject(PROJECT, List.of(service, order, money), new DependencyGraph());

        assertThat(RelatedTypesResolver.resolve(service, project, 1)).extracting(JavaClassInfo::className)
                .containsExactly("Order");
        assertThat(RelatedTypesResolver.resolve(service, project, 0)).isEmpty();
        assertThat(RelatedTypesResolver.resolve(service, null, 5)).isEmpty();
    }

    @Test
    @DisplayName("ambiguous simple names and JDK types are ignored")
    void resolve_shouldIgnoreAmbiguousAndForeignTypes() {
        JavaClassInfo otherOrder = type("com.acme.legacy", "Order", ClassKind.CLASS);
        JavaClassInfo service = new JavaClassInfo("com.acme.service", "OrderService", ClassKind.CLASS, false,
                "", "", List.of(),
                List.of(new MethodInfo("find", "java.util.List<Order>", List.of(), List.of(), "", List.of(), false)),
                List.of(), List.of(), PROJECT.resolve("OrderService.java"), null);
        ScannedProject project = new ScannedProject(PROJECT, List.of(service, order, otherOrder), new DependencyGraph());

        assertThat(RelatedTypesResolver.resolve(service, project, 8)).isEmpty();
    }

    private static JavaClassInfo type(String pkg, String name, ClassKind kind) {
        return new JavaClassInfo(pkg, name, kind, false, "", "", List.of(), List.of(), List.of(), List.of(),
                PROJECT.resolve(name + ".java"), null);
    }
}
