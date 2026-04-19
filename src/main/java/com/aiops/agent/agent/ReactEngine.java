package com.aiops.agent.agent;

import com.aiops.agent.agent.memory.SessionMemory;
import com.aiops.agent.agent.model.ReActStep;
import com.aiops.agent.agent.model.ToolCallResult;
import com.aiops.agent.tools.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct（Reasoning + Acting）推理引擎
 *
 * 核心循环：Thought → Action → Observation → Decision → (循环或输出)
 *
 * Prompt 简化版，强制 LLM 输出 STOP 关键字停止推理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactEngine {

    private final SessionMemory sessionMemory;
    private final List<Tool> tools;

    @Value("${react.max-iterations:8}")
    private int maxIterations;

    private final ObjectMapper mapper = new ObjectMapper();

    // 从 LLM 输出中提取 Action
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(\\w+)\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "Decision:\\s*(\\S+)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 执行 ReAct 推理
     * @param userInput 用户输入（告警描述）
     * @param sessionId 会话 ID
     * @return 最终诊断结论
     */
    public String run(String userInput, String sessionId) {
        List<ReActStep> history = new ArrayList<>();
        String context = sessionMemory.getContext(sessionId);
        String ragContext = ""; // 从 DiagnosisService 传入

        log.info("[ReAct] 开始推理 sessionId={}, input={}", sessionId, userInput);

        for (int i = 0; i < maxIterations; i++) {
            log.info("[ReAct] 第 {} 轮迭代", i + 1);

            // 构建简化版 Prompt
            String prompt = buildPrompt(userInput, context, history, ragContext);

            // 调用 LLM
            String llmOutput;
            try {
                llmOutput = callLLM(prompt).trim();
                log.info("[ReAct] LLM 输出:\n{}", llmOutput);
            } catch (Exception e) {
                log.error("[ReAct] LLM 调用失败: {}", e.getMessage());
                return "LLM 调用失败: " + e.getMessage();
            }

            // 解析输出
            ReActStep step = parseLLMOutput(llmOutput, history.size());
            history.add(step);

            // 强制停止：检测 STOP / OUTPUT / 结论 等关键词，或工具调用失败
            String decision = step.getDecision() != null ? step.getDecision().toUpperCase() : "";
            boolean toolFailed = step.getObservation() != null && step.getObservation().contains("错误");
            boolean shouldStop = decision.contains("STOP")
                    || decision.contains("OUTPUT")
                    || llmOutput.contains("STOP")
                    || llmOutput.contains("【诊断结论】")
                    || llmOutput.contains("诊断结论")
                    || (toolFailed && llmOutput.length() > 50);

            if (shouldStop || (step.getThought() != null && step.getThought().length() > 10 && step.getAction() == null)) {
                String conclusion = extractConclusion(llmOutput);
                log.info("[ReAct] 推理完成，得出结论: {}", conclusion);
                sessionMemory.saveDiagnosis(sessionId, conclusion, history);
                return conclusion;
            }

            // 执行 Tool
            if (step.getAction() != null && !step.getAction().isEmpty()) {
                ToolCallResult toolResult = executeTool(step.getAction(), step.getToolArgs());
                step.setObservation(toolResult.getResult());
                step.setSuccess(toolResult.isSuccess());

                String observation = String.format(
                        "[Step %d] Action: %s(%s) => %s",
                        history.size(), step.getAction(), step.getToolArgs(),
                        toolResult.isSuccess() ? "成功" : "失败: " + toolResult.getError()
                );
                context += "\n" + observation;
                sessionMemory.append(sessionId, observation);
            }
        }

        log.warn("[ReAct] 达到最大迭代次数 {}，强制结束", maxIterations);
        return "推理超时（超过 " + maxIterations + " 轮），请人工介入排查";
    }

    /**
     * 调用 MiniMax LLM
     * 这里直接用 HTTP 调用，不走 LangChain4j（减少依赖）
     */
    private String callLLM(String prompt) throws Exception {
        String apiKey = "sk-cp-FJWOJ-fAmPxOrrXQyHLhZvMIDfBR5RIn8n3iHfqB00BpowDzVUNU9ww2ehmHh7on22SiVnYozEnhPIUJpLAXi519YrhD3InseElcc2df-lYIXW1HJS0Yr5I";
        String baseUrl = "https://api.minimax.chat/v1";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", "MiniMax-M2.1");
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", 1500);

        ObjectNode systemMsg = mapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一个运维故障诊断专家。用户描述告警后，你按以下格式输出：\n" +
                "Thought: <分析当前情况>\n" +
                "Action: <工具名>(<参数JSON>)  # 如果需要查询数据\n" +
                "Decision: STOP  # 得出结论后必须写 STOP\n\n" +
                "如果已经收集到足够信息判断根因，立即输出【诊断结论】并写 STOP，不要继续调用工具。");

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        requestBody.putArray("messages").add(systemMsg).add(userMsg);

        java.net.URL url = new java.net.URL(baseUrl + "/chat/completions");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(mapper.writeValueAsBytes(requestBody));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new RuntimeException("LLM API 返回错误: " + code + " - " + conn.getResponseMessage());
        }

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            // 解析 OpenAI 格式响应
            var node = mapper.readTree(response.toString());
            return node.at("/choices/0/message/content").asText();
        }
    }

    /**
     * 构建 Prompt
     */
    private String buildPrompt(String userInput, String context, List<ReActStep> history, String ragContext) {
        StringBuilder sb = new StringBuilder();

        sb.append("【当前告警】\n").append(userInput).append("\n\n");

        if (!ragContext.isEmpty()) {
            sb.append("【相似历史案例参考】\n").append(ragContext).append("\n\n");
        }

        if (!context.isEmpty()) {
            sb.append("【已查询结果】\n").append(context).append("\n\n");
        }

        // 历史推理记录（限制最近2轮，避免过长）
        if (!history.isEmpty()) {
            sb.append("【推理历史】\n");
            int start = Math.max(0, history.size() - 2);
            for (int i = start; i < history.size(); i++) {
                ReActStep s = history.get(i);
                sb.append("- ").append(s.getThought() != null ? s.getThought() : "").append("\n");
                if (s.getAction() != null) {
                    sb.append("  调用: ").append(s.getAction()).append(" => ").append(s.getObservation() != null ? "成功" : "失败").append("\n");
                }
            }
            sb.append("\n");
        }

        // 明确指出可用的工具
        sb.append("【可用工具】（必须严格使用以下工具名，禁止自创工具）：\n");
        sb.append("- query_metrics({\"target\":\"pod|vm|physical\",\"target_name\":\"名称\",\"metric\":\"cpu|memory|latency\"})\n");
        sb.append("- query_topology({\"target\":\"pod|vm|physical\",\"target_name\":\"名称\"})\n");
        sb.append("- query_change_records({\"target_name\":\"名称\",\"hours\":6})\n");
        sb.append("- search_knowledge_base({\"query\":\"告警描述\",\"top_k\":3})\n");
        sb.append("- query_pod_logs({\"pod_name\":\"Pod名称\",\"namespace\":\"命名空间\",\"lines\":100,\"keyword\":\"可选过滤关键词\"})\n");
        sb.append("- query_events({\"namespace\":\"命名空间\",\"pod_name\":\"Pod名称(可选)\",\"limit\":50})\n");
        sb.append("- query_metrics_history({\"target\":\"pod|vm\",\"target_name\":\"名称\",\"metric\":\"cpu|memory|latency\",\"duration_minutes\":30})\n");
        sb.append("\n");
        sb.append("【重要】：如果上述工具返回失败或错误，或者历史案例已给出充分诊断，直接输出【诊断结论】并写 STOP，不要再调用新工具。\n");
        sb.append("根据以上信息输出诊断结论：");
        return sb.toString();
    }

    /**
     * 解析 LLM 输出
     */
    private ReActStep parseLLMOutput(String output, int stepNum) {
        ReActStep step = new ReActStep();
        step.setStepNum(stepNum + 1);

        Matcher actionMatcher = ACTION_PATTERN.matcher(output);
        if (actionMatcher.find()) {
            step.setAction(actionMatcher.group(1).trim());
            step.setToolArgs(actionMatcher.group(2).trim());
        }

        Matcher decisionMatcher = DECISION_PATTERN.matcher(output);
        if (decisionMatcher.find()) {
            step.setDecision(decisionMatcher.group(1).trim());
        }

        // Thought 是 Thought: 之后、Action 或 Decision 之前的所有内容
        int thoughtStart = -1;
        int thoughtEnd = -1;
        for (String line : output.split("\n")) {
            if ((line.startsWith("Thought:") || line.startsWith("thought:")) && thoughtStart == -1) {
                thoughtStart = output.indexOf(line) + line.indexOf(":") + 1;
            }
            if ((line.startsWith("Action:") || line.startsWith("action:") || line.startsWith("Decision:") || line.startsWith("decision:")) && thoughtStart != -1 && thoughtEnd == -1) {
                thoughtEnd = output.indexOf(line);
                break;
            }
        }
        if (thoughtStart != -1) {
            String thought = (thoughtEnd == -1 ? output.substring(thoughtStart + 1) : output.substring(thoughtStart + 1, thoughtEnd)).trim();
            step.setThought(thought);
        }

        return step;
    }

    /**
     * 执行 Tool
     */
    private ToolCallResult executeTool(String toolName, String args) {
        long start = System.currentTimeMillis();
        try {
            Optional<Tool> found = tools.stream()
                    .filter(t -> t.name().equalsIgnoreCase(toolName.trim()))
                    .findFirst();

            if (found.isEmpty()) {
                return ToolCallResult.fail("未找到工具: " + toolName);
            }

            String result = found.get().execute(args != null ? args.trim() : "{}");
            long duration = System.currentTimeMillis() - start;
            log.info("[Tool] {} 执行成功，耗时 {}ms", toolName, duration);
            return ToolCallResult.success(result);
        } catch (Exception e) {
            log.error("[Tool] {} 执行异常: {}", toolName, e.getMessage(), e);
            return ToolCallResult.fail(e.getMessage());
        }
    }

    /**
     * 提取最终结论（找【诊断结论】或最后一段有内容的非格式文本）
     */
    private String extractConclusion(String output) {
        // 优先找【诊断结论】【根因】【处理建议】等标记段落
        String[] markers = {"【诊断结论】", "【根因】", "诊断结论", "## 诊断", "**诊断结果**"};
        for (String marker : markers) {
            int idx = output.indexOf(marker);
            if (idx != -1) {
                String after = output.substring(idx + marker.length()).trim();
                // 找到下一个同级标题就截断
                String nextMarkers = "【";
                int nextIdx = after.indexOf(nextMarkers);
                if (nextIdx > 10) {
                    after = after.substring(0, nextIdx).trim();
                }
                if (after.length() > 10) return after;
            }
        }

        // 降级：返回 output 的后半段（通常包含结论）
        String[] lines = output.split("\n");
        List<String> meaningfulLines = new ArrayList<>();
        for (int i = lines.length / 2; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("Thought") && !line.startsWith("Action") && !line.startsWith("Decision")
                    && !line.startsWith("observation") && !line.isEmpty() && line.length() > 5) {
                meaningfulLines.add(line);
            }
        }
        if (!meaningfulLines.isEmpty()) {
            return String.join("\n", meaningfulLines);
        }
        return output.substring(Math.max(0, output.length() - 300)).trim();
    }
}
