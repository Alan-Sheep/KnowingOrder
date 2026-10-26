import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { createConnection } from 'node:net';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

// 仅使用本脚本新建的E盘datadir及固定13316端口；不读取本机MySQL密码，不连接3306。
const port = 13316, mysqlBin = 'E:/MySQL/bin';
const run = (program, args, sql = '', env = process.env, timeout = 15000) => new Promise((resolve, reject) => {
  const child = execFile(program, args, { env, timeout, windowsHide: true, encoding: 'utf8', maxBuffer: 1024 * 1024 },
    (error, stdout, stderr) => resolve({ code: error?.code ?? 0, stdout, stderr }));
  child.on('error', reject); child.stdin.end(sql, 'utf8');
});
const available = await new Promise(resolve => {
  const socket = createConnection({ host: '127.0.0.1', port });
  socket.on('connect', () => { socket.destroy(); resolve(false); });
  socket.on('error', () => resolve(true));
});
assert.ok(available, '13316已占用，不接管任何现有MySQL实例');
const artifactRoot = 'E:/Codex/test-artifacts';
await mkdir(artifactRoot, { recursive: true });
const directory = await mkdtemp(join(artifactRoot, 'school-replacement-mysql-'));
const data = join(directory, 'data'), temporary = join(directory, 'tmp');
await mkdir(data); await mkdir(temporary);
const initialized = await run(join(mysqlBin, 'mysqld.exe'), ['--no-defaults', '--initialize-insecure', '--basedir=E:/MySQL', `--datadir=${data}`], '', process.env, 60000);
assert.equal(initialized.code, 0, initialized.stderr.slice(-1200));
const server = spawn(join(mysqlBin, 'mysqld.exe'), ['--no-defaults', '--basedir=E:/MySQL', `--datadir=${data}`,
  `--tmpdir=${temporary}`, `--port=${port}`, '--bind-address=127.0.0.1', '--mysqlx=0', '--skip-log-bin',
  '--innodb-buffer-pool-size=64M', `--log-error=${join(directory, 'server.log')}`, `--pid-file=${join(directory, 'server.pid')}`],
  { stdio: 'ignore', windowsHide: true });
let launchError;
server.on('error', error => { launchError = error; });
const connection = ['--no-defaults', '--host=127.0.0.1', `--port=${port}`, '--user=root', '--default-character-set=utf8mb4', '--connect-timeout=2', '--batch', '--raw', '--skip-column-names'];
let environment = { ...process.env, MYSQL_PWD: '' };
const query = sql => run(join(mysqlBin, 'mysql.exe'), connection, sql, environment);
let checks = 0;
const passed = text => console.log(`通过 ${++checks}：${text}`);
try {
  let ready = false;
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    if (server.exitCode !== null) throw Error('隔离MySQL未启动，请核对E盘server.log');
    const result = await query('SELECT 1;');
    if (result.code === 0) { ready = true; break; }
    await delay(200);
  }
  assert.ok(ready, '隔离MySQL启动超时');
  const password = `Temporary-${randomUUID()}`;
  assert.equal((await query(`ALTER USER 'root'@'localhost' IDENTIFIED BY '${password}';`)).code, 0);
  environment = { ...process.env, MYSQL_PWD: password };
  const sql = await readFile(new URL('../../backend/sql/demo/20261024_replace_school_data.sql', import.meta.url), 'utf8');
  const schema = await readFile(new URL('../../backend/schema.sql', import.meta.url), 'utf8');
  const quote = value => `'${String(value).replaceAll("'", "''")}'`;
  const spaceIds = [5, 12, 13, 14, 15, 16, 17, 38, 39];
  const documentIds = [8, 9, 10, 11, 12, 13, 14, 15, 16, 37, 38, 39, 40, 41, 42, 43, 44, 45];
  const spaceOf = id => id <= 11 ? 12 : id <= 16 ? id + 1 : id <= 40 ? 38 : 39;
  const names = ['adminW', 'org-test-1792391328887', 'office-live-1792830843016-cb1ac17a', 'office-live-1792831327440-5b80b073'];
  const fixture = [
    "INSERT INTO sys_enterprise(id,name,status,permission_version) VALUES(1,'知序',0,3);",
    `INSERT INTO sys_user(id,username,password_hash) VALUES ${[25, 36, 57, 58].map((id, i) => `(${id},${quote(names[i])},'fixture-only-hash')`).join(',')};`,
    "INSERT INTO sys_department(id,enterprise_id,name,status) VALUES(1,1,'org-test-1792391328887-部门-归档',1);",
    "INSERT INTO sys_enterprise_member(id,enterprise_id,user_id,role,status) VALUES(1,1,25,'ADMIN',0),(12,1,36,'EMPLOYEE',1),(33,1,57,'EMPLOYEE',1),(34,1,58,'EMPLOYEE',1);",
    `INSERT INTO rag_knowledge_space(id,enterprise_id,name) VALUES ${spaceIds.map(id => `(${id},1,${quote(id === 38 ? names[2] : id === 39 ? names[3] : '联调测试空间-' + id)})`).join(',')};`,
    ...documentIds.map(id => `INSERT INTO rag_document(id,enterprise_id,knowledge_space_id,title,source_type,owner_member_id,published_version_id) VALUES(${id},1,${spaceOf(id)},'fixture-document-${id}','UPLOAD',1,${id === 15 ? 14 : 'NULL'});`),
    ...documentIds.map(id => `INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,upload_request_id,original_name,storage_key,content_sha256,request_sha256,file_size,parse_status,publication_status,created_by_member) VALUES(${id - 1},1,${spaceOf(id)},${id},1,${quote(randomUUID())},'fixture.md',${quote(randomUUID())},REPEAT('a',64),REPEAT('b',64),16,'READY',${quote(id === 15 ? 'PUBLISHED' : 'WITHDRAWN')},1);`),
    ...Array.from({ length: 44 }, (_, i) => { const id = documentIds[i % 18]; return `INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content) VALUES(${i + 1},${id},${id - 1},${Math.floor(i / 18)},'fixture-content');`; }),
    ...spaceIds.map(id => `INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,${id},1,'MAINTAIN');`)
  ];
  const chats = Array.from({ length: 3 }, () => randomUUID()), jobs = Array.from({ length: 4 }, () => randomUUID());
  fixture.push(...chats.map((id, i) => `INSERT INTO rag_knowledge_chat(id,enterprise_id,member_id,user_id,space_id,title,created_ms) VALUES(${quote(id)},1,1,25,${[12, 16, 17][i]},'fixture-chat',1);`));
  fixture.push(...jobs.map((id, i) => `INSERT INTO rag_knowledge_job(id,chat_id,auth_version,question,status,created_ms,deadline_ms,finished_ms) VALUES(${quote(id)},${quote(chats[i % 3])},1,'fixture-question','COMPLETED',1,30001,2);`));
  fixture.push(...jobs.map(id => `INSERT INTO rag_knowledge_context(job_id,context_json,snapshot_id) VALUES(${quote(id)},'{}',REPEAT('a',64));`));
  fixture.push(`INSERT INTO rag_knowledge_feedback(id,job_id,enterprise_id,space_id,member_id,category,comment_text,created_ms) VALUES(${quote(randomUUID())},${quote(jobs[0])},1,12,1,'OTHER','fixture-feedback',1);`);
  fixture.push(...Array.from({ length: 11 }, (_, i) => `INSERT INTO rag_ingest_job(id,enterprise_id,space_id,document_id,version_id,member_id,user_id,auth_version,expected_revision,action,status,created_ms,deadline_ms,finished_ms) VALUES(${quote(randomUUID())},1,12,8,7,1,25,1,1,'PARSE','SUCCEEDED',1,30001,2);`));
  const reset = async () => {
    const location = await query('SELECT @@datadir;');
    assert.equal(location.code, 0);
    assert.equal(location.stdout.trim().replaceAll('\\', '/').replace(/\/$/, '').toLowerCase(), data.replaceAll('\\', '/').toLowerCase(), '拒绝操作非本轮隔离datadir');
    const result = await query('DROP DATABASE IF EXISTS rag_qa;\n' + schema + '\n' + fixture.join('\n'));
    assert.equal(result.code, 0, result.stderr.slice(-700));
  };
  const snapshot = async () => {
    const result = await query("USE rag_qa; SELECT name,permission_version FROM sys_enterprise; SELECT COUNT(*) FROM rag_document; SELECT COUNT(*) FROM rag_chunk; SELECT COUNT(*) FROM rag_knowledge_job; SELECT COUNT(*) FROM sys_user; SELECT password_hash FROM sys_user WHERE id=25;");
    assert.equal(result.code, 0); return result.stdout.replaceAll('\r\n', '\n');
  };
  await reset();
  const original = await snapshot();
  const success = await query(sql);
  assert.equal(success.code, 0, success.stderr.slice(-800));
  assert.ok(success.stdout.includes('SCHOOL_REPLACEMENT_COMMITTED'));
  assert.equal((await snapshot()).trim(), '知序大学\t4\n0\n0\n0\n1\nfixture-only-hash');
  passed('完全匹配盘点时提交清理，只保留管理员账号及原密码散列，学校名称和版本正确');
  const repeated = await query(sql);
  assert.notEqual(repeated.code, 0);
  assert.equal((await snapshot()).trim(), '知序大学\t4\n0\n0\n0\n1\nfixture-only-hash');
  passed('重复执行被前置校验拒绝，不二次修改学校或管理员');
  await reset();
  assert.equal(await snapshot(), original);
  assert.equal((await query("USE rag_qa; INSERT INTO rag_knowledge_space(enterprise_id,name) VALUES(1,'不得删除的新空间');")).code, 0);
  const changed = await snapshot();
  assert.notEqual((await query(sql)).code, 0);
  assert.equal(await snapshot(), changed);
  assert.equal((await query("USE rag_qa; SELECT COUNT(*) FROM rag_knowledge_space WHERE name='不得删除的新空间';")).stdout.trim(), '1');
  passed('盘点后新增空间时全程拒绝清理，原文档、片段和新增空间保留');
  await reset();
  const injected = sql.replace('COMMIT;', "INSERT INTO knowingorder_school_guard VALUES ('injected-failure', 0);\nCOMMIT;");
  assert.notEqual((await query(injected)).code, 0);
  assert.equal(await snapshot(), original);
  passed('删除后、提交前故意触发CHECK错误，默认MySQL批处理断开并回滚全部DML');
  await reset();
  assert.equal((await query("USE rag_qa; UPDATE rag_ingest_job SET status='RUNNING',finished_ms=NULL LIMIT 1;")).code, 0);
  const active = await snapshot();
  assert.notEqual((await query(sql)).code, 0);
  assert.equal(await snapshot(), active);
  passed('有运行中任务时拒绝替换，避免后台写入与清理竞争');
  await writeFile(join(directory, 'result.json'), JSON.stringify({ passed: checks, mode: 'isolated-mysql', port, developmentDatabaseTouched: false }, null, 2) + '\n', 'utf8');
  console.log(`学校替换SQL隔离验证完成：${checks}/${checks}，未连接开发库3306；证据：${directory}`);
} finally {
  const stopped = await run(join(mysqlBin, 'mysqladmin.exe'), ['--no-defaults', '--host=127.0.0.1', `--port=${port}`, '--user=root', 'shutdown'], '', environment);
  if (stopped.code !== 0) server.kill();
}
