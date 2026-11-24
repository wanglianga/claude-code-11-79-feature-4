<script setup>
const props = defineProps({
  kind: { type: String, required: true }, // LOCAL_AVAILABLE | GLOBAL_AVAILABLE | PENDING | BLOCKED
  compact: { type: Boolean, default: false }
})

const map = {
  LOCAL_AVAILABLE: { cls: 'local', label: 'Local available' },
  GLOBAL_AVAILABLE: { cls: 'ok', label: 'Global available' },
  PENDING: { cls: 'pend', label: 'Pending' },
  BLOCKED: { cls: 'bad', label: 'Blocked' },
  ELIGIBLE: { cls: 'ok', label: 'Eligible' },
  UNDETERMINED: { cls: 'pend', label: 'Undetermined' },
  NOT_ELIGIBLE: { cls: 'bad', label: 'Not eligible' },
  VALID: { cls: 'ok', label: 'Valid' },
  STALE: { cls: 'warn', label: 'Stale' },
  AUDIT_ONLY: { cls: 'muted', label: 'Audit only' },
  ACTIVE: { cls: 'ok', label: 'Active' },
  ACCESS_REVOKED: { cls: 'bad', label: 'Access revoked' },
  DESTROYED: { cls: 'muted', label: 'Destroyed' },
  GRANT: { cls: 'ok', label: 'Grant' },
  WITHDRAWAL: { cls: 'bad', label: 'Withdrawal' },
  APPROVED: { cls: 'ok', label: 'Approved' },
  PARTIALLY_APPROVED: { cls: 'warn', label: 'Partially approved' },
  WITHDRAWN: { cls: 'bad', label: 'Withdrawn' },
  DRAFT: { cls: 'muted', label: 'Draft' },
  OPEN: { cls: 'warn', label: 'Open' },
  RESOLVED: { cls: 'ok', label: 'Resolved' },
  PASS: { cls: 'ok', label: 'Pass' },
  FINDINGS: { cls: 'warn', label: 'Findings' },
  APPLIED: { cls: 'local', label: 'Applied' },
  SCHEDULED: { cls: 'warn', label: 'Scheduled' },
  EXECUTED: { cls: 'ok', label: 'Executed' },
  RETAIN_RESTRICTED: { cls: 'local', label: 'Retain restricted' },
  DESTROY_AFTER: { cls: 'bad', label: 'Destroy after' },
  // ---- observability fragment states ----
  INCLUDED: { cls: 'ok', label: 'Included' },
  KNOWN_RESTRICTED: { cls: 'local', label: 'Known restricted' },
  KNOWN_PENDING_ARRIVAL: { cls: 'pend', label: 'Known pending arrival' },
  AUDIT_FLAGGED_MISSING: { cls: 'warn', label: 'Audit-flagged missing' },
  UNOBSERVED_NO_ANCHOR: { cls: 'bad', label: 'Unobserved (no anchor)' },
  // ---- expected-opportunity statuses ----
  OBSERVED: { cls: 'ok', label: 'Observed' },
  IN_WINDOW: { cls: 'pend', label: 'In window' },
  OVERDUE_UNOBSERVED: { cls: 'bad', label: 'Overdue (unobserved)' },
  CENSORED_FUTURE: { cls: 'muted', label: 'Right-censored' },
  CLOSED_OUT: { cls: 'warn', label: 'Closed out' },
  // ---- estimate routes / coverage ----
  PARTIAL_BOUNDS: { cls: 'local', label: 'Partial bounds' },
  DELAY_CAPTURE_MODEL: { cls: 'ok', label: 'Delay / capture model' },
  CORRELATED: { cls: 'bad', label: 'Correlated sources' },
  COVERAGE_FAILURE: { cls: 'bad', label: 'Coverage failure' },
  HIGH: { cls: 'bad', label: 'High risk' },
  STANDARD: { cls: 'muted', label: 'Standard risk' }
}

function meta() {
  return map[props.kind] || { cls: 'muted', label: props.kind || '—' }
}
</script>

<template>
  <span class="badge" :class="meta().cls">
    <span v-if="!compact" class="pip"></span>{{ meta().label }}
  </span>
</template>
