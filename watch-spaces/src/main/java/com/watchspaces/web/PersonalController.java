package com.watchspaces.web;

import com.watchspaces.security.AuthUser;
import com.watchspaces.service.DashboardService;
import com.watchspaces.service.RecommendationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Dashboard, interaction recording and the recommendation rail (Epic 3). */
@RestController
@RequestMapping("/api/v1")
public class PersonalController {

    public record InteractionRequest(@NotNull Long titleId, @Min(0) int watchedSeconds, boolean completed,
                                     @Min(1) @Max(5) Integer rating) { }

    private final DashboardService dashboard;
    private final RecommendationService recs;

    public PersonalController(DashboardService dashboard, RecommendationService recs) {
        this.dashboard = dashboard;
        this.recs = recs;
    }

    @GetMapping("/dashboard")
    public DashboardService.Dashboard dashboard(@AuthenticationPrincipal AuthUser me) {
        return dashboard.forUser(me.id());
    }

    @GetMapping("/recommendations")
    public List<RecommendationService.RecView> recommendations(@AuthenticationPrincipal AuthUser me,
                                                               @RequestParam(defaultValue = "10") int limit) {
        return recs.forUser(me.id(), Math.max(1, Math.min(limit, 50)));
    }

    @PostMapping("/interactions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> interaction(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody InteractionRequest r) {
        var i = recs.record(me.id(), r.titleId(), r.watchedSeconds(), r.completed(), r.rating(), null);
        return Map.of("id", i.id, "titleId", r.titleId());
    }
}
