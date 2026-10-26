-- 演示数据种子：100 名企业员工 + 各业务表示例。仅 INSERT/受限 UPDATE，可重复执行。
-- 口径：只写 rag_qa 现有启用企业；不清理、不改表、不重置自增值、不触碰访客凭据与私有原件。
-- 口令统一为 Demo@2026（BCrypt），仅用于本机演示，禁止用于任何真实环境。
-- 凭证表 rag_affair_receipt 需要真实原件文件，本脚本不伪造下载记录，故不填充。
USE rag_qa;
SET SESSION innodb_lock_wait_timeout = 5;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
START TRANSACTION;

-- 前置条件守卫：先占主键；条件不满足时再插同一主键，触发1062并中止脚本、回滚事务。
CREATE TEMPORARY TABLE seed_guard (
    item VARCHAR(80) PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @ent = (SELECT id FROM sys_enterprise WHERE status = 0 ORDER BY id LIMIT 1);
INSERT INTO seed_guard VALUES ('enterprise');
INSERT INTO seed_guard SELECT 'enterprise' FROM DUAL WHERE @ent IS NULL;
SET @pwd = '$2a$10$M9.p91kEVMtRbMc7Qny8zeq7/Yb/WwFXWH.z9cAWXTrlNs7MJazIa';
SET @now_ms = CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED);
SET @period = DATE_FORMAT(NOW(), '%Y-%m');

-- 1. 部门：5 个平级部门，按企业内名称判重。
INSERT INTO sys_department (enterprise_id, name, status)
SELECT @ent, '产品研发部', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_department WHERE enterprise_id = @ent AND name = '产品研发部');
INSERT INTO sys_department (enterprise_id, name, status)
SELECT @ent, '市场运营部', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_department WHERE enterprise_id = @ent AND name = '市场运营部');
INSERT INTO sys_department (enterprise_id, name, status)
SELECT @ent, '客户成功部', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_department WHERE enterprise_id = @ent AND name = '客户成功部');
INSERT INTO sys_department (enterprise_id, name, status)
SELECT @ent, '财务部', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_department WHERE enterprise_id = @ent AND name = '财务部');
INSERT INTO sys_department (enterprise_id, name, status)
SELECT @ent, '人力资源部', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_department WHERE enterprise_id = @ent AND name = '人力资源部');

-- 2. 100 名演示员工：姓名虚构，用户名用拼音，邮箱在保留域 zhixu.demo。
CREATE TEMPORARY TABLE seed_people (
    seq INT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    dept VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO seed_people VALUES
    (1,'chenzihan','陈子涵','产品研发部'),(2,'chenyutong','陈雨桐','市场运营部'),
    (3,'chenhaoran','陈浩然','客户成功部'),(4,'chensiyuan','陈思远','财务部'),
    (5,'chenjianing','陈嘉宁','人力资源部'),(6,'linzihan','林子涵','产品研发部'),
    (7,'linyutong','林雨桐','市场运营部'),(8,'linhaoran','林浩然','客户成功部'),
    (9,'linsiyuan','林思远','财务部'),(10,'linjianing','林嘉宁','人力资源部'),
    (11,'wangzihan','王子涵','产品研发部'),(12,'wangyutong','王雨桐','市场运营部'),
    (13,'wanghaoran','王浩然','客户成功部'),(14,'wangsiyuan','王思远','财务部'),
    (15,'wangjianing','王嘉宁','人力资源部'),(16,'lizihan','李子涵','产品研发部'),
    (17,'liyutong','李雨桐','市场运营部'),(18,'lihaoran','李浩然','客户成功部'),
    (19,'lisiyuan','李思远','财务部'),(20,'lijianing','李嘉宁','人力资源部'),
    (21,'zhangzihan','张子涵','产品研发部'),(22,'zhangyutong','张雨桐','市场运营部'),
    (23,'zhanghaoran','张浩然','客户成功部'),(24,'zhangsiyuan','张思远','财务部'),
    (25,'zhangjianing','张嘉宁','人力资源部'),(26,'liuzihan','刘子涵','产品研发部'),
    (27,'liuyutong','刘雨桐','市场运营部'),(28,'liuhaoran','刘浩然','客户成功部'),
    (29,'liusiyuan','刘思远','财务部'),(30,'liujianing','刘嘉宁','人力资源部'),
    (31,'zhaozihan','赵子涵','产品研发部'),(32,'zhaoyutong','赵雨桐','市场运营部'),
    (33,'zhaohaoran','赵浩然','客户成功部'),(34,'zhaosiyuan','赵思远','财务部'),
    (35,'zhaojianing','赵嘉宁','人力资源部'),(36,'zhouzihan','周子涵','产品研发部'),
    (37,'zhouyutong','周雨桐','市场运营部'),(38,'zhouhaoran','周浩然','客户成功部'),
    (39,'zhousiyuan','周思远','财务部'),(40,'zhoujianing','周嘉宁','人力资源部'),
    (41,'wuzihan','吴子涵','产品研发部'),(42,'wuyutong','吴雨桐','市场运营部'),
    (43,'wuhaoran','吴浩然','客户成功部'),(44,'wusiyuan','吴思远','财务部'),
    (45,'wujianing','吴嘉宁','人力资源部'),(46,'xuzihan','徐子涵','产品研发部'),
    (47,'xuyutong','徐雨桐','市场运营部'),(48,'xuhaoran','徐浩然','客户成功部'),
    (49,'xusiyuan','徐思远','财务部'),(50,'xujianing','徐嘉宁','人力资源部'),
    (51,'sunzihan','孙子涵','产品研发部'),(52,'sunyutong','孙雨桐','市场运营部'),
    (53,'sunhaoran','孙浩然','客户成功部'),(54,'sunsiyuan','孙思远','财务部'),
    (55,'sunjianing','孙嘉宁','人力资源部'),(56,'mazihan','马子涵','产品研发部'),
    (57,'mayutong','马雨桐','市场运营部'),(58,'mahaoran','马浩然','客户成功部'),
    (59,'masiyuan','马思远','财务部'),(60,'majianing','马嘉宁','人力资源部'),
    (61,'zhuzihan','朱子涵','产品研发部'),(62,'zhuyutong','朱雨桐','市场运营部'),
    (63,'zhuhaoran','朱浩然','客户成功部'),(64,'zhusiyuan','朱思远','财务部'),
    (65,'zhujianing','朱嘉宁','人力资源部'),(66,'huzihan','胡子涵','产品研发部'),
    (67,'huyutong','胡雨桐','市场运营部'),(68,'huhaoran','胡浩然','客户成功部'),
    (69,'husiyuan','胡思远','财务部'),(70,'hujianing','胡嘉宁','人力资源部'),
    (71,'guozihan','郭子涵','产品研发部'),(72,'guoyutong','郭雨桐','市场运营部'),
    (73,'guohaoran','郭浩然','客户成功部'),(74,'guosiyuan','郭思远','财务部'),
    (75,'guojianing','郭嘉宁','人力资源部'),(76,'hezihan','何子涵','产品研发部'),
    (77,'heyutong','何雨桐','市场运营部'),(78,'hehaoran','何浩然','客户成功部'),
    (79,'hesiyuan','何思远','财务部'),(80,'hejianing','何嘉宁','人力资源部'),
    (81,'gaozihan','高子涵','产品研发部'),(82,'gaoyutong','高雨桐','市场运营部'),
    (83,'gaohaoran','高浩然','客户成功部'),(84,'gaosiyuan','高思远','财务部'),
    (85,'gaojianing','高嘉宁','人力资源部'),(86,'huangzihan','黄子涵','产品研发部'),
    (87,'huangyutong','黄雨桐','市场运营部'),(88,'huanghaoran','黄浩然','客户成功部'),
    (89,'huangsiyuan','黄思远','财务部'),(90,'huangjianing','黄嘉宁','人力资源部'),
    (91,'luozihan','罗子涵','产品研发部'),(92,'luoyutong','罗雨桐','市场运营部'),
    (93,'luohaoran','罗浩然','客户成功部'),(94,'luosiyuan','罗思远','财务部'),
    (95,'luojianing','罗嘉宁','人力资源部'),(96,'zhengzihan','郑子涵','产品研发部'),
    (97,'zhengyutong','郑雨桐','市场运营部'),(98,'zhenghaoran','郑浩然','客户成功部'),
    (99,'zhengsy','郑思远','财务部'),(100,'zhengjianing','郑嘉宁','人力资源部');

INSERT INTO sys_user (username, password_hash, nickname, email, status)
SELECT p.username, @pwd, p.nickname, CONCAT(p.username, '@zhixu.demo'), 0
FROM seed_people p
WHERE NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.username = p.username);

INSERT INTO sys_enterprise_member (enterprise_id, user_id, department_id, role, status)
SELECT @ent, u.id, d.id, 'EMPLOYEE', 0
FROM seed_people p
JOIN sys_user u ON u.username = p.username
JOIN sys_department d ON d.enterprise_id = @ent AND d.name = p.dept
WHERE NOT EXISTS (SELECT 1 FROM sys_enterprise_member m
                  WHERE m.enterprise_id = @ent AND m.user_id = u.id);

-- 3. 演示管理员与财务账号：仅用于演示审批链路。
INSERT INTO sys_user (username, password_hash, nickname, email, status)
SELECT 'zhixuadmin', @pwd, '演示管理员', 'zhixuadmin@zhixu.demo', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'zhixuadmin');
INSERT INTO sys_enterprise_member (enterprise_id, user_id, role, status)
SELECT @ent, u.id, 'ADMIN', 0 FROM sys_user u
WHERE u.username = 'zhixuadmin'
  AND NOT EXISTS (SELECT 1 FROM sys_enterprise_member m WHERE m.enterprise_id = @ent AND m.user_id = u.id);
INSERT INTO sys_user (username, password_hash, nickname, email, status)
SELECT 'zhixufinance', @pwd, '演示财务', 'zhixufinance@zhixu.demo', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'zhixufinance');
INSERT INTO sys_enterprise_member (enterprise_id, user_id, role, status)
SELECT @ent, u.id, 'FINANCE', 0 FROM sys_user u
WHERE u.username = 'zhixufinance'
  AND NOT EXISTS (SELECT 1 FROM sys_enterprise_member m WHERE m.enterprise_id = @ent AND m.user_id = u.id);

SET @admin_user = (SELECT id FROM sys_user WHERE username = 'zhixuadmin');
SET @admin_member = (SELECT id FROM sys_enterprise_member WHERE enterprise_id = @ent AND user_id = @admin_user);
SET @finance_user = (SELECT id FROM sys_user WHERE username = 'zhixufinance');
SET @finance_member = (SELECT id FROM sys_enterprise_member WHERE enterprise_id = @ent AND user_id = @finance_user);
SET @m1 = (SELECT m.id FROM sys_enterprise_member m JOIN sys_user u ON u.id = m.user_id
           WHERE m.enterprise_id = @ent AND u.username = 'chenzihan');
SET @m2 = (SELECT m.id FROM sys_enterprise_member m JOIN sys_user u ON u.id = m.user_id
           WHERE m.enterprise_id = @ent AND u.username = 'linyutong');
SET @m3 = (SELECT m.id FROM sys_enterprise_member m JOIN sys_user u ON u.id = m.user_id
           WHERE m.enterprise_id = @ent AND u.username = 'wanghaoran');
SET @m4 = (SELECT m.id FROM sys_enterprise_member m JOIN sys_user u ON u.id = m.user_id
           WHERE m.enterprise_id = @ent AND u.username = 'lisiyuan');
SET @u1 = (SELECT id FROM sys_user WHERE username = 'chenzihan');
SET @u2 = (SELECT id FROM sys_user WHERE username = 'linyutong');
SET @u3 = (SELECT id FROM sys_user WHERE username = 'wanghaoran');
INSERT INTO seed_guard VALUES ('demo-members');
INSERT INTO seed_guard SELECT 'demo-members' FROM DUAL
WHERE NOT (@m1 IS NOT NULL AND @m2 IS NOT NULL AND @m3 IS NOT NULL AND @m4 IS NOT NULL
           AND @admin_member IS NOT NULL AND @finance_member IS NOT NULL);

-- 4. 知识空间与授权：制度空间对全体员工开放，产品空间只给研发部与部分员工。
INSERT INTO rag_knowledge_space (enterprise_id, name, status)
SELECT @ent, '制度与流程', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_space WHERE enterprise_id = @ent AND name = '制度与流程');
INSERT INTO rag_knowledge_space (enterprise_id, name, status)
SELECT @ent, '产品与方案', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_space WHERE enterprise_id = @ent AND name = '产品与方案');
INSERT INTO rag_knowledge_space (enterprise_id, name, status)
SELECT @ent, '新员工指南', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_space WHERE enterprise_id = @ent AND name = '新员工指南');
SET @space1 = (SELECT id FROM rag_knowledge_space WHERE enterprise_id = @ent AND name = '制度与流程');
SET @space2 = (SELECT id FROM rag_knowledge_space WHERE enterprise_id = @ent AND name = '产品与方案');
SET @space3 = (SELECT id FROM rag_knowledge_space WHERE enterprise_id = @ent AND name = '新员工指南');

INSERT INTO rag_knowledge_grant (enterprise_id, knowledge_space_id, department_id, permission, status)
SELECT @ent, @space1, d.id, 'READ', 0
FROM sys_department d
WHERE d.enterprise_id = @ent
  AND NOT EXISTS (SELECT 1 FROM rag_knowledge_grant g
                  WHERE g.enterprise_id = @ent AND g.knowledge_space_id = @space1 AND g.department_id = d.id);

INSERT INTO rag_knowledge_grant (enterprise_id, knowledge_space_id, member_id, permission, status)
SELECT @ent, @space2, m.id, 'READ', 0
FROM sys_enterprise_member m
JOIN sys_user u ON u.id = m.user_id
JOIN seed_people p ON p.username = u.username
WHERE m.enterprise_id = @ent AND p.seq <= 10
  AND NOT EXISTS (SELECT 1 FROM rag_knowledge_grant g
                  WHERE g.enterprise_id = @ent AND g.knowledge_space_id = @space2 AND g.member_id = m.id);

-- 5. 文档、版本与片段：原件键为演示占位，下载会因无文件而失败，仅用于列表与检索演示。
INSERT INTO rag_document (title, source_type, status, chunk_count, created_by, enterprise_id,
                          knowledge_space_id, owner_member_id)
SELECT t.title, 'UPLOAD', 2, 2, @admin_user, @ent, t.space_id, @admin_member
FROM (
    SELECT '员工差旅报销管理办法' AS title, @space1 AS space_id
    UNION ALL SELECT '请假与调休申请流程', @space1
    UNION ALL SELECT '客户方案评审清单', @space2
    UNION ALL SELECT '新员工入职三十天指引', @space3
) t
WHERE NOT EXISTS (SELECT 1 FROM rag_document d
                  WHERE d.enterprise_id = @ent AND d.knowledge_space_id = t.space_id
                    AND d.title = t.title AND d.deleted = 0);

INSERT INTO rag_document_version (enterprise_id, knowledge_space_id, document_id, version_no,
    upload_request_id, original_name, storage_key, content_sha256, request_sha256, file_size,
    parse_status, publication_status, chunk_count, created_by_member)
SELECT @ent, d.knowledge_space_id, d.id, 1,
       CONCAT('d0000000-0000-4000-8000-0000000000', LPAD(d.id MOD 100, 2, '0')),
       CONCAT(d.title, '.md'),
       CONCAT('e0000000-0000-4000-8000-0000000000', LPAD(d.id MOD 100, 2, '0')),
       REPEAT('a1', 32), REPEAT('b2', 32), 1024,
       'READY', 'PUBLISHED', 2, @admin_member
FROM rag_document d
WHERE d.enterprise_id = @ent AND d.deleted = 0
  AND d.title IN ('员工差旅报销管理办法', '请假与调休申请流程', '客户方案评审清单', '新员工入职三十天指引')
  AND NOT EXISTS (SELECT 1 FROM rag_document_version v WHERE v.document_id = d.id AND v.version_no = 1);

INSERT INTO rag_chunk (document_id, version_id, chunk_index, content, token_count, start_offset, end_offset, start_line, end_line)
SELECT v.document_id, v.id, c.chunk_index, c.content, c.tokens, c.start_offset, c.start_offset + CHAR_LENGTH(c.content), 1, 3
FROM rag_document_version v
JOIN rag_document d ON d.id = v.document_id
JOIN (
    SELECT '员工差旅报销管理办法' AS title, 0 AS chunk_index,
           '员工出差前须在系统提交差旅申请，注明目的地、起止日期与预计费用；未经批准的行程不进入报销流程。' AS content,
           62 AS tokens, 0 AS start_offset
    UNION ALL SELECT '员工差旅报销管理办法', 1,
           '报销须附原始票据与审批记录；市内交通按实际发生金额报销，住宿标准按城市分级执行。', 56, 100
    UNION ALL SELECT '请假与调休申请流程', 0,
           '请假须提前在系统提交，事假与年假分别计算；连续请假超过三天需直属主管与人力资源部共同确认。', 58, 0
    UNION ALL SELECT '请假与调休申请流程', 1,
           '调休须在加班发生后一个月内使用，过期作废；调休申请通过后不影响当月考勤统计。', 48, 100
    UNION ALL SELECT '客户方案评审清单', 0,
           '方案提交评审前须完成需求确认、成本测算与交付排期三项检查，缺项不得进入评审会。', 52, 0
    UNION ALL SELECT '客户方案评审清单', 1,
           '评审结论分为通过、有条件通过与退回；有条件通过须在五个工作日内补齐材料。', 46, 100
    UNION ALL SELECT '新员工入职三十天指引', 0,
           '入职首周完成账号开通、办公设备领取与团队介绍；第二周起参与岗位培训与业务流程学习。', 56, 0
    UNION ALL SELECT '新员工入职三十天指引', 1,
           '第三十天由直属主管完成试用期首月反馈，人力资源部记录培训完成情况与改进建议。', 52, 100
) c ON c.title = d.title
WHERE v.enterprise_id = @ent AND v.version_no = 1
  AND NOT EXISTS (SELECT 1 FROM rag_chunk ch
                  WHERE ch.document_id = v.document_id AND ch.version_id = v.id AND ch.chunk_index = c.chunk_index);

UPDATE rag_document d
JOIN rag_document_version v ON v.document_id = d.id AND v.version_no = 1
JOIN rag_chunk c ON c.document_id = d.id AND c.version_id = v.id
SET d.published_version_id = v.id
WHERE d.enterprise_id = @ent AND d.deleted = 0
  AND d.title IN ('员工差旅报销管理办法', '请假与调休申请流程', '客户方案评审清单', '新员工入职三十天指引');

-- 6. 旧版会话与任务锚点：只保留归属，不产生执行记录。
INSERT INTO rag_conversation (user_id, enterprise_id, member_id, title)
SELECT @u1, @ent, @m1, '差旅报销怎么算' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_conversation WHERE user_id = @u1 AND enterprise_id = @ent AND title = '差旅报销怎么算');
INSERT INTO rag_conversation (user_id, enterprise_id, member_id, title)
SELECT @u2, @ent, @m2, '调休有效期是多久' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_conversation WHERE user_id = @u2 AND enterprise_id = @ent AND title = '调休有效期是多久');
INSERT INTO rag_conversation (user_id, enterprise_id, member_id, title)
SELECT @u3, @ent, @m3, '方案评审要准备什么' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_conversation WHERE user_id = @u3 AND enterprise_id = @ent AND title = '方案评审要准备什么');
SET @conv1 = (SELECT id FROM rag_conversation WHERE user_id = @u1 AND enterprise_id = @ent AND title = '差旅报销怎么算' ORDER BY id LIMIT 1);
SET @conv2 = (SELECT id FROM rag_conversation WHERE user_id = @u2 AND enterprise_id = @ent AND title = '调休有效期是多久' ORDER BY id LIMIT 1);
SET @conv3 = (SELECT id FROM rag_conversation WHERE user_id = @u3 AND enterprise_id = @ent AND title = '方案评审要准备什么' ORDER BY id LIMIT 1);

INSERT INTO rag_message (conversation_id, role, content)
SELECT @conv1, 'user', '出差住宿能报多少？' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_message WHERE conversation_id = @conv1 AND role = 'user');
INSERT INTO rag_message (conversation_id, role, content)
SELECT @conv1, 'assistant', '住宿标准按城市分级执行，报销时须附原始票据与审批记录。' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_message WHERE conversation_id = @conv1 AND role = 'assistant');
INSERT INTO rag_message (conversation_id, role, content)
SELECT @conv2, 'user', '调休多久内必须用完？' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_message WHERE conversation_id = @conv2 AND role = 'user');
INSERT INTO rag_message (conversation_id, role, content)
SELECT @conv2, 'assistant', '调休须在加班发生后一个月内使用，过期作废。' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_message WHERE conversation_id = @conv2 AND role = 'assistant');
INSERT INTO rag_message (conversation_id, role, content)
SELECT @conv3, 'user', '方案评审前要准备哪些材料？' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_message WHERE conversation_id = @conv3 AND role = 'user');
INSERT INTO rag_message (conversation_id, role, content)
SELECT @conv3, 'assistant', '须完成需求确认、成本测算与交付排期三项检查，缺项不得进入评审会。' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_message WHERE conversation_id = @conv3 AND role = 'assistant');

INSERT INTO rag_task_reference (enterprise_id, conversation_id, message_id)
SELECT @ent, m.conversation_id, m.id
FROM rag_message m
WHERE m.conversation_id IN (@conv1, @conv2, @conv3) AND m.role = 'user'
  AND NOT EXISTS (SELECT 1 FROM rag_task_reference t
                  WHERE t.enterprise_id = @ent AND t.conversation_id = m.conversation_id AND t.message_id = m.id);

-- 7. 企业私有问答：会话、失败任务、受理快照与一条开放反馈。
INSERT INTO rag_knowledge_chat (id, enterprise_id, member_id, user_id, space_id, title, created_ms)
SELECT 'f0000000-0000-4000-8000-000000000001', @ent, @m1, @u1, @space1, '差旅报销咨询', @now_ms FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_chat WHERE id = 'f0000000-0000-4000-8000-000000000001');
INSERT INTO rag_knowledge_chat (id, enterprise_id, member_id, user_id, space_id, title, created_ms)
SELECT 'f0000000-0000-4000-8000-000000000002', @ent, @m2, @u2, @space1, '调休规则咨询', @now_ms FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_chat WHERE id = 'f0000000-0000-4000-8000-000000000002');

INSERT INTO rag_knowledge_job (id, chat_id, auth_version, question, status, created_ms, deadline_ms, finished_ms, answer_json, error_code)
SELECT 'f1000000-0000-4000-8000-000000000001', 'f0000000-0000-4000-8000-000000000001', 1,
       '出差住宿标准如何执行？', 'CANCELLED', @now_ms, @now_ms + 30000, @now_ms + 2000, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_job WHERE id = 'f1000000-0000-4000-8000-000000000001');
INSERT INTO rag_knowledge_job (id, chat_id, auth_version, question, status, created_ms, deadline_ms, finished_ms, answer_json, error_code)
SELECT 'f1000000-0000-4000-8000-000000000002', 'f0000000-0000-4000-8000-000000000002', 1,
       '调休过期后能否补用？', 'FAILED', @now_ms, @now_ms + 30000, @now_ms + 3000, NULL, 500 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_job WHERE id = 'f1000000-0000-4000-8000-000000000002');

INSERT INTO rag_knowledge_context (job_id, context_json, snapshot_id)
SELECT 'f1000000-0000-4000-8000-000000000001', '{"questions":["出差住宿标准如何执行？"]}', REPEAT('c3', 32) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_context WHERE job_id = 'f1000000-0000-4000-8000-000000000001');
INSERT INTO rag_knowledge_context (job_id, context_json, snapshot_id)
SELECT 'f1000000-0000-4000-8000-000000000002', '{"questions":["调休过期后能否补用？"]}', REPEAT('c4', 32) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_context WHERE job_id = 'f1000000-0000-4000-8000-000000000002');

SET @chunk1 = (SELECT c.id FROM rag_chunk c JOIN rag_document d ON d.id = c.document_id
               WHERE d.enterprise_id = @ent AND d.title = '请假与调休申请流程' ORDER BY c.id LIMIT 1);
INSERT INTO rag_knowledge_feedback (id, job_id, enterprise_id, space_id, member_id, chunk_id, category, comment_text, status, created_ms)
SELECT 'f2000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000002', @ent, @space1, @m2, @chunk1,
       'MISSING', '希望补充调休过期后的处理说明。', 'OPEN', @now_ms FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_knowledge_feedback WHERE id = 'f2000000-0000-4000-8000-000000000001');

-- 8. 入库与向量控制面：入库成功一条；向量任务取消一条，发布表只放撤回墓碑。
SET @doc1 = (SELECT id FROM rag_document WHERE enterprise_id = @ent AND title = '员工差旅报销管理办法' AND deleted = 0 ORDER BY id LIMIT 1);
SET @ver1 = (SELECT id FROM rag_document_version WHERE document_id = @doc1 AND version_no = 1);
INSERT INTO rag_ingest_job (id, enterprise_id, space_id, document_id, version_id, member_id, user_id,
    auth_version, expected_revision, action, status, attempts, created_ms, deadline_ms, finished_ms)
SELECT 'f3000000-0000-4000-8000-000000000001', @ent, @space1, @doc1, @ver1, @admin_member, @admin_user,
       1, 1, 'PARSE', 'SUCCEEDED', 1, @now_ms, @now_ms + 30000, @now_ms + 4000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_ingest_job WHERE id = 'f3000000-0000-4000-8000-000000000001');

INSERT INTO rag_vector_job (id, generation, enterprise_id, space_id, document_id, version_id, member_id, user_id,
    auth_version, expected_revision, request_sha256, source_sha256, collection_name, model_name, model_revision,
    dimensions, expected_chunks, status, created_ms, deadline_ms, finished_ms, failure_code)
SELECT 'f4000000-0000-4000-8000-000000000001', 'f4100000-0000-4000-8000-000000000001', @ent, @space1, @doc1, @ver1,
       @admin_member, @admin_user, 1, 1, REPEAT('d5', 32), REPEAT('a1', 32), 'knowingorder_demo',
       'demo-embedding', REPEAT('e6', 32), 1024, 2, 'CANCELLED', @now_ms, @now_ms + 120000, @now_ms + 5000, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_vector_job WHERE id = 'f4000000-0000-4000-8000-000000000001');

INSERT INTO rag_vector_publication (document_id, enterprise_id, space_id, job_id, revision, updated_ms)
SELECT @doc1, @ent, @space1, NULL, 1, @now_ms FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_vector_publication WHERE document_id = @doc1);

-- 9. 访客身份：只放一条已到期记录，凭据摘要为占位，不代表可用身份。
SET @guest_issued = @now_ms - 172800000;
INSERT INTO rag_guest_identity (credential_hash, issued_at_epoch_ms, accept_until_epoch_ms,
    drain_until_epoch_ms, expires_at_epoch_ms, status, result_read_window_ms)
SELECT UNHEX(REPEAT('7f', 32)), @guest_issued, @guest_issued + 86400000,
       @guest_issued + 90000000, @guest_issued + 93600000, 3, 600000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_guest_identity WHERE credential_hash = UNHEX(REPEAT('7f', 32)));

-- 10. 员工事务：一条生效规则、四条不同状态的申请、结算与退款调整。
INSERT INTO rag_affair_rule (enterprise_id, name, version, status, period, member_limit_cents,
    application_limit_cents, requires_manager, effective_from, created_by, created_ms, updated_ms)
SELECT @ent, '差旅补贴（演示）', 1, 'ACTIVE', 'MONTH', 50000, 20000, 1, DATE_SUB(NOW(), INTERVAL 1 DAY),
       @admin_member, @now_ms, @now_ms FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_rule
                  WHERE enterprise_id = @ent AND name = '差旅补贴（演示）' AND version = 1);
SET @rule = (SELECT id FROM rag_affair_rule
             WHERE enterprise_id = @ent AND name = '差旅补贴（演示）' AND version = 1);

INSERT INTO rag_affair_application (id, enterprise_id, applicant_member_id, rule_id, rule_version, platform,
    external_order_no, occurred_at, amount_cents, payer_member_id, payee_member_id, status, current_version,
    quota_period, reserved_cents, manager_approved_by, manager_approved_ms, revision, created_ms, updated_ms)
SELECT 'a1000000-0000-4000-8000-000000000001', @ent, @m1, @rule, 1, 'taobao', NULL,
       DATE_SUB(NOW(), INTERVAL 5 DAY), 12000, @m1, @m1, 'SETTLED', 1, @period, 0,
       @admin_member, @now_ms - 300000000, 1, @now_ms - 400000000, @now_ms - 200000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_application WHERE id = 'a1000000-0000-4000-8000-000000000001');
INSERT INTO rag_affair_application (id, enterprise_id, applicant_member_id, rule_id, rule_version, platform,
    external_order_no, occurred_at, amount_cents, payer_member_id, status, current_version, quota_period,
    reserved_cents, manager_approved_by, manager_approved_ms, revision, created_ms, updated_ms)
SELECT 'a1000000-0000-4000-8000-000000000002', @ent, @m2, @rule, 1, 'meituan', NULL,
       DATE_SUB(NOW(), INTERVAL 3 DAY), 8000, @m2, 'PENDING_SETTLEMENT', 1, @period, 8000,
       @admin_member, @now_ms - 100000000, 1, @now_ms - 200000000, @now_ms - 100000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_application WHERE id = 'a1000000-0000-4000-8000-000000000002');
INSERT INTO rag_affair_application (id, enterprise_id, applicant_member_id, rule_id, rule_version, platform,
    external_order_no, occurred_at, amount_cents, payer_member_id, status, current_version, quota_period,
    reserved_cents, revision, created_ms, updated_ms)
SELECT 'a1000000-0000-4000-8000-000000000003', @ent, @m3, @rule, 1, 'taobao', 'DEMO-ORDER-0003',
       DATE_SUB(NOW(), INTERVAL 1 DAY), 6000, @m3, 'SUBMITTED', 1, @period, 6000,
       1, @now_ms - 60000000, @now_ms - 60000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_application WHERE id = 'a1000000-0000-4000-8000-000000000003');
INSERT INTO rag_affair_application (id, enterprise_id, applicant_member_id, rule_id, rule_version, platform,
    external_order_no, occurred_at, amount_cents, payer_member_id, status, current_version, quota_period,
    reserved_cents, revision, created_ms, updated_ms)
SELECT 'a1000000-0000-4000-8000-000000000004', @ent, @m4, @rule, 1, 'meituan', NULL,
       DATE_SUB(NOW(), INTERVAL 10 DAY), 5000, @m4, 'CANCELLED', 1, @period, 0,
       1, @now_ms - 700000000, @now_ms - 600000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_application WHERE id = 'a1000000-0000-4000-8000-000000000004');

INSERT INTO rag_affair_application_version (application_id, version, amount_cents, participants_json,
    snapshot_json, change_reason, created_by, created_ms)
SELECT 'a1000000-0000-4000-8000-000000000001', 1, 12000,
       CONCAT('[{"memberId":', @m1, ',"shareCents":8000},{"memberId":', @m2, ',"shareCents":4000}]'),
       '{"rule":"差旅补贴（演示）","platform":"taobao"}', '创建申请', @m1, @now_ms - 400000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_application_version
                  WHERE application_id = 'a1000000-0000-4000-8000-000000000001' AND version = 1);
INSERT INTO rag_affair_application_version (application_id, version, amount_cents, participants_json,
    snapshot_json, change_reason, created_by, created_ms)
SELECT 'a1000000-0000-4000-8000-000000000002', 1, 8000,
       CONCAT('[{"memberId":', @m2, ',"shareCents":8000}]'),
       '{"rule":"差旅补贴（演示）","platform":"meituan"}', '创建申请', @m2, @now_ms - 200000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_application_version
                  WHERE application_id = 'a1000000-0000-4000-8000-000000000002' AND version = 1);
INSERT INTO rag_affair_application_version (application_id, version, amount_cents, participants_json,
    snapshot_json, change_reason, created_by, created_ms)
SELECT 'a1000000-0000-4000-8000-000000000003', 1, 6000,
       CONCAT('[{"memberId":', @m3, ',"shareCents":3000},{"memberId":', @m4, ',"shareCents":3000}]'),
       '{"rule":"差旅补贴（演示）","platform":"taobao"}', '创建申请', @m3, @now_ms - 60000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_application_version
                  WHERE application_id = 'a1000000-0000-4000-8000-000000000003' AND version = 1);
INSERT INTO rag_affair_application_version (application_id, version, amount_cents, participants_json,
    snapshot_json, change_reason, created_by, created_ms)
SELECT 'a1000000-0000-4000-8000-000000000004', 1, 5000,
       CONCAT('[{"memberId":', @m4, ',"shareCents":5000}]'),
       '{"rule":"差旅补贴（演示）","platform":"meituan"}', '创建申请', @m4, @now_ms - 700000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_application_version
                  WHERE application_id = 'a1000000-0000-4000-8000-000000000004' AND version = 1);

INSERT INTO rag_affair_participant (application_id, version, member_id, share_cents, status, confirmed_ms, note)
SELECT 'a1000000-0000-4000-8000-000000000001', 1, @m2, 4000, 'CONFIRMED', @now_ms - 350000000, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_participant
                  WHERE application_id = 'a1000000-0000-4000-8000-000000000001' AND version = 1 AND member_id = @m2);
INSERT INTO rag_affair_participant (application_id, version, member_id, share_cents, status, confirmed_ms, note)
SELECT 'a1000000-0000-4000-8000-000000000003', 1, @m4, 3000, 'PENDING', NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_participant
                  WHERE application_id = 'a1000000-0000-4000-8000-000000000003' AND version = 1 AND member_id = @m4);

INSERT INTO rag_affair_quota (enterprise_id, member_id, period, limit_cents, used_cents, reserved_cents, revision, updated_ms)
SELECT @ent, @m1, @period, 50000, 12000, 0, 2, @now_ms FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_quota WHERE enterprise_id = @ent AND member_id = @m1 AND period = @period);
INSERT INTO rag_affair_quota (enterprise_id, member_id, period, limit_cents, used_cents, reserved_cents, revision, updated_ms)
SELECT @ent, @m2, @period, 50000, 0, 8000, 2, @now_ms FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_quota WHERE enterprise_id = @ent AND member_id = @m2 AND period = @period);
INSERT INTO rag_affair_quota (enterprise_id, member_id, period, limit_cents, used_cents, reserved_cents, revision, updated_ms)
SELECT @ent, @m3, @period, 50000, 0, 6000, 2, @now_ms FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_quota WHERE enterprise_id = @ent AND member_id = @m3 AND period = @period);
INSERT INTO rag_affair_quota (enterprise_id, member_id, period, limit_cents, used_cents, reserved_cents, revision, updated_ms)
SELECT @ent, @m4, @period, 50000, 0, 0, 1, @now_ms FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_quota WHERE enterprise_id = @ent AND member_id = @m4 AND period = @period);

INSERT INTO rag_affair_settlement (enterprise_id, application_id, application_version, rule_id, amount_cents,
    payee_member_id, status, finance_member_id, approved_ms, settled_ms, note, revision, created_ms, updated_ms)
SELECT @ent, 'a1000000-0000-4000-8000-000000000001', 1, @rule, 12000, @m1,
       'SETTLED', @finance_member, @now_ms - 280000000, @now_ms - 250000000, '演示结算，不代表工资已发放',
       1, @now_ms - 280000000, @now_ms - 250000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_settlement WHERE application_id = 'a1000000-0000-4000-8000-000000000001');
SET @settlement1 = (SELECT id FROM rag_affair_settlement WHERE application_id = 'a1000000-0000-4000-8000-000000000001');

INSERT INTO rag_affair_adjustment (enterprise_id, application_id, settlement_id, kind, amount_cents, reason,
    operator_member_id, created_ms)
SELECT @ent, 'a1000000-0000-4000-8000-000000000001', @settlement1, 'REFUND_PARTIAL', 2000,
       '演示：部分退款登记', @finance_member, @now_ms - 240000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM rag_affair_adjustment
                  WHERE application_id = 'a1000000-0000-4000-8000-000000000001'
                    AND kind = 'REFUND_PARTIAL' AND amount_cents = 2000);

COMMIT;

SELECT 'DEMO_SEED_COMMITTED' AS marker,
       (SELECT COUNT(*) FROM sys_user WHERE email LIKE '%@zhixu.demo') AS demo_users,
       (SELECT COUNT(*) FROM sys_enterprise_member m JOIN sys_user u ON u.id = m.user_id
        WHERE m.enterprise_id = @ent AND u.email LIKE '%@zhixu.demo') AS demo_members,
       (SELECT COUNT(*) FROM rag_knowledge_space WHERE enterprise_id = @ent AND name IN ('制度与流程', '产品与方案', '新员工指南')) AS demo_spaces,
       (SELECT COUNT(*) FROM rag_document WHERE enterprise_id = @ent AND title IN ('员工差旅报销管理办法', '请假与调休申请流程', '客户方案评审清单', '新员工入职三十天指引')) AS demo_documents,
       (SELECT COUNT(*) FROM rag_affair_application WHERE id LIKE 'a1000000-%') AS demo_applications;
