package com.ctrial.service;

import com.ctrial.model.*;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Delay calibrator.
 *
 * <p>Estimates what fraction of currently-in-flight serious events may still be
 * recovered, using <b>only realised return intervals</b> — events that actually
 * arrived ({@code returnedAt} set). An event that has not returned contributes
 * <em>nothing</em> to the interval distribution; using it would assume its lag,
 * which is exactly the quantity under estimation.
 *
 * <p>The realised intervals are kept split by site, protocol period and event
 * grade rather than pooled. If a centre's returns are systematically slower or a
 * period has a different grade mix, that difference is preserved and shown, and
 * a stratum with too few observations is flagged as uncalibrated instead of
 * being quietly merged.
 */
@Service
public class DelayCalibrationService {

    private final SaeRecordRepo saeRepo;
    private final SubjectExposureRepo exposureRepo;
    private final StudyClock clock;
    private final EligibilityService eligibility;

    public DelayCalibrationService(SaeRecordRepo saeRepo, SubjectExposureRepo exposureRepo,
                                   StudyClock clock, EligibilityService eligibility) {
        this.saeRepo = saeRepo;
        this.exposureRepo = exposureRepo;
        this.clock = clock;
        this.eligibility = eligibility;
    }

    public record IntervalRow(String pseudonym, String siteCode, String periodCode, String gradeBand,
                              double lagDays, Instant occurredAt, Instant returnedAt) {
    }

    public record Kpi(String siteCode, String periodCode, String gradeBand,
                      int realisedCount, double medianDays, double p80Days, double p90Days,
                      int inFlightCount, int recoverableWithinWindow, double recoverableFraction,
                      int windowDays, boolean calibrated) {
    }

    public record Diagnostics(int analysisDay, Instant analysisAt, int windowDays,
                              List<IntervalRow> realisedIntervals, List<Kpi> strata,
                              Kpi pooled, List<String> reconciliation, List<String> warnings) {
    }

    /** Default expected return window; a stratum is "late" only past this many days. */
    public static final int DEFAULT_WINDOW_DAYS = 10;

    @Transactional(readOnly = true)
    public Diagnostics build(int day, int windowDays) {
        Study study = eligibility.study();
        Instant t = clock.analysisInstant(study, day);

        Map<Long, SubjectExposure> exposureBySubject = new HashMap<>();
        for (SubjectExposure ex : exposureRepo.findByExposedTrueOrderByStartedOnAsc()) {
            exposureBySubject.putIfAbsent(ex.getSubject().getId(), ex);
        }

        List<IntervalRow> realised = new ArrayList<>();
        // events that have not returned are candidates for "still may arrive"; they are
        // never placed into the interval distribution.
        List<SaeRecord> inFlight = new ArrayList<>();

        for (SaeRecord s : saeRepo.findAllByOrderByOccurredAtAsc()) {
            SubjectExposure ex = exposureBySubject.get(s.getSubject().getId());
            String site = s.getSubject().getSite().getCode();
            String period = ex == null ? "-" : ex.getPeriod().getCode();
            String band = s.highGrade() ? "GRADE_3_PLUS" : "GRADE_1_2";
            if (s.isArrived() && s.getReturnedAt() != null && !s.getReturnedAt().isAfter(t)) {
                double lag = (s.getReturnedAt().toEpochMilli() - s.getOccurredAt().toEpochMilli()) / 86_400_000.0;
                realised.add(new IntervalRow(s.getSubject().getPseudonym(), site, period, band,
                        round2(lag), s.getOccurredAt(), s.getReturnedAt()));
            } else if (!s.getOccurredAt().isAfter(t)) {
                // known via anchor but not arrived, or arrived after the analysis instant
                inFlight.add(s);
            }
        }

        List<Kpi> strata = new ArrayList<>();
        Map<String, List<IntervalRow>> byStratum = realised.stream().collect(Collectors.groupingBy(
                r -> r.siteCode() + "|" + r.periodCode() + "|" + r.gradeBand(),
                LinkedHashMap::new, Collectors.toList()));
        Map<String, List<SaeRecord>> inFlightByStratum = inFlight.stream().collect(Collectors.groupingBy(
                s -> {
                    SubjectExposure ex = exposureBySubject.get(s.getSubject().getId());
                    String period = ex == null ? "-" : ex.getPeriod().getCode();
                    return s.getSubject().getSite().getCode() + "|" + period + "|"
                            + (s.highGrade() ? "GRADE_3_PLUS" : "GRADE_1_2");
                },
                LinkedHashMap::new, Collectors.toList()));

        Set<String> keys = new TreeSet<>(byStratum.keySet());
        keys.addAll(inFlightByStratum.keySet());
        for (String key : keys) {
            String[] parts = key.split("\\|");
            List<IntervalRow> rows = byStratum.getOrDefault(key, List.of());
            List<SaeRecord> fly = inFlightByStratum.getOrDefault(key, List.of());
            strata.add(kpi(parts[0], parts[1], parts[2], rows, fly, t, windowDays));
        }
        Kpi pooled = kpi("ALL", "ALL", "ALL", realised, inFlight, t, windowDays);

        List<String> reconciliation = new ArrayList<>();
        reconciliation.add("Interval distribution uses only " + realised.size()
                + " realised return(s); " + inFlight.size() + " in-flight candidate(s) are NOT used to fit it.");
        reconciliation.add("Still-recoverable fraction is the share of in-flight events whose age is under the "
                + windowDays + "-day window, using the per-stratum p90 lag; an uncalibrated stratum falls back to pooled.");
        List<String> warnings = new ArrayList<>();
        for (Kpi k : strata) {
            if (!k.calibrated()) {
                warnings.add(k.siteCode() + "/" + k.periodCode() + "/" + k.gradeBand()
                        + " has fewer than 3 realised returns — calibration is not significant for this stratum; "
                        + "the pooled p90 is shown but the difference must not be ignored.");
            }
        }
        return new Diagnostics(day, t, windowDays, realised, strata, pooled, reconciliation, warnings);
    }

    public Diagnostics build(int day) {
        return build(day, DEFAULT_WINDOW_DAYS);
    }

    private Kpi kpi(String site, String period, String band, List<IntervalRow> rows, List<SaeRecord> fly,
                    Instant t, int windowDays) {
        List<Double> lags = rows.stream().map(IntervalRow::lagDays).sorted().toList();
        double median = lags.isEmpty() ? 0 : percentile(lags, 0.50);
        double p80 = lags.isEmpty() ? 0 : percentile(lags, 0.80);
        double p90 = lags.isEmpty() ? 0 : percentile(lags, 0.90);
        double cap = lags.isEmpty() ? windowDays : Math.min(windowDays, Math.max(p90, median));
        int recoverable = 0;
        for (SaeRecord s : fly) {
            double age = (t.toEpochMilli() - s.getOccurredAt().toEpochMilli()) / 86_400_000.0;
            if (age <= cap) {
                recoverable++;
            }
        }
        double fraction = fly.isEmpty() ? 1.0 : (double) recoverable / fly.size();
        return new Kpi(site, period, band, lags.size(), round2(median), round2(p80), round2(p90),
                fly.size(), recoverable, round4(fraction), windowDays, lags.size() >= 3);
    }

    /** Linear-interpolated percentile over an already-sorted list. */
    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double idx = p * (sorted.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sorted.get(lo);
        }
        double w = idx - lo;
        return sorted.get(lo) * (1 - w) + sorted.get(hi) * w;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
