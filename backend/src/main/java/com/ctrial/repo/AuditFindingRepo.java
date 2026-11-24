package com.ctrial.repo;

import com.ctrial.model.AuditFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditFindingRepo extends JpaRepository<AuditFinding, Long> {
    List<AuditFinding> findAllByOrderByObservedOnAsc();
}
