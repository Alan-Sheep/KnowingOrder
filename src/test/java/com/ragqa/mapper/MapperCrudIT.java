package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.Constants;
import com.ragqa.domain.entity.RagChunk;
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

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private RagChunkMapper ragChunkMapper;

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
