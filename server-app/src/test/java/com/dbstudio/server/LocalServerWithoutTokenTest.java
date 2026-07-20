package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dbstudio.open-browser=false",
        "dbstudio.local-access-token.enabled=false",
        "dbstudio.data-directory=${java.io.tmpdir}/dbstudio-no-token-test-${random.uuid}"
})
class LocalServerWithoutTokenTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired LocalRequestFilter filter;

    @Test
    void allowsSameOriginApiWithoutCookieButStillRejectsCrossOriginRequests() throws Exception {
        HttpHeaders local = new HttpHeaders();
        local.set("Origin", "http://127.0.0.1:" + port);
        ResponseEntity<String> accepted = http.exchange(url("/api/v1/workspaces"), HttpMethod.GET,
                new HttpEntity<Void>(local), String.class);
        assertEquals(200, accepted.getStatusCodeValue());

        MockHttpServletRequest foreign = new MockHttpServletRequest("GET", "/api/v1/workspaces");
        foreign.setLocalPort(33000);
        foreign.addHeader(HttpHeaders.HOST, "127.0.0.1:33000");
        foreign.addHeader(HttpHeaders.ORIGIN, "https://example.invalid");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(foreign, rejected, new MockFilterChain());
        assertEquals(403, rejected.getStatus());
    }

    private String url(String path) { return "http://127.0.0.1:" + port + path; }
}
