package com.ragqa.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实随机端口Servlet+Security+Mapper+事务，数据库为隔离H2，无模型或搜索调用。 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "knowingorder.security.login-per-address-per-minute=1000",
        "knowingorder.security.login-global-per-minute=2000",
        "knowingorder.knowledge.synthetic-model-spaces=",
        "logging.level.com.ragqa=WARN", "spring.sql.init.mode=never", "server.address=127.0.0.1"
})
class EnterpriseSecurityHttpTest {
    // 明确为本次隔离测试的虚构口令，不用于任何本机/生产账号。
    private static final String TEST_PASSWORD = "Local-test-only#2026";
    private static final String TEST_PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(TEST_PASSWORD);
    private static final EnterpriseDatabase DB = database();
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB.dataSource::getURL);
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("spring.datasource.username", () -> "sa");
        properties.add("spring.datasource.password", () -> "");
    }

    private static EnterpriseDatabase database() {
        try {
            var db = new EnterpriseDatabase();
            db.seed();
            db.jdbc.update("UPDATE sys_user SET password_hash=?", TEST_PASSWORD_HASH);
            return db;
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @BeforeEach
    void reset() {
        DB.jdbc.update("UPDATE sys_user SET status=0, deleted=0, auth_version=1, password_hash=? WHERE id IN (1,2,3)", TEST_PASSWORD_HASH);
        DB.jdbc.update("UPDATE sys_enterprise SET status=0, permission_version=1");
        DB.jdbc.update("UPDATE sys_enterprise_member SET status=0, permission_version=1 WHERE id IN (101,102,103,201)");
    }

    @AfterAll
    static void close() throws Exception {
        DB.close();
    }

    @Test
    void anonymousAndForgedHeadersCannotReadEnterpriseData() throws Exception {
        var client = client();
        var response = client.send(HttpRequest.newBuilder(uri("/api/enterprises/1/documents/501"))
                .header("X-User-Id", "1").header("X-Member-Id", "101").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(tree(response).path("code").asInt()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("制度", "filePath", "password", "token");
    }

    @Test
    void actualPasswordLoginRotatesSessionAndExposesNoCredentials() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
        JsonNode token = csrf(client);
        String before = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        var loggedIn = login(client, token, "owner", TEST_PASSWORD);
        assertThat(loggedIn.statusCode()).isEqualTo(200);
        assertThat(tree(loggedIn).path("code").asInt()).isZero();
        assertThat(tree(loggedIn).path("data").path("userId").asLong()).isEqualTo(1);
        String after = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        assertThat(after).isNotEqualTo(before);
        assertThat(loggedIn.body()).doesNotContain(TEST_PASSWORD, "passwordHash", "$2a$", "authorities");
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(200);
        JsonNode account = tree(get(client, "/api/auth/me")).path("data");
        assertThat(account.path("enterprises").size()).isEqualTo(2);
        assertThat(account.path("enterprises").get(0).path("enterpriseId").asText()).isEqualTo("1");
        assertThat(account.path("enterprises").get(0).path("enterpriseName").asText()).isEqualTo("甲企业");
        assertThat(account.path("enterprises").get(0).path("role").asText()).isEqualTo("EMPLOYEE");
        DB.jdbc.update("UPDATE sys_enterprise_member SET status=1 WHERE id=201");
        assertThat(tree(get(client, "/api/auth/me")).path("data").path("enterprises").size()).isEqualTo(1);
        assertThat(tree(get(client, "/api/enterprises/1/conversations/1001")).path("data").path("title").asText())
                .isEqualTo("本人甲企业会话");
        assertThat(tree(get(client, "/api/enterprises/1/conversations/1002")).path("code").asInt()).isEqualTo(404);
        assertThat(tree(get(client, "/api/enterprises/1/conversations/2001")).path("code").asInt()).isEqualTo(404);
        assertThat(get(client, "/api/enterprises/1/documents/501").headers().firstValue("Cache-Control").orElse(""))
                .contains("no-store");
    }

    @Test
    void wrongUnknownDisabledAndDeletedAccountsHaveSameSafeFailure() throws Exception {
        var client = client();
        JsonNode token = csrf(client);
        var wrong = login(client, token, "owner", "Definitely-wrong#2026");
        var missing = login(client, token, "not_a_real_account", TEST_PASSWORD);
        DB.jdbc.update("UPDATE sys_user SET status=1 WHERE id=1");
        var disabled = login(client, token, "owner", TEST_PASSWORD);
        DB.jdbc.update("UPDATE sys_user SET status=0, deleted=1 WHERE id=1");
        var deleted = login(client, token, "owner", TEST_PASSWORD);
        for (var response : List.of(wrong, missing, disabled, deleted)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(tree(response).path("message").asText()).isEqualTo("账号或密码错误，或账号不可用");
        }
    }

    @Test
    void csrfProtectsLoginAndEveryStateChangingPrivateEntry() throws Exception {
        var client = client();
        var without = client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=owner&password=" + TEST_PASSWORD)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(without.statusCode()).isEqualTo(403);
        assertThat(login(client, csrf(client), "admin", TEST_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(post(client, "/api/enterprises/1/employees", "{}", null).statusCode()).isEqualTo(403);
        assertThat(post(client, "/api/auth/revoke-sessions", "{}", null).statusCode()).isEqualTo(403);
        assertThat(post(client, "/api/auth/logout", "{}", null).statusCode()).isEqualTo(403);
    }

    @Test
    void revocationDisablingAndLogoutInvalidateExistingLogin() throws Exception {
        var client = loggedIn("owner");
        assertThat(post(client, "/api/auth/revoke-sessions", "{}", csrf(client)).statusCode()).isEqualTo(200);
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(401);
        client = loggedIn("owner");
        DB.jdbc.update("UPDATE sys_user SET status=1 WHERE id=1");
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(401);
        DB.jdbc.update("UPDATE sys_user SET status=0 WHERE id=1");
        client = loggedIn("owner");
        assertThat(post(client, "/api/auth/logout", "{}", csrf(client)).statusCode()).isEqualTo(200);
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void adminCanProvisionEmployeeButEmployeeCannotProvisionOrReadOthersPrivateData() throws Exception {
        String username = "employee_" + UUID.randomUUID().toString().replace("-", "");
        var admin = loggedIn("admin");
        var created = post(admin, "/api/enterprises/1/employees", accountJson(username), csrf(admin));
        assertThat(tree(created).path("code").asInt()).isZero();
        long memberId = tree(created).path("data").path("memberId").asLong();
        String hash = DB.jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE username=?", String.class, username);
        assertThat(hash).isNotEqualTo(TEST_PASSWORD);
        assertThat(new BCryptPasswordEncoder().matches(TEST_PASSWORD, hash)).isTrue();
        var employee = loggedIn(username);
        assertThat(tree(post(employee, "/api/enterprises/1/employees", accountJson(username + "_x"), csrf(employee))).path("code").asInt())
                .isEqualTo(404);
        assertThat(tree(get(admin, "/api/enterprises/1/documents/501")).path("code").asInt()).isEqualTo(404);
        assertThat(tree(post(admin, "/api/enterprises/1/employees/" + memberId + "/status", "{\"status\":2}", csrf(admin))).path("code").asInt()).isZero();
        assertThat(tree(get(employee, "/api/enterprises/1/documents/501")).path("code").asInt()).isEqualTo(404);
        assertThat(tree(post(admin, "/api/enterprises/2/employees", accountJson(username + "_y"), csrf(admin))).path("code").asInt())
                .isEqualTo(404);
    }

    @Test
    void failureOfSecondWriteRollsBackUserAndDoesNotExposeRawError() throws Exception {
        String username = "rollback_" + UUID.randomUUID().toString().replace("-", "");
        var admin = loggedIn("admin");
        DB.jdbc.execute("ALTER TABLE sys_enterprise_member ADD CONSTRAINT test_member_failure CHECK(user_id<=3) NOCHECK");
        try {
            var response = post(admin, "/api/enterprises/1/employees", accountJson(username), csrf(admin));
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).doesNotContain(TEST_PASSWORD, "INSERT", "password_hash", "test_member_failure");
            assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, username)).isZero();
        } finally {
            DB.jdbc.execute("ALTER TABLE sys_enterprise_member DROP CONSTRAINT test_member_failure");
        }
    }

    @Test
    void simultaneousDuplicateProvisioningCreatesExactlyOneAccountAndMember() throws Exception {
        String username = "race_" + UUID.randomUUID().toString().replace("-", "");
        var admin = loggedIn("admin");
        var token = csrf(admin);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = executor.invokeAll(List.of(
                    (java.util.concurrent.Callable<Integer>) () -> tree(post(admin, "/api/enterprises/1/employees", accountJson(username), token)).path("code").asInt(),
                    () -> tree(post(admin, "/api/enterprises/1/employees", accountJson(username), token)).path("code").asInt()));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(0, 1000);
            assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, username)).isEqualTo(1);
            assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise_member m JOIN sys_user u ON u.id=m.user_id WHERE u.username=?", Integer.class, username)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void storageFailureIsVisibleForBothLoginAndExistingSession() throws Exception {
        var existing = loggedIn("owner");
        var anonymous = client();
        var token = csrf(anonymous);
        DB.jdbc.execute("ALTER TABLE sys_user RENAME TO sys_user_unavailable");
        try {
            assertThat(get(existing, "/api/auth/me").statusCode()).isEqualTo(503);
            var response = login(anonymous, token, "owner", TEST_PASSWORD);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("sys_user", "SELECT", TEST_PASSWORD);
        } finally {
            DB.jdbc.execute("ALTER TABLE sys_user_unavailable RENAME TO sys_user");
        }
    }

    @Test
    void malformedStateAndSqlInjectionTextCannotModifyAccounts() throws Exception {
        var anonymous = client();
        assertThat(login(anonymous, csrf(anonymous), "owner' OR 1=1 --", TEST_PASSWORD).statusCode()).isEqualTo(401);
        var admin = loggedIn("admin");
        var token = csrf(admin);
        int count = DB.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class);
        for (String body : List.of("{}", "{\"username\":\"bad' OR 1=1\",\"password\":\"invalid\"}",
                "{\"username\":\"valid_name\",\"password\":\"short\"}")) {
            assertThat(tree(post(admin, "/api/enterprises/1/employees", body, token)).path("code").asInt()).isEqualTo(400);
        }
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(count);
        for (String body : List.of("{}", "{\"status\":null}", "{\"status\":1.5}", "{\"status\":\"1\"}", "{\"status\":true}", "{\"status\":3}")) {
            assertThat(tree(post(admin, "/api/enterprises/1/employees/102/status", body, token)).path("code").asInt()).isEqualTo(400);
        }
        assertThat(DB.jdbc.queryForObject("SELECT status FROM sys_enterprise_member WHERE id=102", Integer.class)).isZero();
    }

    @Test
    void memberOrEnterpriseRevocationStopsResourcesWithoutDisablingWholeAccount() throws Exception {
        var client = loggedIn("owner");
        DB.jdbc.update("UPDATE sys_enterprise SET status=1 WHERE id=1");
        assertThat(tree(get(client, "/api/enterprises/1/documents/501")).path("code").asInt()).isEqualTo(404);
        DB.jdbc.update("UPDATE sys_enterprise SET status=0 WHERE id=1");
        DB.jdbc.update("UPDATE sys_enterprise_member SET status=2, permission_version=2 WHERE id=101");
        assertThat(tree(get(client, "/api/enterprises/1/messages/6001")).path("code").asInt()).isEqualTo(404);
        assertThat(tree(get(client, "/api/enterprises/1/tasks/9001/reference")).path("code").asInt()).isEqualTo(404);
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(tree(get(client, "/api/enterprises/2/conversations/2001")).path("code").asInt()).isZero();
    }

    @Test
    void sessionCookieIsHttpOnlyAndPrivateRequestBodyIsBounded() throws Exception {
        var client = client();
        var response = get(client, "/api/auth/csrf");
        assertThat(response.headers().allValues("Set-Cookie"))
                .anyMatch(value -> value.contains("HttpOnly") && value.contains("SameSite=Lax"));
        var oversized = post(client, "/api/enterprises/1/employees", "x".repeat(16 * 1024 + 1), null);
        assertThat(oversized.statusCode()).isEqualTo(413);
        assertThat(oversized.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void enterpriseAnswerRequiresSessionCsrfAndExplicitSpaceGrant() throws Exception {
        String path = "/api/enterprises/1/knowledge-spaces/401/answers";
        String question = "{\"question\":\"虚构餐补多少？\",\"allowExternal\":true,\"memberId\":201}";
        var anonymous = client();
        assertThat(post(anonymous, path, question, csrf(anonymous)).statusCode()).isEqualTo(401);
        var owner = loggedIn("owner");
        assertThat(post(owner, path, question, null).statusCode()).isEqualTo(403);
        var token = csrf(owner);
        assertThat(tree(get(owner, path + "/mode")).path("data").asText()).isEqualTo("LOCAL_EVIDENCE");
        assertThat(tree(get(owner, path.replace("401", "402") + "/mode")).path("code").asInt()).isEqualTo(404);
        var response = post(owner, path, question, token);
        assertThat(tree(response).path("code").asInt()).isZero();
        assertThat(tree(response).path("data").path("mode").asText()).isEqualTo("LOCAL_EVIDENCE");
        assertThat(tree(response).path("data").path("status").asText()).isEqualTo("NO_EVIDENCE");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(tree(post(owner, path.replace("401", "402"), question, token)).path("code").asInt()).isEqualTo(404);
        assertThat(tree(post(owner, path, "{\"question\":\" \"}", token)).path("code").asInt()).isEqualTo(400);
        assertThat(tree(get(owner, path + "/evidence/999999")).path("code").asInt()).isEqualTo(404);
    }

    private HttpClient loggedIn(String username) throws Exception {
        var client = client();
        var response = login(client, csrf(client), username, TEST_PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        return client;
    }

    @Test
    void durableWorkspaceRequiresSessionCsrfAndOwnerEvenWithForgedBody() throws Exception {
        String path = "/api/enterprises/1/knowledge-spaces/401/workspace";
        String chatId = UUID.randomUUID().toString(), requestId = UUID.randomUUID().toString();
        String body = json.writeValueAsString(java.util.Map.of("chatId", chatId, "requestId", requestId,
                "question", "仅虚构资料测试", "memberId", 102, "enterpriseId", 2));
        var anonymous = client();
        assertThat(post(anonymous, path + "/jobs", body, csrf(anonymous)).statusCode()).isEqualTo(401);
        var owner = loggedIn("owner");
        assertThat(post(owner, path + "/jobs", body, null).statusCode()).isEqualTo(403);
        var accepted = tree(post(owner, path + "/jobs", body, csrf(owner)));
        assertThat(accepted.path("code").asInt()).isZero();
        assertThat(accepted.path("data").path("id").asText()).isEqualTo(requestId);
        assertThat(accepted.toString()).doesNotContain("authVersion", "answerJson", "memberId");
        long deadline = accepted.path("data").path("deadlineMs").asLong();
        var repeated = tree(post(owner, path + "/jobs", body, csrf(owner)));
        assertThat(repeated.path("data").path("deadlineMs").asLong()).isEqualTo(deadline);
        var peer = loggedIn("peer");
        assertThat(tree(get(peer, path + "/jobs/" + requestId)).path("code").asInt()).isEqualTo(404);
        assertThat(tree(get(owner, path.replace("401", "402") + "/jobs/" + requestId)).path("code").asInt()).isEqualTo(404);
        assertThat(tree(get(owner, path + "/chats/" + chatId)).path("data").size()).isEqualTo(1);
        var cancelled = post(owner, path + "/jobs/" + requestId + "/cancel", "{}", csrf(owner));
        assertThat(tree(cancelled).path("code").asInt()).isZero();
        assertThat(cancelled.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void workspaceFeedbackWriteAlsoRequiresCsrfAndReadRequiresMaintain() throws Exception {
        String path = "/api/enterprises/1/knowledge-spaces/401/workspace/feedback";
        var owner = loggedIn("owner");
        assertThat(post(owner, path, "{}", null).statusCode()).isEqualTo(403);
        assertThat(tree(post(owner, path, "{}", csrf(owner))).path("code").asInt()).isEqualTo(400);
        assertThat(tree(get(owner, path)).path("code").asInt()).isEqualTo(404);
    }

    @Test
    void passwordChangeRequiresCsrfRejectsAmbiguousJsonAndInvalidatesOldSessions() throws Exception {
        var owner = loggedIn("owner");
        var second = loggedIn("owner");
        String replacement = "New-local-test-password#2026";
        String body = json.writeValueAsString(java.util.Map.of("previousPassword", TEST_PASSWORD, "newPassword", replacement));
        assertThat(post(owner, "/api/auth/password", body, null).statusCode()).isEqualTo(403);
        for (String malformed : List.of("null", body + "{}", body.replace("\"newPassword\":", "\"newPassword\":\"duplicate\",\"newPassword\":"),
                "{\"previousPassword\":123,\"newPassword\":\"0123456789abc\"}", body.substring(0, body.length() - 1) + ",\"userId\":2}"))
            assertThat(tree(post(owner, "/api/auth/password", malformed, csrf(owner))).path("code").asInt()).isEqualTo(400);
        var changed = post(owner, "/api/auth/password", body, csrf(owner));
        assertThat(tree(changed).path("code").asInt()).isZero();
        assertThat(changed.body()).doesNotContain(TEST_PASSWORD, replacement, "passwordHash");
        assertThat(get(second, "/api/auth/me").statusCode()).isEqualTo(401);
        var fresh = client();
        assertThat(login(fresh, csrf(fresh), "owner", replacement).statusCode()).isEqualTo(200);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    private JsonNode csrf(HttpClient client) throws Exception {
        return tree(get(client, "/api/auth/csrf")).path("data");
    }

    private HttpResponse<String> login(HttpClient client, JsonNode token, String username, String password) throws Exception {
        String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(uri("/api/auth/login")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header(token.path("headerName").asText(), token.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, JsonNode token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) {
            builder.header(token.path("headerName").asText(), token.path("token").asText());
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private JsonNode tree(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private String accountJson(String username) throws Exception {
        return json.writeValueAsString(java.util.Map.of("username", username, "password", TEST_PASSWORD));
    }
}
