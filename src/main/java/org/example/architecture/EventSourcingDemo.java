package org.example.architecture;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * ============================================================
 * BÀI 9.2 — EVENT SOURCING + CQRS
 * ============================================================
 *
 * TRADITIONAL (State-based):
 *   DB lưu CURRENT STATE: orders(id, status, total, ...)
 *   UPDATE orders SET status='SHIPPED' WHERE id=?
 *   → Mất lịch sử: ai đã cancel? khi nào? tại sao?
 *
 * EVENT SOURCING:
 *   DB lưu EVENTS: event_store(id, aggregate_id, type, payload, timestamp)
 *   INSERT INTO events (OrderPlaced), (ItemAdded), (OrderConfirmed), (OrderShipped)
 *   Current state = replay tất cả events từ đầu
 *   → Lịch sử đầy đủ, audit trail miễn phí, time-travel debugging
 *
 * CQRS (Command Query Responsibility Segregation):
 *   Command side (Write): nhận command → emit events → lưu vào Event Store
 *   Query side  (Read) : subscribe events → build/update Read Model (projection)
 *   → Write model tối ưu cho consistency
 *   → Read model tối ưu cho query (denormalized, pre-computed)
 *
 * ============================================================
 * KHI NÀO DÙNG EVENT SOURCING?
 * ============================================================
 *
 * NÊN DÙNG:
 *   ✅ Audit trail bắt buộc (ngân hàng, y tế, pháp lý)
 *   ✅ Time-travel: "trạng thái tháng trước là gì?"
 *   ✅ Event-driven architecture, microservices
 *   ✅ Complex business workflow (order lifecycle, loan approval)
 *   ✅ Debug production issues ("chính xác điều gì đã xảy ra?")
 *
 * KHÔNG NÊN DÙNG:
 *   ❌ Simple CRUD (user profile, config)
 *   ❌ Team chưa quen — learning curve cao
 *   ❌ Report-heavy app (CQRS projection phức tạp)
 *   ❌ Eventual consistency không chấp nhận được
 *
 * ============================================================
 * KIẾN TRÚC TỔNG QUAN
 * ============================================================
 *
 *   ┌─────────┐  Command   ┌────────────────────┐
 *   │  Client │ ─────────► │  Command Handler   │
 *   └─────────┘            └────────┬───────────┘
 *                                   │ load aggregate
 *                          ┌────────▼───────────┐
 *                          │   Event Store      │ ← append-only
 *                          │ (immutable log)    │
 *                          └────────┬───────────┘
 *                                   │ publish events
 *                          ┌────────▼───────────┐
 *                          │   Projections      │ → Read Models
 *                          │  (event handlers)  │ → OrderSummaryView
 *                          └────────────────────┘  → CustomerOrdersView
 *
 *   ┌─────────┐  Query    ┌────────────────────┐
 *   │  Client │ ─────────►│   Query Handler    │ reads from Read Models
 *   └─────────┘           └────────────────────┘
 *
 * ============================================================
 */
public class EventSourcingDemo {

    // ═══════════════════════════════════════════════════════
    // SECTION 1: DOMAIN EVENTS — immutable facts
    // ═══════════════════════════════════════════════════════

    /**
     * Domain Events = immutable facts về điều đã xảy ra.
     * Mỗi event là 1 record (past tense name).
     *
     * QUAN TRỌNG:
     *   - Không bao giờ xóa hay sửa event (append-only)
     *   - Chứa đủ dữ liệu để rebuild state (self-contained)
     *   - sequenceNumber: thứ tự trong aggregate (tránh lost update)
     */
    sealed interface DomainEvent permits
        AccountOpened, MoneyDeposited, MoneyWithdrawn,
        TransferInitiated, TransferCompleted, AccountFrozen, AccountClosed {
        String aggregateId();
        Instant occurredAt();
        int sequenceNumber();
    }

    record AccountOpened(
        String aggregateId, String owner, String currency,
        Instant occurredAt, int sequenceNumber
    ) implements DomainEvent {}

    record MoneyDeposited(
        String aggregateId, BigDecimal amount, String description,
        Instant occurredAt, int sequenceNumber
    ) implements DomainEvent {}

    record MoneyWithdrawn(
        String aggregateId, BigDecimal amount, String description,
        Instant occurredAt, int sequenceNumber
    ) implements DomainEvent {}

    record TransferInitiated(
        String aggregateId, String targetAccountId, BigDecimal amount,
        Instant occurredAt, int sequenceNumber
    ) implements DomainEvent {}

    record TransferCompleted(
        String aggregateId, String sourceAccountId, BigDecimal amount,
        Instant occurredAt, int sequenceNumber
    ) implements DomainEvent {}

    record AccountFrozen(
        String aggregateId, String reason,
        Instant occurredAt, int sequenceNumber
    ) implements DomainEvent {}

    record AccountClosed(
        String aggregateId, String reason,
        Instant occurredAt, int sequenceNumber
    ) implements DomainEvent {}

    // ═══════════════════════════════════════════════════════
    // SECTION 2: EVENT STORE — append-only log
    // ═══════════════════════════════════════════════════════

    /**
     * Event Store: trái tim của Event Sourcing.
     *
     * Invariant:
     *   - Chỉ APPEND, không UPDATE hay DELETE
     *   - Optimistic concurrency: expectedVersion prevent lost update
     *     (2 requests load version 3, cả 2 cùng append → 1 sẽ conflict)
     *
     * Production implementations:
     *   - EventStoreDB (native ES database)
     *   - PostgreSQL với append-only table
     *   - Kafka (events as topics)
     *   - AWS DynamoDB Streams
     */
    static class EventStore {
        // aggregateId → ordered list of events
        private final Map<String, List<DomainEvent>> store = new ConcurrentHashMap<>();
        // global event log (for projections)
        private final List<DomainEvent> globalLog = new CopyOnWriteArrayList<>();
        // event handlers (projections subscribe here)
        private final List<Consumer<DomainEvent>> subscribers = new CopyOnWriteArrayList<>();

        /**
         * Append events với optimistic concurrency check.
         *
         * @param aggregateId   aggregate này
         * @param events        events mới cần lưu
         * @param expectedVersion version hiện tại client đang biết
         */
        void append(String aggregateId, List<DomainEvent> events, int expectedVersion) {
            List<DomainEvent> existing = store.computeIfAbsent(aggregateId, k -> new ArrayList<>());

            // Optimistic concurrency check
            int currentVersion = existing.size();
            if (currentVersion != expectedVersion) {
                throw new OptimisticConcurrencyException(
                    "Concurrency conflict on aggregate " + aggregateId +
                    ": expected version " + expectedVersion + " but current is " + currentVersion);
            }

            existing.addAll(events);
            globalLog.addAll(events);

            // Notify projections
            events.forEach(e -> subscribers.forEach(sub -> sub.accept(e)));
        }

        /**
         * Load all events for an aggregate (replay).
         */
        List<DomainEvent> loadEvents(String aggregateId) {
            return Collections.unmodifiableList(
                store.getOrDefault(aggregateId, List.of()));
        }

        /**
         * Load events từ version cụ thể (dùng sau snapshot).
         */
        List<DomainEvent> loadEventsSince(String aggregateId, int fromVersion) {
            List<DomainEvent> all = store.getOrDefault(aggregateId, List.of());
            return all.subList(Math.min(fromVersion, all.size()), all.size());
        }

        int currentVersion(String aggregateId) {
            return store.getOrDefault(aggregateId, List.of()).size();
        }

        List<DomainEvent> globalLog() { return Collections.unmodifiableList(globalLog); }

        void subscribe(Consumer<DomainEvent> handler) { subscribers.add(handler); }

        // Stats
        int totalEvents() { return globalLog.size(); }
        int eventCount(String aggregateId) {
            return store.getOrDefault(aggregateId, List.of()).size();
        }
    }

    static class OptimisticConcurrencyException extends RuntimeException {
        OptimisticConcurrencyException(String message) { super(message); }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 3: AGGREGATE — rebuilt from events
    // ═══════════════════════════════════════════════════════

    /**
     * BankAccount Aggregate — state rebuilt bằng cách apply events.
     *
     * Pattern:
     *   1. Load events từ Event Store
     *   2. Apply từng event → rebuild current state
     *   3. Execute business logic → generate new events
     *   4. Apply new events (cập nhật in-memory state)
     *   5. Save new events vào Event Store
     *
     * KEY: apply(event) chỉ cập nhật state, KHÔNG có business logic.
     *      Business logic ở command methods (deposit, withdraw, ...)
     */
    static class BankAccount {
        enum Status { ACTIVE, FROZEN, CLOSED }

        private String accountId;
        private String owner;
        private String currency;
        private BigDecimal balance = BigDecimal.ZERO;
        private Status status;
        private int version = 0; // số events đã apply
        private final List<DomainEvent> pendingEvents = new ArrayList<>();

        // Factory: tạo account mới
        static BankAccount open(String accountId, String owner, String currency) {
            BankAccount acc = new BankAccount();
            acc.applyAndRecord(new AccountOpened(accountId, owner, currency, Instant.now(), 0));
            return acc;
        }

        // Factory: rebuild từ events
        static BankAccount reconstitute(List<DomainEvent> events) {
            if (events.isEmpty()) throw new IllegalArgumentException("Cannot reconstitute from empty events");
            BankAccount acc = new BankAccount();
            events.forEach(acc::apply);
            return acc;
        }

        // ── Command methods (business logic) ────────────────────

        void deposit(BigDecimal amount, String description) {
            requireStatus(Status.ACTIVE);
            if (amount.compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("Deposit amount must be positive");
            applyAndRecord(new MoneyDeposited(accountId, amount, description, Instant.now(), version));
        }

        void withdraw(BigDecimal amount, String description) {
            requireStatus(Status.ACTIVE);
            if (amount.compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("Withdrawal amount must be positive");
            if (balance.compareTo(amount) < 0)
                throw new IllegalStateException(
                    "Insufficient funds: balance=" + balance + " withdrawal=" + amount);
            applyAndRecord(new MoneyWithdrawn(accountId, amount, description, Instant.now(), version));
        }

        void initiateTransfer(String targetAccountId, BigDecimal amount) {
            requireStatus(Status.ACTIVE);
            if (balance.compareTo(amount) < 0)
                throw new IllegalStateException("Insufficient funds for transfer");
            applyAndRecord(new TransferInitiated(accountId, targetAccountId, amount, Instant.now(), version));
        }

        void receiveTransfer(String sourceAccountId, BigDecimal amount) {
            requireStatus(Status.ACTIVE);
            applyAndRecord(new TransferCompleted(accountId, sourceAccountId, amount, Instant.now(), version));
        }

        void freeze(String reason) {
            requireStatus(Status.ACTIVE);
            applyAndRecord(new AccountFrozen(accountId, reason, Instant.now(), version));
        }

        void close(String reason) {
            if (status == Status.CLOSED)
                throw new IllegalStateException("Account already closed");
            if (balance.compareTo(BigDecimal.ZERO) > 0)
                throw new IllegalStateException("Cannot close account with remaining balance: " + balance);
            applyAndRecord(new AccountClosed(accountId, reason, Instant.now(), version));
        }

        // ── Apply methods: state transitions ONLY, no business logic ─

        private void apply(DomainEvent event) {
            switch (event) {
                case AccountOpened e -> {
                    this.accountId = e.aggregateId();
                    this.owner     = e.owner();
                    this.currency  = e.currency();
                    this.balance   = BigDecimal.ZERO;
                    this.status    = Status.ACTIVE;
                }
                case MoneyDeposited e    -> this.balance = balance.add(e.amount());
                case MoneyWithdrawn e    -> this.balance = balance.subtract(e.amount());
                case TransferInitiated e -> this.balance = balance.subtract(e.amount());
                case TransferCompleted e -> this.balance = balance.add(e.amount());
                case AccountFrozen e     -> this.status  = Status.FROZEN;
                case AccountClosed e     -> this.status  = Status.CLOSED;
            }
            this.version++;
        }

        private void applyAndRecord(DomainEvent event) {
            apply(event);
            pendingEvents.add(event);
        }

        List<DomainEvent> pullPendingEvents() {
            List<DomainEvent> events = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
            return events;
        }

        AccountSnapshot toSnapshot() {
            return new AccountSnapshot(accountId, owner, currency, balance, status, version, Instant.now());
        }

        static BankAccount fromSnapshot(AccountSnapshot snap, List<DomainEvent> eventsSince) {
            BankAccount acc = new BankAccount();
            acc.accountId = snap.accountId();
            acc.owner     = snap.owner();
            acc.currency  = snap.currency();
            acc.balance   = snap.balance();
            acc.status    = snap.status();
            acc.version   = snap.version();
            eventsSince.forEach(acc::apply);
            return acc;
        }

        private void requireStatus(Status required) {
            if (status != required)
                throw new IllegalStateException("Account is " + status + ", required: " + required);
        }

        // Getters
        String      accountId() { return accountId; }
        String      owner()     { return owner; }
        BigDecimal  balance()   { return balance; }
        Status      status()    { return status; }
        int         version()   { return version; }
        String      currency()  { return currency; }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 4: SNAPSHOT — tối ưu replay cho long-lived aggregates
    // ═══════════════════════════════════════════════════════

    /**
     * Vấn đề: Account có 10,000 events → replay mỗi lần = chậm.
     *
     * Giải pháp: Snapshot = ảnh chụp state tại 1 thời điểm.
     *   - Lưu snapshot mỗi 100/500 events
     *   - Reload = load snapshot + replay events SAU snapshot
     *   - Thay vì: replay 10,000 events
     *   - Thành:   load snapshot(9,900) + replay 100 events
     */
    record AccountSnapshot(
        String accountId,
        String owner,
        String currency,
        BigDecimal balance,
        BankAccount.Status status,
        int version,         // version tại thời điểm snapshot
        Instant snapshotAt
    ) {}

    static class SnapshotStore {
        private final Map<String, AccountSnapshot> snapshots = new HashMap<>();
        static final int SNAPSHOT_THRESHOLD = 5; // snapshot mỗi 5 events (demo purpose)

        void save(AccountSnapshot snapshot) {
            snapshots.put(snapshot.accountId(), snapshot);
        }

        Optional<AccountSnapshot> findLatest(String accountId) {
            return Optional.ofNullable(snapshots.get(accountId));
        }

        boolean shouldTakeSnapshot(int version) {
            return version % SNAPSHOT_THRESHOLD == 0;
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 5: CQRS — Read Models (Projections)
    // ═══════════════════════════════════════════════════════

    /**
     * Projection = event handler xây dựng Read Model.
     *
     * Read Model tối ưu cho query:
     *   - Denormalized: không cần JOIN
     *   - Pre-computed: tính sẵn aggregates
     *   - Eventually consistent: có thể lag sau Write model
     *
     * Mỗi Read Model phục vụ 1 use case cụ thể:
     *   AccountSummaryView    → dashboard: balance, status
     *   TransactionHistoryView → statement: danh sách giao dịch
     *   RichCustomerView      → analytics: tổng hợp theo customer
     */

    // Read Model 1: Account Summary (cho dashboard)
    static class AccountSummaryProjection {
        record AccountSummaryView(
            String accountId, String owner, BigDecimal balance,
            BankAccount.Status status, int totalTransactions,
            BigDecimal totalDeposited, BigDecimal totalWithdrawn,
            Instant lastActivity
        ) {}

        private final Map<String, AccountSummaryView> views = new ConcurrentHashMap<>();

        void on(DomainEvent event) {
            switch (event) {
                case AccountOpened e -> views.put(e.aggregateId(),
                    new AccountSummaryView(e.aggregateId(), e.owner(),
                        BigDecimal.ZERO, BankAccount.Status.ACTIVE, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, e.occurredAt()));
                case MoneyDeposited e ->
                    views.compute(e.aggregateId(), (id, v) -> applyTransaction(v, e.amount(), e.occurredAt()));
                case MoneyWithdrawn e ->
                    views.compute(e.aggregateId(), (id, v) -> applyTransaction(v, e.amount().negate(), e.occurredAt()));
                case TransferInitiated e ->
                    views.compute(e.aggregateId(), (id, v) -> applyTransaction(v, e.amount().negate(), e.occurredAt()));
                case TransferCompleted e ->
                    views.compute(e.aggregateId(), (id, v) -> applyTransaction(v, e.amount(), e.occurredAt()));
                case AccountFrozen e ->
                    views.compute(e.aggregateId(), (id, v) ->
                        v == null ? null : new AccountSummaryView(
                            v.accountId(), v.owner(), v.balance(),
                            BankAccount.Status.FROZEN, v.totalTransactions(),
                            v.totalDeposited(), v.totalWithdrawn(), e.occurredAt()));
                case AccountClosed e ->
                    views.compute(e.aggregateId(), (id, v) ->
                        v == null ? null : new AccountSummaryView(
                            v.accountId(), v.owner(), v.balance(),
                            BankAccount.Status.CLOSED, v.totalTransactions(),
                            v.totalDeposited(), v.totalWithdrawn(), e.occurredAt()));
            }
        }

        // delta > 0: credit (deposit/transfer-in), delta < 0: debit (withdrawal/transfer-out)
        private AccountSummaryView applyTransaction(AccountSummaryView v, BigDecimal delta, Instant time) {
            if (v == null) return null;
            boolean isCredit = delta.compareTo(BigDecimal.ZERO) > 0;
            return new AccountSummaryView(
                v.accountId(), v.owner(),
                v.balance().add(delta), v.status(),
                v.totalTransactions() + 1,
                isCredit ? v.totalDeposited().add(delta) : v.totalDeposited(),
                isCredit ? v.totalWithdrawn() : v.totalWithdrawn().add(delta.negate()),
                time);
        }

        Optional<AccountSummaryView> find(String accountId) {
            return Optional.ofNullable(views.get(accountId));
        }

        Collection<AccountSummaryView> findAll() { return views.values(); }
    }

    // Read Model 2: Transaction History (cho sao kê)
    static class TransactionHistoryProjection {
        record TransactionEntry(
            String accountId, String type, BigDecimal amount,
            String description, BigDecimal balanceAfter, Instant occurredAt
        ) {}

        private final Map<String, List<TransactionEntry>> history = new ConcurrentHashMap<>();

        void on(DomainEvent event) {
            switch (event) {
                case MoneyDeposited e ->
                    addEntry(e.aggregateId(),
                        new TransactionEntry(e.aggregateId(), "DEPOSIT", e.amount(),
                            e.description(), null, e.occurredAt()));
                case MoneyWithdrawn e ->
                    addEntry(e.aggregateId(),
                        new TransactionEntry(e.aggregateId(), "WITHDRAWAL", e.amount().negate(),
                            e.description(), null, e.occurredAt()));
                case TransferInitiated e ->
                    addEntry(e.aggregateId(),
                        new TransactionEntry(e.aggregateId(), "TRANSFER_OUT", e.amount().negate(),
                            "Transfer to " + e.targetAccountId(), null, e.occurredAt()));
                case TransferCompleted e ->
                    addEntry(e.aggregateId(),
                        new TransactionEntry(e.aggregateId(), "TRANSFER_IN", e.amount(),
                            "Transfer from " + e.sourceAccountId(), null, e.occurredAt()));
                default -> {} // AccountOpened, Frozen, Closed không tạo transaction entry
            }
        }

        private void addEntry(String accountId, TransactionEntry entry) {
            history.computeIfAbsent(accountId, k -> new CopyOnWriteArrayList<>()).add(entry);
        }

        List<TransactionEntry> findByAccount(String accountId) {
            return history.getOrDefault(accountId, List.of());
        }

        List<TransactionEntry> findByAccountLastN(String accountId, int n) {
            List<TransactionEntry> all = findByAccount(accountId);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 6: COMMAND HANDLER — glue it all together
    // ═══════════════════════════════════════════════════════

    /**
     * Command Handler: nhận Command → load aggregate → execute → save events.
     *
     * CQRS Commands (write side):
     *   OpenAccountCommand, DepositCommand, WithdrawCommand, TransferCommand
     *
     * CQRS Queries (read side):
     *   GetAccountSummaryQuery → AccountSummaryProjection
     *   GetTransactionHistoryQuery → TransactionHistoryProjection
     */
    record OpenAccountCommand(String owner, String currency) {}
    record DepositCommand(String accountId, BigDecimal amount, String description) {}
    record WithdrawCommand(String accountId, BigDecimal amount, String description) {}
    record TransferCommand(String fromAccountId, String toAccountId, BigDecimal amount) {}
    record FreezeAccountCommand(String accountId, String reason) {}

    static class BankAccountCommandHandler {
        private final EventStore eventStore;
        private final SnapshotStore snapshotStore;

        BankAccountCommandHandler(EventStore eventStore, SnapshotStore snapshotStore) {
            this.eventStore    = eventStore;
            this.snapshotStore = snapshotStore;
        }

        String handle(OpenAccountCommand cmd) {
            String accountId = "ACC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            BankAccount account = BankAccount.open(accountId, cmd.owner(), cmd.currency());
            saveAggregate(account);
            return accountId;
        }

        void handle(DepositCommand cmd) {
            BankAccount account = loadAggregate(cmd.accountId());
            int expectedVersion = account.version();
            account.deposit(cmd.amount(), cmd.description());
            saveAggregate(account, expectedVersion);
        }

        void handle(WithdrawCommand cmd) {
            BankAccount account = loadAggregate(cmd.accountId());
            int expectedVersion = account.version();
            account.withdraw(cmd.amount(), cmd.description());
            saveAggregate(account, expectedVersion);
        }

        void handle(TransferCommand cmd) {
            // Load both accounts
            BankAccount from = loadAggregate(cmd.fromAccountId());
            BankAccount to   = loadAggregate(cmd.toAccountId());

            int fromVersion = from.version();
            int toVersion   = to.version();

            from.initiateTransfer(cmd.toAccountId(), cmd.amount());
            to.receiveTransfer(cmd.fromAccountId(), cmd.amount());

            saveAggregate(from, fromVersion);
            saveAggregate(to, toVersion);
        }

        void handle(FreezeAccountCommand cmd) {
            BankAccount account = loadAggregate(cmd.accountId());
            int expectedVersion = account.version();
            account.freeze(cmd.reason());
            saveAggregate(account, expectedVersion);
        }

        private BankAccount loadAggregate(String accountId) {
            Optional<AccountSnapshot> snapshot = snapshotStore.findLatest(accountId);
            if (snapshot.isPresent()) {
                AccountSnapshot snap = snapshot.get();
                List<DomainEvent> recentEvents = eventStore.loadEventsSince(accountId, snap.version());
                return BankAccount.fromSnapshot(snap, recentEvents);
            }
            List<DomainEvent> events = eventStore.loadEvents(accountId);
            if (events.isEmpty()) throw new NoSuchElementException("Account not found: " + accountId);
            return BankAccount.reconstitute(events);
        }

        private void saveAggregate(BankAccount account) {
            saveAggregate(account, 0);
        }

        private void saveAggregate(BankAccount account, int expectedVersion) {
            List<DomainEvent> pending = account.pullPendingEvents();
            if (!pending.isEmpty()) {
                eventStore.append(account.accountId(), pending, expectedVersion);
                // Take snapshot if needed
                if (snapshotStore.shouldTakeSnapshot(account.version())) {
                    snapshotStore.save(account.toSnapshot());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // DEMO RUNNERS
    // ═══════════════════════════════════════════════════════

    static void demoEventStore() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 1: Event Store — Append-Only Log");
        System.out.println("═══════════════════════════════════════════════════");

        EventStore store = new EventStore();
        String accId = "ACC-001";

        // Append events manually
        List<DomainEvent> events1 = List.of(
            new AccountOpened(accId, "Alice", "VND", Instant.now(), 0),
            new MoneyDeposited(accId, new BigDecimal("5000000"), "initial deposit", Instant.now(), 1)
        );
        store.append(accId, events1, 0);
        System.out.println("Appended 2 events. Version: " + store.currentVersion(accId));

        List<DomainEvent> events2 = List.of(
            new MoneyWithdrawn(accId, new BigDecimal("1000000"), "ATM", Instant.now(), 2)
        );
        store.append(accId, events2, 2); // expectedVersion = 2
        System.out.println("Appended 1 more event. Version: " + store.currentVersion(accId));

        // Load and replay
        List<DomainEvent> all = store.loadEvents(accId);
        System.out.println("\nAll events for " + accId + ":");
        all.forEach(e -> System.out.printf("  [v%d] %s%n", e.sequenceNumber(), e.getClass().getSimpleName()));

        // Optimistic concurrency conflict
        System.out.println("\nOptimistic concurrency test:");
        try {
            // Client A loaded version 2, Client B already wrote making it 3
            store.append(accId, List.of(
                new MoneyDeposited(accId, BigDecimal.TEN, "stale write", Instant.now(), 3)
            ), 2); // wrong expected version!
        } catch (OptimisticConcurrencyException e) {
            System.out.println("  Caught: " + e.getMessage());
        }
    }

    static void demoAggregateReplay() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 2: Aggregate Reconstitution via Event Replay");
        System.out.println("═══════════════════════════════════════════════════");

        EventStore store  = new EventStore();
        SnapshotStore ss  = new SnapshotStore();
        BankAccountCommandHandler handler = new BankAccountCommandHandler(store, ss);

        // Execute commands
        String accId = handler.handle(new OpenAccountCommand("Bob", "VND"));
        System.out.println("Opened: " + accId);

        handler.handle(new DepositCommand(accId, new BigDecimal("10000000"), "salary"));
        handler.handle(new DepositCommand(accId, new BigDecimal("2000000"),  "bonus"));
        handler.handle(new WithdrawCommand(accId, new BigDecimal("3000000"), "rent"));
        handler.handle(new WithdrawCommand(accId, new BigDecimal("500000"),  "groceries"));

        System.out.println("Events stored: " + store.eventCount(accId));
        System.out.println("Snapshot taken: " + ss.findLatest(accId).isPresent()
            + " (threshold=" + SnapshotStore.SNAPSHOT_THRESHOLD + ")");

        // Reconstitute from events
        BankAccount fromEvents = BankAccount.reconstitute(store.loadEvents(accId));
        System.out.printf("\nReconstituted from %d events:%n", store.eventCount(accId));
        System.out.println("  Owner  : " + fromEvents.owner());
        System.out.println("  Balance: " + fromEvents.balance() + " " + fromEvents.currency());
        System.out.println("  Status : " + fromEvents.status());
        System.out.println("  Version: " + fromEvents.version());

        // Time-travel: state at version 2 (after first deposit only)
        List<DomainEvent> partialEvents = store.loadEvents(accId).subList(0, 2);
        BankAccount pastState = BankAccount.reconstitute(partialEvents);
        System.out.println("\nTime-travel to version 2 (after 2nd event):");
        System.out.println("  Balance was: " + pastState.balance() + " (only first deposit)");
    }

    static void demoSnapshot() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 3: Snapshot — Optimize Long-Lived Aggregates");
        System.out.println("═══════════════════════════════════════════════════");

        EventStore store = new EventStore();
        SnapshotStore ss = new SnapshotStore();
        BankAccountCommandHandler handler = new BankAccountCommandHandler(store, ss);

        String accId = handler.handle(new OpenAccountCommand("Charlie", "VND"));

        // Generate many events to trigger snapshots
        for (int i = 1; i <= 12; i++) {
            handler.handle(new DepositCommand(accId,
                new BigDecimal(i * 100_000), "deposit-" + i));
        }

        System.out.println("Total events stored: " + store.eventCount(accId));
        System.out.println("Snapshots: " + ss.findLatest(accId).map(s ->
            "version=" + s.version() + " balance=" + s.balance()).orElse("none"));

        // Load via snapshot (much faster)
        AccountSnapshot snap = ss.findLatest(accId).orElseThrow();
        List<DomainEvent> recentEvents = store.loadEventsSince(accId, snap.version());
        System.out.println("\nLoad with snapshot:");
        System.out.printf("  Snapshot at version: %d (skip %d events)%n",
            snap.version(), snap.version());
        System.out.printf("  Events replayed after snapshot: %d%n", recentEvents.size());

        BankAccount restored = BankAccount.fromSnapshot(snap, recentEvents);
        System.out.println("  Balance: " + restored.balance());
        System.out.println("  Version: " + restored.version());
    }

    static void demoCqrsProjections() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 4: CQRS — Projections & Read Models");
        System.out.println("═══════════════════════════════════════════════════");

        EventStore store     = new EventStore();
        SnapshotStore ss     = new SnapshotStore();
        var summaryProj      = new AccountSummaryProjection();
        var historyProj      = new TransactionHistoryProjection();

        // Wire projections to event store (subscribe)
        store.subscribe(summaryProj::on);
        store.subscribe(historyProj::on);

        BankAccountCommandHandler handler = new BankAccountCommandHandler(store, ss);

        // Execute commands (write side)
        String accA = handler.handle(new OpenAccountCommand("Alice", "VND"));
        String accB = handler.handle(new OpenAccountCommand("Bob",   "VND"));

        handler.handle(new DepositCommand(accA, new BigDecimal("20000000"), "salary March"));
        handler.handle(new DepositCommand(accA, new BigDecimal("5000000"),  "freelance project"));
        handler.handle(new WithdrawCommand(accA, new BigDecimal("8000000"), "rent March"));
        handler.handle(new DepositCommand(accB, new BigDecimal("15000000"), "salary March"));
        handler.handle(new TransferCommand(accA, accB, new BigDecimal("3000000")));
        handler.handle(new WithdrawCommand(accA, new BigDecimal("1000000"), "utilities"));

        // Query side: AccountSummaryView
        System.out.println("[Read Model 1: Account Summary]");
        summaryProj.findAll().forEach(v ->
            System.out.printf("  %-10s %-8s balance=%-12s deposits=%-12s withdrawals=%s transactions=%d%n",
                v.accountId(), v.owner(), v.balance(),
                v.totalDeposited(), v.totalWithdrawn(), v.totalTransactions()));

        // Query side: Transaction History
        System.out.println("\n[Read Model 2: Transaction History for Alice]");
        historyProj.findByAccountLastN(accA, 10).forEach(t ->
            System.out.printf("  %-15s %+12.2f  %-25s%n",
                t.type(), t.amount(), t.description()));

        System.out.println("\n[Event Store global log: " + store.totalEvents() + " total events]");
        store.globalLog().forEach(e ->
            System.out.printf("  %-12s [%s] %s%n",
                e.getClass().getSimpleName(),
                e.aggregateId(),
                e.occurredAt().toString().substring(11, 19)));
    }

    static void demoTimeTravelDebug() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 5: Time-Travel Debugging");
        System.out.println("═══════════════════════════════════════════════════");

        EventStore store = new EventStore();
        SnapshotStore ss = new SnapshotStore();
        BankAccountCommandHandler handler = new BankAccountCommandHandler(store, ss);

        String accId = handler.handle(new OpenAccountCommand("Diana", "VND"));
        handler.handle(new DepositCommand(accId,  new BigDecimal("10000000"), "initial"));
        handler.handle(new WithdrawCommand(accId,  new BigDecimal("2000000"),  "expense A"));
        handler.handle(new DepositCommand(accId,  new BigDecimal("5000000"),  "bonus"));
        handler.handle(new WithdrawCommand(accId,  new BigDecimal("7000000"),  "expense B"));

        List<DomainEvent> allEvents = store.loadEvents(accId);
        System.out.println("Full event history:");

        // Replay incrementally = time-travel
        for (DomainEvent e : allEvents) {
            BankAccount state = BankAccount.reconstitute(allEvents.subList(0, e.sequenceNumber() + 1));
            System.out.printf("  After [v%d] %-20s → balance=%s%n",
                e.sequenceNumber(),
                e.getClass().getSimpleName(),
                state.balance().toPlainString());
        }

        System.out.println("\n'Why did account drop to 6M after v3?'");
        System.out.println("  → Replay to v3: see exactly what happened");
        System.out.println("  → No need for 'who changed this?' investigation");
        System.out.println("  → Events are the truth — no data loss");
    }

    static void demoEventSourcingVsTraditional() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 6: Event Sourcing vs Traditional");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            TRADITIONAL (State-based):
            ───────────────────────────
              Table: bank_accounts(id, owner, balance, status, updated_at)
              UPDATE bank_accounts SET balance = balance - 5000 WHERE id = ?

              Problems:
              ❌ "Why is balance 47,000? Where did money go?" → No answer
              ❌ Audit log = afterthought (separate table, often incomplete)
              ❌ Bug in prod? "What was balance last Tuesday?" → Unknown
              ❌ Regulatory compliance needs full history → painful retrofit

            EVENT SOURCING:
            ───────────────────────────
              Table: event_store(id, aggregate_id, type, payload, occurred_at)
              INSERT INTO event_store (MoneyWithdrawn, amount=5000, desc='ATM')

              Benefits:
              ✅ Complete audit trail: who, what, when, why — free
              ✅ Time-travel: replay to any point in time
              ✅ Bug replay: reproduce exact sequence of events
              ✅ Multiple projections from same events (new read model? replay from start)
              ✅ Event-driven: other services subscribe to events naturally

              Costs:
              ⚠ Complexity: replay, projections, eventual consistency
              ⚠ Event schema evolution: old events must remain readable
              ⚠ Query: cannot "SELECT WHERE balance > 1M" on event store directly
              ⚠ Learning curve for team

            EVENT SCHEMA EVOLUTION:
            ───────────────────────────
              v1: MoneyDeposited(amount)
              v2: MoneyDeposited(amount, currency)  ← added field
              Solution: Upcaster (transform old events to new schema on read)

              class MoneyDepositedUpcaster {
                  MoneyDepositedV2 upcast(MoneyDepositedV1 old) {
                      return new MoneyDepositedV2(old.amount(), "VND"); // default
                  }
              }
            """);
    }

    // ═══════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  BÀI 9.2 — EVENT SOURCING + CQRS                 ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        demoEventStore();
        demoAggregateReplay();
        demoSnapshot();
        demoCqrsProjections();
        demoTimeTravelDebug();
        demoEventSourcingVsTraditional();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TỔNG KẾT BÀI 9.2                                ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║                                                   ║");
        System.out.println("║  EVENT STORE = append-only, never update/delete  ║");
        System.out.println("║  Optimistic concurrency = expectedVersion check  ║");
        System.out.println("║                                                   ║");
        System.out.println("║  AGGREGATE: apply(event) = state only, no logic  ║");
        System.out.println("║  Command method = business logic + emit events   ║");
        System.out.println("║                                                   ║");
        System.out.println("║  SNAPSHOT = skip N events, replay remainder only ║");
        System.out.println("║  Take snapshot every 100-500 events in prod      ║");
        System.out.println("║                                                   ║");
        System.out.println("║  CQRS: Write = Event Store (normalized events)   ║");
        System.out.println("║         Read = Projections (denormalized views)  ║");
        System.out.println("║  Multiple read models from same events           ║");
        System.out.println("║                                                   ║");
        System.out.println("║  TIME-TRAVEL: replay to any version → debug prod ║");
        System.out.println("║  AUDIT: events ARE the log — compliance free     ║");
        System.out.println("║                                                   ║");
        System.out.println("║  USE WHEN: audit required, event-driven arch,    ║");
        System.out.println("║  complex workflow. AVOID: simple CRUD.           ║");
        System.out.println("║                                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }
}
