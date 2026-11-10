package com.watchspaces.web;

import com.watchspaces.service.MetricsRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Records latency of REST calls. AI endpoints are excluded so the "non-AI P95 < 200 ms" number is honest. */
@Component
public class ApiLatencyFilter extends OncePerRequestFilter {
    private final MetricsRegistry metrics;

    public ApiLatencyFilter(MetricsRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !p.startsWith("/api/") || p.startsWith("/api/v1/metrics") || p.endsWith("/ai/ask");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            metrics.apiLatencyMs.record((System.nanoTime() - t0) / 1_000_000.0);
        }
    }
}
