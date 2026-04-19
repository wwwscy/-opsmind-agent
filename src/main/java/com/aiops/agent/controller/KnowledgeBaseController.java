package com.aiops.agent.controller;

import com.aiops.agent.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /** 列出所有知识库案例 */
    @GetMapping
    public Map<String, Object> listCases() {
        List<Map<String, Object>> cases = knowledgeBaseService.listCases();
        return Map.of(
                "total", cases.size(),
                "cases", cases
        );
    }

    /** 手动添加案例 */
    @PostMapping
    public Map<String, Object> addCase(@RequestBody Map<String, Object> body) {
        String alertFeatures = (String) body.get("alertFeatures");
        String rootCause = (String) body.get("rootCause");
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) body.get("solutionSteps");
        String result = (String) body.getOrDefault("result", "手动添加");
        if (alertFeatures == null || rootCause == null) {
            return Map.of("error", "alertFeatures 和 rootCause 不能为空");
        }
        knowledgeBaseService.addCase(alertFeatures, rootCause, steps, result);
        return Map.of("ok", true, "message", "案例已添加");
    }
}
