# OpsMind — AI 智能运维故障诊断 Agent

## 一、项目概述

**定位：** 对话式智能运维故障诊断助手。用户用自然语言描述告警/故障现象，Agent 通过 ReAct 推理 + Tool Calling + RAG + Multi-Agent 协作，完成根因诊断并给出处理建议。

**核心技术栈（面试必问全覆盖）：**
- Java 17 + Spring Boot 3.x
- LangChain4j（ReAct + Tool Calling + Memory）
- Milvus 向量数据库（RAG 知识库）
- MCP（Model Context Protocol）工具接口标准化
- MySQL + Redis

**项目优势：**
- 你有华为云 AI 故障定界系统实战经验，面试能讲真实细节
- 技术栈与简历优化方向完全对应（RAG / ReAct / MCP / Multi-Agent）
- 场景真实：运维诊断天然需要知识库 + 多步推理 + 多系统集成

---

## 二、系统架构

```
┌─────────────────────────────────────────────────────────┐
│                     用户交互层                           │
│            (对话界面 / API / 钉钉机器人)                   │
└─────────────────────┬───────────────────────────────────┘
                      │ 用户输入: "Pod xxx CPU 告警了"
                      ▼
┌─────────────────────────────────────────────────────────┐
│                   Agent 调度层                           │
│  ┌──────────────────────────────────────────────────┐   │
│  │   ReAct Loop（思考 → 行动 → 观察 → 决策）          │   │
│  │   - Thought: 分析当前状态，决定下一步              │   │
│  │   - Action: 调用某个 Tool                         │   │
│  │   - Observation: 获取 Tool 返回结果                │   │
│  │   - Decision: 结果是否满意，否则继续循环            │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────┬───────────────────────────────────┘
                      │
      ┌───────────────┼───────────────┬───────────────┐
      ▼               ▼               ▼               ▼
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ 指标查询  │  │ 拓扑查询  │  │ 变更查询  │  │ 知识库   │
│  Tool    │  │  Tool    │  │  Tool    │  │  Tool    │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │
     └─────────────┴─────────────┼─────────────┘
                                 ▼
              MCP Tool Server（统一工具接口层）
     ┌──────────┐  ┌──────────┐  ┌──────────┐
     │ Prometheus│  │ CMDB    │  │ 告警服务  │
     │   API    │  │   API   │  │   API   │
     └──────────┘  └──────────┘  └──────────┘
```

---

## 三、Multi-Agent 协作设计

```
                     ┌──────────────────┐
                     │   调度 Agent      │
                     │ (Supervisor)      │
                     │ 接收用户输入        │
                     │ 分配子任务          │
                     │ 汇总诊断结论        │
                     └────────┬─────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
   ┌────────────┐      ┌────────────┐      ┌────────────┐
   │ 指标 Agent │      │ 拓扑 Agent │      │ 知识库 Agent│
   │            │      │            │      │            │
   │ 负责查 CPU │      │ 负责查 Pod │      │ 负责查相似 │
   │ 内存 磁盘  │      │ VM 物理机  │      │ 历史案例   │
   │ 当前值     │      │ 层级关系   │      │ SOP 手册  │
   └────────────┘      └────────────┘      └────────────┘
```

**并行执行：** 三个子 Agent 同时工作，调度 Agent 等所有结果都回来后做综合推理。

---

## 四、Tool Calling 设计（MCP 协议）

每个 Tool = 一个 MCP 工具接口，Agent 按需调用。

| Tool 名称 | 功能 | 返回 |
|---|---|---|
| `query_metrics` | 查询 Pod/VM/物理机当前指标 | CPU%、内存%、延迟、错误率 |
| `query_topology` | 查询拓扑层级关系 | Pod → VM → 物理机链路 |
| `query_change_records` | 查询近期变更记录 | 版本发布、配置变更、扩缩容 |
| `query_alert_history` | 查询历史告警 | 相似告警的处理记录 |
| `search_knowledge_base` | RAG 检索知识库 | 相似故障案例 + 处理步骤 |
| `execute_action` | 执行运维动作 | 版本回退、扩缩容、切流 |

---

## 五、RAG 知识库设计

**入库内容：**
- 历史故障案例（告警特征 → 根因 → 处理步骤 → 结果）
- 运维 SOP 手册（标准处理流程）
- 变更操作记录（什么变更引起过什么故障）

**检索流程：**
1. 用户输入告警描述
2. 向量化告警文本，在 Milvus 里检索 Top5 相似案例
3. 把案例 + 处理结论一起作为 Context 注入 Prompt
4. LLM 参考历史案例给出诊断建议

---

## 六、ReAct 推理示例

```
用户输入: "Pod nginx-7d9f8c 告警 CPU 99%，帮我看看"

Agent 推理过程:
────────────────────────────────────────────────
Thought: Pod 告警 CPU 99%，首先要确认是瞬时突发还是持续性异常。
Action: query_metrics(pod="nginx-7d9f8c", metric="cpu", duration="10m")
Observation: CPU 从 30% 在 5 分钟内飙升至 99%，且持续未下降。
Decision: CPU 飙高可能原因：流量突增 / 资源不足 / 异常进程

Thought: 接下来查近期是否有变更操作，变更引起的问题优先排查。
Action: query_change_records(target="nginx-7d9f8c", hours=6)
Observation: 1 小时前有版本升级操作，镜像从 v1.2.0 → v1.3.0
Decision: 变更时间线吻合，很可能是新版本引起的资源问题

Thought: 查拓扑确认 Pod 所处层级和关联 VM/物理机状态。
Action: query_topology(pod="nginx-7d9f8c")
Observation: Pod 在 VM web-vm-04 上，所在物理机 phost-12 状态正常
Decision: 物理机正常，问题范围缩小到 Pod 级别

Thought: 搜索知识库，看 v1.3.0 版本是否有已知的资源问题。
Action: search_knowledge_base(query="nginx v1.3.0 CPU 资源")
Observation: 发现 2 条相似案例：v1.3.0 存在内存泄漏导致 CPU 飙高
Decision: 高度匹配历史案例

结论输出:
【诊断结果】Pod nginx-7d9f8c 因 v1.3.0 版本内存泄漏导致 CPU 飙高
【建议操作】回退至 v1.2.0 或联系开发确认修复
【置信度】高（变更时间线吻合 + 知识库相似案例）
```

---

## 七、Memory 模块设计

**短期记忆（对话上下文）：**
- Redis 存储当前对话 Session 的诊断历史
- 每次 Tool 调用结果存入 Session，LLM 可引用
- 同一 Session 内支持追问："刚才那个 Pod 现在怎么样了？"

**长期记忆（用户反馈闭环）：**
- MySQL 记录诊断结论 + 用户反馈（采纳/不采纳）
- 用户说"这个诊断不对" → 记录纠正结果
- 下次 RAG 检索时，优先推荐反馈好的案例

---

## 八、数据库设计

```sql
-- 诊断记录表
CREATE TABLE diagnosis_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(64),
    user_input TEXT,
    agent_thought TEXT,        -- ReAct 思考过程（JSON）
    tool_calls TEXT,            -- 调用的工具及参数（JSON）
    final_diagnosis TEXT,       -- 最终诊断结论
    user_feedback VARCHAR(20),  -- good/bad/null
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 知识库案例表（用于 RAG）
CREATE TABLE kb_cases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    alert_features TEXT,       -- 告警特征描述
    root_cause TEXT,           -- 根因
    solution_steps TEXT,       -- 处理步骤
    result VARCHAR(20),        -- 成功/失败
    embedding_id BIGINT,       -- Milvus 向量 ID
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 工具调用日志
CREATE TABLE tool_call_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(64),
    tool_name VARCHAR(50),
    input_params TEXT,
    output_result TEXT,
    duration_ms INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## 九、开发步骤（分6个阶段）

### 阶段一：项目骨架（1-2天）
- Spring Boot 3.x 项目结构
- 引入 LangChain4j（对接 MiniMax/M2.7）
- 对接 Redis（Session 管理）
- 对接 MySQL（数据持久化）

### 阶段二：Tool 接口层（2-3天）
- 实现 MCP Tool 接口规范（定义 ToolSpec）
- 实现 6 个核心 Tool：query_metrics / query_topology / query_change_records / search_knowledge_base 等
- Tool 返回结果标准化（JSON 格式）
- Mock 数据先跑通，后续接真实接口

### 阶段三：ReAct 引擎（3-4天）
- 实现 ReAct Loop（Thought → Action → Observation → Decision）
- Prompt 模板设计（让 LLM 按格式输出 Thought/Action）
- 循环终止条件（达到最大步数 / 决策置信度高）
- Tool 调用结果注入 ReAct 上下文

### 阶段四：RAG 知识库（2-3天）
- Milvus 部署（Docker 一行命令）
- 向量化：Text2Vec 或 MiniMax Embedding
- 知识入库：历史故障案例 SOP
- 检索验证：输入告警 → Milvus 查询 → 结果质量评估

### 阶段五：Multi-Agent 协作（2-3天）
- Supervisor Agent（调度）
- 指标 Agent / 拓扑 Agent / 知识库 Agent
- 并行执行框架（CompletableFuture）
- 结果汇总与冲突处理

### 阶段六：对话界面 + 优化（2-3天）
- Web 界面（简洁的对话页面）
- 对话历史（Redis Session）
- 用户反馈闭环（采纳/不采纳）
- 诊断记录展示

---

## 十、面试时怎么讲

**项目开场：**
> "这是一个对话式智能运维诊断 Agent，用户用自然语言描述告警，Agent 自动完成多步推理、多系统查询，最终输出带置信度的诊断结论。"

**技术深度问题准备：**

| 问题 | 回答要点 |
|------|---------|
| ReAct 和 CoT 区别？ | CoT 只思考不行动，ReAct 是思考+行动+观察的循环，更适合需要查数据的场景 |
| Tool Calling 怎么做的？ | 定义统一 ToolSpec，每种 Tool 有 schema 描述，输入输出标准化，LLM 按格式输出工具名和参数 |
| RAG 怎么保证召回质量？ | 分块策略（按段落 vs 按语义切片）、向量化模型选择、混合检索（向量+关键词）、重排序 |
| Multi-Agent 怎么协调？ | Supervisor 做调度，三个子 Agent 并行执行，用 Future 汇总结果，有超时和熔断机制 |
| MCP 解决什么问题？ | 统一工具接口，Agent 不需要关心工具实现细节，新增工具只需要注册 schema |
| 记忆怎么管理的？ | Redis 做短期会话记忆，MySQL 做长期案例记忆，反馈闭环持续优化 |

---

## 十一、技术选型说明

| 组件 | 选型 | 原因 |
|------|------|------|
| LLM | MiniMax M2.7 / M2.1 | 你已有 API Key，成本低，效果够用 |
| Java Agent 框架 | LangChain4j | Java 生态唯一完整 Agent 框架，支持 ReAct/Tool Calling/Memory |
| 向量数据库 | Milvus | 开源成熟，支持千万级向量，部署简单 |
| 工具接口协议 | MCP | 2024年主流标准，Anthropic 提出，生态正在扩大 |
| 对话记忆 | Redis | Session 级存储，支持过期，自动清理 |

---

## 十二、参考资源

- LangChain4j 文档：https://docs.langchain4j.dev/
- MCP 协议规范：https://modelcontextprotocol.io/
- Milvus 快速开始：https://milvus.io/docs/quickstart.md
- 项目 GitHub 仓库建议：`https://github.com/wwwscy/aiops-agent`
