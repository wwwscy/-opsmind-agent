#!/bin/bash
# OpsMind Agent 一键启动脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="$HOME/data"

echo "=== OpsMind Agent 启动脚本 ==="

# 1. 启动 Colima
echo "[1/5] 启动 Colima..."
if ! colima list 2>/dev/null | grep -q "Running"; then
  colima start
else
  echo "  Colima 已运行"
fi

# 2. 启动 Docker 容器
echo "[2/5] 启动 Docker 容器..."

# MySQL
if docker ps -a --format '{{.Names}}' | grep -q "^opsmind-mysql$"; then
  docker start opsmind-mysql 2>/dev/null || echo "  MySQL 启动失败"
else
  echo "  创建 MySQL 容器..."
  docker run -d --name opsmind-mysql \
    -p 3306:3306 \
    -e MYSQL_ROOT_PASSWORD=opsmind_root \
    -e MYSQL_DATABASE=opsmind \
    -e MYSQL_USER=opsmind \
    -e MYSQL_PASSWORD=opsmind_pass \
    -v "$DATA_DIR/mysql:/var/lib/mysql" \
    mysql:8.0
fi

# Redis
if docker ps -a --format '{{.Names}}' | grep -q "^opsmind-redis$"; then
  docker start opsmind-redis 2>/dev/null || echo "  Redis 启动失败"
else
  echo "  创建 Redis 容器..."
  docker run -d --name opsmind-redis \
    -p 6379:6379 \
    -v "$DATA_DIR/redis:/data" \
    redis:7-alpine
fi

# Milvus
if docker ps -a --format '{{.Names}}' | grep -q "^milvus$"; then
  docker start milvus 2>/dev/null || echo "  Milvus 启动失败"
else
  echo "  创建 Milvus 容器..."
  docker run -d --name milvus \
    -p 19530:19530 \
    -p 9091:9091 \
    milvusdb/milvus:v2.5.0
fi

# 等待 Milvus 启动
echo "[3/5] 等待 Milvus 就绪（约 30 秒）..."
sleep 30

# 3. 检查服务状态
echo "[4/5] 检查服务状态..."
echo "  MySQL:   $(docker ps --filter name=opsmind-mysql --format '{{.Status}}')"
echo "  Redis:   $(docker ps --filter name=opsmind-redis --format '{{.Status}}')"
echo "  Milvus:  $(docker ps --filter name=milvus --format '{{.Status}}')"

# 4. 启动应用
echo "[5/5] 启动应用..."
cd "$SCRIPT_DIR"
if [ ! -f "target/opsmind-agent-1.0.0.jar" ]; then
  echo "  JAR 不存在，执行编译..."
  ./mvnw package -DskipTests -q
fi

# 杀掉旧进程
pkill -f "opsmind-agent-1.0.0.jar" 2>/dev/null || true
sleep 1

# 启动应用
nohup java -jar target/opsmind-agent-1.0.0.jar > /tmp/opsmind-agent.log 2>&1 &
APP_PID=$!
echo "  应用 PID: $APP_PID"

# 等待应用启动
echo "  等待应用启动..."
for i in {1..30}; do
  if curl -s http://localhost:8080/api/health > /dev/null 2>&1; then
    echo ""
    echo "=== 启动完成 ==="
    echo "  应用: http://localhost:8080"
    echo "  健康检查: curl http://localhost:8080/api/health"
    echo "  日志: tail -f /tmp/opsmind-agent.log"
    exit 0
  fi
  sleep 1
  [ $((i % 5)) -eq 0 ] && echo "  等待中... ($i 秒)"
done

echo ""
echo "!!! 应用启动可能失败，查看日志: tail -f /tmp/opsmind-agent.log"
