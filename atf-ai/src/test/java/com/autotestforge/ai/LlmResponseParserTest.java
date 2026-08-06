package com.autotestforge.ai;

import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.exception.LlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmResponseParserTest {

    private final LlmResponseParser parser = new LlmResponseParser();

    @Test
    @DisplayName("extracts the test class from a fenced java block with surrounding prose")
    void parse_shouldExtractClass_whenResponseHasFencedBlock() {
        String response = """
                Sure! Here is the test class you asked for:

                ```java
                package com.acme;

                class OrderServiceTest {
                }
                ```

                Let me know if you need anything else.
                """;

        GeneratedTestFile test = parser.parse(response);

        assertThat(test.packageName()).isEqualTo("com.acme");
        assertThat(test.className()).isEqualTo("OrderServiceTest");
        assertThat(test.fullyQualifiedName()).isEqualTo("com.acme.OrderServiceTest");
        assertThat(test.sourceCode()).contains("class OrderServiceTest");
    }

    @Test
    @DisplayName("accepts bare code without markdown fences")
    void parse_shouldExtractClass_whenResponseIsBareCode() {
        String response = """
                package com.acme;

                class PriceCalculatorTest {
                }
                """;

        GeneratedTestFile test = parser.parse(response);

        assertThat(test.className()).isEqualTo("PriceCalculatorTest");
    }

    @Test
    @DisplayName("skips non-java fenced blocks and picks the valid one")
    void parse_shouldSkipInvalidBlocks_whenMultipleBlocksPresent() {
        String response = """
                First run this command:

                ```
                mvn test
                ```

                Then the test class:

                ```java
                class StringUtilsTest {
                }
                ```
                """;

        GeneratedTestFile test = parser.parse(response);

        assertThat(test.className()).isEqualTo("StringUtilsTest");
        assertThat(test.packageName()).isEmpty();
    }

    @Test
    @DisplayName("rejects responses that contain no valid Java")
    void parse_shouldThrow_whenResponseIsNotJava() {
        assertThatThrownBy(() -> parser.parse("I am sorry, I cannot help with that."))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("does not contain a syntactically valid Java class");
    }

    @Test
    @DisplayName("rejects empty responses")
    void parse_shouldThrow_whenResponseIsEmpty() {
        assertThatThrownBy(() -> parser.parse("  "))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("empty");
    }
}
