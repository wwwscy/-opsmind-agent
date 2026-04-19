# OpsMind — AI 智能运维故障诊断 Agent

对话式智能运维诊断助手，用户用自然语言描述告警现象，Agent 自动完成多步推理、多系统查询，最终输出带置信度的诊断结论。

## 技术栈

| 技术 | 用途 |
|------|------|
| Java 17 + Spring Boot 3 | 后端框架 |
| LangChain4j 0.35.0 | ReAct 推理 + Tool Calling + Memory |
| **MySQL 8** | 持久化存储（告警、诊断记录） |
| **Milvus 2.5** | 向量数据库（RAG 知识库） |
| **Redis 7** | 会话记忆缓存 |
| MiniMax M2.1 | LLM 推理引擎 |

## 核心架构

```
用户输入 → ReAct Engine → Tool Calling → [指标/拓扑/变更/知识库]
                ↓
          多 Agent 并行查询
                ↓
          诊断结论 + RAG 参考
```

## 机器依赖

### 必须安装

| 依赖 | 版本 | 用途 | 安装方式 |
|------|------|------|----------|
| **Java** | 17+ | 运行 Spring Boot | `brew install openjdk@17` |
| **Maven** | 3.8+ | 编译项目 | `brew install maven` |
| **MySQL** | 8.0 | 告警/诊断数据持久化 | Docker 或本地安装 |
| **Milvus** | 2.5+ | 向量数据库 | Docker 部署 |
| **Redis** | 7+ | 会话缓存 | Docker 或本地安装 |

### 可选（开发用）

| 依赖 | 用途 | 说明 |
|------|------|------|
| Docker + Colima | 运行环境 | Mac 需要 Colima 运行 Docker |
| H2 Console | 数据库调试 | 开发阶段可用，路径 `/h2-console` |

### 网络要求

- 能够访问 **MiniMax API** (`https://api.minimax.chat`)
- 能够访问 **Docker Hub**（国内需要配置代理）

## 快速启动

### 方式一：Docker Compose 一键部署（推荐）

```bash
# 克隆项目
git clone https://github.com/wwwscy/-opsmind-agent.git
cd opsmind-agent

# 启动所有依赖服务
docker-compose up -d

# 编译并启动应用
./mvnw package -DskipTests
java -jar target/opsmind-agent-1.0.0.jar
```

### 方式二：手动分步部署

#### 1. 启动 MySQL

```bash
docker run -d --name opsmind-mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=opsmind_root \
  -e MYSQL_DATABASE=opsmind \
  -e MYSQL_USER=opsmind \
  -e MYSQL_PASSWORD=opsmind_pass \
  -v /your/data/path/mysql:/var/lib/mysql \
  mysql:8.0
```

#### 2. 启动 Redis

```bash
docker run -d --name opsmind-redis \
  -p 6379:6379 \
  -v /your/data/path/redis:/data \
  redis:7-alpine
```

#### 3. 启动 Milvus

```bash
docker run -d --name milvus \
  -p 19530:19530 \
  -p 9091:9091 \
  milvusdb/milvus:v2.5.0
```

#### 4. 配置 application.yml

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/opsmind
    username: opsmind
    password: opsmind_pass

milvus:
  host: localhost
  port: 19530

redis:
  host: localhost
  port: 6379
```

#### 5. 编译运行

```bash
./mvnw package -DskipTests
java -jar target/opsmind-agent-1.0.0.jar
```

### 配置 MiniMax API Key

编辑 `src/main/resources/application.yml`，修改：

```yaml
minimax:
  api-key: 你的API Key
```

API Key 获取：https://platform.minimax.com/

## 测试接口

### 发起诊断

```bash
curl -X POST http://localhost:8080/api/diagnosis \
  -H "Content-Type: application/json" \
  -d '{"input": "Pod nginx-7d9f8c 告警 CPU 99%，帮我看看"}'
```

### 查询诊断进度

```bash
curl http://localhost:8080/api/diagnosis/{sessionId}
```

### 告警列表

```bash
curl http://localhost:8080/api/alerts
```

### 健康检查

```bash
curl http://localhost:8080/api/health
```

## 目录结构

```
src/main/java/com/aiops/agent/
├── AgentApplication.java              # 启动类
├── config/
│   └── LangChain4jConfig.java        # MiniMax LLM 配置
├── controller/
│   ├── AiDiagnosisController.java    # 诊断对话接口
│   ├── AlertController.java          # 告警管理接口
│   └── KnowledgeBaseController.java  # 知识库管理接口
├── service/
│   └── DiagnosisService.java         # 诊断服务（入口）
├── agent/
│   ├── ReactEngine.java             # ReAct 推理引擎（核心）
│   ├── model/
│   │   └── ReActStep.java           # 推理步骤
│   ├── memory/
│   │   └── SessionMemory.java       # 会话记忆（Redis）
│   └── MultiAgentSupervisor.java    # Multi-Agent 调度器
├── tools/
│   ├── MetricsQueryTool.java         # 指标查询
│   ├── TopologyQueryTool.java        # 拓扑查询
│   ├── ChangeQueryTool.java         # 变更记录查询
│   └── KnowledgeBaseTool.java       # RAG 知识库检索
├── monitor/
│   ├── AnomalyDetector.java         # 异常检测
│   ├── MetricsCollector.java         # 指标采集
│   ├── AlertController.java         # 告警控制器
│   └── FeishuNotificationService.java # 飞书通知
├── mcp/
│   └── ToolSpec.java                # MCP 工具规范
└── rag/
    └── MilvusKnowledgeBaseService.java # Milvus RAG 服务
```

## 接入真实华为云 API

目前各 Tool 为 Mock 实现，接入真实环境只需替换 `tools/` 下的 HTTP 调用：

| Tool | 真实 API |
|------|---------|
| MetricsQueryTool | 华为云 APM / CES 指标接口 |
| TopologyQueryTool | 华为云 VPC / CMDB 接口 |
| ChangeQueryTool | 华为云变更管理平台接口 |

## 常见问题

### Q: Docker 镜像拉取失败？

国内网络需要配置代理，或使用国内镜像站：

```bash
# 配置 Docker 代理（如果宿主机有代理）
docker run -e HTTP_PROXY=http://host.docker.internal:7890 ...
```

### Q: MySQL 连接失败？

检查 MySQL 是否启动，端口 3306 是否可访问：

```bash
docker ps | grep mysql
mysql -h localhost -u opsmind -p opsmind_pass
```

### Q: Milvus 连接失败？

Milvus 启动后需要等待约 30 秒才能接受连接：

```bash
docker logs milvus  # 查看启动状态
```

## 面试话术

**Q: 讲讲你这个项目的架构？**

> 用户输入告警描述后，ReAct Engine 先做 RAG 检索相似历史案例，注入上下文，然后进入推理循环：先 Thought 分析情况，再 Action 调用工具（查指标/查拓扑/查变更），系统把工具结果填入 Observation，Agent 判断是否继续还是输出结论。每个 Tool 都遵循 MCP 协议规范，Agent 不需要关心工具实现细节。

**Q: ReAct 和普通 Prompt 有什么区别？**

> 普通 Prompt 是单次生成，ReAct 是思考-行动-观察的循环。Agent 每一步都能调用真实工具获取最新数据，而不是靠训练知识猜答案。诊断场景必须用 ReAct，因为指标、拓扑、变更这些都是实时数据，必须查了才能判断。

**Q: RAG 怎么保证召回质量？**

> 知识库按故障类型分块（CPU 类/内存类/网络类），向量化时用故障特征做检索词而不是直接用告警原文。另外做了混合检索：向量相似度 + BM25 关键词双重召回，再做重排序。召回质量差的时候主要调分块策略和检索词。
