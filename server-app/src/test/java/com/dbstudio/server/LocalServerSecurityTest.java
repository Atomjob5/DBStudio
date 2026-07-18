package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dbstudio.open-browser=false",
        "dbstudio.data-directory=${java.io.tmpdir}/dbstudio-server-test-${random.uuid}"
})
class LocalServerSecurityTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired LocalAccessToken token;

    @Test
    void servesEmbeddedFrontendAndRejectsUnauthenticatedApi() {
        ResponseEntity<String> index = http.getForEntity(url("/"), String.class);
        assertEquals(HttpStatus.OK, index.getStatusCode());
        assertTrue(index.getBody().contains("id=\"app\""));
        assertNotNull(index.getHeaders().getFirst("Content-Security-Policy"));

        ResponseEntity<String> denied = http.getForEntity(url("/api/v1/bootstrap"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, denied.getStatusCode());
        assertTrue(denied.getBody().contains("UNAUTHORIZED"));
    }

    @Test
    void exchangesLaunchTokenForStrictCookieAndCreatesWorkspace() {
        Map<String, String> request = new HashMap<String, String>();
        request.put("token", token.launchValue());
        ResponseEntity<String> exchanged = http.postForEntity(url("/api/v1/auth/exchange"), request, String.class);
        assertEquals(HttpStatus.OK, exchanged.getStatusCode());
        String cookie = exchanged.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie.substring(0, cookie.indexOf(';')));
        headers.add(HttpHeaders.ORIGIN, "http://127.0.0.1:" + port);
        String workspaceId = UUID.randomUUID().toString();
        ResponseEntity<String> created = http.exchange(url("/api/v1/workspaces/" + workspaceId),
                HttpMethod.PUT, new HttpEntity<String>("{}", headers), String.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertTrue(created.getBody().contains(workspaceId));
    }

    @Test
    void rejectsForeignOrigin() throws Exception {
        LocalRequestFilter filter = new LocalRequestFilter(token, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bootstrap");
        request.setLocalPort(33000);
        request.addHeader(HttpHeaders.HOST, "127.0.0.1:33000");
        request.addHeader(HttpHeaders.ORIGIN, "http://attacker.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("LOCAL_ACCESS_REQUIRED"));
    }

    @Test
    void websocketHandshakeRequiresAnOrigin() throws Exception {
        LocalRequestFilter filter = new LocalRequestFilter(token, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events");
        request.setLocalPort(33000);
        request.addHeader(HttpHeaders.HOST, "127.0.0.1:33000");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("LOCAL_ACCESS_REQUIRED"));
    }

    @Test
    void validatesAndPersistsColumnLayoutScope() {
        HttpHeaders headers = authenticatedHeaders();
        ResponseEntity<String> defaults = http.exchange(url("/api/v1/settings"), HttpMethod.GET,
                new HttpEntity<String>(headers), String.class);
        assertEquals(HttpStatus.OK, defaults.getStatusCode());
        assertTrue(defaults.getBody().contains("\"result.columnLayoutScope\":\"result\""));
        assertTrue(defaults.getBody().contains("\"result.copyHeaderOnDoubleClick\":\"true\""));
        assertTrue(defaults.getBody().contains("\"result.copySeparator\":\"comma\""));
        assertTrue(defaults.getBody().contains("\"connection.maxActiveSessions\":\"10\""));
        assertTrue(defaults.getBody().contains("\"connection.idleTimeoutMinutes\":\"10\""));

        Map<String, String> setting = new HashMap<String, String>();
        setting.put("key", "result.columnLayoutScope");
        setting.put("value", "editor");
        ResponseEntity<String> saved = http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class);
        assertEquals(HttpStatus.OK, saved.getStatusCode());
        assertTrue(saved.getBody().contains("\"value\":\"editor\""));

        setting.put("value", "global");
        ResponseEntity<String> rejected = http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertTrue(rejected.getBody().contains("INVALID_SETTING"));

        setting.put("key", "result.copyHeaderOnDoubleClick");
        setting.put("value", "false");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        setting.put("value", "yes");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        setting.put("key", "result.copySeparator");
        for (String separator : Arrays.asList("comma", "tab", "semicolon", "pipe")) {
            setting.put("value", separator);
            assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                    new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        }
        setting.put("value", "space");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        setting.put("key", "connection.maxActiveSessions");
        setting.put("value", "12");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        setting.put("value", "101");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        setting.put("key", "connection.idleTimeoutMinutes");
        setting.put("value", "30");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        setting.put("value", "0");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void movesConnectionProfileAcrossSystemsWithoutChangingRevision() {
        HttpHeaders headers = authenticatedHeaders();
        String workspaceId = UUID.randomUUID().toString();
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/workspaces/" + workspaceId), HttpMethod.PUT,
                new HttpEntity<String>("{}", headers), String.class).getStatusCode());

        Map<String, Object> systemBody = new HashMap<String, Object>(); systemBody.put("name", "订单系统");
        Map<String, Object> firstSystem = http.exchange(url("/api/v1/connection-systems"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(systemBody, headers), Map.class).getBody();
        systemBody.put("name", "资金系统");
        Map<String, Object> secondSystem = http.exchange(url("/api/v1/connection-systems"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(systemBody, headers), Map.class).getBody();
        assertNotNull(firstSystem); assertNotNull(secondSystem);

        Map<String, Object> environmentBody = new HashMap<String, Object>();
        environmentBody.put("systemId", firstSystem.get("id")); environmentBody.put("name", "DEV");
        Map<String, Object> firstEnvironment = http.exchange(url("/api/v1/connection-environments"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(environmentBody, headers), Map.class).getBody();
        environmentBody.put("systemId", secondSystem.get("id")); environmentBody.put("name", "SIT");
        Map<String, Object> secondEnvironment = http.exchange(url("/api/v1/connection-environments"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(environmentBody, headers), Map.class).getBody();
        assertNotNull(firstEnvironment); assertNotNull(secondEnvironment);

        Map<String, Object> profileBody = new HashMap<String, Object>();
        profileBody.put("providerId", "mysql"); profileBody.put("name", "开发库");
        profileBody.put("environmentId", firstEnvironment.get("id"));
        profileBody.put("settings", new HashMap<String, String>()); profileBody.put("password", "");
        profileBody.put("rememberPassword", false);
        Map<String, Object> profile = http.exchange(url("/api/v1/workspaces/" + workspaceId
                        + "/connection-profiles"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(profileBody, headers), Map.class).getBody();
        assertNotNull(profile);

        Map<String, Object> moveBody = new HashMap<String, Object>();
        moveBody.put("environmentId", secondEnvironment.get("id"));
        ResponseEntity<Map> movedResponse = http.exchange(url("/api/v1/workspaces/" + workspaceId
                        + "/connection-profiles/" + profile.get("id") + "/location"), HttpMethod.PUT,
                new HttpEntity<Map<String, Object>>(moveBody, headers), Map.class);
        assertEquals(HttpStatus.OK, movedResponse.getStatusCode());
        assertEquals(secondEnvironment.get("id"), movedResponse.getBody().get("environmentId"));
        assertEquals(profile.get("revision"), movedResponse.getBody().get("revision"));

        ResponseEntity<Map> idempotent = http.exchange(url("/api/v1/workspaces/" + workspaceId
                        + "/connection-profiles/" + profile.get("id") + "/location"), HttpMethod.PUT,
                new HttpEntity<Map<String, Object>>(moveBody, headers), Map.class);
        assertEquals(HttpStatus.OK, idempotent.getStatusCode());
        assertEquals(profile.get("revision"), idempotent.getBody().get("revision"));

        moveBody.put("environmentId", "missing-environment");
        ResponseEntity<String> rejected = http.exchange(url("/api/v1/workspaces/" + workspaceId
                        + "/connection-profiles/" + profile.get("id") + "/location"), HttpMethod.PUT,
                new HttpEntity<Map<String, Object>>(moveBody, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertTrue(rejected.getBody().contains("ENVIRONMENT_NOT_FOUND"));
    }

    private HttpHeaders authenticatedHeaders() {
        Map<String, String> request = new HashMap<String, String>();
        request.put("token", token.launchValue());
        ResponseEntity<String> exchanged = http.postForEntity(url("/api/v1/auth/exchange"), request, String.class);
        String cookie = exchanged.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie.substring(0, cookie.indexOf(';')));
        headers.add(HttpHeaders.ORIGIN, "http://127.0.0.1:" + port);
        return headers;
    }

    private String url(String path) { return "http://127.0.0.1:" + port + path; }
}
