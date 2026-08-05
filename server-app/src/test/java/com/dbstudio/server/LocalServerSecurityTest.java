package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dbstudio.open-browser=false",
        "dbstudio.local-access-token.enabled=true",
        "dbstudio.data-directory=${java.io.tmpdir}/dbstudio-server-test-${random.uuid}"
})
class LocalServerSecurityTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired LocalAccessToken token;
    @Autowired WorkspaceRegistry workspaces;
    @Autowired ObjectMapper mapper;

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
    @SuppressWarnings("unchecked")
    void persistsWorkspaceDraftAndRestoresOriginalEditorIdAfterRuntimeExpires() {
        HttpHeaders headers = authenticatedHeaders();
        String workspaceId = UUID.randomUUID().toString();
        ResponseEntity<Map> first = http.exchange(url("/api/v1/workspaces/" + workspaceId),
                HttpMethod.PUT, new HttpEntity<String>("{}", headers), Map.class);
        assertEquals(Boolean.TRUE, first.getBody().get("created"));
        String clientId = openWorkspace(headers, workspaceId);
        Map<String, Object> createdEditor = http.exchange(url("/api/v1/workspaces/" + workspaceId + "/editors"),
                HttpMethod.POST, new HttpEntity<Map<String,Object>>(new HashMap<String,Object>(), headers), Map.class).getBody();
        String editorId = String.valueOf(createdEditor.get("id"));
        Map<String, Object> invalidDraft = new HashMap<String, Object>();
        invalidDraft.put("title", "   "); invalidDraft.put("sqlText", "select 1");
        assertEquals(HttpStatus.BAD_REQUEST,http.exchange(url("/api/v1/workspaces/" + workspaceId + "/editors/" + editorId + "/draft"),
                HttpMethod.PUT,new HttpEntity<Map<String,Object>>(invalidDraft,headers),String.class).getStatusCode());
        assertEquals(createdEditor.get("title"),workspaces.require(workspaceId).editors().require(editorId).title());
        Map<String, Object> draft = new HashMap<String, Object>();
        draft.put("title", "未保存查询"); draft.put("sqlText", "select 42"); draft.put("dirty", true);
        draft.put("sortOrder", 0); draft.put("active", true);
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/workspaces/" + workspaceId + "/editors/" + editorId + "/draft"),
                HttpMethod.PUT, new HttpEntity<Map<String, Object>>(draft, headers), String.class).getStatusCode());
        assertEquals("未保存查询",workspaces.require(workspaceId).editors().require(editorId).title());
        workspaces.expireNow(workspaceId);
        headers.set("X-DBStudio-Client-Id", clientId);
        Map<String, Object> openBody = new HashMap<String, Object>(); openBody.put("clientId", clientId);
        ResponseEntity<Map> reopened = http.exchange(url("/api/v1/workspaces/" + workspaceId + "/open"),
                HttpMethod.POST, new HttpEntity<Map<String, Object>>(openBody, headers), Map.class);
        assertEquals(Boolean.TRUE, reopened.getBody().get("recoveryDecisionRequired"));
        Map<String, Object> recovery = new HashMap<String, Object>(); recovery.put("decision", "restore");
        recovery.put("processRestarted", false);
        ResponseEntity<Map> response = http.exchange(url("/api/v1/workspaces/" + workspaceId + "/recovery"),
                HttpMethod.POST, new HttpEntity<Map<String, Object>>(recovery, headers), Map.class);
        List<Map<String, Object>> restored = (List<Map<String, Object>>) response.getBody().get("editors");
        assertEquals(editorId, restored.get(0).get("id"));
        assertEquals("未保存查询", restored.get(0).get("title"));
        assertEquals("select 42", restored.get(0).get("content"));
        assertEquals(editorId, workspaces.require(workspaceId).editors().require(editorId).id().toString());
        assertEquals("未保存查询",workspaces.require(workspaceId).editors().require(editorId).title());
    }

    @Test
    @SuppressWarnings("unchecked")
    void respondsToWebSocketHeartbeatWithAWorkspacePong() throws Exception {
        HttpHeaders headers = authenticatedHeaders();
        String workspaceId = UUID.randomUUID().toString();
        http.exchange(url("/api/v1/workspaces/" + workspaceId), HttpMethod.PUT,
                new HttpEntity<String>("{}", headers), String.class);
        String clientId = openWorkspace(headers, workspaceId);
        final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<Map<String, Object>>();
        WebSocketHttpHeaders socketHeaders = new WebSocketHttpHeaders();
        socketHeaders.setOrigin("http://127.0.0.1:" + port);
        socketHeaders.add(HttpHeaders.COOKIE, headers.getFirst(HttpHeaders.COOKIE));
        WebSocketSession socket = new StandardWebSocketClient().doHandshake(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                events.add(mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() { }));
            }
        }, socketHeaders, URI.create("ws://127.0.0.1:" + port + "/api/v1/events?workspaceId=" + workspaceId
                + "&clientId=" + clientId))
                .get(10, TimeUnit.SECONDS);
        try {
            awaitEvent(events, "workspace.ready");
            socket.sendMessage(new TextMessage("ping"));
            Map<String, Object> pong = awaitEvent(events, "workspace.pong");
            assertNotNull(((Map<String, Object>) pong.get("payload")).get("serverTime"));
        } finally {
            socket.close();
        }
    }

    @Test
    void reconnectsImmediatelyAfterThePreviousBrowserTabCloses() throws Exception {
        HttpHeaders headers = authenticatedHeaders();
        String workspaceId = UUID.randomUUID().toString();
        http.exchange(url("/api/v1/workspaces/" + workspaceId), HttpMethod.PUT,
                new HttpEntity<String>("{}", headers), String.class);

        String firstClientId = openWorkspace(headers, workspaceId);
        BlockingQueue<Map<String, Object>> firstEvents = new LinkedBlockingQueue<Map<String, Object>>();
        WebSocketSession first = connectWorkspaceSocket(headers, workspaceId, firstClientId, firstEvents);
        awaitEvent(firstEvents, "workspace.ready");
        first.close();
        long disconnectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (workspaces.require(workspaceId).events().connected()
                && System.nanoTime() < disconnectDeadline) Thread.sleep(20L);

        String secondClientId = openWorkspace(headers, workspaceId);
        BlockingQueue<Map<String, Object>> secondEvents = new LinkedBlockingQueue<Map<String, Object>>();
        WebSocketSession second = connectWorkspaceSocket(headers, workspaceId, secondClientId, secondEvents);
        try {
            assertEquals(workspaceId, ((Map<?, ?>) awaitEvent(secondEvents, "workspace.ready")
                    .get("payload")).get("workspaceId"));
        } finally {
            second.close();
        }
    }

    @Test
    void rejectsForeignOrigin() throws Exception {
        LocalRequestFilter filter = new LocalRequestFilter(token, new ObjectMapper(), workspaces);
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
        LocalRequestFilter filter = new LocalRequestFilter(token, new ObjectMapper(), workspaces);
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
        assertTrue(defaults.getBody().contains("\"editor.dangerousStatementWarningEnabled\":\"true\""));
        assertTrue(defaults.getBody().contains("\"result.copyHeaderOnDoubleClick\":\"true\""));
        assertTrue(defaults.getBody().contains("\"result.copySeparator\":\"comma\""));
        assertTrue(defaults.getBody().contains("\"result.headerSortingEnabled\":\"true\""));
        assertTrue(defaults.getBody().contains("\"result.headerFilteringEnabled\":\"true\""));
        assertTrue(defaults.getBody().contains("\"result.showColumnRemarksInHeader\":\"false\""));
        assertTrue(defaults.getBody().contains("\"result.scrollOptimizationEnabled\":\"false\""));
        assertTrue(defaults.getBody().contains("\"result.scrollOptimizationBufferScreens\":\"1\""));
        assertTrue(defaults.getBody().contains("\"statusBar.showSelectedColumnRemarks\":\"true\""));
        assertTrue(defaults.getBody().contains("\"connection.maxActiveSessions\":\"10\""));
        assertTrue(defaults.getBody().contains("\"connection.autoCommit\":\"false\""));
        assertTrue(defaults.getBody().contains("\"connection.idleTimeoutMinutes\":\"10\""));
        assertTrue(defaults.getBody().contains("\"connection.transactionDisconnectRollbackMinutes\":\"10\""));
        assertTrue(defaults.getBody().contains("\"editor.completionPreciseMatchingEnabled\":\"false\""));
        assertTrue(defaults.getBody().contains("\"editor.completionSnippets\":\"[]\""));
        assertTrue(defaults.getBody().contains("\"editor.minimapEnabled\":\"true\""));
        assertTrue(defaults.getBody().contains("\"editor.wordWrapEnabled\":\"false\""));

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

        for (String key : Arrays.asList("result.headerSortingEnabled", "result.headerFilteringEnabled",
                "result.showColumnRemarksInHeader", "result.scrollOptimizationEnabled",
                "statusBar.showSelectedColumnRemarks", "editor.minimapEnabled", "editor.wordWrapEnabled",
                "editor.dangerousStatementWarningEnabled")) {
            setting.put("key", key);
            setting.put("value", "false");
            assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                    new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
            setting.put("value", "enabled");
            ResponseEntity<String> invalidToggle = http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                    new HttpEntity<Map<String, String>>(setting, headers), String.class);
            assertEquals(HttpStatus.BAD_REQUEST, invalidToggle.getStatusCode());
            assertTrue(invalidToggle.getBody().contains("INVALID_SETTING"));
        }
        setting.put("key", "result.copySeparator");
        setting.put("value", "space");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        setting.put("key", "result.scrollOptimizationBufferScreens");
        for (String screens : Arrays.asList("0.5", "1", "1.5", "3")) {
            setting.put("value", screens);
            assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                    new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        }
        for (String screens : Arrays.asList("0", "1.25", "3.5", "invalid")) {
            setting.put("value", screens);
            assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                    new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        }

        setting.put("key", "connection.maxActiveSessions");
        setting.put("value", "12");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        setting.put("value", "101");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        setting.put("key", "connection.autoCommit");
        setting.put("value", "true");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        setting.put("value", "enabled");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        setting.put("key", "connection.idleTimeoutMinutes");
        setting.put("value", "30");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        setting.put("value", "0");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        setting.put("key", "connection.transactionDisconnectRollbackMinutes");
        setting.put("value", "20");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        setting.put("value", "1441");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
    }

    @Test
    void validatesAndPersistsShortcutBindings() {
        HttpHeaders headers = authenticatedHeaders();
        ResponseEntity<String> defaults = http.exchange(url("/api/v1/settings"), HttpMethod.GET,
                new HttpEntity<String>(headers), String.class);
        assertEquals(HttpStatus.OK, defaults.getStatusCode());
        assertTrue(defaults.getBody().contains("\\\"query.executeCurrent\\\":\\\"F8\\\""));
        assertTrue(defaults.getBody().contains("\\\"query.executeCurrentNewTab\\\":null"));
        assertTrue(defaults.getBody().contains("\\\"query.executeAll\\\":\\\"F7\\\""));
        assertTrue(defaults.getBody().contains("\\\"query.cancel\\\":\\\"Shift+Escape\\\""));
        assertTrue(defaults.getBody().contains("\\\"editor.uppercase\\\":null"));
        assertTrue(defaults.getBody().contains("\\\"editor.lowercase\\\":null"));
        assertTrue(defaults.getBody().contains("\\\"editor.toggleLineComment\\\":null"));
        assertTrue(defaults.getBody().contains("\\\"editor.toggleBlockComment\\\":null"));

        Map<String, String> setting = new HashMap<String, String>();
        setting.put("key", "keyboard.shortcuts");
        setting.put("value", "{\"query.executeCurrent\":\"Mod+8\",\"query.executeCurrentNewTab\":\"Mod+Alt+8\",\"query.executeAll\":null,"
                + "\"query.cancel\":\"Shift+Escape\",\"editor.compact\":\"Mod+Alt+M\","
                + "\"editor.toggleMinimap\":null,\"editor.toggleWordWrap\":null,"
                + "\"editor.uppercase\":\"Mod+Alt+U\",\"editor.lowercase\":null,"
                + "\"editor.toggleLineComment\":null,\"editor.toggleBlockComment\":null,"
                + "\"editor.complete\":\"F6\"}");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        for (String invalid : Arrays.asList(
                "{\"unknown.action\":\"F9\"}",
                "{\"file.newQuery\":\"Mod+C\"}",
                "{\"file.newQuery\":\"F9\",\"file.openSql\":\"F9\"}",
                "{\"query.cancel\":\"Escape\"}",
                "{\"file.newQuery\":\"N\"}",
                "not-json")) {
            setting.put("value", invalid);
            ResponseEntity<String> rejected = http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                    new HttpEntity<Map<String, String>>(setting, headers), String.class);
            assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
            assertTrue(rejected.getBody().contains("INVALID_SETTING"));
        }
    }

    @Test
    void validatesAndPersistsSqlCompletionSettings() {
        HttpHeaders headers = authenticatedHeaders();
        Map<String, String> setting = new HashMap<String, String>();
        setting.put("key", "editor.completionPreciseMatchingEnabled");
        setting.put("value", "true");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        setting.put("value", "enabled");
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        setting.put("key", "editor.completionSnippets");
        setting.put("value", "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\","
                + "\"trigger\":\"sf\",\"remarks\":\"通用查询\",\"sql\":\"select * from\"}]");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());
        setting.put("value", "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\","
                + "\"trigger\":\"sf\",\"remarks\":\"通用查询\","
                + "\"sql\":\"select * from t where c = '${column_value}' "
                + "and d = ${column_value} and e = ${中文变量1}\"}]");
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<Map<String, String>>(setting, headers), String.class).getStatusCode());

        for (String invalid : Arrays.asList(
                "not-json",
                "[{\"id\":\"bad\",\"trigger\":\"sf\",\"remarks\":\"\",\"sql\":\"select 1\"}]",
                "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\",\"trigger\":\"select all\","
                        + "\"remarks\":\"\",\"sql\":\"select 1\"}]",
                "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\",\"trigger\":\"sf\","
                        + "\"remarks\":\"\",\"sql\":\"\"}]",
                "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\",\"trigger\":\"sf\","
                        + "\"remarks\":\"\",\"sql\":\"select 1\"},"
                        + "{\"id\":\"dbf3fc10-3b72-41b8-a83f-9f786314303e\",\"trigger\":\"SF\","
                        + "\"remarks\":\"\",\"sql\":\"select 2\"}]",
                "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\",\"trigger\":\"sf\","
                        + "\"remarks\":\"\",\"sql\":\"select ${}\"}]",
                "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\",\"trigger\":\"sf\","
                        + "\"remarks\":\"\",\"sql\":\"select ${1name}\"}]",
                "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\",\"trigger\":\"sf\","
                        + "\"remarks\":\"\",\"sql\":\"select ${name-value}\"}]",
                "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\",\"trigger\":\"sf\","
                        + "\"remarks\":\"\",\"sql\":\"select ${missing\"}]",
                "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\",\"trigger\":\"sf\","
                        + "\"remarks\":\"\",\"sql\":\"select ${outer${inner}}\"}]")) {
            setting.put("value", invalid);
            ResponseEntity<String> rejected = http.exchange(url("/api/v1/settings"), HttpMethod.PUT,
                    new HttpEntity<Map<String, String>>(setting, headers), String.class);
            assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
            assertTrue(rejected.getBody().contains("INVALID_SETTING"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void movesConnectionProfileAcrossSystemsWithoutChangingRevision() {
        HttpHeaders headers = authenticatedHeaders();
        String workspaceId = UUID.randomUUID().toString();
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/workspaces/" + workspaceId), HttpMethod.PUT,
                new HttpEntity<String>("{}", headers), String.class).getStatusCode());
        openWorkspace(headers, workspaceId);

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

    @Test
    void completionSnapshotRequiresExactlyOneProfileOrEditorSource() {
        HttpHeaders headers = authenticatedHeaders();
        String workspaceId = UUID.randomUUID().toString();
        assertEquals(HttpStatus.OK, http.exchange(url("/api/v1/workspaces/" + workspaceId), HttpMethod.PUT,
                new HttpEntity<String>("{}", headers), String.class).getStatusCode());
        openWorkspace(headers, workspaceId);

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("loadId", "completion-source-validation");
        ResponseEntity<String> missing = http.exchange(url("/api/v1/workspaces/" + workspaceId
                        + "/metadata/completion-snapshot"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(body, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertTrue(missing.getBody().contains("INVALID_COMPLETION_SOURCE"));

        body.put("profileId", UUID.randomUUID().toString());
        body.put("editorId", UUID.randomUUID().toString());
        ResponseEntity<String> duplicate = http.exchange(url("/api/v1/workspaces/" + workspaceId
                        + "/metadata/completion-snapshot"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(body, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, duplicate.getStatusCode());
        assertTrue(duplicate.getBody().contains("INVALID_COMPLETION_SOURCE"));

        body.remove("editorId");
        body.remove("profileId");
        ResponseEntity<String> missingStream = http.exchange(url("/api/v1/workspaces/" + workspaceId
                        + "/metadata/completion-snapshot-stream"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(body, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, missingStream.getStatusCode());
        assertTrue(missingStream.getBody().contains("INVALID_COMPLETION_SOURCE"));

        Map<String, Object> structureBody = new HashMap<String, Object>();
        structureBody.put("schema", "CBSAC");
        structureBody.put("table", "CUSTOMERS");
        ResponseEntity<String> missingEditor = http.exchange(url("/api/v1/workspaces/" + workspaceId
                        + "/metadata/completion-table-structure"), HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(structureBody, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, missingEditor.getStatusCode());
        assertTrue(missingEditor.getBody().contains("EDITOR_REQUIRED"));
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

    private String openWorkspace(HttpHeaders headers, String workspaceId) {
        String clientId = UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<String, Object>(); body.put("clientId", clientId);
        ResponseEntity<String> opened = http.exchange(url("/api/v1/workspaces/" + workspaceId + "/open"),
                HttpMethod.POST, new HttpEntity<Map<String, Object>>(body, headers), String.class);
        assertEquals(HttpStatus.OK, opened.getStatusCode());
        headers.set("X-DBStudio-Client-Id", clientId);
        return clientId;
    }

    private WebSocketSession connectWorkspaceSocket(HttpHeaders headers, String workspaceId, String clientId,
                                                     final BlockingQueue<Map<String, Object>> events)
            throws Exception {
        WebSocketHttpHeaders socketHeaders = new WebSocketHttpHeaders();
        socketHeaders.setOrigin("http://127.0.0.1:" + port);
        socketHeaders.add(HttpHeaders.COOKIE, headers.getFirst(HttpHeaders.COOKIE));
        return new StandardWebSocketClient().doHandshake(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                events.add(mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() { }));
            }
        }, socketHeaders, URI.create("ws://127.0.0.1:" + port + "/api/v1/events?workspaceId=" + workspaceId
                + "&clientId=" + clientId)).get(10, TimeUnit.SECONDS);
    }

    private Map<String, Object> awaitEvent(BlockingQueue<Map<String, Object>> events, String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Map<String, Object> event = events.poll(1, TimeUnit.SECONDS);
            if (event != null && type.equals(event.get("type"))) return event;
        }
        throw new AssertionError("Timed out waiting for " + type);
    }

    private String url(String path) { return "http://127.0.0.1:" + port + path; }
}
