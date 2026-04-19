package com.aiops.agent.agent;

import com.aiops.agent.agent.memory.SessionMemory;
import com.aiops.agent.tools.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Multi-Agent 调度器
 *
 * 场景：Pod/VM/物理机 三层定位交给三个子 Agent 并行查询
 *       再由 Supervisor 汇总结果给调度 Agent 做综合推理
 *
 * 工作流程：
 *   1. Supervisor 接收用户输入，分析需要哪些子 Agent 参与
 *   2. 并行触发所有子 Agent（各自调用自己的 Tool）
 *   3. 等待所有结果返回（或超时）
 *   4. 汇总结果，注入 ReAct 上下文中
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentSupervisor {

    private final SessionMemory sessionMemory;
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 并行执行多个子 Agent 任务
     *
     * @param tasks 子 Agent 任务列表
     * @param timeoutSeconds 超时时间
     * @return 各子 Agent 结果汇总
     */
    public Map<String, String> executeParallel(List<SubAgentTask> tasks, int timeoutSeconds) {
        log.info("[MultiAgent] 并行执行 {} 个子任务", tasks.size());

        Map<String, String> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (SubAgentTask task : tasks) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    String result = executeSubAgent(task);
                    results.put(task.getAgentName(), result);
                    log.info("[MultiAgent] {} 执行完成", task.getAgentName());
                } catch (Exception e) {
                    log.error("[MultiAgent] {} 执行异常: {}", task.getAgentName(), e.getMessage(), e);
                    results.put(task.getAgentName(), "{\"error\": \"" + e.getMessage() + "\"}");
                }
            }, executor);
            futures.add(future);
        }

        // 等待所有任务完成（或超时）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[MultiAgent] 子 Agent 执行超时，已返回已有结果");
        } catch (Exception e) {
            log.error("[MultiAgent] 等待子 Agent 异常: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * 执行单个子 Agent
     * 子 Agent 封装了自己的 Tool 调用逻辑
     */
    private String executeSubAgent(SubAgentTask task) throws Exception {
        // 这里是简化的并行执行：
        // 真实实现中，每个子 Agent 也有自己的 ReAct Loop 和 Tool 列表
        // 例如：MetricsAgent 调用 query_metrics，TopologyAgent 调用 query_topology

        // 构造子 Agent 的 Prompt
        String prompt = String.format(
                "你是 %s 子 Agent，负责 %s。\n" +
                "根据以下输入执行你的职责：\n%s\n" +
                "只返回查询结果，不要多余解释。",
                task.getAgentName(), task.getDescription(), task.getInput()
        );

        // 这里直接返回 Task 的 Mock 结果
        // 真实实现：调用各子 Agent 自己的 ReAct 引擎
        return mapper.writeValueAsString(Map.of(
                "agent", task.getAgentName(),
                "status", "completed",
                "data", task.getInput()
        ));
    }

    /**
     * 汇总子 Agent 结果，给 LLM 做最终推理
     */
    public String summarize(Map<String, String> subResults) {
        try {
            ObjectNode summary = mapper.createObjectNode();
            subResults.forEach(summary::put);
            return mapper.writeValueAsString(summary);
        } catch (Exception e) {
            return subResults.toString();
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SubAgentTask {
        private String agentName;    // 指标 Agent / 拓扑 Agent / 知识库 Agent
        private String description;    // 职责描述
        private String input;         // 输入参数
        private Tool targetTool;     // 要调用的工具
    }
}
