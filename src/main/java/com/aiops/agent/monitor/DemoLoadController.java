package com.aiops.agent.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟业务负载 Demo
 *
 * 暴露几个接口，人为制造 CPU/内存/响应延迟 异常波动
 * 用于测试告警检测 + AI 诊断 + 飞书推送全链路
 *
 * 启动后访问：http://localhost:8080/api/demo/start
 */
@Slf4j
@RestController
@RequestMapping("/api/demo")
public class DemoLoadController {

    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // 模拟业务指标
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong lastResponseTime = new AtomicLong(0);

    // 异常注入开关
    private volatile boolean cpuSpike = false;
    private volatile boolean memoryLeak = false;
    private volatile boolean latencySpike = false;
    private final List<byte[]> leakBuffers = new ArrayList<>();

    // 线程池模拟业务处理
    private final ExecutorService bizExecutor = Executors.newFixedThreadPool(10);

    public DemoLoadController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerMeters() {
        // 业务指标注册到 Micrometer
        Gauge.builder("app.requests.total", requestCount, AtomicLong::doubleValue)
                .description("总请求数").register(meterRegistry);

        Gauge.builder("app.requests.errors", errorCount, AtomicLong::doubleValue)
                .description("错误请求数").register(meterRegistry);

        Gauge.builder("app.active_connections", activeConnections, AtomicInteger::doubleValue)
                .description("活跃连接数").register(meterRegistry);

        Gauge.builder("app.response_time_ms", lastResponseTime, AtomicLong::doubleValue)
                .description("上次响应时间(ms)").register(meterRegistry);

        Gauge.builder("app.cpu.spike", () -> cpuSpike ? 1.0 : 0.0)
                .description("CPU 异常标记").register(meterRegistry);

        Gauge.builder("app.memory.leak", () -> memoryLeak ? 1.0 : 0.0)
                .description("内存泄漏标记").register(meterRegistry);

        // 注册 JVM 指标（已有，但显式打印）
        log.info("[Demo] 业务指标已注册，访问 /api/demo/start 启动模拟负载");
    }

    // ==================== 业务接口 ====================

    @GetMapping("/start")
    public Map<String, Object> startLoad() {
        startNormalTraffic();
        startCpuMonitor();
        startMemoryMonitor();
        return Map.of(
                "message", "模拟负载已启动",
                "endpoints", List.of(
                        "GET /api/demo/start — 启动正常流量",
                        "GET /api/demo/cpu-spike — 触发 CPU 异常",
                        "GET /api/demo/memory-leak — 触发内存泄漏",
                        "GET /api/demo/latency-spike — 触发延迟异常",
                        "GET /api/demo/resolve — 恢复正常",
                        "GET /api/demo/status — 查看状态"
                )
        );
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "cpuSpike", cpuSpike,
                "memoryLeak", memoryLeak,
                "latencySpike", latencySpike,
                "leakBufferCount", leakBuffers.size(),
                "requestCount", requestCount.get(),
                "errorCount", errorCount.get(),
                "activeConnections", activeConnections.get(),
                "lastResponseTimeMs", lastResponseTime.get()
        );
    }

    @GetMapping("/resolve")
    public Map<String, Object> resolve() {
        cpuSpike = false;
        memoryLeak = false;
        latencySpike = false;
        leakBuffers.clear();
        log.info("[Demo] ✅ 所有异常已清除");
        return Map.of("message", "所有异常已清除，系统恢复正常");
    }

    /**
     * 触发 CPU 异常（模拟一个 Pod CPU 99%）
     */
    @GetMapping("/cpu-spike")
    public Map<String, Object> cpuSpike() {
        cpuSpike = true;
        log.warn("[Demo] 🚨 CPU 异常已触发");
        return Map.of("message", "CPU 异常已触发，指标: app.cpu.spike=1", "alertExpected", "JVM CPU 告警将在 60 秒内触发");
    }

    /**
     * 触发内存泄漏（模拟 OOM 前的内存持续增长）
     */
    @GetMapping("/memory-leak")
    public Map<String, Object> memoryLeak() {
        memoryLeak = true;
        log.warn("[Demo] 🚨 内存泄漏已触发");
        return Map.of("message", "内存泄漏已触发，指标: app.memory.leak=1", "alertExpected", "JVM 堆内存告警将在 60 秒内触发");
    }

    /**
     * 触发延迟异常
     */
    @GetMapping("/latency-spike")
    public Map<String, Object> latencySpike() {
        latencySpike = true;
        log.warn("[Demo] 🚨 延迟异常已触发");
        return Map.of("message", "延迟异常已触发，响应时间将飙升", "alertExpected", "延迟超过阈值后触发");
    }

    // ==================== 模拟业务逻辑 ====================

    private void startNormalTraffic() {
        // 每 2 秒模拟一次请求
        scheduler.scheduleAtFixedRate(() -> {
            int conn = activeConnections.incrementAndGet();
            long start = System.currentTimeMillis();

            bizExecutor.submit(() -> {
                try {
                    // 正常业务处理：10-50ms
                    int baseDelay = latencySpike ? 5000 : new Random().nextInt(40) + 10;
                    Thread.sleep(baseDelay);

                    // 随机错误率 1%
                    if (new Random().nextInt(100) == 0) {
                        errorCount.incrementAndGet();
                    }

                    requestCount.incrementAndGet();
                    lastResponseTime.set(System.currentTimeMillis() - start);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    activeConnections.decrementAndGet();
                }
            });
        }, 0, 2, TimeUnit.SECONDS);

        log.info("[Demo] 正常流量模拟已启动");
    }

    private void startCpuMonitor() {
        // 每 5 秒检查是否需要注入 CPU 异常
        scheduler.scheduleAtFixedRate(() -> {
            if (cpuSpike) {
                // 模拟 CPU 飙高：执行密集计算
                triggerCpuSpike();
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void triggerCpuSpike() {
        // 占用 CPU 的计算（模拟 GC 或死循环）
        bizExecutor.submit(() -> {
            long start = System.currentTimeMillis();
            double dummy = 0;
            while (cpuSpike && System.currentTimeMillis() - start < 5000) {
                dummy += Math.random() * Math.random();
                dummy = Math.sqrt(dummy) * Math.PI;
            }
        });
        log.debug("[Demo] CPU 飙升中...");
    }

    private void startMemoryMonitor() {
        // 每 3 秒注入少量内存泄漏
        scheduler.scheduleAtFixedRate(() -> {
            if (memoryLeak) {
                // 每轮泄漏约 5MB
                byte[] leak = new byte[5 * 1024 * 1024];
                leakBuffers.add(leak);
                log.debug("[Demo] 内存泄漏注入，当前持有 {} MB", leakBuffers.size() * 5);
            }
        }, 0, 3, TimeUnit.SECONDS);
    }
}
