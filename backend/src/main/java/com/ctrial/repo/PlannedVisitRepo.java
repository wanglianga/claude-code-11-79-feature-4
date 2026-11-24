package com.ctrial.repo;

import com.ctrial.model.PlannedVisitTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlannedVisitRepo extends JpaRepository<PlannedVisitTemplate, Long> {
    List<PlannedVisitTemplate> findAllByOrderByDayOffsetAsc();
    List<PlannedVisitTemplate> findByPeriodCodeOrderByDayOffsetAsc(String periodCode);
}
