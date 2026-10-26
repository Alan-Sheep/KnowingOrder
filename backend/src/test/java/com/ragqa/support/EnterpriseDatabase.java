package com.ragqa.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.ragqa.mapper.EnterpriseAccountMapper;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import com.ragqa.mapper.EnterpriseResourceMapper;
import com.ragqa.mapper.SysUserMapper;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 隔离内存库执行真实Mapper，提供向量迁移前基线；H2适配不替代MySQL部署验收。 */
public final class EnterpriseDatabase implements AutoCloseable {
    public final JdbcDataSource dataSource = new JdbcDataSource();
    public final JdbcTemplate jdbc;
    public final SqlSessionFactory factory;
    public final SqlSession session;
    private final Connection anchor;

    public EnterpriseDatabase() throws Exception {
        dataSource.setURL("jdbc:h2:mem:enterprise_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        anchor = dataSource.getConnection();
        String schema = Files.readString(Path.of("schema.sql"), StandardCharsets.UTF_8);
        schema = beforeVectorMigrations(schema);
        schema = schema.substring(schema.indexOf("CREATE TABLE sys_user"))
                .replaceAll("(?i)COLLATE\\s*=?\\s*utf8mb4_unicode_ci", "");
        ScriptUtils.executeSqlScript(anchor, new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
        jdbc = new JdbcTemplate(dataSource);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        var builder = new MybatisSqlSessionFactoryBean();
        builder.setDataSource(dataSource);
        builder.setConfiguration(configuration);
        factory = builder.getObject();
        configuration.addMapper(SysUserMapper.class);
        configuration.addMapper(EnterpriseDirectoryMapper.class);
        configuration.addMapper(EnterpriseResourceMapper.class);
        configuration.addMapper(EnterpriseAccountMapper.class);
        session = factory.openSession(true);
    }

    /** 旧企业用例从向量迁移前起步；先严格核对首建定义，不能靠忽略重复建表掩盖结构漂移。 */
    private static String beforeVectorMigrations(String schema) throws Exception {
        var tables = Map.of(
                "20261019_04_knowledge_vector_jobs.sql", List.of("rag_vector_gate", "rag_vector_job"),
                "20261020_01_knowledge_vector_manifests.sql", List.of("rag_vector_manifest_entry"),
                "20261020_02_knowledge_vector_publication.sql", List.of("rag_vector_publication"));
        for (var entry : tables.entrySet()) {
            String migration = Files.readString(Path.of("sql/migrations", entry.getKey()), StandardCharsets.UTF_8);
            for (String table : entry.getValue()) {
                String definition = tableDefinition(schema, table);
                if (!definition.equals(tableDefinition(migration, table))) {
                    throw new IllegalStateException("首建与增量定义不一致：" + table);
                }
                // 只剥离已经逐字核对的向量DDL；用例仍执行原增量，重复/部分迁移继续报错。
                schema = schema.replace(definition, "");
            }
        }
        String gate = "INSERT INTO rag_vector_gate(id) VALUES(1);";
        String migration = Files.readString(Path.of("sql/migrations/20261019_04_knowledge_vector_jobs.sql"), StandardCharsets.UTF_8);
        for (String script : List.of(schema, migration)) {
            int index = script.indexOf(gate);
            if (index < 0 || script.indexOf(gate, index + gate.length()) >= 0) {
                throw new IllegalStateException("向量受理锁行须初始化且仅初始化一次");
            }
        }
        return schema.replace(gate, "");
    }

    private static String tableDefinition(String script, String table) {
        var matcher = Pattern.compile("(?s)CREATE TABLE " + Pattern.quote(table) + " \\(.*?\\) ENGINE=InnoDB[^;]*;").matcher(script);
        if (!matcher.find()) throw new IllegalStateException("缺少向量建表定义：" + table);
        String definition = matcher.group();
        if (matcher.find()) throw new IllegalStateException("重复向量建表定义：" + table);
        return definition;
    }

    public void seed() {
        jdbc.update("INSERT INTO sys_enterprise(id,name) VALUES(1,'甲企业'),(2,'乙企业')");
        jdbc.update("INSERT INTO sys_department(id,enterprise_id,name) VALUES(11,1,'研发'),(22,2,'研发')");
        jdbc.update("INSERT INTO sys_user(id,username,password_hash) VALUES(1,'owner','测试占位'),(2,'peer','测试占位'),(3,'admin','测试占位')");
        jdbc.update("""
                INSERT INTO sys_enterprise_member(id,enterprise_id,user_id,department_id,role)
                VALUES(101,1,1,11,'EMPLOYEE'),(102,1,2,NULL,'EMPLOYEE'),(103,1,3,11,'ADMIN'),(201,2,1,22,'EMPLOYEE')
                """);
        jdbc.update("""
                INSERT INTO rag_conversation(id,enterprise_id,member_id,user_id,guest_id,title)
                VALUES(1001,1,101,1,NULL,'本人甲企业会话'),(1002,1,102,2,NULL,'他人会话'),
                    (2001,2,201,1,NULL,'本人乙企业会话'),(3001,NULL,NULL,1,NULL,'旧会话'),
                    (4001,NULL,NULL,NULL,1,'访客会话')
                """);
        jdbc.update("INSERT INTO rag_message(id,conversation_id,role,content) VALUES(6001,1001,'user','甲企业问题'),(6002,1002,'user','他人问题'),(6003,2001,'user','乙企业问题')");
        jdbc.update("INSERT INTO rag_knowledge_space(id,enterprise_id,name) VALUES(401,1,'成员授权'),(402,1,'未授权'),(403,2,'乙企业空间'),(404,1,'部门授权')");
        jdbc.update("""
                INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,department_id,permission)
                VALUES(1,401,101,NULL,'READ'),(2,403,201,NULL,'READ'),(1,404,NULL,11,'READ')
                """);
        jdbc.update("""
                INSERT INTO rag_document(id,enterprise_id,knowledge_space_id,title,source_type,file_path)
                VALUES(501,1,401,'制度','UPLOAD','不可外露的存储键'),(502,1,402,'私密制度','UPLOAD',NULL),
                    (503,2,403,'乙企业制度','UPLOAD',NULL),(504,NULL,NULL,'旧文档','UPLOAD',NULL),
                    (505,1,404,'部门制度','UPLOAD',NULL),(506,1,403,'错误跨企业关联','UPLOAD',NULL)
                """);
        jdbc.update("""
                INSERT INTO rag_task_reference(id,enterprise_id,conversation_id,message_id)
                VALUES(9001,1,1001,6001),(9002,1,1002,6002),(9003,1,1001,6002),(9004,2,2001,6003)
                """);
    }

    @Override
    public void close() throws Exception {
        session.close();
        anchor.close();
    }
}
