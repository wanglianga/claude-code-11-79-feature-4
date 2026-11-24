package com.ctrial.repo;

import com.ctrial.model.TreatmentPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TreatmentPeriodRepo extends JpaRepository<TreatmentPeriod, Long> {
    Optional<TreatmentPeriod> findByCode(String code);
    List<TreatmentPeriod> findAllByOrderByStartsOnAsc();
}
