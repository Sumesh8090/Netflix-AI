package com.watchspaces.web;

import com.watchspaces.entity.Title;
import com.watchspaces.repo.TitleRepository;
import com.watchspaces.service.TimelineService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/titles")
public class TitleController {

    public record TimelineResponse(long titleId, List<Dto.TimelineEventView> events) { }

    private final TitleRepository titles;
    private final TimelineService timeline;

    public TitleController(TitleRepository titles, TimelineService timeline) {
        this.titles = titles;
        this.timeline = timeline;
    }

    @GetMapping
    public List<Dto.TitleView> list() {
        return titles.findAll().stream().map(Dto.TitleView::of).toList();
    }

    @GetMapping("/{titleId}")
    public Dto.TitleView get(@PathVariable long titleId) {
        return Dto.TitleView.of(titles.findById(titleId).orElseThrow(() -> ApiException.notFound("Title")));
    }

    /** Events whose window overlaps [from, to] seconds. Defaults to the whole title. */
    @GetMapping("/{titleId}/timeline")
    public TimelineResponse timeline(@PathVariable long titleId,
                                     @RequestParam(required = false) Integer from,
                                     @RequestParam(required = false) Integer to) {
        Title t = titles.findById(titleId).orElseThrow(() -> ApiException.notFound("Title"));
        int f = from == null ? 0 : from;
        int e = to == null ? t.durationSeconds : to;
        if (f < 0 || e < f) {
            throw ApiException.badRequest("Require 0 <= from <= to");
        }
        return new TimelineResponse(titleId,
                timeline.range(titleId, f, e).stream().map(Dto.TimelineEventView::of).toList());
    }
}
