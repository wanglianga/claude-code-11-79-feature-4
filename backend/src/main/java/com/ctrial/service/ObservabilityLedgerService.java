package com.ctrial.service;

import com.ctrial.model.*;
import com.ctrial.model.Enums.FragmentState;
import com.ctrial.model.Enums.SourceKind;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Observability classification ledger.
 *
 * <p>Partitions every subject (the denominator) by <b>site × treatment period ×
 * risk stratum</b> and classifies each fragment into exactly one of the five
 * observability states. The point of the classification is that these states are
 * <em>not</em> interchangeable:
 *
 * <ul>
 *   <li>{@code INCLUDED} — the fragment is inside the global analysis plane.</li>
 *   <li>{@code KNOWN_RESTRICTED} — locally known but consent forbids egress.
 *       This is a <b>known</b> person, bounded locally; it must not be counted as
 *       an unobserved rate.</li>
 *   <li>{@code KNOWN_PENDING_ARRIVAL} — registered and still inside the expected
 *       return window; it may still arrive.</li>
 *   <li>{@code AUDIT_FLAGGED_MISSING} — past the window, but an independent audit
 *       anchor found the record; it is a known gap, not an unknown.</li>
 *   <li>{@code UNOBSERVED_NO_ANCHOR} — no schedule / dispensing / hotline source
 *       and no anchor observed it. Only this bucket is genuinely unknown.</li>
 * </ul>
 *
 * <p>The global side receives only a {@link GlobalProjection}: permitted count
 * <em>ranges</em> plus status summaries. Subject identifiers never appear in it.
 */
@Service
public class ObservabilityLedgerService {

    private final SubjectExposureRepo exposureRepo;
    private final ObservationSourceRepo sourceRepo;
    private final SaeRecordRepo saeRepo;
    private final AnchorRecordRepo anchorRepo;
    private final AuditFindingRepo auditRepo;
    private final StudyClock clock;
    private final EligibilityService eligibility;

    public ObservabilityLedgerService(SubjectExposureRepo exposureRepo, ObservationSourceRepo sourceRepo,
                                      SaeRecordRepo saeRepo, AnchorRecordRepo anchorRepo,
                                      AuditFindingRepo auditRepo, StudyClock clock,
                                      EligibilityService eligibility) {
        this.exposureRepo = exposureRepo;
        this.sourceRepo = sourceRepo;
        this.saeRepo = saeRepo;
        this.anchorRepo = anchorRepo;
        this.auditRepo = auditRepo;
        this.clock = clock;
        this.eligibility = eligibility;
    }

    public record Fragment(String pseudonym, FragmentState state, String reason,
                           int observedEvents, int arrivedEvents, boolean egressPermitted,
                           List<String> anchors) {
    }

    public record Segment(String key, String siteCode, String periodCode, String riskStratum,
                          int denominator, int included, int knownRestricted, int knownPendingArrival,
                          int auditFlaggedMissing, int unobservedNoAnchor,
                          int globalVisibleEvents, int locallyKnownEvents, int restrictedEvents,
                          List<Fragment> fragments) {
    }

    public record Ledger(int analysisDay, Instant analysisAt, List<String> siteOrder, List<String> periodOrder,
                         List<Segment> segments, List<Fragment> fragments, List<String> disclosure) {
    }

    /**
     * A sponsor/global-facing projection. Contains only permitted count ranges and
     * status summaries — no pseudonyms and no direct identifiers.
     */
    public record GlobalRange(String siteCode, String periodCode, String riskStratum,
                              int includedCount, int knownRestrictedCount, int knownPendingCount,
                              int auditFlaggedCount, int unobservedCount,
                              int visibleEventLower, int visibleEventUpper,
                              String statusSummary) {
    }

    public record GlobalProjection(int analysisDay, String permittedScope,
                                   List<GlobalRange> ranges, Totals totals, List<String> disclosure) {
    }

    public record Totals(int denominators, int included, int knownRestricted, int knownPending,
                         int auditFlagged, int unobserved, int visibleEventLower, int visibleEventUpper) {
    }

    @Transactional(readOnly = true)
    public Ledger build(int day, int windowDays) {
        Study study = eligibility.study();
        Instant t = clock.analysisInstant(study, day);

        Map<Long, List<ObservationSource>> sources = new HashMap<>();
        for (ObservationSource s : sourceRepo.findAllByOrderByObservedOnAsc()) {
            sources.computeIfAbsent(s.getSubject().getId(), k -> new ArrayList<>()).add(s);
        }
        Map<Long, List<SaeRecord>> saes = new HashMap<>();
        for (SaeRecord s : saeRepo.findAllByOrderByOccurredAtAsc()) {
            saes.computeIfAbsent(s.getSubject().getId(), k -> new ArrayList<>()).add(s);
        }
        Map<Long, List<AnchorRecord>> anchors = new HashMap<>();
        for (AnchorRecord a : anchorRepo.findAllByOrderByObservedOnAsc()) {
            anchors.computeIfAbsent(a.getSubject().getId(), k -> new ArrayList<>()).add(a);
        }
        Set<String> auditSampled = new HashSet<>();
        for (AuditFinding af : auditRepo.findAllByOrderByObservedOnAsc()) {
            if (af.getSampledSubjects() != null) {
                for (String p : af.getSampledSubjects().split(",")) {
                    if (!p.isBlank()) {
                        auditSampled.add(p.trim());
                    }
                }
            }
        }

        List<Fragment> fragments = new ArrayList<>();
        for (SubjectExposure ex : exposureRepo.findByExposedTrueOrderByStartedOnAsc()) {
            Subject sub = ex.getSubject();
            List<ObservationSource> src = sources.getOrDefault(sub.getId(), List.of());
            List<SaeRecord> evs = saes.getOrDefault(sub.getId(), List.of());
            List<AnchorRecord> anc = anchors.getOrDefault(sub.getId(), List.of());

            // Count events attached to this fragment. An event is "visible" if it is
            // arrived AND egress-permitted; otherwise it is locally known only.
            int arrived = 0;
            int localKnown = 0;
            boolean inFlightWithinWindow = false;
            boolean inFlightPastWindow = false;
            for (SaeRecord s : evs) {
                if (s.getOccurredAt().isAfter(t)) {
                    continue;
                }
                boolean returned = s.isArrived() && s.getReturnedAt() != null && !s.getReturnedAt().isAfter(t);
                localKnown++;
                if (returned && ex.isEgressPermitted()) {
                    // egress-gated: a consent-restricted return is locally known but never globally visible
                    arrived++;
                } else if (returned) {
                    // returned locally but consent forbids egress; known, not visible
                } else {
                    double age = (t.toEpochMilli() - s.getOccurredAt().toEpochMilli()) / 86_400_000.0;
                    if (age <= windowDays) {
                        inFlightWithinWindow = true;
                    } else {
                        inFlightPastWindow = true;
                    }
                }
            }

            FragmentState state;
            String reason;
            List<String> anchorCodes = anc.stream().map(AnchorRecord::getAuditCode).distinct().toList();

            if (!ex.isEgressPermitted()) {
                // Consent restricts egress. Even with an SAE record present, this is a
                // KNOWN person: it changes only what the global plane may see.
                state = FragmentState.KNOWN_RESTRICTED;
                reason = "Consent scope forbids cross-border egress; "
                        + (localKnown > 0 ? localKnown + " event(s) are locally known but must not enter the global plane."
                        : "no events, but the fragment is known locally and bounded locally.");
            } else if (arrived > 0) {
                state = FragmentState.INCLUDED;
                reason = "Inside the global analysis plane with " + arrived + " arrived event(s).";
            } else if (!inFlightWithinWindow && !inFlightPastWindow && fullyObserved(src, auditSampled.contains(sub.getPseudonym()))) {
                state = FragmentState.INCLUDED;
                reason = "All planned opportunities observed and no event; a genuine zero, not an unobserved gap.";
            } else if (inFlightWithinWindow) {
                state = FragmentState.KNOWN_PENDING_ARRIVAL;
                reason = "Registered, inside the expected return window, and the outstanding return may still arrive.";
            } else if (!anchorCodes.isEmpty() && (inFlightPastWindow || localKnown > 0)) {
                state = FragmentState.AUDIT_FLAGGED_MISSING;
                reason = "Past the return window, but an independent audit anchor observed a record; this is a known gap.";
            } else if (inFlightPastWindow) {
                state = FragmentState.UNOBSERVED_NO_ANCHOR;
                reason = "A known record is past its return window with no anchor; it is genuinely unknown from the platform's view.";
            } else {
                state = FragmentState.UNOBSERVED_NO_ANCHOR;
                reason = "No schedule, dispensing or safety-hotline source and no anchor observed this fragment; "
                        + "it is genuinely unknown.";
            }

            fragments.add(new Fragment(sub.getPseudonym(), state, reason, localKnown, arrived,
                    ex.isEgressPermitted(), anchorCodes));
        }

        // group into segments site x period x stratum
        Map<String, List<Fragment>> bySegment = new LinkedHashMap<>();
        Map<String, SubjectExposure> metaByPseudo = new HashMap<>();
        for (SubjectExposure ex : exposureRepo.findByExposedTrueOrderByStartedOnAsc()) {
            metaByPseudo.put(ex.getSubject().getPseudonym(), ex);
        }
        for (Fragment f : fragments) {
            SubjectExposure ex = metaByPseudo.get(f.pseudonym());
            String key = ex.getSubject().getSite().getCode() + "|" + ex.getPeriod().getCode() + "|"
                    + ex.getRiskStratum().name();
            bySegment.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        List<Segment> segments = new ArrayList<>();
        for (Map.Entry<String, List<Fragment>> e : bySegment.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            List<Fragment> fs = e.getValue();
            segments.add(new Segment(e.getKey(), parts[0], parts[1], parts[2], fs.size(),
                    count(fs, FragmentState.INCLUDED), count(fs, FragmentState.KNOWN_RESTRICTED),
                    count(fs, FragmentState.KNOWN_PENDING_ARRIVAL), count(fs, FragmentState.AUDIT_FLAGGED_MISSING),
                    count(fs, FragmentState.UNOBSERVED_NO_ANCHOR),
                    fs.stream().mapToInt(Fragment::arrivedEvents).sum(),
                    fs.stream().mapToInt(Fragment::observedEvents).sum(),
                    fs.stream().filter(f -> f.state() == FragmentState.KNOWN_RESTRICTED)
                            .mapToInt(Fragment::observedEvents).sum(),
                    List.copyOf(fs)));
        }
        segments.sort(Comparator.comparing(Segment::siteCode).thenComparing(Segment::periodCode)
                .thenComparing(Segment::riskStratum));
        List<String> siteOrder = segments.stream().map(Segment::siteCode).distinct().toList();
        List<String> periodOrder = segments.stream().map(Segment::periodCode).distinct().toList();

        List<String> disclosure = List.of(
                "Each fragment is in exactly one state; the five states are not interchangeable evidence.",
                "KNOWN_RESTRICTED is a known person bounded locally — it changes only what the global plane may see and is never counted as an unobserved rate.",
                "A fragment with a returned serious AE is INCLUDED even after that AE is later withdrawn at the occurrence level; the state describes observability, not consent re-use.",
                "UNOBSERVED_NO_ANCHOR is the only bucket that is genuinely unknown; only it feeds the sensitivity band.");
        return new Ledger(day, t, siteOrder, periodOrder, segments, fragments, disclosure);
    }

    @Transactional(readOnly = true)
    public GlobalProjection projectGlobal(int day, int windowDays) {
        Ledger ledger = build(day, windowDays);
        List<GlobalRange> ranges = new ArrayList<>();
        for (Segment s : ledger.segments()) {
            int visibleLower = s.globalVisibleEvents();
            // Under partial identification the ignored unobserved mass can carry at most
            // the audit-supported rate; here we expose the included count as the known
            // lower bound and the audit-flagged additions as an upper hint. The estimator
            // supplies the final band; the projection shows permitted ranges only.
            int visibleUpper = visibleLower + s.auditFlaggedMissing();
            String status = s.unobservedNoAnchor() > 0 ? "INCOMPLETE_UNOBSERVED"
                    : s.knownPendingArrival() > 0 ? "PENDING_RETURN"
                    : s.knownRestricted() > 0 ? "RESTRICTED_LOCAL_ONLY" : "COMPLETE";
            ranges.add(new GlobalRange(s.siteCode(), s.periodCode(), s.riskStratum(),
                    s.included(), s.knownRestricted(), s.knownPendingArrival(), s.auditFlaggedMissing(),
                    s.unobservedNoAnchor(), visibleLower, visibleUpper, status));
        }
        Totals totals = new Totals(
                ledger.segments().stream().mapToInt(Segment::denominator).sum(),
                ledger.segments().stream().mapToInt(Segment::included).sum(),
                ledger.segments().stream().mapToInt(Segment::knownRestricted).sum(),
                ledger.segments().stream().mapToInt(Segment::knownPendingArrival).sum(),
                ledger.segments().stream().mapToInt(Segment::auditFlaggedMissing).sum(),
                ledger.segments().stream().mapToInt(Segment::unobservedNoAnchor).sum(),
                ranges.stream().mapToInt(GlobalRange::visibleEventLower).sum(),
                ranges.stream().mapToInt(GlobalRange::visibleEventUpper).sum());
        List<String> disclosure = List.of(
                "This projection carries permitted count ranges and status summaries only — no subject identifiers.",
                "It never includes the local identity handle or the pseudonym of a restricted subject.",
                "Counts are ranges because the unobserved region is bounded, not assumed zero.");
        return new GlobalProjection(day, "GLOBAL_ANALYSIS cross-border envelope", ranges, totals, disclosure);
    }

    private boolean fullyObserved(List<ObservationSource> src, boolean auditLook) {
        boolean schedule = src.stream().anyMatch(s -> s.getSourceKind() == SourceKind.SCHEDULE);
        boolean dispensing = src.stream().anyMatch(s -> s.getSourceKind() == SourceKind.DISPENSING);
        boolean hotline = src.stream().anyMatch(s -> s.getSourceKind() == SourceKind.HOTLINE);
        return (schedule && dispensing && hotline) || (schedule && dispensing && auditLook);
    }

    private static int count(List<Fragment> fs, FragmentState st) {
        return (int) fs.stream().filter(f -> f.state() == st).count();
    }
}
