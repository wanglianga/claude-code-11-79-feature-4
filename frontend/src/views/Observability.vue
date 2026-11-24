<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { store, toast } from '../lib/store.js'
import StatusBadge from '../components/StatusBadge.vue'

const loading = ref(true)
const error = ref('')
const tab = ref('ledger')
const windowDays = ref(10)
const route = ref('PARTIAL_BOUNDS')

const ledger = ref(null)
const globalView = ref(null)
const expected = ref(null)
const delay = ref(null)
const estimate = ref(null)
const versions = ref([])
const publishing = ref(false)

const TABS = [
  { key: 'ledger', label: 'Observability ledger' },
  { key: 'expected', label: 'Expected opportunities' },
  { key: 'delay', label: 'Delay diagnostics' },
  { key: 'estimate', label: 'Estimate & sensitivity' },
  { key: 'versions', label: 'Versioned records' }
]

const FRAGMENT_ORDER = ['INCLUDED', 'KNOWN_RESTRICTED', 'KNOWN_PENDING_ARRIVAL', 'AUDIT_FLAGGED_MISSING', 'UNOBSERVED_NO_ANCHOR']

async function load() {
  loading.value = true
  error.value = ''
  try {
    const day = store.analysisDay
    const wd = windowDays.value
    const [l, g, x, d, e, v] = await Promise.all([
      api.get(`/observability/ledger?day=${day}&windowDays=${wd}`),
      api.get(`/observability/global?day=${day}&windowDays=${wd}`),
      api.get(`/observability/expected?day=${day}`),
      api.get(`/observability/delay?day=${day}&windowDays=${wd}`),
      api.get(`/observability/estimate?day=${day}&route=${route.value}&windowDays=${wd}`),
      api.get('/observability/estimates')
    ])
    ledger.value = l
    globalView.value = g
    expected.value = x
    delay.value = d
    estimate.value = e
    versions.value = v
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function recompute() {
  await load()
}

async function publish() {
  publishing.value = true
  try {
    const saved = await api.post('/observability/estimates', {
      day: store.analysisDay,
      route: route.value,
      versionGroup: `EST-D${store.analysisDay}`
    })
    toast(`Published ${saved.code} (v${saved.version}): range [${saved.rangeLower}, ${saved.rangeUpper}] per 100.`, 'ok')
    await load()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    publishing.value = false
  }
}

const totals = computed(() => ledger.value?.segments || [])

function fragmentsIn(segment, state) {
  return (segment.fragments || []).filter((f) => f.state === state)
}

function fmtBand(lo, hi) {
  if (lo == null && hi == null) return '—'
  return `[${lo ?? '–'}, ${hi ?? '–'}]`
}

function coverageTone(active) {
  return active ? 'bad' : 'ok'
}

function parseAssumptions(json) {
  try { return JSON.parse(json || '[]') } catch { return [] }
}
function parseTightening(json) {
  try { return JSON.parse(json || '[]') } catch { return [] }
}
</script>

<template>
  <div>
    <div class="page-head">
      <h1>Observability &amp; sensitivity</h1>
      <p class="lede">
        Whether a global serious-AE rate can support a safety conclusion depends on <em>why</em> a centre is missing
        from the analysis plane. This workbench classifies every denominator fragment into one of five observability
        states, generates the observation opportunities that <em>should</em> have appeared, calibrates return delay
        from realised intervals only, and estimates bounds — never imputing the unknown.
      </p>
    </div>

    <div v-if="error" class="notice bad">{{ error }}</div>

    <div class="row wrap" style="margin-bottom: 14px">
      <div class="tabs" style="margin: 0; border: none">
        <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
          @click="tab = t.key">{{ t.label }}</button>
      </div>
      <div class="spacer" style="flex: 1"></div>
      <label class="field" style="flex-direction: row; align-items: center; gap: 8px">
        <span class="faint">Return window (days)</span>
        <input type="number" min="1" max="60" v-model.number="windowDays" @change="recompute" style="width: 70px" />
      </label>
      <button @click="recompute">Recompute</button>
    </div>

    <div v-if="loading" class="empty">Loading observability at day {{ store.analysisDay }}…</div>

    <template v-else>
      <!-- ============ LEDGER ============ -->
      <div v-show="tab === 'ledger'" class="stack">
        <div class="grid cols-5" style="margin-bottom: 4px">
          <div v-for="st in FRAGMENT_ORDER" :key="st" class="stat">
            <div class="label">
              <StatusBadge :kind="st" compact />
            </div>
            <div class="value">{{ (ledger?.fragments || []).filter(f => f.state === st).length }}</div>
            <div class="foot">
              {{ st === 'UNOBSERVED_NO_ANCHOR' ? 'the only genuinely unknown bucket'
                : st === 'KNOWN_RESTRICTED' ? 'known locally; egress-blocked'
                : st === 'INCLUDED' ? 'inside the global plane'
                : st === 'KNOWN_PENDING_ARRIVAL' ? 'return may still arrive'
                : 'past window, audit-anchored' }}
            </div>
          </div>
        </div>

        <section class="panel">
          <div class="panel-head">
            <h2>Classification by site × period × risk stratum</h2>
            <div class="spacer"></div>
            <span class="small faint">local ledger — contains pseudonyms</span>
          </div>
          <div class="panel-body tight scroll-y">
            <table>
              <thead>
                <tr>
                  <th>Site</th><th>Period</th><th>Stratum</th><th>N</th>
                  <th>Included</th><th>Restricted</th><th>Pending</th><th>Audit-flagged</th><th>Unobserved</th>
                  <th>Global events</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in totals" :key="s.key">
                  <td class="mono">{{ s.siteCode }}</td>
                  <td class="small">{{ s.periodCode }}</td>
                  <td><StatusBadge :kind="s.riskStratum" compact /></td>
                  <td class="num">{{ s.denominator }}</td>
                  <td class="num" style="color: var(--ok)">{{ s.included }}</td>
                  <td class="num" style="color: var(--local)">{{ s.knownRestricted }}</td>
                  <td class="num" style="color: var(--pend)">{{ s.knownPendingArrival }}</td>
                  <td class="num" style="color: var(--warn)">{{ s.auditFlaggedMissing }}</td>
                  <td class="num" style="color: var(--bad)">{{ s.unobservedNoAnchor }}</td>
                  <td class="num">{{ s.globalVisibleEvents }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Fragment detail</h2><div class="spacer"></div>
            <span class="small faint">{{ ledger?.fragments?.length }} fragment(s)</span></div>
          <div class="panel-body tight scroll-y">
            <table>
              <thead><tr><th>Subject</th><th>State</th><th>Egress</th><th>Events</th><th>Anchors</th><th>Reason</th></tr></thead>
              <tbody>
                <tr v-for="f in ledger?.fragments || []" :key="f.pseudonym">
                  <td class="mono">{{ f.pseudonym }}</td>
                  <td><StatusBadge :kind="f.state" compact /></td>
                  <td>
                    <span class="badge" :class="f.egressPermitted ? 'ok' : 'bad'">
                      {{ f.egressPermitted ? 'permitted' : 'blocked' }}
                    </span>
                  </td>
                  <td class="num">{{ f.observedEvents }}</td>
                  <td><span v-for="a in f.anchors" :key="a" class="chip">{{ a }}</span>
                      <span v-if="!f.anchors.length" class="faint">—</span></td>
                  <td class="small faint">{{ f.reason }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Global-safe projection (what the sponsor side receives)</h2></div>
          <div class="panel-body">
            <div class="notice ok small" style="margin-bottom: 12px">
              Permitted count ranges and status summaries only — no subject identifiers leave the region.
            </div>
            <table class="table-wrap">
              <thead><tr><th>Site</th><th>Period</th><th>Stratum</th><th>Included</th><th>Restricted</th>
                <th>Pending</th><th>Audit-flagged</th><th>Unobserved</th><th>Visible events (range)</th><th>Status</th></tr></thead>
              <tbody>
                <tr v-for="r in globalView?.ranges || []" :key="r.siteCode + r.periodCode + r.riskStratum">
                  <td class="mono">{{ r.siteCode }}</td>
                  <td class="small">{{ r.periodCode }}</td>
                  <td class="small">{{ r.riskStratum }}</td>
                  <td class="num">{{ r.includedCount }}</td>
                  <td class="num">{{ r.knownRestrictedCount }}</td>
                  <td class="num">{{ r.knownPendingCount }}</td>
                  <td class="num">{{ r.auditFlaggedCount }}</td>
                  <td class="num">{{ r.unobservedCount }}</td>
                  <td class="num">{{ r.visibleEventLower }}–{{ r.visibleEventUpper }}</td>
                  <td><span class="chip">{{ r.statusSummary }}</span></td>
                </tr>
              </tbody>
            </table>
            <div class="small faint" style="margin-top: 10px">
              Totals: {{ globalView?.totals?.denominators }} denominators · included {{ globalView?.totals?.included }} ·
              restricted {{ globalView?.totals?.knownRestricted }} · pending {{ globalView?.totals?.knownPending }} ·
              audit-flagged {{ globalView?.totals?.auditFlagged }} · unobserved {{ globalView?.totals?.unobserved }}.
            </div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Classification rules</h2></div>
          <div class="panel-body">
            <ul class="small muted" style="margin: 0; padding-left: 18px">
              <li v-for="d in ledger?.disclosure || []" :key="d" style="margin-bottom: 6px">{{ d }}</li>
            </ul>
          </div>
        </section>
      </div>

      <!-- ============ EXPECTED ============ -->
      <div v-show="tab === 'expected'" class="stack">
        <div class="grid cols-4" style="margin-bottom: 4px">
          <div class="stat"><div class="label">Expected opportunities</div>
            <div class="value">{{ expected?.totals?.expectedOpportunities }}</div>
            <div class="foot">due by the analysis instant</div></div>
          <div class="stat"><div class="label">Observed</div>
            <div class="value" style="color: var(--ok)">{{ expected?.totals?.observed }}</div>
            <div class="foot">satisfied by a source record</div></div>
          <div class="stat"><div class="label">Right-censored</div>
            <div class="value" style="color: var(--text-dim)">{{ expected?.totals?.censoredFuture }}</div>
            <div class="foot">not yet due — NOT "no event"</div></div>
          <div class="stat"><div class="label">Overdue unobserved</div>
            <div class="value" style="color: var(--bad)">{{ expected?.totals?.overdueUnobserved }}</div>
            <div class="foot">past window, still not "no event"</div></div>
        </div>

        <section class="panel">
          <div class="panel-head"><h2>Per-subject opportunities</h2></div>
          <div class="panel-body tight scroll-y">
            <table>
              <thead><tr><th>Subject</th><th>Site</th><th>Expected</th><th>Observed</th><th>In window</th>
                <th>Censored</th><th>Overdue</th><th>Closed-out</th></tr></thead>
              <tbody>
                <tr v-for="s in expected?.subjects || []" :key="s.pseudonym">
                  <td class="mono">{{ s.pseudonym }}</td>
                  <td class="mono">{{ s.siteCode }}</td>
                  <td class="num">{{ s.expectedCount }}</td>
                  <td class="num" style="color: var(--ok)">{{ s.observed }}</td>
                  <td class="num" style="color: var(--pend)">{{ s.inWindow }}</td>
                  <td class="num" style="color: var(--text-dim)">{{ s.censoredFuture }}</td>
                  <td class="num" style="color: var(--bad)">{{ s.overdueUnobserved }}</td>
                  <td class="num" style="color: var(--warn)">{{ s.closedOut }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Opportunity timeline</h2><div class="spacer"></div>
            <span class="small faint">showing {{ expected?.all?.length }} line item(s)</span></div>
          <div class="panel-body tight scroll-y">
            <table>
              <thead><tr><th>Subject</th><th>Kind</th><th>Ref</th><th>Due day</th><th>Status</th><th>Note</th></tr></thead>
              <tbody>
                <tr v-for="(o, i) in expected?.all || []" :key="o.pseudonym + o.kind + o.ref + i">
                  <td class="mono">{{ o.pseudonym }}</td>
                  <td class="small">{{ o.kind }}</td>
                  <td class="mono-sm">{{ o.ref }}</td>
                  <td class="num">D{{ o.studyDay }}</td>
                  <td><StatusBadge :kind="o.status" compact /></td>
                  <td class="small faint">{{ o.note }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Framework disclosure</h2></div>
          <div class="panel-body">
            <ul class="small muted" style="margin: 0; padding-left: 18px">
              <li v-for="d in expected?.disclosure || []" :key="d" style="margin-bottom: 6px">{{ d }}</li>
            </ul>
          </div>
        </section>
      </div>

      <!-- ============ DELAY ============ -->
      <div v-show="tab === 'delay'" class="stack">
        <section class="panel">
          <div class="panel-head"><h2>Calibration strata (site × period × grade)</h2>
            <div class="spacer"></div><span class="small faint">window {{ delay?.windowDays }} days</span></div>
          <div class="panel-body tight scroll-y">
            <table>
              <thead><tr><th>Site</th><th>Period</th><th>Grade band</th><th>Realised</th><th>Median</th><th>p80</th>
                <th>p90</th><th>In-flight</th><th>Recoverable</th><th>Calibrated</th></tr></thead>
              <tbody>
                <tr v-for="k in delay?.strata || []" :key="k.siteCode + k.periodCode + k.gradeBand">
                  <td class="mono">{{ k.siteCode }}</td>
                  <td class="small">{{ k.periodCode }}</td>
                  <td class="small">{{ k.gradeBand }}</td>
                  <td class="num">{{ k.realisedCount }}</td>
                  <td class="num">{{ k.medianDays }}</td>
                  <td class="num">{{ k.p80Days }}</td>
                  <td class="num">{{ k.p90Days }}</td>
                  <td class="num">{{ k.inFlightCount }}</td>
                  <td class="num">{{ k.recoverableWithinWindow }}/{{ k.inFlightCount }} ({{ Math.round(k.recoverableFraction * 100) }}%)</td>
                  <td>
                    <span class="badge" :class="k.calibrated ? 'ok' : 'warn'">{{ k.calibrated ? 'yes' : 'insufficient' }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <div class="grid cols-2">
          <section class="panel">
            <div class="panel-head"><h2>Realised return intervals</h2></div>
            <div class="panel-body tight scroll-y">
              <div v-if="!delay?.realisedIntervals?.length" class="empty">No realised return intervals yet.</div>
              <table v-else>
                <thead><tr><th>Subject</th><th>Site</th><th>Period</th><th>Grade</th><th>Lag (days)</th></tr></thead>
                <tbody>
                  <tr v-for="(r, i) in delay.realisedIntervals" :key="i">
                    <td class="mono">{{ r.pseudonym }}</td>
                    <td class="mono">{{ r.siteCode }}</td>
                    <td class="small">{{ r.periodCode }}</td>
                    <td class="small">{{ r.gradeBand }}</td>
                    <td class="num">{{ r.lagDays }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="panel">
            <div class="panel-head"><h2>Diagnostics</h2></div>
            <div class="panel-body stack">
              <div v-for="r in delay?.reconciliation || []" :key="r" class="notice small">{{ r }}</div>
              <div v-for="w in delay?.warnings || []" :key="w" class="notice warn small">{{ w }}</div>
              <div v-if="!delay?.warnings?.length" class="notice ok small">All strata have ≥3 realised intervals.</div>
            </div>
          </section>
        </div>
      </div>

      <!-- ============ ESTIMATE ============ -->
      <div v-show="tab === 'estimate'" class="stack">
        <div class="row wrap" style="margin-bottom: 8px">
          <label class="field" style="flex-direction: row; align-items: center; gap: 8px">
            <span class="faint">Route</span>
            <select v-model="route" @change="recompute">
              <option value="PARTIAL_BOUNDS">Partial bounds (assumption-light)</option>
              <option value="DELAY_CAPTURE_MODEL">Delay / capture model (narrower)</option>
            </select>
          </label>
          <div class="spacer" style="flex: 1"></div>
          <button class="primary" :disabled="publishing" @click="publish">
            {{ publishing ? 'Publishing…' : `Publish versioned estimate (D${store.analysisDay})` }}
          </button>
        </div>

        <div v-if="estimate?.downgraded" class="notice warn">
          <strong>Route downgraded to {{ estimate.route }}.</strong> {{ estimate.downgradeReason }}
        </div>

        <div class="grid cols-3" style="margin-bottom: 4px">
          <div class="stat">
            <div class="label">Combined global range (per 100)</div>
            <div class="value">{{ fmtBand(estimate?.combined?.lower, estimate?.combined?.upper) }}</div>
            <div class="foot">
              <StatusBadge :kind="estimate?.route" compact />
              <span v-if="estimate?.pointEstimate != null"> point {{ estimate.pointEstimate }}</span>
            </div>
          </div>
          <div class="stat">
            <div class="label">Known-restricted band (local)</div>
            <div class="value" style="color: var(--local)">{{ fmtBand(estimate?.restricted?.lower, estimate?.restricted?.upper) }}</div>
            <div class="foot">bounded locally — never counted as unobserved</div>
          </div>
          <div class="stat">
            <div class="label">Unobserved sensitivity band</div>
            <div class="value" style="color: var(--warn)">{{ fmtBand(estimate?.unobserved?.lower, estimate?.unobserved?.upper) }}</div>
            <div class="foot">anchors + declared clinical limit</div>
          </div>
        </div>

        <div class="grid cols-2">
          <section class="panel">
            <div class="panel-head"><h2>Assumptions</h2></div>
            <div class="panel-body stack">
              <div v-for="a in estimate?.assumptions || []" :key="a.code" class="notice small">
                <div class="row"><span class="chip">{{ a.code }}</span>
                  <span class="badge" :class="a.testable ? 'ok' : 'warn'">{{ a.testable ? 'testable' : 'structural' }}</span></div>
                <div style="margin-top: 6px">{{ a.statement }}</div>
              </div>
            </div>
          </section>

          <section class="panel">
            <div class="panel-head"><h2>What tightens the range</h2></div>
            <div class="panel-body stack">
              <div v-for="(t, i) in estimate?.tightening || []" :key="i" class="notice small">
                <div><strong>{{ t.action }}</strong></div>
                <div class="faint" style="margin-top: 4px">{{ t.expectedEffect }}</div>
              </div>
            </div>
          </section>
        </div>

        <div class="grid cols-2">
          <section class="panel" :style="{ borderColor: estimate?.coverageFailure?.active ? 'rgba(248,81,73,0.5)' : '' }">
            <div class="panel-head">
              <h2>Coverage status</h2><div class="spacer"></div>
              <StatusBadge :kind="estimate?.coverageFailure?.active ? 'COVERAGE_FAILURE' : 'INCLUDED'" compact />
            </div>
            <div class="panel-body stack">
              <div v-if="estimate?.coverageFailure?.active" class="notice bad small">
                <div><strong>{{ estimate.coverageFailure.siteCode }}</strong> — {{ estimate.coverageFailure.detail }}</div>
                <ul style="margin: 8px 0 0; padding-left: 18px">
                  <li v-for="c in estimate.coverageFailure.conditions" :key="c" style="margin-bottom: 5px">{{ c }}</li>
                </ul>
              </div>
              <div v-else class="notice ok small">No coverage failure: no closed-site silence exceeds the calibrated delay.</div>
            </div>
          </section>

          <section class="panel" :style="{ borderColor: estimate?.correlation?.present ? 'rgba(248,81,73,0.5)' : '' }">
            <div class="panel-head">
              <h2>Source dependence</h2><div class="spacer"></div>
              <StatusBadge :kind="estimate?.correlation?.present ? 'CORRELATED' : 'INCLUDED'" compact />
            </div>
            <div class="panel-body stack">
              <div v-if="estimate?.correlation?.present" class="notice bad small">
                <ul style="margin: 0; padding-left: 18px">
                  <li v-for="c in estimate.correlation.detail" :key="c" style="margin-bottom: 5px">{{ c }}</li>
                </ul>
              </div>
              <div v-else class="notice ok small">Sources are independent; a capture model would be admissible if anchors suffice.</div>
              <div class="small faint">
                Independent anchors: <strong>{{ estimate?.anchorsSufficient ? 'sufficient' : 'insufficient' }}</strong>
                · coverage {{ estimate?.anchorCoverage }}
              </div>
            </div>
          </section>
        </div>

        <section class="panel">
          <div class="panel-head"><h2>Estimator diagnostics</h2></div>
          <div class="panel-body">
            <ul class="small muted" style="margin: 0; padding-left: 18px">
              <li v-for="d in estimate?.diagnostics || []" :key="d" style="margin-bottom: 6px">{{ d }}</li>
            </ul>
            <div class="divider"></div>
            <div class="kv small">
              <dt>Restricted basis</dt><dd>{{ estimate?.restricted?.basis }}</dd>
              <dt>Unobserved basis</dt><dd>{{ estimate?.unobserved?.basis }}</dd>
              <dt>Combined basis</dt><dd>{{ estimate?.combined?.basis }}</dd>
            </div>
          </div>
        </section>
      </div>

      <!-- ============ VERSIONS ============ -->
      <div v-show="tab === 'versions'" class="stack">
        <div class="notice small">
          Re-estimating writes a <strong>new</strong> version; an earlier published interpretation (e.g. a wider band
          from an older month's report) is retained and still explained. Nothing is overwritten.
        </div>
        <section class="panel">
          <div class="panel-head"><h2>Published estimate records</h2><div class="spacer"></div>
            <span class="small faint">{{ versions.length }} record(s)</span></div>
          <div class="panel-body tight scroll-y">
            <div v-if="!versions.length" class="empty">No estimate has been published yet. Use “Publish versioned estimate”.</div>
            <table v-else>
              <thead><tr><th>Code</th><th>Day</th><th>Route</th><th>Global</th><th>Restricted</th>
                <th>Unobserved</th><th>Range (per 100)</th><th>Flags</th><th>Created</th></tr></thead>
              <tbody>
                <tr v-for="v in versions" :key="v.code">
                  <td class="mono">{{ v.code }}</td>
                  <td class="num">D{{ v.analysisDay }}</td>
                  <td><StatusBadge :kind="v.route" compact /></td>
                  <td class="num">{{ v.globalVisibleCount }}</td>
                  <td class="num">{{ v.knownRestrictedCount }}</td>
                  <td class="num">{{ v.unobservedCount }}</td>
                  <td class="num">{{ fmtBand(v.rangeLower, v.rangeUpper) }}</td>
                  <td>
                    <span v-if="v.downgraded" class="badge warn">downgraded</span>
                    <span v-if="v.coverageFailure" class="badge bad">coverage failure</span>
                    <span v-if="v.correlatedSources" class="badge bad">correlated</span>
                    <span v-if="!v.downgraded && !v.coverageFailure && !v.correlatedSources" class="badge ok">clean</span>
                  </td>
                  <td class="mono-sm faint">{{ new Date(v.createdAt).toISOString().slice(0, 16).replace('T', ' ') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-for="v in versions" :key="'d' + v.code" class="panel">
          <div class="panel-head"><h2>{{ v.code }} · day {{ v.analysisDay }}</h2>
            <div class="spacer"></div><StatusBadge :kind="v.route" compact /></div>
          <div class="panel-body stack">
            <div class="kv small">
              <dt>Global-visible events</dt><dd>{{ v.globalVisibleCount }}</dd>
              <dt>Known-restricted</dt><dd>{{ v.knownRestrictedCount }} (band {{ fmtBand(v.restrictedLower, v.restrictedUpper) }})</dd>
              <dt>Unobserved</dt><dd>{{ v.unobservedCount }} (band {{ fmtBand(v.unobservedLower, v.unobservedUpper) }})</dd>
              <dt>Combined range</dt><dd>{{ fmtBand(v.rangeLower, v.rangeUpper) }} per 100<span v-if="v.pointEstimate != null"> · point {{ v.pointEstimate }}</span></dd>
              <dt v-if="v.downgradeReason">Downgrade reason</dt><dd v-if="v.downgradeReason">{{ v.downgradeReason }}</dd>
              <dt v-if="v.coverageFailureDetail">Coverage failure</dt><dd v-if="v.coverageFailureDetail">{{ v.coverageFailureDetail }}</dd>
              <dt v-if="v.correlatedDetail">Correlation</dt><dd v-if="v.correlatedDetail">{{ v.correlatedDetail }}</dd>
              <dt>Created</dt><dd>{{ v.createdBy }} · {{ new Date(v.createdAt).toISOString().slice(0, 16).replace('T', ' ') }}</dd>
            </div>
            <div class="grid cols-2">
              <div>
                <div class="small faint" style="margin-bottom: 6px">Assumptions</div>
                <div v-for="a in parseAssumptions(v.assumptionsJson)" :key="a.code" class="notice small" style="margin-bottom: 6px">
                  <span class="chip">{{ a.code }}</span> {{ a.statement }}
                </div>
              </div>
              <div>
                <div class="small faint" style="margin-bottom: 6px">Tightening actions</div>
                <div v-for="(t, i) in parseTightening(v.tighteningJson)" :key="i" class="notice small" style="margin-bottom: 6px">
                  <strong>{{ t.action }}</strong>
                  <div class="faint">{{ t.effect }}</div>
                </div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
