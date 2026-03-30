package org.example.testing;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * ============================================================
 * BÀI 8.3 — MUTATION TESTING: ĐO CHẤT LƯỢNG TEST THẬT SỰ
 * ============================================================
 *
 * VẤN ĐỀ VỚI CODE COVERAGE THÔNG THƯỜNG:
 *   Coverage 100% ≠ test tốt!
 *
 *   void transfer(Account from, Account to, int amount) {
 *       from.balance -= amount;
 *       to.balance += amount;   // ← dòng này cover = true
 *   }
 *   @Test void test() {
 *       transfer(a, b, 100);
 *       // assertThat(b.balance).isEqualTo(100); ← QUÊN ASSERT!
 *   }
 *   → Coverage: 100% | Test quality: 0%
 *
 * MUTATION TESTING GIẢI QUYẾT:
 *   PIT tự động inject lỗi (mutation) vào bytecode:
 *     from.balance -= amount  →  from.balance += amount  (negate)
 *     to.balance += amount    →  to.balance -= amount    (negate)
 *     if (x > 0)              →  if (x >= 0)             (boundary)
 *   Nếu test pass với code sai → test yếu → "mutant survived"
 *   Nếu test fail với code sai → test tốt → "mutant killed"
 *
 * MUTATION SCORE = killed / (killed + survived) × 100%
 *   > 80%: tốt | > 60%: chấp nhận được | < 60%: test yếu
 *
 * ============================================================
 * MUTATION OPERATORS PHỔ BIẾN (PIT)
 * ============================================================
 *
 * | Operator          | Original       | Mutant            |
 * |-------------------|----------------|-------------------|
 * | CONDITIONALS_BOUN | x > 0          | x >= 0            |
 * | NEGATE_CONDITIONA | if (a)         | if (!a)           |
 * | MATH              | x + y          | x - y             |
 * | INCREMENT         | i++            | i--               |
 * | INVERT_NEGS       | -x             | x                 |
 * | RETURN_VALS       | return true    | return false      |
 * | VOID_METHOD_CALLS | list.clear()   | (removed)         |
 * | CONSTRUCTOR_CALLS | new Foo()      | null              |
 * | NON_VOID_METHOD   | x.get()        | null / 0 / false  |
 *
 * ============================================================
 * PIT MAVEN SETUP
 * ============================================================
 *
 * pom.xml:
 *   <plugin>
 *     <groupId>org.pitest</groupId>
 *     <artifactId>pitest-maven</artifactId>
 *     <version>1.15.3</version>
 *     <dependencies>
 *       <!-- JUnit 5 support -->
 *       <dependency>
 *         <groupId>org.pitest</groupId>
 *         <artifactId>pitest-junit5-plugin</artifactId>
 *         <version>1.2.1</version>
 *       </dependency>
 *     </dependencies>
 *     <configuration>
 *       <targetClasses>
 *         <param>org.example.service.*</param>
 *       </targetClasses>
 *       <targetTests>
 *         <param>org.example.service.*Test</param>
 *       </targetTests>
 *       <mutators>DEFAULTS</mutators>  <!-- hoặc ALL, STRONGER -->
 *       <outputFormats>HTML,XML</outputFormats>
 *       <timestampedReports>false</timestampedReports>
 *     </configuration>
 *   </plugin>
 *
 * Run: mvn test-compile org.pitest:pitest-maven:mutationCoverage
 * Report: target/pit-reports/index.html
 *
 * ============================================================
 */
public class MutationTestingDemo {

    // ═══════════════════════════════════════════════════════
    // SECTION 1: PRODUCTION CODE — CÁC CLASS ĐỂ TEST
    // ═══════════════════════════════════════════════════════

    /**
     * Discount calculator — nhiều boundary conditions.
     * Đây là loại code dễ có surviving mutants nếu test không kỹ.
     */
    static class DiscountCalculator {
        /**
         * Business rules:
         *   amount < 0    → throw IllegalArgumentException
         *   amount < 100  → 0% discount
         *   100 <= amount < 500  → 10% discount
         *   500 <= amount < 1000 → 20% discount
         *   amount >= 1000       → 30% discount
         *   VIP customer         → extra 5%
         */
        public double calculate(double amount, boolean isVip) {
            if (amount < 0) {
                throw new IllegalArgumentException("Amount must be non-negative: " + amount);
            }

            double discountRate;
            if (amount < 100) {
                discountRate = 0.0;
            } else if (amount < 500) {
                discountRate = 0.10;
            } else if (amount < 1000) {
                discountRate = 0.20;
            } else {
                discountRate = 0.30;
            }

            if (isVip) {
                discountRate += 0.05;
            }

            return amount * discountRate;
        }

        public double calculateFinal(double amount, boolean isVip) {
            return amount - calculate(amount, isVip);
        }
    }

    /**
     * Stack implementation — void methods và state mutations.
     * Void method calls là operator khó test nhất.
     */
    static class BoundedStack<T> {
        private final Object[] elements;
        private int size = 0;
        private final int capacity;

        public BoundedStack(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
            this.capacity = capacity;
            this.elements = new Object[capacity];
        }

        public void push(T element) {
            if (size >= capacity) throw new IllegalStateException("Stack is full");
            elements[size++] = element;
        }

        @SuppressWarnings("unchecked")
        public T pop() {
            if (size == 0) throw new NoSuchElementException("Stack is empty");
            T element = (T) elements[--size];
            elements[size] = null; // prevent memory leak (VOID_METHOD_CALLS target)
            return element;
        }

        @SuppressWarnings("unchecked")
        public T peek() {
            if (size == 0) throw new NoSuchElementException("Stack is empty");
            return (T) elements[size - 1];
        }

        public boolean isEmpty() { return size == 0; }
        public boolean isFull()  { return size >= capacity; }
        public int size()        { return size; }

        public void clear() {
            Arrays.fill(elements, null); // VOID_METHOD_CALLS target
            size = 0;
        }
    }

    /**
     * Password validator — nhiều boolean conditions.
     * Mutation NEGATE_CONDITIONALS và CONDITIONALS_BOUNDARY sẽ test kỹ.
     */
    static class PasswordValidator {
        static final int MIN_LENGTH = 8;
        static final int MAX_LENGTH = 64;

        public record ValidationResult(boolean valid, List<String> errors) {
            static ValidationResult ok() { return new ValidationResult(true, List.of()); }
            static ValidationResult fail(List<String> errors) { return new ValidationResult(false, errors); }
        }

        public ValidationResult validate(String password) {
            List<String> errors = new ArrayList<>();

            if (password == null || password.isEmpty()) {
                return ValidationResult.fail(List.of("Password must not be empty"));
            }

            if (password.length() < MIN_LENGTH) {
                errors.add("Password must be at least " + MIN_LENGTH + " characters");
            }
            if (password.length() > MAX_LENGTH) {
                errors.add("Password must be at most " + MAX_LENGTH + " characters");
            }
            if (!password.matches(".*[A-Z].*")) {
                errors.add("Password must contain at least one uppercase letter");
            }
            if (!password.matches(".*[a-z].*")) {
                errors.add("Password must contain at least one lowercase letter");
            }
            if (!password.matches(".*[0-9].*")) {
                errors.add("Password must contain at least one digit");
            }
            if (!password.matches(".*[!@#$%^&*].*")) {
                errors.add("Password must contain at least one special character (!@#$%^&*)");
            }

            return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
        }
    }

    /**
     * Order service với state + void methods.
     * VOID_METHOD_CALLS: nếu audit.log() bị remove, test phải detect được.
     */
    static class OrderService {
        enum OrderStatus { PENDING, CONFIRMED, SHIPPED, CANCELLED }

        record Order(String id, String customer, double amount, OrderStatus status) {
            Order withStatus(OrderStatus newStatus) {
                return new Order(id, customer, amount, newStatus);
            }
        }

        interface AuditLog {
            void log(String event, String orderId);
        }

        private final Map<String, Order> orders = new HashMap<>();
        private final AuditLog auditLog;

        OrderService(AuditLog auditLog) { this.auditLog = auditLog; }

        public Order createOrder(String customer, double amount) {
            if (customer == null || customer.isBlank()) {
                throw new IllegalArgumentException("Customer name required");
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("Amount must be positive");
            }
            String id = "ORD-" + (orders.size() + 1);
            Order order = new Order(id, customer, amount, OrderStatus.PENDING);
            orders.put(id, order);
            auditLog.log("ORDER_CREATED", id);  // VOID_METHOD_CALLS target
            return order;
        }

        public Order confirmOrder(String orderId) {
            Order order = findOrder(orderId);
            if (order.status() != OrderStatus.PENDING) {
                throw new IllegalStateException("Only PENDING orders can be confirmed");
            }
            Order confirmed = order.withStatus(OrderStatus.CONFIRMED);
            orders.put(orderId, confirmed);
            auditLog.log("ORDER_CONFIRMED", orderId);
            return confirmed;
        }

        public Order cancelOrder(String orderId) {
            Order order = findOrder(orderId);
            if (order.status() == OrderStatus.SHIPPED) {
                throw new IllegalStateException("Cannot cancel shipped order");
            }
            Order cancelled = order.withStatus(OrderStatus.CANCELLED);
            orders.put(orderId, cancelled);
            auditLog.log("ORDER_CANCELLED", orderId);
            return cancelled;
        }

        public Optional<Order> findById(String orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        private Order findOrder(String orderId) {
            return orders.computeIfAbsent(orderId, id -> {
                throw new NoSuchElementException("Order not found: " + id);
            });
        }

        public List<Order> findByStatus(OrderStatus status) {
            return orders.values().stream()
                .filter(o -> o.status() == status)
                .sorted(Comparator.comparing(Order::id))
                .collect(Collectors.toList());
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 2: TEST QUALITY SIMULATION
    // ═══════════════════════════════════════════════════════

    /**
     * Simulate mutation testing bằng cách manually inject mutations
     * và kiểm tra xem test suite có detect không.
     *
     * Đây là cách visualize PIT hoạt động:
     *   1. Inject mutation vào code
     *   2. Run tests
     *   3. Nếu test fail → mutant KILLED ✅
     *   4. Nếu test pass → mutant SURVIVED ❌ → test yếu
     */
    static class MutationSimulator {

        record MutationResult(String operator, String original, String mutant, boolean killed, String reason) {
            @Override
            public String toString() {
                String status = killed ? "✅ KILLED" : "❌ SURVIVED";
                return String.format("  [%s] %s: '%s' → '%s'%s",
                    status, operator, original, mutant,
                    killed ? "" : "\n    ⚠ TEST WEAKNESS: " + reason);
            }
        }

        static List<MutationResult> simulateDiscountMutations(DiscountCalculator calc) {
            List<MutationResult> results = new ArrayList<>();

            // ── MUTATION 1: CONDITIONALS_BOUNDARY ─────────────────
            // Original: amount < 100  →  Mutant: amount <= 100
            // Test must cover exact boundary: amount = 100
            {
                double exactBoundary = 100.0;
                double originalResult = calc.calculate(exactBoundary, false);   // should be 10% = 10.0
                // Mutant behavior: amount <= 100 → 100 gets 0% discount → result = 0.0
                boolean testWouldCatch = (originalResult != 0.0); // 10.0 ≠ 0.0 → caught
                results.add(new MutationResult(
                    "CONDITIONALS_BOUNDARY",
                    "amount < 100",
                    "amount <= 100",
                    testWouldCatch,
                    "Need test with amount=100 asserting 10% discount"
                ));
            }

            // ── MUTATION 2: CONDITIONALS_BOUNDARY ─────────────────
            // Original: amount < 500  →  Mutant: amount <= 500
            // Test must cover amount = 500
            {
                double boundary = 500.0;
                double originalResult = calc.calculate(boundary, false);   // 20% = 100.0
                // Mutant: amount <= 500 → 500 gets 10% → result = 50.0
                boolean killed = Math.abs(originalResult - 100.0) < 0.001;
                results.add(new MutationResult(
                    "CONDITIONALS_BOUNDARY",
                    "amount < 500",
                    "amount <= 500",
                    killed,
                    "Need test with amount=500 asserting 20% discount"
                ));
            }

            // ── MUTATION 3: MATH ───────────────────────────────────
            // Original: discountRate += 0.05  →  Mutant: discountRate -= 0.05
            {
                double amount = 200.0;
                double withVip    = calc.calculate(amount, true);   // 10% + 5% = 15% = 30.0
                double withoutVip = calc.calculate(amount, false);  // 10% = 20.0
                double diff = withVip - withoutVip;
                // Original: diff = 10.0 | Mutant: diff = -10.0
                boolean killed = Math.abs(diff - 10.0) < 0.001;
                results.add(new MutationResult(
                    "MATH",
                    "discountRate += 0.05 (VIP bonus)",
                    "discountRate -= 0.05",
                    killed,
                    "Need test comparing VIP vs non-VIP to verify +5% not -5%"
                ));
            }

            // ── MUTATION 4: NEGATE_CONDITIONALS ───────────────────
            // Original: if (isVip)  →  Mutant: if (!isVip)
            {
                double amount = 200.0;
                double vipDiscount    = calc.calculate(amount, true);   // 30.0
                double nonVipDiscount = calc.calculate(amount, false);  // 20.0
                boolean killed = vipDiscount > nonVipDiscount;
                results.add(new MutationResult(
                    "NEGATE_CONDITIONALS",
                    "if (isVip)",
                    "if (!isVip)",
                    killed,
                    "Need both VIP=true and VIP=false assertions"
                ));
            }

            // ── MUTATION 5: RETURN_VALS ────────────────────────────
            // Original: return amount * discountRate  →  Mutant: return 0
            {
                double discount = calc.calculate(200.0, false); // 20.0
                boolean killed = discount > 0;
                results.add(new MutationResult(
                    "RETURN_VALS",
                    "return amount * discountRate",
                    "return 0.0",
                    killed,
                    "Assert exact return value, not just > 0"
                ));
            }

            return results;
        }

        static List<MutationResult> simulateStackMutations(BoundedStack<Integer> stack) {
            List<MutationResult> results = new ArrayList<>();

            // ── MUTATION 6: INCREMENT (i++ → i--) ─────────────────
            // push: elements[size++] → elements[size--]
            {
                BoundedStack<Integer> s = new BoundedStack<>(5);
                s.push(10);
                int sizeAfterPush = s.size();
                boolean killed = sizeAfterPush == 1; // Mutant: size decrements → -1 → error
                results.add(new MutationResult(
                    "INCREMENT",
                    "elements[size++] in push",
                    "elements[size--]",
                    killed,
                    "Assert size() == 1 after pushing 1 element"
                ));
            }

            // ── MUTATION 7: VOID_METHOD_CALLS (clear nullification) ─
            // clear(): Arrays.fill(elements, null) → removed
            // If fill is removed, elements still have old references → memory leak
            // This is hard to detect without checking internal state!
            {
                // Simulate: after clear(), if we inspect internal array it should be null
                // In real PIT test, you'd need to verify via reflection or behavior
                BoundedStack<Object> s2 = new BoundedStack<>(3);
                s2.push("a");
                s2.push("b");
                s2.clear();
                boolean killed = s2.size() == 0 && s2.isEmpty();
                // NOTE: size==0 alone doesn't catch the mutation!
                // We need to verify elements[0] is null (via reflection in real test)
                results.add(new MutationResult(
                    "VOID_METHOD_CALLS",
                    "Arrays.fill(elements, null) in clear()",
                    "(removed — memory leak)",
                    false, // ← SURVIVED! size()==0 check doesn't catch it
                    "Must verify elements[0]==null via reflection after clear()"
                ));
            }

            // ── MUTATION 8: CONDITIONALS_BOUNDARY in isFull ────────
            // isFull: size >= capacity → size > capacity
            {
                BoundedStack<Integer> s = new BoundedStack<>(2);
                s.push(1);
                s.push(2);
                boolean isFull = s.isFull();
                boolean killed = isFull; // should be true when size==capacity
                results.add(new MutationResult(
                    "CONDITIONALS_BOUNDARY",
                    "size >= capacity in isFull()",
                    "size > capacity",
                    killed,
                    "Test isFull() when size == capacity exactly"
                ));
            }

            return results;
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 3: WEAK vs STRONG TEST SUITE
    // ═══════════════════════════════════════════════════════

    /**
     * So sánh weak test suite vs strong test suite.
     * Minh họa tại sao coverage 100% ≠ mutation score cao.
     */
    static void demoWeakVsStrongTests() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 1: Weak vs Strong Test Suite");
        System.out.println("═══════════════════════════════════════════════════");

        DiscountCalculator calc = new DiscountCalculator();

        System.out.println("\n--- WEAK TEST SUITE (100% coverage, low mutation score) ---");
        System.out.println("Code:");
        System.out.println("  @Test void testCalculate() {");
        System.out.println("      double result = calc.calculate(200, false);");
        System.out.println("      assertThat(result).isGreaterThan(0); // WEAK: just > 0");
        System.out.println("  }");
        System.out.println("\nMutants this WEAK test CANNOT kill:");

        // Weak assertion: just > 0
        double result = calc.calculate(200, false); // 20.0
        System.out.println("  ❌ MATH: amount*0.1 → amount*0.05 → result=10.0 (still >0, SURVIVED)");
        System.out.println("  ❌ RETURN_VALS: return 0 → result=0 (isGreaterThan(0) catches it... barely)");
        System.out.println("  ❌ CONDITIONALS_BOUNDARY: <100 → <=100 (amount=200 unaffected, SURVIVED)");
        System.out.println("  ❌ No VIP test → VIP mutations SURVIVE");

        System.out.println("\n--- STRONG TEST SUITE (high mutation score) ---");
        System.out.println("Tests:");

        // Test boundary: 99 → 0%
        double d99 = calc.calculate(99, false);
        System.out.printf("  ✅ calculate(99,  false) = %.1f (expected 0.0)%n", d99);

        // Test exact boundary: 100 → 10%
        double d100 = calc.calculate(100, false);
        System.out.printf("  ✅ calculate(100, false) = %.1f (expected 10.0)%n", d100);

        // Test boundary: 499 → 10%
        double d499 = calc.calculate(499, false);
        System.out.printf("  ✅ calculate(499, false) = %.2f (expected 49.90)%n", d499);

        // Test exact boundary: 500 → 20%
        double d500 = calc.calculate(500, false);
        System.out.printf("  ✅ calculate(500, false) = %.1f (expected 100.0)%n", d500);

        // Test exact boundary: 1000 → 30%
        double d1000 = calc.calculate(1000, false);
        System.out.printf("  ✅ calculate(1000, false) = %.1f (expected 300.0)%n", d1000);

        // Test VIP both ways
        double vip = calc.calculate(200, true);
        double nonVip = calc.calculate(200, false);
        System.out.printf("  ✅ calculate(200, true)  = %.1f (expected 30.0) VIP%n", vip);
        System.out.printf("  ✅ calculate(200, false) = %.1f (expected 20.0) non-VIP%n", nonVip);

        // Test exception
        System.out.println("  ✅ calculate(-1, false)  → throws IllegalArgumentException");
        try { calc.calculate(-1, false); } catch (IllegalArgumentException e) {
            System.out.println("       caught: " + e.getMessage());
        }

        System.out.println("\nEstimated mutation scores:");
        System.out.println("  Weak  suite: ~30-40% (boundary mutations all survive)");
        System.out.println("  Strong suite: ~90-95% (boundary + VIP + exception covered)");
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 4: MUTATION SIMULATION RUN
    // ═══════════════════════════════════════════════════════

    static void demoMutationSimulation() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 2: Mutation Simulation (Manual PIT emulation)");
        System.out.println("═══════════════════════════════════════════════════");

        DiscountCalculator calc = new DiscountCalculator();
        BoundedStack<Integer> stack = new BoundedStack<>(10);

        System.out.println("\n[DiscountCalculator mutations]");
        List<MutationSimulator.MutationResult> discountResults =
            MutationSimulator.simulateDiscountMutations(calc);
        discountResults.forEach(System.out::println);

        long discountKilled = discountResults.stream().filter(r -> r.killed()).count();
        System.out.printf("%nDiscount mutation score: %d/%d = %.0f%%%n",
            discountKilled, discountResults.size(),
            100.0 * discountKilled / discountResults.size());

        System.out.println("\n[BoundedStack mutations]");
        List<MutationSimulator.MutationResult> stackResults =
            MutationSimulator.simulateStackMutations(stack);
        stackResults.forEach(System.out::println);

        long stackKilled = stackResults.stream().filter(r -> r.killed()).count();
        System.out.printf("%nStack mutation score: %d/%d = %.0f%%%n",
            stackKilled, stackResults.size(),
            100.0 * stackKilled / stackResults.size());

        long totalKilled = discountKilled + stackKilled;
        long totalMutants = discountResults.size() + stackResults.size();
        System.out.printf("%nOVERALL MUTATION SCORE: %d/%d = %.0f%%%n",
            totalKilled, totalMutants, 100.0 * totalKilled / totalMutants);
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 5: FIX SURVIVING MUTANTS
    // ═══════════════════════════════════════════════════════

    /**
     * Khi PIT report cho thấy surviving mutants, cần phân tích:
     * 1. Mutant survived vì test assertion yếu → strengthen assertion
     * 2. Mutant survived vì equivalent mutant → có thể ignore
     * 3. Mutant survived vì dead code → xóa code đó
     *
     * EQUIVALENT MUTANT: mutation không thay đổi behavior
     *   x = i + 0  →  x = i - 0  (cùng kết quả → không thể kill)
     *   while(true) { if(x) break; }  vs  do { } while(!x)
     * → PIT không thể tự phát hiện, cần human review
     */
    static void demoFixingSurvivingMutants() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 3: Fixing Surviving Mutants");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\nCase 1: VOID_METHOD_CALLS — Arrays.fill() survived");
        System.out.println("  Problem: clear() sets size=0 but fill() mutation not caught");
        System.out.println("  Fix: Test internal state via reflection OR behavioral proof");
        System.out.println();

        // FIX: Behavioral proof — push after clear should work from index 0
        BoundedStack<String> s = new BoundedStack<>(2);
        s.push("first");
        s.push("second");
        s.clear();
        s.push("third");  // if elements[0] wasn't nulled, this would still "work" but corrupt state

        // Better fix: verify via reflection
        try {
            var field = BoundedStack.class.getDeclaredField("elements");
            field.setAccessible(true);
            Object[] elements = (Object[]) field.get(s);
            // after clear+push("third"), elements[0]="third", elements[1]=null
            boolean nulled = elements[1] == null;
            System.out.println("  Test: after clear()+push('third'), elements[1]==null: " + nulled);
            System.out.println("  [" + (nulled ? "KILLS" : "MISSES") + "] VOID_METHOD_CALLS mutant");
        } catch (Exception e) {
            System.out.println("  Reflection failed: " + e.getMessage());
        }

        System.out.println("\nCase 2: CONDITIONALS_BOUNDARY — need exact boundary tests");
        System.out.println("  PIT report shows: 'amount < 100' → 'amount <= 100' survived");
        System.out.println("  Fix: Add test with amount = 100 and assert exact result");
        System.out.println();

        DiscountCalculator calc = new DiscountCalculator();
        double at100 = calc.calculate(100, false);
        System.out.printf("  calc.calculate(100, false) = %.1f%n", at100);
        System.out.printf("  assertThat(result).isEqualTo(10.0) → %s%n",
            Math.abs(at100 - 10.0) < 0.001 ? "PASSES ✅ (kills boundary mutant)" : "FAILS");

        System.out.println("\nCase 3: Equivalent Mutant — can safely ignore");
        System.out.println("  Original: if (errors.isEmpty()) return ok() else return fail()");
        System.out.println("  Mutant  : if (!errors.isEmpty()) return fail() else return ok()");
        System.out.println("  → Logically equivalent → impossible to kill");
        System.out.println("  → Use @SuppressWarnings or PIT exclude annotation");
        System.out.println("  → Or restructure code: return errors.isEmpty() ? ok() : fail()");

        System.out.println("\nCase 4: RETURN_VALS on Optional.empty()");
        System.out.println("  Original: return Optional.ofNullable(map.get(key))");
        System.out.println("  Mutant  : return Optional.empty()");
        System.out.println("  Fix: Test findById with EXISTING key and assert value present");

        OrderService svc = new OrderService((event, id) -> {});
        var order = svc.createOrder("Alice", 100);
        var found = svc.findById(order.id());
        System.out.println("  findById('" + order.id() + "').isPresent() = " + found.isPresent() + " ✅");
        System.out.println("  findById('FAKE').isEmpty()   = " + svc.findById("FAKE").isEmpty() + " ✅");
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 6: PIT CONFIGURATION & OPERATORS
    // ═══════════════════════════════════════════════════════

    static void demoPitConfiguration() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 4: PIT Configuration & Operator Groups");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\nMUTATOR GROUPS:");
        System.out.println("""
            DEFAULTS (recommended start):
              CONDITIONALS_BOUNDARY  — < ↔ <=, > ↔ >=
              NEGATE_CONDITIONALS    — if(a) → if(!a)
              MATH                   — + ↔ -, * ↔ /
              INCREMENT              — i++ ↔ i--
              INVERT_NEGS            — -x ↔ x
              RETURN_VALS            — return true/false/null/0
              VOID_METHOD_CALLS      — remove void method calls

            STRONGER (adds):
              NON_VOID_METHOD_CALLS  — replace call result with default
              CONSTRUCTOR_CALLS      — replace new Foo() with null
              INLINE_CONSTS          — replace literal constants

            ALL:
              Includes EXPERIMENTAL operators
              → Slower, more noise, may have equivalent mutants
            """);

        System.out.println("CONFIGURATION OPTIONS:");
        System.out.println("""
            <configuration>
              <!-- Which classes to mutate -->
              <targetClasses>
                <param>com.example.service.*</param>
                <param>com.example.domain.*</param>
              </targetClasses>

              <!-- Which tests to run -->
              <targetTests>
                <param>com.example.*Test</param>
                <param>com.example.*IT</param>
              </targetTests>

              <!-- Exclude generated/boilerplate code -->
              <excludedClasses>
                <param>com.example.generated.*</param>
              </excludedClasses>

              <!-- Exclude trivial methods -->
              <excludedMethods>
                <param>toString</param>
                <param>hashCode</param>
                <param>equals</param>
              </excludedMethods>

              <!-- Mutation score threshold (fail build if below) -->
              <mutationThreshold>80</mutationThreshold>
              <coverageThreshold>90</coverageThreshold>

              <!-- Parallel execution -->
              <threads>4</threads>

              <!-- Report formats -->
              <outputFormats>HTML,XML,CSV</outputFormats>
              <timestampedReports>false</timestampedReports>

              <!-- Incremental analysis (only re-test changed code) -->
              <withHistory>true</withHistory>

              <!-- Mutation operators -->
              <mutators>DEFAULTS</mutators>
            </configuration>
            """);

        System.out.println("CI/CD INTEGRATION:");
        System.out.println("""
            # GitHub Actions:
            - name: Mutation Tests
              run: mvn test-compile org.pitest:pitest-maven:mutationCoverage
            - name: Upload PIT Report
              uses: actions/upload-artifact@v3
              with:
                name: pit-report
                path: target/pit-reports/

            # Fail build if mutation score < 80%:
            <mutationThreshold>80</mutationThreshold>
            → mvn pitest:mutationCoverage exits with code 1 if below threshold
            """);
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 7: READING PIT REPORT
    // ═══════════════════════════════════════════════════════

    static void demoReadingPitReport() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 5: Reading PIT HTML Report");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            PIT REPORT STRUCTURE (target/pit-reports/index.html):

            ┌─────────────────────────────────────────────────────┐
            │ Package Summary                                      │
            │                                                     │
            │ Package              | Coverage | Mutation Score    │
            │ com.example.service  |    95%   |      82%  ← goal  │
            │ com.example.domain   |    88%   |      71%  ← weak  │
            │ com.example.util     |   100%   |      45%  ← BAD!  │
            └─────────────────────────────────────────────────────┘

            Click on class → see per-line mutation results:

            Line 45:  if (amount < 100) {
                      ├── CONDITIONALS_BOUNDARY: killed ✅ (test covers amount=99 and 100)
                      └── NEGATE_CONDITIONALS:   killed ✅

            Line 52:  discountRate += 0.05;
                      ├── MATH: survived ❌ ← NEED FIX
                      └── VOID_METHOD_CALLS: N/A (not void)

            Line 67:  log.info("Order created");
                      └── VOID_METHOD_CALLS: survived ❌
                          → Either add spy assertion on logger, or exclude log lines

            HOW TO PRIORITIZE FIXES:
            1. Check survived mutants on CRITICAL business logic first
               (pricing, validation, state transitions)
            2. Ignore survived mutants on:
               - Logging statements (low risk)
               - Equivalent mutants (mathematically same)
               - Generated code (DTO, Lombok, etc.)
            3. Fix by adding/strengthening assertions, not just coverage
            """);

        System.out.println("COMMON SURVIVING MUTANT PATTERNS & FIXES:");
        System.out.println("""
            1. VOID_METHOD_CALLS on event publishing / audit log
               → Fix: Capture & assert with ArgumentCaptor (Mockito)
               → assertThat(auditCaptor.getValue()).isEqualTo("ORDER_CREATED")

            2. CONDITIONALS_BOUNDARY on numeric ranges
               → Fix: Test at boundary ±1: (99, 100, 101), (499, 500, 501)
               → Use @ParameterizedTest with @ValueSource

            3. RETURN_VALS on Optional
               → Fix: Test both present and empty cases, assert value contents

            4. NEGATE_CONDITIONALS on boolean flag
               → Fix: Test with flag=true AND flag=false, assert different behavior

            5. MATH on percentage/ratio calculations
               → Fix: Assert exact numeric result (not just > 0)
               → Use assertThat(result).isCloseTo(30.0, within(0.001))
            """);
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 8: PROPERTY-BASED TESTING TIE-IN
    // ═══════════════════════════════════════════════════════

    /**
     * Property-based testing tự nhiên kill nhiều mutants hơn example-based.
     *
     * Libraries: jqwik, junit-quickcheck, QuickTheories
     *
     * Vì property tests chạy với random inputs:
     *   - Boundary conditions được hit ngẫu nhiên
     *   - Nhiều mutation bị kill mà không cần viết explicit test
     *   - Mutation score tăng từ 60% → 85%+ với cùng số test methods
     */
    static void demoPropertyBasedTie() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 6: Property-Based Testing → Better Mutation Score");
        System.out.println("═══════════════════════════════════════════════════");

        DiscountCalculator calc = new DiscountCalculator();

        System.out.println("\nProperty: discount must be in [0, amount] for all valid amounts");
        Random rng = new Random(42);
        int passed = 0, failed = 0;
        for (int i = 0; i < 1000; i++) {
            double amount = rng.nextDouble() * 2000;
            boolean vip = rng.nextBoolean();
            double discount = calc.calculate(amount, vip);
            if (discount >= 0 && discount <= amount * 0.35) { // max 30% + 5% VIP
                passed++;
            } else {
                failed++;
                System.out.printf("  FAIL: amount=%.2f vip=%b discount=%.2f%n", amount, vip, discount);
            }
        }
        System.out.printf("  1000 random inputs: %d passed, %d failed%n", passed, failed);

        System.out.println("\nProperty: VIP always gets >= non-VIP discount (same amount)");
        int vipGreater = 0;
        for (int i = 0; i < 100; i++) {
            double amount = rng.nextDouble() * 2000 + 1; // > 0
            double vipDisc    = calc.calculate(amount, true);
            double nonVipDisc = calc.calculate(amount, false);
            if (vipDisc >= nonVipDisc) vipGreater++;
        }
        System.out.printf("  100 random amounts: VIP >= non-VIP in %d/100 cases%n", vipGreater);

        System.out.println("\nProperty: calculateFinal = amount - calculate (invariant)");
        boolean invariantHolds = true;
        for (int i = 0; i < 100; i++) {
            double amount = rng.nextDouble() * 1000;
            boolean vip = rng.nextBoolean();
            double finalPrice = calc.calculateFinal(amount, vip);
            double discount   = calc.calculate(amount, vip);
            if (Math.abs(finalPrice - (amount - discount)) > 0.0001) {
                invariantHolds = false;
                break;
            }
        }
        System.out.println("  finalPrice = amount - discount invariant: " + (invariantHolds ? "HOLDS ✅" : "BROKEN ❌"));

        System.out.println("""
            \nProperty-based testing with jqwik:
              @Property
              void discountNeverExceedsAmount(@ForAll @DoubleRange(min=0, max=10000) double amount,
                                              @ForAll boolean vip) {
                  double discount = calc.calculate(amount, vip);
                  assertThat(discount).isBetween(0.0, amount);
              }
              → jqwik generates 1000 random examples automatically
              → Shrinks failing cases to minimal reproducer
            """);
    }

    // ═══════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  BÀI 8.3 — MUTATION TESTING: TEST QUALITY METRICS ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        demoWeakVsStrongTests();
        demoMutationSimulation();
        demoFixingSurvivingMutants();
        demoPitConfiguration();
        demoReadingPitReport();
        demoPropertyBasedTie();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TỔNG KẾT BÀI 8.3                                ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║                                                   ║");
        System.out.println("║  MUTATION TESTING = inject bugs, check if tests  ║");
        System.out.println("║  catch them. Better quality metric than coverage. ║");
        System.out.println("║                                                   ║");
        System.out.println("║  MUTATION SCORE > 80% = healthy test suite       ║");
        System.out.println("║  Coverage 100% + Score 40% = weak tests          ║");
        System.out.println("║                                                   ║");
        System.out.println("║  KEY OPERATORS:                                   ║");
        System.out.println("║  CONDITIONALS_BOUNDARY → test exact boundaries   ║");
        System.out.println("║  VOID_METHOD_CALLS     → capture & assert calls  ║");
        System.out.println("║  RETURN_VALS           → assert exact values     ║");
        System.out.println("║  NEGATE_CONDITIONALS   → test both flag values   ║");
        System.out.println("║                                                   ║");
        System.out.println("║  FIX PATTERN:                                    ║");
        System.out.println("║  survived mutant → strengthen assertion          ║");
        System.out.println("║  equivalent mutant → annotate or ignore          ║");
        System.out.println("║                                                   ║");
        System.out.println("║  Property-based testing kills more mutants       ║");
        System.out.println("║  with fewer explicit test cases                  ║");
        System.out.println("║                                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        System.out.println("\n✅ Module 8 — Testing chuyên sâu: HOÀN THÀNH!");
        System.out.println("→ Bài tiếp theo: Module 9.1 — Domain Driven Design (DDD)");
    }
}
