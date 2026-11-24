package com.ctrial.service;

import com.ctrial.model.*;
import com.ctrial.model.Enums.SourceKind;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Expected-event framework.
 *
 * <p>Instantiates, per subject, the observation <em>opportunities</em> that
 * <b>should</b> have appeared: randomisation, each protocol-planned visit, each
 * planned drug dispensing, and each audit-sampling look. Opportunities are
 * generated from the randomisation instant, the visit templates, the site's
 * closure day and the declared audit samples — never from the events that were
 * actually reported.
 *
 * <p>Two rules are non-negotiable here:
 * <ul>
 *   <li>An opportunity whose due date has not yet arrived is <b>right-censored</b>
 *       ({@link Status#CENSORED_FUTURE}); it is neither "late" nor "no event".</li>
 *   <li>A past-window opportunity with no return is reported as
 *       {@link Status#OVERDUE_UNOBSERVED} — an <em>unobserved opportunity</em>,
 *       never converted into "no event occurred".</li>
 * </ul>
 */
@Service
public class ExpectedOpportunityService {

    private final SubjectExposureRepo exposureRepo;
    private final PlannedVisitRepo visitRepo;
    private final ObservationSourceRepo sourceRepo;
    private final SaeRecordRepo saeRepo;
    private final AuditFindingRepo auditRepo;
    private final StudyClock clock;
    private final EligibilityService eligibility;

    public ExpectedOpportunityService(SubjectExposureRepo exposureRepo, PlannedVisitRepo visitRepo,
                                      ObservationSourceRepo sourceRepo, SaeRecordRepo saeRepo,
                                      AuditFindingRepo auditRepo, StudyClock clock,
                                      EligibilityService eligibility) {
        this.exposureRepo = exposureRepo;
        this.visitRepo = visitRepo;
        this.sourceRepo = sourceRepo;
        this.saeRepo = saeRepo;
        this.auditRepo = auditRepo;
        this.clock = clock;
        this.eligibility = eligibility;
    }

    public enum Kind { RANDOMIZATION, VISIT, DISPENSING, AUDIT }

    public enum Status {
        /** Due before the analysis instant and satisfied by a source record. */
        OBSERVED,
        /** Due, but not yet observed; still inside the return window. */
        IN_WINDOW,
        /** Past the return window with no observation: an unobserved opportunity, NOT "no event". */
        OVERDUE_UNOBSERVED,
        /** Due date is after the analysis instant: right-censored, not late. */
        CENSORED_FUTURE,
        /** The site had already closed before this opportunity was due. */
        CLOSED_OUT
    }

    public record Opportunity(String pseudonym, String siteCode, String periodCode, String riskStratum,
                              Kind kind, String ref, int studyDay, Instant dueAt, Instant windowEndAt,
                              Status status, Instant observedAt, String note) {
        public boolean countsAsOpportunity() {
            return status == Status.OBSERVED || status == Status.IN_WINDOW
                    || status == Status.OVERDUE_UNOBSERVED;
        }
    }

    public record SubjectOpportunities(String pseudonym, String siteCode, String periodCode, String riskStratum,
                                       int expectedCount, int observed, int inWindow, int censoredFuture,
                                       int overdueUnobserved, int closedOut, List<Opportunity> opportunities) {
    }

    public record Framework(int analysisDay, Instant analysisAt, List<SubjectOpportunities> subjects,
                            List<Opportunity> all, Totals totals, List<String> disclosure) {
    }

    public record Totals(int expectedOpportunities, int observed, int inWindow, int censoredFuture,
                         int overdueUnobserved, int closedOut) {
    }

    @Transactional(readOnly = true)
    public Framework build(int day) {
        Study study = eligibility.study();
        Instant t = clock.analysisInstant(study, day);
        Map<Long, List<ObservationSource>> sourcesBySubject = new HashMap<>();
        for (ObservationSource s : sourceRepo.findAllByOrderByObservedOnAsc()) {
            sourcesBySubject.computeIfAbsent(s.getSubject().getId(), k -> new ArrayList<>()).add(s);
        }
        Map<Long, List<SaeRecord>> saesBySubject = new HashMap<>();
        for (SaeRecord s : saeRepo.findAllByOrderByOccurredAtAsc()) {
            saesBySubject.computeIfAbsent(s.getSubject().getId(), k -> new ArrayList<>()).add(s);
        }
        // audit-sampled subjects -> an audit look is itself an observation opportunity
        Map<String, List<AuditFinding>> auditsBySubject = new HashMap<>();
        for (AuditFinding af : auditRepo.findAllByOrderByObservedOnAsc()) {
            if (af.getSampledSubjects() == null || af.getSampledSubjects().isBlank()) {
                continue;
            }
            for (String p : af.getSampledSubjects().split(",")) {
                if (!p.isBlank()) {
                    auditsBySubject.computeIfAbsent(p.trim(), k -> new ArrayList<>()).add(af);
                }
            }
        }
        List<PlannedVisitTemplate> templates = visitRepo.findAllByOrderByDayOffsetAsc();

        List<SubjectOpportunities> subjects = new ArrayList<>();
        List<Opportunity> all = new ArrayList<>();
        for (SubjectExposure ex : exposureRepo.findByExposedTrueOrderByStartedOnAsc()) {
            Subject sub = ex.getSubject();
            Site site = sub.getSite();
            List<Opportunity> opps = new ArrayList<>();

            // 1) randomisation opportunity
            opps.add(new Opportunity(sub.getPseudonym(), site.getCode(), ex.getPeriod().getCode(),
                    ex.getRiskStratum().name(), Kind.RANDOMIZATION, "RAND", 0, ex.getStartedOn(), ex.getStartedOn(),
                    Status.OBSERVED, ex.getStartedOn(), "randomisation / first dose defines the denominator"));

            // 2) planned visits + planned dispensing from the period templates
            List<ObservationSource> sources = sourcesBySubject.getOrDefault(sub.getId(), List.of());
            for (PlannedVisitTemplate tp : templates) {
                if (!tp.getPeriodCode().equals(ex.getPeriod().getCode())) {
                    continue;
                }
                Instant due = ex.getStartedOn().plusSeconds((long) tp.getDayOffset() * 86_400L);
                Instant winEnd = due.plusSeconds((long) tp.getWindowDays() * 86_400L);
                int dueDay = clock.dayOf(study, due);
                boolean closed = site.getClosureDay() != null && dueDay > site.getClosureDay();
                Status st;
                Instant observedAt = null;
                String note;
                if (closed) {
                    st = Status.CLOSED_OUT;
                    note = "site closed on day " + site.getClosureDay() + " before this visit was due";
                } else if (due.isAfter(t)) {
                    st = Status.CENSORED_FUTURE;
                    note = "visit not yet due at the analysis instant — right-censored, not late and not 'no event'";
                } else {
                    ObservationSource hit = sources.stream()
                            .filter(s -> s.getSourceKind() == SourceKind.SCHEDULE)
                            .filter(s -> s.getPlannedDay() != null && s.getPlannedDay() == tp.getDayOffset())
                            .findFirst().orElse(null);
                    if (hit != null) {
                        st = Status.OBSERVED;
                        observedAt = hit.getObservedOn();
                        note = "schedule record present (" + hit.getSourceRef() + ")";
                    } else if (t.isBefore(winEnd)) {
                        st = Status.IN_WINDOW;
                        note = "due and inside the return window; no return yet — may still arrive";
                    } else {
                        st = Status.OVERDUE_UNOBSERVED;
                        note = "past the return window with no observation — an unobserved opportunity, not 'no event'";
                    }
                }
                opps.add(new Opportunity(sub.getPseudonym(), site.getCode(), ex.getPeriod().getCode(),
                        ex.getRiskStratum().name(), Kind.VISIT, tp.getVisitCode(), tp.getDayOffset(), due, winEnd,
                        st, observedAt, note));
                if (tp.isDispensing()) {
                    ObservationSource dispHit = sources.stream()
                            .filter(s -> s.getSourceKind() == SourceKind.DISPENSING)
                            .filter(s -> s.getPlannedDay() != null && s.getPlannedDay() == tp.getDayOffset())
                            .findFirst().orElse(null);
                    Status dst = closed ? Status.CLOSED_OUT
                            : due.isAfter(t) ? Status.CENSORED_FUTURE
                            : dispHit != null ? Status.OBSERVED
                            : t.isBefore(winEnd) ? Status.IN_WINDOW : Status.OVERDUE_UNOBSERVED;
                    opps.add(new Opportunity(sub.getPseudonym(), site.getCode(), ex.getPeriod().getCode(),
                            ex.getRiskStratum().name(), Kind.DISPENSING, "DISP-" + tp.getVisitCode(), tp.getDayOffset(),
                            due, winEnd, dst, dispHit == null ? null : dispHit.getObservedOn(),
                            "dispensing is an independent contact point for an opportunity"));
                }
            }

            // 3) dispensing recorded outside the template schedule (ad-hoc) is also an opportunity
            for (ObservationSource s : sources) {
                if (s.getSourceKind() == SourceKind.DISPENSING && s.getPlannedDay() == null) {
                    Status st = s.getObservedOn().isAfter(t) ? Status.CENSORED_FUTURE : Status.OBSERVED;
                    opps.add(new Opportunity(sub.getPseudonym(), site.getCode(), ex.getPeriod().getCode(),
                            ex.getRiskStratum().name(), Kind.DISPENSING, s.getSourceRef(),
                            clock.dayOf(study, s.getObservedOn()), s.getObservedOn(), s.getObservedOn(),
                            st, st == Status.OBSERVED ? s.getObservedOn() : null, "ad-hoc dispensing record"));
                }
            }

            // 4) audit-sampling look
            List<AuditFinding> myAudits = auditsBySubject.getOrDefault(sub.getPseudonym(), List.of());
            for (AuditFinding af : myAudits) {
                Status st = af.getObservedOn().isAfter(t) ? Status.CENSORED_FUTURE : Status.OBSERVED;
                opps.add(new Opportunity(sub.getPseudonym(), site.getCode(), ex.getPeriod().getCode(),
                        ex.getRiskStratum().name(), Kind.AUDIT, af.getAuditCode(),
                        clock.dayOf(study, af.getObservedOn()), af.getObservedOn(), af.getObservedOn(),
                        st, st == Status.OBSERVED ? af.getObservedOn() : null,
                        "centre audit sampled this chart (finding: " + af.getFindingKind() + ")"));
            }

            int observed = (int) opps.stream().filter(o -> o.status() == Status.OBSERVED).count();
            int inWindow = (int) opps.stream().filter(o -> o.status() == Status.IN_WINDOW).count();
            int censored = (int) opps.stream().filter(o -> o.status() == Status.CENSORED_FUTURE).count();
            int overdue = (int) opps.stream().filter(o -> o.status() == Status.OVERDUE_UNOBSERVED).count();
            int closedOut = (int) opps.stream().filter(o -> o.status() == Status.CLOSED_OUT).count();
            int expected = observed + inWindow + overdue; // censored & closed-out are not yet opportunity observations
            subjects.add(new SubjectOpportunities(sub.getPseudonym(), site.getCode(), ex.getPeriod().getCode(),
                    ex.getRiskStratum().name(), expected, observed, inWindow, censored, overdue, closedOut,
                    List.copyOf(opps)));
            all.addAll(opps);
        }
        Totals totals = new Totals(
                subjects.stream().mapToInt(SubjectOpportunities::expectedCount).sum(),
                subjects.stream().mapToInt(SubjectOpportunities::observed).sum(),
                subjects.stream().mapToInt(SubjectOpportunities::inWindow).sum(),
                subjects.stream().mapToInt(SubjectOpportunities::censoredFuture).sum(),
                subjects.stream().mapToInt(SubjectOpportunities::overdueUnobserved).sum(),
                subjects.stream().mapToInt(SubjectOpportunities::closedOut).sum());
        List<String> disclosure = List.of(
                "Opportunities are generated from randomisation, planned visits, planned dispensing, site closure and audit sampling — never from reported events.",
                "A visit not yet due is right-censored: it is not counted as late and not counted as 'no event'.",
                "A past-window visit with no return is an unobserved opportunity, not evidence that no event occurred.",
                "After a site's closure day no new visits are expected; only dispensing/return silence across the closure is diagnostic.");
        return new Framework(day, t, subjects, all, totals, disclosure);
    }
}
