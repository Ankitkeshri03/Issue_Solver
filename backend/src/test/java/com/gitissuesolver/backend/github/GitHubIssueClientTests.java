package com.gitissuesolver.backend.github;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubIssueClientTests {
    @Test
    void paginatesAndFiltersPullRequestsIncludingEmptyPullRequestMarkers() {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var github = new GitHubService("", builder);
        String firstPage = IntStream.rangeClosed(1, 100).mapToObj(number ->
                "{\"number\":" + number + ",\"title\":\"Fix\",\"state\":\"open\",\"pull_request\":{}}")
                .collect(Collectors.joining(",", "[", "]"));
        server.expect(requestTo("https://api.github.com/repos/example/test/issues?state=all&per_page=100&page=1"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/example/test/issues?state=all&per_page=100&page=2"))
                .andRespond(withSuccess("""
                    [{"number":101,"title":"Real bug","state":"open"},
                     {"number":102,"title":"Closed bug","state":"closed"}]
                    """, MediaType.APPLICATION_JSON));
        assertThat(github.fetchOpenIssues("example", "test", "test-token"))
                .extracting(GitHubIssueDto::number).containsExactly(101);
        server.verify();
    }
}
