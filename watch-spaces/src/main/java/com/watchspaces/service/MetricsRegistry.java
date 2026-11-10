package com.watchspaces.service;

import com.watchspaces.core.LatencyStats;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** In-process measurements reported by GET /api/v1/metrics/summary. All values are real samples, never estimates. */
@Component
public class MetricsRegistry {
    public final LatencyStats apiLatencyMs = new LatencyStats(5000);
    public final LatencyStats aiLatencyMs = new LatencyStats(2000);
    public final LatencyStats fanoutMs = new LatencyStats(5000);
    public final LatencyStats syncDriftAbsMs = new LatencyStats(5000);
    public final LatencyStats triviaLagMs = new LatencyStats(2000);
    public final AtomicLong wsMessages = new AtomicLong();
    public final AtomicLong wsErrors = new AtomicLong();

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("apiLatencyMs_nonAi", apiLatencyMs.summary());
        m.put("aiLatencyMs", aiLatencyMs.summary());
        m.put("wsFanoutMs", fanoutMs.summary());
        m.put("syncDriftAbsMs", syncDriftAbsMs.summary());
        m.put("triviaLagMs", triviaLagMs.summary());
        m.put("wsMessages", wsMessages.get());
        m.put("wsErrors", wsErrors.get());
        return m;
    }
}
