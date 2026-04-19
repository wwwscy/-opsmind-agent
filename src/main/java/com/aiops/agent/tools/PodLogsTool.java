package com.aiops.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Pod 日志查询工具
 * 从 K8s / Loki / ElasticSearch 查询指定 Pod 的最近日志
 *
 * 开发阶段返回 Mock 日志，接入真实环境时替换 queryLogs() 里的 HTTP 调用
 */
@Slf4j
@Component
public class PodLogsTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    @Override
    public String name() {
        return "query_pod_logs";
    }

    @Override
    public String description() {
        return "查询指定 Pod 的最近日志（默认100行）。用于排查应用错误、异常退出、OOM 等问题。返回最近日志内容、时间戳和日志级别（INFO/WARN/ERROR）。";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        String podName = params.path("pod_name").asText("unknown");
        String namespace = params.path("namespace").asText("default");
        int lines = params.path("lines").asInt(100);
        String keyword = params.path("keyword").asText(null); // 可选：只返回包含关键词的行

        log.info("[Tool] query_pod_logs pod={} namespace={} lines={}", podName, namespace, lines);
        return queryLogs(podName, namespace, lines, keyword);
    }

    private JsonNode queryLogs(String podName, String namespace, int lines, String keyword) {
        // ─────────────────────────────────────────────────────────
        // 真实实现：
        //   - K8s: kubectl logs <pod> -n <namespace> --tail=<lines>
        //   - Loki: GET /loki/api/v1/query_range?query={pod="<pod>"}
        //   - Elastic: POST /logs-*/_search
        // ─────────────────────────────────────────────────────────

        ObjectNode result = mapper.createObjectNode();

        // 生成 Mock 日志（根据 pod 类型返回不同内容）
        String[] mockLogs = generateMockLogs(podName, lines, keyword);
        ArrayNode logsArray = mapper.createArrayNode();
        for (String entry : mockLogs) {
            logsArray.add(entry);
        }

        result.put("pod_name", podName);
        result.put("namespace", namespace);
        result.put("total_lines", mockLogs.length);
        result.put("source", "mock_k8s_api");
        result.set("logs", logsArray);
        return result;
    }

    private String[] generateMockLogs(String podName, int count, String keyword) {
        String[] infos = {
            "INFO  2026-04-19 16:50:01  [main] Tomcat started on port 8080",
            "INFO  2026-04-19 16:50:02  [main] Spring Boot application started in 3.2s",
            "INFO  2026-04-19 16:51:15  [http-nio-8080-exec-1] GET /api/health 200 12ms",
            "INFO  2026-04-19 16:52:30  [http-nio-8080-exec-3] GET /api/alerts 200 45ms",
            "WARN  2026-04-19 16:53:00  [pool-1-thread-5] Connection pool near limit: 48/50",
            "INFO  2026-04-19 16:53:15  [HikariPool-1 house-keeper] HikariPool hikari-pool-1 - Pool stats (total=50, active=48, idle=2, waiting=3)",
            "ERROR 2026-04-19 16:53:28  [http-nio-8080-exec-7] java.net.SocketTimeoutException: Read timed out",
            "WARN  2026-04-19 16:53:45  [Camel (camel) thread #99] Upstream service slow: /api/metrics latency=5200ms",
            "ERROR 2026-04-19 16:54:00  [http-nio-8080-exec-2] java.lang.OutOfMemoryError: Java heap space",
            "INFO  2026-04-19 16:54:01  [SpringContextShutdownHooks] Closing Spring root WebApplicationContext",
            "WARN  2026-04-19 16:54:02  [SpringContextShutdownHooks] 1 threads could not be stopped",
            "ERROR 2026-04-19 16:54:03  [Finalizer] java.lang.ref.Finalizer GC ROOT: Large heap allocation",
            "INFO  2026-04-19 16:54:10  [main] MemoryMXBean used: 1847MB / 2048MB (90.2%)",
            "WARN  2026-04-19 16:54:15  [pool-2-thread-1] Redis connection timeout after 5000ms",
            "ERROR 2026-04-19 16:54:20  [http-nio-8080-exec-9] java.sql.SQLTransientConnectionException: DB connection pool exhausted",
        };

        if (podName.toLowerCase().contains("nginx") || podName.toLowerCase().contains("ingress")) {
            infos = new String[]{
                "2026/04/19 16:50:01 [notice] 1#0: start worker process 128",
                "2026/04/19 16:52:15 [warn] 1#0: upstream connection timeout",
                "2026/04/19 16:53:00 [error] 1#0: connect() failed (111: Connection refused) while connecting to upstream",
                "2026/04/19 16:53:30 [warn] 1#0: upstream buffer overflow, 1024 bytes dropped",
                "2026/04/19 16:54:00 [notice] 1#0: signal process started",
            };
        }

        if (podName.toLowerCase().contains("mysql") || podName.toLowerCase().contains("mariadb")) {
            infos = new String[]{
                "2026-04-19T16:50:01.012345Z 0 [Note] /usr/sbin/mysqld: ready for connections.",
                "2026-04-19T16:52:30.123456Z 5 [Note] Aborted connection 5281 to db: 'app_db' user: 'app' host: '10.0.0.15' (GC took 120ms)",
                "2026-04-19T16:53:00.234567Z 3 [Warning] InnoDB: Table mysql/innodb_table_stats has 5 missing indexes",
                "2026-04-19T16:53:45.345678Z 0 [ERROR] Disk is 92% full: /var/lib/mysql",
            };
        }

        // 如果指定了 keyword，只返回匹配的
        if (keyword != null && !keyword.isEmpty()) {
            java.util.List<String> filtered = new java.util.ArrayList<>();
            for (String log : infos) {
                if (log.toLowerCase().contains(keyword.toLowerCase())) {
                    filtered.add(log);
                }
            }
            return filtered.toArray(new String[0]);
        }

        return infos;
    }
}
