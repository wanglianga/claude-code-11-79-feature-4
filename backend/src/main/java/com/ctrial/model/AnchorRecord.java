package com.ctrial.model;

import com.ctrial.model.Enums.SourceKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An independent anchor: a record that proves an opportunity or an event
 * existed even though it never reached the global plane. The classic case is a
 * centre audit that reads the site's paper file and finds a serious AE that was
 * never transmitted. Anchors are what make the unobserved region estimable at
 * all; without them the unobserved gap is not identifiable and stays a bound.
 *
 * <p>{@code auditLinked} marks an anchor that came from the manual safety
 * register (shared with the hotline). An estimator may use such an anchor only
 * as a correlated source.
 */
@Entity
@Table(name = "anchor_record")
@Getter
@Setter
@NoArgsConstructor
public class AnchorRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    /** Letter / code of the audit that produced the anchor (e.g. "AUD-CN03-01"). */
    @Column(name = "audit_code", nullable = false)
    private String auditCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false)
    private SourceKind sourceKind;

    /**
     * True when this anchor only covers events that were also visible to the
     * hotline register; such anchors are correlated and cannot be used to
     * "prove" two independent captures.
     */
    @Column(name = "correlated")
    private boolean correlated;

    /** Number of serious events the anchor independently witnessed. */
    @Column(name = "events_witnessed", nullable = false)
    private int eventsWitnessed;

    @Column(name = "observed_on", nullable = false)
    private Instant observedOn;

    /** Restricts the anchor to one stratum; null means all strata. */
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_stratum")
    private Enums.RiskStratum riskStratum;

    /** Restricts the anchor to one treatment period; null means all periods. */
    @Column(name = "period_code")
    private String periodCode;

    public AnchorRecord(Subject subject, String auditCode, SourceKind sourceKind, boolean correlated,
                        int eventsWitnessed, Instant observedOn, Enums.RiskStratum riskStratum, String periodCode) {
        this.subject = subject;
        this.auditCode = auditCode;
        this.sourceKind = sourceKind;
        this.correlated = correlated;
        this.eventsWitnessed = eventsWitnessed;
        this.observedOn = observedOn;
        this.riskStratum = riskStratum;
        this.periodCode = periodCode;
    }
}
