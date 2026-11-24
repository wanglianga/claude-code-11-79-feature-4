package com.ctrial.repo;

import com.ctrial.model.ObservationSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ObservationSourceRepo extends JpaRepository<ObservationSource, Long> {
    List<ObservationSource> findAllByOrderByObservedOnAsc();
    List<ObservationSource> findBySubjectIdOrderByObservedOnAsc(Long subjectId);
}
