package com.watchspaces.web;

import com.watchspaces.entity.WatchSpace;
import com.watchspaces.security.AuthUser;
import com.watchspaces.service.AiService;
import com.watchspaces.service.SessionService;
import com.watchspaces.ws.RoomLifecycle;
import com.watchspaces.ws.RoomRegistry;
import com.watchspaces.ws.RoomRuntime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/watch-spaces")
public class WatchSpaceController {

    public record CreateRequest(@NotNull Long titleId, @Size(max = 120) String name,
                                @Min(2) @Max(SessionService.MAX_ROOM_CAPACITY) Integer maxParticipants,
                                String aiVerbosity, Boolean votingEnabled, Boolean triviaEnabled, String privacy) { }

    public record JoinRequest(@NotBlank String inviteCode) { }

    /** Spec 7.3.3 request. {@code userId} is accepted for compatibility but ignored: the asker is always the token's user. */
    public record AskRequest(Long userId, @Min(0) Integer currentTs, @NotBlank @Size(max = AiService.MAX_QUESTION_CHARS) String question) { }

    private final SessionService sessions;
    private final AiService ai;
    private final RoomRegistry registry;
    private final RoomLifecycle lifecycle;

    public WatchSpaceController(SessionService sessions, AiService ai, RoomRegistry registry, RoomLifecycle lifecycle) {
        this.sessions = sessions;
        this.ai = ai;
        this.registry = registry;
        this.lifecycle = lifecycle;
    }

    /** Spec 7.3.1. HOST or ADMIN only (enforced in SecurityConfig). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateRequest r) {
        WatchSpace w = sessions.create(user, new SessionService.CreateRequest(r.titleId(), r.name(), r.maxParticipants(),
                r.aiVerbosity(), r.votingEnabled(), r.triviaEnabled(), r.privacy()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("watchSpaceId", w.id);
        out.put("inviteCode", w.inviteCode);
        out.put("status", w.status.name().toLowerCase());
        out.put("hostUserId", w.host.id);
        out.put("createdAt", w.createdAt);
        out.put("websocketPath", "/ws/watch-spaces/" + w.id);
        return out;
    }

    /** Resolve an invite code; returns the id to connect the WebSocket to. */
    @PostMapping("/join")
    public Map<String, Object> join(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody JoinRequest r) {
        WatchSpace w = sessions.joinByCode(user, r.inviteCode());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("watchSpace", Dto.WatchSpaceView.of(w, true));
        out.put("websocketPath", "/ws/watch-spaces/" + w.id);
        return out;
    }

    @GetMapping("/{id}")
    public Dto.WatchSpaceView get(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return sessions.view(id, user);
    }

    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> messages(@AuthenticationPrincipal AuthUser user, @PathVariable long id,
                                              @RequestParam(required = false) Long beforeSeq,
                                              @RequestParam(defaultValue = "50") int limit) {
        return sessions.history(id, user, beforeSeq, limit);
    }

    /**
     * Spec 7.3.3. Grounded answer from the title's metadata; the claimed {@code currentTs} is clamped to the room's real
     * position (so no spoilers). The answer is also broadcast to the room as {@code room.ai.answer}.
     */
    @PostMapping("/{id}/ai/ask")
    public Map<String, Object> ask(@AuthenticationPrincipal AuthUser user, @PathVariable long id,
                                   @Valid @RequestBody AskRequest r) {
        RoomRuntime rt = ai.liveRoom(id, user.id());
        if (!rt.aiLimiter.tryAcquire(user.id(), System.currentTimeMillis())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Please wait before asking the co-pilot again");
        }
        Dto.AskResult a = ai.ask(rt, user.id(), r.currentTs(), r.question());
        registry.broadcast(rt, "room.ai.answer", ai.answerPayload(user.id(), r.question(), a));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", a.answer());
        out.put("sourceEvents", a.sourceEvents());
        out.put("generatedAt", a.generatedAt());
        out.put("questionId", a.questionId());
        out.put("grounded", a.grounded());
        out.put("sources", a.sources());
        out.put("latencyMs", a.latencyMs());
        return out;
    }

    /** Host or admin ends the session; the analytics snapshot is frozen and returned. */
    @PostMapping("/{id}/end")
    public SessionService.Analytics end(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        WatchSpace w = sessions.get(id);
        if (!(user.isAdmin() || w.host.id == user.id())) {
            throw ApiException.forbidden("Only the host or an admin can end this Watch Space");
        }
        var rt = registry.find(id);
        if (rt.isPresent()) {
            SessionService.Analytics a = lifecycle.end(rt.get(), user.id(), "host_ended");
            if (a != null) {
                return a;
            }
        }
        return sessions.finish(id); // never went live, or already ended: idempotent
    }

    @GetMapping("/{id}/analytics")
    public SessionService.Analytics analytics(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return sessions.analytics(id, user);
    }
}
