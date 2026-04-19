package com.aiops.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * K8s Events 查询工具
 * 查询指定命名空间或 Pod 最近的 K8s Events（事件）
 * 用于了解 Pod 重启、调度、镜像拉取失败、资源限制等信息
 *
 * 开发阶段返回 Mock Events，接入真实环境时替换 queryEvents() 里的 HTTP 调用
 */
@Slf4j
@Component
public class EventsTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "query_events";
    }

    @Override
    public String description() {
        return "查询 Kubernetes Event 事件列表。可查询全部命名空间或指定 namespace/pod 的最近事件。返回事件类型（Normal/Warning）、原因、时间、内容。";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        String namespace = params.path("namespace").asText("default");
        String podName = params.path("pod_name").asText(null);
        int limit = params.path("limit").asInt(50);

        log.info("[Tool] query_events namespace={} pod={} limit={}", namespace, podName, limit);
        return queryEvents(namespace, podName, limit);
    }

    private JsonNode queryEvents(String namespace, String podName, int limit) {
        // ─────────────────────────────────────────────────────────
        // 真实实现：
        //   kubectl get events -n <namespace> --sort-by='.lastTimestamp'
        //   K8s API: GET /api/v1/namespaces/<ns>/events
        // ─────────────────────────────────────────────────────────

        ObjectNode result = mapper.createObjectNode();
        result.put("namespace", namespace);
        result.put("limit", limit);
        result.put("source", "mock_k8s_api");

        ArrayNode events = mapper.createArrayNode();

        // 常见 K8s Events
        ObjectNode[] mockEvents = {
            makeEvent("Normal", "Scheduled", "Pod", "default/nginx-7d9f8c-abc12", "Successfully scheduled pod on node-1"),
            makeEvent("Normal", "Pulled", "Pod", "default/nginx-7d9f8c-abc12", "Container image \"nginx:1.21\" already present"),
            makeEvent("Normal", "Created", "Pod", "default/nginx-7d9f8c-abc12", "Created container nginx"),
            makeEvent("Normal", "Started", "Pod", "default/nginx-7d9f8c-abc12", "Started container nginx"),
            makeEvent("Warning", "BackOff", "Pod", "default/app-v2-5f4d8c", "Back-off restarting failed container"),
            makeEvent("Warning", "Unhealthy", "Pod", "default/app-v2-5f4d8c", "Liveness probe failed: HTTP GET /healthz status=503"),
            makeEvent("Normal", "Killing", "Pod", "default/app-v1-3c7f9a", "Stopping container app"),
            makeEvent("Normal", "ScalingReplicaSet", "Deployment", "default/app-deployment", "Scaled up replica set app-v2 to 3"),
            makeEvent("Warning", "FailedScheduling", "Pod", "default/batch-job-xyz", "0/3 nodes are available: 1 Insufficient memory, 2 node(s) were taint"),
            makeEvent("Normal", "LeaderElection", "ConfigMap", "kube-system/extension-apiserver-authentication", "successfully acquired lease kube-system/extension-apiserver-authentication"),
            makeEvent("Warning", "Evicted", "Pod", "default/worker-99f7a", "Pod was evicted: node pressure"),
            makeEvent("Normal", "NodeReady", "Node", "node-2", "Node node-2 status is now: NodeReady"),
            makeEvent("Warning", "OomKilled", "Pod", "default/java-service-abc1", "Container java-app was OOMKilled"),
        };

        for (ObjectNode e : mockEvents) {
            if (events.size() >= limit) break;
            if (podName != null && !podName.isEmpty() && !e.get("involvedObject").asText().contains(podName)) {
                continue;
            }
            events.add(e);
        }

        result.set("events", events);
        result.put("total", events.size());
        return result;
    }

    private ObjectNode makeEvent(String type, String reason, String kind, String obj, String msg) {
        ObjectNode e = mapper.createObjectNode();
        e.put("type", type);
        e.put("reason", reason);
        e.put("message", msg);
        e.put("involvedObject", obj);
        e.put("kind", kind);
        e.put("timestamp", "2026-04-19T16:50:" + String.format("%02d", (int)(Math.random() * 60)));
        e.put("count", (int)(Math.random() * 5) + 1);
        return e;
    }
}
