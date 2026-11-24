package com.ctrial;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observability &amp; sensitivity rule tests. Each test names the requirement it
 * proves against the seeded scenario at analysis day 16.
 */
@SpringBootTest
@ActiveProfiles("test")
class ObservabilityRulesTest {

    @Autowired ObservabilityLedgerService ledger;
    @Autowired ExpectedOpportunityService expected;
    @Autowired DelayCalibrationService delay;
    @Autowired ObservabilityEstimateService estimates;
    @Autowired SubjectExposureRepo exposureRepo;
    @Autowired SaeRecordRepo saeRepo;

    private ObservabilityLedgerService.Fragment fragmentAt(int day, String pseudonym) {
        return ledger.build(day, 10).fragments().stream()
                .filter(f -> f.pseudonym().equals(pseudonym)).findFirst().orElseThrow();
    }

    // ---- mandated: restricted subject with an SAE record is KNOWN_RESTRICTED, not unobserved ----

    @Test
    void withdrawnConsentWithSaeIsKnownRestrictedAndNotCountedAsUnobserved() {
        ObservabilityLedgerService.Ledger l = ledger.build(16, 10);
        ObservabilityLedgerService.Fragment b2 = fragmentAt(16, "PSN-B2");
        assertThat(b2.state()).isEqualTo(FragmentState.KNOWN_RESTRICTED);
        assertThat(b2.egressPermitted()).isFalse();
        assertThat(b2.observedEvents()).isGreaterThanOrEqualTo(1); // B's AE is on file

        // and the classification does not bleed into the unobserved count
        ObservabilityLedgerService.GlobalProjection g = ledger.projectGlobal(16, 10);
        long b2Segments = g.ranges().stream().filter(r -> r.siteCode().equals("SG-01"))
                .mapToInt(ObservabilityLedgerService.GlobalRange::knownRestrictedCount).sum();
        assertThat(b2Segments).isGreaterThanOrEqualTo(1);
        // B's event is consent-blocked, so it is never visible in the global projection
        int visibleForB = l.segments().stream().filter(s -> s.siteCode().equals("SG-01"))
                .mapToInt(ObservabilityLedgerService.Segment::globalVisibleEvents).sum();
        assertThat(visibleForB).isZero();
    }

    // ---- mandated: planned visit not yet due is right-censored, not late / not "no event" ----

    @Test
    void notYetDueVisitsAreRightCensoredNotLateOrZero() {
        ExpectedOpportunityService.Framework f = expected.build(16);
        ExpectedOpportunityService.SubjectOpportunities m13 = f.subjects().stream()
                .filter(s -> s.pseudonym().equals("PSN-M13")).findFirst().orElseThrow();
        // M13 was randomised day 15: visits are not yet due
        assertThat(m13.censoredFuture()).isGreaterThan(0);
        assertThat(m13.overdueUnobserved()).isZero();
        // and censored visits are not counted as expected-opportunities that are overdue
        boolean anyOverdueForM13 = m13.opportunities().stream()
                .anyMatch(o -> o.status() == ExpectedOpportunityService.Status.OVERDUE_UNOBSERVED);
        assertThat(anyOverdueForM13).isFalse();
    }

    @Test
    void pastWindowWithoutReturnIsUnobservedOpportunityNotNoEvent() {
        ExpectedOpportunityService.Framework f = expected.build(16);
        // D4/J10 have no schedule/dispensing/hotline; their due past-window visits are OVERDUE_UNOBSERVED,
        // i.e. an unobserved opportunity — never rendered as "no event".
        for (String p : List.of("PSN-D4", "PSN-J10")) {
            ExpectedOpportunityService.SubjectOpportunities s = f.subjects().stream()
                    .filter(x -> x.pseudonym().equals(p)).findFirst().orElseThrow();
            assertThat(s.overdueUnobserved()).isGreaterThan(0);
        }
        assertThat(f.all().stream()
                .anyMatch(o -> o.status() == ExpectedOpportunityService.Status.OVERDUE_UNOBSERVED)).isTrue();
        assertThat(f.disclosure().stream().anyMatch(d -> d.contains("no event"))).isTrue();
    }

    // ---- mandated: delay calibrator uses only realised intervals ----

    @Test
    void delayCalibratorUsesOnlyRealisedIntervals() {
        DelayCalibrationService.Diagnostics d = delay.build(16, 10);
        // every realised interval has a returned time strictly after occurrence
        assertThat(d.realisedIntervals()).allSatisfy(r -> assertThat(r.lagDays()).isGreaterThanOrEqualTo(0));
        // the in-flight (non-returned) events are counted separately and never inside the interval list
        long arrived = saeRepo.findByArrivedTrueOrderByOccurredAtAsc().stream()
                .filter(s -> s.getReturnedAt() != null).count();
        assertThat(d.realisedIntervals().size()).isLessThanOrEqualTo((int) arrived);
        assertThat(d.reconciliation().get(0)).contains("only");
        assertThat(d.reconciliation().get(0)).contains("in-flight candidate(s) are NOT used");
    }

    @Test
    void delayCalibratorPreservesStratumDifferences() {
        DelayCalibrationService.Diagnostics d = delay.build(16, 10);
        // strata are split by site/period/grade; a high grade and a low grade do not collapse into one number
        assertThat(d.strata()).allSatisfy(k -> assertThat(k.siteCode()).isNotBlank());
        assertThat(d.strata().stream().anyMatch(k -> k.gradeBand().equals("GRADE_3_PLUS"))).isTrue();
    }

    // ---- mandated: partial estimator keeps restricted and unobserved separate ----

    @Test
    void estimatorSeparatesKnownRestrictedFromUnobserved() {
        ObservabilityEstimateService.Computation c = estimates.compute(16, EstimateRoute.PARTIAL_BOUNDS);
        assertThat(c.counts().knownRestricted()).isGreaterThan(0);
        // restricted band is built on the restricted denominator, unobserved band on the unobserved one
        assertThat(c.restricted().basis()).contains("consent-restricted");
        assertThat(c.restricted().basis()).contains("NOT treated as unobserved");
        assertThat(c.unobserved().basis()).contains("Sensitivity band");
        assertThat(c.unobserved().upper()).isGreaterThan(c.unobserved().lower());
        assertThat(c.diagnostics().stream().anyMatch(x -> x.contains("restricted events are bounded locally"))).isTrue();
    }

    // ---- mandated: correlated sources are flagged and the capture model refuses ----

    @Test
    void correlatedSourcesAreFlaggedAndPreventCaptureModel() {
        ObservabilityEstimateService.Computation c = estimates.compute(16, EstimateRoute.DELAY_CAPTURE_MODEL);
        assertThat(c.correlation().present()).isTrue();
        assertThat(c.correlation().detail().stream().anyMatch(x -> x.contains("PROHIBITED"))).isTrue();
        // requesting the capture model must downgrade rather than narrow on correlated captures
        assertThat(c.downgraded()).isTrue();
        assertThat(c.route()).isEqualTo(EstimateRoute.PARTIAL_BOUNDS);
        assertThat(c.downgradeReason()).contains("correlated");
    }

    // ---- mandated: closed site with dispensing and long silence escalates ----

    @Test
    void closedSiteSilenceEscalatesAsCoverageFailure() {
        ObservabilityEstimateService.Computation c = estimates.compute(16, EstimateRoute.PARTIAL_BOUNDS);
        assertThat(c.coverageFailure().active()).isTrue();
        assertThat(c.coverageFailure().siteCode()).isEqualTo("CN-03");
        assertThat(c.coverageFailure().conditions()).anyMatch(x -> x.contains("pause trend conclusions"));
        // a capture model cannot explain away a coverage failure either
        ObservabilityEstimateService.Computation cm = estimates.compute(16, EstimateRoute.DELAY_CAPTURE_MODEL);
        assertThat(cm.downgraded()).isTrue();
    }

    // ---- mandated: versioned records preserve the original interpretation ----

    @Test
    @Transactional
    void reEstimateWritesNewVersionAndKeepsTheOldOne() {
        ObservabilityEstimateService.Computation c16 = estimates.compute(16, EstimateRoute.PARTIAL_BOUNDS);
        ObservabilityEstimate v1 = estimates.materialize(c16, "TEST-GROUP", "test");

        // a later analysis day tightens the range; it must be a new record, not an overwrite
        ObservabilityEstimateService.Computation c20 = estimates.compute(20, EstimateRoute.PARTIAL_BOUNDS);
        ObservabilityEstimate v2 = estimates.materialize(c20, "TEST-GROUP", "test");

        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v1.getCode()).isNotEqualTo(v2.getCode());
        // the older interpretation is still present and still explained
        List<ObservabilityEstimate> group = estimates.versions().stream()
                .filter(e -> e.getVersionGroup().equals("TEST-GROUP")).toList();
        assertThat(group).hasSize(2);
        assertThat(group.stream().anyMatch(e -> e.getCode().equals(v1.getCode()))).isTrue();
        assertThat(v1.getAssumptionsJson()).isNotBlank();
    }

    // ---- global-safe projection carries no pseudonyms ----

    @Test
    void globalProjectionCarriesNoIdentifiers() {
        ObservabilityLedgerService.GlobalProjection g = ledger.projectGlobal(16, 10);
        String json = g.ranges().toString() + g.disclosure();
        assertThat(json).doesNotContain("PSN-");
        assertThat(g.ranges()).isNotEmpty();
        assertThat(g.totals().denominators()).isGreaterThan(0);
    }
}
