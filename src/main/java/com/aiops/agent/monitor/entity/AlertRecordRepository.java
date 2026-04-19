package com.aiops.agent.monitor.entity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlertRecordRepository extends JpaRepository<AlertRecord, Long>, JpaSpecificationExecutor<AlertRecord> {

    Page<AlertRecord> findByOrderByTriggeredAtDesc(Pageable pageable);

    Page<AlertRecord> findBySeverityOrderByTriggeredAtDesc(String severity, Pageable pageable);

    Page<AlertRecord> findByStatusOrderByTriggeredAtDesc(String status, Pageable pageable);

    Page<AlertRecord> findBySeverityAndStatusOrderByTriggeredAtDesc(String severity, String status, Pageable pageable);

    List<AlertRecord> findTop10ByOrderByTriggeredAtDesc();

    List<AlertRecord> findByTriggeredAtAfter(LocalDateTime time);

    long countByStatus(String status);

    long countBySeverity(String severity);
}
