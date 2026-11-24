package com.ctrial.repo;

import com.ctrial.model.SubjectExposure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubjectExposureRepo extends JpaRepository<SubjectExposure, Long> {
    List<SubjectExposure> findByExposedTrueOrderByStartedOnAsc();
    List<SubjectExposure> findBySubjectIdOrderByStartedOnAsc(Long subjectId);
    List<SubjectExposure> findAllByOrderByStartedOnAsc();
}
