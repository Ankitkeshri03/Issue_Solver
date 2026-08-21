package com.gitissuesolver.backend.agent;

import com.gitissuesolver.backend.agent.dto.AnalyzeRequest;
import com.gitissuesolver.backend.agent.dto.AnalyzeResponse;
import com.gitissuesolver.backend.agent.dto.ImplementRequest;
import com.gitissuesolver.backend.agent.dto.ImplementResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** HTTP client to the Python FastAPI AI service (ai-service/). */
@Service
public class AgentClient {

    private final RestClient restClient;

    public AgentClient(@Value("${ai.service.url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
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
