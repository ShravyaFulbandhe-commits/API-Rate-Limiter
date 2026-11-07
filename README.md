# API Rate Limiter

A thread-safe, per-client API rate limiter built with **Java 17 and Spring Boot**. It ships with four interchangeable algorithms (Token Bucket, Sliding Window Log, Sliding Window Counter, Fixed Window), returns a proper `429 Too Many Requests`, and includes a benchmark page that replays the same burst of traffic through every algorithm so you can see how they differ.

![Benchmark comparison](docs/benchmark.png)

## Highlights

- **4 algorithms behind one interface** (Strategy pattern), switchable at runtime without a restart
- **Correct under concurrency:** per-key atomic updates with `ConcurrentHashMap.compute`, with no global lock
- **Proper 429 responses** with `Retry-After` and `X-RateLimit-*` headers and a JSON body
- **43 tests**, including multi-threaded stress tests and a 200-request concurrent HTTP test
- **Deterministic testing:** an injectable clock, so there is no `Thread.sleep` and no flaky tests
- **Memory-safe:** a scheduled background task evicts idle clients
- **Visual benchmark** that makes the trade-offs between the algorithms obvious

## Benchmark results

The same traffic is replayed through each algorithm on a virtual clock: a steady 5 requests/second for 30 seconds, plus bursts of 100 requests at 9.9s, 10.1s and 20s. The limit is **100 requests per 10 seconds**.

| Algorithm | Allowed (of 450) | Rejected | Peak admitted in any 10s window |
|---|---:|---:|---:|
| Fixed Window | 300 | 150 | **199** (almost 2x the limit) |
| Sliding Window Log | 250 | 200 | **100** |
| Sliding Window Counter | 249 | 201 | **100** |
| Token Bucket | 300 | 150 | **151** |

**What this shows:** Fixed Window resets its counter at every window boundary, so a client can exhaust one window and immediately exhaust the next, getting about 199 requests through in 10 seconds against a limit of 100. The sliding window algorithms never exceed the limit. Token Bucket deliberately allows a burst (its bucket size) followed by the average rate, which is why most public APIs use it.

Run the benchmark yourself at `http://localhost:8080/benchmark.html`. You can change the limit, window, steady rate and burst timing.

## The algorithms

| Algorithm | Memory per client | Burst behaviour | Accuracy |
|---|---|---|---|
| **Fixed Window** | O(1) | Up to ~2x the limit across a boundary | Exact only within aligned windows |
| **Sliding Window Log** | O(limit) | Never exceeds the limit in any window | Exact |
| **Sliding Window Counter** | O(1) | Very close to the limit | Approximate (assumes even traffic) |
| **Token Bucket** | O(1) | Allows bursts up to bucket size, then the average rate | Long-run rate exact |

## Getting started

**Requirements:** JDK 17+ and Maven 3.6+

```bash
git clone https://github.com/YOUR-USERNAME/api-rate-limiter.git
cd api-rate-limiter

mvn test                # run all 43 tests
mvn spring-boot:run     # start the app on http://localhost:8080
```

### Try it

Send requests with an API key (the default is 10 requests per 10 seconds):

```bash
curl -i -H "X-API-Key: alice" http://localhost:8080/api/ping
```

On Windows Command Prompt, hit it 12 times to see the limit kick in:

```
for /L %i in (1,1,12) do @curl -s -o NUL -w "%{http_code}\n" -H "X-API-Key: alice" localhost:8080/api/ping
```

You should see ten `200`s followed by `429`s.

## API

| Endpoint | Description |
|---|---|
| `GET /api/ping` | Sample protected endpoint (rate limited) |
| `GET /admin/algorithm` | Show the active algorithm and available options |
| `PUT /admin/algorithm?name=SLIDING_WINDOW_LOG` | Switch algorithm at runtime |
| `GET /benchmark/run` | Run the benchmark and return JSON (`limit`, `windowMs`, `durationMs`, `steadyRps`, `burstSize`, `burstTimes`) |
| `GET /benchmark.html` | Benchmark charts |

Only `/api/**` is rate limited. Clients are identified by the `X-API-Key` header, falling back to the remote IP address.

### Example 429 response

```
HTTP/1.1 429
Retry-After: 4
X-RateLimit-Algorithm: TOKEN_BUCKET
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
Content-Type: application/json

{"error":"Too Many Requests","message":"Rate limit of 10 requests exceeded","retryAfterMs":3200}
```

## Configuration

`src/main/resources/application.properties`:

```properties
# FIXED_WINDOW | SLIDING_WINDOW_LOG | SLIDING_WINDOW_COUNTER | TOKEN_BUCKET
ratelimiter.algorithm=TOKEN_BUCKET
ratelimiter.limit=10
ratelimiter.window-seconds=10
ratelimiter.cleanup-interval-seconds=60
```

## How it works

```
Request -> RateLimitFilter -> active RateLimiter.tryAcquire(key) -> allowed?  yes -> controller
                                                                              no  -> 429 + Retry-After
```

### Concurrency design

Each client's state is stored in a `ConcurrentHashMap`, and every read-modify-write runs inside `compute()`, which locks only that key's hash bin:

- Threads hitting the **same** client are serialised, so there are no lost updates and no over-admission.
- Threads hitting **different** clients run in parallel.
- The idle-key cleanup task uses `computeIfPresent` on the same lock, so it cannot race with a request and drop a count.

### Injectable clock

Algorithms depend on a `TimeSource` interface rather than the system clock. Production uses `System.nanoTime()` (monotonic). Tests and the benchmark use a `ManualClock`, which makes time-based behaviour instant and repeatable.

## Project structure

```
src/main/java/com/example/ratelimiter/
├── core/          RateLimiter interface, AbstractKeyedLimiter, the 4 algorithms, TimeSource, ManualClock
├── config/        RateLimiterProperties, RateLimiterRegistry (active algorithm + scheduled cleanup)
├── web/           RateLimitFilter, ApiController, AdminController, BenchmarkController
└── benchmark/     BenchmarkService, BenchmarkResult
src/main/resources/static/benchmark.html   Benchmark charts
src/test/java/...                          Unit, concurrency, HTTP and benchmark tests
```

## Testing

```bash
mvn test
```

43 tests, including:

- **Behaviour shared by all four algorithms:** the exact limit is enforced, clients are independent, quota returns after idle time, idle clients are evicted.
- **Concurrency:** 16 threads on one key admit exactly `limit` requests; 50 keys under 8 threads each get exactly their quota; eviction running alongside requests loses no counts.
- **Algorithm-specific behaviour:** the Fixed Window boundary burst, the Sliding Log's exact limit, Sliding Counter weighting, and Token Bucket refill and capacity.
- **HTTP:** the 429 response and headers, separate quotas per API key, runtime switching, and 200 concurrent requests from 20 threads with exactly `limit` succeeding.
- **Benchmark:** Fixed Window exceeds the limit at a boundary and the Sliding Log does not.

## Limitations and future work

- **State is in memory**, so limits apply per server instance. To scale horizontally, move state to Redis: Fixed Window maps to `INCR` + `EXPIRE`, Sliding Log to a sorted set, and Token Bucket to a Lua script so check-and-update stays atomic.
- Client identity uses the `X-API-Key` header or the remote IP. Behind a proxy, `X-Forwarded-For` should only be trusted from a known proxy.
- Ideas: a Leaky Bucket algorithm, per-plan limits (free vs paid), Micrometer metrics, and a JMH throughput benchmark.

## Tech stack

Java 17, Spring Boot 3.3, Maven, JUnit 5, MockMvc