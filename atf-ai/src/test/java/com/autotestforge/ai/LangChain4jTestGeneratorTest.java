package com.autotestforge.ai;

import com.autotestforge.core.domain.ClassKind;
import com.autotestforge.core.domain.GeneratedTestFile;
import com.autotestforge.core.domain.GenerationContext;
import com.autotestforge.core.domain.JavaClassInfo;
import com.autotestforge.core.domain.MethodInfo;
import com.autotestforge.core.domain.TestFailure;
import com.autotestforge.core.domain.ValidationResult;
import com.autotestforge.core.exception.LlmException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jTestGeneratorTest {

    private static final String VALID_TEST = """
            ```java
            package com.acme;

            import org.junit.jupiter.api.Test;

            class OrderServiceTest {
                @Test
                void pay_shouldWork() {}
            }
            ```
            """;

    private final JavaClassInfo orderService = new JavaClassInfo("com.acme", "OrderService", ClassKind.CLASS, false,
            "public class OrderService {}", "", List.of(),
            List.of(new MethodInfo("pay", "void", List.of(), List.of(), "", List.of(), false)),
            List.of(), List.of(), Path.of("OrderService.java"), null);

    @Test
    @DisplayName("system prompt plus user prompt are sent and the answer is parsed and normalized")
    void generate_shouldSendSystemAndUserMessages() {
        ScriptedChatModel model = new ScriptedChatModel(() -> VALID_TEST.replace("com.acme;", "com.other;"));
        LangChain4jTestGenerator generator = generator(model);

        GeneratedTestFile test = generator.generate(orderService, "openai", GenerationContext.empty());

        assertThat(test.packageName()).isEqualTo("com.acme");
        assertThat(test.className()).isEqualTo("OrderServiceTest");
        assertThat(model.requests).hasSize(1);
        List<ChatMessage> messages = model.requests.get(0);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(1)).singleText()).contains("public class OrderService");
    }

    @Test
    @DisplayName("an answer without Java is sent back to the model with a correction request")
    void generate_shouldRetryConversationally_whenAnswerIsNotJava() {
        ScriptedChatModel model = new ScriptedChatModel(
                () -> "I cannot help with that.",
                () -> VALID_TEST);
        LangChain4jTestGenerator generator = generator(model);

        GeneratedTestFile test = generator.generate(orderService, null, GenerationContext.empty());

        assertThat(test.className()).isEqualTo("OrderServiceTest");
        assertThat(model.requests).hasSize(2);
        List<ChatMessage> secondRequest = model.requests.get(1);
        assertThat(secondRequest).hasSize(4);
        assertThat(secondRequest.get(2)).isInstanceOf(AiMessage.class);
        assertThat(((UserMessage) secondRequest.get(3)).singleText()).contains("EXACTLY ONE fenced");
    }

    @Test
    @DisplayName("persistently unusable answers fail the class with a descriptive error")
    void generate_shouldGiveUp_afterFormatRetriesExhausted() {
        ScriptedChatModel model = new ScriptedChatModel(() -> "nope");

        assertThatThrownBy(() -> generator(model).generate(orderService, null, GenerationContext.empty()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("did not return a usable test class");
        assertThat(model.requests).hasSize(LangChain4jTestGenerator.MAX_FORMAT_RETRIES + 1);
    }

    @Test
    @DisplayName("transport failures are retried with backoff before giving up")
    void generate_shouldRetryTransportErrors() {
        ScriptedChatModel model = new ScriptedChatModel(
                () -> {
                    throw new RuntimeException("connection refused");
                },
                () -> VALID_TEST);

        GeneratedTestFile test = generator(model).generate(orderService, null, GenerationContext.empty());

        assertThat(test.className()).isEqualTo("OrderServiceTest");
        assertThat(model.requests).hasSize(2);
    }

    @Test
    @DisplayName("an unreachable model fails with LlmException after maxRetries attempts")
    void generate_shouldThrow_whenModelUnreachable() {
        ScriptedChatModel model = new ScriptedChatModel(() -> {
            throw new RuntimeException("timeout");
        });

        assertThatThrownBy(() -> generator(model).generate(orderService, null, GenerationContext.empty()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("unreachable after 3 attempts")
                .hasMessageContaining("timeout");
        assertThat(model.requests).hasSize(3);
    }

    @Test
    @DisplayName("fix requests carry the previous test and the failures")
    void fix_shouldSendPreviousTestAndFailures() {
        ScriptedChatModel model = new ScriptedChatModel(() -> VALID_TEST);
        GeneratedTestFile previous = new GeneratedTestFile("com.acme", "OrderServiceTest", "class OrderServiceTest { broken }");
        ValidationResult failure = ValidationResult.failure(
                List.of(new TestFailure("com.acme.OrderServiceTest", "pay_shouldWork", "expected 1", "trace")), "log");

        generator(model).fix(orderService, previous, failure, null, GenerationContext.empty());

        String prompt = ((UserMessage) model.requests.get(0).get(1)).singleText();
        assertThat(prompt).contains("class OrderServiceTest { broken }").contains("expected 1");
    }

    private static LangChain4jTestGenerator generator(ChatModel model) {
        return new LangChain4jTestGenerator(model, new TestPromptBuilder(), new LlmResponseParser(),
                new GeneratedTestNormalizer(), 3, 0, true);
    }

    /** Replays scripted answers (the last one repeats) and records every request. */
    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<Supplier<String>> answers = new ArrayDeque<>();
        private final List<List<ChatMessage>> requests = new ArrayList<>();

        @SafeVarargs
        ScriptedChatModel(Supplier<String>... scripted) {
            answers.addAll(List.of(scripted));
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(List.copyOf(request.messages()));
            Supplier<String> answer = answers.size() > 1 ? answers.pollFirst() : answers.peekFirst();
            return ChatResponse.builder().aiMessage(AiMessage.from(answer.get())).build();
        }
    }
}
