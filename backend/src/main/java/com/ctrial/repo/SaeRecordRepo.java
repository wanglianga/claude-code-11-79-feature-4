package com.ctrial.repo;

import com.ctrial.model.SaeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SaeRecordRepo extends JpaRepository<SaeRecord, Long> {
    List<SaeRecord> findAllByOrderByOccurredAtAsc();
    List<SaeRecord> findByArrivedTrueOrderByOccurredAtAsc();
    List<SaeRecord> findBySubjectIdOrderByOccurredAtAsc(Long subjectId);
}
