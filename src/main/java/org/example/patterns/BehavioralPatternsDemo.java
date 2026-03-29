package org.example.patterns;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * ============================================================
 * BÀI 4.3 — Behavioral Patterns thực chiến
 * ============================================================
 *
 * MỤC TIÊU:
 *   1. Observer — event-driven, decoupled pub/sub, EventBus
 *   2. Strategy — swap algorithm tại runtime, loại bỏ if-else chain
 *   3. Command — encapsulate action, undo/redo, audit log, queue
 *   4. Chain of Responsibility — middleware pipeline, filter chain
 *   5. Template Method + State — skeleton algorithm, FSM
 *
 * CHẠY: mvn compile exec:java -Dexec.mainClass="org.example.patterns.BehavioralPatternsDemo"
 * ============================================================
 */
public class BehavioralPatternsDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BÀI 4.3: Behavioral Patterns ===\n");

        demo1_Observer();
        demo2_Strategy();
        demo3_Command();
        demo4_ChainOfResponsibility();
        demo5_TemplateMethodAndState();

        System.out.println("\n=== KẾT THÚC BÀI 4.3 ===");
    }

    // ================================================================
    // DEMO 1: Observer — Event-Driven, Decoupled Pub/Sub
    // ================================================================

    /**
     * Observer: khi Subject thay đổi state, tất cả Observer được notify tự động.
     * Subject không biết Observer là ai — loosely coupled.
     *
     * 3 BIẾN THỂ THỰC TẾ:
     *   Classic OOP:  Subject.addObserver / notifyAll() → tightly coupled, synchronous
     *   EventBus:     publish(event) / @Subscribe → loosely coupled, type-safe
     *   Reactive:     Observable/Flux → backpressure, async, composable operators
     *
     * THỰC TẾ TRONG JAVA/SPRING:
     *   ApplicationEventPublisher + @EventListener  — Spring Event
     *   @EntityListeners + @PostPersist             — JPA Entity Lifecycle
     *   PropertyChangeListener                      — Java Beans
     *   RxJava / Project Reactor                    — reactive observer
     *
     * PITFALLS:
     *   Memory leak: Subject giữ strong reference → Observer không được GC
     *   Fix: WeakReference hoặc unsubscribe rõ ràng
     *
     *   Exception trong observer: 1 observer crash → block các observer sau
     *   Fix: wrap từng observer call trong try-catch
     *
     *   Ordering: không đảm bảo thứ tự notify → đừng dựa vào thứ tự
     *
     * SA INSIGHT: Observer là nền tảng của Event-Driven Architecture.
     *   Domain Event trong DDD = Observer pattern ở quy mô distributed.
     *   Kafka topic = Subject, consumer group = Observer.
     *   Quan trọng: Domain Event nên IMMUTABLE và carry đủ context.
     */
    static void demo1_Observer() {
        System.out.println("--- DEMO 1: Observer ---");

        // Classic Observer — OOP style
        System.out.println("  Classic Observer (OOP):");
        OrderService orderService = new OrderService();
        orderService.addObserver(new EmailNotifier());
        orderService.addObserver(new InventoryUpdater());
        orderService.addObserver(new AuditLogger());
        orderService.placeOrder(new Order("ORD-001", "Alice", 299.99));

        // EventBus — type-safe, decoupled
        System.out.println("\n  EventBus (type-safe, decoupled):");
        EventBus bus = new EventBus();
        bus.subscribe(UserRegisteredEvent.class, e ->
            System.out.println("  [EmailService] Welcome email → " + e.email()));
        bus.subscribe(UserRegisteredEvent.class, e ->
            System.out.println("  [Analytics] Track new signup: " + e.userId()));
        bus.subscribe(UserRegisteredEvent.class, e ->
            System.out.println("  [Referral] Check referral code: " + e.referralCode()));
        bus.subscribe(PaymentProcessedEvent.class, e ->
            System.out.println("  [Invoice] Generate invoice for $" + e.amount()));

        bus.publish(new UserRegisteredEvent("usr-42", "alice@example.com", "REF-XYZ"));
        bus.publish(new PaymentProcessedEvent("ORD-001", 299.99, "card-123"));

        // Async EventBus — observer chạy trên background thread
        System.out.println("\n  Async EventBus:");
        AsyncEventBus asyncBus = new AsyncEventBus(Executors.newFixedThreadPool(3));
        asyncBus.subscribe(OrderShippedEvent.class, e -> {
            sleep(50);
            System.out.println("  [SMS] Notify " + e.userId() + " — tracking: " + e.trackingId()
                    + " (thread: " + Thread.currentThread().getName() + ")");
        });
        asyncBus.subscribe(OrderShippedEvent.class, e -> {
            sleep(30);
            System.out.println("  [Push] Notify " + e.userId() + " — order shipped!"
                    + " (thread: " + Thread.currentThread().getName() + ")");
        });
        asyncBus.publish(new OrderShippedEvent("ORD-001", "usr-42", "SHIP-999"));
        sleep(200); // chờ async observers xong
        asyncBus.shutdown();
        System.out.println();
    }

    // Domain Events — immutable, carry context
    record Order(String id, String userId, double total) {}
    record UserRegisteredEvent(String userId, String email, String referralCode) {}
    record PaymentProcessedEvent(String orderId, double amount, String paymentMethod) {}
    record OrderShippedEvent(String orderId, String userId, String trackingId) {}

    interface OrderObserver { void onOrderPlaced(Order order); }

    static class OrderService {
        private final List<OrderObserver> observers = new ArrayList<>();
        void addObserver(OrderObserver o) { observers.add(o); }
        void placeOrder(Order order) {
            System.out.println("  OrderService: processing " + order.id());
            observers.forEach(o -> {
                try { o.onOrderPlaced(order); }
                catch (Exception e) { System.err.println("Observer error: " + e.getMessage()); }
            });
        }
    }

    static class EmailNotifier   implements OrderObserver {
        @Override public void onOrderPlaced(Order o) {
            System.out.println("  [Email]     → " + o.userId() + ": order " + o.id() + " confirmed"); }
    }
    static class InventoryUpdater implements OrderObserver {
        @Override public void onOrderPlaced(Order o) {
            System.out.println("  [Inventory] → deduct stock for " + o.id()); }
    }
    static class AuditLogger implements OrderObserver {
        @Override public void onOrderPlaced(Order o) {
            System.out.println("  [Audit]     → log order " + o.id() + " amount=$" + o.total()); }
    }

    // Type-safe EventBus
    static class EventBus {
        private final Map<Class<?>, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
            handlers.computeIfAbsent(eventType, k -> new ArrayList<>())
                    .add(e -> handler.accept((T) e));
        }

        void publish(Object event) {
            handlers.getOrDefault(event.getClass(), List.of())
                    .forEach(h -> {
                        try { h.accept(event); }
                        catch (Exception e) { System.err.println("[EventBus] handler error: " + e.getMessage()); }
                    });
        }
    }

    static class AsyncEventBus {
        private final EventBus inner = new EventBus();
        private final ExecutorService executor;
        AsyncEventBus(ExecutorService exec) { this.executor = exec; }

        @SuppressWarnings("unchecked")
        <T> void subscribe(Class<T> type, Consumer<T> handler) { inner.subscribe(type, handler); }
        void publish(Object event) { executor.submit(() -> inner.publish(event)); }
        void shutdown() { executor.shutdown(); }
    }

    // ================================================================
    // DEMO 2: Strategy — Swap Algorithm tại Runtime
    // ================================================================

    /**
     * Strategy: đóng gói từng algorithm vào class riêng, swap tại runtime.
     * Loại bỏ if-else / switch chain lớn → Open/Closed Principle.
     *
     * NHẬN BIẾT KHI NÀO CẦN:
     *   if (type == "credit")       calculateFee(...)
     *   else if (type == "paypal")  calculateFee(...)
     *   else if (type == "crypto")  calculateFee(...)
     *   → Mỗi lần thêm payment method phải sửa method này → vi phạm OCP
     *   → Thay bằng Map<String, PricingStrategy>
     *
     * THỰC TẾ TRONG JAVA:
     *   Comparator<T>                 — strategy cho sorting
     *   Runnable, Callable            — strategy cho execution
     *   javax.validation.Validator    — strategy cho validation
     *   RetryPolicy trong Resilience4j — strategy cho retry
     *
     * FUNCTIONAL: Strategy thường là @FunctionalInterface
     *   Truyền lambda thay vì tạo class riêng → concise, inline
     *
     * SA INSIGHT: Strategy + Registry (Map) = Plugin architecture.
     *   Thêm strategy mới = thêm 1 class + register → không sửa code cũ.
     *   Đây là cách Spring MVC HandlerMapping hoạt động.
     */
    static void demo2_Strategy() {
        System.out.println("--- DEMO 2: Strategy ---");

        // Shipping cost strategy
        System.out.println("  Shipping Strategy:");
        ShippingCalculator calc = new ShippingCalculator();
        calc.register("standard",  new StandardShipping());
        calc.register("express",   new ExpressShipping());
        calc.register("overnight", pkg -> pkg.weight() * 15.0 + 25.0); // lambda strategy
        calc.register("free",      pkg -> 0.0);

        Package pkg = new Package("PKG-001", 2.5, "NY", "LA");
        for (String method : List.of("standard", "express", "overnight", "free")) {
            System.out.printf("  %-10s → $%.2f%n", method, calc.calculate(method, pkg));
        }

        // Sorting strategy
        System.out.println("\n  Sort Strategy (Comparator as Strategy):");
        List<Employee2> employees = new ArrayList<>(List.of(
            new Employee2("Charlie", "Engineering", 85000),
            new Employee2("Alice",   "Marketing",   70000),
            new Employee2("Bob",     "Engineering", 95000),
            new Employee2("Dave",    "HR",          65000)
        ));

        Map<String, Comparator<Employee2>> sortStrategies = Map.of(
            "by-name",   Comparator.comparing(Employee2::name),
            "by-salary", Comparator.comparingInt(Employee2::salary).reversed(),
            "by-dept",   Comparator.comparing(Employee2::dept).thenComparing(Employee2::name)
        );

        sortStrategies.forEach((name, comparator) -> {
            List<Employee2> sorted = employees.stream().sorted(comparator).toList();
            System.out.println("  " + name + ": " + sorted.stream()
                .map(e -> e.name() + "(" + e.salary() + ")").toList());
        });

        // Validation strategy — chain of strategies
        System.out.println("\n  Validation Strategy:");
        Validator<String> passwordValidator = Validator
            .<String>of(s -> s.length() >= 8,         "Minimum 8 characters")
            .and(s -> s.matches(".*[A-Z].*"),          "Must contain uppercase")
            .and(s -> s.matches(".*[0-9].*"),          "Must contain digit")
            .and(s -> s.matches(".*[!@#$%].*"),        "Must contain special char");

        for (String pwd : List.of("weak", "StrongPass1!", "NoSpecial1", "short1!")) {
            ValidationResult result = passwordValidator.validate(pwd);
            System.out.printf("  %-15s → %s%n", pwd,
                result.valid() ? "✓ valid" : "✗ " + result.errors());
        }
        System.out.println();
    }

    record Package(String id, double weight, String from, String to) {}
    record Employee2(String name, String dept, int salary) {}

    interface ShippingStrategy { double calculate(Package pkg); }
    static class StandardShipping implements ShippingStrategy {
        @Override public double calculate(Package pkg) { return pkg.weight() * 3.5 + 5.0; }
    }
    static class ExpressShipping implements ShippingStrategy {
        @Override public double calculate(Package pkg) { return pkg.weight() * 7.0 + 10.0; }
    }

    static class ShippingCalculator {
        private final Map<String, ShippingStrategy> strategies = new LinkedHashMap<>();
        void register(String name, ShippingStrategy s) { strategies.put(name, s); }
        double calculate(String method, Package pkg) {
            return Optional.ofNullable(strategies.get(method))
                .map(s -> s.calculate(pkg))
                .orElseThrow(() -> new IllegalArgumentException("Unknown method: " + method));
        }
    }

    @FunctionalInterface
    interface Validator<T> {
        ValidationResult validate(T value);

        static <T> Validator<T> of(Predicate<T> rule, String errorMsg) {
            return value -> rule.test(value)
                ? ValidationResult.ok()
                : ValidationResult.fail(errorMsg);
        }

        default Validator<T> and(Predicate<T> rule, String errorMsg) {
            return value -> {
                ValidationResult first = this.validate(value);
                ValidationResult second = rule.test(value)
                    ? ValidationResult.ok() : ValidationResult.fail(errorMsg);
                return first.merge(second);
            };
        }
    }

    record ValidationResult(boolean valid, List<String> errors) {
        static ValidationResult ok()         { return new ValidationResult(true, List.of()); }
        static ValidationResult fail(String e){ return new ValidationResult(false, List.of(e)); }
        ValidationResult merge(ValidationResult other) {
            List<String> all = new ArrayList<>(errors);
            all.addAll(other.errors);
            return new ValidationResult(valid && other.valid, all);
        }
    }

    // ================================================================
    // DEMO 3: Command — Encapsulate Action, Undo/Redo, Audit
    // ================================================================

    /**
     * Command: đóng gói request thành object.
     *   → Parameterize, queue, log, undo/redo operations.
     *
     * THÀNH PHẦN:
     *   Command interface: execute() [+ undo()]
     *   ConcreteCommand:   thực hiện action cụ thể trên Receiver
     *   Invoker:           giữ và execute commands (không biết command làm gì)
     *   Receiver:          object thực sự thực hiện work
     *
     * ỨNG DỤNG THỰC TẾ:
     *   Text editor: Ctrl+Z / Ctrl+Y — undo/redo command stack
     *   Database: Transaction = Command, rollback = undo()
     *   Message Queue: task = Command, worker = Invoker
     *   HTTP: Request object = Command (URL, method, body)
     *   CQRS: Command side của Command Query Responsibility Segregation
     *
     * SA INSIGHT: Command + Queue + Retry = reliable task processing.
     *   Khi command fail → đưa lại vào queue, retry sau.
     *   Khi cần audit trail: log mọi command + timestamp + user + result.
     *   Event Sourcing: state = replay tất cả commands từ đầu.
     */
    static void demo3_Command() throws Exception {
        System.out.println("--- DEMO 3: Command ---");

        // Text Editor với Undo/Redo
        System.out.println("  Text Editor — Undo/Redo:");
        TextEditor editor = new TextEditor();
        CommandHistory history = new CommandHistory();

        history.execute(new InsertCommand(editor, 0, "Hello"));
        System.out.println("  After insert 'Hello':    \"" + editor.getText() + "\"");

        history.execute(new InsertCommand(editor, 5, " World"));
        System.out.println("  After insert ' World':   \"" + editor.getText() + "\"");

        history.execute(new DeleteCommand(editor, 5, 6));
        System.out.println("  After delete ' World':   \"" + editor.getText() + "\"");

        history.execute(new ReplaceCommand(editor, 0, 5, "Hi"));
        System.out.println("  After replace 'Hello'→'Hi': \"" + editor.getText() + "\"");

        history.undo();
        System.out.println("  After undo:              \"" + editor.getText() + "\"");
        history.undo();
        System.out.println("  After undo:              \"" + editor.getText() + "\"");
        history.redo();
        System.out.println("  After redo:              \"" + editor.getText() + "\"");

        // Command Queue — async processing với audit trail
        System.out.println("\n  Command Queue (async + audit trail):");
        CommandQueue queue = new CommandQueue(2);
        queue.enqueue(new TransferCommand("ACC-001", "ACC-002", 500.0, "user-alice"));
        queue.enqueue(new TransferCommand("ACC-003", "ACC-001", 200.0, "user-bob"));
        queue.enqueue(new TransferCommand("ACC-002", "ACC-004", 1000.0, "user-alice"));
        queue.processAll();
        queue.printAuditLog();
        System.out.println();
    }

    // Text Editor command pattern
    static class TextEditor {
        private StringBuilder text = new StringBuilder();
        void insert(int pos, String s)       { text.insert(pos, s); }
        void delete(int pos, int len)        { text.delete(pos, pos + len); }
        String getText()                     { return text.toString(); }
    }

    interface Command { void execute(); void undo(); }

    static class InsertCommand implements Command {
        private final TextEditor editor; private final int pos; private final String text;
        InsertCommand(TextEditor e, int pos, String text) { this.editor = e; this.pos = pos; this.text = text; }
        @Override public void execute() { editor.insert(pos, text); }
        @Override public void undo()    { editor.delete(pos, text.length()); }
    }

    static class DeleteCommand implements Command {
        private final TextEditor editor; private final int pos, len; private String deleted;
        DeleteCommand(TextEditor e, int pos, int len) { this.editor = e; this.pos = pos; this.len = len; }
        @Override public void execute() { deleted = editor.getText().substring(pos, pos + len); editor.delete(pos, len); }
        @Override public void undo()    { editor.insert(pos, deleted); }
    }

    static class ReplaceCommand implements Command {
        private final TextEditor editor; private final int pos, len; private final String newText; private String oldText;
        ReplaceCommand(TextEditor e, int pos, int len, String newText) {
            this.editor = e; this.pos = pos; this.len = len; this.newText = newText;
        }
        @Override public void execute() {
            oldText = editor.getText().substring(pos, pos + len);
            editor.delete(pos, len); editor.insert(pos, newText);
        }
        @Override public void undo() { editor.delete(pos, newText.length()); editor.insert(pos, oldText); }
    }

    static class CommandHistory {
        private final Deque<Command> undoStack = new ArrayDeque<>();
        private final Deque<Command> redoStack = new ArrayDeque<>();
        void execute(Command cmd) { cmd.execute(); undoStack.push(cmd); redoStack.clear(); }
        void undo() { if (!undoStack.isEmpty()) { Command c = undoStack.pop(); c.undo(); redoStack.push(c); } }
        void redo() { if (!redoStack.isEmpty()) { Command c = redoStack.pop(); c.execute(); undoStack.push(c); } }
    }

    // Command Queue with audit
    record AuditEntry(String command, String user, long timestamp, boolean success, String error) {}

    static class TransferCommand {
        final String from, to, user; final double amount;
        TransferCommand(String from, String to, double amount, String user) {
            this.from = from; this.to = to; this.amount = amount; this.user = user;
        }
        boolean execute() {
            sleep(20); // simulate DB write
            if (amount > 900) throw new IllegalArgumentException("Amount exceeds daily limit");
            System.out.println("  [Transfer] " + from + " → " + to + " $" + amount + " by " + user);
            return true;
        }
        @Override public String toString() { return "Transfer(" + from + "→" + to + " $" + amount + ")"; }
    }

    static class CommandQueue {
        private final BlockingQueue<TransferCommand> queue = new LinkedBlockingQueue<>();
        private final List<AuditEntry> auditLog = new ArrayList<>();
        private final int workers;
        CommandQueue(int workers) { this.workers = workers; }
        void enqueue(TransferCommand cmd) { queue.offer(cmd); }

        void processAll() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            List<Future<?>> futures = new ArrayList<>();
            while (!queue.isEmpty()) {
                TransferCommand cmd = queue.poll();
                if (cmd == null) break;
                futures.add(pool.submit(() -> {
                    long ts = System.currentTimeMillis();
                    try {
                        cmd.execute();
                        synchronized (auditLog) { auditLog.add(new AuditEntry(cmd.toString(), cmd.user, ts, true, null)); }
                    } catch (Exception e) {
                        synchronized (auditLog) { auditLog.add(new AuditEntry(cmd.toString(), cmd.user, ts, false, e.getMessage())); }
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
            pool.shutdown();
        }

        void printAuditLog() {
            System.out.println("  Audit log:");
            auditLog.forEach(e -> System.out.printf("  [AUDIT] %-40s user=%-12s %s%s%n",
                e.command(), e.user(), e.success() ? "✓ OK" : "✗ FAIL",
                e.error() != null ? " — " + e.error() : ""));
        }
    }

    // ================================================================
    // DEMO 4: Chain of Responsibility — Middleware Pipeline
    // ================================================================

    /**
     * Chain of Responsibility: pass request qua chuỗi handlers.
     *   Mỗi handler quyết định: xử lý và dừng, hoặc pass tiếp cho handler sau.
     *
     * THỰC TẾ TRONG JAVA/SPRING:
     *   javax.servlet.Filter chain        — HTTP request pipeline
     *   Spring Security filter chain      — authentication/authorization
     *   Spring Interceptor                — pre/post handle
     *   Netty ChannelPipeline             — network packet processing
     *   java.util.logging.Handler         — log record routing
     *
     * FUNCTIONAL CHAIN: dùng Function.andThen() hoặc list của handler functions
     *   Gọn hơn OOP chain, dễ compose động
     *
     * SA INSIGHT: Middleware pattern trong microservices = Chain of Responsibility.
     *   Request đi qua: Auth → RateLimit → Logging → Validation → BusinessLogic
     *   Mỗi middleware độc lập, có thể bật/tắt, reorder không sửa core logic.
     *   gRPC interceptor, Kafka consumer interceptor đều dùng pattern này.
     */
    static void demo4_ChainOfResponsibility() {
        System.out.println("--- DEMO 4: Chain of Responsibility ---");

        // HTTP Request Pipeline — middleware chain
        System.out.println("  HTTP Middleware Pipeline:");
        Handler pipeline = Handler.chain(
            new AuthenticationHandler(),
            new RateLimitHandler(3),
            new LoggingHandler(),
            new ValidationHandler(),
            new BusinessHandler()
        );

        // Valid authenticated request
        HttpRequest req1 = new HttpRequest("GET", "/api/users", "Bearer valid-token", Map.of());
        pipeline.handle(req1);

        // No token
        System.out.println();
        HttpRequest req2 = new HttpRequest("POST", "/api/orders", null, Map.of("item", "book"));
        pipeline.handle(req2);

        // Rate limit test
        System.out.println();
        for (int i = 1; i <= 4; i++) {
            HttpRequest r = new HttpRequest("GET", "/api/products", "Bearer token", Map.of());
            System.out.print("  Request " + i + ": ");
            pipeline.handle(r);
        }

        // Functional chain — dùng UnaryOperator
        System.out.println("\n  Functional Chain (price calculation pipeline):");
        List<UnaryOperator<Double>> priceRules = List.of(
            price -> price * 1.10,               // +10% platform fee
            price -> price > 100 ? price * 0.95 : price, // -5% for orders > $100
            price -> Math.round(price * 100.0) / 100.0    // round to 2 decimals
        );

        UnaryOperator<Double> pricePipeline = priceRules.stream()
            .reduce(UnaryOperator.identity(), (f, g) -> v -> g.apply(f.apply(v)));

        for (double base : List.of(50.0, 120.0, 200.0)) {
            System.out.printf("  $%.2f → $%.2f (after pipeline)%n", base, pricePipeline.apply(base));
        }
        System.out.println();
    }

    record HttpRequest(String method, String path, String authToken, Map<String, String> body) {}
    record HttpResponse(int status, String body) {
        @Override public String toString() { return "HTTP " + status + ": " + body; }
    }

    interface Handler {
        HttpResponse handle(HttpRequest request);

        @SuppressWarnings("unchecked")
        static Handler chain(Handler... handlers) {
            return request -> {
                for (Handler h : handlers) {
                    HttpResponse resp = h.handle(request);
                    if (resp != null) return resp; // Short-circuit nếu handler trả về response
                }
                return new HttpResponse(200, "OK");
            };
        }
    }

    static class AuthenticationHandler implements Handler {
        @Override public HttpResponse handle(HttpRequest req) {
            if (req.authToken() == null || !req.authToken().startsWith("Bearer ")) {
                System.out.println("  [Auth] BLOCKED — no token");
                return new HttpResponse(401, "Unauthorized");
            }
            System.out.println("  [Auth] OK");
            return null; // continue chain
        }
    }

    static class RateLimitHandler implements Handler {
        private final int maxPerWindow;
        private final Map<String, Integer> counts = new ConcurrentHashMap<>();
        RateLimitHandler(int max) { this.maxPerWindow = max; }
        @Override public HttpResponse handle(HttpRequest req) {
            String key = req.path();
            int count = counts.merge(key, 1, Integer::sum);
            if (count > maxPerWindow) {
                System.out.println("  [RateLimit] BLOCKED — " + count + "/" + maxPerWindow);
                return new HttpResponse(429, "Too Many Requests");
            }
            System.out.println("  [RateLimit] OK (" + count + "/" + maxPerWindow + ")");
            return null;
        }
    }

    static class LoggingHandler implements Handler {
        @Override public HttpResponse handle(HttpRequest req) {
            System.out.println("  [Log] " + req.method() + " " + req.path());
            return null;
        }
    }

    static class ValidationHandler implements Handler {
        @Override public HttpResponse handle(HttpRequest req) {
            if ("POST".equals(req.method()) && req.body().isEmpty()) {
                System.out.println("  [Validation] BLOCKED — empty body");
                return new HttpResponse(400, "Bad Request: body required for POST");
            }
            System.out.println("  [Validation] OK");
            return null;
        }
    }

    static class BusinessHandler implements Handler {
        @Override public HttpResponse handle(HttpRequest req) {
            System.out.println("  [Business] Processing " + req.path() + " → OK");
            return new HttpResponse(200, "Success");
        }
    }

    // ================================================================
    // DEMO 5: Template Method + State Machine
    // ================================================================

    /**
     * TEMPLATE METHOD: Định nghĩa skeleton của algorithm trong base class.
     *   Subclass override các bước cụ thể mà không thay đổi cấu trúc tổng thể.
     *   Inversion of Control: base class gọi subclass (không phải ngược lại).
     *
     * THỰC TẾ: Spring JdbcTemplate, AbstractList, HttpServlet.service()
     *   → doGet(), doPost() là template method hooks.
     *
     * STATE MACHINE (FSM): Object thay đổi behaviour khi state thay đổi.
     *   Không dùng if/switch → mỗi state là 1 class riêng.
     *   Transition rõ ràng, dễ thêm state mới, không ảnh hưởng state khác.
     *
     * THỰC TẾ: Order lifecycle (Pending→Paid→Shipped→Delivered→Cancelled),
     *   Connection state (Connecting→Connected→Disconnecting→Disconnected),
     *   Traffic light, vending machine, document approval workflow.
     *
     * SA INSIGHT: State Machine + Event = Event-Driven FSM.
     *   Spring State Machine, XState (JS) implement pattern này.
     *   Saga pattern trong microservices = distributed State Machine.
     */
    static void demo5_TemplateMethodAndState() {
        System.out.println("--- DEMO 5: Template Method + State Machine ---");

        // Template Method — data export pipeline
        System.out.println("  Template Method — Data Export Pipeline:");
        new CsvExporter().export(List.of(
            Map.of("name", "Alice", "dept", "Eng", "salary", "95000"),
            Map.of("name", "Bob",   "dept", "Mkt", "salary", "70000")
        ));
        System.out.println();
        new JsonExporter().export(List.of(
            Map.of("name", "Alice", "dept", "Eng", "salary", "95000")
        ));

        // Order State Machine
        System.out.println("\n  Order State Machine:");
        OrderStateMachine order = new OrderStateMachine("ORD-007");
        System.out.println("  Initial: " + order.getState());

        order.pay();
        order.ship();
        order.deliver();

        System.out.println("\n  Cancel từ trạng thái Paid:");
        OrderStateMachine order2 = new OrderStateMachine("ORD-008");
        order2.pay();
        order2.cancel();

        System.out.println("\n  Transition không hợp lệ:");
        OrderStateMachine order3 = new OrderStateMachine("ORD-009");
        order3.ship(); // Chưa pay — bị chặn

        System.out.println();
        System.out.println("=== TỔNG KẾT BÀI 4.3 ===");
        System.out.println("  ✓ Observer: EventBus type-safe, async observers. Domain Event = immutable + context");
        System.out.println("  ✓ Strategy: Map<String, Strategy> = plugin registry. Loại bỏ if-else chain");
        System.out.println("  ✓ Command: undo/redo stack, audit trail, queue + retry. CQRS command side");
        System.out.println("  ✓ Chain: middleware pipeline, short-circuit. Functional chain = UnaryOperator.andThen");
        System.out.println("  ✓ Template Method: skeleton + hooks. State: FSM thay switch → extensible");
        System.out.println("  → Bài tiếp: 4.4 ConcurrentPatternsDemo — Producer-Consumer, Reactor");
    }

    // Template Method — export pipeline
    abstract static class DataExporter {
        // TEMPLATE METHOD — skeleton không thay đổi
        final void export(List<Map<String, String>> data) {
            String header = buildHeader(data.get(0).keySet());
            System.out.println("  " + header);
            data.forEach(row -> System.out.println("  " + buildRow(row)));
            System.out.println("  " + buildFooter(data.size()));
        }
        // HOOKS — subclass override
        abstract String buildHeader(Set<String> columns);
        abstract String buildRow(Map<String, String> row);
        String buildFooter(int count) { return "-- " + count + " row(s) exported --"; }
    }

    static class CsvExporter extends DataExporter {
        @Override String buildHeader(Set<String> cols) { return String.join(",", cols); }
        @Override String buildRow(Map<String, String> row) { return String.join(",", row.values()); }
    }

    static class JsonExporter extends DataExporter {
        @Override String buildHeader(Set<String> cols) { return "["; }
        @Override String buildRow(Map<String, String> row) {
            StringBuilder sb = new StringBuilder("  {");
            row.forEach((k, v) -> sb.append("\"").append(k).append("\":\"").append(v).append("\","));
            sb.deleteCharAt(sb.length() - 1);
            return sb.append("}").toString();
        }
        @Override String buildFooter(int count) { return "]  // " + count + " items"; }
    }

    // State Machine — Order lifecycle
    enum OrderStatus { PENDING, PAID, SHIPPED, DELIVERED, CANCELLED }

    static class OrderStateMachine {
        private OrderStatus state = OrderStatus.PENDING;
        private final String orderId;

        OrderStateMachine(String id) { this.orderId = id; }

        String getState() { return state.name(); }

        void pay() {
            if (state != OrderStatus.PENDING) {
                System.out.println("  [" + orderId + "] Cannot pay from state: " + state); return;
            }
            state = OrderStatus.PAID;
            System.out.println("  [" + orderId + "] PENDING → PAID ✓");
        }

        void ship() {
            if (state != OrderStatus.PAID) {
                System.out.println("  [" + orderId + "] Cannot ship from state: " + state
                    + " (must be PAID first)"); return;
            }
            state = OrderStatus.SHIPPED;
            System.out.println("  [" + orderId + "] PAID → SHIPPED ✓");
        }

        void deliver() {
            if (state != OrderStatus.SHIPPED) {
                System.out.println("  [" + orderId + "] Cannot deliver from state: " + state); return;
            }
            state = OrderStatus.DELIVERED;
            System.out.println("  [" + orderId + "] SHIPPED → DELIVERED ✓");
        }

        void cancel() {
            if (state == OrderStatus.SHIPPED || state == OrderStatus.DELIVERED) {
                System.out.println("  [" + orderId + "] Cannot cancel — already " + state); return;
            }
            System.out.println("  [" + orderId + "] " + state + " → CANCELLED ✓");
            state = OrderStatus.CANCELLED;
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
