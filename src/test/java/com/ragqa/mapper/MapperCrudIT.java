package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.Constants;
import com.ragqa.domain.entity.RagChunk;
import com.ragqa.domain.entity.RagConversation;
import com.ragqa.domain.entity.RagDocument;
import com.ragqa.domain.entity.RagMessage;
import com.ragqa.domain.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper 层增删改查验证，需要本机已启动 MySQL 并导入 schema.sql。
 *
 * <p>类名以 IT 结尾，而 surefire 默认只匹配 {@code *Test} / {@code Test*} / {@code *Tests} / {@code *TestCase}，
 * 因此常规 {@code mvn test} 不会执行本类（避免无数据库的环境构建失败）。手动执行：</p>
 *
 * <pre>mvn test -Dtest=MapperCrudIT</pre>
 *
 * <p>类上的 {@code @Transactional} 让每个用例结束后回滚，不会在库里留下测试数据。</p>
 */
@SpringBootTest
@Transactional
class MapperCrudIT {

    /** 测试数据统一前缀，便于识别与清理 */
    private static final String USERNAME_PREFIX = "it_verify_";

    /** 故意使用不存在的文档ID，用于验证未建物理外键 */
    private static final long ORPHAN_DOCUMENT_ID = 999999999L;

    /** 冒烟用例里引用的用户 / 会话 ID：不存在对应主记录，同样依赖未建物理外键 */
    private static final long ORPHAN_USER_ID = 999999998L;
    private static final long ORPHAN_CONVERSATION_ID = 999999997L;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private RagChunkMapper ragChunkMapper;

    @Autowired
    private RagDocumentMapper ragDocumentMapper;

    @Autowired
    private RagConversationMapper ragConversationMapper;

    @Autowired
    private RagMessageMapper ragMessageMapper;

    /** 绕过 MyBatis-Plus 直读物理行，用于验证逻辑删除与时间列的数据库默认值 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("插入后按ID查回，自增主键与时间列由数据库维护")
    void insertThenSelectById() {
        SysUser user = newUser("insert");
        assertThat(sysUserMapper.insert(user)).isEqualTo(1);
        assertThat(user.getId()).as("自增主键回填").isNotNull();

        SysUser loaded = sysUserMapper.selectById(user.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUsername()).isEqualTo(user.getUsername());
        assertThat(loaded.getStatus()).isEqualTo(Constants.UserStatus.NORMAL);
        assertThat(loaded.getDeleted()).isEqualTo(Constants.Deleted.NO);
        assertThat(loaded.getCreatedAt()).as("created_at 由 DEFAULT CURRENT_TIMESTAMP 维护").isNotNull();
        assertThat(loaded.getUpdatedAt()).as("updated_at 由 ON UPDATE CURRENT_TIMESTAMP 维护").isNotNull();
    }

    @Test
    @DisplayName("updateById 只更新传入的非空字段")
    void updateByIdOnlyTouchesNonNullFields() {
        SysUser user = newUser("update");
        sysUserMapper.insert(user);

        SysUser patch = new SysUser();
        patch.setId(user.getId());
        patch.setNickname("新昵称");
        assertThat(sysUserMapper.updateById(patch)).isEqualTo(1);

        SysUser loaded = sysUserMapper.selectById(user.getId());
        assertThat(loaded.getNickname()).isEqualTo("新昵称");
        assertThat(loaded.getUsername()).as("未传入的字段保持原值").isEqualTo(user.getUsername());
    }

    @Test
    @DisplayName("分页插件生效：total 为条件命中总数，records 受 size 限制")
    void paginationInterceptorWorks() {
        String tag = "page_" + shortId();
        for (int i = 0; i < 3; i++) {
            sysUserMapper.insert(newUser(tag + "_" + i));
        }

        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .likeRight(SysUser::getUsername, USERNAME_PREFIX + tag);

        Page<SysUser> page = sysUserMapper.selectPage(new Page<>(1, 2), wrapper);
        assertThat(page.getTotal()).as("命中 3 条").isEqualTo(3);
        assertThat(page.getRecords()).as("单页最多 2 条").hasSize(2);
    }

    @Test
    @DisplayName("逻辑删除：deleteById 只置 deleted=1，物理行仍在")
    void logicDeleteKeepsRow() {
        SysUser user = newUser("logic_delete");
        sysUserMapper.insert(user);
        Long id = user.getId();

        assertThat(sysUserMapper.deleteById(id)).isEqualTo(1);
        assertThat(sysUserMapper.selectById(id)).as("MyBatis-Plus 自动过滤已删除行").isNull();

        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM sys_user WHERE id = ?", Integer.class, id);
        assertThat(deleted).as("物理行仍在，仅标记删除").isEqualTo(Constants.Deleted.YES);
    }

    @Test
    @DisplayName("rag_chunk 无 deleted 列：删除即物理删除，且未建物理外键")
    void chunkHasNoLogicDeleteAndNoForeignKey() {
        RagChunk chunk = new RagChunk();
        chunk.setDocumentId(ORPHAN_DOCUMENT_ID);
        chunk.setChunkIndex(0);
        chunk.setContent("中文内容往返校验：分块内容。");
        chunk.setTokenCount(12);

        assertThat(ragChunkMapper.insert(chunk))
                .as("父文档不存在也能写入，说明未建物理外键")
                .isEqualTo(1);

        RagChunk loaded = ragChunkMapper.selectById(chunk.getId());
        assertThat(loaded.getContent()).as("中文经 utf8mb4 往返一致").isEqualTo("中文内容往返校验：分块内容。");

        assertThat(ragChunkMapper.deleteById(chunk.getId())).isEqualTo(1);
        Long remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_chunk WHERE id = ?", Long.class, chunk.getId());
        assertThat(remaining).as("无 deleted 列，删除即物理删除").isZero();
    }

    /*
     * 以下 3 个是最轻的冒烟：只验证「插入 + 按主键查回」，用来兜住 @TableName 表名写错、
     * 字段映射缺失这类问题。表名写错会在 insert 时直接抛 SQL 异常；
     * 驼峰字段映射漏掉则会在查回后断言失败。
     */

    @Test
    @DisplayName("冒烟 rag_document：插入后按ID查回，字段映射与默认值正确")
    void documentInsertThenSelect() {
        RagDocument doc = new RagDocument();
        doc.setTitle("冒烟文档");
        doc.setSourceType(Constants.SourceType.UPLOAD);
        doc.setFilePath("/data/it_verify.pdf");
        doc.setFileSize(1024L);
        doc.setCreatedBy(ORPHAN_USER_ID);

        assertThat(ragDocumentMapper.insert(doc)).isEqualTo(1);
        assertThat(doc.getId()).isNotNull();

        RagDocument loaded = ragDocumentMapper.selectById(doc.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTitle()).isEqualTo("冒烟文档");
        assertThat(loaded.getSourceType()).as("source_type 映射").isEqualTo(Constants.SourceType.UPLOAD);
        assertThat(loaded.getFilePath()).as("file_path 映射").isEqualTo("/data/it_verify.pdf");
        assertThat(loaded.getFileSize()).as("file_size 映射").isEqualTo(1024L);
        assertThat(loaded.getCreatedBy()).as("created_by 映射").isEqualTo(ORPHAN_USER_ID);
        assertThat(loaded.getStatus()).as("status 取数据库默认值").isEqualTo(Constants.DocumentStatus.PENDING);
        assertThat(loaded.getChunkCount()).as("chunk_count 取数据库默认值").isZero();
        assertThat(loaded.getDeleted()).isEqualTo(Constants.Deleted.NO);
    }

    @Test
    @DisplayName("冒烟 rag_conversation：插入后按ID查回，字段映射正确")
    void conversationInsertThenSelect() {
        RagConversation conversation = new RagConversation();
        conversation.setUserId(ORPHAN_USER_ID);
        conversation.setTitle("冒烟会话");

        assertThat(ragConversationMapper.insert(conversation)).isEqualTo(1);
        assertThat(conversation.getId()).isNotNull();

        RagConversation loaded = ragConversationMapper.selectById(conversation.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).as("user_id 映射").isEqualTo(ORPHAN_USER_ID);
        assertThat(loaded.getTitle()).isEqualTo("冒烟会话");
        assertThat(loaded.getDeleted()).isEqualTo(Constants.Deleted.NO);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("冒烟 rag_message：插入后按ID查回，字段映射正确")
    void messageInsertThenSelect() {
        RagMessage message = new RagMessage();
        message.setConversationId(ORPHAN_CONVERSATION_ID);
        message.setRole(Constants.MessageRole.USER);
        message.setContent("中文提问：什么是 RAG？");
        message.setTokenCount(8);

        assertThat(ragMessageMapper.insert(message)).isEqualTo(1);
        assertThat(message.getId()).isNotNull();

        RagMessage loaded = ragMessageMapper.selectById(message.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getConversationId()).as("conversation_id 映射").isEqualTo(ORPHAN_CONVERSATION_ID);
        assertThat(loaded.getRole()).isEqualTo(Constants.MessageRole.USER);
        assertThat(loaded.getContent()).isEqualTo("中文提问：什么是 RAG？");
        assertThat(loaded.getTokenCount()).as("token_count 映射").isEqualTo(8);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    private SysUser newUser(String tag) {
        SysUser user = new SysUser();
        user.setUsername(USERNAME_PREFIX + tag + "_" + shortId());
        user.setPasswordHash("$2a$10$it-verify-placeholder-hash");
        user.setNickname("验证用户");
        user.setEmail("it_verify@example.com");
        user.setStatus(Constants.UserStatus.NORMAL);
        return user;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
