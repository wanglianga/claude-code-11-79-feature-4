package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A planned-visit template for a treatment period. The expected-opportunity
 * framework instantiates these into per-subject scheduled visits, each of which
 * is censored if its due date has not yet arrived at the analysis instant.
 */
@Entity
@Table(name = "planned_visit_template")
@Getter
@Setter
@NoArgsConstructor
public class PlannedVisitTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_code", nullable = false)
    private String periodCode;

    @Column(name = "visit_code", nullable = false)
    private String visitCode;

    @Column(name = "label", nullable = false)
    private String label;

    /** Days after randomisation at which the visit is expected. */
    @Column(name = "day_offset", nullable = false)
    private int dayOffset;

    /** Planned window (days) within which the visit counts as expected. */
    @Column(name = "window_days", nullable = false)
    private int windowDays;

    /** Whether this visit itself also triggers drug dispensing (an independent source). */
    @Column(name = "dispensing")
    private boolean dispensing;

    public PlannedVisitTemplate(String periodCode, String visitCode, String label, int dayOffset,
                                int windowDays, boolean dispensing) {
        this.periodCode = periodCode;
        this.visitCode = visitCode;
        this.label = label;
        this.dayOffset = dayOffset;
        this.windowDays = windowDays;
        this.dispensing = dispensing;
    }
}
