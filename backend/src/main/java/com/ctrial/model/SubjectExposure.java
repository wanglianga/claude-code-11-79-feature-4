package com.ctrial.model;

import com.ctrial.model.Enums.RiskStratum;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Subject-level exposure: which site, treatment period and risk stratum the
 * subject contributes a denominator fragment to, and whether the subject is
 * enrolled inside the approved cross-border envelope at all.
 *
 * <p>{@code exposed} means the subject was randomised and dosed in this period.
 * A subject who withdrew global egress consent but is still followed locally is
 * <em>still exposed</em>; the consent only limits what may leave the region, not
 * whether a serious event could have occurred. This distinction is what lets the
 * ledger separate "known restricted" from "unobserved".
 */
@Entity
@Table(name = "subject_exposure", indexes = {
        @Index(name = "idx_exposure_subject", columnList = "subject_id")
})
@Getter
@Setter
@NoArgsConstructor
public class SubjectExposure {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "period_id")
    private TreatmentPeriod period;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_stratum", nullable = false)
    private RiskStratum riskStratum;

    /** Randomisation / first-dose instant within the period. */
    @Column(name = "started_on", nullable = false)
    private Instant startedOn;

    /** End of exposure (last dose / withdrawal / death / analysis cut). Null = ongoing. */
    @Column(name = "ended_on")
    private Instant endedOn;

    @Column(nullable = false)
    private boolean exposed;

    /**
     * Whether the subject's consent scope permits the cross-border purpose at
     * the analysis instant. A subject may be exposed and follow-up complete yet
     * still be consent-restricted for the global plane.
     */
    @Column(name = "egress_permitted", nullable = false)
    private boolean egressPermitted;

    public SubjectExposure(Subject subject, TreatmentPeriod period, RiskStratum riskStratum,
                           Instant startedOn, Instant endedOn, boolean exposed, boolean egressPermitted) {
        this.subject = subject;
        this.period = period;
        this.riskStratum = riskStratum;
        this.startedOn = startedOn;
        this.endedOn = endedOn;
        this.exposed = exposed;
        this.egressPermitted = egressPermitted;
    }
}
