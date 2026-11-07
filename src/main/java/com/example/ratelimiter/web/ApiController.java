package com.example.ratelimiter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** A trivial protected resource so there is something to rate limit. */
@RestController
@RequestMapping("/api")
public class ApiController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("message", "pong", "time", Instant.now().toString());
    }
}
