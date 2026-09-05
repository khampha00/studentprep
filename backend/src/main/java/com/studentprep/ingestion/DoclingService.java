package com.studentprep.ingestion;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import java.util.Map;

@Service
public class DoclingService {
    private final RestClient restClient;

    public DoclingService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public String parsePdf(Resource fileResource) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        String doclingUrl = System.getenv().getOrDefault("DOCLING_PARSER_URL", "http://localhost:8000");

        Map response = restClient.post()
                .uri(doclingUrl + "/parse")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);

        return (String) response.get("markdown");
    }
}
