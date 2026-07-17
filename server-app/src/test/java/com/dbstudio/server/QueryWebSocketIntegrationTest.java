package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dbstudio.open-browser=false",
        "dbstudio.data-directory=${java.io.tmpdir}/dbstudio-websocket-test-${random.uuid}"
})
class QueryWebSocketIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("dbstudio").withUsername("dbstudio").withPassword("dbstudio-test-password");

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired LocalAccessToken token;
    @Autowired ObjectMapper mapper;

    @Test
    void streamsRealQueryEventsUsingIndependentLimitsAndBatchSizes() throws Exception {
        String cookie = authenticate();
        String workspaceId = UUID.randomUUID().toString();
        exchange(HttpMethod.PUT, "/api/v1/workspaces/" + workspaceId, new HashMap<String, Object>(), cookie);
        connect(workspaceId, cookie);
        String editorId = String.valueOf(exchange(HttpMethod.POST,
                "/api/v1/workspaces/" + workspaceId + "/editors", new HashMap<String, Object>(), cookie).get("id"));

        final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<Map<String, Object>>();
        WebSocketHttpHeaders socketHeaders = new WebSocketHttpHeaders();
        socketHeaders.setOrigin(origin());
        socketHeaders.add(HttpHeaders.COOKIE, cookie);
        WebSocketSession socket = new StandardWebSocketClient().doHandshake(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                events.add(mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() { }));
            }
        }, socketHeaders, URI.create("ws://127.0.0.1:" + port + "/api/v1/events?workspaceId=" + workspaceId))
                .get(10, TimeUnit.SECONDS);
        try {
            awaitType(events, "workspace.ready", 10);
            updateSetting(cookie, "result.maxRows", "120");
            updateSetting(cookie, "result.streamBatchRows", "50");
            List<Map<String, Object>> first = execute(editorId, workspaceId, cookie, events, 250);
            assertEquals(Arrays.asList(50, 50, 20), rowBatchSizes(first));
            assertTrue(resultComplete(first).get("truncated").equals(Boolean.TRUE));
            assertOrder(first);

            updateSetting(cookie, "result.maxRows", "250");
            updateSetting(cookie, "result.streamBatchRows", "100");
            List<Map<String, Object>> second = execute(editorId, workspaceId, cookie, events, 250);
            assertEquals(Arrays.asList(100, 100, 50), rowBatchSizes(second));
            assertOrder(second);
        } finally {
            socket.close();
        }
    }

    private List<Map<String, Object>> execute(String editorId, String workspaceId, String cookie,
                                               BlockingQueue<Map<String, Object>> events, int rows) throws Exception {
        events.clear();
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("editorId", editorId);
        body.put("scope", "script");
        body.put("stopOnError", true);
        body.put("text", "WITH RECURSIVE numbers(id) AS (SELECT 1 UNION ALL SELECT id + 1 FROM numbers WHERE id < "
                + rows + ") SELECT id FROM numbers");
        exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId + "/editors/" + editorId
                + "/executions", body, cookie);
        List<Map<String, Object>> collected = new ArrayList<Map<String, Object>>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Map<String, Object> event = events.poll(1, TimeUnit.SECONDS);
            if (event == null) continue;
            collected.add(event);
            if ("query.executionComplete".equals(event.get("type"))) return collected;
        }
        throw new AssertionError("Timed out waiting for query.executionComplete");
    }

    private void connect(String workspaceId, String cookie) {
        Map<String, Object> settings = new HashMap<String, Object>();
        settings.put("host", MYSQL.getHost());
        settings.put("port", String.valueOf(MYSQL.getMappedPort(3306)));
        settings.put("database", MYSQL.getDatabaseName());
        settings.put("username", MYSQL.getUsername());
        settings.put("timeoutSeconds", "20");
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("providerId", "mysql"); body.put("name", "WebSocket integration");
        body.put("settings", settings); body.put("password", MYSQL.getPassword()); body.put("rememberPassword", false);
        exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId + "/connection", body, cookie);
    }

    private void updateSetting(String cookie, String key, String value) {
        Map<String, Object> body = new HashMap<String, Object>(); body.put("key", key); body.put("value", value);
        exchange(HttpMethod.PUT, "/api/v1/settings", body, cookie);
    }

    private String authenticate() {
        Map<String, String> body = new HashMap<String, String>(); body.put("token", token.launchValue());
        HttpHeaders headers = new HttpHeaders(); headers.setOrigin(origin()); headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange(url("/api/v1/auth/exchange"), HttpMethod.POST,
                new HttpEntity<Map<String, String>>(body, headers), String.class);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(HttpMethod method, String path, Object body, String cookie) {
        HttpHeaders headers = new HttpHeaders(); headers.setOrigin(origin()); headers.add(HttpHeaders.COOKIE, cookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = http.exchange(url(path), method, new HttpEntity<Object>(body, headers), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(), String.valueOf(response.getBody()));
        return response.getBody();
    }

    private void awaitType(BlockingQueue<Map<String, Object>> events, String type, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Map<String, Object> event = events.poll(1, TimeUnit.SECONDS);
            if (event != null && type.equals(event.get("type"))) return;
        }
        throw new AssertionError("Timed out waiting for " + type);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> rowBatchSizes(List<Map<String, Object>> events) {
        List<Integer> result = new ArrayList<Integer>();
        for (Map<String, Object> event : events) if ("query.rows".equals(event.get("type"))) {
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            result.add(((List<Object>) payload.get("rows")).size());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultComplete(List<Map<String, Object>> events) {
        for (Map<String, Object> event : events) if ("query.resultComplete".equals(event.get("type"))) {
            return (Map<String, Object>) event.get("payload");
        }
        throw new AssertionError("Missing query.resultComplete");
    }

    private void assertOrder(List<Map<String, Object>> events) {
        List<String> types = new ArrayList<String>();
        for (Map<String, Object> event : events) types.add(String.valueOf(event.get("type")));
        assertTrue(types.indexOf("query.started") < types.indexOf("query.resultMeta"), types.toString());
        assertTrue(types.indexOf("query.resultMeta") < types.indexOf("query.rows"), types.toString());
        assertTrue(types.lastIndexOf("query.rows") < types.indexOf("query.resultComplete"), types.toString());
        assertTrue(types.indexOf("query.resultComplete") < types.indexOf("query.executionComplete"), types.toString());
    }

    private String origin() { return "http://127.0.0.1:" + port; }
    private String url(String path) { return origin() + path; }
}
