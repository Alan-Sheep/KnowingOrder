#!/usr/bin/env bash
# 知序 · ECS 初始化脚本（Alibaba Cloud Linux 3）
#
# 用途：把「装运行环境 + 建目录建账号 + 调小内存参数 + 配 swap」一次做完。
# 不做：建库导数据、填密钥、首次管理员引导 —— 这些必须人工确认，见下方提示。
#
# 用法：
#   1) 把 backend/deploy/ 整个目录传到服务器：scp -r backend/deploy root@IP:/tmp/deploy
#   2) sudo bash /tmp/deploy/bootstrap.sh
#
# 幂等：可重复执行，已完成的步骤会跳过。

set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "请用 root 执行：sudo bash $0" >&2
  exit 1
fi

APP_DIR=/opt/rag-qa
WEB_DIR=/var/www/rag-qa
DATA_DIR=/data/knowingorder
LOG_DIR=/var/log/rag-qa
RUN_USER=ragqa
PORT=8090

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '    \033[33m!\033[0m %s\n' "$1"; }

step "1/7 安装运行环境（JDK 17 / Nginx / MySQL 8）"
dnf install -y java-17-openjdk-headless nginx mysql-server tar >/dev/null
ok "$(java -version 2>&1 | head -1)"
ok "$(mysql --version)"

MYSQL_VER=$(mysql --version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
if [ "$(printf '%s\n8.0.16\n' "$MYSQL_VER" | sort -V | head -1)" != "8.0.16" ]; then
  warn "MySQL 版本 $MYSQL_VER 低于 schema 要求的 8.0.16，请改用官方源安装"
  exit 1
fi

step "2/7 创建运行账号与目录"
if id "$RUN_USER" >/dev/null 2>&1; then
  ok "用户 $RUN_USER 已存在"
else
  useradd -r -s /sbin/nologin "$RUN_USER"
  ok "已创建用户 $RUN_USER"
fi
mkdir -p "$APP_DIR" "$APP_DIR/logs" "$WEB_DIR" "$DATA_DIR/knowledge" "$DATA_DIR/tmp" "$LOG_DIR"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR" "$DATA_DIR" "$LOG_DIR"
chmod 750 "$DATA_DIR"
ok "$APP_DIR / $WEB_DIR / $DATA_DIR / $LOG_DIR"

step "3/7 调低 MySQL 内存占用（4 GiB 机器必需）"
cat > /etc/my.cnf.d/ragqa.cnf <<'EOF'
[mysqld]
innodb_buffer_pool_size = 256M
max_connections = 60
innodb_log_file_size = 128M
performance_schema = OFF
EOF
systemctl enable --now mysqld
systemctl enable --now nginx
ok "MySQL 与 Nginx 已设为开机启动并运行中"

step "4/7 配置 2 GiB swap（防 PDF 解析峰值 OOM）"
if swapon --show | grep -q .; then
  ok "已存在 swap，跳过：$(swapon --show --noheadings | head -1)"
else
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  ok "已创建 /swapfile 2 GiB"
fi
if [ "$(cat /proc/sys/vm/swappiness)" != "10" ]; then
  echo 'vm.swappiness = 10' > /etc/sysctl.d/99-ragqa.conf
  sysctl -p /etc/sysctl.d/99-ragqa.conf >/dev/null
  ok "vm.swappiness = 10（不积极换出，避免无谓抖动）"
fi

step "5/7 放行 $PORT（firewalld 若在用）"
if systemctl is-active --quiet firewalld; then
  firewall-cmd --permanent --add-port=${PORT}/tcp >/dev/null
  firewall-cmd --reload >/dev/null
  ok "firewalld 已放行 $PORT/tcp"
else
  warn "firewalld 未启用，按阿里云惯例由安全组控制，请确认安全组已放行 $PORT"
fi

step "6/7 安装 systemd 单元与 Nginx 站点配置"
if [ -f /tmp/deploy/rag-qa.service ]; then
  install -m 644 /tmp/deploy/rag-qa.service /etc/systemd/system/rag-qa.service
  systemctl daemon-reload
  ok "已安装 rag-qa.service（未启动，等数据库与密钥就绪）"
else
  warn "未找到 /tmp/deploy/rag-qa.service，跳过"
fi
if [ -f /tmp/deploy/nginx-rag-qa.conf ]; then
  install -m 644 /tmp/deploy/nginx-rag-qa.conf /etc/nginx/conf.d/rag-qa.conf
  nginx -t >/dev/null 2>&1 && systemctl reload nginx
  ok "已安装 Nginx 站点配置并重载"
else
  warn "未找到 /tmp/deploy/nginx-rag-qa.conf，跳过"
fi

step "7/7 环境自检"
printf '    %-18s %s\n' "内网 IP" "$(hostname -I | awk '{print $1}')"
printf '    %-18s %s\n' "监听 $PORT" "$(ss -lntp 2>/dev/null | grep -c ":$PORT " || true) 个"
printf '    %-18s %s\n' "内存" "$(free -h | awk '/^Mem:/{print $2" 总 / "$7" 可用"}')"
printf '    %-18s %s\n' "swap" "$(free -h | awk '/^Swap:/{print $2}')"
printf '    %-18s %s\n' "磁盘" "$(df -h / | awk 'NR==2{print $2" 总 / "$4" 可用"}')"

cat <<'NEXT'

------------------------------------------------------------------
基础环境就绪。接下来必须人工完成，脚本不代劳：

1) 建库与授权
   mysql -uroot -p
   CREATE DATABASE rag_qa DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'rag_qa_app'@'127.0.0.1' IDENTIFIED BY '<强密码>';
   GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES
     ON rag_qa.* TO 'rag_qa_app'@'127.0.0.1';
   FLUSH PRIVILEGES;

2) 导结构与迁移（新库）
   mysql -uroot -p rag_qa < schema.sql
   for f in sql/migrations/*.sql; do mysql -uroot -p rag_qa < "$f"; done
   mysql -uroot -p rag_qa < sql/demo/20261025_seed_demo_data.sql   # 演示数据，按需

3) 填环境变量
   cp /tmp/deploy/env.prod.example /etc/rag-qa/env
   chmod 600 /etc/rag-qa/env && chown root:ragqa /etc/rag-qa/env
   vi /etc/rag-qa/env

4) 首次管理员（一次性，跑完清理变量）
   见 docs/企业身份与资源授权实施说明.md 第 97 行

5) 放 jar 与前端产物
   install -m 644 app.jar /opt/rag-qa/app.jar && chown ragqa:ragqa /opt/rag-qa/app.jar
   cp -r dist/* /var/www/rag-qa/

6) 启动并观察
   systemctl enable --now rag-qa && journalctl -u rag-qa -f
------------------------------------------------------------------
NEXT
