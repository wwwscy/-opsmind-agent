package com.aiops.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Slf4j
@Service
@Profile("!milvus")
public class InMemoryKnowledgeBaseService implements KnowledgeBaseService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<KnowledgeCase> cases = new ArrayList<>();

    @PostConstruct
    public void init() {
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
        log.info("[InMemoryKB] 初始化完成，{} 条案例", cases.size());
    }

    @Override
    public String searchSimilarCases(String query, int topK) {
        log.info("[InMemoryKB] 检索: query='{}', topK={}", query, topK);
        String lower = query.toLowerCase();
        String[] keywords = {"cpu","内存","oom","延迟","pod","版本","升级","告警","gc","jvm","连接","timeout","网络"};

        List<KnowledgeCase> matched = new ArrayList<>();
        for (KnowledgeCase c : cases) {
            int score = 0;
            String lf = c.alertFeatures.toLowerCase();
            for (String kw : keywords) {
                if (lower.contains(kw) && lf.contains(kw)) score += 2;
                else if (lf.contains(kw)) score++;
            }
            c.score = score;
            if (score > 0) matched.add(c);
        }
        matched.sort((a, b) -> b.score - a.score);
        if (matched.size() > topK) matched = matched.subList(0, topK);

        ObjectNode result = mapper.createObjectNode();
        result.put("query", query);
        result.put("top_k", topK);
        result.put("mode", "in_memory");
        result.put("source", "in_memory_kb");

        ArrayNode arr = mapper.createArrayNode();
        for (KnowledgeCase c : matched) {
            ObjectNode node = mapper.createObjectNode();
            node.put("case_id", c.id);
            node.put("similarity", String.format("%.2f", c.score * 0.25));
            node.put("alert_features", c.alertFeatures);
            node.put("root_cause", c.rootCause);
            node.put("solution_steps", String.join("\n", c.solutionSteps));
            node.put("result", c.result);
            arr.add(node);
        }
        result.set("cases", arr);
        result.put("total", arr.size());
        return result.toString();
    }

    @Override
    public void addCase(String alertFeatures, String rootCause, List<String> solutionSteps, String result) {
        KnowledgeCase c = new KnowledgeCase();
        c.id = "KB-" + System.currentTimeMillis();
        c.alertFeatures = alertFeatures;
        c.rootCause = rootCause;
        c.solutionSteps = solutionSteps;
        c.result = result;
        c.score = 0;
        c.createdAt = System.currentTimeMillis();
        cases.add(c);
        log.info("[InMemoryKB] 新增案例: id={}", c.id);
    }

    @Override
    public List<Map<String, Object>> listCases() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeCase c : cases) {
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
