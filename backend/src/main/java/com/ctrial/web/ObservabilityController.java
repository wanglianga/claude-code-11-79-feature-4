package com.ctrial.web;

import com.ctrial.model.Enums.EstimateRoute;
import com.ctrial.model.ObservabilityEstimate;
import com.ctrial.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Observability &amp; sensitivity API: the classification ledger, its global-safe
 * projection, the expected-opportunity framework, delay diagnostics, the
 * partial-identification / capture-model estimator, and versioned estimate records.
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final ObservabilityLedgerService ledger;
    private final ExpectedOpportunityService expected;
    private final DelayCalibrationService delay;
    private final ObservabilityEstimateService estimates;

    public ObservabilityController(ObservabilityLedgerService ledger, ExpectedOpportunityService expected,
                                   DelayCalibrationService delay, ObservabilityEstimateService estimates) {
        this.ledger = ledger;
        this.expected = expected;
        this.delay = delay;
        this.estimates = estimates;
    }

    private String actor(Authentication auth) {
        return auth == null ? "anonymous" : auth.getName();
    }

    /** Full local ledger (contains pseudonyms) — the source-side classification. */
    @GetMapping("/ledger")
    public ObservabilityLedgerService.Ledger ledger(@RequestParam(defaultValue = "16") int day,
                                                    @RequestParam(defaultValue = "10") int windowDays) {
        return ledger.build(day, windowDays);
    }

    /** Global-safe projection: permitted count ranges + status summaries only. */
    @GetMapping("/global")
    public ObservabilityLedgerService.GlobalProjection global(@RequestParam(defaultValue = "16") int day,
                                                              @RequestParam(defaultValue = "10") int windowDays) {
        return ledger.projectGlobal(day, windowDays);
    }

    @GetMapping("/expected")
    public ExpectedOpportunityService.Framework expected(@RequestParam(defaultValue = "16") int day) {
        return expected.build(day);
    }

    @GetMapping("/delay")
    public DelayCalibrationService.Diagnostics delay(@RequestParam(defaultValue = "16") int day,
                                                     @RequestParam(defaultValue = "10") int windowDays) {
        return delay.build(day, windowDays);
    }

    /** Compute a live estimate at an analysis day (not persisted). */
    @GetMapping("/estimate")
    public ObservabilityEstimateService.Computation estimate(@RequestParam(defaultValue = "16") int day,
                                                             @RequestParam(defaultValue = "PARTIAL_BOUNDS") String route,
                                                             @RequestParam(defaultValue = "10") int windowDays) {
        return estimates.compute(day, parseRoute(route), windowDays);
    }

    /** Persist a versioned estimate record; a re-estimate writes a NEW version, never overwrites. */
    public record PublishRequest(Integer day, String route, String versionGroup) {
    }

    @PostMapping("/estimates")
    public ResponseEntity<?> publish(@RequestBody(required = false) PublishRequest req, Authentication auth) {
        int day = req == null || req.day() == null ? 16 : req.day();
        EstimateRoute route = parseRoute(req == null || req.route() == null ? "PARTIAL_BOUNDS" : req.route());
        String group = req == null || req.versionGroup() == null || req.versionGroup().isBlank()
                ? "EST-D" + day : req.versionGroup();
        ObservabilityEstimateService.Computation c = estimates.compute(day, route);
        ObservabilityEstimate saved = estimates.materialize(c, group, actor(auth));
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/estimates")
    public List<ObservabilityEstimate> estimates() {
        return estimates.versions();
    }

    private EstimateRoute parseRoute(String route) {
        try {
            return EstimateRoute.valueOf(route.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return EstimateRoute.PARTIAL_BOUNDS;
        }
    }
}
