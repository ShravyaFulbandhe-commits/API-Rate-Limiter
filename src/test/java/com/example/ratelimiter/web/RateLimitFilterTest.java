package com.example.ratelimiter.web;

import com.example.ratelimiter.config.RateLimiterRegistry;
import com.example.ratelimiter.core.Algorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimiter.algorithm=FIXED_WINDOW",
        "ratelimiter.limit=3",
        "ratelimiter.window-seconds=3600"   // long window so the test can't straddle a boundary
})
class RateLimitFilterTest {

    @Autowired MockMvc mvc;
    @Autowired RateLimiterRegistry registry;

    @AfterEach
    void restoreAlgorithm() {
        registry.setActive(Algorithm.FIXED_WINDOW);
    }

    @Test
    void returns429WithRetryAfterOnceLimitIsHit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/ping").header("X-API-Key", "alice"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(2 - i)));
        }
        mvc.perform(get("/api/ping").header("X-API-Key", "alice"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.retryAfterMs").isNumber());
    }

    @Test
    void differentApiKeysHaveSeparateQuotas() throws Exception {
        for (int i = 0; i < 3; i++) mvc.perform(get("/api/ping").header("X-API-Key", "carol"));
        mvc.perform(get("/api/ping").header("X-API-Key", "carol")).andExpect(status().isTooManyRequests());

        mvc.perform(get("/api/ping").header("X-API-Key", "dave")).andExpect(status().isOk());
    }

    @Test
    void adminEndpointsAreNotRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/admin/algorithm")).andExpect(status().isOk());
        }
    }

    @Test
    void algorithmCanBeSwitchedAtRuntime() throws Exception {
        mvc.perform(put("/admin/algorithm").param("name", "sliding_window_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value("SLIDING_WINDOW_LOG"));

        mvc.perform(get("/api/ping").header("X-API-Key", "erin"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Algorithm", "SLIDING_WINDOW_LOG"));
    }

    @Test
    void unknownAlgorithmIsA400() throws Exception {
        mvc.perform(put("/admin/algorithm").param("name", "LEAKY_NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentHttpRequestsFromOneClientAdmitExactlyTheLimit() throws Exception {
        int threads = 20, requests = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger tooMany = new AtomicInteger();
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    int status = mvc.perform(get("/api/ping").header("X-API-Key", "stress"))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) ok.incrementAndGet();
                    else if (status == 429) tooMany.incrementAndGet();
                    return null;
                }));
            }
            for (Future<Void> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(3, ok.get(), "exactly `limit` requests may succeed");
        assertEquals(requests - 3, tooMany.get());
    }
}
