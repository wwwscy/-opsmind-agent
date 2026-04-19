package com.aiops.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Milvus 向量数据库知识库实现（生产用）
 *
 * 激活方式：spring.profiles.active=milvus
 *
 * Milvus 启动命令（Docker）：
 *   docker run -d --name milvus -p 19530:19530 -p 9091:9091 \\
 *     milvusdb/milvus:v3.1.0 /milvus/bin/milvus run standalone
 *
 * 或使用 Milvus Attu（管理界面）：
 *   docker run -d --name attu -p 3000:3000 \\
 *     zilliz/attu:v3.1.0 -e MILVUS_URL=http://localhost:19530
 */
@Slf4j
@Service
@Profile("milvus")
public class MilvusKnowledgeBaseService implements KnowledgeBaseService {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.collection-name:opsmind_kb}")
    private String collectionName;

    @Value("${milvus.dimension:384}")
    private int dimension;

    private WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        String baseUrl = "http://" + host + ":" + port;
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        log.info("[MilvusKB] Milvus REST ready: {}/v1/vector", baseUrl);
        ensureCollection();
    }

    private void ensureCollection() {
        try {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("collectionName", collectionName);
            schema.put("dimension", dimension);
            schema.put("metricType", "COSINE");
            schema.put("description", "OpsMind AI Ops Knowledge Base");

            client.post()
                    .uri("/v1/vector/collections/create")
                    .bodyValue(schema)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                     .block();
            log.info("[MilvusKB] Collection '{}' 就绪", collectionName);
        } catch (Exception e) {
            log.warn("[MilvusKB] ensureCollection: {} (可能已存在)", e.getMessage());
        }
    }

    @Override
    public String searchSimilarCases(String query, int topK) {
        log.info("[MilvusKB] 检索: query='{}', topK={}", query, topK);
        float[] queryVec = embedText(query);

        ObjectNode req = mapper.createObjectNode();
        req.put("collectionName", collectionName);
        req.put("topK", topK);
        ArrayNode vecArr = mapper.createArrayNode();
        for (float v : queryVec) vecArr.add(BigDecimal.valueOf(v));
        req.set("vector", vecArr);

        ArrayNode outFields = mapper.createArrayNode();
        outFields.add("id"); outFields.add("alert_features");
        outFields.add("root_cause"); outFields.add("solution_steps"); outFields.add("result");
        req.set("outputFields", outFields);

        try {
            String raw = client.post()
                    .uri("/v1/vector/search")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            ObjectNode result = mapper.createObjectNode();
            result.put("query", query);
            result.put("top_k", topK);
            result.put("mode", "milvus");
            result.put("source", "milvus_vector_db");

            ArrayNode cases = mapper.createArrayNode();
            if (raw != null) {
                ObjectNode resp = (ObjectNode) mapper.readTree(raw);
                if (resp.has("data")) {
                    for (var item : (ArrayNode) resp.get("data")) {
                        ObjectNode obj = mapper.createObjectNode();
                        obj.put("case_id", textVal(item, "id"));
                        obj.put("alert_features", textVal(item, "alert_features"));
                        obj.put("root_cause", textVal(item, "root_cause"));
                        obj.put("solution_steps", textVal(item, "solution_steps"));
                        obj.put("result", textVal(item, "result"));
                        if (item.has("score")) obj.put("similarity", item.get("score").asText());
                        cases.add(obj);
                    }
                }
            }
            result.set("cases", cases);
            result.put("total", cases.size());
            return result.toString();
        } catch (Exception e) {
            log.error("[MilvusKB] search failed: {}", e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\",\"mode\":\"milvus\"}";
        }
    }

    @Override
    public void addCase(String alertFeatures, String rootCause, List<String> solutionSteps, String result) {
        String id = "KB-" + System.currentTimeMillis();
        float[] embedding = embedText(alertFeatures + " " + rootCause);

        ObjectNode req = mapper.createObjectNode();
        req.put("collectionName", collectionName);

        ObjectNode record = mapper.createObjectNode();
        record.put("id", id);
        record.put("alert_features", alertFeatures);
        record.put("root_cause", rootCause);
        record.put("solution_steps", String.join("\n", solutionSteps));
        record.put("result", result);
        ArrayNode vecArr = mapper.createArrayNode();
        for (float v : embedding) vecArr.add(BigDecimal.valueOf(v));
        record.set("embedding", vecArr);

        ArrayNode records = mapper.createArrayNode();
        records.add(record);
        req.set("records", records);

        try {
            client.post()
                    .uri("/v1/vector/insert")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            log.info("[MilvusKB] 案例已入库: id={}", id);
        } catch (Exception e) {
            log.error("[MilvusKB] insert failed: {}", e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> listCases() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("collectionName", collectionName);
            ArrayNode fields = mapper.createArrayNode();
            fields.add("id"); fields.add("alert_features");
            fields.add("root_cause"); fields.add("solution_steps"); fields.add("result");
            req.set("outputFields", fields);
            req.put("limit", 1000L);

            String raw = client.post()
                    .uri("/v1/vector/query")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            if (raw != null) {
                ObjectNode resp = (ObjectNode) mapper.readTree(raw);
                if (resp.has("data")) {
                    for (var item : (ArrayNode) resp.get("data")) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", textVal(item, "id"));
                        m.put("alertFeatures", textVal(item, "alert_features"));
                        m.put("rootCause", textVal(item, "root_cause"));
                        m.put("solutionSteps", textVal(item, "solution_steps"));
                        m.put("result", textVal(item, "result"));
                        m.put("createdAt", new Date().toString());
                        result.add(m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[MilvusKB] listCases error: {}", e.getMessage());
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────
    private String textVal(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
    }

    /**
     * 文本向量化（开发用简单哈希，生产替换为 text-embedding-3-small 或 MiniMax Embedding API）
     */
    private float[] embedText(String text) {
        float[] vec = new float[dimension];
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < dimension; i++) {
            int val = 0;
            for (int j = 0; j < bytes.length; j++) {
                val = (val * 31 + bytes[(i + j) % bytes.length]) & 0x7FFFFFFF;
            }
            vec[i] = (float) (Math.sin(val * 0.618) * 0.5 + 0.5);
        }
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 1e-6) for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        return vec;
    }
}
