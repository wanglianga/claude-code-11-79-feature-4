package com.ctrial.service;

import com.ctrial.model.*;
import com.ctrial.model.Enums.EstimateRoute;
import com.ctrial.model.Enums.FragmentState;
import com.ctrial.model.Enums.SourceKind;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Serious-AE rate estimator on top of the observability ledger.
 *
 * <p>Two routes, chosen by <b>anchor completeness</b>, never by convenience:
 *
 * <ol>
 *   <li><b>PARTIAL_BOUNDS</b> (default, assumption-light). Known-restricted
 *       subjects are bounded <em>locally</em> with a finite local denominator, so
 *       their event rate is a real interval — not folded into the unknown. The
 *       genuinely unobserved subjects get only a sensitivity band built from
 *       independent-anchor coverage, the pre-declared clinical upper bound and
 *       the audit finding rate. This route makes no representativeness
 *       assumption.</li>
 *   <li><b>DELAY_CAPTURE_MODEL</b> (optional, only when independent anchors are
 *       sufficient AND schedule/dispensing/hotline coverage is adequate AND the
 *       sources are not correlated). It folds in the calibrated recovery of
 *       in-flight events for a narrower interval. It is refused — and downgraded
 *       to the bounds route — whenever those preconditions fail, because a model
 *       cannot be substituted for evidence that does not exist.</li>
 * </ol>
 *
 * <p>Correlated sources (e.g. hotline and audit sharing a manual register) are
 * detected and explicitly prohibited from narrowing the band by "capture-recapture"
 * multiplication.
 */
@Service
public class ObservabilityEstimateService {

    private final ObservabilityLedgerService ledger;
    private final DelayCalibrationService delay;
    private final ExpectedOpportunityService expected;
    private final AnchorRecordRepo anchorRepo;
    private final AuditFindingRepo auditRepo;
    private final ObservationSourceRepo sourceRepo;
    private final SubjectExposureRepo exposureRepo;
    private final SaeRecordRepo saeRepo;
    private final TreatmentPeriodRepo periodRepo;
    private final ObservabilityEstimateRepo estimateRepo;
    private final StudyClock clock;
    private final EligibilityService eligibility;
    private final AuditService audit;

    public ObservabilityEstimateService(ObservabilityLedgerService ledger, DelayCalibrationService delay,
                                        ExpectedOpportunityService expected, AnchorRecordRepo anchorRepo,
                                        AuditFindingRepo auditRepo, ObservationSourceRepo sourceRepo,
                                        SubjectExposureRepo exposureRepo, SaeRecordRepo saeRepo,
                                        TreatmentPeriodRepo periodRepo, ObservabilityEstimateRepo estimateRepo,
                                        StudyClock clock, EligibilityService eligibility, AuditService audit) {
        this.ledger = ledger;
        this.delay = delay;
        this.expected = expected;
        this.anchorRepo = anchorRepo;
        this.auditRepo = auditRepo;
        this.sourceRepo = sourceRepo;
        this.exposureRepo = exposureRepo;
        this.saeRepo = saeRepo;
        this.periodRepo = periodRepo;
        this.estimateRepo = estimateRepo;
        this.clock = clock;
        this.eligibility = eligibility;
        this.audit = audit;
    }

    /** A full, transient computation; the persisted record is a projection of this. */
    public record Computation(int analysisDay, EstimateRoute route, EstimateRoute requestedRoute,
                              boolean downgraded, String downgradeReason,
                              LedgerCounts counts, RateBand restricted, RateBand unobserved, RateBand combined,
                              Double pointEstimate, boolean anchorsSufficient, double anchorCoverage,
                              CoverageFailure coverageFailure, Correlation correlation,
                              List<Assumption> assumptions, List<TighteningAction> tightening,
                              List<String> diagnostics, DelayCalibrationService.Diagnostics delayDiagnostics,
                              ExpectedOpportunityService.Framework expectedFramework,
                              ObservabilityLedgerService.GlobalProjection globalProjection) {
    }

    public record LedgerCounts(int denominator, int globalVisible, int knownRestricted, int knownPending,
                               int auditFlagged, int unobserved) {
    }

    /** Rate per 100 subjects over the analysis window. */
    public record RateBand(double lower, double upper, String basis) {
    }

    public record CoverageFailure(boolean active, String siteCode, String detail, List<String> conditions) {
    }

    public record Correlation(boolean present, List<String> detail) {
    }

    public record Assumption(String code, String statement, boolean testable) {
    }

    public record TighteningAction(String action, String expectedEffect) {
    }

    // ------------------------------------------------ build

    @Transactional
    public Computation compute(int day, EstimateRoute requested, int windowDays) {
        ObservabilityLedgerService.Ledger led = ledger.build(day, windowDays);
        ObservabilityLedgerService.GlobalProjection projection = ledger.projectGlobal(day, windowDays);
        DelayCalibrationService.Diagnostics dx = delay.build(day, windowDays);
        ExpectedOpportunityService.Framework framework = expected.build(day);

        int denominator = led.segments().stream().mapToInt(ObservabilityLedgerService.Segment::denominator).sum();
        int included = led.segments().stream().mapToInt(ObservabilityLedgerService.Segment::included).sum();
        int restricted = led.segments().stream().mapToInt(ObservabilityLedgerService.Segment::knownRestricted).sum();
        int pending = led.segments().stream().mapToInt(ObservabilityLedgerService.Segment::knownPendingArrival).sum();
        int flagged = led.segments().stream().mapToInt(ObservabilityLedgerService.Segment::auditFlaggedMissing).sum();
        int unobserved = led.segments().stream().mapToInt(ObservabilityLedgerService.Segment::unobservedNoAnchor).sum();
        int visibleEvents = led.segments().stream().mapToInt(ObservabilityLedgerService.Segment::globalVisibleEvents).sum();
        int localKnownEvents = led.segments().stream().mapToInt(ObservabilityLedgerService.Segment::locallyKnownEvents).sum();
        int restrictedEvents = led.segments().stream().mapToInt(ObservabilityLedgerService.Segment::restrictedEvents).sum();
        LedgerCounts counts = new LedgerCounts(denominator, included, restricted, pending, flagged, unobserved);

        // ---- correlation detection (shared manual register) ----
        Correlation correlation = detectCorrelation();

        // ---- anchor completeness ----
        List<AnchorRecord> anchors = anchorRepo.findAllByOrderByObservedOnAsc();
        long independentAnchors = anchors.stream().filter(a -> !a.isCorrelated()).count();
        int independentAnchorEvents = anchors.stream().filter(a -> !a.isCorrelated())
                .mapToInt(AnchorRecord::getEventsWitnessed).sum();
        long unobservedSubjects = led.fragments().stream()
                .filter(f -> f.state() == FragmentState.UNOBSERVED_NO_ANCHOR).count();
        double anchorCoverage = unobservedSubjects == 0 ? 1.0
                : Math.min(1.0, (double) independentAnchorEvents / Math.max(1, unobservedSubjects));
        boolean anchorsSufficient = independentAnchors >= 2 && anchorCoverage >= 0.20 && !correlation.present();

        // source coverage adequacy for the capture model: all three source kinds present
        boolean scheduleCovered = hasKind(SourceKind.SCHEDULE);
        boolean dispensingCovered = hasKind(SourceKind.DISPENSING);
        boolean hotlineCovered = hasKind(SourceKind.HOTLINE);
        boolean coverageAdequate = scheduleCovered && dispensingCovered && hotlineCovered;

        // ---- coverage failure: closed site with dispensing but long silence ----
        CoverageFailure coverageFailure = detectCoverageFailure(day, led);

        // ---- route selection ----
        EstimateRoute route = requested;
        boolean downgraded = false;
        String downgradeReason = null;
        if (requested == EstimateRoute.DELAY_CAPTURE_MODEL) {
            if (correlation.present()) {
                route = EstimateRoute.PARTIAL_BOUNDS;
                downgraded = true;
                downgradeReason = "Capture model refused: two source kinds share one manual register (correlated), "
                        + "so independent-capture multiplication is invalid.";
            } else if (!anchorsSufficient) {
                route = EstimateRoute.PARTIAL_BOUNDS;
                downgraded = true;
                downgradeReason = "Capture model refused: independent anchors are insufficient "
                        + "(independent=" + independentAnchors + ", coverage=" + round2(anchorCoverage)
                        + "); a model cannot replace missing evidence.";
            } else if (!coverageAdequate) {
                route = EstimateRoute.PARTIAL_BOUNDS;
                downgraded = true;
                downgradeReason = "Capture model refused: schedule/dispensing/hotline coverage is not adequate for a "
                        + "capture/delay model.";
            } else if (coverageFailure.active()) {
                route = EstimateRoute.PARTIAL_BOUNDS;
                downgraded = true;
                downgradeReason = "Capture model refused: a coverage failure (closed-site silence) is not explainable "
                        + "by delay; it must escalate, not be modelled away.";
            }
        }

        // ---- rate bands (per 100 subjects) ----
        RateBand restrictedBand = restrictedBand(counts, restrictedEvents);
        int anchorsInUnobserved = (int) anchors.stream().filter(a -> !a.isCorrelated())
                .filter(a -> {
                    ObservabilityLedgerService.Fragment f = led.fragments().stream()
                            .filter(x -> x.pseudonym().equals(a.getSubject().getPseudonym())).findFirst().orElse(null);
                    return f != null && f.state() == FragmentState.UNOBSERVED_NO_ANCHOR;
                })
                .mapToInt(AnchorRecord::getEventsWitnessed).sum();
        // audit finding rate: serious events found / distinct charts independently audited
        Set<String> auditedSubjects = new HashSet<>();
        int auditedSerious = 0;
        for (AuditFinding af : auditRepo.findAllByOrderByObservedOnAsc()) {
            if (af.isCorrelated()) {
                continue; // correlated findings do not contribute independent evidence
            }
            auditedSerious += af.getSeriousEvents();
            if (af.getSampledSubjects() != null && !af.getSampledSubjects().isBlank()) {
                for (String p : af.getSampledSubjects().split(",")) {
                    if (!p.isBlank()) {
                        auditedSubjects.add(p.trim());
                    }
                }
            }
        }
        double auditFindingRate = auditedSubjects.isEmpty() ? 0.0
                : Math.min(1.0, (double) auditedSerious / auditedSubjects.size());
        RateBand unobservedBand = unobservedBand(counts, anchorsInUnobserved, auditFindingRate);
        RateBand combined = combine(route, counts, restrictedBand, unobservedBand, visibleEvents,
                dx, coverageFailure);

        Double pointEstimate = null;
        if (route == EstimateRoute.DELAY_CAPTURE_MODEL && counts.denominator() > 0) {
            double recovered = dx.pooled().recoverableFraction();
            double expectedEvents = visibleEvents + (visibleEvents * recovered);
            pointEstimate = round2(100.0 * expectedEvents / counts.denominator());
        }

        // ---- assumptions & tightening actions ----
        List<Assumption> assumptions = assumptions(route, requested, correlation, coverageFailure, anchorCoverage);
        List<TighteningAction> tightening = tightening(led, counts, correlation, coverageFailure, dx);

        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("Route requested=" + requested + ", used=" + route + (downgraded ? " (downgraded)" : ""));
        diagnostics.add("Denominator " + denominator + " = included " + included + " + restricted " + restricted
                + " + pending " + pending + " + audit-flagged " + flagged + " + unobserved " + unobserved + ".");
        diagnostics.add("Global-visible events " + visibleEvents + "; locally-known events " + localKnownEvents
                + " (restricted events are bounded locally, never counted as unobserved).");
        diagnostics.add("Independent anchors " + independentAnchors + ", coverage " + round2(anchorCoverage) + ".");
        diagnostics.add("Delay: " + dx.realisedIntervals().size() + " realised interval(s) fitted; "
                + "pooled p90 " + dx.pooled().p90Days() + " day(s).");

        return new Computation(day, route, requested, downgraded, downgradeReason, counts,
                restrictedBand, unobservedBand, combined, pointEstimate, anchorsSufficient, round4(anchorCoverage),
                coverageFailure, correlation, assumptions, tightening, diagnostics, dx, framework, projection);
    }

    public Computation compute(int day, EstimateRoute requested) {
        return compute(day, requested, DelayCalibrationService.DEFAULT_WINDOW_DAYS);
    }

    // ------------------------------------------------ bands

    /**
     * Known-restricted band: these subjects are known and have a finite local
     * denominator, so their rate is a genuine bound. Lower bound is the events
     * actually known locally among restricted fragments (stated as a rate); upper
     * adds a conservative +1 per restricted subject to that floor, mirroring a
     * local partial-identification interval.
     */
    private RateBand restrictedBand(LedgerCounts c, int restrictedEvents) {
        if (c.knownRestricted() == 0) {
            return new RateBand(0, 0, "No consent-restricted fragments at this instant.");
        }
        double lower = 100.0 * restrictedEvents / c.knownRestricted();
        double upper = 100.0 * (restrictedEvents + c.knownRestricted()) / c.knownRestricted();
        return new RateBand(round2(lower), round2(upper),
                "Local finite-denominator interval over " + c.knownRestricted()
                        + " consent-restricted subject(s); their " + restrictedEvents
                        + " known event(s) are bounded locally and NOT treated as unobserved.");
    }

    /**
     * Unobserved sensitivity band, built over the strictly-unobserved population
     * only. The floor is the <b>audit finding rate</b> (serious events found per
     * independently audited chart) plus any independent anchor that landed inside
     * the unobserved region; the top is the pre-declared clinical upper limit. If
     * the audit evidence already exceeds the declared ceiling, the ceiling is
     * raised to the evidence and the fact is disclosed — the limit is a prior, not
     * a cap on what audits actually found.
     */
    private RateBand unobservedBand(LedgerCounts c, int independentAnchorEventsInUnobserved,
                                    double auditFindingRate) {
        int u = c.unobserved();
        if (u == 0) {
            return new RateBand(0, 0, "No strictly-unobserved fragments; the global plane is complete for observable strata.");
        }
        double declaredUpper = declaredUpper();
        double anchorRate = (double) independentAnchorEventsInUnobserved / u;
        double rate = Math.max(anchorRate, auditFindingRate);
        double lower = 100.0 * rate;
        double upper = 100.0 * Math.max(declaredUpper, rate);
        String basis = "Sensitivity band over " + u + " strictly-unobserved subject(s): floor from the audit finding "
                + "rate (" + round4(auditFindingRate) + "/chart) and independent anchors ("
                + independentAnchorEventsInUnobserved + "), ceiling from the pre-declared clinical limit "
                + declaredUpper + "/subject. No representativeness assumed.";
        if (rate > declaredUpper) {
            basis += " The declared ceiling was raised to the audit evidence (" + round4(rate) + ").";
        }
        return new RateBand(round2(lower), round2(upper), basis);
    }

    private RateBand combine(EstimateRoute route, LedgerCounts c, RateBand restricted, RateBand unobserved,
                             int visibleEvents, DelayCalibrationService.Diagnostics dx,
                             CoverageFailure coverageFailure) {
        if (c.denominator() == 0) {
            return new RateBand(0, 0, "Empty denominator.");
        }
        double visibleRate = 100.0 * visibleEvents / c.denominator();
        // pending arrivals are known people expected to arrive; only the strictly-unobserved
        // mass carries the sensitivity band, so weight it by that count alone.
        double unobservedWeight = (double) c.unobserved() / c.denominator();
        if (route == EstimateRoute.PARTIAL_BOUNDS) {
            // global visible is the floor; restricted + unobserved upper contributes the ceiling.
            double lower = visibleRate;
            double upper = visibleRate + unobserved.upper() * unobservedWeight
                    + restricted.upper() * ((double) c.knownRestricted() / c.denominator());
            if (coverageFailure.active()) {
                // escalate: the upper must widen by the full declared clinical limit over the silent site
                upper = Math.max(upper, visibleRate + unobserved.upper());
            }
            return new RateBand(round2(lower), round2(upper),
                    "Non-parametric bounds: visible rate is the floor; restricted (local) + unobserved (sensitivity) "
                            + "mass widens the ceiling. Conservative when anchors are scarce.");
        }
        // capture model: shrink the upper by the calibrated recoverable fraction; never below the visible floor
        double recovered = dx.pooled().recoverableFraction();
        double lower = visibleRate;
        double upper = visibleRate * (1.0 + (1.0 - recovered)) + unobserved.lower() * unobservedWeight;
        return new RateBand(round2(Math.min(lower, upper)), round2(Math.max(lower, upper)),
                "Delay/capture model: upper shrunk by calibrated recoverable fraction " + round2(recovered)
                        + "; requires ongoing validation of source independence and site migration.");
    }

    // ------------------------------------------------ coverage failure & correlation

    private CoverageFailure detectCoverageFailure(int day, ObservabilityLedgerService.Ledger led) {
        List<String> conditions = new ArrayList<>();
        List<Site> sites = exposureRepo.findByExposedTrueOrderByStartedOnAsc().stream()
                .map(e -> e.getSubject().getSite()).distinct().toList();
        String hitSite = null;
        String detail = null;
        for (Site site : sites) {
            if (site.getClosureDay() == null || day <= site.getClosureDay()) {
                continue;
            }
            Instant closureAt = clock.dayStart(eligibility.study(), site.getClosureDay());
            // dispensing activity observed at or after closure, yet no safety return ever arrived
            long dispensingAfterClosure = sourceRepo.findAllByOrderByObservedOnAsc().stream()
                    .filter(s -> s.getSubject().getSite().getCode().equals(site.getCode()))
                    .filter(s -> s.getSourceKind() == SourceKind.DISPENSING)
                    .filter(s -> !s.getObservedOn().isBefore(closureAt))
                    .count();
            // any safety return (arrived SAE) at all for this site?
            long arrivedSaes = saeRepo.findAllByOrderByOccurredAtAsc().stream()
                    .filter(s -> s.getSubject().getSite().getCode().equals(site.getCode()))
                    .filter(SaeRecord::isArrived).count();
            long daysSilent = day + 1L - (long) site.getClosureDay();
            if (dispensingAfterClosure > 0 && arrivedSaes == 0 && daysSilent > 10) {
                hitSite = site.getCode();
                detail = "Site " + site.getCode() + " closed on day " + site.getClosureDay() + " but shows "
                        + dispensingAfterClosure + " dispensing record(s) and 0 safety returns across "
                        + daysSilent + " day(s) of silence.";
                conditions.add("Coverage failure is NOT explainable by the delay model: a closed site with dispensing "
                        + "activity but no returns for " + daysSilent + " days exceeds any calibrated return interval.");
                conditions.add("Condition to widen the audit: independently re-audit the closed centre's dispensing "
                        + "batch and safety register before any finite estimate is published.");
                conditions.add("Condition to pause trend conclusions: do not attribute the silence to a low event rate; "
                        + "treat the global rate as a lower bound until returns or an audit resolves the gap.");
            }
        }
        return new CoverageFailure(hitSite != null, hitSite, detail, conditions);
    }

    private Correlation detectCorrelation() {
        List<String> detail = new ArrayList<>();
        boolean present = false;
        for (ObservationSource s : sourceRepo.findAllByOrderByObservedOnAsc()) {
            if (s.getCorrelatedWith() != null) {
                present = true;
                detail.add("Source " + s.getSourceKind() + " (" + s.getSourceRef() + ") shares a manual register with "
                        + s.getCorrelatedWith() + "; captures are correlated.");
            }
        }
        for (AnchorRecord a : anchorRepo.findAllByOrderByObservedOnAsc()) {
            if (a.isCorrelated()) {
                present = true;
                detail.add("Anchor " + a.getAuditCode() + " is keyed from the shared manual safety register; "
                        + "it is correlated, not independent.");
            }
        }
        if (present) {
            detail.add("PROHIBITED: narrowing the interval by treating these captures as independent "
                    + "(capture-recapture) is disallowed for correlated sources.");
        }
        return new Correlation(present, detail.stream().distinct().toList());
    }

    private boolean hasKind(SourceKind kind) {
        return sourceRepo.findAllByOrderByObservedOnAsc().stream().anyMatch(s -> s.getSourceKind() == kind);
    }

    private double declaredUpper() {
        return periodRepo.findAllByOrderByStartsOnAsc().stream()
                .map(TreatmentPeriod::getDeclaredUpper).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0.30);
    }

    // ------------------------------------------------ assumptions & actions

    private List<Assumption> assumptions(EstimateRoute route, EstimateRoute requested, Correlation correlation,
                                         CoverageFailure cf, double anchorCoverage) {
        List<Assumption> out = new ArrayList<>();
        out.add(new Assumption("NO_REPRESENTATIVENESS",
                "The unobserved region is NOT assumed to resemble the observed region; it is bounded, not imputed.", true));
        out.add(new Assumption("RESTRICTED_BOUNDED_LOCALLY",
                "Consent-restricted subjects are known persons with a finite local denominator; their rate is bounded "
                        + "locally and never mixed into the unobserved rate.", true));
        out.add(new Assumption("ANCHORS_INDEPENDENT",
                "Only independent anchors raise the floor of the unobserved band; correlated anchors do not.", true));
        if (route == EstimateRoute.DELAY_CAPTURE_MODEL) {
            out.add(new Assumption("DELAY_STATIONARY",
                    "The realised return-interval distribution predicts future arrivals; requires continuous "
                            + "validation of source correlation, site migration and under-reporting.", false));
            out.add(new Assumption("SOURCES_INDEPENDENT",
                    "Schedule, dispensing and hotline captures are conditionally independent after calibration.", false));
        } else if (requested == EstimateRoute.DELAY_CAPTURE_MODEL) {
            out.add(new Assumption("MODEL_REFUSED",
                    "A capture/delay model was requested but refused; the bounds route is shown instead so that no "
                            + "evidence-free narrowing is published.", true));
        }
        if (correlation.present()) {
            out.add(new Assumption("CORRELATED_SOURCES",
                    "Two or more sources share a manual register; they are treated as one correlated source.", true));
        }
        if (cf.active()) {
            out.add(new Assumption("COVERAGE_FAILURE_ESCALATED",
                    "Closed-site silence with dispensing activity is escalated as a coverage failure, not modelled as delay.", true));
        }
        if (anchorCoverage < 0.20) {
            out.add(new Assumption("ANCHORS_SCARCE",
                    "Independent anchor coverage is below 20%; the unobserved band cannot be narrowed and stays at the "
                            + "pre-declared clinical limit.", true));
        }
        return out;
    }

    private List<TighteningAction> tightening(ObservabilityLedgerService.Ledger led, LedgerCounts c,
                                               Correlation correlation, CoverageFailure cf,
                                               DelayCalibrationService.Diagnostics dx) {
        List<TighteningAction> out = new ArrayList<>();
        if (c.knownPending() > 0) {
            out.add(new TighteningAction("Complete the " + c.knownPending()
                    + " pending return(s) within the expected window",
                    "Moves fragments from KNOWN_PENDING_ARRIVAL to INCLUDED, shrinking the unobserved mass."));
        }
        if (c.unobserved() > 0) {
            out.add(new TighteningAction("Widen centre audit sampling over the " + c.unobserved()
                    + " anchor-less fragment(s)",
                    "Adds independent anchors, raising the unobserved floor and tightening the upper band."));
        }
        if (cf.active()) {
            out.add(new TighteningAction("Re-audit the closed centre " + cf.siteCode() + "'s dispensing batch and safety register",
                    "Determines whether the silence is real or a coverage failure; until resolved the rate stays a lower bound."));
        }
        if (correlation.present()) {
            out.add(new TighteningAction("Separate the shared manual register into independent electronic extracts",
                    "Once hotline and audit are captured independently, a capture model may legitimately narrow the band."));
        }
        if (!dx.warnings().isEmpty()) {
            out.add(new TighteningAction("Collect more late returns at under-calibrated strata",
                    "Each currently-uncalibrated stratum must reach >=3 realised intervals before its p90 is trusted."));
        }
        if (out.isEmpty()) {
            out.add(new TighteningAction("No further tightening indicated at this instant",
                    "The global plane is complete; continue routine returns to keep the interval current."));
        }
        return out;
    }

    // ------------------------------------------------ persistence (versioned)

    @Transactional
    public ObservabilityEstimate materialize(Computation c, String versionGroup, String actor) {
        int nextVersion = estimateRepo.findByVersionGroupOrderByVersionAsc(versionGroup).stream()
                .mapToInt(ObservabilityEstimate::getVersion).max().orElse(0) + 1;
        ObservabilityEstimate e = new ObservabilityEstimate();
        e.setCode(versionGroup + "-v" + nextVersion);
        e.setVersionGroup(versionGroup);
        e.setVersion(nextVersion);
        e.setAnalysisDay(c.analysisDay());
        e.setRoute(c.route());
        e.setRequestedRoute(c.requestedRoute());
        e.setDowngraded(c.downgraded());
        e.setDowngradeReason(c.downgradeReason());
        e.setGlobalVisibleCount(c.counts().globalVisible());
        e.setKnownRestrictedCount(c.counts().knownRestricted());
        e.setKnownPendingCount(c.counts().knownPending());
        e.setAuditFlaggedCount(c.counts().auditFlagged());
        e.setUnobservedCount(c.counts().unobserved());
        e.setDenominator(c.counts().denominator());
        e.setRestrictedLower(c.restricted().lower());
        e.setRestrictedUpper(c.restricted().upper());
        e.setUnobservedLower(c.unobserved().lower());
        e.setUnobservedUpper(c.unobserved().upper());
        e.setRangeLower(c.combined().lower());
        e.setRangeUpper(c.combined().upper());
        e.setPointEstimate(c.pointEstimate());
        e.setAnchorsSufficient(c.anchorsSufficient());
        e.setAnchorCoverage(c.anchorCoverage());
        e.setCoverageFailure(c.coverageFailure().active());
        e.setCoverageFailureDetail(c.coverageFailure().detail());
        e.setCorrelatedSources(c.correlation().present());
        e.setCorrelatedDetail(String.join(" | ", c.correlation().detail()));
        e.setAssumptionsJson(jsonAssumptions(c.assumptions()));
        e.setTighteningJson(jsonTightening(c.tightening()));
        e.setDiagnosticsJson(String.join("\n", c.diagnostics()));
        e.setGlobalSafe(true);
        e.setCreatedAt(Instant.now());
        e.setCreatedBy(actor);
        e = estimateRepo.save(e);
        audit.record(actor, "OBSERVABILITY_ESTIMATE_PUBLISHED", "ObservabilityEstimate", e.getCode(),
                "route=" + e.getRoute() + " range=[" + e.getRangeLower() + "," + e.getRangeUpper() + "]"
                        + (e.isDowngraded() ? " (downgraded: " + e.getDowngradeReason() + ")" : ""));
        return e;
    }

    public List<ObservabilityEstimate> versions() {
        return estimateRepo.findAllByOrderByCreatedAtDesc();
    }

    // ------------------------------------------------ serialisation helpers

    private String jsonAssumptions(List<Assumption> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Assumption a = list.get(i);
            sb.append("{\"code\":\"").append(esc(a.code())).append("\",\"statement\":\"").append(esc(a.statement()))
                    .append("\",\"testable\":").append(a.testable()).append("}");
            if (i < list.size() - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    private String jsonTightening(List<TighteningAction> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            TighteningAction a = list.get(i);
            sb.append("{\"action\":\"").append(esc(a.action())).append("\",\"effect\":\"").append(esc(a.expectedEffect()))
                    .append("\"}");
            if (i < list.size() - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
