package com.gitissuesolver.backend.agent;

import com.gitissuesolver.backend.agent.dto.AnalyzeRequest;
import com.gitissuesolver.backend.agent.dto.AnalyzeResponse;
import com.gitissuesolver.backend.agent.dto.ImplementRequest;
import com.gitissuesolver.backend.agent.dto.ImplementResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

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
                .onStatus(HttpStatusCode::isError, this::surfaceAiServiceError)
                .body(AnalyzeResponse.class);
    }

    public ImplementResponse implement(ImplementRequest request) {
        return restClient.post()
                .uri("/implement")
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::surfaceAiServiceError)
                .body(ImplementResponse.class);
    }

    /**
     * RestClient's default error handling discards the response body, so a detailed
     * failure from the AI service (dead model, exhausted Gemini quota, git error) reached
     * the UI as a bare {@code 500 "Internal Server Error"}. The AI service returns
     * {"error": "...", "type": "..."} -- unwrap it so the real cause is visible.
     */
    private void surfaceAiServiceError(HttpRequest request, ClientHttpResponse response) throws IOException {
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        throw new AiServiceException(response.getStatusCode().value(), extractErrorMessage(body));
    }

    /** Pulls the "error" field out of the AI service's JSON body, falling back to raw text. */
    private static String extractErrorMessage(String body) {
        int keyIndex = body.indexOf("\"error\"");
        if (keyIndex < 0) {
            return body;
        }
        int start = body.indexOf('"', body.indexOf(':', keyIndex) + 1);
        if (start < 0) {
            return body;
        }
        StringBuilder message = new StringBuilder();
        for (int i = start + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                message.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> next;
                });
            } else if (c == '"') {
                return message.toString();
            } else {
                message.append(c);
            }
        }
        return body;
    }
}
