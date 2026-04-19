#!/bin/bash
set -e

# 从环境变量或默认值设置
export SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-milvus}
export SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-jdbc:mysql://mysql:3306/opsmind?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}
export SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-opsmind}
export SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD:-opsmind_pass}
export MILVUS_HOST=${MILVUS_HOST:-milvus}
export MILVUS_PORT=${MILVUS_PORT:-19530}

echo "=========================================="
echo "OpsMind Agent Starting..."
echo "Profile: $SPRING_PROFILES_ACTIVE"
echo "MySQL: $SPRING_DATASOURCE_URL"
echo "Milvus: $MILVUS_HOST:$MILVUS_PORT"
echo "=========================================="

exec java -jar app.jar "$@"
