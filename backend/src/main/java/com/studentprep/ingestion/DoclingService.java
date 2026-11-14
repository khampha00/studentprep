package com.studentprep.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Client for the internal FastAPI Docling PDF parser sidecar, authenticating requests via
 * {@code X-API-Key}, enforcing bounded HTTP timeouts, and sanitizing upstream failures.
 */
@Service
public class DoclingService {

    private static final Logger log = LoggerFactory.getLogger(DoclingService.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String doclingParserUrl;
    private final String doclingApiKey;

    @Autowired
    public DoclingService(
            RestClient.Builder restClientBuilder,
            @Value("${docling.parser.url:http://localhost:8000}") String doclingParserUrl,
            @Value("${docling.api-key:}") String doclingApiKey,
            @Value("${docling.parser.timeout-ms:60000}") long timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int boundedTimeoutMs = (int) Math.max(100L, Math.min(timeoutMs, Integer.MAX_VALUE));
        requestFactory.setConnectTimeout(boundedTimeoutMs);
        requestFactory.setReadTimeout(boundedTimeoutMs);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.doclingParserUrl = normalizeBaseUrl(doclingParserUrl);
        this.doclingApiKey = doclingApiKey;
    }

    DoclingService(RestClient.Builder restClientBuilder, String doclingParserUrl, String doclingApiKey) {
        this.restClient = restClientBuilder.build();
        this.doclingParserUrl = normalizeBaseUrl(doclingParserUrl);
        this.doclingApiKey = doclingApiKey;
    }

    public String parsePdf(Resource fileResource) {
        if (doclingApiKey == null || doclingApiKey.isBlank()) {
            throw new IllegalStateException("Docling API key is not configured.");
        }
        if (doclingParserUrl.isBlank()) {
            throw new IllegalStateException("Docling parser URL is not configured.");
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri(doclingParserUrl + "/parse")
                    .header(API_KEY_HEADER, doclingApiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(RESPONSE_TYPE);
        } catch (RestClientResponseException ex) {
            int statusCode = ex.getStatusCode().value();
            log.warn("Docling parser returned HTTP status {}", statusCode);
            throw new IllegalStateException("Document parser failed with HTTP " + statusCode);
        } catch (RestClientException ex) {
            String sanitized = ErrorSanitizer.sanitizeThrowable(ex, doclingApiKey);
            log.warn("Docling parser request failed: {}", sanitized);
            throw new IllegalStateException("Document parser is unavailable or timed out");
        }

        if (response == null || !(response.get("markdown") instanceof String markdown)) {
            throw new IllegalStateException("Document parser returned an invalid response payload");
        }
        return markdown;
    }

    private static String normalizeBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        return rawUrl.trim().replaceAll("/+$", "");
    }
}
