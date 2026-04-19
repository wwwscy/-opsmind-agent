package com.aiops.agent.monitor.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlertRecordRepository extends JpaRepository<AlertRecord, Long> {

    /** 查询最近告警 */
    List<AlertRecord> findTop10ByOrderByTriggeredAtDesc();

    /** 按状态查询 */
    List<AlertRecord> findByStatus(String status);

    /** 查询最近未处理的告警 */
    List<AlertRecord> findByStatusIn(List<String> statuses);

    /** 查询某时间之后的告警 */
    List<AlertRecord> findByTriggeredAtAfter(LocalDateTime time);
}
