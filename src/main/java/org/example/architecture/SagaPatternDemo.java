package org.example.architecture;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * ============================================================
 * BÀI 9.3 — SAGA PATTERN: DISTRIBUTED TRANSACTIONS
 * ============================================================
 *
 * VẤN ĐỀ: Distributed Transaction trong Microservices
 * ─────────────────────────────────────────────────────────
 *   E-commerce: PlaceOrder cần phối hợp 3 services:
 *     OrderService  → tạo order (DB: orders)
 *     PaymentService → charge thẻ   (DB: payments)
 *     InventoryService → giảm tồn kho (DB: inventory)
 *
 *   Nếu dùng 2PC (Two-Phase Commit):
 *     ❌ Blocking protocol → low throughput
 *     ❌ Coordinator là single point of failure
 *     ❌ Không work với NoSQL, message queues
 *     ❌ Microservices không chia sẻ DB
 *
 * SAGA GIẢI QUYẾT:
 * ─────────────────────────────────────────────────────────
 *   Saga = chuỗi local transactions, mỗi service làm 1 bước.
 *   Nếu bước N fail → chạy compensating transactions từ bước N-1 về 0.
 *
 *   T1 → T2 → T3 → FAIL → C3 → C2 → C1
 *   (Ti = forward tx, Ci = compensating tx)
 *
 * 2 KIỂU SAGA:
 * ─────────────────────────────────────────────────────────
 *
 * CHOREOGRAPHY (Event-driven, decentralized):
 *   - Mỗi service publish event khi xong
 *   - Service khác listen và react
 *   - Không có coordinator
 *   + Loose coupling, simple services
 *   - Khó track overall saga state, testing phức tạp
 *
 * ORCHESTRATION (Central coordinator):
 *   - 1 Saga Orchestrator điều phối từng bước
 *   - Orchestrator biết toàn bộ flow
 *   + Dễ monitor, dễ debug, centralized state
 *   - Orchestrator có thể trở thành God Object
 *
 * ============================================================
 * USE CASE: E-Commerce Order Placement
 * ============================================================
 *
 * Steps:
 *   1. Create Order        → compensate: Cancel Order
 *   2. Reserve Inventory   → compensate: Release Inventory
 *   3. Process Payment     → compensate: Refund Payment
 *   4. Confirm Order       → compensate: (terminal — if reached, saga succeeded)
 *
 * ============================================================
 */
public class SagaPatternDemo {

    // ═══════════════════════════════════════════════════════
    // SECTION 1: SHARED DOMAIN — Commands, Events, State
    // ═══════════════════════════════════════════════════════

    record OrderId(String value) {
        static OrderId generate() {
            return new OrderId("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        @Override public String toString() { return value; }
    }

    record Money(BigDecimal amount) {
        static Money of(double v) { return new Money(BigDecimal.valueOf(v)); }
        Money add(Money o)        { return new Money(amount.add(o.amount)); }
        @Override public String toString() { return amount.toPlainString() + " VND"; }
    }

    record OrderItem(String productId, String name, int qty, Money unitPrice) {
        Money total() { return new Money(unitPrice.amount().multiply(BigDecimal.valueOf(qty))); }
    }

    // Saga State Machine
    enum SagaStatus {
        STARTED,
        ORDER_CREATED,
        INVENTORY_RESERVED,
        PAYMENT_PROCESSED,
        COMPLETED,
        // Compensating
        COMPENSATING_PAYMENT,
        COMPENSATING_INVENTORY,
        COMPENSATING_ORDER,
        FAILED
    }

    // Saga context: dữ liệu chia sẻ giữa các bước
    static class SagaContext {
        final OrderId orderId;
        final String customerId;
        final List<OrderItem> items;
        final String paymentMethodId;

        // Filled in as saga progresses
        String paymentTransactionId;
        Map<String, Integer> reservedInventory = new LinkedHashMap<>();
        String failureReason;
        SagaStatus status = SagaStatus.STARTED;
        final List<String> log = new ArrayList<>();

        SagaContext(OrderId orderId, String customerId,
                    List<OrderItem> items, String paymentMethodId) {
            this.orderId         = orderId;
            this.customerId      = customerId;
            this.items           = items;
            this.paymentMethodId = paymentMethodId;
        }

        Money totalAmount() {
            return items.stream().map(OrderItem::total)
                .reduce(Money.of(0), Money::add);
        }

        void log(String msg) {
            String entry = "[" + Instant.now().toString().substring(11, 19) + "] " + msg;
            log.add(entry);
            System.out.println("  " + entry);
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 2: PARTICIPANT SERVICES
    // ═══════════════════════════════════════════════════════

    /**
     * Mỗi service có:
     *   - execute():    forward transaction (có thể fail)
     *   - compensate(): undo transaction (phải idempotent, không fail)
     *
     * IDEMPOTENCY: compensate() phải safe để retry.
     *   Nếu refund đã xử lý → gọi lại không refund 2 lần.
     *   Dùng idempotency key (transactionId) để deduplicate.
     */
    static class OrderService {
        private final Map<String, String> orders = new ConcurrentHashMap<>(); // orderId → status

        boolean createOrder(SagaContext ctx) {
            ctx.log("OrderService: creating order " + ctx.orderId);
            // Simulate occasional failure
            if (ctx.customerId.equals("BLOCKED_CUSTOMER")) {
                ctx.log("OrderService: customer is blocked!");
                return false;
            }
            orders.put(ctx.orderId.value(), "PENDING");
            ctx.log("OrderService: order created ✅");
            return true;
        }

        void cancelOrder(SagaContext ctx) {
            ctx.log("OrderService [COMPENSATE]: cancelling order " + ctx.orderId);
            // Idempotent: if already cancelled, no-op
            orders.compute(ctx.orderId.value(), (k, v) -> "CANCELLED");
            ctx.log("OrderService [COMPENSATE]: order cancelled ✅");
        }

        void confirmOrder(SagaContext ctx) {
            ctx.log("OrderService: confirming order " + ctx.orderId);
            orders.put(ctx.orderId.value(), "CONFIRMED");
            ctx.log("OrderService: order confirmed ✅");
        }

        String getStatus(String orderId) {
            return orders.getOrDefault(orderId, "NOT_FOUND");
        }
    }

    static class InventoryService {
        // productId → available stock
        private final Map<String, Integer> stock = new ConcurrentHashMap<>(Map.of(
            "P-001", 10,
            "P-002", 5,
            "P-003", 2
        ));
        // reservationId → reserved qty (for idempotency)
        private final Map<String, Map<String, Integer>> reservations = new ConcurrentHashMap<>();

        boolean reserveInventory(SagaContext ctx) {
            ctx.log("InventoryService: reserving inventory for " + ctx.items.size() + " items");

            // Check all items first (all-or-nothing within this service)
            for (OrderItem item : ctx.items) {
                int available = stock.getOrDefault(item.productId(), 0);
                if (available < item.qty()) {
                    ctx.log("InventoryService: insufficient stock for " + item.productId()
                        + " (need=" + item.qty() + " have=" + available + ")");
                    return false;
                }
            }

            // Reserve
            Map<String, Integer> reservation = new LinkedHashMap<>();
            for (OrderItem item : ctx.items) {
                stock.merge(item.productId(), -item.qty(), Integer::sum);
                reservation.put(item.productId(), item.qty());
                ctx.log("InventoryService: reserved " + item.qty() + "x " + item.productId());
            }
            reservations.put(ctx.orderId.value(), reservation);
            ctx.reservedInventory = reservation;
            return true;
        }

        void releaseInventory(SagaContext ctx) {
            ctx.log("InventoryService [COMPENSATE]: releasing inventory for " + ctx.orderId);
            // Idempotent: only release if reservation exists
            Map<String, Integer> reservation = reservations.remove(ctx.orderId.value());
            if (reservation == null) {
                ctx.log("InventoryService [COMPENSATE]: no reservation found — already released ✅");
                return;
            }
            reservation.forEach((productId, qty) -> {
                stock.merge(productId, qty, Integer::sum);
                ctx.log("InventoryService [COMPENSATE]: released " + qty + "x " + productId);
            });
        }

        Map<String, Integer> getStock() { return Collections.unmodifiableMap(stock); }
    }

    static class PaymentService {
        // transactionId → amount (for idempotency)
        private final Map<String, BigDecimal> processedPayments = new ConcurrentHashMap<>();
        private final Map<String, BigDecimal> refunds = new ConcurrentHashMap<>();

        // Simulate failure scenarios
        private final Set<String> failingPaymentMethods = new HashSet<>();

        void setPaymentMethodFailing(String methodId) { failingPaymentMethods.add(methodId); }

        boolean processPayment(SagaContext ctx) {
            String txId = "TXN-" + ctx.orderId.value();
            ctx.log("PaymentService: processing payment " + ctx.totalAmount()
                + " via " + ctx.paymentMethodId);

            // Idempotency check
            if (processedPayments.containsKey(txId)) {
                ctx.log("PaymentService: payment already processed (idempotent) ✅");
                ctx.paymentTransactionId = txId;
                return true;
            }

            // Simulate payment gateway failure
            if (failingPaymentMethods.contains(ctx.paymentMethodId)) {
                ctx.log("PaymentService: payment declined (insufficient funds / card expired)");
                return false;
            }

            processedPayments.put(txId, ctx.totalAmount().amount());
            ctx.paymentTransactionId = txId;
            ctx.log("PaymentService: payment charged txId=" + txId + " ✅");
            return true;
        }

        void refundPayment(SagaContext ctx) {
            ctx.log("PaymentService [COMPENSATE]: refunding " + ctx.paymentTransactionId);
            if (ctx.paymentTransactionId == null) {
                ctx.log("PaymentService [COMPENSATE]: no payment to refund ✅");
                return;
            }
            // Idempotent: don't refund twice
            if (refunds.containsKey(ctx.paymentTransactionId)) {
                ctx.log("PaymentService [COMPENSATE]: already refunded ✅");
                return;
            }
            BigDecimal amount = processedPayments.get(ctx.paymentTransactionId);
            if (amount != null) {
                refunds.put(ctx.paymentTransactionId, amount);
                ctx.log("PaymentService [COMPENSATE]: refunded " + amount + " ✅");
            }
        }

        boolean wasRefunded(String txId) { return refunds.containsKey(txId); }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 3A: ORCHESTRATION SAGA
    // ═══════════════════════════════════════════════════════

    /**
     * Saga Orchestrator: điều phối toàn bộ flow.
     *
     * State machine:
     *   STARTED → ORDER_CREATED → INVENTORY_RESERVED → PAYMENT_PROCESSED → COMPLETED
     *                                                                     ↑ success path
     *   Failure at any step → reverse compensating chain:
     *   PAYMENT fail → compensate INVENTORY → compensate ORDER → FAILED
     *
     * KEY BENEFITS của Orchestration:
     *   ✅ Centralized saga state — dễ monitor/audit
     *   ✅ Retry logic tập trung
     *   ✅ Timeout handling tập trung
     *   ✅ Dễ test (mock services, verify state machine)
     */
    static class OrderSagaOrchestrator {
        private final OrderService    orderService;
        private final InventoryService inventoryService;
        private final PaymentService  paymentService;

        OrderSagaOrchestrator(OrderService o, InventoryService i, PaymentService p) {
            this.orderService    = o;
            this.inventoryService = i;
            this.paymentService  = p;
        }

        SagaContext execute(SagaContext ctx) {
            System.out.println("\n  ── Forward Steps ──────────────────────────────");

            // Step 1: Create Order
            ctx.status = SagaStatus.STARTED;
            if (!orderService.createOrder(ctx)) {
                ctx.failureReason = "Order creation failed";
                return compensate(ctx, false, false);
            }
            ctx.status = SagaStatus.ORDER_CREATED;

            // Step 2: Reserve Inventory
            if (!inventoryService.reserveInventory(ctx)) {
                ctx.failureReason = "Inventory reservation failed";
                return compensate(ctx, true, false);
            }
            ctx.status = SagaStatus.INVENTORY_RESERVED;

            // Step 3: Process Payment
            if (!paymentService.processPayment(ctx)) {
                ctx.failureReason = "Payment processing failed";
                return compensate(ctx, true, true);
            }
            ctx.status = SagaStatus.PAYMENT_PROCESSED;

            // Step 4: Confirm Order (terminal success step)
            orderService.confirmOrder(ctx);
            ctx.status = SagaStatus.COMPLETED;
            ctx.log("Saga COMPLETED successfully ✅");
            return ctx;
        }

        private SagaContext compensate(SagaContext ctx,
                                       boolean orderCreated,
                                       boolean inventoryReserved) {
            System.out.println("\n  ── Compensating Steps (reverse order) ─────────");

            // Compensate in REVERSE order — only if step was completed
            if (inventoryReserved) {
                ctx.status = SagaStatus.COMPENSATING_INVENTORY;
                inventoryService.releaseInventory(ctx);
            }
            if (orderCreated) {
                ctx.status = SagaStatus.COMPENSATING_ORDER;
                orderService.cancelOrder(ctx);
            }

            ctx.status = SagaStatus.FAILED;
            ctx.log("Saga FAILED: " + ctx.failureReason + " ❌");
            return ctx;
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 3B: CHOREOGRAPHY SAGA
    // ═══════════════════════════════════════════════════════

    /**
     * Choreography: không có orchestrator.
     * Mỗi service publish event → service khác subscribe và react.
     *
     * Flow:
     *   Client → OrderService.createOrder() → publishes OrderCreated
     *   InventoryService listens OrderCreated → reserves → publishes InventoryReserved
     *   PaymentService listens InventoryReserved → charges → publishes PaymentProcessed
     *   OrderService listens PaymentProcessed → confirms order
     *
     *   Failure flow:
     *   PaymentService fails → publishes PaymentFailed
     *   InventoryService listens PaymentFailed → releases inventory → publishes InventoryReleased
     *   OrderService listens InventoryReleased → cancels order
     *
     * KEY: Mỗi service chỉ biết events nó cần, không biết toàn bộ flow.
     */

    // Events trong choreography
    sealed interface SagaEvent permits
        OrderCreatedEvt, InventoryReservedEvt, InventoryReservationFailedEvt,
        PaymentProcessedEvt, PaymentFailedEvt,
        InventoryReleasedEvt, OrderConfirmedEvt, OrderCancelledEvt {}

    record OrderCreatedEvt(String orderId, String customerId, List<OrderItem> items, Money total) implements SagaEvent {}
    record InventoryReservedEvt(String orderId, Map<String, Integer> reserved) implements SagaEvent {}
    record InventoryReservationFailedEvt(String orderId, String reason) implements SagaEvent {}
    record PaymentProcessedEvt(String orderId, String txId, Money amount) implements SagaEvent {}
    record PaymentFailedEvt(String orderId, String reason) implements SagaEvent {}
    record InventoryReleasedEvt(String orderId) implements SagaEvent {}
    record OrderConfirmedEvt(String orderId) implements SagaEvent {}
    record OrderCancelledEvt(String orderId, String reason) implements SagaEvent {}

    // Simple in-process event bus
    static class EventBus {
        private final Map<Class<?>, List<Consumer<SagaEvent>>> handlers = new HashMap<>();

        @SuppressWarnings("unchecked")
        <T extends SagaEvent> void subscribe(Class<T> type, Consumer<T> handler) {
            handlers.computeIfAbsent(type, k -> new ArrayList<>())
                .add(e -> handler.accept((T) e));
        }

        void publish(SagaEvent event) {
            System.out.println("  [EVENT] " + event.getClass().getSimpleName());
            List<Consumer<SagaEvent>> subs = handlers.getOrDefault(event.getClass(), List.of());
            subs.forEach(h -> h.accept(event));
        }
    }

    static class ChoreographyOrderService {
        private final Map<String, String> orders = new ConcurrentHashMap<>();
        private final EventBus bus;

        ChoreographyOrderService(EventBus bus) { this.bus = bus; }

        void createOrder(String orderId, String customerId, List<OrderItem> items) {
            System.out.println("  ChoreographyOrderService: creating order " + orderId);
            orders.put(orderId, "PENDING");
            Money total = items.stream().map(OrderItem::total).reduce(Money.of(0), Money::add);
            bus.publish(new OrderCreatedEvt(orderId, customerId, items, total));
        }

        void onPaymentProcessed(PaymentProcessedEvt e) {
            System.out.println("  ChoreographyOrderService: confirming order after payment");
            orders.put(e.orderId(), "CONFIRMED");
            bus.publish(new OrderConfirmedEvt(e.orderId()));
        }

        void onInventoryReleased(InventoryReleasedEvt e) {
            System.out.println("  ChoreographyOrderService: cancelling order after inventory released");
            orders.put(e.orderId(), "CANCELLED");
            bus.publish(new OrderCancelledEvt(e.orderId(), "payment failed"));
        }

        String getStatus(String orderId) { return orders.getOrDefault(orderId, "NOT_FOUND"); }
    }

    static class ChoreographyInventoryService {
        private final Map<String, Integer> stock;
        private final EventBus bus;
        private final boolean shouldFail;

        ChoreographyInventoryService(EventBus bus, boolean shouldFail) {
            this.bus = bus;
            this.shouldFail = shouldFail;
            this.stock = new ConcurrentHashMap<>(Map.of("P-001", 10, "P-002", 5));
        }

        void onOrderCreated(OrderCreatedEvt e) {
            System.out.println("  ChoreographyInventoryService: reserving for order " + e.orderId());
            if (shouldFail) {
                bus.publish(new InventoryReservationFailedEvt(e.orderId(), "out of stock"));
                return;
            }
            Map<String, Integer> reserved = new LinkedHashMap<>();
            e.items().forEach(item -> {
                stock.merge(item.productId(), -item.qty(), Integer::sum);
                reserved.put(item.productId(), item.qty());
            });
            bus.publish(new InventoryReservedEvt(e.orderId(), reserved));
        }

        void onPaymentFailed(PaymentFailedEvt e) {
            System.out.println("  ChoreographyInventoryService [COMPENSATE]: releasing for " + e.orderId());
            // In real system: look up reservation by orderId and release
            bus.publish(new InventoryReleasedEvt(e.orderId()));
        }
    }

    static class ChoreographyPaymentService {
        private final EventBus bus;
        private final boolean shouldFail;

        ChoreographyPaymentService(EventBus bus, boolean shouldFail) {
            this.bus = bus;
            this.shouldFail = shouldFail;
        }

        void onInventoryReserved(InventoryReservedEvt e) {
            System.out.println("  ChoreographyPaymentService: processing payment for " + e.orderId());
            if (shouldFail) {
                bus.publish(new PaymentFailedEvt(e.orderId(), "card declined"));
                return;
            }
            String txId = "TXN-" + e.orderId();
            bus.publish(new PaymentProcessedEvt(e.orderId(), txId, Money.of(100_000)));
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 4: IDEMPOTENCY — Safe retry
    // ═══════════════════════════════════════════════════════

    /**
     * Idempotency = gọi nhiều lần = kết quả như gọi 1 lần.
     *
     * TẠI SAO QUAN TRỌNG TRONG SAGA:
     *   - Network timeout → retry → duplicate payment?
     *   - Compensating tx cũng cần retry → double refund?
     *   - At-least-once delivery (Kafka, SQS) → message processed twice?
     *
     * CÁCH IMPLEMENT:
     *   - Idempotency key = unique request ID (UUID từ client)
     *   - Server lưu processed keys → nếu duplicate → return same result
     *   - TTL: xóa keys sau 24h (sau đó key có thể reuse)
     */
    static class IdempotentPaymentProcessor {
        private final Map<String, ProcessedResult> processedKeys = new ConcurrentHashMap<>();

        record ProcessedResult(boolean success, String txId, Instant processedAt) {}

        ProcessedResult processPayment(String idempotencyKey, Money amount, String method) {
            // Return cached result if already processed
            ProcessedResult existing = processedKeys.get(idempotencyKey);
            if (existing != null) {
                System.out.println("    [IDEMPOTENT] Key " + idempotencyKey + " already processed → returning cached result");
                return existing;
            }

            // Process for the first time
            boolean success = !method.equals("DECLINED_CARD");
            String txId = success ? "TXN-" + UUID.randomUUID().toString().substring(0, 8) : null;
            ProcessedResult result = new ProcessedResult(success, txId, Instant.now());
            processedKeys.put(idempotencyKey, result);
            System.out.println("    [NEW] Processed payment: success=" + success + " txId=" + txId);
            return result;
        }
    }

    // ═══════════════════════════════════════════════════════
    // DEMO RUNNERS
    // ═══════════════════════════════════════════════════════

    static void demoOrchestrationSuccess() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 1: Orchestration Saga — Happy Path");
        System.out.println("═══════════════════════════════════════════════════");

        OrderService     orderSvc     = new OrderService();
        InventoryService inventorySvc = new InventoryService();
        PaymentService   paymentSvc   = new PaymentService();

        System.out.println("\nInitial inventory: " + inventorySvc.getStock());

        OrderSagaOrchestrator saga = new OrderSagaOrchestrator(orderSvc, inventorySvc, paymentSvc);

        SagaContext ctx = new SagaContext(
            OrderId.generate(), "CUST-001",
            List.of(
                new OrderItem("P-001", "Laptop", 1, Money.of(15_000_000)),
                new OrderItem("P-002", "Mouse",  2, Money.of(500_000))
            ),
            "CARD-VISA-001"
        );

        System.out.println("Saga ID: " + ctx.orderId);
        System.out.println("Total:   " + ctx.totalAmount());
        saga.execute(ctx);

        System.out.println("\nFinal state:");
        System.out.println("  Saga status : " + ctx.status);
        System.out.println("  Order status: " + orderSvc.getStatus(ctx.orderId.value()));
        System.out.println("  Payment txId: " + ctx.paymentTransactionId);
        System.out.println("  Inventory   : " + inventorySvc.getStock());
    }

    static void demoOrchestrationPaymentFailure() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 2: Orchestration Saga — Payment Failure → Compensation");
        System.out.println("═══════════════════════════════════════════════════");

        OrderService     orderSvc     = new OrderService();
        InventoryService inventorySvc = new InventoryService();
        PaymentService   paymentSvc   = new PaymentService();

        System.out.println("\nInitial inventory: " + inventorySvc.getStock());

        // Make payment fail for this card
        paymentSvc.setPaymentMethodFailing("CARD-EXPIRED");

        OrderSagaOrchestrator saga = new OrderSagaOrchestrator(orderSvc, inventorySvc, paymentSvc);
        SagaContext ctx = new SagaContext(
            OrderId.generate(), "CUST-002",
            List.of(new OrderItem("P-001", "Laptop", 2, Money.of(15_000_000))),
            "CARD-EXPIRED"  // ← will fail at payment step
        );

        System.out.println("Saga ID: " + ctx.orderId);
        saga.execute(ctx);

        System.out.println("\nFinal state:");
        System.out.println("  Saga status : " + ctx.status);
        System.out.println("  Order status: " + orderSvc.getStatus(ctx.orderId.value()));
        System.out.println("  Inventory (should be restored): " + inventorySvc.getStock());
        System.out.println("  Payment txId: " + ctx.paymentTransactionId + " (should be null)");
        System.out.println("  Failure: " + ctx.failureReason);
    }

    static void demoOrchestrationInventoryFailure() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 3: Orchestration Saga — Inventory Failure → Partial Compensation");
        System.out.println("═══════════════════════════════════════════════════");

        OrderService     orderSvc     = new OrderService();
        InventoryService inventorySvc = new InventoryService();
        PaymentService   paymentSvc   = new PaymentService();

        System.out.println("Initial inventory: " + inventorySvc.getStock());

        OrderSagaOrchestrator saga = new OrderSagaOrchestrator(orderSvc, inventorySvc, paymentSvc);
        SagaContext ctx = new SagaContext(
            OrderId.generate(), "CUST-003",
            List.of(
                new OrderItem("P-003", "Rare Item", 99, Money.of(1_000_000)) // ← only 2 in stock!
            ),
            "CARD-OK"
        );

        System.out.println("Saga ID: " + ctx.orderId + " (requesting 99x P-003, only 2 available)");
        saga.execute(ctx);

        System.out.println("\nFinal state:");
        System.out.println("  Saga status : " + ctx.status);
        System.out.println("  Order status: " + orderSvc.getStatus(ctx.orderId.value()));
        System.out.println("  Payment txId: " + ctx.paymentTransactionId + " (payment never reached)");
    }

    static void demoChoreography() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 4: Choreography Saga — Decentralized Flow");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\n--- 4A: Choreography Happy Path ---");
        {
            EventBus bus = new EventBus();
            ChoreographyOrderService     orderSvc     = new ChoreographyOrderService(bus);
            ChoreographyInventoryService inventorySvc = new ChoreographyInventoryService(bus, false);
            ChoreographyPaymentService   paymentSvc   = new ChoreographyPaymentService(bus, false);

            // Wire up subscriptions (done at startup)
            bus.subscribe(OrderCreatedEvt.class,        inventorySvc::onOrderCreated);
            bus.subscribe(InventoryReservedEvt.class,   paymentSvc::onInventoryReserved);
            bus.subscribe(PaymentProcessedEvt.class,    orderSvc::onPaymentProcessed);
            bus.subscribe(PaymentFailedEvt.class,       inventorySvc::onPaymentFailed);
            bus.subscribe(InventoryReleasedEvt.class,   orderSvc::onInventoryReleased);

            String orderId = "ORD-CHOREO-1";
            orderSvc.createOrder(orderId, "CUST-001",
                List.of(new OrderItem("P-001", "Book", 1, Money.of(200_000))));

            System.out.println("\n  Final order status: " + orderSvc.getStatus(orderId));
        }

        System.out.println("\n--- 4B: Choreography — Payment Failure Compensation ---");
        {
            EventBus bus = new EventBus();
            ChoreographyOrderService     orderSvc     = new ChoreographyOrderService(bus);
            ChoreographyInventoryService inventorySvc = new ChoreographyInventoryService(bus, false);
            ChoreographyPaymentService   paymentSvc   = new ChoreographyPaymentService(bus, true); // ← fail payment

            bus.subscribe(OrderCreatedEvt.class,        inventorySvc::onOrderCreated);
            bus.subscribe(InventoryReservedEvt.class,   paymentSvc::onInventoryReserved);
            bus.subscribe(PaymentProcessedEvt.class,    orderSvc::onPaymentProcessed);
            bus.subscribe(PaymentFailedEvt.class,       inventorySvc::onPaymentFailed);
            bus.subscribe(InventoryReleasedEvt.class,   orderSvc::onInventoryReleased);

            String orderId = "ORD-CHOREO-2";
            orderSvc.createOrder(orderId, "CUST-002",
                List.of(new OrderItem("P-001", "Laptop", 1, Money.of(15_000_000))));

            System.out.println("\n  Final order status: " + orderSvc.getStatus(orderId) + " (should be CANCELLED)");
        }
    }

    static void demoIdempotency() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 5: Idempotency — Safe Retry");
        System.out.println("═══════════════════════════════════════════════════");

        IdempotentPaymentProcessor processor = new IdempotentPaymentProcessor();
        String idempotencyKey = "REQ-" + UUID.randomUUID().toString().substring(0, 8);

        System.out.println("Idempotency key: " + idempotencyKey);
        System.out.println("\nAttempt 1 (first call):");
        var result1 = processor.processPayment(idempotencyKey, Money.of(500_000), "CARD-VISA");

        System.out.println("\nAttempt 2 (network timeout → retry same key):");
        var result2 = processor.processPayment(idempotencyKey, Money.of(500_000), "CARD-VISA");

        System.out.println("\nAttempt 3 (another retry):");
        var result3 = processor.processPayment(idempotencyKey, Money.of(500_000), "CARD-VISA");

        System.out.println("\nAll 3 attempts same txId: " +
            (result1.txId().equals(result2.txId()) && result2.txId().equals(result3.txId()) ? "✅" : "❌"));
        System.out.println("No double charge occurred ✅");

        System.out.println("\nNew key (different request):");
        String newKey = "REQ-" + UUID.randomUUID().toString().substring(0, 8);
        processor.processPayment(newKey, Money.of(200_000), "CARD-VISA");
    }

    static void demoOrchestrationVsChoreography() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 6: Orchestration vs Choreography Trade-offs");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            ORCHESTRATION:
            ──────────────────────────────────────────────────
            + Central visibility: "Where is this saga right now?" → DB
            + Error handling: retry/timeout in one place
            + Easy to add new step (add to orchestrator only)
            + Easy to test: mock services, test state machine
            + Audit: orchestrator logs every step/state
            - Orchestrator becomes a bottleneck / God Object
            - Services coupled to orchestrator's protocol
            - Single point of failure (mitigate: persist saga state)

            Best for:
              → Complex workflows (10+ steps, conditional branches)
              → Compliance-heavy domains (fintech, healthcare)
              → When you need detailed audit trail

            CHOREOGRAPHY:
            ──────────────────────────────────────────────────
            + Loose coupling: services only know events they care about
            + High scalability: no central coordinator
            + Services can evolve independently
            - "Where is this saga?" → very hard to answer
            - Tracking overall saga state requires dedicated service
            - Adding new step may require changing multiple services
            - Cycle detection: A→B→C→A (accidental cycle)
            - Testing end-to-end flow is harder

            Best for:
              → Simple 2-3 step flows
              → Teams that own separate services
              → Event-driven architecture already in place

            IN PRACTICE:
            ──────────────────────────────────────────────────
              - Use Orchestration for business-critical, complex sagas
              - Use Choreography for simple reactive flows
              - Frameworks: Axon Framework, Eventuate Tram, Apache Camel
              - AWS Step Functions = managed orchestration
            """);

        System.out.println("SAGA vs 2PC:");
        System.out.println("""
              Feature          | 2PC               | Saga
              ─────────────────|───────────────────|─────────────────
              Blocking         | YES (locks held)  | NO (local tx)
              Latency          | High              | Low
              Throughput       | Low               | High
              Failure recovery | Coordinator crash | Compensate
              Cross-DB/service | Hard              | Native
              Consistency      | Strong (ACID)     | Eventual
              Complexity       | Low               | High
            """);
    }

    // ═══════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  BÀI 9.3 — SAGA PATTERN: DISTRIBUTED TRANSACTIONS ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        demoOrchestrationSuccess();
        demoOrchestrationPaymentFailure();
        demoOrchestrationInventoryFailure();
        demoChoreography();
        demoIdempotency();
        demoOrchestrationVsChoreography();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TỔNG KẾT BÀI 9.3                                ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║                                                   ║");
        System.out.println("║  SAGA = chuỗi local tx + compensating tx        ║");
        System.out.println("║  KHÔNG dùng 2PC trong microservices              ║");
        System.out.println("║                                                   ║");
        System.out.println("║  ORCHESTRATION: central coordinator, visible     ║");
        System.out.println("║    state, best for complex flows (5+ steps)      ║");
        System.out.println("║                                                   ║");
        System.out.println("║  CHOREOGRAPHY: event-driven, decoupled,          ║");
        System.out.println("║    best for simple 2-3 step flows                ║");
        System.out.println("║                                                   ║");
        System.out.println("║  COMPENSATING TX: phải idempotent + always       ║");
        System.out.println("║    succeed (no rollback of rollback)             ║");
        System.out.println("║                                                   ║");
        System.out.println("║  IDEMPOTENCY KEY: client generates UUID,         ║");
        System.out.println("║    server deduplicates → safe retry              ║");
        System.out.println("║                                                   ║");
        System.out.println("║  Compensate in REVERSE order:                    ║");
        System.out.println("║    T1→T2→T3 fail → C2→C1 (not C3!)             ║");
        System.out.println("║                                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }
}
