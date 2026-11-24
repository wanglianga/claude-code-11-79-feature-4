package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A protocol treatment period (e.g. induction / maintenance). The observability
 * ledger partitions denominators by treatment period alongside site and risk
 * stratum, because the expected event rate and the return-delay profile both
 * differ between periods.
 */
@Entity
@Table(name = "treatment_period")
@Getter
@Setter
@NoArgsConstructor
public class TreatmentPeriod {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String label;

    @Column(name = "starts_on", nullable = false)
    private Instant startsOn;

    @Column(name = "ends_on")
    private Instant endsOn;

    /** Pre-declared expected serious-AE rate (events per subject-period) for this period. */
    @Column(name = "declared_rate")
    private Double declaredRate;

    /** Pre-declared clinical plausible upper bound for a stratum, used by the sensitivity band. */
    @Column(name = "declared_upper")
    private Double declaredUpper;

    public TreatmentPeriod(String code, String label, Instant startsOn, Instant endsOn,
                           Double declaredRate, Double declaredUpper) {
        this.code = code;
        this.label = label;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.declaredRate = declaredRate;
        this.declaredUpper = declaredUpper;
    }
}
