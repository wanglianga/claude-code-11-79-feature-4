package com.ctrial.model;

import com.ctrial.model.Enums.SourceKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An observation source that can independently witness that a subject had an
 * opportunity for a serious adverse event: a scheduled visit (schedule record),
 * a drug dispensing, or a safety-hotline contact. The expected-event framework
 * turns these into the moments at which a serious event <em>could</em> have been
 * observed.
 *
 * <p>Sources are not independent by construction. When two sources are captured
 * through the same manual intake form (e.g. hotline and audit both keyed from the
 * centre's paper safety log), {@code correlatedWith} names the other source and
 * the estimator must not treat their captures as independent.
 */
@Entity
@Table(name = "observation_source", indexes = {
        @Index(name = "idx_obs_source_subject", columnList = "subject_id,observed_on")
})
@Getter
@Setter
@NoArgsConstructor
public class ObservationSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false)
    private SourceKind sourceKind;

    /** Label of the record in the source system, e.g. "VIS-STUDYDAY-28" or "DISP-0007". */
    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    /** Instant the opportunity was observed. */
    @Column(name = "observed_on", nullable = false)
    private Instant observedOn;

    /** Study day the opportunity is expected to fall due (null for unplanned contacts). */
    @Column(name = "planned_day")
    private Integer plannedDay;

    /**
     * When true this source is itself a manual transcription (hotline / audit
     * form). Manual transcriptions are the usual source of dependence.
     */
    @Column(nullable = false)
    private boolean manualTranscription;

    /** Names another SourceKind whose captures are keyed from the same register, if any. */
    @Enumerated(EnumType.STRING)
    @Column(name = "correlated_with")
    private SourceKind correlatedWith;

    public ObservationSource(Subject subject, SourceKind sourceKind, String sourceRef, Instant observedOn,
                             Integer plannedDay, boolean manualTranscription, SourceKind correlatedWith) {
        this.subject = subject;
        this.sourceKind = sourceKind;
        this.sourceRef = sourceRef;
        this.observedOn = observedOn;
        this.plannedDay = plannedDay;
        this.manualTranscription = manualTranscription;
        this.correlatedWith = correlatedWith;
    }
}
