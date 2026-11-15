package com.studentprep.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmStructuringServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void structureChunk_ParsesMarkdownFencedJsonArray() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmStructuringService service = new LlmStructuringService(
                builder,
                objectMapper,
                "test-gemini-key",
                "gemini-3.5-flash-lite"
        );

        String fencedJson = """
                ```json
                [
                  {
                    "type": "MCQ_LATEX",
                    "text": "Evaluate $\\\\frac{1}{2} + \\\\frac{1}{3}$",
                    "options": {"A": "5/6", "B": "2/5"},
                    "correctOption": "A",
                    "assets": [],
                    "shared_context": "",
                    "is_follow_up": false,
                    "questionNumber": "1"
                  }
                ]
                ```
                """;
        String geminiEnvelope = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", fencedJson)))))
        ));

        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-gemini-key"))
                .andRespond(withSuccess(geminiEnvelope, MediaType.APPLICATION_JSON));

        JsonNode result = service.structureChunk("1. Evaluate 1/2 + 1/3", null);

        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("questionNumber").asText()).isEqualTo("1");
        assertThat(result.get(0).path("is_follow_up").asBoolean()).isFalse();
        server.verify();
    }

    @Test
    void structureChunk_SalvagesTruncatedArrayWithTrailingQuestionNumberAndFollowUpKeys() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmStructuringService service = new LlmStructuringService(
                builder,
                objectMapper,
                "test-gemini-key",
                "gemini-3.5-flash-lite"
        );

        String truncatedOutput = """
                ```json
                [
                  {
                    "type": "MCQ",
                    "text": "Passage question",
                    "options": {"A": "Alpha", "B": "Beta"},
                    "correctOption": "A",
                    "assets": [],
                    "shared_context": "Long passage text.",
                    "is_follow_up": false,
                    "questionNumber": "1"
                  },
                  {
                    "type": "MCQ",
                    "text": "Follow-up question",
                    "options": {"A": "Gamma", "B": "Delta"},
                    "correctOption": "B",
                    "shared_context": "",
                    "questionNumber": "2",
                    "is_follow_up": true
                  },
                  {
                    "type": "MCQ",
                    "text": "Truncated question mid-stream",
                    "options": {"A": "Halfway
                """;
        String geminiEnvelope = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", truncatedOutput)))))
        ));

        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(geminiEnvelope, MediaType.APPLICATION_JSON));

        JsonNode result = service.structureChunk("markdown", null);

        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(2);
        assertThat(result.get(0).path("questionNumber").asText()).isEqualTo("1");
        assertThat(result.get(0).path("is_follow_up").asBoolean()).isFalse();
        assertThat(result.get(1).path("questionNumber").asText()).isEqualTo("2");
        assertThat(result.get(1).path("is_follow_up").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    void salvageTruncatedArray_HandlesNestedLatexBracesEscapesMissingFollowUpAndTrailingBracket() throws Exception {
        LlmStructuringService service = new LlmStructuringService(
                RestClient.builder(),
                objectMapper,
                "test-gemini-key",
                "gemini-3.5-flash-lite"
        );

        String rawTruncatedEndingWithBracket = """
                Here are items [1]:
                [
                  {
                    "type": "MCQ_LATEX",
                    "prompt": "Compute $\\\\frac{1}{2} + \\"{x}\\"$ with \\\\\\\\ backslash",
                    "options": {"A": "$\\\\{1\\\\}$", "B": "$2$"},
                    "questionNumber": "10"
                  },
                  {
                    "type": "MCQ",
                    "text": "Truncated object ending in empty array",
                    "assets": []
                """;

        JsonNode salvaged = service.parseOrSalvageArray(rawTruncatedEndingWithBracket);

        assertThat(salvaged.isArray()).isTrue();
        assertThat(salvaged).hasSize(1);
        assertThat(salvaged.get(0).path("questionNumber").asText()).isEqualTo("10");
        assertThat(salvaged.get(0).path("is_follow_up").isMissingNode()).isTrue();
        assertThat(salvaged.get(0).path("options").path("A").asText()).isEqualTo("$\\{1\\}$");
    }

    @Test
    void salvageTruncatedArray_ReturnsEmptyArrayWhenNoCompleteObjectExists() {
        LlmStructuringService service = new LlmStructuringService(
                RestClient.builder(),
                objectMapper,
                "test-gemini-key",
                "gemini-3.5-flash-lite"
        );

        String salvaged = service.salvageTruncatedArray("[{\"type\":\"MCQ\",\"text\":\"Incomplete");
        assertThat(salvaged).isEqualTo("[]");
    }

    @Test
    void parseOrSalvageArray_ThrowsWhenMalformedOutputSalvagesZeroObjects() {
        LlmStructuringService service = new LlmStructuringService(
                RestClient.builder(),
                objectMapper,
                "test-gemini-key",
                "gemini-3.5-flash-lite"
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.parseOrSalvageArray("[{\"type\":\"MCQ\",\"text\":\"Incomplete")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 salvageable question objects");
    }

    @Test
    void parseOrSalvageArray_AllowsExplicitEmptyJsonArray() throws Exception {
        LlmStructuringService service = new LlmStructuringService(
                RestClient.builder(),
                objectMapper,
                "test-gemini-key",
                "gemini-3.5-flash-lite"
        );

        JsonNode parsed = service.parseOrSalvageArray("[]");
        assertThat(parsed.isArray()).isTrue();
        assertThat(parsed).isEmpty();
    }

    @Test
    void structureChunk_SendsStructuredOutputSchemaAndSkipsThoughtParts() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();

        String geminiResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "thought": true, "text": "Thinking about biology questions..." },
                          { "text": "[{\\"type\\":\\"MCQ\\",\\"text\\":\\"What is osmosis?\\",\\"options\\":{\\"A\\":\\"Diffusion\\",\\"B\\":\\"Active transport\\"},\\"correctOption\\":\\"A\\",\\"assets\\":[],\\"shared_context\\":null,\\"is_follow_up\\":false,\\"questionNumber\\":\\"1\\"}]" }
                        ]
                      }
                    }
                  ]
                }
                """;

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                        org.springframework.http.HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.generationConfig.responseSchema.type").value("ARRAY"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.generationConfig.responseSchema.items.properties.options.type").value("OBJECT"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        geminiResponse, org.springframework.http.MediaType.APPLICATION_JSON));

        LlmStructuringService service = new LlmStructuringService(
                builder,
                objectMapper,
                "test-gemini-key",
                "gemini-3.5-flash-lite"
        );

        JsonNode result = service.structureChunk("1. What is osmosis?", null);

        server.verify();
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("text").asText()).isEqualTo("What is osmosis?");
    }

    @Test
    void structureChunk_ThrowsWhenResponseContainsOnlyThoughtParts() {
        RestClient.Builder builder = RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();

        String thoughtOnlyResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "thought": true, "text": "Internal reasoning without final JSON" }
                        ]
                      }
                    }
                  ]
                }
                """;

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                        org.springframework.http.HttpMethod.POST))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        thoughtOnlyResponse, org.springframework.http.MediaType.APPLICATION_JSON));

        LlmStructuringService service = new LlmStructuringService(
                builder,
                objectMapper,
                "test-gemini-key",
                "gemini-3.5-flash-lite"
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.structureChunk("1. Explain transpiration.", null)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no answer text parts");

        server.verify();
    }

    @Test
    void structureChunk_EnforcesBoundedReadTimeoutOnSlowUpstream() throws Exception {
        SimpleClientHttpRequestFactory defaultFactory = LlmStructuringService.createRequestFactory(60_000L);
        assertThat(defaultFactory).isNotNull();
        assertThat(LlmStructuringService.createRequestFactory(0L)).isNotNull();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v1beta/models/gemini-test:generateContent", exchange -> {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = "{\"candidates\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            String slowUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1beta/models/{model}:generateContent";
            LlmStructuringService timeoutService = new LlmStructuringService(
                    RestClient.builder(),
                    objectMapper,
                    "secret-key-12345",
                    "gemini-test",
                    150L,
                    slowUrl
            );

            long startNanos = System.nanoTime();
            assertThatThrownBy(() -> timeoutService.structureChunk("1. Slow prompt", null))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> assertThat(ex.getMessage())
                            .contains("communication failure")
                            .doesNotContain("secret-key-12345"));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
        } finally {
            server.stop(0);
        }
    }
}
