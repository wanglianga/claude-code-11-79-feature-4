# APEX-3 · Dual-Domain Sync & Cross-Border Egress Control

A platform for a multinational clinical trial that keeps China source records resident locally while letting a
global team use **de-identified derived data** only inside an approved purpose, field, site, observation-window and
protocol-version envelope. It implements the consent ledger, protocol catalogue, dual-domain eligibility
evaluator, watermark/back-fill coordination, snapshot delivery compiler, withdrawal propagator and post-outage
recovery verifier, together with the acceptance scenario for subjects A/B/C/D at analysis day 16.

Stack: **Vue 3 (Vite)** · **Spring Boot 3.5 / JDK** · **PostgreSQL**. Single page app is bundled into the Spring
Boot jar and served together with the REST API.

---

## 1. Quick start

### Option A — Docker Compose (one command)

```bash
cd /workspace/79
docker compose up --build
# open http://localhost:8080   (Log in as admin / demo123)
```

Compose builds the frontend, bundles it into the Spring Boot jar, starts PostgreSQL and the app, and seeds the
demo scenario on first boot. Health check: `curl http://localhost:8080/actuator/health`.

> The provided container in this workspace has **no Docker daemon**, so Compose was not executed here. The same
> image build was validated by running the packaged jar directly (Option B) plus the integration/UI test suites.

### Option B — Run locally (used for the verification below)

Prerequisites already present in this environment: `/opt/jdk-25`, `/opt/maven`, `/opt/node-22`, PostgreSQL on
`127.0.0.1:5432` (user/db `ccdev`), Redis not required.

```bash
# 1. database (already running in the workspace image)
psql "host=127.0.0.1 user=ccdev password=ccdev dbname=postgres" -c "CREATE DATABASE ctrial;"   # once

# 2. build the SPA, bundle it, and build the backend jar
cd frontend && npm install && npm run build && cd ..
cd backend  && mvn -DskipTests package

# 3. run
java -jar backend/target/ctrial-sync-1.0.0.jar
# open http://localhost:8080  — schema and demo data seed automatically on first start
```

Frontend developer mode (hot reload, proxies `/api` to :8080):

```bash
cd frontend && npm run dev      # http://localhost:5173
```

The backend reads `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `DEMO_SEED` from the environment
(defaults in `backend/src/main/resources/application.yml`).

---

## 2. Test accounts (HTTP Basic)

All demo passwords are `demo123`. Roles are enforced server-side.

| Username | Role | Can do |
|----------|------|--------|
| `admin`    | ADMIN | everything |
| `global`   | GLOBAL_ANALYST (+AUDITOR) | compile cross-border deliveries, read snapshots |
| `china`    | CN_ANALYST | drive watermark / transfer / link operations |
| `auditor`  | AUDITOR | read-only audit, recovery, quarantine |
| `sponsor`  | SPONSOR | record consent & protocol versions |

Role checks that matter and were exercised: a China analyst gets **403** on `POST /api/deliveries/**`; an auditor
gets **403** on `POST /api/consents`.

---

## 3. How the domain model maps to the requirements

| Requirement | Where it lives |
|-------------|----------------|
| Consent ledger: subject, purpose, field group, region, validity interval | `ConsentEntry` — two clocks: `effectiveFrom`/`effectiveTo` (local signature) and `recordedAt` (receipt) |
| Consent validity uses the **local** signed/withdrawn instant, not receipt time | `EligibilityEngine.resolveConsent`, `EligibilityService.loadWorld` (filters on `effectiveFrom ≤ analysis`) |
| Protocol catalogue: version, approvals, effective time, applicable sites | `ProtocolVersion.fullyApproved/approvedAt/activeAt/appliesToSite` |
| Amendment applies only from explicit effective date, only after ethics **and** sponsor approval | `EligibilityEngine.resolveProtocol` |
| Local event ledger: stable id, event time, receipt time, revision chain, source, void | `LocalEvent` (unique on `(sourceSystem,domain,sourceSeq)` and `(stableId,revision)`; no direct identifiers) |
| No raw identity leaves China | `Subject.localIdentity` never projected; `DeliveryRecord` has only pseudonym + projected fields |
| Eligibility per record: 本地可用 / 全球可用 / 待定 + reasons | `Eligibility.LOCAL_AVAILABLE / GLOBAL_AVAILABLE / PENDING / BLOCKED` + `reasonCodes` |
| Intersection (not union) of consent, protocol, approval, purpose scope | `EligibilityEngine.evaluate` — sequential gates, any failure blocks, any undeterminable pends |
| Watermark coordinator: source-confirmed / transfer-ack / analysis, per domain | `Watermark` (three counters), `StateService`, `coverage` view |
| Gaps are UNCONFIRMED, never zero | `WATERMARK_UNCONFIRMED` reason + analysis cap; UI gap band |
| Snapshot compiler pins versions, member summary, field projection, filter, watermark | `DeliveryService.compile`, `Delivery` |
| Withdrawal propagator stops future queries, applies pre-registered retention/destruction | `RevocationService`, `DispositionRule`, `DispositionExecution`; historical delivery summary kept |
| Recovery verifier: gaps, duplicate re-sends, cross-domain digest agreement | `RecoveryService.run` → `RecoveryCheck` |
| Two reconstruction routes producing identical legal sets | `EligibilityRunService` (MATERIALIZED vs REPLAY) + equivalence test; both call one engine |
| Stale snapshots never authorise a new read; old deliveries not pretended erased | `invalidateRuns`, `Delivery.accessRevoked` with summary/digests intact |
| Retransmission after restore not double-counted | `EventIngestService` transport-identity dedupe + `TransportReceipt` |
| Withdrawal/correction race adjudicated by approved rule, else quarantined | `EventIngestService` + `TieBreakRule` + `QuarantineItem` |

### Reconstruction routes

* **MATERIALIZED** — `POST /api/runs/materialize?day=16` evaluates once and persists one row per candidate. Later
  consent/protocol revisions mark affected runs `STALE`. Queries read the stored rows directly; a stale run is
  never consulted to authorise a read.
* **REPLAY** — `GET /api/runs/replay?day=16` rebuilds from the append-only ledgers at the chosen instant,
  persisting nothing; it requires the watermark completeness proof returned alongside.

Both call the same pure `EligibilityEngine`. The acceptance check `ALL/EQ` and `GET /api/runs/compare?day=16`
assert the two routes produce the same legal membership, verdicts and reason codes. At day 16 both report
**13 legal rows, 0 differences**.

---

## 4. The acceptance scenario (analysis day 16)

Study day 1 = `2026-03-02T09:00Z`; analysis instant for day N = `23:00Z` on that day.
Sites: `CN-01` Shanghai (healthy), `CN-02` Wuhan (link stalled), `SG-01` Singapore (healthy).

| Subject | Timeline | Required outcome |
|---------|----------|------------------|
| **A** | signs China-only consent D10; visit occurs D11; uploaded D15 | **not** global-available (reason = consent scope, not upload delay); locally available |
| **B** | AE occurs D11; withdraws global purpose D12 (signed 09:00, received 20:00); new visit D13 | D11 AE held under restricted retention but reuse globally blocked; D13 visit has no basis; local use continues |
| **C** | protocol v2.0 approved D13, effective D14 at CN-01; imaging D13 and D15 | D13 under v1.2; D15 under v2.0 carrying `IMAGING_ADC`/`IMAGING_TUMOR_VOL`; v2.0 never applies to other sites |
| **D** | CN-02 link stalled after D9; randomisation D5, visit D12 | D5 globally available; D12 **PENDING / UNCONFIRMED** globally, locally available; gap shown, not zero |

If a historical delivery contains B's D11 row (e.g. compile at day 11), propagating B's withdrawal revokes
access to it immediately and applies `DR-RETAIN-25Y` (restricted retention) while the delivery's frozen member
summary and digests stay intact for audit — the delivery is not pretended to have been erased.

---

## 5. Verification (what was actually run here)

All commands below were executed in this workspace against a live instance. Results are quoted verbatim.

### 5.1 Backend integration tests — 25/25 pass

```bash
cd backend && mvn test
```
```
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
Covers: A/B/C/D rules; intersection semantics; both reconstruction routes agreeing (read-only and persisted);
stale-snapshot invalidation; transport dedupe; conflicting-duplicate quarantine; withdrawal/correction race
quarantine; delivery projection has no identity fields and survives revocation as an intact summary. Plus the
observability suite (`ObservabilityRulesTest`, 10 tests): five-state classification, known-restricted isolation,
right-censoring, realised-interval-only delay calibration, correlated-source refusal of the capture model,
closed-site coverage-failure escalation, versioned estimate retention, and the identifier-free global projection.

### 5.2 Frontend render tests (live backend) — 8/8 pass

```bash
cd frontend && npm run test        # vitest + happy-dom, mounts the real views against http://127.0.0.1:8080
```
```
Test Files  1 passed (1)
     Tests  8 passed (8)
```
Asserts the Overview shows **15/15**, the Acceptance cards list A1/B1/C1/D2, the Eligibility pivot renders both
planes and subject D as Pending, the Consent / Deliveries / Watermarks views render without error, and the
Observability view renders all five fragment-state labels with the withdrawn-consent subject present.

### 5.3 End-to-end API acceptance (live jar)

| Step | Result |
|------|--------|
| `GET /api/acceptance?day=16` | **15/15 acceptance checks pass** (A1–A3, B1–B5, C1–C3, D1–D3, EQ) |
| `POST /api/deliveries/compile {day:11}` | `DLV-D11-001`, 2 members incl. `PSN-B2`, digest `a5110f88…` |
| `POST /api/deliveries/compile {day:16, route:REPLAY}` | `DLV-D16-001`, 4 members (C×3, D randomisation) |
| `POST /api/revocations/propagate {consentEntryId:3}` | 2 snapshot runs invalidated, 1 delivery access-revoked, 1 row restricted under `DR-RETAIN-25Y` |
| `GET /api/deliveries/DLV-D11-001` after revocation | `readable=false`, `status=ACCESS_REVOKED`, content digest intact, 2 summary lines kept |
| `GET /api/deliveries/DLV-D16-001` | `readable=true`, `status=ACTIVE` (unaffected) |
| `POST /api/recovery/run {site:CN-02}` | `status=FINDINGS`, 2 gaps, 0 duplicate resends, 0 digest mismatches |
| exact re-send of `VST-D4-V1` seq 12 | `duplicate=true` — "Retransmission … acknowledged; counted once." event count stays 9 |
| conflicting re-send (different payload) | `duplicate=true, quarantined=true` → `Q-0001-DUP` |
| `GET /api/watermarks?day=16` | CN-02 source confirmed 12 / ack 9 / analysis 9 → D12 **UNCONFIRMED** |

The pinned watermark string for day 16 is
`CN-01:AE=15(src=15,ack=15); … CN-02:VISIT=12(src=12,ack=9)…` (the CN-02 analysis stays at 9).

### 5.4 Observability & sensitivity API (live jar, seeded cohort, day 16)

| Endpoint / action | Observed result |
|-------------------|-----------------|
| `GET /api/observability/ledger?day=16` | 13 fragments: **3 KNOWN_RESTRICTED, 3 INCLUDED, 1 KNOWN_PENDING_ARRIVAL, 1 AUDIT_FLAGGED_MISSING, 5 UNOBSERVED_NO_ANCHOR**; B2 (withdrawn + SAE) = KNOWN_RESTRICTED |
| `GET /api/observability/global?day=16` | permitted ranges + status summaries; string contains **no `PSN-`** |
| `GET /api/observability/expected?day=16` | M13 (randomised D15): 7 right-censored, 0 overdue; framework totals 55 expected / 39 censored / 10 overdue-unobserved |
| `GET /api/observability/delay?day=16` | 2 realised intervals fitted; 2 in-flight candidates excluded; strata split by site/period/grade; all flagged uncalibrated (<3) |
| `GET /api/observability/estimate?day=16&route=PARTIAL_BOUNDS` | restricted band `[33.33, 133.33]` (local, N=3); unobserved band `[25, 30]` (audit floor 0.25 + clinical ceiling 0.3); combined `[7.69, 50]` |
| `GET …/estimate?day=16&route=DELAY_CAPTURE_MODEL` | **downgraded** to PARTIAL_BOUNDS — "two source kinds share one manual register (correlated)" |
| coverage-failure check | `active=true`, site `CN-03`, conditions include "pause trend conclusions" |
| `POST /api/observability/estimates {day:16,versionGroup:EST-D16}` | `EST-D16-v1`; a second publish at day 20 → `EST-D16-v2`; **v1 retained** |
| `POST /api/observability/estimates` as `china` | **403** (only GLOBAL_ANALYST/ADMIN may publish) |

---

## 6. Using the console

Open `http://localhost:8080` and sign in (the login screen lists the demo accounts; click one to prefill).

* **Overview** — acceptance score, watermark gaps, deliveries, subjects.
* **Acceptance** — the four subject cards with expected vs observed and reason codes.
* **Consent & protocol** — append grants/withdrawals and protocol versions; per-subject timelines; approved
  envelopes. Recording a withdrawal invalidates affected snapshot runs immediately.
* **Eligibility** — the dual-plane pivot (replay or materialised), with a live route-equivalence badge and the
  source event ledger. The top-bar **analysis day** changes the instant for every screen.
* **Watermarks** — per-domain three-counter bars with an UNCONFIRMED gap band; confirm source / ack transfer /
  fold analysis / break & restore link; run a recovery check.
* **Deliveries** — compile by either route, inspect the pinned envelope, frozen member summary and projected
  records; revocation state is visible.
* **Observability & sensitivity** — see §9: the five-state classification ledger, its global-safe projection, the
  expected-opportunity framework, delay diagnostics, the partial-identification / capture-model estimator, and
  versioned estimate records.
* **Withdrawal** — propagate a recorded withdrawal, register disposition and tie-break rules, see executions.
* **Recovery** — recovery reports (expand for per-domain digests), simulate a post-restore re-send, resolve
  quarantined races.
* **Audit** — the append-only audit trail of every regulator-relevant transition.

### Try the headline flow

1. As **global**, Deliveries → set analysis day `11` → *Compile delivery* (contains B's D11 AE).
2. As **admin**, Withdrawal → *Propagate* B's withdrawal.
3. Back in Deliveries → open `DLV-D11-001`: content is revoked, but the frozen summary and digests remain.
4. Set day `16`, Acceptances → all checks pass; Eligibility → D's D12 visit is Pending globally, available locally.

---

## 7. Design decisions worth noting

* **One engine, two routes.** Materialisation is a cache over the same `EligibilityEngine`; it can never diverge
  because it is not a second implementation. Snapshots are `STALE`d on later changes and are refused for new
  reads, so an old delivery is never a standing authorisation.
* **Intersection, literally.** Every gate must pass; a single failing gate blocks, a single undeterminable gate
  pends. There is no union anywhere in the evaluator.
* **Two clocks, on purpose.** All validity uses the local signature/withdrawal instant; the receipt instant is
  only an ordering tie-breaker. B's withdrawal is effective at the moment it was signed, not when it reached us.
* **Gaps are first-class.** The analysis watermark can never fold past the transfer acknowledgement, and anything
  above it is `PENDING`, never reported as absent. The UI draws the unconfirmed band instead of a zero.
* **History is preserved.** Revoking a delivery sets `accessRevoked` and applies the registered disposition, but
  the legal-member summary and digests are frozen and kept. Nothing claims the delivery never existed.
* **Isolate, don't guess.** A withdrawal/correction race with equal effective instants and no registered
  tie-break rule is quarantined; it never silently picks a side.

---

## 8. Project layout

```
backend/
  src/main/java/com/ctrial/
    model/        JPA entities + Enums (the rule vocabulary); observability entities
                  (TreatmentPeriod, SubjectExposure, ObservationSource, SaeRecord,
                  AnchorRecord, AuditFinding, PlannedVisitTemplate, ObservabilityEstimate)
    service/      EligibilityEngine (pure), EligibilityService, EligibilityRunService,
                  DeliveryService, RevocationService, RecoveryService, EventIngestService,
                  StateService, AcceptanceService, AuditService,
                  ObservabilityLedgerService, ExpectedOpportunityService,
                  DelayCalibrationService, ObservabilityEstimateService
    web/          ReadController, CommandController, ObservabilityController, SpaConfig
    config/       SecurityConfig, DemoSeeder, ObservabilitySeeder
  src/test/java/com/ctrial/AcceptanceRulesTest.java, ObservabilityRulesTest.java
frontend/
  src/            App, views/* (incl. Observability.vue), components/*, lib/api.js, lib/store.js, ui.test.js
Dockerfile, compose.yaml
```

---

## 9. Observability, expected events, delay calibration & partial identification

When a global serious-AE rate is judged before a China centre has fully entered the analysis plane, the "gap" is
**not one thing**. This module treats five evidence states as distinct, never interchangeable:

| Fragment state | Meaning | How it feeds the estimate |
|----------------|---------|---------------------------|
| `INCLUDED` | Inside the global analysis plane (returned events, or fully-observed genuine zero) | Counted directly |
| `KNOWN_RESTRICTED` | Locally known but consent forbids egress (e.g. withdrawn global consent, yet an SAE is on file) | Bounded **locally** with a finite local denominator; **never** mixed into the unobserved rate |
| `KNOWN_PENDING_ARRIVAL` | Registered, still inside the expected return window | May still arrive |
| `AUDIT_FLAGGED_MISSING` | Past the window, but an independent audit anchor found the record | A known gap, not an unknown |
| `UNOBSERVED_NO_ANCHOR` | No schedule / dispensing / safety-hotline source and no anchor | The **only** genuinely-unknown bucket; it alone drives the sensitivity band |

### 9.1 What each component does

* **Observability classification ledger** (`GET /api/observability/ledger`) — partitions every subject by
  **site × treatment period × risk stratum** and assigns exactly one of the five states. The global side receives
  only `GET /api/observability/global` — permitted **count ranges** and status summaries, **no pseudonyms** (verified:
  the projection string contains no `PSN-`). Output for the seeded cohort at day 16: 3 restricted, 3 included,
  1 pending-arrival, 1 audit-flagged, 5 unobserved; B2 (withdrawn global consent with an SAE) is `KNOWN_RESTRICTED`
  and its event is **not** in the global-visible count.
* **Expected-event framework** (`GET /api/observability/expected`) — generates the observation opportunities that
  *should* exist from randomisation, planned visits, planned dispensing, the site's closure day and audit sampling.
  A visit not yet due is **right-censored** (not late, not "no event"); a past-window visit with no return is an
  *unobserved opportunity*, never "no event". M13 (randomised day 15) shows 7 censored visits and 0 overdue.
* **Delay calibrator** (`GET /api/observability/delay`) — estimates what fraction of in-flight events may still
  arrive using **only realised return intervals** (events that actually returned). In-flight events contribute
  nothing to the distribution. Intervals are kept split by **site × period × grade**; strata with <3 realised
  returns are flagged uncalibrated rather than pooled silently.
* **Partial-identification estimator** (`GET /api/observability/estimate`) — separates `KNOWN_RESTRICTED` (bounded
  locally on its own finite denominator) from the genuinely unobserved (a sensitivity band from independent-anchor
  coverage, the pre-declared clinical upper limit and the audit finding rate). **No representativeness assumption.**
* **Sensitivity & assumptions console** (the Vue view) — shows the global summary, the identifiable range, the key
  assumptions, the named actions that would tighten the range, coverage-failure escalation and source-correlation
  warnings.

### 9.2 Route selection by anchor completeness

* **`PARTIAL_BOUNDS`** (default) — assumption-light bounds. The global-visible rate is the floor; the restricted
  and unobserved masses widen the ceiling. Conservative, and auditable even when anchors are scarce.
* **`DELAY_CAPTURE_MODEL`** — narrower, *only* when independent anchors are sufficient **and**
  schedule/dispensing/hotline coverage is adequate **and** the sources are independent. It is **refused and
  downgraded** to `PARTIAL_BOUNDS` whenever any precondition fails — a model is never substituted for evidence that
  does not exist. In the seed it is downgraded for two independent reasons (correlated hotline/audit register, and
  the CN-03 coverage failure).

### 9.3 Mandated behaviours exercised by the seed

| Scenario in the seed | Ledger / estimator behaviour |
|----------------------|------------------------------|
| B2 withdrew global consent **and** has an SAE on file | `KNOWN_RESTRICTED`; only its **global visibility** changes; it is **not** counted as unobserved (test `withdrawnConsentWithSaeIsKnownRestrictedAndNotCountedAsUnobserved`) |
| A visit not yet due (M13 randomised day 15) | **Right-censored**; not late, not "no event" (test `notYetDueVisitsAreRightCensoredNotLateOrZero`) |
| Hotline and audit share one manual safety register | Flagged **correlated**; capture-model narrowing **prohibited** and the route downgraded (test `correlatedSourcesAreFlaggedAndPreventCaptureModel`) |
| CN-03 closed on day 4 with post-closure dispensing but zero returns | Escalated as a **coverage failure** the delay model cannot explain; gives the "widen audit / pause trend conclusion" conditions (test `closedSiteSilenceEscalatesAsCoverageFailure`) |
| A later re-estimate tightens the range | Written as a **new version**; the earlier published record is retained and still explained (test `reEstimateWritesNewVersionAndKeepsTheOldOne`) |

### 9.4 API quick reference

```bash
A='admin:demo123'
curl -s -u $A 'http://127.0.0.1:8080/api/observability/ledger?day=16'      # local, 5-state fragments (pseudonyms)
curl -s -u $A 'http://127.0.0.1:8080/api/observability/global?day=16'      # global-safe ranges + summaries (no IDs)
curl -s -u $A 'http://127.0.0.1:8080/api/observability/expected?day=16'    # expected opportunities (censoring)
curl -s -u $A 'http://127.0.0.1:8080/api/observability/delay?day=16'       # realised-interval calibration
curl -s -u $A 'http://127.0.0.1:8080/api/observability/estimate?day=16&route=DELAY_CAPTURE_MODEL'  # downgraded
# publish a versioned record (GLOBAL_ANALYST or ADMIN only; a re-publish writes a new version)
curl -s -u global:demo123 -X POST 'http://127.0.0.1:8080/api/observability/estimates' \
  -H 'Content-Type: application/json' -d '{"day":16,"route":"PARTIAL_BOUNDS","versionGroup":"EST-D16"}'
curl -s -u $A 'http://127.0.0.1:8080/api/observability/estimates'          # version history
```

`POST /api/observability/**` is restricted to `GLOBAL_ANALYST`/`ADMIN` (a China analyst gets **403**).

