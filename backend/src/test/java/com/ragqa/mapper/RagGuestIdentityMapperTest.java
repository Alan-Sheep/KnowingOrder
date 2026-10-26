package com.ragqa.mapper;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.domain.entity.RagGuestIdentity;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.ByteArrayTypeHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 通过真实 MyBatis 映射生成 SQL，不启动应用、不连接数据库、不使用真实凭据。 */
class RagGuestIdentityMapperTest {

    private static final DataSource DATA_SOURCE = mock(DataSource.class);
    private static Configuration configuration;

    @BeforeAll
    static void initializeMapping() throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(DATA_SOURCE);
        configuration = factory.getObject().getConfiguration();
        configuration.addMapper(RagGuestIdentityMapper.class);
    }

    @AfterAll
    static void neverConnectsToDatabase() {
        verifyNoInteractions(DATA_SOURCE);
    }

    @Test
    @DisplayName("精确映射八字段及自增主键，不继承审计或软删除列")
    void mapsOnlyDeclaredColumns() {
        var table = TableInfoHelper.getTableInfo(RagGuestIdentity.class);
        assertThat(table.getTableName()).isEqualTo("rag_guest_identity");
        assertThat(table.getKeyColumn()).isEqualTo("id");
        assertThat(table.getIdType()).isEqualTo(IdType.AUTO);
        assertThat(table.isWithLogicDelete()).isFalse();
        assertThat(table.getFieldList()).extracting(field -> field.getColumn())
                .containsExactlyInAnyOrder("credential_hash", "issued_at_epoch_ms",
                        "accept_until_epoch_ms", "drain_until_epoch_ms", "expires_at_epoch_ms",
                        "status", "result_read_window_ms");
    }

    @Test
    @DisplayName("插入使用占位参数，二进制摘要通过字节数组处理器绑定")
    void insertBindsAllColumnsAndBinaryHash() {
        RagGuestIdentity identity = populatedIdentity();
        BoundSql bound = sql("insert", identity);
        assertThat(normalize(bound)).contains("INSERT INTO rag_guest_identity")
                .contains("credential_hash", "issued_at_epoch_ms", "accept_until_epoch_ms",
                        "drain_until_epoch_ms", "expires_at_epoch_ms", "status",
                        "result_read_window_ms", "?")
                .doesNotContain("created_at", "updated_at", "deleted", "${");
        assertThat(values(bound, identity)).containsExactly(identity.getCredentialHash(),
                1000L, 2000L, 3000L, 603000L, 0, 600000L);
        assertThat(bound.getParameterMappings().get(0).getTypeHandler())
                .isInstanceOf(ByteArrayTypeHandler.class);
        var statement = configuration.getMappedStatement(statementId("insert"));
        assertThat(statement.getKeyGenerator()).isInstanceOf(Jdbc3KeyGenerator.class);
        assertThat(statement.getKeyProperties()).containsExactly("id");
    }

    @Test
    @DisplayName("未指定状态和阅读窗口时不伪造快照值，插入保留数据库默认语义")
    void leavesDefaultsToDatabaseWhenUnspecified() {
        RagGuestIdentity identity = populatedIdentity();
        identity.setStatus(null);
        identity.setResultReadWindowMs(null);
        assertThat(new RagGuestIdentity().getStatus()).isNull();
        assertThat(new RagGuestIdentity().getResultReadWindowMs()).isNull();
        assertThat(normalize(sql("insert", identity)))
                .doesNotContain("status", "result_read_window_ms");
    }

    @Test
    @DisplayName("默认按主键查询不取出摘要，也不生成表中不存在的公共字段")
    void selectByIdOmitsSensitiveAndNonexistentColumns() {
        BoundSql bound = sql("selectById", Map.of("id", 42L));
        assertThat(normalize(bound)).contains("FROM rag_guest_identity", "WHERE id=?")
                .doesNotContain("credential_hash", "created_at", "updated_at", "deleted");
        assertThat(values(bound, Map.of("id", 42L))).containsExactly(42L);
    }

    @Test
    @DisplayName("凭据摘要可以作为参数化过滤条件，但不会进入默认查询结果列")
    void hashFilterIsBoundInsteadOfInterpolated() {
        byte[] hash = syntheticHash();
        var wrapper = new LambdaQueryWrapper<RagGuestIdentity>()
                .eq(RagGuestIdentity::getCredentialHash, hash);
        var parameters = Map.of("ew", wrapper);
        BoundSql bound = sql("selectList", parameters);
        String query = normalize(bound);
        assertThat(query.substring(0, query.indexOf(" FROM "))).doesNotContain("credential_hash");
        assertThat(query).containsPattern("credential_hash\\s*=\\s*\\?")
                .doesNotContain("${");
        assertThat(values(bound, parameters)).containsExactly((Object) hash);
    }

    @Test
    @DisplayName("按主键更新状态只修改显式赋值字段，不覆盖摘要或期限")
    void updateOnlyExplicitState() {
        RagGuestIdentity identity = new RagGuestIdentity();
        identity.setId(42L);
        identity.setStatus(2);
        var parameters = Map.of("et", identity);
        BoundSql bound = sql("updateById", parameters);
        assertThat(normalize(bound)).isEqualTo("UPDATE rag_guest_identity SET status=? WHERE id=?");
        assertThat(values(bound, parameters)).containsExactly(2, 42L);
    }

    @Test
    @DisplayName("JSON 输出不暴露凭据摘要，输入也不能注入摘要字段")
    void jsonNeverExposesOrAcceptsCredentialHash() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(populatedIdentity()));
        assertThat(json.has("credentialHash")).isFalse();
        assertThat(json.get("acceptUntilEpochMs").longValue()).isEqualTo(2000L);
        var decoded = mapper.readValue("{\"id\":42,\"credentialHash\":\"AQID\"}",
                RagGuestIdentity.class);
        assertThat(decoded.getId()).isEqualTo(42L);
        assertThat(decoded.getCredentialHash()).isNull();
    }

    @Test
    @DisplayName("日志字符串仅展示内部编号，不输出摘要或期限")
    void toStringContainsOnlyInternalId() {
        assertThat(populatedIdentity().toString()).isEqualTo("RagGuestIdentity(id=null)");
    }

    @Test
    @DisplayName("摘要读写均复制数组，外部修改不能改变实体中保存的数据")
    void hashArrayHasDefensiveCopies() {
        byte[] original = syntheticHash();
        byte[] expected = original.clone();
        RagGuestIdentity identity = new RagGuestIdentity();
        identity.setCredentialHash(original);
        original[0] = 99;
        byte[] returned = identity.getCredentialHash();
        assertThat(returned).isEqualTo(expected);
        returned[1] = 99;
        assertThat(identity.getCredentialHash()).isEqualTo(expected);
    }

    @Test
    @DisplayName("空摘要保持为空，清空时不残留原数组")
    void nullHashIsPreserved() {
        RagGuestIdentity identity = new RagGuestIdentity();
        assertThat(identity.getCredentialHash()).isNull();
        identity.setCredentialHash(syntheticHash());
        identity.setCredentialHash(null);
        assertThat(identity.getCredentialHash()).isNull();
    }

    private static RagGuestIdentity populatedIdentity() {
        RagGuestIdentity identity = new RagGuestIdentity();
        identity.setCredentialHash(syntheticHash());
        identity.setIssuedAtEpochMs(1000L);
        identity.setAcceptUntilEpochMs(2000L);
        identity.setDrainUntilEpochMs(3000L);
        identity.setExpiresAtEpochMs(603000L);
        identity.setStatus(0);
        identity.setResultReadWindowMs(600000L);
        return identity;
    }

    /** 仅为测试合成固定的 32 字节数据，不对应真实凭据。 */
    private static byte[] syntheticHash() {
        byte[] hash = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            hash[index] = (byte) index;
        }
        return hash;
    }

    private static String statementId(String method) {
        return RagGuestIdentityMapper.class.getName() + "." + method;
    }

    private static BoundSql sql(String method, Object parameters) {
        return configuration.getMappedStatement(statementId(method)).getBoundSql(parameters);
    }

    private static String normalize(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").strip();
    }

    private static List<Object> values(BoundSql bound, Object parameters) {
        var metaObject = configuration.newMetaObject(parameters);
        return bound.getParameterMappings().stream()
                .map(mapping -> bound.hasAdditionalParameter(mapping.getProperty())
                        ? bound.getAdditionalParameter(mapping.getProperty())
                        : metaObject.getValue(mapping.getProperty()))
                .toList();
    }
}
