package com.ctrial.config;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Seeds the observability &amp; sensitivity scenario on top of the acceptance
 * cohort. It deliberately produces, in one data set, every state the ledger and
 * the estimator must distinguish:
 *
 * <pre>
 *  B2  withdrew global egress D12 but has a serious AE on file  -> KNOWN_RESTRICTED (and its SAE is NOT globally visible)
 *  A1, I9  China-only consent                                    -> KNOWN_RESTRICTED (no event)
 *  C3, F6  fully observed, no event                             -> INCLUDED (genuine zero)
 *  E5  returned serious AE in-window                            -> INCLUDED (with event)
 *  G7  serious AE still inside the return window                -> KNOWN_PENDING_ARRIVAL
 *  H8  past-window AE found by an independent audit             -> AUDIT_FLAGGED_MISSING
 *  D4, J10  no schedule/dispensing/hotline, no anchor           -> UNOBSERVED_NO_ANCHOR
 *  K11, L12 closed CN-03 with dispensing but no returns        -> UNOBSERVED + coverage failure
 *  M13 randomised D15 -> all visits right-censored              -> CENSORED_FUTURE (not late, not "no event")
 *  B2  hotline + audit share one manual register               -> CORRELATED (capture model must refuse)
 * </pre>
 */
@Component
@Order(20)
public class ObservabilitySeeder implements ApplicationRunner {

    private final SubjectRepo subjectRepo;
    private final SiteRepo siteRepo;
    private final TreatmentPeriodRepo periodRepo;
    private final SubjectExposureRepo exposureRepo;
    private final PlannedVisitRepo visitRepo;
    private final ObservationSourceRepo sourceRepo;
    private final SaeRecordRepo saeRepo;
    private final AnchorRecordRepo anchorRepo;
    private final AuditFindingRepo auditRepo;
    private final boolean seed;

    public ObservabilitySeeder(SubjectRepo subjectRepo, SiteRepo siteRepo, TreatmentPeriodRepo periodRepo,
                               SubjectExposureRepo exposureRepo, PlannedVisitRepo visitRepo,
                               ObservationSourceRepo sourceRepo, SaeRecordRepo saeRepo, AnchorRecordRepo anchorRepo,
                               AuditFindingRepo auditRepo,
                               @Value("${app.demo.seed-on-startup:true}") boolean seed) {
        this.subjectRepo = subjectRepo;
        this.siteRepo = siteRepo;
        this.periodRepo = periodRepo;
        this.exposureRepo = exposureRepo;
        this.visitRepo = visitRepo;
        this.sourceRepo = sourceRepo;
        this.saeRepo = saeRepo;
        this.anchorRepo = anchorRepo;
        this.auditRepo = auditRepo;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seed || periodRepo.count() > 0) {
            return;
        }
        Site cn1 = siteRepo.findByCode("CN-01").orElse(null);
        Site cn2 = siteRepo.findByCode("CN-02").orElse(null);
        Site sg = siteRepo.findByCode("SG-01").orElse(null);
        if (cn1 == null || cn2 == null || sg == null || subjectRepo.findByPseudonym("PSN-A1").isEmpty()) {
            return; // demo cohort not present
        }

        // ---- closed centre: dispensing activity but no safety returns ----
        Site cn3 = new Site("CN-03", "Chengdu Second People's Hospital", "CN", "CN-HUB-3", true);
        cn3.setClosureDay(4);
        cn3 = siteRepo.save(cn3);

        TreatmentPeriod induction = periodRepo.save(new TreatmentPeriod("INDUCTION", "Induction (weeks 1-4)",
                DemoSeeder.day(1), DemoSeeder.day(29), 0.20, 0.30));
        TreatmentPeriod maintenance = periodRepo.save(new TreatmentPeriod("MAINTENANCE", "Maintenance (weeks 5+)",
                DemoSeeder.day(29), null, 0.10, 0.20));

        // ---- planned-visit templates (drive the expected-opportunity framework) ----
        template(induction.getCode(), "V7", "Cycle 1 visit", 7, 3, true);
        template(induction.getCode(), "V14", "Cycle 2 visit", 14, 3, true);
        template(induction.getCode(), "V21", "Cycle 3 visit", 21, 3, false);
        template(induction.getCode(), "V28", "End of induction", 28, 3, true);
        template(maintenance.getCode(), "M14", "Maintenance visit 1", 14, 3, true);
        template(maintenance.getCode(), "M28", "Maintenance visit 2", 28, 3, false);
        template(maintenance.getCode(), "M42", "Maintenance visit 3", 42, 3, true);
        template(maintenance.getCode(), "M56", "Maintenance visit 4", 56, 3, false);

        Subject a1 = s("PSN-A1");
        Subject b2 = s("PSN-B2");
        Subject c3 = s("PSN-C3");
        Subject d4 = s("PSN-D4");

        Subject e5 = synth("PSN-E5", cn1, "CHN-001-0105");
        Subject f6 = synth("PSN-F6", cn1, "CHN-001-0106");
        Subject g7 = synth("PSN-G7", cn1, "CHN-001-0107");
        Subject h8 = synth("PSN-H8", cn1, "CHN-001-0108");
        Subject i9 = synth("PSN-I9", cn1, "CHN-001-0109");
        Subject j10 = synth("PSN-J10", cn2, "CHN-002-0110");
        Subject k11 = synth("PSN-K11", cn3, "CHN-003-0111");
        Subject l12 = synth("PSN-L12", cn3, "CHN-003-0112");
        Subject m13 = synth("PSN-M13", cn1, "CHN-001-0113");

        // ---- exposures ----
        expo(a1, induction, RiskStratum.HIGH, DemoSeeder.day(1), false);
        expo(b2, induction, RiskStratum.HIGH, DemoSeeder.day(1), false); // withdrew global egress, has SAE
        expo(c3, induction, RiskStratum.STANDARD, DemoSeeder.day(1), true);
        expo(d4, induction, RiskStratum.STANDARD, DemoSeeder.day(1), true);
        expo(e5, induction, RiskStratum.HIGH, DemoSeeder.day(1), true);
        expo(f6, induction, RiskStratum.STANDARD, DemoSeeder.day(1), true);
        expo(g7, maintenance, RiskStratum.HIGH, DemoSeeder.day(1), true);
        expo(h8, maintenance, RiskStratum.STANDARD, DemoSeeder.day(1), true);
        expo(i9, induction, RiskStratum.HIGH, DemoSeeder.day(1), false);
        expo(j10, induction, RiskStratum.STANDARD, DemoSeeder.day(1), true);
        expo(k11, induction, RiskStratum.HIGH, DemoSeeder.day(1), true);
        expo(l12, induction, RiskStratum.STANDARD, DemoSeeder.day(1), true);
        expo(m13, induction, RiskStratum.HIGH, DemoSeeder.day(15), true);

        // ---- observation sources ----
        // fully observed subjects (schedule + dispensing + hotline), no event -> INCLUDED zero
        observedThrough(c3, induction, 16, true, false);
        observedThrough(f6, induction, 16, true, false);
        // E5 has a returned event; give it only a schedule record
        source(e5, SourceKind.SCHEDULE, "VIS-E5-V7", DemoSeeder.day(8), 7, false, null);
        // B2: hotline keyed from the shared manual register (correlated with audit)
        source(b2, SourceKind.SCHEDULE, "VIS-B2-V7", DemoSeeder.day(8), 7, false, null);
        source(b2, SourceKind.HOTLINE, "HT-B2-D11", DemoSeeder.day(11), null, true, SourceKind.AUDIT);
        // G7: partial schedule; return still in flight
        source(g7, SourceKind.SCHEDULE, "VIS-G7-M14", DemoSeeder.day(15), 14, false, null);
        // H8: schedule record present but the AE was found only by audit
        source(h8, SourceKind.SCHEDULE, "VIS-H8-M14", DemoSeeder.day(15), 14, false, null);
        // CN-03: ad-hoc dispensing AFTER closure, with no safety return (coverage failure)
        source(k11, SourceKind.DISPENSING, "DISP-K11-D5", DemoSeeder.day(5), null, false, null);
        source(l12, SourceKind.DISPENSING, "DISP-L12-D6", DemoSeeder.day(6), null, false, null);

        // ---- serious adverse events ----
        // E5: returned in 2 days -> INCLUDED with an event
        sae(e5, "AE-E5-01", DemoSeeder.hour(9, 10), DemoSeeder.hour(11, 10), 3, true, true, null);
        // B2: returned, but consent forbids egress -> KNOWN_RESTRICTED (event known, not visible)
        sae(b2, "AE-B2-01", DemoSeeder.hour(11, 10), DemoSeeder.hour(12, 10), 3, true, false, null);
        // G7: occurred D14, not yet returned, inside the 10-day window -> KNOWN_PENDING_ARRIVAL
        saeOpen(g7, "AE-G7-01", DemoSeeder.hour(14, 12), 3);
        // H8: occurred D1, never returned, past the window, found by independent audit -> AUDIT_FLAGGED_MISSING
        saeOpen(h8, "AE-H8-01", DemoSeeder.hour(1, 12), 3);

        // ---- anchors ----
        anchor(h8, "AUD-CN01-01", SourceKind.AUDIT, false, 1, DemoSeeder.hour(16, 9), RiskStratum.STANDARD, "MAINTENANCE");
        anchor(g7, "AUD-CN01-02", SourceKind.AUDIT, false, 1, DemoSeeder.hour(16, 9), RiskStratum.HIGH, "MAINTENANCE");
        // correlated anchor: keyed from the shared manual safety register
        anchor(b2, "AUD-SG01-01", SourceKind.HOTLINE, true, 1, DemoSeeder.hour(16, 9), RiskStratum.HIGH, "INDUCTION");

        // ---- audit findings (sampled charts drive audit opportunities + coverage) ----
        audit(cn1, "AUD-CN01-01", AuditFindingKind.LATE_UNTREATED_SAE,
                "Chart review found a grade-3 AE on day 1 that never reached the platform.", false, 1, 0.25,
                "PSN-H8", DemoSeeder.hour(16, 9));
        audit(cn1, "AUD-CN01-02", AuditFindingKind.ROUTINE_SAMPLE,
                "Routine 25% chart sample (includes G7).", false, 0, 0.25, "PSN-G7,PSN-C3,PSN-F6",
                DemoSeeder.hour(16, 9));
        audit(cn1, "AUD-CN01-03", AuditFindingKind.DISPENSING_WITHOUT_RETURN,
                "Dispensing register cross-check.", false, 0, 0.10, "", DemoSeeder.hour(16, 9));
    }

    // ------------------------------------------------------------------ helpers

    private void template(String period, String code, String label, int offset, int window, boolean dispensing) {
        visitRepo.save(new PlannedVisitTemplate(period, code, label, offset, window, dispensing));
    }

    private Subject s(String pseudonym) {
        return subjectRepo.findByPseudonym(pseudonym).orElseThrow();
    }

    private Subject synth(String pseudonym, Site site, String localIdentity) {
        return subjectRepo.save(new Subject(pseudonym, site, site.getRegion(), localIdentity));
    }

    private void expo(Subject s, TreatmentPeriod p, RiskStratum stratum, Instant start, boolean egress) {
        exposureRepo.save(new SubjectExposure(s, p, stratum, start, null, true, egress));
    }

    /**
     * Create schedule + dispensing + (optionally) hotline sources for every visit
     * template due on or before {@code throughDay}, plus a correlated hotline when
     * requested. {@code correlatedHotline} keys the hotline from the audit register.
     */
    private void observedThrough(Subject s, TreatmentPeriod p, int throughDay, boolean hotline,
                                 boolean correlatedHotline) {
        for (PlannedVisitTemplate t : visitRepo.findByPeriodCodeOrderByDayOffsetAsc(p.getCode())) {
            // randomisation is study day 1; a visit with offset N is due on day N+1
            Instant due = DemoSeeder.day(t.getDayOffset() + 1);
            if (dayOf(due) > throughDay) {
                continue;
            }
            source(s, SourceKind.SCHEDULE, "VIS-" + s.getPseudonym() + "-" + t.getVisitCode(), due,
                    t.getDayOffset(), false, null);
            if (t.isDispensing()) {
                source(s, SourceKind.DISPENSING, "DISP-" + s.getPseudonym() + "-" + t.getVisitCode(), due,
                        t.getDayOffset(), false, null);
            }
        }
        if (hotline) {
            source(s, SourceKind.HOTLINE, "HT-" + s.getPseudonym() + "-D10", DemoSeeder.day(10), null,
                    correlatedHotline, correlatedHotline ? SourceKind.AUDIT : null);
        }
    }

    private int dayOf(Instant instant) {
        return (int) Math.floorDiv(instant.toEpochMilli() - DemoSeeder.day(1).toEpochMilli(), 86_400_000L) + 1;
    }

    private void source(Subject s, SourceKind kind, String ref, Instant at, Integer plannedDay,
                        boolean manual, SourceKind correlatedWith) {
        sourceRepo.save(new ObservationSource(s, kind, ref, at, plannedDay, manual, correlatedWith));
    }

    private void sae(Subject s, String stableId, Instant occurred, Instant returned, int grade, boolean serious,
                     boolean egress, String anchorCode) {
        saeRepo.save(new SaeRecord(s, stableId, occurred, returned, grade, serious, SourceKind.SCHEDULE,
                true, egress, anchorCode));
    }

    private void saeOpen(Subject s, String stableId, Instant occurred, int grade) {
        saeRepo.save(new SaeRecord(s, stableId, occurred, null, grade, true, SourceKind.AUDIT,
                false, true, null));
    }

    private void anchor(Subject s, String auditCode, SourceKind kind, boolean correlated, int witnessed,
                        Instant at, RiskStratum stratum, String period) {
        anchorRepo.save(new AnchorRecord(s, auditCode, kind, correlated, witnessed, at, stratum, period));
    }

    private void audit(Site site, String code, AuditFindingKind kind, String detail, boolean correlated,
                       int serious, double fraction, String sampledSubjects, Instant at) {
        auditRepo.save(new AuditFinding(site, code, kind, detail, correlated, serious, fraction,
                sampledSubjects, at));
    }
}
