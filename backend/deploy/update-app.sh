#!/usr/bin/env bash
# 只更新运行产物：后端 jar 与前端静态文件；不动数据库、环境变量和 systemd 单元。
#
# 用法（本地一条命令上传并执行）：
#   cd backend && ./mvnw -q -DskipTests package && cd ../frontend && npm run build && cd ..
#   scp backend/target/rag-qa-0.0.1-SNAPSHOT.jar root@8.155.137.88:/tmp/app.jar
#   scp -r frontend/dist root@8.155.137.88:/tmp/dist
#   scp backend/deploy/update-app.sh root@8.155.137.88:/tmp/
#   ssh root@8.155.137.88 "bash /tmp/update-app.sh"
#
# 幂等：可重复执行；旧 jar 备份为 app.jar.bak-<时间戳>，失败可直接回滚。
# 只重启服务、不迁移数据库，所以对现有演示数据无影响。

set -uo pipefail

APP_JAR=/tmp/app.jar
APP_DIR=/opt/rag-qa
WEB_DIR=/var/www/rag-qa
DIST=/tmp/dist
# scp -r 在 /tmp/dist 已存在时会把它复制成 /tmp/dist/dist（嵌套），这里自动纠正到真实产物目录。
if [ -d "$DIST/dist" ] && [ -f "$DIST/dist/index.html" ]; then
  warn "检测到 /tmp/dist/dist 嵌套目录（上次 scp 目标已存在导致），已自动纠正"
  DIST="$DIST/dist"
fi
STAMP=$(date +%Y%m%d-%H%M%S)

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '    \033[33m!\033[0m %s\n' "$1"; }
die()  { printf '    \033[31m✗\033[0m %s\n' "$1"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 执行（sudo bash $0）"

# ---------------------------------------------------------------- 1 后端
step "1/3 更新后端 jar"
if [ -f "$APP_JAR" ]; then
  if [ -f "$APP_DIR/app.jar" ]; then
    cp -p "$APP_DIR/app.jar" "$APP_DIR/app.jar.bak-$STAMP" || die "备份旧 jar 失败"
    ok "旧 jar 已备份为 app.jar.bak-$STAMP"
  fi
  install -m 644 "$APP_JAR" "$APP_DIR/app.jar" || die "安装 jar 失败"
  chown ragqa:ragqa "$APP_DIR/app.jar"
  ok "jar 已更新（$(du -h "$APP_DIR/app.jar" | cut -f1)）"
else
  warn "未找到 /tmp/app.jar，跳过后端更新"
fi

# ---------------------------------------------------------------- 2 前端
step "2/3 更新前端静态文件"
if [ -d "$DIST" ]; then
  cp -r "$DIST"/. "$WEB_DIR"/ || die "复制前端产物失败"
  chmod -R a+rX "$WEB_DIR"
  ok "前端产物已覆盖到 $WEB_DIR（$(ls "$WEB_DIR"/assets | wc -l) 个资源文件）"
  printf '    当前入口 %s\n' "$(grep -o 'assets/[^"]*\.js' "$WEB_DIR/index.html" | head -1)"
else
  warn "未找到 /tmp/dist，跳过前端更新"
fi

# ---------------------------------------------------------------- 3 重启与自检
step "3/3 重启并自检"
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
  printf '\n    回滚命令：install -m 644 %s/app.jar.bak-%s %s/app.jar && systemctl restart rag-qa\n' \
    "$APP_DIR" "$STAMP" "$APP_DIR"
  exit 1
fi

ok "更新完成；浏览器请按 Ctrl+Shift+R 强制刷新，避免读到旧构建的缓存。"
