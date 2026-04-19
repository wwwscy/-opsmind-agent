package com.aiops.agent.tools;
import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 变更记录查询工具
 * 查询近期变更操作（版本发布、配置变更、扩缩容）
 * 变更通常是故障的重要诱因，优先查询
 */
@Slf4j
@Component
public class ChangeQueryTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "query_change_records";
    }

    @Override
    public String description() {
        return "查询目标对象近期的变更操作记录，包括版本发布、配置修改、扩缩容等。变更时间与故障时间吻合时优先排查变更相关问题。";
    }

    @Override
    public Object handle(com.fasterxml.jackson.databind.JsonNode params) throws Exception {
        String targetName = params.path("target_name").asText();
        int hours = params.path("hours").asInt(6); // 默认查近6小时

        // TODO: 接入真实变更管理系统 API（如华为云 GaussDB / 运维平台）
        return queryChanges(targetName, hours);
    }

    private JsonNode queryChanges(String targetName, int hours) {
        // ─────────────────────────────────────────────────────────
        // 真实实现：调用变更管理系统 API
        // GET /cmdb/changes?target=$targetName&hours=$hours
        // ─────────────────────────────────────────────────────────

        ObjectNode root = mapper.createObjectNode();
        root.put("target_name", targetName);
        root.put("query_hours", hours);

        ObjectNode changes = mapper.createObjectNode();
        // 模拟数据：1小时前有一次版本升级
        changes.put("change_id", "CHG-20240419-001");
        changes.put("change_type", "version_upgrade");  // version_upgrade | config_change | scale
        changes.put("change_time", "2024-04-19T08:30:00+08:00");
        changes.put("version_from", "v1.2.0");
        changes.put("version_to", "v1.3.0");
        changes.put("operator", "ci-cd-pipeline");
        changes.put("status", "completed");

        // 额外的变更记录（用于演示多变更场景）
        root.set("latest_change", changes);
        root.putPOJO("other_changes", new Object[]{
            Map.of("change_id", "CHG-20240418-015", "change_type", "config_change",
                   "change_time", "2024-04-18T14:00:00+08:00", "description", "修改 JVM 堆内存参数"),
            Map.of("change_id", "CHG-20240417-009", "change_type", "scale",
                   "change_time", "2024-04-17T10:00:00+08:00", "description", "扩缩容 2→4 副本")
        });
        root.put("source", "mock_change_management_system");

        return root;
    }
}
