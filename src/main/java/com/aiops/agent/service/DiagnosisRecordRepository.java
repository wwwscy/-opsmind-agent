package com.aiops.agent.service;

import com.aiops.agent.service.entity.DiagnosisRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DiagnosisRecordRepository extends JpaRepository<DiagnosisRecord, Long> {

    Optional<DiagnosisRecord> findBySessionId(String sessionId);
}
