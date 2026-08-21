package com.gitissuesolver.backend.agent;

import com.gitissuesolver.backend.agent.dto.AnalyzeRequest;
import com.gitissuesolver.backend.agent.dto.AnalyzeResponse;
import com.gitissuesolver.backend.agent.dto.ImplementRequest;
import com.gitissuesolver.backend.agent.dto.ImplementResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** HTTP client to the Python FastAPI AI service (ai-service/). */
@Service
public class AgentClient {

    private final RestClient restClient;

    public AgentClient(@Value("${ai.service.url}") String baseUrl) {
        // uvicorn (plain HTTP/1.1) doesn't understand the JDK HttpClient's default
        // "Upgrade: h2c" cleartext-HTTP/2 negotiation attempt — it silently drops the
        // request body, so pydantic sees an empty body. Force HTTP/1.1 to avoid it.
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    public AnalyzeResponse analyze(AnalyzeRequest request) {
        return restClient.post()
                .uri("/analyze")
                .body(request)
                .retrieve()
                .body(AnalyzeResponse.class);
    }

    public ImplementResponse implement(ImplementRequest request) {
        return restClient.post()
                .uri("/implement")
                .body(request)
                .retrieve()
                .body(ImplementResponse.class);
    }
}
