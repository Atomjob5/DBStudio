package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.Connection;
import java.sql.Statement;
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
        "dbstudio.local-access-token.enabled=true",
        "dbstudio.data-directory=${java.io.tmpdir}/dbstudio-websocket-test-${random.uuid}"
})
class QueryWebSocketIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("dbstudio").withUsername("dbstudio").withPassword("dbstudio-test-password");

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired LocalAccessToken token;
    @Autowired ObjectMapper mapper;
    @Autowired WorkspaceRegistry workspaces;
    private String clientId;

    @Test
    void streamsRealQueryEventsUsingIndependentLimitsAndBatchSizes() throws Exception {
        String cookie = authenticate();
        String workspaceId = UUID.randomUUID().toString();
        exchange(HttpMethod.PUT, "/api/v1/workspaces/" + workspaceId, new HashMap<String, Object>(), cookie);
        openWorkspace(workspaceId, cookie);
        String profileId = createProfile(workspaceId, cookie);
        Map<String, Object> editorBody = new HashMap<String, Object>();
        editorBody.put("profileId", profileId);
        String editorId = String.valueOf(exchange(HttpMethod.POST,
                "/api/v1/workspaces/" + workspaceId + "/editors", editorBody, cookie).get("id"));

        final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<Map<String, Object>>();
        WebSocketHttpHeaders socketHeaders = new WebSocketHttpHeaders();
        socketHeaders.setOrigin(origin());
        socketHeaders.add(HttpHeaders.COOKIE, cookie);
        WebSocketSession socket = new StandardWebSocketClient().doHandshake(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                events.add(mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() { }));
            }
        }, socketHeaders, URI.create("ws://127.0.0.1:" + port + "/api/v1/events?workspaceId=" + workspaceId
                + "&clientId=" + clientId))
                .get(10, TimeUnit.SECONDS);
        try {
            awaitType(events, "workspace.ready", 10);
            try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE result_column_comment(id BIGINT COMMENT '订单编号', amount DECIMAL(10,2) COMMENT '订单金额')");
                statement.execute("CREATE TABLE completion_customer(id BIGINT COMMENT '客户编号', name VARCHAR(100) COMMENT '客户名称')");
                statement.execute("INSERT INTO result_column_comment VALUES (1, 12.30)");
            }
            assertCompletionSnapshot(editorId, workspaceId, cookie, events);
            List<Map<String, Object>> metadataEvents = executeSql(editorId, workspaceId, cookie, events,
                    "SELECT id AS order_id, amount, amount + 1 AS calculated FROM result_column_comment");
            assertColumnMetadata(metadataEvents);

            updateSetting(cookie, "result.maxRows", "120");
            updateSetting(cookie, "result.streamBatchRows", "50");
            List<Map<String, Object>> first = execute(editorId, workspaceId, cookie, events, 250);
            assertEquals(Arrays.asList(50, 50, 20), rowBatchSizes(first));
            assertTrue(resultComplete(first).get("truncated").equals(Boolean.TRUE));
            assertOrder(first);
            Map<String, Object> pageBody = new HashMap<String, Object>();
            pageBody.put("offset", 120); pageBody.put("limit", 100);
            Map<String, Object> page = exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                    + "/editors/" + editorId + "/results/0/page", pageBody, cookie);
            assertEquals(100, ((List<?>) page.get("rows")).size());
            assertEquals(Boolean.TRUE, page.get("hasMore"));
            assertEquals(220, ((Number) page.get("nextOffset")).intValue());

            updateSetting(cookie, "result.maxRows", "250");
            updateSetting(cookie, "result.streamBatchRows", "100");
            List<Map<String, Object>> second = execute(editorId, workspaceId, cookie, events, 250);
            assertEquals(Arrays.asList(100, 100, 50), rowBatchSizes(second));
            assertOrder(second);
        } finally {
            socket.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoresAnExpiredEditorAndExecutesAfterSupplyingItsUnrememberedPassword() throws Exception {
        String cookie = authenticate();
        String workspaceId = UUID.randomUUID().toString();
        exchange(HttpMethod.PUT, "/api/v1/workspaces/" + workspaceId,
                new HashMap<String, Object>(), cookie);
        openWorkspace(workspaceId, cookie);
        String profileId = createProfile(workspaceId, cookie);
        Map<String, Object> editorBody = new HashMap<String, Object>(); editorBody.put("profileId", profileId);
        String editorId = String.valueOf(exchange(HttpMethod.POST,
                "/api/v1/workspaces/" + workspaceId + "/editors", editorBody, cookie).get("id"));

        Map<String, Object> draft = new HashMap<String, Object>(); draft.put("title", "恢复查询");
        draft.put("sqlText", "SELECT 1"); draft.put("dirty", true); draft.put("sortOrder", 0);
        draft.put("active", true); draft.put("profileId", profileId);
        exchange(HttpMethod.PUT, "/api/v1/workspaces/" + workspaceId + "/editors/" + editorId + "/draft", draft, cookie);
        workspaces.expireNow(workspaceId);
        Map<String, Object> reopened = openWorkspace(workspaceId, cookie);
        assertEquals(Boolean.TRUE, reopened.get("recoveryDecisionRequired"));
        Map<String, Object> recoveryBody = new HashMap<String, Object>(); recoveryBody.put("decision", "restore");
        Map<String, Object> recovery = exchange(HttpMethod.POST,
                "/api/v1/workspaces/" + workspaceId + "/recovery", recoveryBody, cookie);
        Map<String, Object> restored = (Map<String, Object>) ((List<?>) recovery.get("editors")).get(0);
        assertEquals(editorId, restored.get("id"));
        assertEquals("credentials-required", restored.get("connectionState"));

        final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<Map<String, Object>>();
        WebSocketHttpHeaders socketHeaders = new WebSocketHttpHeaders();
        socketHeaders.setOrigin(origin()); socketHeaders.add(HttpHeaders.COOKIE, cookie);
        WebSocketSession socket = new StandardWebSocketClient().doHandshake(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                events.add(mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() { }));
            }
        }, socketHeaders, URI.create("ws://127.0.0.1:" + port + "/api/v1/events?workspaceId=" + workspaceId
                + "&clientId=" + clientId))
                .get(10, TimeUnit.SECONDS);
        try {
            awaitType(events, "workspace.ready", 10);
            Map<String, Object> bind = new HashMap<String, Object>();
            bind.put("profileId", profileId); bind.put("password", MYSQL.getPassword());
            bind.put("rememberPassword", false); bind.put("transactionAction", "");
            Map<String, Object> rebound = exchange(HttpMethod.PUT, "/api/v1/workspaces/" + workspaceId
                    + "/editors/" + editorId + "/connection", bind, cookie);
            assertEquals("ready", rebound.get("connectionState"));

            List<Map<String, Object>> queryEvents = executeSql(editorId, workspaceId, cookie, events, "SELECT 1");
            assertOrder(queryEvents);
            assertTrue(queryEvents.stream().anyMatch(value -> "query.executionComplete".equals(value.get("type"))));
        } finally {
            socket.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void assertCompletionSnapshot(String editorId, String workspaceId, String cookie,
                                          BlockingQueue<Map<String, Object>> events) throws Exception {
        events.clear();
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("loadId", "completion-integration");
        body.put("editorId", editorId);
        Map<String, Object> snapshot = exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                + "/metadata/completion-snapshot", body, cookie);
        assertEquals("mysql", snapshot.get("providerId"));
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) snapshot.get("suggestions");
        assertTrue(suggestions.stream().anyMatch(value -> "table".equals(value.get("kind"))
                && "result_column_comment".equals(value.get("label"))));
        assertTrue(suggestions.stream().anyMatch(value -> "column".equals(value.get("kind"))
                && "订单编号".equals(value.get("remarks"))));
        assertEquals(2, suggestions.stream().filter(value -> "column".equals(value.get("kind"))
                && "id".equals(value.get("label"))).count());
        assertEquals(2, suggestions.stream().filter(value -> "column".equals(value.get("kind"))
                && "id".equals(value.get("label"))).map(value -> String.valueOf(value.get("id"))).distinct().count());

        boolean receivedProgress = false;
        Map<String, Object> event;
        while ((event = events.poll(200, TimeUnit.MILLISECONDS)) != null) {
            if ("metadata.completionProgress".equals(event.get("type"))) {
                Map<String, Object> payload = (Map<String, Object>) event.get("payload");
                assertEquals("completion-integration", payload.get("loadId"));
                receivedProgress = true;
            }
        }
        assertTrue(receivedProgress, "completion snapshot should publish progress events");
    }

    private List<Map<String, Object>> execute(String editorId, String workspaceId, String cookie,
                                               BlockingQueue<Map<String, Object>> events, int rows) throws Exception {
        return executeSql(editorId, workspaceId, cookie, events,
                "WITH RECURSIVE numbers(id) AS (SELECT 1 UNION ALL SELECT id + 1 FROM numbers WHERE id < "
                        + rows + ") SELECT id FROM numbers");
    }

    private List<Map<String, Object>> executeSql(String editorId, String workspaceId, String cookie,
                                                  BlockingQueue<Map<String, Object>> events, String sql) throws Exception {
        events.clear();
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("editorId", editorId);
        body.put("scope", "script");
        body.put("stopOnError", true);
        body.put("text", sql);
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
        throw new AssertionError("Timed out waiting for query.executionComplete; received=" + collected);
    }

    @SuppressWarnings("unchecked")
    private void assertColumnMetadata(List<Map<String, Object>> events) {
        for (Map<String, Object> event : events) if ("query.resultMeta".equals(event.get("type"))) {
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            List<Map<String, Object>> columns = (List<Map<String, Object>>) payload.get("columnDetails");
            assertEquals("order_id", columns.get(0).get("label"));
            assertEquals("id", columns.get(0).get("name"));
            assertEquals("订单编号", columns.get(0).get("remarks"));
            assertEquals("订单金额", columns.get(1).get("remarks"));
            assertEquals("", columns.get(2).get("remarks"));
            return;
        }
        throw new AssertionError("Missing query.resultMeta columnDetails");
    }

    @SuppressWarnings("unchecked")
    private String createProfile(String workspaceId, String cookie) {
        Map<String, Object> bootstrap = exchange(HttpMethod.GET, "/api/v1/bootstrap?workspaceId=" + workspaceId,
                null, cookie);
        String environmentId = String.valueOf(((Map<String, Object>)
                ((List<Object>) bootstrap.get("environments")).get(0)).get("id"));
        Map<String, Object> settings = new HashMap<String, Object>();
        settings.put("host", MYSQL.getHost());
        settings.put("port", String.valueOf(MYSQL.getMappedPort(3306)));
        settings.put("database", MYSQL.getDatabaseName());
        settings.put("username", MYSQL.getUsername());
        settings.put("timeoutSeconds", "20");
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("providerId", "mysql"); body.put("name", "WebSocket integration");
        body.put("environmentId", environmentId);
        body.put("settings", settings); body.put("password", MYSQL.getPassword()); body.put("rememberPassword", false);
        return String.valueOf(exchange(HttpMethod.POST,
                "/api/v1/workspaces/" + workspaceId + "/connection-profiles", body, cookie).get("id"));
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
        if (clientId != null) headers.add("X-DBStudio-Client-Id", clientId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = body == null ? new HttpEntity<Object>(headers) : new HttpEntity<Object>(body, headers);
        ResponseEntity<Map> response = http.exchange(url(path), method, entity, Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(), String.valueOf(response.getBody()));
        return response.getBody();
    }

    private Map<String, Object> openWorkspace(String workspaceId, String cookie) {
        clientId = UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<String, Object>(); body.put("clientId", clientId);
        return exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId + "/open", body, cookie);
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
