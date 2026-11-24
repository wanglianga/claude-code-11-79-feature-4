package com.ctrial.repo;

import com.ctrial.model.AnchorRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnchorRecordRepo extends JpaRepository<AnchorRecord, Long> {
    List<AnchorRecord> findAllByOrderByObservedOnAsc();
}
