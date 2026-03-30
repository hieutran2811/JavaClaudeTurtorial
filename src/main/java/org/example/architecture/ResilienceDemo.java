package org.example.architecture;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.random.*;

/**
 * ============================================================
 * BÀI 9.4 — RESILIENCE PATTERNS
 * ============================================================
 *
 * MICROSERVICES FAIL — câu hỏi không phải "IF" mà là "WHEN".
 * Resilience patterns giúp hệ thống chịu đựng failure gracefully
 * thay vì cascade down toàn bộ.
 *
 * FAILURE SCENARIOS:
 *   - Remote service slow (timeout)       → Retry, Timeout
 *   - Remote service down                 → Circuit Breaker, Fallback
 *   - Too many concurrent calls           → Bulkhead
 *   - Client gửi quá nhiều request        → Rate Limiter
 *   - Thundering herd khi service recover → Exponential Backoff + Jitter
 *
 * RESILIENCE4J PATTERNS (thư viện phổ biến nhất cho Java):
 *   CircuitBreaker → Retry → Bulkhead → RateLimiter → TimeLimiter
 *
 * ============================================================
 * PATTERN 1: CIRCUIT BREAKER
 * ============================================================
 *
 *   CLOSED ──(failures > threshold)──► OPEN
 *     ▲                                  │
 *     │                                  │ (wait timeout)
 *     └──(probe success)── HALF_OPEN ◄──┘
 *
 *   CLOSED:    Normal operation. Count failures.
 *   OPEN:      Fast-fail all requests (no network call). Give downstream time to recover.
 *   HALF_OPEN: Allow limited probe requests. Success → CLOSED. Fail → OPEN again.
 *
 *   WHY: Without CB, slow service causes thread pool exhaustion → cascade failure.
 *
 * ============================================================
 * PATTERN 2: RETRY + EXPONENTIAL BACKOFF + JITTER
 * ============================================================
 *
 *   Retry alone → thundering herd: 1000 clients retry at t+1s simultaneously.
 *   Exponential backoff: wait 1s, 2s, 4s, 8s... → spread load over time.
 *   Jitter: add randomness → prevent synchronized retries.
 *
 *   delay = min(cap, base * 2^attempt) + random(0, jitter)
 *
 * ============================================================
 * PATTERN 3: BULKHEAD
 * ============================================================
 *
 *   Ship bulkhead: compartments isolate flooding.
 *   Software: limit concurrent calls to each service.
 *   ServiceA slow → doesn't exhaust thread pool for ServiceB.
 *
 *   Thread-pool isolation: each service gets own thread pool
 *   Semaphore isolation: limit concurrent calls with semaphore
 *
 * ============================================================
 * PATTERN 4: RATE LIMITER
 * ============================================================
 *
 *   Token Bucket: bucket holds N tokens, refilled at rate R.
 *     Each request consumes 1 token. Empty bucket → wait or reject.
 *
 *   Sliding Window: count requests in last N seconds.
 *     Smoother than fixed window (no burst at window boundary).
 *
 * ============================================================
 * COMPOSING PATTERNS (Resilience4j decorator order):
 * ============================================================
 *
 *   Bulkhead → CircuitBreaker → RateLimiter → Retry → TimeLimiter → call
 *
 *   Bulkhead first: reject if too many concurrent (before CB even counts)
 *   CB second: fast-fail if open (before rate limiter wastes tokens)
 *   Retry last: only retry if CB allows it
 *
 * ============================================================
 */
public class ResilienceDemo {

    // ═══════════════════════════════════════════════════════
    // SHARED INFRASTRUCTURE
    // ═══════════════════════════════════════════════════════

    static class ServiceUnavailableException extends RuntimeException {
        ServiceUnavailableException(String msg) { super(msg); }
    }

    static class TimeoutException extends RuntimeException {
        TimeoutException(String msg) { super(msg); }
    }

    static class RateLimitExceededException extends RuntimeException {
        RateLimitExceededException(String msg) { super(msg); }
    }

    static class BulkheadFullException extends RuntimeException {
        BulkheadFullException(String msg) { super(msg); }
    }

    /**
     * Simulated downstream service that can be configured to fail.
     */
    static class UnreliableService {
        private final AtomicInteger callCount   = new AtomicInteger(0);
        private final AtomicInteger failCount   = new AtomicInteger(0);
        private volatile boolean   forceDown    = false;
        private volatile int       slowMs       = 0;
        private volatile double    failRate      = 0.0;

        void setDown(boolean down)         { this.forceDown = down; }
        void setSlowMs(int ms)             { this.slowMs = ms; }
        void setFailRate(double rate)      { this.failRate = rate; }
        int  callCount()                   { return callCount.get(); }
        int  failCount()                   { return failCount.get(); }

        String call(String request) {
            int n = callCount.incrementAndGet();
            if (slowMs > 0) {
                try { Thread.sleep(slowMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (forceDown || Math.random() < failRate) {
                failCount.incrementAndGet();
                throw new ServiceUnavailableException("Service down (call #" + n + ")");
            }
            return "OK[" + n + "]: " + request;
        }

        void reset() { callCount.set(0); failCount.set(0); forceDown = false; slowMs = 0; failRate = 0; }
    }

    // ═══════════════════════════════════════════════════════
    // PATTERN 1: CIRCUIT BREAKER (from scratch)
    // ═══════════════════════════════════════════════════════

    /**
     * Circuit Breaker — full implementation từ đầu.
     *
     * Configuration:
     *   failureThreshold    : bao nhiêu % fail thì OPEN (vd: 50%)
     *   minimumCalls        : cần ít nhất N calls mới tính tỉ lệ
     *   openDurationMs      : OPEN bao lâu trước khi chuyển HALF_OPEN
     *   halfOpenPermitted   : số calls được phép ở HALF_OPEN
     */
    static class CircuitBreaker {
        enum State { CLOSED, OPEN, HALF_OPEN }

        private volatile State state = State.CLOSED;
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger halfOpenCalls = new AtomicInteger(0);
        private volatile long openedAt = 0;

        private final int    failureThreshold;  // % (50 = 50%)
        private final int    minimumCalls;
        private final long   openDurationMs;
        private final int    halfOpenPermitted;
        private final String name;

        CircuitBreaker(String name, int failureThreshold, int minimumCalls,
                       long openDurationMs, int halfOpenPermitted) {
            this.name               = name;
            this.failureThreshold   = failureThreshold;
            this.minimumCalls       = minimumCalls;
            this.openDurationMs     = openDurationMs;
            this.halfOpenPermitted  = halfOpenPermitted;
        }

        <T> T execute(Supplier<T> action) {
            checkAndTransition();

            switch (state) {
                case OPEN -> throw new ServiceUnavailableException(
                    "[CB:" + name + "] OPEN — fast-fail (no network call)");

                case HALF_OPEN -> {
                    int probeCount = halfOpenCalls.incrementAndGet();
                    if (probeCount > halfOpenPermitted) {
                        throw new ServiceUnavailableException(
                            "[CB:" + name + "] HALF_OPEN — probe limit reached");
                    }
                    try {
                        T result = action.get();
                        onSuccess();
                        return result;
                    } catch (Exception e) {
                        onFailure();
                        throw e;
                    }
                }

                case CLOSED -> {
                    try {
                        T result = action.get();
                        onSuccess();
                        return result;
                    } catch (Exception e) {
                        onFailure();
                        throw e;
                    }
                }

                default -> throw new IllegalStateException("Unknown CB state: " + state);
            }
        }

        private void onSuccess() {
            successCount.incrementAndGet();
            if (state == State.HALF_OPEN) {
                // Probe succeeded → reset and close
                transitionTo(State.CLOSED);
                successCount.set(0);
                failureCount.set(0);
                halfOpenCalls.set(0);
            }
        }

        private void onFailure() {
            failureCount.incrementAndGet();
            if (state == State.HALF_OPEN) {
                transitionTo(State.OPEN);
                openedAt = System.currentTimeMillis();
                halfOpenCalls.set(0);
                return;
            }
            int total = successCount.get() + failureCount.get();
            if (total >= minimumCalls) {
                int failPct = failureCount.get() * 100 / total;
                if (failPct >= failureThreshold) {
                    transitionTo(State.OPEN);
                    openedAt = System.currentTimeMillis();
                }
            }
        }

        private void checkAndTransition() {
            if (state == State.OPEN) {
                long elapsed = System.currentTimeMillis() - openedAt;
                if (elapsed >= openDurationMs) {
                    transitionTo(State.HALF_OPEN);
                    halfOpenCalls.set(0);
                }
            }
        }

        private synchronized void transitionTo(State newState) {
            if (state != newState) {
                System.out.printf("    [CB:%s] %s → %s%n", name, state, newState);
                state = newState;
            }
        }

        State state()      { return state; }
        int   successes()  { return successCount.get(); }
        int   failures()   { return failureCount.get(); }

        String stats() {
            int total = successCount.get() + failureCount.get();
            int pct   = total == 0 ? 0 : failureCount.get() * 100 / total;
            return String.format("state=%s success=%d failure=%d failRate=%d%%",
                state, successCount.get(), failureCount.get(), pct);
        }
    }

    // ═══════════════════════════════════════════════════════
    // PATTERN 2: RETRY + EXPONENTIAL BACKOFF + JITTER
    // ═══════════════════════════════════════════════════════

    /**
     * Retry với exponential backoff + full jitter.
     *
     * Full Jitter (AWS recommendation):
     *   delay = random(0, min(cap, base * 2^attempt))
     *   → Distributes retries evenly, prevents thundering herd
     *
     * Decorrelated Jitter (alternative):
     *   delay = random(base, prev_delay * 3)
     */
    static class RetryPolicy {
        private final int    maxAttempts;
        private final long   baseDelayMs;
        private final long   maxDelayMs;
        private final double jitterFactor;  // 0.0 = no jitter, 1.0 = full jitter
        private final Set<Class<? extends Exception>> retryOn;

        @SafeVarargs
        RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs,
                    double jitterFactor, Class<? extends Exception>... retryOn) {
            this.maxAttempts  = maxAttempts;
            this.baseDelayMs  = baseDelayMs;
            this.maxDelayMs   = maxDelayMs;
            this.jitterFactor = jitterFactor;
            this.retryOn      = new HashSet<>(Arrays.asList(retryOn));
        }

        <T> T execute(Supplier<T> action) {
            Exception lastException = null;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                try {
                    return action.get();
                } catch (Exception e) {
                    lastException = e;
                    boolean shouldRetry = retryOn.isEmpty() ||
                        retryOn.stream().anyMatch(c -> c.isInstance(e));

                    if (!shouldRetry || attempt == maxAttempts - 1) break;

                    long delay = computeDelay(attempt);
                    System.out.printf("    [Retry] Attempt %d/%d failed: %s. Waiting %dms...%n",
                        attempt + 1, maxAttempts, e.getMessage(), delay);
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            throw new RuntimeException("All " + maxAttempts + " attempts failed", lastException);
        }

        private long computeDelay(int attempt) {
            // Exponential: base * 2^attempt, capped at maxDelayMs
            long exponential = Math.min(maxDelayMs, baseDelayMs * (1L << attempt));
            // Full jitter: random(0, exponential * jitterFactor) + exponential * (1 - jitterFactor)
            long jitter = (long)(Math.random() * exponential * jitterFactor);
            return (long)(exponential * (1 - jitterFactor)) + jitter;
        }
    }

    // ═══════════════════════════════════════════════════════
    // PATTERN 3: BULKHEAD
    // ═══════════════════════════════════════════════════════

    /**
     * Semaphore Bulkhead: giới hạn concurrent calls.
     *
     * Mỗi downstream service có 1 bulkhead riêng.
     * ServiceA slow → semaphore của ServiceA full → reject thêm calls ServiceA
     * ServiceB vẫn hoạt động bình thường (không bị ảnh hưởng)
     *
     * Thread-pool Bulkhead (mạnh hơn):
     *   Mỗi service có thread pool riêng → hoàn toàn isolated
     *   Đắt hơn về resource nhưng isolation tốt hơn
     */
    static class Bulkhead {
        private final String    name;
        private final Semaphore semaphore;
        private final long      waitTimeMs;
        private final AtomicInteger rejected = new AtomicInteger(0);
        private final AtomicInteger active   = new AtomicInteger(0);
        private final AtomicInteger maxActive = new AtomicInteger(0);

        Bulkhead(String name, int maxConcurrent, long waitTimeMs) {
            this.name       = name;
            this.semaphore  = new Semaphore(maxConcurrent, true); // fair
            this.waitTimeMs = waitTimeMs;
        }

        <T> T execute(Supplier<T> action) {
            boolean acquired;
            try {
                acquired = semaphore.tryAcquire(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BulkheadFullException("[Bulkhead:" + name + "] Interrupted");
            }
            if (!acquired) {
                rejected.incrementAndGet();
                throw new BulkheadFullException(
                    "[Bulkhead:" + name + "] Full (" + semaphore.availablePermits() + " available)");
            }
            int cur = active.incrementAndGet();
            maxActive.updateAndGet(m -> Math.max(m, cur));
            try {
                return action.get();
            } finally {
                active.decrementAndGet();
                semaphore.release();
            }
        }

        int rejected()   { return rejected.get(); }
        int available()  { return semaphore.availablePermits(); }
        int maxActive()  { return maxActive.get(); }
        String stats()   {
            return String.format("[Bulkhead:%s] rejected=%d maxConcurrent=%d",
                name, rejected.get(), maxActive.get());
        }
    }

    // ═══════════════════════════════════════════════════════
    // PATTERN 4: RATE LIMITER (Token Bucket)
    // ═══════════════════════════════════════════════════════

    /**
     * Token Bucket Rate Limiter.
     *
     * Bucket capacity = maxTokens
     * Refill rate = tokensPerSecond
     * Each request consumes 1 token
     * Empty bucket → wait or reject
     *
     * Cho phép burst ngắn (dùng hết tokens tích lũy)
     * Khác Leaky Bucket: Leaky Bucket = fixed output rate, không cho burst
     */
    static class TokenBucketRateLimiter {
        private final String name;
        private final double tokensPerMs;      // refill rate
        private final double maxTokens;
        private double       tokens;
        private long         lastRefillTime;
        private final AtomicInteger allowed   = new AtomicInteger(0);
        private final AtomicInteger throttled = new AtomicInteger(0);

        TokenBucketRateLimiter(String name, double requestsPerSecond, double burstCapacity) {
            this.name           = name;
            this.tokensPerMs    = requestsPerSecond / 1000.0;
            this.maxTokens      = burstCapacity;
            this.tokens         = burstCapacity; // start full
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized <T> T execute(Supplier<T> action) {
            refill();
            if (tokens < 1.0) {
                throttled.incrementAndGet();
                throw new RateLimitExceededException(
                    "[RateLimit:" + name + "] Rate limit exceeded. Tokens: " +
                    String.format("%.2f", tokens));
            }
            tokens -= 1.0;
            allowed.incrementAndGet();
            return action.get();
        }

        private void refill() {
            long now     = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            tokens = Math.min(maxTokens, tokens + elapsed * tokensPerMs);
            lastRefillTime = now;
        }

        int allowed()   { return allowed.get(); }
        int throttled() { return throttled.get(); }
        String stats()  {
            return String.format("[RateLimit:%s] allowed=%d throttled=%d",
                name, allowed.get(), throttled.get());
        }
    }

    /**
     * Sliding Window Rate Limiter (không có burst problem của fixed window).
     *
     * Fixed window: 60 req/min → 60 at 0:59 + 60 at 1:01 = 120 in 2 seconds
     * Sliding window: count requests in last 60s → no burst at boundary
     */
    static class SlidingWindowRateLimiter {
        private final String name;
        private final int    limit;
        private final long   windowMs;
        private final Deque<Long> timestamps = new ArrayDeque<>();

        SlidingWindowRateLimiter(String name, int limit, long windowMs) {
            this.name     = name;
            this.limit    = limit;
            this.windowMs = windowMs;
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            // Remove timestamps outside window
            while (!timestamps.isEmpty() && timestamps.peekFirst() < now - windowMs) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) return false;
            timestamps.addLast(now);
            return true;
        }

        synchronized int currentCount() {
            long now = System.currentTimeMillis();
            while (!timestamps.isEmpty() && timestamps.peekFirst() < now - windowMs) {
                timestamps.pollFirst();
            }
            return timestamps.size();
        }
    }

    // ═══════════════════════════════════════════════════════
    // PATTERN 5: TIMEOUT + FALLBACK
    // ═══════════════════════════════════════════════════════

    /**
     * Timeout: đừng đợi mãi cho slow service.
     *
     * CompletableFuture.orTimeout() (Java 9+) hoặc
     * ExecutorService.submit() + Future.get(timeout)
     *
     * Fallback: khi service down, trả về cached/default value
     *   - Cache fallback: return stale data from cache
     *   - Default fallback: return empty/default response
     *   - Fail-fast fallback: throw specific exception to client
     */
    static class TimeLimitedService {
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final long timeoutMs;
        private final Map<String, String> cache = new ConcurrentHashMap<>();

        TimeLimitedService(long timeoutMs) { this.timeoutMs = timeoutMs; }

        String callWithTimeout(Supplier<String> action) {
            Future<String> future = executor.submit(action::get);
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                throw new TimeoutException("Call timed out after " + timeoutMs + "ms");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        String callWithFallback(String cacheKey, Supplier<String> action) {
            try {
                String result = callWithTimeout(action);
                cache.put(cacheKey, result); // update cache on success
                return result;
            } catch (TimeoutException | ServiceUnavailableException e) {
                String cached = cache.get(cacheKey);
                if (cached != null) {
                    System.out.println("    [Fallback] Using cached value for '" + cacheKey + "': " + cached);
                    return "[STALE] " + cached;
                }
                System.out.println("    [Fallback] No cache available, using default");
                return "[DEFAULT] Service unavailable";
            }
        }

        void shutdown() { executor.shutdown(); }
    }

    // ═══════════════════════════════════════════════════════
    // PATTERN 6: COMPOSED RESILIENCE PIPELINE
    // ═══════════════════════════════════════════════════════

    /**
     * Resilience4j style: compose multiple patterns.
     *
     * Execution order (outer → inner):
     *   Bulkhead → CircuitBreaker → RateLimiter → Retry → actual call
     *
     * Decorator pattern: each wrapper adds 1 concern.
     */
    static class ResilientClient {
        private final Bulkhead           bulkhead;
        private final CircuitBreaker     circuitBreaker;
        private final TokenBucketRateLimiter rateLimiter;
        private final RetryPolicy        retry;
        private final String             name;

        ResilientClient(String name, Bulkhead bh, CircuitBreaker cb,
                        TokenBucketRateLimiter rl, RetryPolicy rp) {
            this.name           = name;
            this.bulkhead       = bh;
            this.circuitBreaker = cb;
            this.rateLimiter    = rl;
            this.retry          = rp;
        }

        <T> T call(Supplier<T> action) {
            // Outer → Inner: Bulkhead → CB → RateLimit → Retry → call
            return bulkhead.execute(() ->
                circuitBreaker.execute(() ->
                    rateLimiter.execute(() ->
                        retry.execute(action)
                    )
                )
            );
        }

        void printStats() {
            System.out.println("  " + bulkhead.stats());
            System.out.println("  " + circuitBreaker.stats());
            System.out.println("  " + rateLimiter.stats());
        }
    }

    // ═══════════════════════════════════════════════════════
    // DEMO RUNNERS
    // ═══════════════════════════════════════════════════════

    static void demoCircuitBreaker() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 1: Circuit Breaker State Machine");
        System.out.println("═══════════════════════════════════════════════════");

        UnreliableService svc = new UnreliableService();
        CircuitBreaker cb = new CircuitBreaker(
            "payment-svc",
            50,     // open when 50%+ fail
            4,      // need at least 4 calls
            200,    // stay OPEN 200ms
            2       // allow 2 probes in HALF_OPEN
        );

        System.out.println("\n[Phase 1] Normal operation (2 successes)");
        svc.setFailRate(0);
        for (int i = 0; i < 2; i++) {
            try {
                String r = cb.execute(() -> svc.call("req"));
                System.out.println("  OK: " + r);
            } catch (Exception e) { System.out.println("  FAIL: " + e.getMessage()); }
        }
        System.out.println("  " + cb.stats());

        System.out.println("\n[Phase 2] Service degraded (fail rate 80%)");
        svc.setFailRate(0.8);
        for (int i = 0; i < 6; i++) {
            try {
                String r = cb.execute(() -> svc.call("req"));
                System.out.println("  OK: " + r);
            } catch (Exception e) { System.out.println("  FAIL: " + e.getMessage()); }
        }
        System.out.println("  " + cb.stats());

        System.out.println("\n[Phase 3] CB OPEN — fast-fail (no network call)");
        svc.reset();
        svc.setFailRate(0);
        int fastFails = 0;
        for (int i = 0; i < 5; i++) {
            try {
                cb.execute(() -> svc.call("req"));
            } catch (ServiceUnavailableException e) {
                fastFails++;
                System.out.println("  Fast-fail: " + e.getMessage());
            }
        }
        System.out.println("  Actual service calls made: " + svc.callCount() + " (should be 0!)");
        System.out.println("  " + cb.stats());

        System.out.println("\n[Phase 4] Waiting for OPEN → HALF_OPEN...");
        try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.out.println("  Probing with healthy service...");
        for (int i = 0; i < 3; i++) {
            try {
                String r = cb.execute(() -> svc.call("probe"));
                System.out.println("  Probe OK: " + r);
            } catch (Exception e) { System.out.println("  Probe FAIL: " + e.getMessage()); }
        }
        System.out.println("  " + cb.stats());
    }

    static void demoRetryWithBackoff() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 2: Retry + Exponential Backoff + Jitter");
        System.out.println("═══════════════════════════════════════════════════");

        UnreliableService svc = new UnreliableService();

        System.out.println("\n[2A] Retry 3 attempts, service recovers on attempt 3");
        AtomicInteger attempt = new AtomicInteger(0);
        RetryPolicy retry = new RetryPolicy(3, 50, 500, 0.5, ServiceUnavailableException.class);
        try {
            String result = retry.execute(() -> {
                int n = attempt.incrementAndGet();
                if (n < 3) throw new ServiceUnavailableException("temporarily down (attempt " + n + ")");
                return "SUCCESS on attempt " + n;
            });
            System.out.println("  Result: " + result);
        } catch (Exception e) {
            System.out.println("  Failed: " + e.getMessage());
        }

        System.out.println("\n[2B] All 3 attempts fail → exhausted");
        svc.setDown(true);
        RetryPolicy retryShort = new RetryPolicy(3, 30, 200, 0.5, ServiceUnavailableException.class);
        try {
            retryShort.execute(() -> svc.call("req"));
        } catch (Exception e) {
            System.out.println("  Exhausted: " + e.getMessage());
            System.out.println("  Service calls made: " + svc.callCount());
        }

        System.out.println("\n[2C] Backoff delay visualization (no actual sleep)");
        System.out.println("  Attempt | Base Delay | With Jitter (50%)");
        System.out.println("  " + "─".repeat(46));
        long base = 100, cap = 2000;
        for (int a = 0; a < 6; a++) {
            long exp     = Math.min(cap, base * (1L << a));
            long jittered = (long)(exp * 0.5) + (long)(Math.random() * exp * 0.5);
            System.out.printf("  %7d | %10dms | %dms%n", a + 1, exp, jittered);
        }
        System.out.println("  → Jitter prevents thundering herd when service recovers");
    }

    static void demoBulkhead() throws InterruptedException {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 3: Bulkhead — Isolate Failures");
        System.out.println("═══════════════════════════════════════════════════");

        Bulkhead paymentBulkhead  = new Bulkhead("payment-svc",  2, 50);  // max 2 concurrent
        Bulkhead inventoryBulkhead = new Bulkhead("inventory-svc", 5, 50); // max 5 concurrent

        UnreliableService slowPayment = new UnreliableService();
        slowPayment.setSlowMs(200); // payment is slow

        System.out.println("\n[3A] Payment service slows down (bulkhead=2, slow=200ms)");
        System.out.println("  Sending 5 concurrent requests to payment...");

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger rejections = new AtomicInteger(0);

        for (int i = 0; i < 5; i++) {
            pool.submit(() -> {
                try {
                    paymentBulkhead.execute(() -> slowPayment.call("pay"));
                    successes.incrementAndGet();
                } catch (BulkheadFullException e) {
                    rejections.incrementAndGet();
                    System.out.println("  Rejected: " + e.getMessage());
                } catch (Exception e) {
                    System.out.println("  Error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(2, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("  Payment results: success=" + successes + " rejected=" + rejections);
        System.out.println("  " + paymentBulkhead.stats());

        System.out.println("\n[3B] Inventory service unaffected (own bulkhead)");
        UnreliableService inventorySvc = new UnreliableService();
        for (int i = 0; i < 3; i++) {
            try {
                String r = inventoryBulkhead.execute(() -> inventorySvc.call("check-stock"));
                System.out.println("  Inventory OK: " + r);
            } catch (Exception e) {
                System.out.println("  Inventory Error: " + e.getMessage());
            }
        }
        System.out.println("  " + inventoryBulkhead.stats());
        System.out.println("  → Payment bulkhead saturation did NOT affect inventory ✅");
    }

    static void demoRateLimiter() throws InterruptedException {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 4: Rate Limiter (Token Bucket + Sliding Window)");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\n[4A] Token Bucket: 5 req/sec, burst=10");
        TokenBucketRateLimiter tb = new TokenBucketRateLimiter("api-gateway", 5, 10);
        UnreliableService svc = new UnreliableService();
        svc.setFailRate(0);

        // Burst: use all 10 tokens immediately
        System.out.println("  Burst phase (10 tokens in bucket):");
        int burstAllowed = 0, burstThrottled = 0;
        for (int i = 0; i < 15; i++) {
            try {
                tb.execute(() -> svc.call("req"));
                burstAllowed++;
            } catch (RateLimitExceededException e) {
                burstThrottled++;
            }
        }
        System.out.println("  Allowed=" + burstAllowed + " Throttled=" + burstThrottled +
            " (bucket had 10, sent 15)");

        // Wait for refill
        System.out.println("  Waiting 400ms for token refill...");
        Thread.sleep(400);

        int refillAllowed = 0, refillThrottled = 0;
        for (int i = 0; i < 5; i++) {
            try {
                tb.execute(() -> svc.call("req"));
                refillAllowed++;
            } catch (RateLimitExceededException e) {
                refillThrottled++;
            }
        }
        System.out.println("  After refill: Allowed=" + refillAllowed + " Throttled=" + refillThrottled);
        System.out.println("  " + tb.stats());

        System.out.println("\n[4B] Sliding Window: 5 req per 100ms");
        SlidingWindowRateLimiter sw = new SlidingWindowRateLimiter("sliding", 5, 100);
        int swAllowed = 0, swThrottled = 0;
        for (int i = 0; i < 8; i++) {
            if (sw.tryAcquire()) { swAllowed++; System.out.print("  ✅"); }
            else                 { swThrottled++; System.out.print("  ❌"); }
        }
        System.out.println();
        System.out.println("  Allowed=" + swAllowed + " Throttled=" + swThrottled
            + " Current window count=" + sw.currentCount());
        Thread.sleep(110); // window expires
        System.out.println("  After window expiry, count=" + sw.currentCount() + " (reset)");
    }

    static void demoFallback() throws InterruptedException {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 5: Timeout + Fallback");
        System.out.println("═══════════════════════════════════════════════════");

        TimeLimitedService client = new TimeLimitedService(100); // 100ms timeout
        UnreliableService svc = new UnreliableService();

        System.out.println("\n[5A] Fast service → success, cache updated");
        svc.setSlowMs(10);
        String r1 = client.callWithFallback("product-123", () -> svc.call("product-123"));
        System.out.println("  Result: " + r1);

        System.out.println("\n[5B] Slow service → timeout → use cached value");
        svc.setSlowMs(300); // 300ms > 100ms timeout
        String r2 = client.callWithFallback("product-123", () -> svc.call("product-123"));
        System.out.println("  Result: " + r2 + " (stale cache ✅)");

        System.out.println("\n[5C] Service down, no cache → default fallback");
        svc.setDown(true);
        String r3 = client.callWithFallback("unknown-key", () -> svc.call("unknown-key"));
        System.out.println("  Result: " + r3);

        client.shutdown();
    }

    static void demoComposedPipeline() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 6: Composed Resilience Pipeline");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\nBuilding resilient client for payment-service:");
        System.out.println("  Bulkhead(3) → CircuitBreaker(50%,4) → RateLimit(10/s) → Retry(3)");

        Bulkhead bh = new Bulkhead("payment", 3, 50);
        CircuitBreaker cb = new CircuitBreaker("payment", 50, 4, 1000, 2);
        TokenBucketRateLimiter rl = new TokenBucketRateLimiter("payment", 10, 15);
        RetryPolicy rp = new RetryPolicy(3, 50, 500, 0.5, ServiceUnavailableException.class);

        ResilientClient client = new ResilientClient("payment-service", bh, cb, rl, rp);
        UnreliableService svc = new UnreliableService();

        System.out.println("\n[Phase 1] Healthy service — 5 calls");
        svc.setFailRate(0);
        int ok = 0;
        for (int i = 0; i < 5; i++) {
            try {
                client.call(() -> svc.call("pay-" + i));
                ok++;
            } catch (Exception e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }
        System.out.println("  Success: " + ok + "/5");

        System.out.println("\n[Phase 2] Degraded service (70% fail) — CB will open");
        svc.setFailRate(0.7);
        int ok2 = 0, fail2 = 0;
        for (int i = 0; i < 10; i++) {
            try {
                client.call(() -> svc.call("pay"));
                ok2++;
            } catch (Exception e) {
                fail2++;
            }
        }
        System.out.println("  Success=" + ok2 + " Failed=" + fail2);

        System.out.println("\nFinal stats:");
        client.printStats();
    }

    static void demoResilience4jComparison() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 7: Resilience4j API (Reference)");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            Maven dependency:
              <dependency>
                <groupId>io.github.resilience4j</groupId>
                <artifactId>resilience4j-circuitbreaker</artifactId>
                <version>2.2.0</version>
              </dependency>

            ── Circuit Breaker ──────────────────────────────────
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)           // open at 50% failures
                .slowCallRateThreshold(50)           // also open if 50% are slow
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .minimumNumberOfCalls(10)
                .slidingWindowType(COUNT_BASED)     // or TIME_BASED
                .slidingWindowSize(20)
                .recordExceptions(IOException.class, TimeoutException.class)
                .ignoreExceptions(BusinessException.class)
                .build();

            CircuitBreaker cb = CircuitBreakerRegistry.of(config)
                .circuitBreaker("payment-service");

            Supplier<String> decorated = CircuitBreaker
                .decorateSupplier(cb, () -> paymentService.charge(amount));
            Try<String> result = Try.ofSupplier(decorated)
                .recover(CallNotPermittedException.class, ex -> "FALLBACK");

            ── Retry ────────────────────────────────────────────
            RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                .retryOnException(e -> e instanceof IOException)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                    100, 2.0, 0.5, 2000))  // base, multiplier, jitter, cap
                .build();

            Retry retry = RetryRegistry.of(retryConfig).retry("payment");
            Supplier<String> withRetry = Retry.decorateSupplier(retry, remoteCall);

            ── Bulkhead (Semaphore) ─────────────────────────────
            BulkheadConfig bhConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ofMillis(50))
                .build();

            Bulkhead bh = BulkheadRegistry.of(bhConfig).bulkhead("inventory");
            Supplier<String> withBh = Bulkhead.decorateSupplier(bh, remoteCall);

            ── Rate Limiter ─────────────────────────────────────
            RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                .limitForPeriod(100)              // 100 requests...
                .limitRefreshPeriod(Duration.ofSeconds(1)) // ...per second
                .timeoutDuration(Duration.ofMillis(25))    // wait max 25ms
                .build();

            RateLimiter rl = RateLimiterRegistry.of(rlConfig).rateLimiter("api");

            ── Compose all (Decorator order matters!) ───────────
            Supplier<String> resilientCall = Decorators.ofSupplier(remoteCall)
                .withBulkhead(bh)          // 1st: reject if full
                .withCircuitBreaker(cb)    // 2nd: fast-fail if open
                .withRateLimiter(rl)       // 3rd: throttle if needed
                .withRetry(retry)          // 4th: retry on transient failure
                .withFallback(List.of(     // 5th: fallback
                    CallNotPermittedException.class,
                    BulkheadFullException.class),
                    ex -> "FALLBACK_RESPONSE")
                .decorate();

            ── Spring Boot integration ──────────────────────────
            @CircuitBreaker(name = "payment", fallbackMethod = "paymentFallback")
            @Retry(name = "payment")
            @RateLimiter(name = "payment")
            public String processPayment(PaymentRequest req) {
                return paymentClient.charge(req);
            }

            public String paymentFallback(PaymentRequest req, Exception ex) {
                return "Payment service unavailable. Please try again.";
            }

            # application.yml:
            resilience4j:
              circuitbreaker:
                instances:
                  payment:
                    failureRateThreshold: 50
                    waitDurationInOpenState: 10s
              retry:
                instances:
                  payment:
                    maxAttempts: 3
                    waitDuration: 100ms
                    enableExponentialBackoff: true
                    exponentialBackoffMultiplier: 2
            """);
    }

    // ═══════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  BÀI 9.4 — RESILIENCE PATTERNS                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        demoCircuitBreaker();
        demoRetryWithBackoff();
        demoBulkhead();
        demoRateLimiter();
        demoFallback();
        demoComposedPipeline();
        demoResilience4jComparison();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TỔNG KẾT BÀI 9.4                                ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║                                                   ║");
        System.out.println("║  CIRCUIT BREAKER: CLOSED→OPEN→HALF_OPEN         ║");
        System.out.println("║    OPEN = fast-fail, no network call             ║");
        System.out.println("║    Prevents cascade failure + thread exhaustion  ║");
        System.out.println("║                                                   ║");
        System.out.println("║  RETRY: exponential backoff + jitter             ║");
        System.out.println("║    Jitter = prevents thundering herd             ║");
        System.out.println("║    Only retry idempotent operations!             ║");
        System.out.println("║                                                   ║");
        System.out.println("║  BULKHEAD: semaphore per service                 ║");
        System.out.println("║    ServiceA slow ≠ ServiceB affected             ║");
        System.out.println("║                                                   ║");
        System.out.println("║  RATE LIMITER: Token Bucket = allow burst        ║");
        System.out.println("║    Sliding Window = no burst at boundary         ║");
        System.out.println("║                                                   ║");
        System.out.println("║  COMPOSE ORDER:                                  ║");
        System.out.println("║  Bulkhead→CB→RateLimit→Retry→TimeLimiter→call   ║");
        System.out.println("║                                                   ║");
        System.out.println("║  FALLBACK: cache→default→fail-fast               ║");
        System.out.println("║  Stale cache > empty response for UX             ║");
        System.out.println("║                                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }
}
