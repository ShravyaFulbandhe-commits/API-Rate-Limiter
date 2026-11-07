package com.example.ratelimiter.web;

import com.example.ratelimiter.config.RateLimiterRegistry;
import com.example.ratelimiter.core.Algorithm;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

/** Runtime control plane (not rate limited: it lives under /admin, not /api). */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final RateLimiterRegistry registry;

    public AdminController(RateLimiterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/algorithm")
    public Map<String, Object> current() {
        return Map.of(
                "active", registry.activeAlgorithm(),
                "available", Arrays.asList(Algorithm.values()),
                "trackedKeys", registry.active().trackedKeys());
    }

    @PutMapping("/algorithm")
    public ResponseEntity<?> switchTo(@RequestParam("name") String name) {
        try {
            Algorithm algorithm = Algorithm.valueOf(name.trim().toUpperCase());
            registry.setActive(algorithm);
            return ResponseEntity.ok(Map.of("active", algorithm));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown algorithm: " + name,
                    "available", Arrays.asList(Algorithm.values())));
        }
    }
}
