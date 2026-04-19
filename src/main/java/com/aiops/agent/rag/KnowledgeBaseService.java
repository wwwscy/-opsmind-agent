package com.aiops.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * RAG 知识库服务（开发阶段内存实现，生产换 Milvus）
 *
 * 开发阶段用内存 List 模拟向量检索，
 * 生产只需改 @Service 实现类，接口不变
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    @Value("${milvus.collection-name:opsmind_kb}")
    private String collectionName;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── 开发阶段：内存知识库 ──────────────────────────────────
    private final List<KnowledgeCase> inMemoryCases = new ArrayList<>();

    @PostConstruct
    public void init() {
        // 预置几条故障案例（开发调试用）
        addCase(
                "Pod CPU 从 30% 在 5 分钟内飙升至 99%，版本升级后出现",
                "v1.3.0 存在已知内存泄漏，导致 GC 频繁触发 CPU 飙高",
                Arrays.asList(
                        "1. 确认版本是否为 v1.3.0",
                        "2. 联系开发确认内存泄漏修复进度",
                        "3. 紧急回退至 v1.2.0"
                ),
                "成功"
        );
        addCase(
                "Pod 延迟突然上升，CPU 和内存正常",
                "上游服务重启导致连接池耗尽",
                Arrays.asList(
                        "1. 检查上游服务状态",
                        "2. 确认连接池配置",
                        "3. 等待上游恢复或手动重启连接池"
                ),
                "成功"
        );
        addCase(
                "Pod OOMKilled，内存持续接近 limit",
                "JVM 堆内存配置过小，流量高峰时触发 OOM",
                Arrays.asList(
                        "1. 查看 Pod events 中的 OOMKilled",
                        "2. 检查 JVM 堆内存参数（-Xmx）",
                        "3. 扩容内存 limit 或优化 JVM 配置"
                ),
                "成功"
        );
        log.info("[KnowledgeBase] 开发模式知识库初始化，共 {} 条案例", inMemoryCases.size());
    }

    /**
     * 检索相似案例
     */
    public Object searchSimilarCases(String query, int topK) throws Exception {
        log.info("[RAG] 检索: query='{}', topK={}", query, topK);

        ObjectNode result = mapper.createObjectNode();
        result.put("query", query);
        result.put("top_k", topK);
        result.put("mode", "in_memory");

        String lowerQuery = query.toLowerCase();
        List<KnowledgeCase> matched = new ArrayList<>();

        for (KnowledgeCase c : inMemoryCases) {
            int score = 0;
            String[] keywords = {"cpu", "内存", "oom", "延迟", "pod", "版本", "升级", "告警"};
            for (String kw : keywords) {
                if (lowerQuery.contains(kw) && c.alertFeatures.toLowerCase().contains(kw)) score++;
                if (c.alertFeatures.toLowerCase().contains(kw)) score++;
            }
            c.score = score;
            if (score > 0) matched.add(c);
        }

        matched.sort((a, b) -> b.score - a.score);
        matched = matched.subList(0, Math.min(topK, matched.size()));

        ArrayNode casesNode = mapper.createArrayNode();
        for (KnowledgeCase c : matched) {
            ObjectNode caseNode = mapper.createObjectNode();
            caseNode.put("case_id", c.id);
            caseNode.put("similarity", String.format("%.2f", c.score * 0.3));
            caseNode.put("alert_features", c.alertFeatures);
            caseNode.put("root_cause", c.rootCause);
            caseNode.put("solution_steps", String.join("\n", c.solutionSteps));
            caseNode.put("result", c.result);
            casesNode.add(caseNode);
        }

        result.set("cases", casesNode);
        result.put("source", "in_memory_kb");
        return result;
    }

    /**
     * 将新案例入库（诊断完成后调用）
     */
    public void addCase(String alertFeatures, String rootCause, List<String> solutionSteps, String result) {
        KnowledgeCase c = new KnowledgeCase();
        c.id = "KB-" + System.currentTimeMillis();
        c.alertFeatures = alertFeatures;
        c.rootCause = rootCause;
        c.solutionSteps = solutionSteps;
        c.result = result;
        c.score = 0;
        c.createdAt = System.currentTimeMillis();
        inMemoryCases.add(c);
        log.info("[KnowledgeBase] 新增案例: id={}, alertFeatures={}", c.id, alertFeatures);
    }

    /**
     * 对外暴露知识库案例列表（用于前端展示）
     */
    public List<Map<String, Object>> listCases() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeCase c : inMemoryCases) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id);
            m.put("alertFeatures", c.alertFeatures);
            m.put("rootCause", c.rootCause);
            m.put("solutionSteps", String.join("\n", c.solutionSteps));
            m.put("result", c.result);
            m.put("createdAt", new Date(c.createdAt).toString());
            result.add(m);
        }
        return result;
    }

    // ── 内部类 ──────────────────────────────────────────────
    private static class KnowledgeCase {
        String id;
        String alertFeatures;
        String rootCause;
        List<String> solutionSteps;
        String result;
        int score;
        long createdAt;
    }
}
