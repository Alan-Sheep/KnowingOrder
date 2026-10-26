#!/usr/bin/env bash
# 知序 · 剩余部署一键完成（在 bootstrap.sh 之后执行）
#
# 用法：
#   1) 本地上传： scp backend\deploy\finish.sh root@IP:/tmp/deploy/
#   2) 服务器执行：bash /tmp/deploy/finish.sh
#
# 覆盖：连接 MySQL → 建库授权 → 导结构+12 个迁移 → 首建企业管理员 → 演示数据
#       → 写环境变量 → 放前端产物 → 启动并自检
# 幂等：已建库/已有企业/已有 env 都会识别并跳过，失败可直接重跑。

set -uo pipefail

APP_JAR=/tmp/rag-qa-0.0.1-SNAPSHOT.jar
APP_DIR=/opt/rag-qa
WEB_DIR=/var/www/rag-qa
ENV_FILE=/etc/rag-qa/env
DB_NAME=rag_qa
DB_USER=rag_qa_app
ENT_NAME="知序科技有限公司"
ADMIN_USER="admin"
ADMIN_PASS="Zhixu@2026Demo"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '    \033[33m!\033[0m %s\n' "$1"; }
die()  { printf '    \033[31m✗\033[0m %s\n' "$1"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 执行（sudo bash $0）"
[ -f "$APP_JAR" ] || die "找不到 $APP_JAR，请确认已上传 jar"

# ---------------------------------------------------------------- 1
step "1/5 连接 MySQL"
MYSQL=(mysql -uroot)
if "${MYSQL[@]}" -e "SELECT 1" >/dev/null 2>&1; then
  ok "root 免密登录可用"
else
  TEMP=$(grep -oE 'temporary password.*: *[^ ]+' /var/log/mysqld.log 2>/dev/null | tail -1 | sed 's/.*: *//')
  if [ -n "${TEMP:-}" ]; then
    ROOTPW=$(openssl rand -hex 12)
    if mysql -uroot -p"$TEMP" --connect-expired-password \
         -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '$ROOTPW';" >/dev/null 2>&1; then
      printf '%s\n' "$ROOTPW" > /root/.mysqlroot && chmod 600 /root/.mysqlroot
      MYSQL=(mysql -uroot -p"$ROOTPW")
      ok "已用日志中的临时口令接管 root，新口令存于 /root/.mysqlroot"
    else
      die "临时口令接管 root 失败，请手动执行 mysql_secure_installation 后重跑"
    fi
  else
    die "root 需要口令且找不到临时口令，请手动执行：mysql -uroot -p"
  fi
fi
printf '    %s\n' "$("${MYSQL[@]}" -N -B -e "SELECT VERSION()" 2>/dev/null)"

# ---------------------------------------------------------------- 2
step "2/5 建库与应用账号"
if [ -f /root/.dbpass ]; then
  DBPASS=$(cat /root/.dbpass); ok "复用已有口令 /root/.dbpass"
else
  DBPASS=$(openssl rand -hex 12)
  printf '%s\n' "$DBPASS" > /root/.dbpass && chmod 600 /root/.dbpass
  ok "已生成随机口令并存入 /root/.dbpass"
fi

"${MYSQL[@]}" <<SQL
CREATE DATABASE IF NOT EXISTS ${DB_NAME} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'127.0.0.1' IDENTIFIED BY '${DBPASS}';
ALTER USER '${DB_USER}'@'127.0.0.1' IDENTIFIED BY '${DBPASS}';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON ${DB_NAME}.* TO '${DB_USER}'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
RC=$?
[ "$RC" -eq 0 ] || die "建库或授权失败（退出码 $RC）"
ok "库 ${DB_NAME} 与账号 ${DB_USER}@127.0.0.1 就绪"

# ---------------------------------------------------------------- 3
step "3/5 导入表结构与增量"
count_tables() {
  local n
  n=$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}'" 2>/dev/null | tr -d ' \r')
  printf '%s' "${n:-0}"
}
has_table() {
  local n
  n=$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}' AND table_name='$1'" 2>/dev/null | tr -d ' \r')
  [ "${n:-0}" -gt 0 ]
}

TABLES=$(count_tables)
if [ "$TABLES" -gt 0 ]; then
  warn "库内已有 ${TABLES} 张表，跳过 schema.sql（已有库禁止重放）"
else
  "${MYSQL[@]}" "$DB_NAME" < /tmp/schema.sql || die "schema.sql 导入失败"
  ok "schema.sql 导入完成（23 张基础表）"
fi

# 20261017~20261020 的 10 支增量内容已全部并入 schema.sql，
# 在空库上执行必然因重复建表中断（ERROR 1050），因此只补 schema.sql 未收录的两支。
if has_table rag_affair_rule; then
  warn "员工事务表已存在，跳过 20261025_01"
else
  "${MYSQL[@]}" "$DB_NAME" < /tmp/sql/migrations/20261025_01_affairs_subsidy.sql \
    || die "事务增量 20261025_01 失败"
  ok "员工事务 8 张表 + FINANCE 角色约束已建立"
fi

if has_table rag_knowledge_chat && \
   [ "$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${DB_NAME}' AND table_name='rag_knowledge_chat' AND column_name='deleted_ms'" 2>/dev/null | tr -d ' \r')" -gt 0 ]; then
  warn "会话软删除列已存在，跳过 20261025_02"
else
  "${MYSQL[@]}" "$DB_NAME" < /tmp/sql/migrations/20261025_02_knowledge_chat_soft_delete.sql \
    || die "软删除增量 20261025_02 失败"
  ok "会话软删除列 deleted_ms 已建立"
fi

printf '    当前表数量 %s\n' "$(count_tables)"

# ---------------------------------------------------------------- 4
step "4/5 首建企业管理员与演示数据"
install -m 644 "$APP_JAR" "$APP_DIR/app.jar" || die "拷贝 jar 失败"
chown ragqa:ragqa "$APP_DIR/app.jar"
ok "jar 已放到 $APP_DIR/app.jar"

ENTS=$("${MYSQL[@]}" "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM sys_enterprise" 2>/dev/null | tr -d ' \r')
if [ "${ENTS:-0}" -eq 0 ]; then
  printf '    正在创建企业与管理员（约 10 秒）…\n'
  KNOWINGORDER_BOOTSTRAP_ENTERPRISE="$ENT_NAME" \
  KNOWINGORDER_BOOTSTRAP_USERNAME="$ADMIN_USER" \
  KNOWINGORDER_BOOTSTRAP_PASSWORD="$ADMIN_PASS" \
  SPRING_PROFILES_ACTIVE=prod \
  DB_URL="jdbc:mysql://127.0.0.1:3306/${DB_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false" \
  DB_USERNAME="$DB_USER" DB_PASSWORD="$DBPASS" \
  KNOWLEDGE_STORAGE_ROOT=/data/knowingorder/knowledge \
  KNOWLEDGE_UPLOAD_TEMP=/data/knowingorder/tmp \
  java -Dloader.main=com.ragqa.utils.BootstrapAdminCommand -cp "$APP_DIR/app.jar" \
       org.springframework.boot.loader.launch.PropertiesLauncher 2>&1 | tail -4
  NEW=$("${MYSQL[@]}" "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM sys_enterprise" 2>/dev/null | tr -d ' \r')
  NEW=${NEW:-0}
  [ "$NEW" -gt 0 ] || die "首建失败，请把上面的输出发我"
  ok "企业「${ENT_NAME}」与管理员 ${ADMIN_USER} 已创建"
else
  warn "已有 ${ENTS} 家企业，跳过首建（该命令只允许执行一次）"
fi

if [ -f /tmp/sql/demo/20261025_seed_demo_data.sql ]; then
  "${MYSQL[@]}" "$DB_NAME" < /tmp/sql/demo/20261025_seed_demo_data.sql \
    && ok "演示数据导入完成" || warn "演示数据导入有报错，可先跳过继续部署"
else
  warn "未找到演示数据脚本，跳过"
fi
printf '    账号 %s 个 / 知识空间 %s 个\n' \
  "$("${MYSQL[@]}" "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM sys_user" 2>/dev/null | tr -d ' \r')" \
  "$("${MYSQL[@]}" "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM rag_knowledge_space" 2>/dev/null | tr -d ' \r')"

# ---------------------------------------------------------------- 5
step "5/5 环境变量、前端产物与启动"
mkdir -p /etc/rag-qa
if [ -f "$ENV_FILE" ]; then
  warn "$ENV_FILE 已存在，仅更新数据库口令"
else
  cp /tmp/deploy/env.prod.example "$ENV_FILE" || die "找不到 env.prod.example"
  ok "已从模板生成 $ENV_FILE"
fi
sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=${DBPASS}|" "$ENV_FILE"
grep -q '^DB_PASSWORD=.\+' "$ENV_FILE" || printf 'DB_PASSWORD=%s\n' "$DBPASS" >> "$ENV_FILE"
chmod 600 "$ENV_FILE" && chown root:ragqa "$ENV_FILE"
ok "数据库口令已写入环境文件"

[ -d /tmp/dist ] || die "找不到 /tmp/dist"
cp -r /tmp/dist/. "$WEB_DIR"/ && chmod -R a+rX "$WEB_DIR"
ok "前端产物已放到 $WEB_DIR"

systemctl daemon-reload
systemctl enable rag-qa >/dev/null 2>&1
systemctl restart rag-qa
printf '    等待服务就绪'
for _ in $(seq 1 45); do
  sleep 1; printf '.'
  if systemctl is-active --quiet rag-qa && curl -s -o /dev/null --max-time 2 http://127.0.0.1:8080/api/auth/me; then break; fi
done
printf '\n'

STATE=$(systemctl is-active rag-qa 2>/dev/null)
BACK=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8080/api/auth/me 2>/dev/null || echo 000)
SITE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8090/ 2>/dev/null || echo 000)
printf '    服务状态 %s\n    后端 8080 -> %s（未登录返回 401 属正常）\n    站点 8090 -> %s\n' "$STATE" "$BACK" "$SITE"

if [ "$STATE" != "active" ] || [ "$SITE" != "200" ]; then
  warn "启动异常，最近 20 行日志："
  journalctl -u rag-qa -n 20 --no-pager
  exit 1
fi

IP=$(curl -s --max-time 3 http://100.100.100.200/latest/meta-data/eipv4 2>/dev/null || true)
[ -n "${IP:-}" ] || IP="<你的公网IP>"

# 演示口令不硬编码：直接取种子脚本声明值，避免文档与数据不一致
DEMO_PW=$(grep -oE '口令统一为[^（]*' /tmp/sql/demo/20261025_seed_demo_data.sql 2>/dev/null \
          | head -1 | sed 's/口令统一为//;s/[[:space:]]//g')
[ -n "${DEMO_PW:-}" ] || DEMO_PW="见种子脚本第 3 行"

cat <<EOF

------------------------------------------------------------------
部署完成，访问：http://${IP}:8090

  管理员      ${ADMIN_USER} / ${ADMIN_PASS}
  演示管理员  zhixuadmin / ${DEMO_PW}
  演示财务    zhixufinance / ${DEMO_PW}
  普通员工    chenzihan 等 100 个 / ${DEMO_PW}

  数据库口令  /root/.dbpass（chmod 600）
  应用账号    ${DB_USER}@127.0.0.1
  服务日志    journalctl -u rag-qa -f
------------------------------------------------------------------
EOF
