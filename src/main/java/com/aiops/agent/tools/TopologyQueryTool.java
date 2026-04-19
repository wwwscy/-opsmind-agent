package com.aiops.agent.tools;
import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 拓扑查询工具
 * 查询 Pod → VM → 物理机 的层级关系
 * 用于定位故障发生在哪一层，以及故障是否向上下游蔓延
 */
@Slf4j
@Component
public class TopologyQueryTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "query_topology";
    }

    @Override
    public String description() {
        return "查询 Pod/VM/物理机的拓扑层级关系，返回节点所处层级和上下游关联节点。适用于缩小故障定位范围。";
    }

    @Override
    public Object handle(com.fasterxml.jackson.databind.JsonNode params) throws Exception {
        String target = params.path("target").asText();
        String targetName = params.path("target_name").asText();

        // TODO: 接入真实 CMDB / 华为云 VPC 拓扑 API
        return queryTopology(target, targetName);
    }

    private JsonNode queryTopology(String target, String targetName) {
        // ─────────────────────────────────────────────────────────
        // 真实实现：调用华为云 VPC API 或 CMDB 系统
        // GET /cmdb/topology?target=pod&name=nginx-7d9f8c
        // 返回: {"pod": "nginx-7d9f8c", "vm": "web-vm-04", "physical": "phost-12", "region": "region-01"}
        // ─────────────────────────────────────────────────────────

        ObjectNode root = mapper.createObjectNode();
        root.put("query_target", target);
        root.put("query_name", targetName);

        ObjectNode topology = mapper.createObjectNode();
        topology.put("pod", "nginx-7d9f8c");
        topology.put("vm", "web-vm-04");
        topology.put("physical_host", "phost-12");
        topology.put("region", "region-01");
        topology.put("availability_zone", "az-01");
        topology.put("pod_status", "Running");
        topology.put("vm_status", "Healthy");
        topology.put("physical_status", "Healthy");

        // 上游节点（同物理机上的其他 Pod）
        topology.putPOJO("peer_pods", new String[]{"redis-3a2b1c", "api-gateway-9x8y2z"});
        topology.putPOJO("downstream_services", new String[]{"upstream-proxy-01"});

        root.set("topology", topology);
        root.put("source", "mock_cmdb_api");

        return root;
    }
}
