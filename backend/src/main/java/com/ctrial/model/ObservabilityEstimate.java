package com.ctrial.model;

import com.ctrial.model.Enums.EstimateRoute;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A versioned, immutable observability &amp; sensitivity estimate. When a later
 * return tightens the range, a <b>new</b> record is written and the prior record
 * is retained for audit — a published interpretation is never silently rewritten.
 *
 * <p>The estimate carries: the global-visible count, the identifiable range
 * (known-restricted is bounded locally, unobserved is a sensitivity band), the
 * route chosen and why, the assumption disclosures, and the named actions that
 * would tighten the range.
 */
@Entity
@Table(name = "observability_estimate", uniqueConstraints = {
        @UniqueConstraint(name = "uq_obs_estimate_code", columnNames = {"code"})
})
@Getter
@Setter
@NoArgsConstructor
public class ObservabilityEstimate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** Version group; later re-estimates share this and increment {@code version}. */
    @Column(name = "version_group", nullable = false)
    private String versionGroup;

    @Column(nullable = false)
    private int version;

    @Column(name = "analysis_day", nullable = false)
    private int analysisDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstimateRoute route;

    /** The route the caller asked for; may differ from {@code route} when downgraded. */
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_route", nullable = false)
    private EstimateRoute requestedRoute;

    @Column(name = "downgraded", nullable = false)
    private boolean downgraded;

    @Column(name = "downgrade_reason", length = 1000)
    private String downgradeReason;

    @Column(name = "global_visible_count", nullable = false)
    private int globalVisibleCount;

    @Column(name = "known_restricted_count", nullable = false)
    private int knownRestrictedCount;

    @Column(name = "known_pending_count", nullable = false)
    private int knownPendingCount;

    @Column(name = "audit_flagged_count", nullable = false)
    private int auditFlaggedCount;

    @Column(name = "unobserved_count", nullable = false)
    private int unobservedCount;

    @Column(name = "denominator", nullable = false)
    private int denominator;

    // --- known-restricted local bound (events per 100 subjects) ---
    @Column(name = "restricted_lower")
    private Double restrictedLower;

    @Column(name = "restricted_upper")
    private Double restrictedUpper;

    // --- unobserved sensitivity band ---
    @Column(name = "unobserved_lower")
    private Double unobservedLower;

    @Column(name = "unobserved_upper")
    private Double unobservedUpper;

    // --- combined global estimate range ---
    @Column(name = "range_lower")
    private Double rangeLower;

    @Column(name = "range_upper")
    private Double rangeUpper;

    @Column(name = "point_estimate")
    private Double pointEstimate;

    /** Whether independent (uncorrelated) anchors were sufficient for the chosen route. */
    @Column(name = "anchors_sufficient", nullable = false)
    private boolean anchorsSufficient;

    @Column(name = "anchor_coverage")
    private Double anchorCoverage;

    /** Coverage failure that the delay model cannot explain (e.g. closed site, silent). */
    @Column(name = "coverage_failure", nullable = false)
    private boolean coverageFailure;

    @Column(name = "coverage_failure_detail", length = 2000)
    private String coverageFailureDetail;

    /** True when two or more sources were captured from the same register and are correlated. */
    @Column(name = "correlated_sources", nullable = false)
    private boolean correlatedSources;

    @Column(name = "correlated_detail", length = 2000)
    private String correlatedDetail;

    @Column(name = "assumptions", columnDefinition = "text")
    private String assumptionsJson;

    @Column(name = "tightening_actions", columnDefinition = "text")
    private String tighteningJson;

    @Column(name = "diagnostics", columnDefinition = "text")
    private String diagnosticsJson;

    /** Whether this is a sponsor-facing safe projection (true) or a local-only record (false). */
    @Column(name = "global_safe", nullable = false)
    private boolean globalSafe;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;
}
