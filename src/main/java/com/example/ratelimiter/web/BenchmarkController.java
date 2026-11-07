package com.example.ratelimiter.web;

import com.example.ratelimiter.benchmark.BenchmarkResult;
import com.example.ratelimiter.benchmark.BenchmarkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/benchmark")
public class BenchmarkController {

    private final BenchmarkService service;

    public BenchmarkController(BenchmarkService service) {
        this.service = service;
    }

    /**
     * Replays one identical traffic pattern through every algorithm.
     * Open /benchmark.html for the charts.
     */
    @GetMapping("/run")
    public ResponseEntity<?> run(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "10000") long windowMs,
            @RequestParam(defaultValue = "30000") long durationMs,
            @RequestParam(defaultValue = "5") int steadyRps,
            @RequestParam(defaultValue = "100") int burstSize,
            @RequestParam(defaultValue = "9900,10100,20000") String burstTimes) {

        if (limit < 1 || windowMs < 1 || durationMs < 1 || durationMs > 300_000
                || steadyRps < 0 || steadyRps > 1000 || burstSize < 0 || burstSize > 10_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Parameter out of range"));
        }
        List<Long> bursts;
        try {
            bursts = burstTimes.isBlank() ? List.of()
                    : Arrays.stream(burstTimes.split(",")).map(String::trim).map(Long::parseLong).toList();
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "burstTimes must be comma-separated millisecond offsets"));
        }
        BenchmarkResult result = service.run(limit, windowMs, durationMs, steadyRps, burstSize, bursts);
        return ResponseEntity.ok(result);
    }
}
