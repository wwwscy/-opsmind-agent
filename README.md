# OpsMind — AI 智能运维故障诊断 Agent

对话式智能运维诊断助手，用户用自然语言描述告警现象，Agent 自动完成多步推理、多系统查询，最终输出带置信度的诊断结论。

## 技术栈

| 技术 | 用途 |
|------|------|
| Java 17 + Spring Boot 3 | 后端框架 |
| LangChain4j 0.35.0 | ReAct 推理 + Tool Calling + Memory |
| H2 内嵌数据库 | 开发阶段 MySQL 替代 |
| MiniMax M2.7 / M2.1 | LLM 推理引擎 |
| ConcurrentHashMap | 开发阶段 Redis 替代 |

**生产环境替换路径：** H2 → MySQL，ConcurrentHashMap → Redis，内存向量库 → Milvus

## 核心架构

```
用户输入 → ReAct Engine → Tool Calling → [指标/拓扑/变更/知识库]
                ↓
          多 Agent 并行查询
                ↓
          诊断结论 + RAG 参考
```

## 技术亮点（面试可展开）

- **ReAct 推理链**：Thought → Action → Observation → Decision，可解释的诊断过程
- **Tool Calling**：统一工具接口，Agent 按需调用，不关心实现细节
- **RAG 知识库**：历史故障案例向量化存储，新故障 RAG 检索相似案例辅助推理
- **Multi-Agent 协作**：指标/拓扑/知识库三个子 Agent 并行执行，Supervisor 汇总结果
- **MCP 协议**：版本回退、扩容、切流等运维动作标准化为 MCP 工具接口
- **Memory 闭环**：对话历史存 ConcurrentHashMap，用户反馈记入长期记忆，持续优化 RAG 质量

## 快速启动

### 1. 配置 MiniMax API Key

```bash
cp .env.example .env
# 编辑 .env，填入真实 API Key
```

API Key 获取：https://platform.minimaxi.com/

### 2. 编译运行（无需 MySQL/Redis/Milvus）

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./mvnw spring-boot:run
```

### 3. 测试诊断接口

```bash
curl -X POST http://localhost:8080/api/diagnosis \
  -H "Content-Type: application/json" \
  -d '{"input": "Pod nginx-7d9f8c 告警 CPU 99%，帮我看看"}'
```

### 4. 查看推理过程

```bash
curl http://localhost:8080/api/diagnosis/{sessionId}
```

### 5. 提交反馈

```bash
curl -X POST http://localhost:8080/api/diagnosis/{sessionId}/feedback \
  -H "Content-Type: application/json" \
  -d '{"feedback": "good"}'
```

## 目录结构

```
src/main/java/com/aiops/agent/
├── AgentApplication.java              # 启动类
├── config/
│   └── LangChain4jConfig.java        # MiniMax LLM 配置
├── controller/
│   └── AiDiagnosisController.java      # HTTP 对话接口
├── service/
│   ├── DiagnosisService.java          # 诊断服务（入口）
│   ├── DiagnosisRecordRepository.java
│   └── entity/
│       └── DiagnosisRecord.java       # 诊断记录实体
├── agent/
│   ├── ReactEngine.java               # ReAct 推理引擎（核心）
│   ├── model/
│   │   ├── ReActStep.java            # 推理步骤
│   │   └── ToolCallResult.java       # 工具执行结果
│   ├── memory/
│   │   └── SessionMemory.java        # 内存会话记忆
│   └── MultiAgentSupervisor.java     # Multi-Agent 调度器
├── tools/
│   ├── Tool.java                     # Tool 接口
│   ├── MetricsQueryTool.java         # 指标查询
│   ├── TopologyQueryTool.java        # 拓扑查询
│   ├── ChangeQueryTool.java          # 变更记录查询
│   └── KnowledgeBaseTool.java        # RAG 知识库检索
├── mcp/
│   └── ToolSpec.java                 # MCP 工具规范
└── rag/
    └── KnowledgeBaseService.java      # RAG 服务（内存向量库）
```

## 接入真实华为云 API

目前各 Tool 为 Mock 实现，接入真实环境只需替换 `tools/` 下的 HTTP 调用：

| Tool | 真实 API |
|------|---------|
| MetricsQueryTool | 华为云 APM / CES 指标接口 |
| TopologyQueryTool | 华为云 VPC / CMDB 接口 |
| ChangeQueryTool | 华为云变更管理平台接口 |

## 面试话术

**Q: 讲讲你这个项目的架构？**

> 用户输入告警描述后，ReAct Engine 先做 RAG 检索相似历史案例，注入上下文，然后进入推理循环：先 Thought 分析情况，再 Action 调用工具（查指标/查拓扑/查变更），系统把工具结果填入 Observation，Agent 判断是否继续还是输出结论。每个 Tool 都遵循 MCP 协议规范，Agent 不需要关心工具实现细节。

**Q: ReAct 和普通 Prompt 有什么区别？**

> 普通 Prompt 是单次生成，ReAct 是思考-行动-观察的循环。Agent 每一步都能调用真实工具获取最新数据，而不是靠训练知识猜答案。诊断场景必须用 ReAct，因为指标、拓扑、变更这些都是实时数据，必须查了才能判断。

**Q: RAG 怎么保证召回质量？**

> 知识库按故障类型分块（CPU 类/内存类/网络类），向量化时用故障特征做检索词而不是直接用告警原文。另外做了混合检索：向量相似度 + BM25 关键词双重召回，再做重排序。召回质量差的时候主要调分块策略和检索词。
