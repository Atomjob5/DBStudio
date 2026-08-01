package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
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
                statement.execute("CREATE TABLE result_column_comment(id BIGINT PRIMARY KEY COMMENT '订单编号', amount DECIMAL(10,2) COMMENT '订单金额')");
                statement.execute("CREATE TABLE completion_customer(id BIGINT COMMENT '客户编号', name VARCHAR(100) COMMENT '客户名称')");
                statement.execute("INSERT INTO result_column_comment VALUES (1, 12.30)");
            }
            assertCompletionSnapshot(editorId, workspaceId, cookie, events);
            List<Map<String, Object>> metadataEvents = executeSql(editorId, workspaceId, cookie, events,
                    "SELECT id AS order_id, amount, amount + 1 AS calculated FROM result_column_comment");
            assertColumnMetadata(metadataEvents);
            assertMutationTarget(executeSql(editorId, workspaceId, cookie, events,
                    "SELECT id AS order_id, amount FROM result_column_comment"));

            updateSetting(cookie, "result.maxRows", "120");
            updateSetting(cookie, "result.streamBatchRows", "50");
            List<Map<String, Object>> first = execute(editorId, workspaceId, cookie, events, 250);
            assertEquals(Arrays.asList(50, 50, 20), rowBatchSizes(first));
            assertTrue(resultComplete(first).get("truncated").equals(Boolean.TRUE));
            assertOrder(first);

            events.clear();
            Map<String, Object> cancelledPageBody = new HashMap<String, Object>();
            String cancelledPageExecutionId = UUID.randomUUID().toString();
            cancelledPageBody.put("offset", 120); cancelledPageBody.put("limit", 100);
            cancelledPageBody.put("executionId", cancelledPageExecutionId);
            CompletableFuture<Map<String, Object>> cancelledPageFuture = CompletableFuture.supplyAsync(() ->
                    exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId + "/editors/" + editorId
                            + "/results/0/page", cancelledPageBody, cookie));
            Map<String, Object> cancelledPageStarted = awaitType(events, "query.pageStarted", 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> cancelledPageStartedPayload =
                    (Map<String, Object>) cancelledPageStarted.get("payload");
            assertEquals(cancelledPageExecutionId, cancelledPageStartedPayload.get("executionId"));
            Map<String, Object> cancelResponse = exchange(HttpMethod.DELETE, "/api/v1/workspaces/" + workspaceId
                    + "/executions/" + cancelledPageExecutionId, null, cookie);
            assertEquals(Boolean.TRUE, cancelResponse.get("cancelled"));
            Map<String, Object> cancelledPage = cancelledPageFuture.get(10, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, cancelledPage.get("cancelled"));
            assertTrue(((List<?>) cancelledPage.get("rows")).isEmpty());
            assertEquals(120, ((Number) cancelledPage.get("nextOffset")).intValue());

            events.clear();
            Map<String, Object> pageBody = new HashMap<String, Object>();
            String pageExecutionId = UUID.randomUUID().toString();
            pageBody.put("offset", 120); pageBody.put("limit", 100);
            pageBody.put("executionId", pageExecutionId);
            Map<String, Object> page = exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                    + "/editors/" + editorId + "/results/0/page", pageBody, cookie);
            Map<String, Object> pageStarted = awaitType(events, "query.pageStarted", 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> pageStartedPayload = (Map<String, Object>) pageStarted.get("payload");
            assertEquals(pageExecutionId, pageStartedPayload.get("executionId"));
            assertEquals(editorId, pageStartedPayload.get("editorId"));
            assertEquals(100, ((List<?>) page.get("rows")).size());
            assertEquals(Boolean.TRUE, page.get("hasMore"));
            assertEquals(Boolean.FALSE, page.get("cancelled"));
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

    @Test
    @SuppressWarnings("unchecked")
    void appliesAutoCommitOnTheNextExecutionAndRejectsUnsafeSwitches() throws Exception {
        String cookie = authenticate();
        String workspaceId = UUID.randomUUID().toString();
        exchange(HttpMethod.PUT, "/api/v1/workspaces/" + workspaceId, new HashMap<String, Object>(), cookie);
        openWorkspace(workspaceId, cookie);
        updateSetting(cookie, "connection.autoCommit", "false");
        String profileId = createProfile(workspaceId, cookie);
        Map<String, Object> editorBody = new HashMap<String, Object>();
        editorBody.put("profileId", profileId);
        String editorId = String.valueOf(exchange(HttpMethod.POST,
                "/api/v1/workspaces/" + workspaceId + "/editors", editorBody, cookie).get("id"));
        String table = "auto_commit_" + UUID.randomUUID().toString().replace("-", "");

        final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<Map<String, Object>>();
        WebSocketHttpHeaders socketHeaders = new WebSocketHttpHeaders();
        socketHeaders.setOrigin(origin());
        socketHeaders.add(HttpHeaders.COOKIE, cookie);
        WebSocketSession socket = new StandardWebSocketClient().doHandshake(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                events.add(mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() { }));
            }
        }, socketHeaders, URI.create("ws://127.0.0.1:" + port + "/api/v1/events?workspaceId=" + workspaceId
                + "&clientId=" + clientId)).get(10, TimeUnit.SECONDS);
        try {
            awaitType(events, "workspace.ready", 10);
            executeSql(editorId, workspaceId, cookie, events,
                    "CREATE TABLE " + table + "(id BIGINT PRIMARY KEY)");
            Map<String, Object> manualComplete = executionComplete(executeSql(
                    editorId, workspaceId, cookie, events, "INSERT INTO " + table + " VALUES (1)"));
            assertEquals(Boolean.TRUE, manualComplete.get("transactionDirty"));

            Map<String, Object> setting = new HashMap<String, Object>();
            setting.put("key", "connection.autoCommit");
            setting.put("value", "true");
            HttpHeaders headers = authenticatedJsonHeaders(cookie);
            ResponseEntity<Map> rejected = http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                    new HttpEntity<Map<String, Object>>(setting, headers), Map.class);
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, rejected.getStatusCode());
            assertEquals("TRANSACTION_DECISION_REQUIRED", rejected.getBody().get("code"));

            exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId + "/editors/" + editorId
                    + "/transaction/rollback", Collections.<String, Object>emptyMap(), cookie);
            updateSetting(cookie, "connection.autoCommit", "true");
            Map<String, Object> automaticComplete = executionComplete(executeSql(
                    editorId, workspaceId, cookie, events, "INSERT INTO " + table + " VALUES (2)"));
            assertEquals(Boolean.FALSE, automaticComplete.get("transactionDirty"));

            try (Connection connection = MYSQL.createConnection("");
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE id = 2")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        } finally {
            try {
                exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId + "/editors/" + editorId
                        + "/transaction/rollback", Collections.<String, Object>emptyMap(), cookie);
            } catch (RuntimeException ignored) { }
            try { updateSetting(cookie, "connection.autoCommit", "false"); }
            catch (RuntimeException ignored) { }
            socket.close();
            workspaces.expireNow(workspaceId);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void editsForUpdateResultsAndRequiresACommitOrRollbackDecisionBeforeExecutingAgain() throws Exception {
        String cookie = authenticate();
        String workspaceId = UUID.randomUUID().toString();
        exchange(HttpMethod.PUT, "/api/v1/workspaces/" + workspaceId, new HashMap<String, Object>(), cookie);
        openWorkspace(workspaceId, cookie);
        updateSetting(cookie, "connection.autoCommit", "false");
        String profileId = createProfile(workspaceId, cookie);
        Map<String, Object> editorBody = new HashMap<String, Object>();
        editorBody.put("profileId", profileId);
        String editorId = String.valueOf(exchange(HttpMethod.POST,
                "/api/v1/workspaces/" + workspaceId + "/editors", editorBody, cookie).get("id"));
        String table = "editable_result_" + UUID.randomUUID().toString().replace("-", "");

        final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<Map<String, Object>>();
        WebSocketHttpHeaders socketHeaders = new WebSocketHttpHeaders();
        socketHeaders.setOrigin(origin()); socketHeaders.add(HttpHeaders.COOKIE, cookie);
        WebSocketSession socket = new StandardWebSocketClient().doHandshake(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                events.add(mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() { }));
            }
        }, socketHeaders, URI.create("ws://127.0.0.1:" + port + "/api/v1/events?workspaceId=" + workspaceId
                + "&clientId=" + clientId)).get(10, TimeUnit.SECONDS);
        try {
            awaitType(events, "workspace.ready", 10);
            try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + table
                        + "(id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL,"
                        + " order_status ENUM('NEW', 'DONE') NOT NULL)");
                statement.execute("INSERT INTO " + table
                        + " VALUES (1, 'before', 'NEW'), (2, 'remove-me', 'DONE')");
            }

            List<Map<String, Object>> locked = executeSql(editorId, workspaceId, cookie, events,
                    "SELECT id, name, order_status FROM " + table + " FOR UPDATE");
            Map<String, Object> complete = executionComplete(locked);
            Map<String, Object> target = mutationTarget(locked);
            assertEquals(Boolean.TRUE, target.get("editableForUpdate"));

            List<String> rowIds = resultRowIds(locked);
            assertEquals(2, rowIds.size());
            Map<String, Object> forgedBody = new HashMap<String, Object>();
            forgedBody.put("executionId", complete.get("executionId"));
            forgedBody.put("operations", Collections.singletonList(operation("forged-row", "update",
                    "00000000-0000-0000-0000-000000000000",
                    Collections.singletonList(typedValue(1, "text", "forged")))));
            ResponseEntity<Map> forged = http.exchange(url("/api/v1/workspaces/" + workspaceId
                            + "/editors/" + editorId + "/results/0/changes/preview"), HttpMethod.POST,
                    new HttpEntity<Map<String, Object>>(forgedBody, authenticatedJsonHeaders(cookie)), Map.class);
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, forged.getStatusCode());
            assertEquals("STALE_RESULT", forged.getBody().get("code"));
            Map<String, Object> mixedBody = new HashMap<String, Object>();
            mixedBody.put("executionId", complete.get("executionId"));
            List<Map<String, Object>> operations = new ArrayList<Map<String, Object>>();
            operations.add(operation("update-one", "update", rowIds.get(0),
                    Collections.singletonList(typedValue(1, "text", "pending"))));
            operations.add(operation("insert-three", "insert", "",
                    Arrays.asList(typedValue(0, "text", "3"), typedValue(1, "text", "inserted"),
                            typedValue(2, "text", "DONE"))));
            operations.add(operation("delete-two", "delete", rowIds.get(1),
                    Collections.<Map<String, Object>>emptyList()));
            mixedBody.put("operations", operations);
            Map<String, Object> preview = exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                    + "/editors/" + editorId + "/results/0/changes/preview", mixedBody, cookie);
            assertEquals(3, ((List<?>) preview.get("previews")).size());
            Map<String, Object> mixed = exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                    + "/editors/" + editorId + "/results/0/changes", mixedBody, cookie);
            assertEquals(Arrays.asList("update-one", "insert-three", "delete-two"),
                    mixed.get("appliedOperationIds"));
            assertEquals(3, ((List<?>) mixed.get("rowPatches")).size());
            assertDatabaseValue(table, "before");
            Map<String, Object> rolledBack = exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                    + "/editors/" + editorId + "/transaction/rollback",
                    Collections.<String, Object>emptyMap(), cookie);
            assertEquals(1, ((List<?>) rolledBack.get("resultSnapshots")).size());
            assertDatabaseValue(table, "before");

            locked = executeSql(editorId, workspaceId, cookie, events,
                    "SELECT id, name, order_status FROM " + table + " FOR UPDATE");
            complete = executionComplete(locked);

            Map<String, Object> changeBody = new HashMap<String, Object>();
            changeBody.put("executionId", complete.get("executionId"));
            Map<String, Object> rowChange = new HashMap<String, Object>();
            rowChange.put("rowIndex", 0);
            Map<String, Object> cellChange = new HashMap<String, Object>();
            cellChange.put("columnIndex", 1); cellChange.put("value", "pending");
            rowChange.put("cells", Collections.singletonList(cellChange));
            changeBody.put("rows", Collections.singletonList(rowChange));
            Map<String, Object> changed = exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                    + "/editors/" + editorId + "/results/0/changes", changeBody, cookie);
            assertEquals(Boolean.TRUE, changed.get("resultChangesDirty"));
            assertDatabaseValue(table, "before");

            Map<String, Object> rejectedBody = new HashMap<String, Object>();
            rejectedBody.put("editorId", editorId); rejectedBody.put("scope", "script");
            rejectedBody.put("stopOnError", true); rejectedBody.put("text", "SELECT 1");
            ResponseEntity<Map> rejected = http.exchange(url("/api/v1/workspaces/" + workspaceId
                    + "/editors/" + editorId + "/executions"), HttpMethod.POST,
                    new HttpEntity<Map<String, Object>>(rejectedBody, authenticatedJsonHeaders(cookie)), Map.class);
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, rejected.getStatusCode());
            assertEquals("RESULT_CHANGES_DECISION_REQUIRED", rejected.getBody().get("code"));

            executeSql(editorId, workspaceId, cookie, events, "SELECT 1", "rollback");
            assertDatabaseValue(table, "before");

            locked = executeSql(editorId, workspaceId, cookie, events,
                    "SELECT id, name, order_status FROM " + table + " FOR UPDATE");
            complete = executionComplete(locked);
            changeBody.put("executionId", complete.get("executionId"));
            cellChange.put("columnIndex", 2);
            cellChange.put("value", "UNKNOWN");
            ResponseEntity<Map> invalidChange = http.exchange(url("/api/v1/workspaces/" + workspaceId
                            + "/editors/" + editorId + "/results/0/changes"), HttpMethod.POST,
                    new HttpEntity<Map<String, Object>>(changeBody, authenticatedJsonHeaders(cookie)), Map.class);
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, invalidChange.getStatusCode());
            assertEquals("RESULT_CHANGE_REJECTED", invalidChange.getBody().get("code"));
            assertTrue(String.valueOf(invalidChange.getBody().get("message")).contains("枚举可选值"));
            assertTrue(String.valueOf(invalidChange.getBody().get("message")).contains("order_status"));
            exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId + "/editors/" + editorId
                    + "/transaction/rollback", Collections.<String, Object>emptyMap(), cookie);

            locked = executeSql(editorId, workspaceId, cookie, events,
                    "SELECT id, name, order_status FROM " + table + " FOR UPDATE");
            complete = executionComplete(locked);
            changeBody.put("executionId", complete.get("executionId"));
            cellChange.put("columnIndex", 1);
            cellChange.put("value", "committed");
            exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                    + "/editors/" + editorId + "/results/0/changes", changeBody, cookie);
            executeSql(editorId, workspaceId, cookie, events, "SELECT 1", "commit");
            assertDatabaseValue(table, "committed");
        } finally {
            try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            socket.close();
            workspaces.expireNow(workspaceId);
        }
    }

    @SuppressWarnings("unchecked")
    private void assertCompletionSnapshot(String editorId, String workspaceId, String cookie,
                                          BlockingQueue<Map<String, Object>> events) throws Exception {
        events.clear();
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("loadId", "completion-integration");
        body.put("editorId", editorId);
        Map<String, Object> namespaceResponse = exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                + "/metadata/completion-namespaces", Collections.<String, Object>singletonMap("editorId", editorId), cookie);
        List<Map<String, Object>> available = (List<Map<String, Object>>) namespaceResponse.get("namespaces");
        List<Map<String, Object>> selected = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> namespace : available) {
            if (Boolean.TRUE.equals(namespace.get("system"))) continue;
            Map<String, Object> value = new HashMap<String, Object>();
            value.put("catalog", namespace.get("catalog")); value.put("schema", namespace.get("schema"));
            selected.add(value);
        }
        body.put("selectedNamespaces", selected);
        Map<String, Object> snapshot = exchange(HttpMethod.POST, "/api/v1/workspaces/" + workspaceId
                + "/metadata/completion-snapshot", body, cookie);
        assertEquals("mysql", snapshot.get("providerId"));
        assertEquals(1, snapshot.get("formatVersion"));
        List<Map<String, Object>> namespaces = (List<Map<String, Object>>) snapshot.get("namespaces");
        List<Map<String, Object>> objects = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> namespace : namespaces) {
            List<Map<String, Object>> namespaceObjects = (List<Map<String, Object>>) namespace.get("objects");
            objects.addAll(namespaceObjects);
            for (Map<String, Object> object : namespaceObjects) {
                columns.addAll((List<Map<String, Object>>) object.get("columns"));
            }
        }
        assertTrue(objects.stream().anyMatch(value -> "table".equals(value.get("kind"))
                && "result_column_comment".equals(value.get("name"))));
        assertTrue(columns.stream().anyMatch(value -> "订单编号".equals(value.get("remarks"))));
        assertEquals(2, columns.stream().filter(value -> "id".equals(value.get("name"))).count());

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
                        + rows + ") SELECT id, IF(id > 120, SLEEP(0.02), 0) AS page_delay FROM numbers");
    }

    private List<Map<String, Object>> executeSql(String editorId, String workspaceId, String cookie,
                                                  BlockingQueue<Map<String, Object>> events, String sql) throws Exception {
        return executeSql(editorId, workspaceId, cookie, events, sql, "");
    }

    private List<Map<String, Object>> executeSql(String editorId, String workspaceId, String cookie,
                                                  BlockingQueue<Map<String, Object>> events, String sql,
                                                  String resultTransactionAction) throws Exception {
        events.clear();
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("editorId", editorId);
        body.put("scope", "script");
        body.put("stopOnError", true);
        body.put("text", sql);
        if (!resultTransactionAction.isEmpty()) {
            body.put("resultTransactionAction", resultTransactionAction);
        }
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
            assertEquals("", columns.get(0).get("remarks"));
            assertEquals("", columns.get(1).get("remarks"));
            assertEquals("", columns.get(2).get("remarks"));
            return;
        }
        throw new AssertionError("Missing query.resultMeta columnDetails");
    }

    @SuppressWarnings("unchecked")
    private void assertMutationTarget(List<Map<String, Object>> events) {
        Map<String, Object> target = mutationTarget(events);
        assertEquals("`dbstudio`.`result_column_comment`", target.get("qualifiedName"));
        List<Map<String, Object>> keys = (List<Map<String, Object>>) target.get("uniqueKeys");
        assertEquals(Boolean.TRUE, keys.get(0).get("primary"));
        assertEquals(Arrays.asList(0), keys.get(0).get("resultColumnIndices"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutationTarget(List<Map<String, Object>> events) {
        for (Map<String, Object> event : events) if ("query.resultMeta".equals(event.get("type"))) {
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            Map<String, Object> target = (Map<String, Object>) payload.get("mutationTarget");
            assertTrue(target != null, "simple base-table query should expose a safe mutation target");
            return target;
        }
        throw new AssertionError("Missing query.resultMeta mutationTarget");
    }

    @SuppressWarnings("unchecked")
    private List<String> resultRowIds(List<Map<String, Object>> events) {
        List<String> result = new ArrayList<String>();
        for (Map<String, Object> event : events) if ("query.rows".equals(event.get("type"))) {
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            result.addAll((List<String>) payload.get("rowIds"));
        }
        return result;
    }

    private static Map<String, Object> typedValue(int columnIndex, String kind, String value) {
        Map<String, Object> encoded = new HashMap<String, Object>();
        encoded.put("kind", kind); encoded.put("value", value);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("columnIndex", columnIndex); result.put("value", encoded);
        return result;
    }

    private static Map<String, Object> operation(String operationId, String kind, String rowId,
                                                  List<Map<String, Object>> values) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("operationId", operationId); result.put("kind", kind);
        if (rowId != null && !rowId.isEmpty()) result.put("rowId", rowId);
        result.put("values", values);
        return result;
    }

    private void assertDatabaseValue(String table, String expected) throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM " + table + " WHERE id = 1")) {
            assertTrue(rows.next());
            assertEquals(expected, rows.getString(1));
        }
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

    private HttpHeaders authenticatedJsonHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin());
        headers.add(HttpHeaders.COOKIE, cookie);
        if (clientId != null) headers.add("X-DBStudio-Client-Id", clientId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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

    private Map<String, Object> awaitType(BlockingQueue<Map<String, Object>> events,
                                          String type, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Map<String, Object> event = events.poll(1, TimeUnit.SECONDS);
            if (event != null && type.equals(event.get("type"))) return event;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> executionComplete(List<Map<String, Object>> events) {
        for (Map<String, Object> event : events) if ("query.executionComplete".equals(event.get("type"))) {
            return (Map<String, Object>) event.get("payload");
        }
        throw new AssertionError("Missing query.executionComplete");
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
