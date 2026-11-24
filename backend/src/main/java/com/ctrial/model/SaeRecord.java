package com.ctrial.model;

import com.ctrial.model.Enums.SourceKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A serious adverse event observed at (or on behalf of) a subject, whether or
 * not it reached the global analysis plane. This is the unit the delay
 * calibrator learns from:
 *
 * <ul>
 *   <li>{@code returnedAt} is the instant the report actually arrived at the
 *       platform. The lag {@code returnedAt - occurredAt} is a realised return
 *       interval used to calibrate what is still plausibly in flight.</li>
 *   <li>{@code arrived == false} means the event is known (via an anchor) but no
 *       return has arrived yet — it is a candidate that the calibrator may
 *       classify as "still may arrive" within the window.</li>
 *   <li>{@code egressPermitted} marks whether the event may appear in the global
 *       plane; a serious event in a consent-restricted subject is still an
 *       event, just not one the global team can see.</li>
 * </ul>
 */
@Entity
@Table(name = "sae_record", indexes = {
        @Index(name = "idx_sae_subject", columnList = "subject_id,occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
public class SaeRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @Column(name = "stable_id", nullable = false)
    private String stableId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Arrival at the platform. Null while the event has not returned. */
    @Column(name = "returned_at")
    private Instant returnedAt;

    /** CTCAE-style grade (1-5). Stratification band uses grade >= 3 as "high". */
    @Column(nullable = false)
    private int grade;

    @Column(nullable = false)
    private boolean serious;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false)
    private SourceKind sourceKind;

    /** The event has actually arrived at the platform (returnedAt set). */
    @Column(nullable = false)
    private boolean arrived;

    /** The event's subject permits cross-border egress at the analysis instant. */
    @Column(name = "egress_permitted", nullable = false)
    private boolean egressPermitted;

    /** Anchor code that surfaced this event, if it did not arrive. */
    @Column(name = "anchor_code")
    private String anchorCode;

    public SaeRecord(Subject subject, String stableId, Instant occurredAt, Instant returnedAt, int grade,
                     boolean serious, SourceKind sourceKind, boolean arrived, boolean egressPermitted,
                     String anchorCode) {
        this.subject = subject;
        this.stableId = stableId;
        this.occurredAt = occurredAt;
        this.returnedAt = returnedAt;
        this.grade = grade;
        this.serious = serious;
        this.sourceKind = sourceKind;
        this.arrived = arrived;
        this.egressPermitted = egressPermitted;
        this.anchorCode = anchorCode;
    }

    public boolean highGrade() {
        return grade >= 3;
    }

    /** Realised return lag in days; null if not arrived. */
    public Double lagDays() {
        if (returnedAt == null) {
            return null;
        }
        return (returnedAt.toEpochMilli() - occurredAt.toEpochMilli()) / 86_400_000.0;
    }
}
