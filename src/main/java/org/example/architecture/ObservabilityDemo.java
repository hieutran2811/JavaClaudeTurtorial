package org.example.architecture;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * ============================================================
 * BÀI 9.5 — OBSERVABILITY: LOGGING, TRACING, METRICS
 * ============================================================
 *
 * "You can't manage what you can't measure."
 * Observability = khả năng hiểu trạng thái hệ thống từ output của nó.
 *
 * 3 PILLARS OF OBSERVABILITY:
 * ─────────────────────────────────────────────────────────
 *
 *   LOGS    → "What happened?" (discrete events)
 *   TRACES  → "Where did time go?" (request flow across services)
 *   METRICS → "How is the system performing?" (aggregated numbers)
 *
 * VẤN ĐỀ KHÔNG CÓ OBSERVABILITY:
 *   - "Production down! Why?" → grep logs 30 minutes → still no answer
 *   - "Users complain about slowness" → which service? which endpoint? which user?
 *   - "Memory leak?" → no trend data → can't predict when it'll OOM
 *
 * VẤN ĐỀ VỚI LOGGING TRUYỀN THỐNG:
 *   log.info("Processing order " + orderId);  // string concatenation
 *   → Không structured → khó search/filter trong ELK/Splunk
 *   → Không có correlation ID → không biết log nào thuộc request nào
 *   → Không có context → "ERROR: NullPointerException" — which user? which order?
 *
 * ============================================================
 * PILLAR 1: STRUCTURED LOGGING
 * ============================================================
 *
 * Structured log = JSON (hoặc key=value), không phải plain text.
 * Mỗi field có thể query được trong ELK/Datadog/CloudWatch.
 *
 * KEY FIELDS:
 *   timestamp      → ISO-8601
 *   level          → INFO/WARN/ERROR
 *   traceId        → link với distributed trace
 *   spanId         → current span
 *   service        → which service
 *   correlationId  → business request ID (idempotency key)
 *   userId         → who
 *   orderId        → about what
 *   durationMs     → how long
 *   error          → exception class + message (không phải full stack trong JSON field)
 *
 * MDC (Mapped Diagnostic Context):
 *   ThreadLocal map SLF4J inject vào tất cả log statements tự động.
 *   → Set once at request entry, available in all methods down the call stack.
 *
 * ============================================================
 * PILLAR 2: DISTRIBUTED TRACING
 * ============================================================
 *
 * Trace = 1 request xuyên qua nhiều services.
 * Span  = 1 unit of work trong 1 service.
 *
 *   [TraceId: abc123]
 *   ├── [Span: API Gateway]        0ms - 250ms
 *   │   ├── [Span: OrderService]   10ms - 200ms
 *   │   │   ├── [Span: PostgreSQL] 15ms - 50ms  (query)
 *   │   │   └── [Span: Kafka]      100ms - 120ms (publish)
 *   │   └── [Span: PaymentService] 150ms - 200ms
 *   └── [Span: InventoryService]   210ms - 250ms
 *
 * W3C Trace Context (standard headers):
 *   traceparent: 00-{traceId}-{spanId}-{flags}
 *   tracestate:  vendor-specific data
 *
 * OpenTelemetry = unified standard (replaces Zipkin/Jaeger APIs).
 *
 * ============================================================
 * PILLAR 3: METRICS
 * ============================================================
 *
 * COUNTER:   monotonically increasing (total requests, total errors)
 * GAUGE:     current value (active connections, queue size, memory)
 * HISTOGRAM: distribution of values (request duration, payload size)
 *            → percentiles: P50, P95, P99, P999
 * SUMMARY:   like histogram but pre-computed quantiles (client-side)
 *
 * RED Method (services):
 *   Rate     → requests per second
 *   Errors   → error rate (%)
 *   Duration → response time distribution (P99)
 *
 * USE Method (resources):
 *   Utilization → % time resource is busy
 *   Saturation  → queue depth, wait time
 *   Errors      → error count
 *
 * ============================================================
 */
public class ObservabilityDemo {

    // ═══════════════════════════════════════════════════════
    // SECTION 1: STRUCTURED LOGGING
    // ═══════════════════════════════════════════════════════

    /**
     * MDC (Mapped Diagnostic Context) — ThreadLocal context cho logging.
     *
     * Trong SLF4J thực tế:
     *   MDC.put("traceId", traceId);
     *   MDC.put("userId", userId);
     *   log.info("Processing request");  // tự động include traceId, userId
     *   MDC.clear();  // ← bắt buộc trong finally (ThreadLocal leak!)
     *
     * Ở đây implement simplified version để demo concept.
     */
    static class MDC {
        private static final ThreadLocal<Map<String, String>> context =
            ThreadLocal.withInitial(LinkedHashMap::new);

        static void put(String key, String value) { context.get().put(key, value); }
        static String get(String key)             { return context.get().get(key); }
        static void remove(String key)            { context.get().remove(key); }
        static Map<String, String> getCopyOfContextMap() { return new LinkedHashMap<>(context.get()); }
        static void clear()                       { context.get().clear(); }
        static void setContextMap(Map<String, String> map) {
            context.get().clear();
            context.get().putAll(map);
        }
    }

    /**
     * Structured Logger — outputs JSON-like structured log entries.
     *
     * Production: dùng Logback + logstash-logback-encoder:
     *   <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
     *   → tự động serialize MDC + message thành JSON
     */
    static class StructuredLogger {
        private final String serviceName;
        private final String component;

        StructuredLogger(String serviceName, String component) {
            this.serviceName = serviceName;
            this.component   = component;
        }

        void info(String event, Map<String, Object> fields) {
            log("INFO", event, fields, null);
        }

        void warn(String event, Map<String, Object> fields) {
            log("WARN", event, fields, null);
        }

        void error(String event, Map<String, Object> fields, Throwable t) {
            log("ERROR", event, fields, t);
        }

        private void log(String level, String event, Map<String, Object> fields, Throwable t) {
            // Build structured log entry
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("level",     level);
            entry.put("service",   serviceName);
            entry.put("component", component);
            entry.put("event",     event);

            // Inject MDC context (traceId, spanId, userId etc.)
            MDC.getCopyOfContextMap().forEach(entry::put);

            // Add custom fields
            entry.putAll(fields);

            if (t != null) {
                entry.put("error.class",   t.getClass().getSimpleName());
                entry.put("error.message", t.getMessage());
            }

            // Print as JSON (production: real JSON serializer)
            System.out.println(toJson(entry));
        }

        private String toJson(Map<String, Object> map) {
            String fields = map.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\": \"" + e.getValue() + "\"")
                .collect(Collectors.joining(", "));
            return "{" + fields + "}";
        }
    }

    /**
     * Request interceptor: set MDC at entry point, clear at exit.
     * In Spring: OncePerRequestFilter or HandlerInterceptor.
     */
    static class RequestContextFilter {
        static <T> T withContext(String traceId, String userId, String correlationId,
                                  Supplier<T> action) {
            try {
                MDC.put("traceId",       traceId);
                MDC.put("userId",        userId);
                MDC.put("correlationId", correlationId);
                MDC.put("service",       "order-service");
                return action.get();
            } finally {
                MDC.clear(); // ← CRITICAL: prevent ThreadLocal leak in thread pools
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 2: DISTRIBUTED TRACING
    // ═══════════════════════════════════════════════════════

    /**
     * Span: unit of work, có start/end time, parent span.
     * Trace: collection of spans với cùng traceId.
     */
    static class Span implements AutoCloseable {
        private final String    traceId;
        private final String    spanId;
        private final String    parentSpanId;
        private final String    name;
        private final String    service;
        private final long      startNano;
        private long            endNano;
        private final Map<String, String> tags    = new LinkedHashMap<>();
        private final List<String>        events  = new ArrayList<>();
        private String          status   = "OK";
        private String          errorMsg = null;

        private final SpanCollector collector;

        Span(String traceId, String spanId, String parentSpanId,
             String name, String service, SpanCollector collector) {
            this.traceId      = traceId;
            this.spanId       = spanId;
            this.parentSpanId = parentSpanId;
            this.name         = name;
            this.service      = service;
            this.collector    = collector;
            this.startNano    = System.nanoTime();
        }

        void setTag(String key, String value) { tags.put(key, value); }
        void addEvent(String event)           { events.add(event); }
        void setError(String message)         { this.status = "ERROR"; this.errorMsg = message; }
        long durationMs()  { return (endNano - startNano) / 1_000_000; }
        String traceId()   { return traceId; }
        String spanId()    { return spanId; }
        String parentSpanId() { return parentSpanId; }
        String name()      { return name; }

        @Override
        public void close() {
            this.endNano = System.nanoTime();
            collector.collect(this);
        }

        @Override
        public String toString() {
            return String.format("Span[%s/%s parent=%s name=%s duration=%dms status=%s]",
                traceId.substring(0, 8), spanId.substring(0, 8),
                parentSpanId == null ? "root" : parentSpanId.substring(0, 8),
                name, durationMs(), status);
        }
    }

    /** Tracer: creates spans and manages active span per thread. */
    static class Tracer {
        private final String        serviceName;
        private final SpanCollector collector;
        private final ThreadLocal<Span> activeSpan = new ThreadLocal<>();

        Tracer(String serviceName, SpanCollector collector) {
            this.serviceName = serviceName;
            this.collector   = collector;
        }

        /** Start a new root span (new trace). */
        Span startTrace(String operationName) {
            String traceId = UUID.randomUUID().toString().replace("-", "");
            String spanId  = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Span span = new Span(traceId, spanId, null, operationName, serviceName, collector);
            activeSpan.set(span);
            MDC.put("traceId", traceId);
            MDC.put("spanId",  spanId);
            return span;
        }

        /** Start a child span (inherits traceId from parent). */
        Span startSpan(String operationName) {
            Span parent    = activeSpan.get();
            String traceId = parent != null ? parent.traceId()
                : UUID.randomUUID().toString().replace("-", "");
            String parentId = parent != null ? parent.spanId() : null;
            String spanId   = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Span span = new Span(traceId, spanId, parentId, operationName, serviceName, collector);
            activeSpan.set(span);
            MDC.put("spanId", spanId);
            return span;
        }

        /** Extract trace context from incoming HTTP headers (W3C format). */
        Span continueTrace(String traceparent, String operationName) {
            // W3C traceparent: 00-{traceId}-{parentSpanId}-{flags}
            String[] parts   = traceparent.split("-");
            String traceId   = parts.length > 1 ? parts[1] : UUID.randomUUID().toString().replace("-","");
            String parentId  = parts.length > 2 ? parts[2] : null;
            String spanId    = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Span span = new Span(traceId, spanId, parentId, operationName, serviceName, collector);
            activeSpan.set(span);
            MDC.put("traceId", traceId);
            MDC.put("spanId",  spanId);
            return span;
        }

        /** Inject trace context into outgoing HTTP headers. */
        String buildTraceparent(Span span) {
            return "00-" + span.traceId() + "-" + span.spanId() + "-01";
        }

        Span activeSpan() { return activeSpan.get(); }
        void clear()      { activeSpan.remove(); MDC.remove("traceId"); MDC.remove("spanId"); }
    }

    /** Collect and store spans for display. */
    static class SpanCollector {
        private final List<Span> spans = new CopyOnWriteArrayList<>();
        void collect(Span span) { spans.add(span); }
        List<Span> spans()      { return Collections.unmodifiableList(spans); }

        void printTrace(String traceId) {
            System.out.println("\n  Trace: " + traceId.substring(0, 16) + "...");
            spans.stream()
                .filter(s -> s.traceId().equals(traceId))
                .sorted(Comparator.comparingLong(s -> s.startNano))
                .forEach(s -> {
                    int depth = s.parentSpanId() == null ? 0 : 1;
                    String indent = "  " + "  ".repeat(depth);
                    System.out.printf("  %s├─ [%s] %s  %dms  %s%n",
                        indent, s.service(), s.name(), s.durationMs(), s.status());
                    s.tags.forEach((k, v) ->
                        System.out.printf("  %s   %s=%s%n", indent, k, v));
                });
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 3: METRICS
    // ═══════════════════════════════════════════════════════

    /** Counter: only goes up. Reset on restart. */
    static class Counter {
        private final String name;
        private final Map<String, String> labels;
        private final AtomicLong value = new AtomicLong(0);

        Counter(String name, Map<String, String> labels) {
            this.name   = name;
            this.labels = labels;
        }

        void increment()           { value.incrementAndGet(); }
        void increment(long delta) { value.addAndGet(delta); }
        long get()                 { return value.get(); }
        String name()              { return name; }

        @Override
        public String toString() {
            return name + labels + " = " + value.get();
        }
    }

    /** Gauge: can go up or down (current value). */
    static class Gauge {
        private final String name;
        private final Supplier<Double> valueSupplier;

        Gauge(String name, Supplier<Double> valueSupplier) {
            this.name          = name;
            this.valueSupplier = valueSupplier;
        }

        double get()  { return valueSupplier.get(); }
        String name() { return name; }

        @Override
        public String toString() {
            return String.format("%s = %.2f", name, get());
        }
    }

    /**
     * Histogram: records distribution of values.
     * Computes percentiles from sorted samples.
     *
     * Production: Prometheus histogram uses buckets (pre-defined boundaries).
     * Here: store all samples (not prod-safe for high cardinality).
     */
    static class Histogram {
        private final String name;
        private final List<Double> samples = new CopyOnWriteArrayList<>();
        private final AtomicLong   count   = new AtomicLong(0);
        private final AtomicLong   sum     = new AtomicLong(0); // in ms × 1000 to avoid double

        Histogram(String name) { this.name = name; }

        void record(double valueMs) {
            samples.add(valueMs);
            count.incrementAndGet();
            sum.addAndGet((long)(valueMs * 1000));
        }

        long count()      { return count.get(); }
        double sum()      { return sum.get() / 1000.0; }
        double mean()     { return count.get() == 0 ? 0 : sum() / count.get(); }

        double percentile(double p) {
            if (samples.isEmpty()) return 0;
            List<Double> sorted = new ArrayList<>(samples);
            Collections.sort(sorted);
            int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        void printSummary() {
            System.out.printf("  %s: count=%d mean=%.1fms P50=%.1fms P95=%.1fms P99=%.1fms max=%.1fms%n",
                name, count(), mean(),
                percentile(50), percentile(95), percentile(99),
                percentile(100));
        }
    }

    /** MetricsRegistry: central registry for all metrics. */
    static class MetricsRegistry {
        private final Map<String, Counter>   counters   = new ConcurrentHashMap<>();
        private final Map<String, Gauge>     gauges     = new ConcurrentHashMap<>();
        private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();

        Counter counter(String name, String... labelPairs) {
            return counters.computeIfAbsent(name + Arrays.toString(labelPairs),
                k -> new Counter(name, toMap(labelPairs)));
        }

        void gauge(String name, Supplier<Double> supplier) {
            gauges.put(name, new Gauge(name, supplier));
        }

        Histogram histogram(String name) {
            return histograms.computeIfAbsent(name, Histogram::new);
        }

        void printAll() {
            System.out.println("\n  [Counters]");
            counters.values().forEach(c -> System.out.println("    " + c));
            System.out.println("  [Gauges]");
            gauges.values().forEach(g -> System.out.println("    " + g));
            System.out.println("  [Histograms]");
            histograms.values().forEach(Histogram::printSummary);
        }

        private Map<String, String> toMap(String[] pairs) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i+1]);
            return map;
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 4: HEALTH CHECKS
    // ═══════════════════════════════════════════════════════

    /**
     * Health Checks:
     *   Liveness  → "Is the app alive?" (restart if fail)
     *              Container orchestrator (K8s) uses this.
     *   Readiness → "Is the app ready to serve traffic?" (remove from LB if fail)
     *              DB connection OK? Kafka connected? Cache warm?
     *
     * Spring Boot Actuator: /actuator/health
     *   /actuator/health/liveness  → livenessState
     *   /actuator/health/readiness → readinessState
     */
    interface HealthCheck {
        HealthResult check();
        String name();
    }

    record HealthResult(String name, Status status, String details, long durationMs) {
        enum Status { UP, DOWN, DEGRADED }

        static HealthResult up(String name, String details, long ms) {
            return new HealthResult(name, Status.UP, details, ms);
        }
        static HealthResult down(String name, String details, long ms) {
            return new HealthResult(name, Status.DOWN, details, ms);
        }
        static HealthResult degraded(String name, String details, long ms) {
            return new HealthResult(name, Status.DEGRADED, details, ms);
        }

        @Override
        public String toString() {
            String icon = switch (status) { case UP -> "✅"; case DOWN -> "❌"; case DEGRADED -> "⚠"; };
            return String.format("  %s %-20s status=%-8s %dms  %s", icon, name, status, durationMs, details);
        }
    }

    static class DatabaseHealthCheck implements HealthCheck {
        private final boolean connected;
        private final long queryMs;

        DatabaseHealthCheck(boolean connected, long queryMs) {
            this.connected = connected;
            this.queryMs   = queryMs;
        }

        @Override
        public HealthResult check() {
            long start = System.currentTimeMillis();
            try { Thread.sleep(queryMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            long dur = System.currentTimeMillis() - start;

            if (!connected) return HealthResult.down(name(), "Cannot connect to PostgreSQL", dur);
            if (queryMs > 1000) return HealthResult.degraded(name(), "Slow query: " + queryMs + "ms", dur);
            return HealthResult.up(name(), "ping OK, pool=8/10", dur);
        }

        @Override public String name() { return "database"; }
    }

    static class KafkaHealthCheck implements HealthCheck {
        private final boolean connected;
        @Override
        public HealthResult check() {
            long start = System.currentTimeMillis();
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            long dur = System.currentTimeMillis() - start;
            if (!connected) return HealthResult.down(name(), "Kafka broker unreachable", dur);
            return HealthResult.up(name(), "broker=localhost:9092 lag=0", dur);
        }
        KafkaHealthCheck(boolean connected) { this.connected = connected; }
        @Override public String name() { return "kafka"; }
    }

    static class RedisHealthCheck implements HealthCheck {
        @Override
        public HealthResult check() {
            long start = System.currentTimeMillis();
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            long dur = System.currentTimeMillis() - start;
            return HealthResult.up(name(), "PONG in 1ms, memory=64MB/512MB", dur);
        }
        @Override public String name() { return "redis"; }
    }

    static class HealthIndicator {
        private final List<HealthCheck> checks = new ArrayList<>();

        void register(HealthCheck check) { checks.add(check); }

        void report(String type) {
            System.out.println("  Health check [" + type + "]:");
            List<HealthResult> results = checks.stream().map(HealthCheck::check).toList();
            results.forEach(System.out::println);
            boolean allUp = results.stream().allMatch(r -> r.status() == HealthResult.Status.UP);
            boolean anyDown = results.stream().anyMatch(r -> r.status() == HealthResult.Status.DOWN);
            String overall = anyDown ? "DOWN" : allUp ? "UP" : "DEGRADED";
            System.out.println("  Overall: " + overall);
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 5: INSTRUMENTED SERVICE (ties everything together)
    // ═══════════════════════════════════════════════════════

    /**
     * Fully instrumented OrderService:
     *   - Structured logging với MDC
     *   - Distributed tracing (start/child spans)
     *   - Metrics (counter, histogram)
     */
    static class InstrumentedOrderService {
        private final StructuredLogger log;
        private final Tracer           tracer;
        private final MetricsRegistry  metrics;

        // Metrics
        private final Counter   requestTotal;
        private final Counter   requestErrors;
        private final Histogram requestDuration;
        private final Histogram dbQueryDuration;

        // Simulated active connections gauge source
        private final AtomicInteger activeRequests = new AtomicInteger(0);

        InstrumentedOrderService(Tracer tracer, MetricsRegistry metrics) {
            this.log    = new StructuredLogger("order-service", "OrderService");
            this.tracer = tracer;
            this.metrics = metrics;

            this.requestTotal    = metrics.counter("http_requests_total",   "service", "order");
            this.requestErrors   = metrics.counter("http_request_errors_total", "service", "order");
            this.requestDuration = metrics.histogram("http_request_duration_ms");
            this.dbQueryDuration = metrics.histogram("db_query_duration_ms");

            metrics.gauge("active_requests", () -> (double) activeRequests.get());
        }

        String processOrder(String orderId, String userId, String incomingTraceparent) {
            long start = System.currentTimeMillis();
            activeRequests.incrementAndGet();

            // Start or continue trace
            Span span = incomingTraceparent != null
                ? tracer.continueTrace(incomingTraceparent, "processOrder")
                : tracer.startTrace("processOrder");
            span.setTag("orderId", orderId);
            span.setTag("userId",  userId);

            // Set MDC for all log statements
            MDC.put("orderId", orderId);
            MDC.put("userId",  userId);

            try {
                log.info("order.processing.started", Map.of(
                    "orderId", orderId,
                    "userId",  userId
                ));

                // Simulate DB query
                String dbResult = queryDatabase(span, orderId);

                // Simulate business logic
                span.addEvent("validation.passed");

                // Simulate downstream call (inject trace context)
                String outgoingTraceparent = tracer.buildTraceparent(span);
                callPaymentService(outgoingTraceparent, orderId);

                requestTotal.increment();
                long dur = System.currentTimeMillis() - start;
                requestDuration.record(dur);

                log.info("order.processing.completed", Map.of(
                    "orderId",    orderId,
                    "durationMs", String.valueOf(dur),
                    "dbResult",   dbResult
                ));

                return "OK:" + orderId;

            } catch (Exception e) {
                requestErrors.increment();
                span.setError(e.getMessage());
                log.error("order.processing.failed", Map.of(
                    "orderId", orderId,
                    "durationMs", String.valueOf(System.currentTimeMillis() - start)
                ), e);
                throw e;
            } finally {
                activeRequests.decrementAndGet();
                span.close(); // records span
                tracer.clear();
                MDC.clear();
            }
        }

        private String queryDatabase(Span parentSpan, String orderId) {
            long dbStart = System.currentTimeMillis();
            try (Span dbSpan = tracer.startSpan("db.query")) {
                dbSpan.setTag("db.type",      "postgresql");
                dbSpan.setTag("db.statement", "SELECT * FROM orders WHERE id=?");
                dbSpan.setTag("db.orderId",   orderId);

                // Simulate query time (10-50ms)
                long simulatedMs = 10 + (long)(Math.random() * 40);
                try { Thread.sleep(simulatedMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                long dur = System.currentTimeMillis() - dbStart;
                dbQueryDuration.record(dur);
                return "order-data-" + orderId;
            }
        }

        private void callPaymentService(String traceparent, String orderId) {
            try (Span paySpan = tracer.startSpan("payment.charge")) {
                paySpan.setTag("http.url",    "http://payment-svc/charge");
                paySpan.setTag("http.method", "POST");
                // W3C header injected into HTTP request:
                paySpan.setTag("traceparent", traceparent);

                long simulatedMs = 20 + (long)(Math.random() * 60);
                try { Thread.sleep(simulatedMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                paySpan.setTag("http.status", "200");
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // DEMO RUNNERS
    // ═══════════════════════════════════════════════════════

    static void demoStructuredLogging() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 1: Structured Logging + MDC");
        System.out.println("═══════════════════════════════════════════════════");

        StructuredLogger log = new StructuredLogger("order-service", "OrderController");

        System.out.println("\n[1A] Without MDC (no context):");
        log.info("order.received", Map.of("orderId", "ORD-001", "amount", "150000"));

        System.out.println("\n[1B] With MDC (request context auto-included):");
        RequestContextFilter.withContext("abc123def456", "user-789", "req-xyz-001", () -> {
            log.info("order.received",    Map.of("orderId", "ORD-001", "amount", "150000"));
            log.info("order.validated",   Map.of("orderId", "ORD-001", "validationMs", "5"));
            log.warn("stock.low",         Map.of("productId", "P-001", "remaining", "2"));
            log.error("payment.failed",   Map.of("orderId", "ORD-001"),
                new RuntimeException("Card declined"));
            return null;
        });

        System.out.println("\n[1C] MDC cleared after request:");
        System.out.println("  traceId after request: '" + MDC.get("traceId") + "' (should be empty)");

        System.out.println("\n[1D] Correlation across threads (manual MDC propagation):");
        Map<String, String> parentCtx = new LinkedHashMap<>();
        parentCtx.put("traceId", "parent-trace-001");
        parentCtx.put("userId",  "user-999");

        // When using thread pools: manually copy MDC to child thread
        System.out.println("  Parent thread MDC: " + parentCtx);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.submit(() -> {
            MDC.setContextMap(parentCtx); // copy parent context
            log.info("async.task.started", Map.of("task", "send-email"));
            MDC.clear();
        });
        exec.shutdown();
        try { exec.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void demoDistributedTracing() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 2: Distributed Tracing");
        System.out.println("═══════════════════════════════════════════════════");

        SpanCollector collector = new SpanCollector();
        Tracer apiTracer     = new Tracer("api-gateway",     collector);
        Tracer orderTracer   = new Tracer("order-service",   collector);
        Tracer paymentTracer = new Tracer("payment-service", collector);

        System.out.println("\n[Simulating cross-service request flow]");

        // 1. API Gateway receives request → starts root span
        try (Span gatewaySpan = apiTracer.startTrace("POST /api/orders")) {
            gatewaySpan.setTag("http.method", "POST");
            gatewaySpan.setTag("http.url",    "/api/orders");
            gatewaySpan.setTag("user.id",     "user-789");
            String traceId = gatewaySpan.traceId();

            System.out.println("  API Gateway: starting trace " + traceId.substring(0,16) + "...");
            String traceparent = apiTracer.buildTraceparent(gatewaySpan);
            System.out.println("  Propagating header: traceparent=" + traceparent.substring(0, 40) + "...");
            sleep(5);

            // 2. OrderService receives request → continues trace
            try (Span orderSpan = orderTracer.continueTrace(traceparent, "createOrder")) {
                orderSpan.setTag("orderId", "ORD-DEMO-001");
                System.out.println("  OrderService: continuing trace, creating child span");
                sleep(15);

                // 3. DB query span
                String orderTraceparent = orderTracer.buildTraceparent(orderSpan);
                try (Span dbSpan = orderTracer.startSpan("SELECT orders")) {
                    dbSpan.setTag("db.type", "postgresql");
                    sleep(20);
                }

                // 4. PaymentService call
                try (Span paySpan = paymentTracer.continueTrace(orderTraceparent, "chargeCard")) {
                    paySpan.setTag("amount", "150000");
                    System.out.println("  PaymentService: processing payment");
                    sleep(40);
                    paySpan.setTag("txId", "TXN-abc");
                }

                orderSpan.setTag("result", "CREATED");
            }

            gatewaySpan.setTag("http.status", "201");
        }

        // Print trace tree
        String traceId = collector.spans().get(0).traceId();
        System.out.println("\nTrace visualization:");
        collector.printTrace(traceId);

        System.out.println("\nW3C Trace Context format:");
        System.out.println("  traceparent: 00-{32hex traceId}-{16hex parentId}-01");
        System.out.println("  Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        apiTracer.clear(); orderTracer.clear(); paymentTracer.clear();
    }

    static void demoMetrics() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 3: Metrics — RED Method");
        System.out.println("═══════════════════════════════════════════════════");

        MetricsRegistry metrics = new MetricsRegistry();
        Counter  reqTotal   = metrics.counter("http_requests_total",  "endpoint", "/orders");
        Counter  reqErrors  = metrics.counter("http_errors_total",    "endpoint", "/orders");
        Histogram duration  = metrics.histogram("http_request_duration_ms");
        Histogram dbLatency = metrics.histogram("db_query_duration_ms");

        int activeConns = 5;
        metrics.gauge("active_connections", () -> (double) activeConns);
        metrics.gauge("jvm_heap_used_mb",   () -> {
            Runtime rt = Runtime.getRuntime();
            return (double)(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        });

        // Simulate 100 requests
        Random rng = new Random(42);
        System.out.println("\nSimulating 100 requests...");
        int errors = 0;
        for (int i = 0; i < 100; i++) {
            // Bimodal distribution: 80% fast (10-50ms), 20% slow (200-500ms)
            double latency = rng.nextDouble() < 0.8
                ? 10 + rng.nextDouble() * 40
                : 200 + rng.nextDouble() * 300;
            double dbLat = latency * 0.3;

            duration.record(latency);
            dbLatency.record(dbLat);
            reqTotal.increment();

            // 5% error rate
            if (rng.nextDouble() < 0.05) {
                reqErrors.increment();
                errors++;
            }
        }

        System.out.println("\nRED Metrics:");
        System.out.println("  Rate:   " + reqTotal.get() + " requests");
        System.out.printf("  Errors: %d (%.0f%% error rate)%n", errors, errors * 100.0 / reqTotal.get());
        System.out.println("  Duration:");
        duration.printSummary();
        dbLatency.printSummary();

        System.out.println("\n  Notice: P99 >> P50 (bimodal distribution)");
        System.out.println("  P99 > 1000ms would trigger SLA alert!");
        System.out.println("  USE Method (resources):");
        System.out.println("  Utilization: " + metrics.gauges.get("active_connections").get() + " active connections");
        System.out.printf("  JVM heap: %.1f MB%n", metrics.gauges.get("jvm_heap_used_mb").get());
    }

    static void demoHealthChecks() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 4: Health Checks (Liveness & Readiness)");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\n[Scenario A] Fully healthy system");
        HealthIndicator liveness  = new HealthIndicator();
        HealthIndicator readiness = new HealthIndicator();

        liveness.register(new DatabaseHealthCheck(true, 10));
        readiness.register(new DatabaseHealthCheck(true, 10));
        readiness.register(new KafkaHealthCheck(true));
        readiness.register(new RedisHealthCheck());

        liveness.report("liveness");
        readiness.report("readiness");

        System.out.println("\n[Scenario B] DB slow — degraded readiness");
        HealthIndicator readiness2 = new HealthIndicator();
        readiness2.register(new DatabaseHealthCheck(true, 1100)); // slow!
        readiness2.register(new KafkaHealthCheck(true));
        readiness2.register(new RedisHealthCheck());
        readiness2.report("readiness");

        System.out.println("\n[Scenario C] Kafka down — not ready, remove from LB");
        HealthIndicator readiness3 = new HealthIndicator();
        readiness3.register(new DatabaseHealthCheck(true, 10));
        readiness3.register(new KafkaHealthCheck(false)); // down!
        readiness3.register(new RedisHealthCheck());
        readiness3.report("readiness");

        System.out.println("\nLiveness vs Readiness:");
        System.out.println("  Liveness  DOWN → K8s RESTARTS the pod");
        System.out.println("  Readiness DOWN → K8s removes pod from Service (load balancer)");
        System.out.println("  App can be LIVE but not READY (e.g., warming up cache)");
    }

    static void demoFullInstrumentation() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 5: Fully Instrumented Service");
        System.out.println("═══════════════════════════════════════════════════");

        SpanCollector  collector = new SpanCollector();
        Tracer         tracer    = new Tracer("order-service", collector);
        MetricsRegistry metrics  = new MetricsRegistry();

        InstrumentedOrderService svc = new InstrumentedOrderService(tracer, metrics);

        System.out.println("\nProcessing 5 orders with full instrumentation:");
        for (int i = 1; i <= 5; i++) {
            String orderId = "ORD-" + String.format("%03d", i);
            String userId  = "user-" + (100 + i);
            try {
                svc.processOrder(orderId, userId, null);
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }

        System.out.println("\nMetrics after 5 requests:");
        metrics.printAll();

        System.out.println("\nSpans collected: " + collector.spans().size());
        String firstTrace = collector.spans().get(0).traceId();
        collector.printTrace(firstTrace);
    }

    static void demoOpenTelemetryReference() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 6: OpenTelemetry Reference (Production Setup)");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            OPENTELEMETRY = unified observability standard (CNCF).
            Replaces: Zipkin, Jaeger, Prometheus client libs (vendor-specific).

            ── Maven dependencies ─────────────────────────────
            <!-- OTel API (no impl dependency) -->
            <dependency>
              <groupId>io.opentelemetry</groupId>
              <artifactId>opentelemetry-api</artifactId>
              <version>1.36.0</version>
            </dependency>
            <!-- SDK (at runtime/main module only) -->
            <dependency>
              <groupId>io.opentelemetry</groupId>
              <artifactId>opentelemetry-sdk</artifactId>
              <version>1.36.0</version>
            </dependency>
            <!-- Auto-instrumentation: zero-code agent -->
            <!-- java -javaagent:opentelemetry-javaagent.jar -jar app.jar -->

            ── Traces ──────────────────────────────────────────
            Tracer tracer = GlobalOpenTelemetry.getTracer("order-service");

            Span span = tracer.spanBuilder("processOrder")
                .setAttribute("orderId", orderId)
                .setAttribute("userId", userId)
                .startSpan();
            try (Scope scope = span.makeCurrent()) {
                // All log statements in this scope auto-include traceId
                doWork();
            } catch (Exception e) {
                span.setStatus(StatusCode.ERROR, e.getMessage());
                span.recordException(e);
                throw e;
            } finally {
                span.end(); // exports to backend
            }

            ── Metrics ─────────────────────────────────────────
            Meter meter = GlobalOpenTelemetry.getMeter("order-service");

            LongCounter requests = meter.counterBuilder("http.requests")
                .setDescription("Total HTTP requests")
                .setUnit("{request}")
                .build();

            LongHistogram duration = meter.histogramBuilder("http.duration")
                .ofLongs()
                .setUnit("ms")
                .build();

            Attributes attrs = Attributes.of(
                AttributeKey.stringKey("endpoint"), "/orders",
                AttributeKey.stringKey("method"),   "POST");
            requests.add(1, attrs);
            duration.record(latencyMs, attrs);

            ── Export backends ──────────────────────────────────
            OTLP → Jaeger (traces), Prometheus (metrics), ELK (logs)

            OpenTelemetryBuilder builder = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(
                        OtlpGrpcSpanExporter.builder()
                            .setEndpoint("http://collector:4317")
                            .build())
                        .build())
                    .build())
                .buildAndRegisterGlobal();

            ── Spring Boot Auto-Configuration ───────────────────
            # application.yml
            management:
              tracing:
                sampling.probability: 1.0   # 100% in dev, 0.1 in prod
              otlp:
                tracing.endpoint: http://tempo:4318/v1/traces

            spring:
              application.name: order-service

            # Micrometer → Prometheus
            management.endpoints.web.exposure.include: health,info,prometheus,metrics

            ── Grafana Observability Stack ──────────────────────
            Prometheus  → scrape metrics from /actuator/prometheus
            Grafana     → dashboards (RED method, JVM, custom)
            Tempo       → distributed traces (OTLP receiver)
            Loki        → log aggregation (structured JSON)
            AlertManager → alert on P99 > threshold, error rate > 5%

            ── SLO / SLA Alerting ───────────────────────────────
            # Prometheus alerting rule:
            groups:
              - name: order-service
                rules:
                - alert: HighErrorRate
                  expr: rate(http_errors_total[5m]) / rate(http_requests_total[5m]) > 0.05
                  for: 2m
                  labels: { severity: critical }
                  annotations:
                    summary: "Error rate > 5% on order-service"

                - alert: SlowP99
                  expr: histogram_quantile(0.99, http_request_duration_ms) > 2000
                  for: 5m
                  annotations:
                    summary: "P99 latency > 2s"
            """);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ═══════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  BÀI 9.5 — OBSERVABILITY: LOG, TRACE, METRICS    ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        demoStructuredLogging();
        demoDistributedTracing();
        demoMetrics();
        demoHealthChecks();
        demoFullInstrumentation();
        demoOpenTelemetryReference();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TỔNG KẾT BÀI 9.5                                ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║                                                   ║");
        System.out.println("║  3 PILLARS: Logs (what) + Traces (where) +       ║");
        System.out.println("║            Metrics (how much/fast)               ║");
        System.out.println("║                                                   ║");
        System.out.println("║  STRUCTURED LOG: JSON + MDC correlation ID       ║");
        System.out.println("║  MDC.clear() in finally — prevent ThreadLocal    ║");
        System.out.println("║  leak in thread pools                            ║");
        System.out.println("║                                                   ║");
        System.out.println("║  TRACE: traceId propagates via W3C traceparent   ║");
        System.out.println("║  header. Span = 1 unit of work (has duration).   ║");
        System.out.println("║                                                   ║");
        System.out.println("║  METRICS:                                         ║");
        System.out.println("║  RED: Rate + Errors + Duration (P99!)            ║");
        System.out.println("║  USE: Utilization + Saturation + Errors          ║");
        System.out.println("║  P99 >> mean → outlier problem, not avg problem  ║");
        System.out.println("║                                                   ║");
        System.out.println("║  HEALTH: Liveness → restart | Readiness → LB    ║");
        System.out.println("║                                                   ║");
        System.out.println("║  OPENTELEMETRY: unified standard                 ║");
        System.out.println("║  Stack: OTel→Tempo+Prometheus+Loki→Grafana       ║");
        System.out.println("║                                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        System.out.println("\n✅ Module 9 — Architecture Patterns: HOÀN THÀNH!");
        System.out.println("→ Bài tiếp theo: Module 10.1 — Modern Java Features");
    }
}
