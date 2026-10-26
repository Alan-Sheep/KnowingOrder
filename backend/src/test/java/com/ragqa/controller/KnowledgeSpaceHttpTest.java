package com.ragqa.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 随机端口真实HTTP、Security和事务测试；仅使用隔离H2及虚构凭据，不调用外部服务。 */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "knowingorder.security.login-per-address-per-minute=1000",
        "knowingorder.security.login-global-per-minute=2000",
        "logging.level.com.ragqa=WARN", "logging.level.com.ragqa.common.aspect.LogAspect=INFO",
        "spring.sql.init.mode=never", "server.address=127.0.0.1"
})
class KnowledgeSpaceHttpTest {
    private static final String COLLECTION = "/api/enterprises/1/knowledge-spaces";
    // 仅为隔离测试创建的虚构口令，不读取或复用本机管理员配置。
    private static final String TEST_PASSWORD = "Space-http-test-only#2026";
    private static final EnterpriseDatabase DB = database();
    private static final String ORIGINALS = "E:/Codex/test-artifacts/knowledge-http-" + UUID.randomUUID();
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper json;
    private int spacesBefore;
    private int grantsBefore;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB.dataSource::getURL);
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("spring.datasource.username", () -> "sa");
        properties.add("spring.datasource.password", () -> "");
        properties.add("knowingorder.knowledge.storage-root", () -> ORIGINALS);
    }

    private static EnterpriseDatabase database() {
        try {
            var db = new EnterpriseDatabase();
            db.seed();
            String encoded = new BCryptPasswordEncoder(12).encode(TEST_PASSWORD);
            db.jdbc.update("UPDATE sys_user SET password_hash = ?", encoded);
            return db;
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @BeforeEach
    void reset() {
        DB.jdbc.update("UPDATE sys_user SET status = 0, deleted = 0, auth_version = 1");
        DB.jdbc.update("UPDATE sys_enterprise SET status = 0, permission_version = 1");
        DB.jdbc.update("UPDATE sys_enterprise_member SET status = 0, permission_version = 1");
        DB.jdbc.update("UPDATE sys_enterprise_member SET role = 'ADMIN' WHERE id = 103");
        spacesBefore = DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_space", Integer.class);
        grantsBefore = DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_grant", Integer.class);
    }

    @AfterAll
    static void close() throws Exception {
        DB.close();
    }

    @Test
    void administratorCreatesAndReadsSpaceWithoutLoggingPrivateName(CapturedOutput output) throws Exception {
        var admin = loggedIn("admin");
        String privateName = "内部名称仅用于测试-" + UUID.randomUUID();
        var response = post(admin.client(), COLLECTION, body(" \t" + privateName + "\u3000"), admin.csrf());
        assertResponse(response, 200, 0);
        JsonNode data = tree(response).path("data");
        assertThat(data.path("id").isTextual()).isTrue();
        long id = data.path("id").asLong(-1);
        assertThat(id).isPositive();
        assertThat(data.path("name").asText()).isEqualTo(privateName);
        assertThat(data.path("permissionVersion").asLong(-1)).isEqualTo(1);
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertResponse(get(admin.client(), COLLECTION + "/" + id), 200, 0);
        var owner = loggedIn("owner");
        assertResponse(get(owner.client(), COLLECTION + "/" + id), 200, 404);
        assertCounts(1, 1);
        assertThat(output.getAll()).contains("KnowledgeSpaceController#create", "<正文不记录>")
                .doesNotContain(privateName);
    }

    @Test
    void organizationRequiresLoginAdminAndCsrfAndStrictJsonTypes() throws Exception {
        String root = "/api/enterprises/1/organization";
        assertResponse(get(client(), root + "/members"), 401, 401);
        var employee = loggedIn("owner");
        assertResponse(get(employee.client(), root + "/departments"), 200, 404);
        var admin = loggedIn("admin");
        assertResponse(post(admin.client(), root + "/departments", "{\"name\":\"禁止无CSRF\",\"expectedEnterpriseVersion\":1}", null), 403, 403);
        for (String bad : List.of("1.5", "true", "\"1\"", "null", "9223372036854775808")) {
            assertResponse(post(admin.client(), root + "/departments", "{\"name\":\"错误版本\",\"expectedEnterpriseVersion\":" + bad + "}", admin.csrf()), 200, 400);
        }
        for (String bad : List.of("1.5", "true", "\"1\"", "null", "3")) {
            assertResponse(post(admin.client(), root + "/members/101", "{\"expectedVersion\":1,\"departmentId\":null,\"status\":" + bad + "}", admin.csrf()), 200, 400);
        }
        assertResponse(post(admin.client(), root + "/members/101", "{\"expectedVersion\":1,\"status\":0}", admin.csrf()), 200, 400);
        assertResponse(post(admin.client(), root + "/members/101", "{\"expectedVersion\":1,\"departmentId\":11,\"status\":0}", admin.csrf()), 200, 400);
        assertResponse(get(admin.client(), root + "/members?pageSize=101"), 200, 400);
    }

    @Test
    void organizationDepartmentLifecycleIsVersionedAndPrivate(CapturedOutput output) throws Exception {
        var admin = loggedIn("admin");
        String root = "/api/enterprises/1/organization";
        String name = "部门私有标记-" + UUID.randomUUID();
        var created = post(admin.client(), root + "/departments", json.writeValueAsString(Map.of("name", name, "expectedEnterpriseVersion", 1)), admin.csrf());
        assertResponse(created, 200, 0);
        var data = tree(created).path("data");
        assertThat(data.path("enterprisePermissionVersion").asInt()).isEqualTo(2);
        assertThat(data.path("department").path("id").isTextual()).isTrue();
        String id = data.path("department").path("id").asText();
        assertResponse(post(admin.client(), root + "/departments/" + id,
                json.writeValueAsString(Map.of("name", name, "status", 1, "expectedEnterpriseVersion", 2)), admin.csrf()), 200, 0);
        assertResponse(post(admin.client(), root + "/departments/" + id,
                json.writeValueAsString(Map.of("name", name, "status", 0, "expectedEnterpriseVersion", 2)), admin.csrf()), 200, 409);
        var listing = get(admin.client(), root + "/departments"); assertResponse(listing, 200, 0);
        assertThat(tree(listing).path("data").path("enterprisePermissionVersion").asLong()).isEqualTo(3);
        assertThat(listing.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(output.getAll()).contains("EnterpriseOrganizationController#create").doesNotContain(name);
    }

    @Test
    void organizationMemberUpdateCannotUseStaleVersionOrManageAdministrator() throws Exception {
        var admin = loggedIn("admin");
        String root = "/api/enterprises/1/organization";
        try {
            var updated = post(admin.client(), root + "/members/101", "{\"expectedVersion\":1,\"departmentId\":null,\"status\":1}", admin.csrf());
            assertResponse(updated, 200, 0);
            assertThat(tree(updated).path("data").path("permissionVersion").asLong()).isEqualTo(2);
            assertThat(tree(updated).path("data").path("departmentId").isNull()).isTrue();
            assertThat(tree(updated).path("data").path("id").isTextual()).isTrue();
            assertThat(updated.body()).doesNotContain("password", "hash", "authVersion");
            assertResponse(post(admin.client(), root + "/members/101", "{\"expectedVersion\":1,\"departmentId\":null,\"status\":0}", admin.csrf()), 200, 409);
            assertResponse(post(admin.client(), root + "/members/103", "{\"expectedVersion\":1,\"departmentId\":null,\"status\":1}", admin.csrf()), 200, 404);
            assertResponse(post(admin.client(), root + "/members/201", "{\"expectedVersion\":1,\"departmentId\":null,\"status\":1}", admin.csrf()), 200, 404);
            assertResponse(get(admin.client(), "/api/enterprises/2/organization/members"), 200, 404);
        } finally { DB.jdbc.update("UPDATE sys_enterprise_member SET department_id=11,status=0,permission_version=1 WHERE id=101"); }
    }

    @Test
    void suppliedIdentifiersAndPermissionsCannotOverrideServerIdentity() throws Exception {
        var admin = loggedIn("admin");
        String forged = """
                {"name":"服务端确定归属","id":401,"enterpriseId":2,"userId":1,"memberId":201,
                 "role":"EMPLOYEE","permission":"READ","status":1,"permissionVersion":999}
                """;
        var response = post(admin.client(), COLLECTION, forged, admin.csrf());
        assertResponse(response, 200, 0);
        long id = tree(response).path("data").path("id").asLong(-1);
        assertThat(id).isPositive().isNotEqualTo(401L);
        var stored = DB.jdbc.queryForMap("SELECT * FROM rag_knowledge_space WHERE id = ?", id);
        assertThat(((Number) stored.get("enterprise_id")).longValue()).isEqualTo(1);
        assertThat(((Number) stored.get("status")).intValue()).isZero();
        assertThat(((Number) stored.get("permission_version")).longValue()).isEqualTo(1);
        var grant = DB.jdbc.queryForMap("SELECT * FROM rag_knowledge_grant WHERE knowledge_space_id = ?", id);
        assertThat(((Number) grant.get("member_id")).longValue()).isEqualTo(103);
        assertThat(grant.get("permission")).isEqualTo("MAINTAIN");
        assertThat(DB.jdbc.queryForObject("SELECT name FROM rag_knowledge_space WHERE id = 401", String.class))
                .isEqualTo("成员授权");
        assertCounts(1, 1);
    }

    @Test
    void anonymousClientCannotAuthenticateWithForgedIdentityHeaders() throws Exception {
        var client = client();
        var token = csrf(client);
        var response = client.send(HttpRequest.newBuilder(uri(COLLECTION)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(token.path("headerName").asText(), token.path("token").asText())
                .header("X-User-Id", "3").header("X-Member-Id", "103").header("X-Role", "ADMIN")
                .POST(HttpRequest.BodyPublishers.ofString(body("不能创建"))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertResponse(response, 401, 401);
        assertCounts(0, 0);
    }

    @Test
    void authenticatedCreationRequiresValidCsrfToken() throws Exception {
        var admin = loggedIn("admin");
        assertResponse(post(admin.client(), COLLECTION, body("没有CSRF"), null), 403, 403);
        var incorrect = json.createObjectNode().put("headerName", admin.csrf().path("headerName").asText())
                .put("token", "invalid-test-token");
        assertResponse(post(admin.client(), COLLECTION, body("错误CSRF"), incorrect), 403, 403);
        assertCounts(0, 0);
    }

    @Test
    void employeeCannotEscalateRoleUsingRequestBody() throws Exception {
        var employee = loggedIn("owner");
        var response = post(employee.client(), COLLECTION,
                "{\"name\":\"不能越权\",\"role\":\"ADMIN\",\"memberId\":103,\"userId\":3}", employee.csrf());
        assertResponse(response, 200, 404);
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @ValueSource(longs = {2, 9999})
    void otherEnterpriseAndMissingEnterpriseAreBothUnavailable(long enterpriseId) throws Exception {
        var admin = loggedIn("admin");
        assertResponse(post(admin.client(), "/api/enterprises/" + enterpriseId + "/knowledge-spaces",
                body("不能越界"), admin.csrf()), 200, 404);
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @CsvSource({"0,200", "-1,200", "abc,400", "9223372036854775808,400"})
    void invalidEnterpriseIdentifierUsesExistingErrorContract(String enterpriseId, int httpStatus) throws Exception {
        var admin = loggedIn("admin");
        assertResponse(post(admin.client(), "/api/enterprises/" + enterpriseId + "/knowledge-spaces",
                body("非法编号"), admin.csrf()), httpStatus, 400);
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]", "{\"name\":", "{}", "{\"name\":null}",
            "{\"name\":123}", "{\"name\":true}", "{\"name\":[]}", "{\"name\":{}}"})
    void malformedOrNonStringNameIsRejectedBeforeCallingService(String requestBody) throws Exception {
        var admin = loggedIn("admin");
        assertResponse(post(admin.client(), COLLECTION, requestBody, admin.csrf()), 400, 400);
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void blankOrOverlongNameUsesServiceValidation(String name) throws Exception {
        var admin = loggedIn("admin");
        assertResponse(post(admin.client(), COLLECTION, body(name), admin.csrf()), 200, 400);
        assertCounts(0, 0);
    }

    private static Stream<String> invalidNames() {
        return Stream.of("", " \t\n\u3000", "文".repeat(129));
    }

    @Test
    void maximumLengthAfterTrimmingIsAccepted() throws Exception {
        var admin = loggedIn("admin");
        String name = "知".repeat(128);
        var response = post(admin.client(), COLLECTION, body(" " + name + " "), admin.csrf());
        assertResponse(response, 200, 0);
        assertThat(tree(response).path("data").path("name").asText()).isEqualTo(name);
        assertCounts(1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE sys_user SET status = 1 WHERE id = 3",
            "UPDATE sys_user SET deleted = 1 WHERE id = 3", "UPDATE sys_user SET auth_version = 2 WHERE id = 3"})
    void revokedLoginCannotCreateSpace(String fixedStatement) throws Exception {
        var admin = loggedIn("admin");
        DB.jdbc.update(fixedStatement);
        assertResponse(post(admin.client(), COLLECTION, body("旧登录不生效"), admin.csrf()), 401, 401);
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE sys_enterprise SET status = 1 WHERE id = 1",
            "UPDATE sys_enterprise_member SET status = 1 WHERE id = 103",
            "UPDATE sys_enterprise_member SET status = 2 WHERE id = 103",
            "UPDATE sys_enterprise_member SET role = 'EMPLOYEE' WHERE id = 103"})
    void changedMembershipStopsCreationWithoutDisablingAccount(String fixedStatement) throws Exception {
        var admin = loggedIn("admin");
        DB.jdbc.update(fixedStatement);
        assertResponse(post(admin.client(), COLLECTION, body("旧权限不生效"), admin.csrf()), 200, 404);
        assertResponse(get(admin.client(), "/api/auth/me"), 200, 0);
        assertCounts(0, 0);
    }

    @Test
    void grantConstraintFailureReturnsSafeErrorAndRollsBackCreatedSpace() throws Exception {
        var admin = loggedIn("admin");
        DB.jdbc.execute("ALTER TABLE rag_knowledge_grant ADD CONSTRAINT test_space_creator_failure CHECK(member_id <> 103) NOCHECK");
        try {
            var response = post(admin.client(), COLLECTION, body("授权失败必须回滚"), admin.csrf());
            assertResponse(response, 500, 500);
            assertThat(response.body()).doesNotContain("INSERT", "rag_knowledge", "test_space_creator_failure",
                    "password", TEST_PASSWORD);
            assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
            assertCounts(0, 0);
        } finally {
            DB.jdbc.execute("ALTER TABLE rag_knowledge_grant DROP CONSTRAINT test_space_creator_failure");
        }
    }

    @Test
    void oversizedRequestIsBoundedBeforeCreation() throws Exception {
        var admin = loggedIn("admin");
        assertResponse(post(admin.client(), COLLECTION, body("x".repeat(16 * 1024 + 1)), admin.csrf()), 413, 413);
        assertCounts(0, 0);
    }

    @Test
    void sqlLookingNameRemainsDataAndDoesNotChangeAccountTable() throws Exception {
        var admin = loggedIn("admin");
        String name = "制度'); DROP TABLE sys_user; --";
        var response = post(admin.client(), COLLECTION, body(name), admin.csrf());
        assertResponse(response, 200, 0);
        long id = tree(response).path("data").path("id").asLong(-1);
        assertThat(DB.jdbc.queryForObject("SELECT name FROM rag_knowledge_space WHERE id = ?", String.class, id))
                .isEqualTo(name);
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(3);
        assertCounts(1, 1);
    }

    @Test
    void listOnlyReturnsExplicitlyAuthorizedSpaces() throws Exception {
        var owner = loggedIn("owner");
        var response = get(owner.client(), COLLECTION + "?pageNum=1&pageSize=1");
        assertResponse(response, 200, 0);
        var page = tree(response).path("data");
        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(page.path("records").size()).isEqualTo(1);
        assertThat(page.path("records").get(0).path("id").asText()).isEqualTo("401");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.body()).doesNotContain("未授权", "乙企业空间");
        assertResponse(get(client(), COLLECTION), 401, 401);
        assertCounts(0, 0);
    }

    @ParameterizedTest
    @CsvSource({"0,1,200", "1,0,200", "1,501,200", "abc,20,400",
            "9223372036854775808,20,400", "9223372036854775807,2,200"})
    void listRejectsInvalidPagination(String page, String size, int status) throws Exception {
        var owner = loggedIn("owner");
        assertResponse(get(owner.client(), COLLECTION + "?pageNum=" + page + "&pageSize=" + size), status, 400);
        assertCounts(0, 0);
    }

    @Test
    void listTracksGrantRevocationAndRejectsOtherEnterprises() throws Exception {
        var owner = loggedIn("owner");
        assertThat(tree(get(owner.client(), COLLECTION)).path("data").path("total").asLong()).isEqualTo(2);
        DB.jdbc.update("UPDATE rag_knowledge_grant SET status = 1 WHERE knowledge_space_id = 401");
        try {
            var response = get(owner.client(), COLLECTION + "?pageNum=2&pageSize=1");
            assertResponse(response, 200, 0);
            assertThat(tree(response).path("data").path("total").asLong()).isEqualTo(1);
            assertThat(tree(response).path("data").path("records").isEmpty()).isTrue();
            var peer = loggedIn("peer");
            assertResponse(get(peer.client(), "/api/enterprises/2/knowledge-spaces"), 200, 404);
            var empty = get(peer.client(), COLLECTION);
            assertResponse(empty, 200, 0);
            assertThat(tree(empty).path("data").path("total").asLong()).isZero();
        } finally {
            DB.jdbc.update("UPDATE rag_knowledge_grant SET status = 0 WHERE knowledge_space_id = 401");
        }
    }

    @Test
    void simultaneousIndependentRequestsUseServerIdentityForBothGrants() throws Exception {
        var admin = loggedIn("admin");
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<HttpResponse<String>> create = () -> post(admin.client(), COLLECTION,
                    body("同名独立HTTP请求"), admin.csrf());
            var results = executor.invokeAll(List.of(create, create), 15, TimeUnit.SECONDS);
            long firstId = -1;
            for (var result : results) {
                var response = result.get();
                assertResponse(response, 200, 0);
                long id = tree(response).path("data").path("id").asLong(-1);
                assertThat(id).isPositive().isNotEqualTo(firstId);
                firstId = id;
                assertThat(DB.jdbc.queryForObject(
                        "SELECT member_id FROM rag_knowledge_grant WHERE knowledge_space_id = ?", Long.class, id))
                        .isEqualTo(103L);
            }
            assertCounts(2, 2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void privateMultipartUploadParsePublishAndOriginalUseRealHttpAndNoPayloadLog(CapturedOutput output) throws Exception {
        var admin = loggedIn("admin");
        long space = createdSpace(admin);
        String collection = COLLECTION + "/" + space + "/documents";
        String privateText = "不可进入日志的正文-" + UUID.randomUUID() + "\n审批前请保留原始凭证。";
        String requestId = UUID.randomUUID().toString();
        String metadata = json.writeValueAsString(Map.of("requestId", requestId, "title", "测试制度"));
        var response = multipart(admin.client(), collection, metadata, "制度.md", privateText, admin.csrf());
        assertResponse(response, 200, 0);
        var version = tree(response).path("data");
        String document = version.path("documentId").asText();
        String id = version.path("id").asText();
        assertThat(version.path("parseStatus").asText()).isEqualTo("PENDING");
        assertThat(response.body()).doesNotContain("storageKey", "contentSha256", ORIGINALS, requestId);
        var replay = multipart(admin.client(), collection, metadata, "制度.md", privateText, admin.csrf());
        assertResponse(replay, 200, 0);
        assertThat(tree(replay).path("data").path("id").asText()).isEqualTo(id);
        String originalPath = collection + "/" + document + "/versions/" + id + "/original";
        var original = get(admin.client(), originalPath);
        assertThat(original.statusCode()).isEqualTo(200);
        assertThat(org.springframework.http.MediaType.parseMediaType(original.headers().firstValue("Content-Type").orElse(""))
                .isCompatibleWith(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)).isTrue();
        assertThat(original.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment;");
        assertThat(original.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(original.body()).isEqualTo(privateText);
        assertResponse(post(admin.client(), collection + "/" + document + "/versions/" + id + "/parse",
                "{\"expectedRevision\":1}", admin.csrf()), 200, 0);
        assertResponse(post(admin.client(), collection + "/" + document + "/lifecycle",
                "{\"expectedRevision\":2,\"versionId\":\"" + id + "\",\"action\":\"PUBLISH\"}", admin.csrf()), 200, 0);
        var chunks = get(admin.client(), collection + "/" + document + "/versions/" + id + "/chunks");
        assertResponse(chunks, 200, 0);
        assertThat(tree(chunks).path("data").path("records").get(0).path("content").asText()).isEqualTo(privateText);
        assertThat(output.getAll()).doesNotContain(privateText);
        assertResponse(get(client(), originalPath), 401, 401);
        var owner = loggedIn("owner");
        assertResponse(get(owner.client(), originalPath), 200, 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-file", "missing-metadata", "no-csrf", "oversize-file", "wrong-extension", "invalid-json"})
    void malformedOrUnauthorizedUploadFailsWithBoundedSafeResponse(String scenario) throws Exception {
        var admin = loggedIn("admin");
        long space = createdSpace(admin);
        String collection = COLLECTION + "/" + space + "/documents";
        String metadata = scenario.equals("missing-metadata") ? null : scenario.equals("invalid-json") ? "{" :
                json.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString(), "title", "上传测试"));
        String filename = scenario.equals("missing-file") ? null : scenario.equals("wrong-extension") ? "制度.exe" : "制度.txt";
        String text = scenario.equals("oversize-file") ? "a".repeat(1048577) : "内部原件";
        var response = multipart(admin.client(), collection, metadata, filename, text,
                scenario.equals("no-csrf") ? null : admin.csrf());
        int status = scenario.equals("no-csrf") ? 403 : scenario.startsWith("missing") || scenario.equals("invalid-json") ? 400 : scenario.equals("oversize-file") ? 413 : 200;
        int code = scenario.equals("wrong-extension") ? 400 : status;
        assertResponse(response, status, code);
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_document WHERE knowledge_space_id=?", Integer.class, space)).isZero();
        assertThat(response.body()).doesNotContain(ORIGINALS, "INSERT", "password_hash", "内部原件");
    }

    @Test
    void spaceRenameAndGrantHttpRequireMaintenanceAndCurrentVersion() throws Exception {
        var admin = loggedIn("admin");
        long space = createdSpace(admin);
        String path = COLLECTION + "/" + space;
        var rename = post(admin.client(), path + "/rename", "{\"expectedVersion\":1,\"name\":\"新空间名称\"}", admin.csrf());
        assertResponse(rename, 200, 0);
        assertResponse(post(admin.client(), path + "/rename", "{\"expectedVersion\":1,\"name\":\"旧页面覆盖\"}", admin.csrf()), 200, 409);
        assertResponse(post(admin.client(), path + "/grants", "{\"expectedVersion\":2,\"targetType\":\"MEMBER\",\"targetId\":101,\"permission\":\"READ\"}", admin.csrf()), 200, 0);
        var owner = loggedIn("owner");
        assertResponse(get(owner.client(), path), 200, 0);
        assertResponse(get(owner.client(), path + "/grants"), 200, 404);
        assertResponse(post(owner.client(), path + "/rename", "{\"expectedVersion\":3,\"name\":\"只读不能改\"}", owner.csrf()), 200, 404);
        assertResponse(post(admin.client(), path + "/grants", "{\"expectedVersion\":3,\"targetType\":\"MEMBER\",\"targetId\":101,\"revoke\":true}", admin.csrf()), 200, 0);
        assertResponse(get(owner.client(), path), 200, 404);
    }

    private long createdSpace(SignedIn admin) throws Exception {
        var response = post(admin.client(), COLLECTION, body("知识HTTP-" + UUID.randomUUID()), admin.csrf());
        assertResponse(response, 200, 0);
        return tree(response).path("data").path("id").asLong();
    }

    @Test
    void durableIngestHttpRequiresSessionCsrfOwnershipAndHidesPrivateState(CapturedOutput output) throws Exception {
        var admin = loggedIn("admin");
        long space = createdSpace(admin);
        String collection = COLLECTION + "/" + space + "/documents";
        String privateText = "任务不得记录的虚构原文-" + UUID.randomUUID();
        var uploaded = multipart(admin.client(), collection,
                json.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString(), "title", "虚构任务制度")),
                "虚构.md", privateText, admin.csrf());
        assertResponse(uploaded, 200, 0);
        var v = tree(uploaded).path("data");
        String path = collection + "/" + v.path("documentId").asText() + "/versions/" + v.path("id").asText() + "/ingest-jobs";
        String id = UUID.randomUUID().toString();
        String body = json.writeValueAsString(Map.of("requestId", id, "expectedRevision", 1, "action", "PARSE"));
        assertResponse(post(admin.client(), path, body, null), 403, 403);
        assertResponse(get(client(), path), 401, 401);
        var accepted = post(admin.client(), path, body, admin.csrf());
        assertResponse(accepted, 200, 0);
        assertThat(tree(accepted).path("data").path("id").asText()).isEqualTo(id);
        assertResponse(post(admin.client(), path, body, admin.csrf()), 200, 0);
        var result = get(admin.client(), path + "/" + id);
        assertResponse(result, 200, 0);
        assertThat(result.body()).doesNotContain("leaseToken", "storageKey", "authVersion", privateText, ORIGINALS);
        assertThat(result.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var owner = loggedIn("owner");
        assertResponse(get(owner.client(), path + "/" + id), 200, 404);
        assertResponse(post(owner.client(), path + "/" + id + "/cancel", "{}", owner.csrf()), 200, 404);
        assertResponse(get(admin.client(), path), 200, 0);
        assertResponse(post(admin.client(), path + "/" + id + "/cancel", "{}", admin.csrf()), 200, 0);
        assertThat(output.getAll()).doesNotContain(privateText, "lease_token", "INSERT INTO rag_ingest_job");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "docx"})
    void binaryOfficeFileCompletesAuthorizedDurableIngestWithoutAutoPublication(String format, CapturedOutput output) throws Exception {
        var admin = loggedIn("admin");
        long space = createdSpace(admin);
        String collection = COLLECTION + "/" + space + "/documents";
        String marker = "PRIVATE_HTTP_POLICY_" + UUID.randomUUID();
        byte[] bytes = officeFile(format, marker);
        String metadata = json.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString(), "title", "虚构二进制制度"));
        var unknownLength = admin.client().send(HttpRequest.newBuilder(uri(collection)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=unknown-length")
                .header(admin.csrf().path("headerName").asText(), admin.csrf().path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(bytes))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertResponse(unknownLength, 413, 413);
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_document WHERE knowledge_space_id=?", Integer.class, space)).isZero();
        assertResponse(multipartBytes(admin.client(), collection, metadata, "policy." + format, bytes, null), 403, 403);
        var uploaded = multipartBytes(admin.client(), collection, metadata, "policy." + format, bytes, admin.csrf());
        assertResponse(uploaded, 200, 0);
        var version = tree(uploaded).path("data");
        String document = version.path("documentId").asText(), id = version.path("id").asText();
        String base = collection + "/" + document + "/versions/" + id;
        assertThat(version.path("fileSize").asLong()).isEqualTo(bytes.length);
        var replay = multipartBytes(admin.client(), collection, metadata, "policy." + format, bytes, admin.csrf());
        assertResponse(replay, 200, 0);
        assertThat(tree(replay).path("data").path("id").asText()).isEqualTo(id);
        var job = ingestToTerminal(admin, base);
        assertThat(job.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(job.path("attempts").asInt()).isEqualTo(1);
        var ready = tree(get(admin.client(), base)).path("data");
        assertThat(ready.path("parseStatus").asText()).isEqualTo("READY");
        assertThat(ready.path("publicationStatus").asText()).isEqualTo("DRAFT");
        var doc = tree(get(admin.client(), collection + "/" + document)).path("data");
        assertThat(doc.path("publishedVersionId").isNull()).isTrue();
        assertThat(doc.path("revision").asLong()).isEqualTo(2);
        var chunksResponse = get(admin.client(), base + "/chunks");
        assertResponse(chunksResponse, 200, 0);
        var chunks = tree(chunksResponse).path("data");
        assertThat(chunks.path("total").asLong()).isEqualTo(1);
        var chunk = chunks.path("records").get(0);
        String extracted = chunk.path("content").asText();
        assertThat(extracted).contains(marker);
        if (format.equals("pdf")) assertThat(extracted).contains("【PDF第1页】");
        assertThat(chunk.path("startOffset").asInt(-1)).isZero();
        assertThat(chunk.path("endOffset").asInt(-1)).isEqualTo(extracted.length());
        var original = admin.client().send(HttpRequest.newBuilder(uri(base + "/original")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(original.statusCode()).isEqualTo(200);
        assertThat(original.body()).containsExactly(bytes);
        assertThat(original.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment;");
        assertThat(original.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertResponse(get(client(), base + "/original"), 401, 401);
        var other = loggedIn("owner");
        assertResponse(get(other.client(), base + "/chunks"), 200, 404);
        assertResponse(get(other.client(), base + "/original"), 200, 404);
        assertResponse(get(other.client(), base + "/ingest-jobs/" + job.path("id").asText()), 200, 404);
        assertResponse(post(admin.client(), collection + "/" + document + "/lifecycle",
                json.writeValueAsString(Map.of("expectedRevision", 2, "versionId", id, "action", "PUBLISH")), admin.csrf()), 200, 0);
        var withdrawn = post(admin.client(), collection + "/" + document + "/lifecycle",
                json.writeValueAsString(Map.of("expectedRevision", 3, "versionId", id, "action", "WITHDRAW")), admin.csrf());
        assertResponse(withdrawn, 200, 0);
        assertThat(tree(withdrawn).path("data").path("publishedVersionId").isNull()).isTrue();
        assertThat(tree(get(admin.client(), base)).path("data").path("publicationStatus").asText()).isEqualTo("WITHDRAWN");
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"forged.pdf", "forged.docx", "scan.pdf", "broken.docx"})
    void officeParseFailureHasTerminalStateAndCannotPublishPartialChunks(String filename, CapturedOutput output) throws Exception {
        var admin = loggedIn("admin");
        long space = createdSpace(admin);
        String collection = COLLECTION + "/" + space + "/documents";
        String marker = "PRIVATE_INVALID_" + UUID.randomUUID();
        byte[] bytes = filename.equals("scan.pdf") ? officeFile("pdf", "")
                : filename.equals("broken.docx") ? officeFile("docx", "<非法XML&" + marker)
                : marker.getBytes(StandardCharsets.UTF_8);
        var uploaded = multipartBytes(admin.client(), collection,
                json.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString(), "title", "虚构异常原件")), filename, bytes, admin.csrf());
        assertResponse(uploaded, 200, 0);
        var version = tree(uploaded).path("data");
        String document = version.path("documentId").asText(), id = version.path("id").asText();
        String base = collection + "/" + document + "/versions/" + id;
        var job = ingestToTerminal(admin, base);
        assertThat(job.path("status").asText()).isEqualTo("FAILED");
        assertThat(job.path("failureCode").asText()).isEqualTo("INVALID_TEXT");
        assertThat(job.path("message").asText()).isNotBlank();
        assertThat(job.toString()).doesNotContain(marker, ORIGINALS, "storageKey", "leaseToken");
        var failed = get(admin.client(), base);
        assertResponse(failed, 200, 0);
        assertThat(tree(failed).path("data").path("parseStatus").asText()).isEqualTo("FAILED");
        assertThat(tree(failed).path("data").path("chunkCount").asInt(-1)).isZero();
        assertThat(tree(failed).path("data").path("publicationStatus").asText()).isEqualTo("DRAFT");
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_chunk WHERE document_id=? AND version_id=?",
                Integer.class, Long.parseLong(document), Long.parseLong(id))).isZero();
        assertResponse(post(admin.client(), collection + "/" + document + "/lifecycle",
                json.writeValueAsString(Map.of("expectedRevision", 2, "versionId", id, "action", "PUBLISH")), admin.csrf()), 200, 409);
        assertThat(tree(get(admin.client(), collection + "/" + document)).path("data").path("publishedVersionId").isNull()).isTrue();
        var original = admin.client().send(HttpRequest.newBuilder(uri(base + "/original")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(original.statusCode()).isEqualTo(200);
        assertThat(original.body()).containsExactly(bytes);
        assertThat(output.getAll()).doesNotContain(marker);
    }

    private JsonNode ingestToTerminal(SignedIn admin, String versionPath) throws Exception {
        String id = UUID.randomUUID().toString();
        String path = versionPath + "/ingest-jobs";
        String payload = json.writeValueAsString(Map.of("requestId", id, "expectedRevision", 1, "action", "PARSE"));
        assertResponse(post(admin.client(), path, payload, admin.csrf()), 200, 0);
        assertResponse(post(admin.client(), path, payload, admin.csrf()), 200, 0);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonNode state;
        do {
            var response = get(admin.client(), path + "/" + id);
            assertResponse(response, 200, 0);
            state = tree(response).path("data");
            if (!List.of("QUEUED", "RUNNING").contains(state.path("status").asText())) return state;
            Thread.sleep(50);
        } while (System.nanoTime() < until);
        throw new AssertionError("本机入库任务未在10秒观察窗口内进入终态；未自动重提");
    }

    /** 用真实二进制组装multipart，禁止将PDF/ZIP先转成UTF-8字符串。 */
    private HttpResponse<String> multipartBytes(HttpClient client, String path, String metadata, String filename,
            byte[] bytes, JsonNode token) throws Exception {
        String boundary = "binary-" + UUID.randomUUID();
        String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n"
                + metadata + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null) builder.header(token.path("headerName").asText(), token.path("token").asText());
        var body = new java.io.ByteArrayOutputStream();
        body.write(prefix.getBytes(StandardCharsets.UTF_8)); body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 内存生成合成资料，不下载文档、不接OCR、不调用任何模型。 */
    private byte[] officeFile(String format, String text) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        if (format.equals("pdf")) {
            try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
                var page = new org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page);
                if (!text.isEmpty()) try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(40, 700); content.showText(text); content.endText();
                }
                doc.save(bytes);
            }
        } else {
            try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
                zip.write("<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'/>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
                zip.write(("<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>"
                        + text + "</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private HttpResponse<String> multipart(HttpClient client, String path, String metadata, String filename,
            String text, JsonNode token) throws Exception {
        String boundary = "knowledge-" + UUID.randomUUID();
        var body = new StringBuilder();
        if (metadata != null) body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n").append(metadata).append("\r\n");
        if (filename != null) body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\nContent-Type: text/plain\r\n\r\n").append(text).append("\r\n");
        body.append("--").append(boundary).append("--\r\n");
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null) builder.header(token.path("headerName").asText(), token.path("token").asText());
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private SignedIn loggedIn(String username) throws Exception {
        var client = client();
        JsonNode token = csrf(client);
        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(TEST_PASSWORD, StandardCharsets.UTF_8);
        var response = client.send(HttpRequest.newBuilder(uri("/api/auth/login")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header(token.path("headerName").asText(), token.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertResponse(response, 200, 0);
        // 登录后会话和CSRF令牌均按安全链更新，必须重新取得令牌再提交写请求。
        return new SignedIn(client, csrf(client));
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    private JsonNode csrf(HttpClient client) throws Exception {
        var response = get(client, "/api/auth/csrf");
        assertResponse(response, 200, 0);
        JsonNode token = tree(response).path("data");
        assertThat(token.path("token").asText()).isNotBlank();
        return token;
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, JsonNode token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header(token.path("headerName").asText(), token.path("token").asText());
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String body(String name) throws Exception {
        return json.writeValueAsString(Map.of("name", name));
    }

    private JsonNode tree(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private void assertResponse(HttpResponse<String> response, int httpStatus, int code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(httpStatus);
        assertThat(tree(response).path("code").asInt(-1)).isEqualTo(code);
        if (code != 0) {
            assertThat(tree(response).path("message").asText()).isNotBlank();
            assertThat(tree(response).path("data").isNull()).isTrue();
        }
    }

    private void assertCounts(int newSpaces, int newGrants) {
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_space", Integer.class))
                .isEqualTo(spacesBefore + newSpaces);
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_grant", Integer.class))
                .isEqualTo(grantsBefore + newGrants);
    }

    private record SignedIn(HttpClient client, JsonNode csrf) {
    }
}
