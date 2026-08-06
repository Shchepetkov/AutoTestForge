package com.autotestforge.mcp;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.ParameterInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpQueryTemplateTest {

    @Test
    @DisplayName("template renderer expands class and method placeholders")
    void render_shouldExpandKnownPlaceholders() {
        JavaClassInfo classInfo = new JavaClassInfo("com.acme", "OrderService", ClassKind.CLASS, false,
                "public class OrderService {}", "", List.of(),
                List.of(new MethodInfo("pay", "Receipt", List.of(new ParameterInfo("orderId", "long")),
                        List.of(), "", List.of(), false)),
                List.of(), List.of(), Path.of("OrderService.java"), null);

        String query = McpQueryTemplate.render(
                "Find ${className} rules for ${fullyQualifiedName} in ${projectPath}; methods=${methods}",
                classInfo,
                Path.of("/repo"));

        assertThat(query)
                .contains("OrderService rules")
                .contains("com.acme.OrderService")
                .contains("/repo")
                .contains("Receipt pay(long orderId)");
    }
}
