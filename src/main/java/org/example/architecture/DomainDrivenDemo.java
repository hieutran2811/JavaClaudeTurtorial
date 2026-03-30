package org.example.architecture;

import java.util.*;
import java.util.stream.*;
import java.time.*;
import java.math.*;

/**
 * ============================================================
 * BÀI 9.1 — DOMAIN DRIVEN DESIGN (DDD)
 * ============================================================
 *
 * DDD là gì?
 *   Cách tổ chức code để phản ánh đúng business domain.
 *   Code = ngôn ngữ của business (Ubiquitous Language).
 *   Không phải "Table → DAO → Service → Controller".
 *
 * Vấn đề DDD giải quyết:
 *   - Code không ai hiểu ngoài dev (không map với business logic)
 *   - Anemic Domain Model: class chỉ có getter/setter, logic nằm hết trong Service
 *   - God Service: OrderService 3000 dòng làm tất cả mọi thứ
 *   - DB schema drive design thay vì business rules drive design
 *
 * ============================================================
 * CÁC BUILDING BLOCKS CHÍNH
 * ============================================================
 *
 * ENTITY:
 *   - Có identity (id): 2 order với id khác nhau = 2 entity khác
 *   - Mutable state (trạng thái thay đổi theo thời gian)
 *   - equals() dựa trên id, không phải fields
 *   - Ví dụ: Order, Customer, Product
 *
 * VALUE OBJECT:
 *   - Không có identity: 2 Money(100, USD) = cùng một giá trị
 *   - Immutable (bất biến)
 *   - equals() dựa trên tất cả fields
 *   - Ví dụ: Money, Address, Email, DateRange
 *
 * AGGREGATE:
 *   - Cluster of entities + value objects với 1 root
 *   - Aggregate Root = entry point duy nhất từ ngoài vào
 *   - Invariant (bất biến business) được enforce trong aggregate
 *   - Ví dụ: Order (root) + OrderLine[] + ShippingAddress
 *
 * REPOSITORY:
 *   - Interface trong domain layer
 *   - Implementation trong infrastructure layer
 *   - Domain không biết DB, chỉ biết "lưu/lấy entity"
 *
 * DOMAIN SERVICE:
 *   - Logic thuộc về domain nhưng không tự nhiên nằm trong 1 entity
 *   - Stateless
 *   - Ví dụ: TransferService (liên quan 2 Account)
 *
 * APPLICATION SERVICE:
 *   - Orchestrate use case: load entity → gọi domain logic → save
 *   - Không chứa business logic
 *   - Handle transaction, security, DTO mapping
 *
 * DOMAIN EVENT:
 *   - Điều gì đó đã xảy ra trong domain
 *   - Immutable, past tense: OrderPlaced, PaymentReceived
 *   - Decouples bounded contexts
 *
 * BOUNDED CONTEXT:
 *   - Ranh giới rõ ràng của 1 model/ngôn ngữ
 *   - "Order" trong Sales context ≠ "Order" trong Warehouse context
 *   - Mỗi BC có code base / team / DB riêng
 *
 * ============================================================
 * DOMAIN MODEL: E-COMMERCE ORDER MANAGEMENT
 * ============================================================
 */
public class DomainDrivenDemo {

    // ═══════════════════════════════════════════════════════
    // SECTION 1: VALUE OBJECTS — Immutable, equality by value
    // ═══════════════════════════════════════════════════════

    /**
     * Money — Value Object điển hình.
     *
     * SAI: dùng double price = 9.99 (floating point error)
     * ĐÚNG: Money(BigDecimal, Currency) — immutable, type-safe
     *
     * KEY: equals() và hashCode() dựa trên amount + currency.
     *      Money là Java record → tự động đúng.
     */
    record Money(BigDecimal amount, String currency) {
        Money {
            Objects.requireNonNull(amount, "amount required");
            Objects.requireNonNull(currency, "currency required");
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Money amount cannot be negative: " + amount);
            }
            amount = amount.setScale(2, RoundingMode.HALF_UP); // normalize
        }

        static Money of(double amount, String currency) {
            return new Money(BigDecimal.valueOf(amount), currency);
        }

        static Money zero(String currency) {
            return new Money(BigDecimal.ZERO, currency);
        }

        Money add(Money other) {
            assertSameCurrency(other);
            return new Money(amount.add(other.amount), currency);
        }

        Money subtract(Money other) {
            assertSameCurrency(other);
            BigDecimal result = amount.subtract(other.amount);
            if (result.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Cannot subtract: result would be negative");
            }
            return new Money(result, currency);
        }

        Money multiply(int factor) {
            return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
        }

        Money multiply(BigDecimal factor) {
            return new Money(amount.multiply(factor).setScale(2, RoundingMode.HALF_UP), currency);
        }

        boolean isGreaterThan(Money other) {
            assertSameCurrency(other);
            return amount.compareTo(other.amount) > 0;
        }

        private void assertSameCurrency(Money other) {
            if (!currency.equals(other.currency)) {
                throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " vs " + other.currency);
            }
        }

        @Override
        public String toString() {
            return amount.toPlainString() + " " + currency;
        }
    }

    /**
     * Email — Value Object với validation.
     * Tránh Primitive Obsession: String email → Email email
     */
    record Email(String value) {
        Email {
            Objects.requireNonNull(value, "email required");
            if (!value.matches("^[^@]+@[^@]+\\.[^@]+$")) {
                throw new IllegalArgumentException("Invalid email: " + value);
            }
            value = value.toLowerCase().trim();
        }

        String domain() { return value.substring(value.indexOf('@') + 1); }
    }

    /**
     * Address — Value Object phức hợp.
     * Immutable: thay đổi address = tạo Address mới
     */
    record Address(String street, String city, String country, String zipCode) {
        Address {
            Objects.requireNonNull(street, "street required");
            Objects.requireNonNull(city, "city required");
            Objects.requireNonNull(country, "country required");
        }

        Address withCity(String newCity) {
            return new Address(street, newCity, country, zipCode);
        }

        @Override
        public String toString() {
            return street + ", " + city + " " + zipCode + ", " + country;
        }
    }

    /**
     * ProductId, CustomerId, OrderId — Typed IDs (tránh primitive obsession)
     *
     * BAD:  void ship(String orderId, String customerId) // dễ đổi chỗ
     * GOOD: void ship(OrderId orderId, CustomerId customerId) // compile-time safe
     */
    record OrderId(String value) {
        OrderId { Objects.requireNonNull(value); }
        static OrderId generate() { return new OrderId("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()); }
        @Override public String toString() { return value; }
    }

    record CustomerId(String value) {
        CustomerId { Objects.requireNonNull(value); }
        static CustomerId generate() { return new CustomerId("CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()); }
    }

    record ProductId(String value) {
        ProductId { Objects.requireNonNull(value); }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 2: ENTITIES — Identity-based equality
    // ═══════════════════════════════════════════════════════

    /**
     * OrderLine — Entity bên trong Aggregate Order.
     * Không tồn tại độc lập, chỉ có nghĩa trong context của Order.
     */
    static class OrderLine {
        private final ProductId productId;
        private final String productName;
        private int quantity;
        private final Money unitPrice;

        OrderLine(ProductId productId, String productName, int quantity, Money unitPrice) {
            if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
            this.productId   = Objects.requireNonNull(productId);
            this.productName = Objects.requireNonNull(productName);
            this.quantity    = quantity;
            this.unitPrice   = Objects.requireNonNull(unitPrice);
        }

        void increaseQuantity(int additional) {
            if (additional <= 0) throw new IllegalArgumentException("Additional quantity must be positive");
            this.quantity += additional;
        }

        Money lineTotal() { return unitPrice.multiply(quantity); }

        ProductId productId()   { return productId; }
        String    productName() { return productName; }
        int       quantity()    { return quantity; }
        Money     unitPrice()   { return unitPrice; }

        @Override
        public String toString() {
            return String.format("  %s × %d @ %s = %s", productName, quantity, unitPrice, lineTotal());
        }
    }

    /**
     * Order — AGGREGATE ROOT.
     *
     * Invariants (bất biến business) được enforce tại đây:
     *   1. Không thể add item vào order đã CONFIRMED
     *   2. Total không thể âm
     *   3. Order phải có ít nhất 1 item để confirm
     *   4. Cancel chỉ khi chưa SHIPPED
     *
     * Từ bên ngoài: chỉ tương tác qua Order (Aggregate Root).
     * KHÔNG bao giờ: orderLine.setQuantity(5) trực tiếp.
     */
    static class Order {
        enum Status { DRAFT, CONFIRMED, SHIPPED, DELIVERED, CANCELLED }

        private final OrderId id;
        private final CustomerId customerId;
        private final List<OrderLine> lines = new ArrayList<>();
        private final Address shippingAddress;
        private Status status;
        private final Instant createdAt;
        private Instant updatedAt;
        private final List<DomainEvent> domainEvents = new ArrayList<>();

        Order(OrderId id, CustomerId customerId, Address shippingAddress) {
            this.id              = Objects.requireNonNull(id);
            this.customerId      = Objects.requireNonNull(customerId);
            this.shippingAddress = Objects.requireNonNull(shippingAddress);
            this.status          = Status.DRAFT;
            this.createdAt       = Instant.now();
            this.updatedAt       = this.createdAt;
        }

        // ── Business Methods (Rich Domain Model) ────────────────

        /**
         * INVARIANT: Chỉ DRAFT order mới có thể thêm item.
         * Nếu item cùng product đã tồn tại → tăng quantity.
         */
        void addItem(ProductId productId, String name, int qty, Money unitPrice) {
            requireStatus(Status.DRAFT, "add items to");

            // Business rule: tăng qty nếu product đã có
            lines.stream()
                .filter(l -> l.productId().equals(productId))
                .findFirst()
                .ifPresentOrElse(
                    existing -> existing.increaseQuantity(qty),
                    () -> lines.add(new OrderLine(productId, name, qty, unitPrice))
                );
            touch();
        }

        void removeItem(ProductId productId) {
            requireStatus(Status.DRAFT, "remove items from");
            boolean removed = lines.removeIf(l -> l.productId().equals(productId));
            if (!removed) throw new NoSuchElementException("Product not in order: " + productId);
            touch();
        }

        /**
         * INVARIANT: Phải có ít nhất 1 item để confirm.
         * Sau confirm: emit domain event OrderConfirmed.
         */
        void confirm() {
            requireStatus(Status.DRAFT, "confirm");
            if (lines.isEmpty()) {
                throw new IllegalStateException("Cannot confirm empty order");
            }
            this.status = Status.CONFIRMED;
            touch();
            domainEvents.add(new OrderConfirmed(id, customerId, total(), Instant.now()));
        }

        void ship() {
            requireStatus(Status.CONFIRMED, "ship");
            this.status = Status.SHIPPED;
            touch();
            domainEvents.add(new OrderShipped(id, Instant.now()));
        }

        void deliver() {
            requireStatus(Status.SHIPPED, "deliver");
            this.status = Status.DELIVERED;
            touch();
        }

        void cancel(String reason) {
            if (status == Status.SHIPPED || status == Status.DELIVERED) {
                throw new IllegalStateException("Cannot cancel order in status: " + status);
            }
            this.status = Status.CANCELLED;
            touch();
            domainEvents.add(new OrderCancelled(id, reason, Instant.now()));
        }

        // ── Queries (no side effects) ────────────────────────────

        Money total() {
            return lines.stream()
                .map(OrderLine::lineTotal)
                .reduce(Money.zero("VND"), Money::add);
        }

        boolean isEmpty()          { return lines.isEmpty(); }
        OrderId id()               { return id; }
        CustomerId customerId()    { return customerId; }
        Status status()            { return status; }
        Address shippingAddress()  { return shippingAddress; }
        Instant createdAt()        { return createdAt; }
        List<OrderLine> lines()    { return Collections.unmodifiableList(lines); }

        List<DomainEvent> pullDomainEvents() {
            List<DomainEvent> events = new ArrayList<>(domainEvents);
            domainEvents.clear(); // consume once
            return events;
        }

        // ── equals/hashCode: identity-based ─────────────────────

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Order other)) return false;
            return id.equals(other.id); // identity = id only
        }

        @Override
        public int hashCode() { return id.hashCode(); }

        private void requireStatus(Status required, String action) {
            if (status != required) {
                throw new IllegalStateException(
                    "Cannot " + action + " order in status " + status + " (required: " + required + ")");
            }
        }

        private void touch() { this.updatedAt = Instant.now(); }

        @Override
        public String toString() {
            return String.format("Order[%s] customer=%s status=%s total=%s",
                id, customerId, status, total());
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 3: DOMAIN EVENTS — Something happened
    // ═══════════════════════════════════════════════════════

    sealed interface DomainEvent permits OrderConfirmed, OrderShipped, OrderCancelled {
        Instant occurredAt();
    }

    record OrderConfirmed(OrderId orderId, CustomerId customerId, Money total, Instant occurredAt)
        implements DomainEvent {}

    record OrderShipped(OrderId orderId, Instant occurredAt)
        implements DomainEvent {}

    record OrderCancelled(OrderId orderId, String reason, Instant occurredAt)
        implements DomainEvent {}

    // ═══════════════════════════════════════════════════════
    // SECTION 4: REPOSITORY — Domain interface, Infra implements
    // ═══════════════════════════════════════════════════════

    /**
     * Repository interface: nằm trong domain layer.
     * Domain biết "lưu Order" nhưng không biết "lưu vào PostgreSQL hay MongoDB".
     *
     * Dependency Rule: Domain ← Application ← Infrastructure
     *   Infrastructure implement interface của Domain (Dependency Inversion)
     */
    interface OrderRepository {
        void save(Order order);
        Optional<Order> findById(OrderId id);
        List<Order> findByCustomer(CustomerId customerId);
        List<Order> findByStatus(Order.Status status);
        void delete(OrderId id);
    }

    /**
     * In-memory implementation (thường dùng trong tests).
     * Production: PostgresOrderRepository, MongoOrderRepository...
     */
    static class InMemoryOrderRepository implements OrderRepository {
        private final Map<OrderId, Order> store = new LinkedHashMap<>();

        @Override public void save(Order order) { store.put(order.id(), order); }

        @Override
        public Optional<Order> findById(OrderId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Order> findByCustomer(CustomerId customerId) {
            return store.values().stream()
                .filter(o -> o.customerId().equals(customerId))
                .collect(Collectors.toList());
        }

        @Override
        public List<Order> findByStatus(Order.Status status) {
            return store.values().stream()
                .filter(o -> o.status() == status)
                .collect(Collectors.toList());
        }

        @Override public void delete(OrderId id) { store.remove(id); }

        int count() { return store.size(); }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 5: DOMAIN SERVICE — Cross-entity logic
    // ═══════════════════════════════════════════════════════

    /**
     * Domain Service: logic thuộc về domain nhưng không fit vào 1 entity.
     *
     * Ví dụ: PricingService cần biết Customer tier + Product category
     *        để tính discount → không thuộc về Order hay Customer riêng lẻ.
     *
     * KHÔNG phải Application Service:
     *   - Không load/save entity
     *   - Không handle transaction
     *   - Chứa thuần business logic
     */
    static class PricingDomainService {

        enum CustomerTier { REGULAR, SILVER, GOLD, PLATINUM }

        /**
         * Business rule: discount theo tier + order total
         *   REGULAR:   0% always
         *   SILVER:    5% if total > 500k VND
         *   GOLD:      10% if total > 200k VND, 15% if total > 1M VND
         *   PLATINUM:  20% always, 25% if total > 2M VND
         */
        Money calculateDiscount(Money orderTotal, CustomerTier tier) {
            BigDecimal rate = switch (tier) {
                case REGULAR  -> BigDecimal.ZERO;
                case SILVER   -> orderTotal.isGreaterThan(Money.of(500_000, "VND"))
                                    ? new BigDecimal("0.05") : BigDecimal.ZERO;
                case GOLD     -> orderTotal.isGreaterThan(Money.of(1_000_000, "VND"))
                                    ? new BigDecimal("0.15")
                                    : orderTotal.isGreaterThan(Money.of(200_000, "VND"))
                                        ? new BigDecimal("0.10") : BigDecimal.ZERO;
                case PLATINUM -> orderTotal.isGreaterThan(Money.of(2_000_000, "VND"))
                                    ? new BigDecimal("0.25") : new BigDecimal("0.20");
            };
            return orderTotal.multiply(rate);
        }

        Money applyDiscount(Money total, Money discount) {
            return total.subtract(discount);
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 6: APPLICATION SERVICE — Orchestrate use cases
    // ═══════════════════════════════════════════════════════

    /**
     * Application Service: điều phối use case.
     *
     * PlaceOrderCommand → load entities → gọi domain logic → save → publish events
     *
     * KHÔNG chứa business rules!
     * Business rules nằm trong: Entity, Value Object, Domain Service.
     */
    static class OrderApplicationService {

        record PlaceOrderCommand(
            CustomerId customerId,
            Address shippingAddress,
            List<OrderItemDto> items
        ) {}

        record OrderItemDto(ProductId productId, String name, int quantity, Money unitPrice) {}

        record OrderSummaryDto(OrderId orderId, Money total, Order.Status status) {}

        private final OrderRepository orderRepository;
        private final PricingDomainService pricingService;
        private final List<DomainEvent> publishedEvents = new ArrayList<>(); // simulate event bus

        OrderApplicationService(OrderRepository repo, PricingDomainService pricing) {
            this.orderRepository = repo;
            this.pricingService  = pricing;
        }

        /**
         * Use case: Place Order
         *   1. Create Order aggregate
         *   2. Add items (domain validates invariants)
         *   3. Confirm (domain validates: not empty)
         *   4. Save
         *   5. Publish domain events
         */
        OrderSummaryDto placeOrder(PlaceOrderCommand cmd) {
            // 1. Create aggregate
            Order order = new Order(
                OrderId.generate(),
                cmd.customerId(),
                cmd.shippingAddress()
            );

            // 2. Add items (domain enforces invariants)
            for (var item : cmd.items()) {
                order.addItem(item.productId(), item.name(), item.quantity(), item.unitPrice());
            }

            // 3. Confirm (domain enforces: must have items)
            order.confirm();

            // 4. Save (repository abstraction — no SQL here!)
            orderRepository.save(order);

            // 5. Publish domain events
            List<DomainEvent> events = order.pullDomainEvents();
            publishedEvents.addAll(events);
            events.forEach(e -> System.out.println("  [EVENT] " + e.getClass().getSimpleName()
                + " → " + e));

            return new OrderSummaryDto(order.id(), order.total(), order.status());
        }

        /**
         * Use case: Cancel Order
         */
        void cancelOrder(OrderId orderId, String reason) {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

            order.cancel(reason); // domain enforces: cannot cancel SHIPPED/DELIVERED

            orderRepository.save(order);

            List<DomainEvent> events = order.pullDomainEvents();
            publishedEvents.addAll(events);
        }

        List<DomainEvent> publishedEvents() { return Collections.unmodifiableList(publishedEvents); }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 7: BOUNDED CONTEXT — Separate models
    // ═══════════════════════════════════════════════════════

    /**
     * Bounded Context: "Order" trong Sales context vs Warehouse context.
     *
     * Sales Context:    Order có customer, payment, discount, promo codes
     * Warehouse Context: Order có items, locations, picking list, weight
     *
     * Cùng concept "Order" nhưng khác model → 2 Bounded Context riêng.
     * Giao tiếp qua: Domain Events hoặc ACL (Anti-Corruption Layer).
     */
    static class WarehouseBoundedContext {

        /**
         * Warehouse's view of an order — hoàn toàn khác Sales.
         * Không import Sales Order class!
         */
        record PickingOrder(
            String warehouseOrderId,
            List<PickingItem> items,
            String zone,
            Instant scheduledAt
        ) {}

        record PickingItem(String sku, int quantity, String location) {}

        /**
         * ACL (Anti-Corruption Layer): translate Sales event → Warehouse model.
         * Giữ cho 2 context độc lập — thay đổi Sales model không break Warehouse.
         */
        static class OrderConfirmedTranslator {
            PickingOrder translate(OrderConfirmed salesEvent) {
                // Translate Sales domain event → Warehouse picking order
                List<PickingItem> items = List.of(
                    new PickingItem("SKU-TRANSLATED", 1, "ZONE-A-01")
                );
                return new PickingOrder(
                    "WH-" + salesEvent.orderId().value(),
                    items,
                    "ZONE-A",
                    Instant.now().plusSeconds(3600)
                );
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // DEMO RUNNERS
    // ═══════════════════════════════════════════════════════

    static void demoValueObjects() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 1: Value Objects");
        System.out.println("═══════════════════════════════════════════════════");

        // Money operations
        Money price1 = Money.of(150_000, "VND");
        Money price2 = Money.of(200_000, "VND");
        Money total  = price1.add(price2);
        Money half   = total.multiply(new BigDecimal("0.5"));

        System.out.println("Money:");
        System.out.println("  " + price1 + " + " + price2 + " = " + total);
        System.out.println("  " + total + " × 0.5 = " + half);
        System.out.println("  isGreaterThan: " + price2.isGreaterThan(price1));

        // Value equality (Record = by fields)
        Money a = Money.of(100, "VND");
        Money b = Money.of(100, "VND");
        System.out.println("  Money.of(100,VND) == Money.of(100,VND): " + a.equals(b) + " (value equality ✅)");

        // Email validation
        System.out.println("\nEmail:");
        try {
            Email email = new Email("  Alice@EXAMPLE.COM  ");
            System.out.println("  normalized: " + email.value() + " | domain: " + email.domain());
        } catch (Exception e) { System.out.println("  " + e.getMessage()); }

        try {
            new Email("not-an-email");
        } catch (IllegalArgumentException e) {
            System.out.println("  invalid email caught: " + e.getMessage());
        }

        // Address
        System.out.println("\nAddress:");
        Address addr = new Address("123 Nguyen Hue", "Ho Chi Minh City", "VN", "70000");
        Address moved = addr.withCity("Hanoi"); // new instance
        System.out.println("  original : " + addr);
        System.out.println("  withCity : " + moved);
        System.out.println("  same ref : " + (addr == moved) + " (immutable — new instance ✅)");

        // Currency mismatch
        System.out.println("\nCurrency safety:");
        try {
            Money.of(100, "VND").add(Money.of(100, "USD"));
        } catch (IllegalArgumentException e) {
            System.out.println("  caught: " + e.getMessage() + " ✅");
        }
    }

    static void demoAggregateInvariants() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 2: Aggregate & Invariant Enforcement");
        System.out.println("═══════════════════════════════════════════════════");

        CustomerId cust = new CustomerId("CUST-001");
        Address addr    = new Address("456 Le Loi", "Ho Chi Minh City", "VN", "70000");
        Order order     = new Order(OrderId.generate(), cust, addr);

        // Add items
        order.addItem(new ProductId("P-001"), "Laptop", 1, Money.of(15_000_000, "VND"));
        order.addItem(new ProductId("P-002"), "Mouse",  2, Money.of(500_000, "VND"));

        // Add same product again → merge quantity
        order.addItem(new ProductId("P-002"), "Mouse", 1, Money.of(500_000, "VND"));

        System.out.println("Order items:");
        order.lines().forEach(System.out::println);
        System.out.println("  Total: " + order.total());
        System.out.println("  Lines count: " + order.lines().size() + " (P-002 merged ✅)");

        // Confirm
        order.confirm();
        System.out.println("\nAfter confirm: " + order.status());

        // Invariant: cannot add item after confirm
        System.out.println("\nInvariant tests:");
        try {
            order.addItem(new ProductId("P-003"), "Keyboard", 1, Money.of(1_000_000, "VND"));
        } catch (IllegalStateException e) {
            System.out.println("  [OK] Add after confirm: " + e.getMessage());
        }

        // Invariant: cannot confirm empty order
        Order emptyOrder = new Order(OrderId.generate(), cust, addr);
        try {
            emptyOrder.confirm();
        } catch (IllegalStateException e) {
            System.out.println("  [OK] Confirm empty order: " + e.getMessage());
        }

        // Ship
        order.ship();
        System.out.println("\nAfter ship: " + order.status());

        // Invariant: cannot cancel shipped order
        try {
            order.cancel("customer request");
        } catch (IllegalStateException e) {
            System.out.println("  [OK] Cancel shipped: " + e.getMessage());
        }

        // Domain events
        Order order2 = new Order(OrderId.generate(), cust, addr);
        order2.addItem(new ProductId("P-001"), "Laptop", 1, Money.of(15_000_000, "VND"));
        order2.confirm();
        order2.cancel("changed mind");
        List<DomainEvent> events = order2.pullDomainEvents();
        System.out.println("\nDomain events from order2: " + events.size());
        events.forEach(e -> System.out.println("  → " + e.getClass().getSimpleName() + " at " + e.occurredAt()));
        // Events consumed — next pull is empty
        System.out.println("  Second pull: " + order2.pullDomainEvents().size() + " events (consumed ✅)");
    }

    static void demoDomainService() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 3: Domain Service — PricingDomainService");
        System.out.println("═══════════════════════════════════════════════════");

        PricingDomainService pricing = new PricingDomainService();

        Object[][] cases = {
            {Money.of(300_000, "VND"),   PricingDomainService.CustomerTier.REGULAR},
            {Money.of(600_000, "VND"),   PricingDomainService.CustomerTier.SILVER},
            {Money.of(300_000, "VND"),   PricingDomainService.CustomerTier.GOLD},
            {Money.of(1_200_000, "VND"), PricingDomainService.CustomerTier.GOLD},
            {Money.of(1_000_000, "VND"), PricingDomainService.CustomerTier.PLATINUM},
            {Money.of(2_500_000, "VND"), PricingDomainService.CustomerTier.PLATINUM},
        };

        System.out.printf("  %-15s %-12s %-15s %-15s%n", "Total", "Tier", "Discount", "Final Price");
        System.out.println("  " + "─".repeat(60));
        for (Object[] c : cases) {
            Money total    = (Money) c[0];
            var   tier     = (PricingDomainService.CustomerTier) c[1];
            Money discount = pricing.calculateDiscount(total, tier);
            Money final_   = pricing.applyDiscount(total, discount);
            System.out.printf("  %-15s %-12s %-15s %-15s%n",
                total, tier, discount, final_);
        }
    }

    static void demoApplicationService() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 4: Application Service — PlaceOrder Use Case");
        System.out.println("═══════════════════════════════════════════════════");

        InMemoryOrderRepository repo = new InMemoryOrderRepository();
        PricingDomainService pricing = new PricingDomainService();
        OrderApplicationService svc  = new OrderApplicationService(repo, pricing);

        CustomerId customerId = new CustomerId("CUST-001");
        Address address = new Address("789 Tran Hung Dao", "Ho Chi Minh City", "VN", "70000");

        // Place order
        System.out.println("\n[PlaceOrder use case]");
        var cmd = new OrderApplicationService.PlaceOrderCommand(
            customerId,
            address,
            List.of(
                new OrderApplicationService.OrderItemDto(
                    new ProductId("P-001"), "MacBook Pro", 1, Money.of(35_000_000, "VND")),
                new OrderApplicationService.OrderItemDto(
                    new ProductId("P-002"), "Magic Mouse", 1, Money.of(2_500_000, "VND"))
            )
        );
        var summary = svc.placeOrder(cmd);
        System.out.println("  Result: orderId=" + summary.orderId() + " total=" + summary.total() + " status=" + summary.status());

        // Verify stored
        Order stored = repo.findById(summary.orderId()).orElseThrow();
        System.out.println("  Stored: " + stored);
        System.out.println("  Events published: " + svc.publishedEvents().size());

        // Cancel
        System.out.println("\n[CancelOrder use case]");
        OrderId orderId2 = svc.placeOrder(new OrderApplicationService.PlaceOrderCommand(
            customerId, address,
            List.of(new OrderApplicationService.OrderItemDto(
                new ProductId("P-003"), "iPad", 1, Money.of(20_000_000, "VND")))
        )).orderId();

        svc.cancelOrder(orderId2, "Customer changed mind");
        Order cancelled = repo.findById(orderId2).orElseThrow();
        System.out.println("  Cancelled order status: " + cancelled.status());
        System.out.println("  Total events published: " + svc.publishedEvents().size());

        // List by status
        System.out.println("\n[Query by status]");
        System.out.println("  CONFIRMED orders: " + repo.findByStatus(Order.Status.CONFIRMED).size());
        System.out.println("  CANCELLED orders: " + repo.findByStatus(Order.Status.CANCELLED).size());
        System.out.println("  Customer orders:  " + repo.findByCustomer(customerId).size());
    }

    static void demoBoundedContext() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 5: Bounded Context & Anti-Corruption Layer");
        System.out.println("═══════════════════════════════════════════════════");

        // Sales context produces OrderConfirmed event
        OrderConfirmed salesEvent = new OrderConfirmed(
            new OrderId("ORD-ABC123"),
            new CustomerId("CUST-001"),
            Money.of(37_500_000, "VND"),
            Instant.now()
        );
        System.out.println("Sales Context event: " + salesEvent.getClass().getSimpleName());
        System.out.println("  orderId=" + salesEvent.orderId() + " total=" + salesEvent.total());

        // Warehouse context receives event via ACL
        var translator = new WarehouseBoundedContext.OrderConfirmedTranslator();
        var pickingOrder = translator.translate(salesEvent);
        System.out.println("\nWarehouse Context (after ACL translation):");
        System.out.println("  Picking Order ID : " + pickingOrder.warehouseOrderId());
        System.out.println("  Zone             : " + pickingOrder.zone());
        System.out.println("  Scheduled at     : " + pickingOrder.scheduledAt());
        System.out.println("  Items            : " + pickingOrder.items());

        System.out.println("\nACL benefits:");
        System.out.println("  ✅ Warehouse doesn't import Sales classes");
        System.out.println("  ✅ Sales model change → only ACL needs update");
        System.out.println("  ✅ Each context can evolve independently");
    }

    static void demoAnemicVsRich() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 6: Anemic Domain Model vs Rich Domain Model");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            ANEMIC DOMAIN MODEL (Anti-Pattern):
            ─────────────────────────────────────
            class Order {               // just a data bag
                private String status;
                private List<Item> items;
                public String getStatus() { return status; }
                public void setStatus(String s) { status = s; } // ← no validation!
                public void setItems(List<Item> i) { items = i; }
            }

            class OrderService {        // all logic here → God Service
                public void confirm(Order order) {
                    if (order.getItems().isEmpty()) throw ...  // business rule leaks here
                    order.setStatus("CONFIRMED");              // bypasses invariants
                    // 500 more lines of business logic...
                }
                public void cancel(Order order) { ... }
                public void ship(Order order) { ... }
                public Money calcTotal(Order order) { ... }
            }

            Problems:
              ❌ Business rules scattered across OrderService, PaymentService, etc.
              ❌ setStatus("CONFIRMED") bypasses ALL invariant checks
              ❌ OrderService becomes God Service (1000+ lines)
              ❌ Ubiquitous Language lost: code ≠ business language
            """);

        System.out.println("""
            RICH DOMAIN MODEL (DDD Way):
            ─────────────────────────────────────
            class Order {               // encapsulates behavior + data
                void confirm() {        // business method, not setter
                    requireStatus(DRAFT, "confirm");
                    if (lines.isEmpty()) throw new IllegalStateException("...");
                    this.status = CONFIRMED;
                    domainEvents.add(new OrderConfirmed(...));
                }
                void cancel(String reason) { ... }  // invariants enforced
                Money total() { ... }               // computed, not stored
            }

            class OrderApplicationService {  // thin orchestrator
                void placeOrder(PlaceOrderCommand cmd) {
                    Order order = new Order(...);   // load/create
                    cmd.items().forEach(order::addItem);
                    order.confirm();                // domain enforces rules
                    repository.save(order);         // infrastructure concern
                }
            }

            Benefits:
              ✅ Invariants always enforced — impossible to bypass
              ✅ Code reads like business: order.confirm(), order.cancel()
              ✅ Application Service stays thin (10-20 lines per use case)
              ✅ Domain logic testable without Spring/DB/HTTP
            """);
    }

    // ═══════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  BÀI 9.1 — DOMAIN DRIVEN DESIGN (DDD)            ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        demoValueObjects();
        demoAggregateInvariants();
        demoDomainService();
        demoApplicationService();
        demoBoundedContext();
        demoAnemicVsRich();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TỔNG KẾT BÀI 9.1                                ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║                                                   ║");
        System.out.println("║  VALUE OBJECT: immutable, equality by value,     ║");
        System.out.println("║    no identity. Record = perfect fit.            ║");
        System.out.println("║                                                   ║");
        System.out.println("║  ENTITY: has identity (id), mutable state,       ║");
        System.out.println("║    equals() by id only.                          ║");
        System.out.println("║                                                   ║");
        System.out.println("║  AGGREGATE: cluster with 1 root, enforces        ║");
        System.out.println("║    invariants, only entry point from outside.    ║");
        System.out.println("║                                                   ║");
        System.out.println("║  REPOSITORY: domain interface, infra implements. ║");
        System.out.println("║    Dependency Inversion at its best.             ║");
        System.out.println("║                                                   ║");
        System.out.println("║  DOMAIN SERVICE: cross-entity business logic.    ║");
        System.out.println("║  APPLICATION SERVICE: thin orchestrator only.    ║");
        System.out.println("║                                                   ║");
        System.out.println("║  BOUNDED CONTEXT: separate model per subdomain.  ║");
        System.out.println("║    ACL translates between contexts.              ║");
        System.out.println("║                                                   ║");
        System.out.println("║  RICH > ANEMIC: behavior in entity, not service. ║");
        System.out.println("║                                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }
}
