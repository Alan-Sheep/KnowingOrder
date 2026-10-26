package com.ragqa.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.ragqa.domain.entity.RagConversation;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.RagConversationMapper;
import com.ragqa.service.impl.ConversationServiceImpl;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 使用真实映射生成 SQL，但不取得数据库连接、不访问模型和搜索。 */
class ConversationServiceTest {

    private static Configuration configuration;
    private final RagConversationMapper mapper = mock(RagConversationMapper.class);
    private final ConversationService service = new ConversationServiceImpl(mapper);

    @BeforeAll
    static void initializeRealMapping() throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        DataSource dataSource = mock(DataSource.class);
        factory.setDataSource(dataSource);
        configuration = factory.getObject().getConfiguration();
        configuration.addMapper(RagConversationMapper.class);
        verifyNoInteractions(dataSource);
    }

    @ParameterizedTest
    @ValueSource(longs = {42L, Long.MAX_VALUE})
    @DisplayName("归属查询同时绑定用户与会话编号，且真实SQL排除已删除会话")
    void ownedConversationUsesBoundIdentifiersAndLogicalDeletion(long conversationId) {
        RagConversation conversation = new RagConversation();
        conversation.setId(conversationId);
        conversation.setUserId(77L);
        doAnswer(invocation -> {
            assertOwnershipQuery(invocation.getArgument(0), 77L, conversationId);
            return conversation;
        }).when(mapper).selectOne(any());

        assertThat(service.requireOwned(77L, conversationId)).isSameAs(conversation);
    }

    @Test
    @DisplayName("查询无匹配行时只返回固定404，不探测会话是否属于其他用户")
    void missingOrInaccessibleConversationHasOneSafeFailure() {
        doAnswer(invocation -> {
            assertOwnershipQuery(invocation.getArgument(0), 77L, 42L);
            return null;
        }).when(mapper).selectOne(any());

        assertThatThrownBy(() -> service.requireOwned(77L, 42L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(404);
                    assertThat(exception.getMessage()).isEqualTo("会话不存在或不可访问");
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    @DisplayName("无效当前用户在查询前拒绝")
    void invalidCurrentUserNeverQueries(Long currentUserId) {
        assertInvalidIdentifier(() -> service.requireOwned(currentUserId, 42L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    @DisplayName("无效会话编号在查询前拒绝")
    void invalidConversationNeverQueries(Long conversationId) {
        assertInvalidIdentifier(() -> service.requireOwned(77L, conversationId));
    }

    @Test
    @DisplayName("数据库异常向上交付，不能伪装为查无资源或成功")
    void storageFailureIsNotConvertedToMissingResource() {
        var failure = new DataAccessResourceFailureException("虚构数据库故障");
        when(mapper.selectOne(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.requireOwned(77L, 42L)).isSameAs(failure);
    }

    @Test
    @DisplayName("并发读取使用各自的归属条件，不串用用户或会话")
    void parallelReadsKeepOwnershipConditionsIsolated() throws Exception {
        doAnswer(invocation -> {
            List<Object> identifiers = boundIdentifiers(invocation.getArgument(0));
            RagConversation row = new RagConversation();
            row.setId((Long) identifiers.get(0));
            row.setUserId((Long) identifiers.get(1));
            return row;
        }).when(mapper).selectOne(any());
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<RagConversation>> reads = new ArrayList<>();
            for (long user = 1; user <= 20; user++) {
                long owner = user;
                reads.add(() -> service.requireOwned(owner, owner + 1000));
            }
            var results = executor.invokeAll(reads, 5, java.util.concurrent.TimeUnit.SECONDS);
            for (int index = 0; index < results.size(); index++) {
                RagConversation row = results.get(index).get();
                assertThat(row.getUserId()).isEqualTo(index + 1L);
                assertThat(row.getId()).isEqualTo(index + 1001L);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertInvalidIdentifier(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(400));
        verifyNoInteractions(mapper);
    }

    private static void assertOwnershipQuery(Wrapper<?> wrapper, long owner, long conversationId) {
        String sql = boundSql(wrapper).getSql().replaceAll("\\s+", " ").strip();
        assertThat(sql).contains("FROM rag_conversation")
                .containsPattern("WHERE\\s+deleted\\s*=\\s*0")
                .containsPattern("id\\s*=\\s*\\?\\s+AND\\s+user_id\\s*=\\s*\\?")
                .doesNotContain(" OR ", "${", Long.toString(Long.MAX_VALUE));
        assertThat(boundIdentifiers(wrapper)).containsExactly(conversationId, owner);
    }

    private static BoundSql boundSql(Wrapper<?> wrapper) {
        return configuration.getMappedStatement(RagConversationMapper.class.getName() + ".selectList")
                .getBoundSql(Map.of("ew", wrapper));
    }

    private static List<Object> boundIdentifiers(Wrapper<?> wrapper) {
        var parameters = configuration.newMetaObject(Map.of("ew", wrapper));
        return boundSql(wrapper).getParameterMappings().stream()
                .map(mapping -> parameters.getValue(mapping.getProperty())).toList();
    }
}
