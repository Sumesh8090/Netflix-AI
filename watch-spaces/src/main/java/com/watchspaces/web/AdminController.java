package com.watchspaces.web;

import com.watchspaces.core.HybridRecommender;
import com.watchspaces.entity.Title;
import com.watchspaces.repo.TitleRepository;
import com.watchspaces.service.MetricsRegistry;
import com.watchspaces.service.RecommendationService;
import com.watchspaces.service.TimelineService;
import com.watchspaces.util.InputSanitizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** ADMIN-only (enforced in SecurityConfig for /api/v1/admin/** and /api/v1/metrics/**). */
@RestController
@RequestMapping("/api/v1")
public class AdminController {

    public record CreateTitleRequest(@NotBlank @Size(max = 200) String name,
                                     @Min(1) @Max(86400) int durationSeconds,
                                     List<@Size(max = 40) String> genres,
                                     @Size(max = 500) String videoAssetUrl) { }

    private static final String TIMELINE_TYPES = "application/json,application/x-yaml,text/yaml,text/plain,application/yaml";

    private final TitleRepository titles;
    private final TimelineService timeline;
    private final MetricsRegistry metrics;
    private final RecommendationService recommendations;

    public AdminController(TitleRepository titles, TimelineService timeline, MetricsRegistry metrics,
                           RecommendationService recommendations) {
        this.recommendations = recommendations;
        this.titles = titles;
        this.timeline = timeline;
        this.metrics = metrics;
    }

    @PostMapping("/admin/titles")
    @ResponseStatus(HttpStatus.CREATED)
    public Dto.TitleView createTitle(@Valid @RequestBody CreateTitleRequest r) {
        Title t = new Title();
        t.name = InputSanitizer.plain(r.name());
        t.durationSeconds = r.durationSeconds();
        t.genres = r.genres() == null ? "" : String.join(",", r.genres().stream().map(InputSanitizer::plain)
                .map(g -> g.replace(",", " ")).toList());
        t.videoAssetUrl = InputSanitizer.plain(r.videoAssetUrl());
        return Dto.TitleView.of(titles.save(t));
    }

    /** Uploads (replaces) a title's timeline. Body is the raw JSON or YAML document. */
    @PutMapping(value = "/admin/titles/{titleId}/timeline", consumes = {"application/json", "application/x-yaml",
            "application/yaml", "text/yaml", "text/plain"})
    public TimelineService.IngestSummary uploadTimeline(@PathVariable long titleId, @RequestBody String body) {
        return timeline.replace(titleId, body);
    }

    /** Dry run: same validation, nothing is stored. */
    @PostMapping(value = "/admin/titles/{titleId}/timeline/validate", consumes = {"application/json",
            "application/x-yaml", "application/yaml", "text/yaml", "text/plain"})
    public TimelineService.Validation validateTimeline(@PathVariable long titleId, @RequestBody String body) {
        return timeline.validate(titleId, body);
    }

    /**
     * Offline Precision@k of the recommender over the interactions currently in the database (hold-out protocol in
     * {@link HybridRecommender#evaluate}). Also reports the best score any model could reach on that hold-out.
     */
    @GetMapping("/admin/recommendations/evaluate")
    public HybridRecommender.Evaluation evaluateRecommendations(@RequestParam(defaultValue = "5") int k) {
        return recommendations.evaluate(Math.max(1, Math.min(k, 20)));
    }

    /** Measured latency/drift numbers since the process started (P50/P95/max). */
    @GetMapping("/metrics/summary")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }
}
