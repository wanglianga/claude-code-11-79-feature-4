package com.ctrial.model;

import com.ctrial.model.Enums.AuditFindingKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A finding from a centre audit: an independent look at the site's own files.
 * A finding of a serious AE that never reached the platform is precisely the
 * anchor that lets the ledger label a fragment "audit-flagged missing" rather
 * than "unobserved". Findings whose source is the shared manual safety register
 * are marked {@code correlated} so the estimator never double-counts them.
 */
@Entity
@Table(name = "audit_finding")
@Getter
@Setter
@NoArgsConstructor
public class AuditFinding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "site_id")
    private Site site;

    @Column(name = "audit_code", nullable = false)
    private String auditCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_kind", nullable = false)
    private AuditFindingKind findingKind;

    @Column(nullable = false, length = 1000)
    private String detail;

    /** Manual transcription (paper register) rather than an electronic, independent extract. */
    @Column(nullable = false)
    private boolean correlated;

    /** Number of serious events the finding attests to. */
    @Column(name = "serious_events", nullable = false)
    private int seriousEvents;

    /** Sampling fraction the audit used (e.g. 0.10 for a 10% chart sample). */
    @Column(name = "sampled_fraction")
    private Double sampledFraction;

    /** The set of subject-pseudonyms audited, when the audit was chart-level. */
    @Column(name = "sampled_subjects", length = 1000)
    private String sampledSubjects;

    @Column(name = "observed_on", nullable = false)
    private Instant observedOn;

    public AuditFinding(Site site, String auditCode, AuditFindingKind findingKind, String detail,
                        boolean correlated, int seriousEvents, Double sampledFraction, String sampledSubjects,
                        Instant observedOn) {
        this.site = site;
        this.auditCode = auditCode;
        this.findingKind = findingKind;
        this.detail = detail;
        this.correlated = correlated;
        this.seriousEvents = seriousEvents;
        this.sampledFraction = sampledFraction;
        this.sampledSubjects = sampledSubjects;
        this.observedOn = observedOn;
    }
}
