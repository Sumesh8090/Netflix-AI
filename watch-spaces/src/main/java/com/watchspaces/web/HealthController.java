package com.watchspaces.web;

import com.watchspaces.ws.RoomRegistry;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Health for the API layer and the real-time gateway (public, suitable for an uptime check). */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
    private final RoomRegistry rooms;

    public HealthController(RoomRegistry rooms) {
        this.rooms = rooms;
    }

    @GetMapping("/api")
    public Map<String, String> api() {
        return Map.of("status", "UP");
    }

    @GetMapping("/gateway")
    public Map<String, Object> gateway() {
        return Map.of("status", "UP", "liveRooms", rooms.all().size());
    }
}
