package com.ctrial.model;

public final class Enums {

    private Enums() {
    }

    /**
     * Approved processing purpose. ALL is only valid inside a consent grant,
     * never as the purpose of a query or a delivery.
     */
    public enum Purpose {
        GLOBAL_ANALYSIS,
        CHINA_LOCAL_ANALYSIS,
        LOCAL_ANALYSIS,
        SAFETY_MONITORING,
        REGULATORY_SUBMISSION,
        ALL;

        public boolean isGlobal() {
            return this == GLOBAL_ANALYSIS;
        }

        public boolean isLocal() {
            return this == CHINA_LOCAL_ANALYSIS || this == LOCAL_ANALYSIS;
        }
    }

    /** Destination / source region codes. GLOBAL = any approved region. */
    public enum Region {
        GLOBAL, EU, CN, SG, DE, TH;

        public boolean covers(String region) {
            return this == GLOBAL || name().equalsIgnoreCase(region);
        }
    }

    /** Data domains flowing through the source ledger. Each has its own watermark. */
    public enum Domain {
        VISIT, LAB, AE, IMAGING, RANDOMIZATION, DEVIATION
    }

    public enum ConsentKind {
        GRANT, WITHDRAWAL
    }

    public enum ProtocolStatus {
        DRAFT,
        PARTIALLY_APPROVED,
        APPROVED,
        WITHDRAWN
    }

    /** Per-record availability. The three outcomes required by the spec. */
    public enum Eligibility {
        LOCAL_AVAILABLE,
        GLOBAL_AVAILABLE,
        PENDING,
        /** No lawful basis in any plane; reported separately, never silently counted as zero. */
        BLOCKED
    }

    /** Finer-grained adjudication used alongside the three availability outcomes. */
    public enum Verdict {
        ELIGIBLE,
        UNDETERMINED,
        NOT_ELIGIBLE
    }

    public enum SnapshotStatus {
        VALID,
        STALE,
        AUDIT_ONLY
    }

    public enum DeliveryStatus {
        ACTIVE,
        ACCESS_REVOKED,
        DESTROYED
    }

    public enum PlaneType {
        GLOBAL,
        LOCAL
    }

    public enum RuleAction {
        RETAIN_RESTRICTED,
        DESTROY_AFTER
    }

    public enum DispositionScope {
        STUDY,
        SITE
    }

    public enum DispositionState {
        APPLIED,
        SCHEDULED,
        EXECUTED
    }

    public enum QuarantineKind {
        RACE_WITHDRAWAL_CORRECTION,
        DUPLICATE_HASH_CONFLICT,
        CROSS_DOMAIN_MISMATCH,
        WATERMARK_INCONSISTENCY
    }

    public enum QuarantineState {
        OPEN,
        RESOLVED
    }

    public enum RecoveryStatus {
        PASS,
        FINDINGS
    }

    public enum Role {
        ADMIN,
        GLOBAL_ANALYST,
        CN_ANALYST,
        AUDITOR,
        SPONSOR
    }

    /**
     * The five observability states a denominator fragment can be in. These are
     * deliberately distinct: a fragment that is locally known but consent-blocked
     * is <em>not</em> the same kind of evidence as one that is simply unobserved,
     * and neither may be silently folded into a "no event" bucket.
     */
    public enum FragmentState {
        /** Data is inside the global analysis plane. */
        INCLUDED,
        /** Locally known, but the consent scope forbids cross-border egress. */
        KNOWN_RESTRICTED,
        /** Registered and locally known, still inside the expected return window. */
        KNOWN_PENDING_ARRIVAL,
        /** Past the return window, but a centre audit found a record that never transmitted. */
        AUDIT_FLAGGED_MISSING,
        /** No schedule, dispensing or safety-hotline source observed the opportunity/event. */
        UNOBSERVED_NO_ANCHOR
    }

    /** Pre-declared risk stratum used to partition the denominator. */
    public enum RiskStratum {
        HIGH,
        STANDARD
    }

    /** Which estimation route was chosen (and whether the requested one was downgraded). */
    public enum EstimateRoute {
        PARTIAL_BOUNDS,
        DELAY_CAPTURE_MODEL
    }

    /** Provenance of an observation / return. */
    public enum SourceKind {
        SCHEDULE,
        DISPENSING,
        HOTLINE,
        AUDIT
    }

    /** Centre-audit finding kinds. */
    public enum AuditFindingKind {
        LATE_UNTREATED_SAE,
        MISSING_VISIT,
        DISPENSING_WITHOUT_RETURN,
        ROUTINE_SAMPLE
    }
}
