package org.example.testing;

import org.mockito.*;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Bài 8.1 — Mocking Deep Dive: Mockito internals, Spy vs Mock, Verification
 *
 * Mục tiêu học:
 * 1. Mockito internals: bytecode proxy generation (Byte Buddy), mock lifecycle
 * 2. Mock vs Spy vs Stub — khác biệt cốt lõi, khi nào dùng gì
 * 3. Stubbing: thenReturn, thenThrow, thenAnswer, doAnswer (void methods)
 * 4. ArgumentMatchers: eq(), any(), argThat(), captor
 * 5. Verification: verify(), times(), never(), atLeast(), InOrder
 * 6. ArgumentCaptor — capture & assert complex args
 * 7. Strict stubbing (Mockito 2+) — cảnh báo unused stubs
 * 8. Common pitfalls: final class, static method, partial mock trap
 * 9. Test design: what to mock, what NOT to mock (SA perspective)
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.testing.MockingDeepDiveDemo"
 *
 * SA Insight: Test doubles phân tầng rõ ràng giúp test nhanh, focused,
 * và không bị flaky. Mock quá nhiều = test chỉ kiểm tra interaction,
 * không kiểm tra behaviour thực sự.
 */
public class MockingDeepDiveDemo {

    public static void main(String[] args) {
        System.out.println("=== Bài 8.1: Mocking Deep Dive với Mockito ===\n");

        demo1_MockitoInternals();
        demo2_MockVsSpyVsStub();
        demo3_StubbingTechniques();
        demo4_VoidMethodStubbing();
        demo5_ArgumentMatchers();
        demo6_ArgumentCaptor();
        demo7_VerificationStrategies();
        demo8_InOrderVerification();
        demo9_TestDesignPrinciples();

        System.out.println("\n=== Hoàn thành bài 8.1 ===");
    }

    // =========================================================================
    // Domain model cho toàn bộ demo
    // =========================================================================

    interface UserRepository {
        User findById(long id);
        User save(User user);
        List<User> findAll();
        void delete(long id);
        boolean existsByEmail(String email);
    }

    interface EmailService {
        void sendWelcome(String email, String name);
        void sendPasswordReset(String email, String token);
        boolean isAvailable();
    }

    interface AuditLogger {
        void log(String action, long userId, String details);
        void logError(String action, Exception e);
    }

    interface PaymentGateway {
        PaymentResult charge(String cardToken, long amountCents);
        void refund(String transactionId);
    }

    record User(long id, String name, String email, boolean active) {
        User withActive(boolean active) {
            return new User(id, name, email, active);
        }
    }

    record PaymentResult(boolean success, String transactionId, String errorCode) {
        static PaymentResult ok(String txId) { return new PaymentResult(true, txId, null); }
        static PaymentResult fail(String code) { return new PaymentResult(false, null, code); }
    }

    // Service under test
    static class UserService {
        private final UserRepository repo;
        private final EmailService emailService;
        private final AuditLogger audit;

        UserService(UserRepository repo, EmailService emailService, AuditLogger audit) {
            this.repo = repo;
            this.emailService = emailService;
            this.audit = audit;
        }

        User registerUser(String name, String email) {
            if (repo.existsByEmail(email)) {
                throw new IllegalArgumentException("Email already registered: " + email);
            }
            User saved = repo.save(new User(0, name, email, true));
            emailService.sendWelcome(email, name);
            audit.log("REGISTER", saved.id(), "name=" + name);
            return saved;
        }

        User activateUser(long id) {
            User user = repo.findById(id);
            if (user == null) throw new IllegalArgumentException("User not found: " + id);
            if (user.active()) return user; // already active
            User activated = repo.save(user.withActive(true));
            audit.log("ACTIVATE", id, "activated");
            return activated;
        }

        void deleteUser(long id) {
            User user = repo.findById(id);
            if (user == null) throw new IllegalArgumentException("User not found: " + id);
            repo.delete(id);
            audit.log("DELETE", id, "email=" + user.email());
        }

        List<User> getActiveUsers() {
            return repo.findAll().stream().filter(User::active).toList();
        }
    }

    // =========================================================================
    // DEMO 1: Mockito Internals — How mocks are created
    // =========================================================================
    static void demo1_MockitoInternals() {
        System.out.println("--- Demo 1: Mockito Internals ---");

        System.out.println("""
            Mockito tạo mock bằng bytecode generation (Byte Buddy library):

            1. mock(UserRepository.class) →
               - Byte Buddy generates subclass: UserRepository$MockitoMock$1234
               - Subclass overrides ALL methods
               - Each method → delegate to MockHandler
               - MockHandler checks stubbing registry → return stub OR default

            2. Default return values (when not stubbed):
               - int/long/double... → 0 / 0L / 0.0
               - boolean → false
               - Object references → null
               - Collections → empty (List.of(), Map.of(), etc.)
               - Optional → Optional.empty()
               - String → "" (configurable via MockSettings)

            3. Spy(realObject) → Byte Buddy subclass that DELEGATES to real object
               unless method is stubbed

            4. MockitoAnnotations.openMocks(this):
               - Scans @Mock / @Spy / @Captor / @InjectMocks fields
               - Creates mocks and injects into @InjectMocks target
               - Must call openMocks() before each test (or use MockitoExtension)
            """);

        // Create mock and show defaults
        UserRepository mockRepo = mock(UserRepository.class);

        System.out.println("Mock class name: " + mockRepo.getClass().getName());
        System.out.println("Is UserRepository: " + (mockRepo instanceof UserRepository));

        System.out.println("\nDefault return values (unstubbed):");
        System.out.println("  findById(1)       → " + mockRepo.findById(1));         // null
        System.out.println("  existsByEmail(..) → " + mockRepo.existsByEmail("x")); // false
        System.out.println("  findAll()         → " + mockRepo.findAll());           // []
        System.out.println("  save(user)        → " + mockRepo.save(new User(1, "A", "a@b.com", true))); // null

        // MockSettings customization
        UserRepository verboseMock = mock(UserRepository.class,
            withSettings().name("userRepo").defaultAnswer(RETURNS_SMART_NULLS));
        System.out.println("\nRETURNS_SMART_NULLS: prevents NPE on unstubbed chains");
        System.out.println("  (smart nulls throw friendly errors on method chaining)");

        System.out.println();
    }

    // =========================================================================
    // DEMO 2: Mock vs Spy vs Stub — core differences
    // =========================================================================
    static void demo2_MockVsSpyVsStub() {
        System.out.println("--- Demo 2: Mock vs Spy vs Stub ---");

        System.out.println("""
            ┌──────────┬───────────────────────────────────────────────────────────┐
            │ Type     │ Behaviour                                                 │
            ├──────────┼───────────────────────────────────────────────────────────┤
            │ Stub     │ Hardcoded return value, no verification                  │
            │ Mock     │ All methods replaced (return default), verify interactions│
            │ Spy      │ Real object wrapped, only stubbed methods intercepted     │
            │ Fake     │ Simplified working implementation (e.g. in-memory DB)    │
            │ Dummy    │ Passed but never actually used (fills parameter list)     │
            └──────────┴───────────────────────────────────────────────────────────┘
            """);

        // MOCK: full replacement, nothing real executes
        System.out.println("=== MOCK ===");
        List<String> mockList = mock(List.class);
        when(mockList.size()).thenReturn(5);
        System.out.println("  mockList.size()  = " + mockList.size());    // 5 (stubbed)
        System.out.println("  mockList.get(0)  = " + mockList.get(0));    // null (unstubbed)
        System.out.println("  mockList.isEmpty()= " + mockList.isEmpty()); // false (default)
        System.out.println("  (real list operations never executed)");

        // SPY: real object, real methods by default
        System.out.println("\n=== SPY ===");
        List<String> realList = new ArrayList<>(List.of("a", "b", "c"));
        List<String> spyList = spy(realList);

        System.out.println("  spyList.size()   = " + spyList.size());   // 3 (real)
        System.out.println("  spyList.get(0)   = " + spyList.get(0));   // "a" (real)

        // Stub only one method
        when(spyList.size()).thenReturn(99);
        System.out.println("  After stub size():");
        System.out.println("  spyList.size()   = " + spyList.size());   // 99 (stubbed)
        System.out.println("  spyList.get(1)   = " + spyList.get(1));   // "b" (still real)
        System.out.println("  spyList.contains(\"c\") = " + spyList.contains("c")); // true (real)

        // SPY PITFALL: when(spy.method()) calls real method FIRST!
        System.out.println("""

            SPY pitfall — when(spy.realMethod()) CALLS real method during stubbing:
              when(spyList.get(0)).thenReturn("x")   ← get(0) runs FIRST, may throw!

            Fix: use doReturn() instead:
              doReturn("x").when(spyList).get(0);    ← safe, no real call
            """);
        doReturn("REPLACED").when(spyList).get(0);
        System.out.println("  After doReturn: spyList.get(0) = " + spyList.get(0));

        // FAKE: in-memory implementation
        System.out.println("\n=== FAKE (in-memory) ===");
        UserRepository fakeRepo = new InMemoryUserRepository();
        User saved = fakeRepo.save(new User(0, "Alice", "alice@example.com", true));
        System.out.println("  Saved: " + saved);
        System.out.println("  FindById: " + fakeRepo.findById(saved.id()));
        System.out.println("  FindAll: " + fakeRepo.findAll().size() + " users");
        System.out.println("  (Fake: real logic, but in-memory, no DB)");
        System.out.println();
    }

    // In-memory fake repository
    static class InMemoryUserRepository implements UserRepository {
        private final Map<Long, User> store = new HashMap<>();
        private final AtomicInteger idSeq = new AtomicInteger(1);

        public User findById(long id) { return store.get(id); }
        public User save(User user) {
            long id = user.id() == 0 ? idSeq.getAndIncrement() : user.id();
            User saved = new User(id, user.name(), user.email(), user.active());
            store.put(id, saved);
            return saved;
        }
        public List<User> findAll() { return new ArrayList<>(store.values()); }
        public void delete(long id) { store.remove(id); }
        public boolean existsByEmail(String email) {
            return store.values().stream().anyMatch(u -> u.email().equals(email));
        }
    }

    // =========================================================================
    // DEMO 3: Stubbing Techniques
    // =========================================================================
    static void demo3_StubbingTechniques() {
        System.out.println("--- Demo 3: Stubbing Techniques ---");

        UserRepository repo = mock(UserRepository.class);
        User alice = new User(1L, "Alice", "alice@example.com", true);
        User bob   = new User(2L, "Bob",   "bob@example.com",   false);

        // thenReturn: simple fixed value
        when(repo.findById(1L)).thenReturn(alice);
        when(repo.findById(2L)).thenReturn(bob);
        System.out.println("thenReturn:");
        System.out.println("  findById(1) = " + repo.findById(1));
        System.out.println("  findById(2) = " + repo.findById(2));
        System.out.println("  findById(9) = " + repo.findById(9)); // null — unstubbed

        // thenReturn with multiple values (sequential stubbing)
        UserRepository seqRepo = mock(UserRepository.class);
        when(seqRepo.findAll())
            .thenReturn(List.of(alice))
            .thenReturn(List.of(alice, bob))
            .thenReturn(Collections.emptyList());

        System.out.println("\nSequential stubbing (thenReturn chained):");
        System.out.println("  call 1: " + seqRepo.findAll().size() + " users");
        System.out.println("  call 2: " + seqRepo.findAll().size() + " users");
        System.out.println("  call 3: " + seqRepo.findAll().size() + " users");
        System.out.println("  call 4: " + seqRepo.findAll().size() + " users (repeats last)");

        // thenThrow: exception stubbing
        UserRepository errorRepo = mock(UserRepository.class);
        when(errorRepo.findById(anyLong()))
            .thenThrow(new RuntimeException("DB connection lost"));

        System.out.println("\nthenThrow:");
        try {
            errorRepo.findById(1L);
        } catch (RuntimeException e) {
            System.out.println("  Caught: " + e.getMessage());
        }

        // thenAnswer: dynamic response based on args
        UserRepository dynamicRepo = mock(UserRepository.class);
        when(dynamicRepo.findById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            if (id <= 0) throw new IllegalArgumentException("Invalid id: " + id);
            return new User(id, "User-" + id, "user" + id + "@example.com", id % 2 == 0);
        });

        System.out.println("\nthenAnswer (dynamic):");
        System.out.println("  findById(3)  = " + dynamicRepo.findById(3));
        System.out.println("  findById(4)  = " + dynamicRepo.findById(4));
        try {
            dynamicRepo.findById(-1);
        } catch (IllegalArgumentException e) {
            System.out.println("  findById(-1) = " + e.getMessage());
        }

        // thenAnswer: simulate save with auto-id
        UserRepository saveRepo = mock(UserRepository.class);
        AtomicInteger idGen = new AtomicInteger(100);
        when(saveRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return new User(idGen.incrementAndGet(), u.name(), u.email(), u.active());
        });

        User saved1 = saveRepo.save(new User(0, "Carol", "carol@example.com", true));
        User saved2 = saveRepo.save(new User(0, "Dave",  "dave@example.com",  true));
        System.out.println("\nthenAnswer (simulate auto-id):");
        System.out.println("  saved1.id() = " + saved1.id() + ", name=" + saved1.name());
        System.out.println("  saved2.id() = " + saved2.id() + ", name=" + saved2.name());
        System.out.println();
    }

    // =========================================================================
    // DEMO 4: Void Method Stubbing
    // =========================================================================
    static void demo4_VoidMethodStubbing() {
        System.out.println("--- Demo 4: Void Method Stubbing ---");

        System.out.println("""
            void methods cannot use when(mock.method()).thenXxx() syntax
            because when() needs a return value to chain on.
            Use doXxx().when(mock).method() form instead:
            """);

        EmailService email = mock(EmailService.class);
        AuditLogger audit = mock(AuditLogger.class);

        // By default: void methods do nothing (no error, no action)
        System.out.println("Default (do nothing):");
        email.sendWelcome("user@example.com", "User"); // silently does nothing
        System.out.println("  sendWelcome() called — no exception, no action");

        // doThrow: make void method throw
        doThrow(new RuntimeException("SMTP server down"))
            .when(email).sendWelcome(eq("bad@example.com"), anyString());

        System.out.println("\ndoThrow on void method:");
        try {
            email.sendWelcome("bad@example.com", "User");
        } catch (RuntimeException e) {
            System.out.println("  sendWelcome threw: " + e.getMessage());
        }
        email.sendWelcome("good@example.com", "User"); // not stubbed → silent

        // doAnswer: void method with side effect
        List<String> auditLog = new ArrayList<>();
        doAnswer(invocation -> {
            String action = invocation.getArgument(0);
            long userId   = invocation.getArgument(1);
            String details = invocation.getArgument(2);
            auditLog.add("[" + action + "] user=" + userId + " " + details);
            return null; // void methods return null from doAnswer
        }).when(audit).log(anyString(), anyLong(), anyString());

        System.out.println("\ndoAnswer on void (capturing side effects):");
        audit.log("LOGIN",  1L, "ip=10.0.0.1");
        audit.log("LOGOUT", 1L, "duration=120s");
        audit.log("DELETE", 2L, "reason=request");
        auditLog.forEach(entry -> System.out.println("  " + entry));

        // doNothing: explicit no-op (useful when spy overrides real method)
        AuditLogger spyAudit = spy(new RealAuditLogger());
        doNothing().when(spyAudit).log(anyString(), anyLong(), anyString());

        System.out.println("\ndoNothing on spy (suppress real method):");
        spyAudit.log("TEST", 1L, "this won't print"); // real method suppressed
        System.out.println("  (real log() suppressed by doNothing)");
        System.out.println();
    }

    static class RealAuditLogger implements AuditLogger {
        public void log(String action, long userId, String details) {
            System.out.println("  [REAL AUDIT] " + action + " user=" + userId + " " + details);
        }
        public void logError(String action, Exception e) {
            System.out.println("  [REAL AUDIT ERROR] " + action + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // DEMO 5: ArgumentMatchers
    // =========================================================================
    static void demo5_ArgumentMatchers() {
        System.out.println("--- Demo 5: ArgumentMatchers ---");

        System.out.println("""
            ArgumentMatchers let stubs respond to argument patterns, not exact values.
            RULE: If you use a matcher for ANY argument, ALL arguments must use matchers.
              ✅ when(repo.findById(anyLong()))
              ✅ when(repo.save(any(User.class)))
              ✅ when(method(eq("x"), anyInt()))   ← mix: eq() wraps literal
              ❌ when(method("x", anyInt()))        ← mixing literal with matcher = error
            """);

        UserRepository repo = mock(UserRepository.class);
        User alice = new User(1L, "Alice", "alice@example.com", true);

        // any() family
        when(repo.findById(anyLong())).thenReturn(alice);
        when(repo.existsByEmail(anyString())).thenReturn(true);
        when(repo.save(any(User.class))).thenReturn(alice);
        when(repo.findAll()).thenReturn(List.of(alice));

        System.out.println("any() matchers:");
        System.out.println("  findById(42L)      = " + repo.findById(42L));
        System.out.println("  existsByEmail(..)  = " + repo.existsByEmail("anything@mail.com"));

        // eq() for exact value alongside matchers
        EmailService email = mock(EmailService.class);
        when(email.isAvailable()).thenReturn(true);
        doNothing().when(email).sendWelcome(eq("vip@company.com"), anyString());

        System.out.println("\neq() exact match:");
        email.sendWelcome("vip@company.com", "anyone");  // matches
        System.out.println("  sendWelcome(vip@company.com, ..) — matched");

        // argThat() — custom predicate matcher
        UserRepository customRepo = mock(UserRepository.class);
        when(customRepo.save(argThat(user ->
            user != null && user.email().endsWith("@company.com")))
        ).thenReturn(new User(99L, "Corp User", "user@company.com", true));

        System.out.println("\nargThat (custom predicate):");
        User corpUser = new User(0, "Eve", "eve@company.com", true);
        User extUser  = new User(0, "Frank", "frank@gmail.com", true);
        System.out.println("  save(corp email) = " + customRepo.save(corpUser));   // 99L
        System.out.println("  save(gmail)      = " + customRepo.save(extUser));    // null (no match)

        // Matcher precedence (most specific wins — order of stubbing matters)
        UserRepository orderedRepo = mock(UserRepository.class);
        when(orderedRepo.findById(anyLong())).thenReturn(new User(0L, "Any", "any@x.com", true));
        when(orderedRepo.findById(1L)).thenReturn(new User(1L, "Special", "special@x.com", true));

        System.out.println("\nStubbing specificity (last stub for matching args wins):");
        System.out.println("  findById(1)  = " + orderedRepo.findById(1));   // Special
        System.out.println("  findById(2)  = " + orderedRepo.findById(2));   // Any
        System.out.println("  findById(99) = " + orderedRepo.findById(99));  // Any

        // isNull / isNotNull
        UserRepository nullCheckRepo = mock(UserRepository.class);
        when(nullCheckRepo.save(isNull())).thenThrow(new NullPointerException("user is null"));
        when(nullCheckRepo.save(isNotNull())).thenReturn(alice);

        System.out.println("\nisNull / isNotNull:");
        System.out.println("  save(alice) = " + nullCheckRepo.save(alice));
        try {
            nullCheckRepo.save(null);
        } catch (NullPointerException e) {
            System.out.println("  save(null)  → " + e.getMessage());
        }
        System.out.println();
    }

    // =========================================================================
    // DEMO 6: ArgumentCaptor — capture & inspect call arguments
    // =========================================================================
    static void demo6_ArgumentCaptor() {
        System.out.println("--- Demo 6: ArgumentCaptor ---");

        System.out.println("""
            ArgumentCaptor captures the actual argument passed to a mock method.
            Use case: when you can't predict/pass the exact object (e.g., auto-generated ID,
            internally created object), but you want to assert its properties.
            """);

        UserRepository repo   = mock(UserRepository.class);
        EmailService   email  = mock(EmailService.class);
        AuditLogger    audit  = mock(AuditLogger.class);

        AtomicInteger idGen = new AtomicInteger(42);
        when(repo.existsByEmail(anyString())).thenReturn(false);
        when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return new User(idGen.incrementAndGet(), u.name(), u.email(), u.active());
        });

        UserService service = new UserService(repo, email, audit);
        service.registerUser("Grace", "grace@example.com");

        // Capture the User passed to repo.save()
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(repo).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        System.out.println("Captured User passed to repo.save():");
        System.out.println("  name   = " + capturedUser.name());
        System.out.println("  email  = " + capturedUser.email());
        System.out.println("  active = " + capturedUser.active());
        System.out.println("  id     = " + capturedUser.id() + " (0 = pre-save, id assigned by repo)");

        // Capture email arguments
        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nameCaptor  = ArgumentCaptor.forClass(String.class);
        verify(email).sendWelcome(emailCaptor.capture(), nameCaptor.capture());
        System.out.println("\nCaptured sendWelcome args:");
        System.out.println("  email = " + emailCaptor.getValue());
        System.out.println("  name  = " + nameCaptor.getValue());

        // Capture audit args
        ArgumentCaptor<String> actionCaptor  = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long>   userIdCaptor  = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> detailCaptor  = ArgumentCaptor.forClass(String.class);
        verify(audit).log(actionCaptor.capture(), userIdCaptor.capture(), detailCaptor.capture());
        System.out.println("\nCaptured audit.log args:");
        System.out.println("  action  = " + actionCaptor.getValue());
        System.out.println("  userId  = " + userIdCaptor.getValue());
        System.out.println("  details = " + detailCaptor.getValue());

        // Capture multiple calls
        System.out.println("\nCapture multiple calls example:");
        UserRepository multiRepo = mock(UserRepository.class);
        when(multiRepo.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return new User(idGen.incrementAndGet(), u.name(), u.email(), u.active());
        });
        multiRepo.save(new User(0, "Henry", "h@x.com", true));
        multiRepo.save(new User(0, "Iris",  "i@x.com", false));
        multiRepo.save(new User(0, "Jack",  "j@x.com", true));

        ArgumentCaptor<User> multiCaptor = ArgumentCaptor.forClass(User.class);
        verify(multiRepo, times(3)).save(multiCaptor.capture());
        System.out.println("  All captured saves:");
        multiCaptor.getAllValues().forEach(u ->
            System.out.println("    " + u.name() + " / " + u.email() + " / active=" + u.active()));
        System.out.println();
    }

    // =========================================================================
    // DEMO 7: Verification Strategies
    // =========================================================================
    static void demo7_VerificationStrategies() {
        System.out.println("--- Demo 7: Verification Strategies ---");

        System.out.println("""
            verify() checks HOW a mock was called — essential for testing side effects.
            Unlike assertions on return values, verify() checks interactions.
            """);

        UserRepository repo  = mock(UserRepository.class);
        EmailService   email = mock(EmailService.class);
        AuditLogger    audit = mock(AuditLogger.class);

        when(repo.existsByEmail(anyString())).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return new User(1L, u.name(), u.email(), u.active());
        });

        UserService service = new UserService(repo, email, audit);
        service.registerUser("Kate", "kate@example.com");

        // verify exactly once (default)
        verify(repo).existsByEmail("kate@example.com");
        System.out.println("✓ verify() — existsByEmail called exactly once");

        verify(repo, times(1)).save(any(User.class));
        System.out.println("✓ verify(times(1)) — save called once");

        verify(email, times(1)).sendWelcome(anyString(), anyString());
        System.out.println("✓ verify email.sendWelcome called once");

        verify(audit, times(1)).log(eq("REGISTER"), eq(1L), anyString());
        System.out.println("✓ verify audit.log called with REGISTER action");

        // never()
        verify(email, never()).sendPasswordReset(anyString(), anyString());
        System.out.println("✓ verify(never()) — sendPasswordReset never called");

        // atLeast / atMost
        UserRepository repoMulti = mock(UserRepository.class);
        when(repoMulti.findById(anyLong())).thenReturn(new User(1L, "X", "x@x.com", false));
        when(repoMulti.save(any())).thenAnswer(inv -> ((User) inv.getArgument(0)).withActive(true));

        service = new UserService(repoMulti, email, audit);
        service.activateUser(1L); // calls findById, then save

        verify(repoMulti, atLeast(1)).findById(anyLong());
        verify(repoMulti, atMost(2)).findById(anyLong());
        System.out.println("✓ verify(atLeast(1)) and verify(atMost(2))");

        // verifyNoMoreInteractions — ensure no unexpected calls
        UserRepository strictRepo = mock(UserRepository.class);
        when(strictRepo.existsByEmail(anyString())).thenReturn(false);
        when(strictRepo.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0); return new User(10L, u.name(), u.email(), u.active());
        });
        EmailService strictEmail = mock(EmailService.class);
        AuditLogger  strictAudit = mock(AuditLogger.class);

        UserService strictService = new UserService(strictRepo, strictEmail, strictAudit);
        strictService.registerUser("Leo", "leo@example.com");

        verify(strictRepo).existsByEmail("leo@example.com");
        verify(strictRepo).save(any());
        verify(strictEmail).sendWelcome(anyString(), anyString());
        verify(strictAudit).log(anyString(), anyLong(), anyString());
        verifyNoMoreInteractions(strictRepo, strictEmail, strictAudit);
        System.out.println("✓ verifyNoMoreInteractions — no unexpected calls detected");

        // verifyNoInteractions
        AuditLogger unusedAudit = mock(AuditLogger.class);
        verifyNoInteractions(unusedAudit);
        System.out.println("✓ verifyNoInteractions — mock was never called");

        System.out.println();
    }

    // =========================================================================
    // DEMO 8: InOrder Verification
    // =========================================================================
    static void demo8_InOrderVerification() {
        System.out.println("--- Demo 8: InOrder Verification ---");

        System.out.println("""
            InOrder ensures methods were called in a specific sequence.
            Critical for: workflow validation, state machine transitions,
            audit trails, and protocol compliance testing.
            """);

        UserRepository repo  = mock(UserRepository.class);
        EmailService   email = mock(EmailService.class);
        AuditLogger    audit = mock(AuditLogger.class);

        when(repo.existsByEmail(anyString())).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return new User(1L, u.name(), u.email(), u.active());
        });

        UserService service = new UserService(repo, email, audit);
        service.registerUser("Mia", "mia@example.com");

        // Verify ORDER: existsByEmail → save → sendWelcome → audit.log
        InOrder inOrder = inOrder(repo, email, audit);
        inOrder.verify(repo).existsByEmail("mia@example.com");
        inOrder.verify(repo).save(any(User.class));
        inOrder.verify(email).sendWelcome(eq("mia@example.com"), eq("Mia"));
        inOrder.verify(audit).log(eq("REGISTER"), eq(1L), anyString());
        System.out.println("✓ InOrder: existsByEmail → save → sendWelcome → audit.log");

        // Delete workflow order
        UserRepository delRepo  = mock(UserRepository.class);
        AuditLogger    delAudit = mock(AuditLogger.class);
        User target = new User(5L, "Noah", "noah@example.com", true);
        when(delRepo.findById(5L)).thenReturn(target);

        UserService delService = new UserService(delRepo, mock(EmailService.class), delAudit);
        delService.deleteUser(5L);

        InOrder delOrder = inOrder(delRepo, delAudit);
        delOrder.verify(delRepo).findById(5L);        // find first
        delOrder.verify(delRepo).delete(5L);          // then delete
        delOrder.verify(delAudit).log(eq("DELETE"), eq(5L), anyString()); // then audit
        System.out.println("✓ InOrder delete: findById → delete → audit.log");

        // Show InOrder across different mocks (payment scenario)
        System.out.println("\nPayment workflow InOrder:");
        PaymentGateway gateway = mock(PaymentGateway.class);
        AuditLogger payAudit   = mock(AuditLogger.class);

        when(gateway.charge(anyString(), anyLong()))
            .thenReturn(PaymentResult.ok("TXN-001"));

        // Simulate payment service
        PaymentResult result = gateway.charge("tok_abc123", 9999L);
        payAudit.log("PAYMENT", 1L, "txn=" + result.transactionId() + " amount=9999");

        InOrder payOrder = inOrder(gateway, payAudit);
        payOrder.verify(gateway).charge(eq("tok_abc123"), eq(9999L));
        payOrder.verify(payAudit).log(eq("PAYMENT"), eq(1L), contains("TXN-001"));
        System.out.println("✓ InOrder payment: charge → audit (txn logged after charge)");
        System.out.println();
    }

    // =========================================================================
    // DEMO 9: Test Design Principles — what to mock, what not to
    // =========================================================================
    static void demo9_TestDesignPrinciples() {
        System.out.println("--- Demo 9: Test Design Principles (SA Perspective) ---");

        System.out.println("""
            ── What TO Mock ───────────────────────────────────────────────────
            ✅ External systems: DB, HTTP APIs, message queues, email servers
            ✅ Slow resources: anything that makes tests take > 100ms
            ✅ Non-deterministic: clocks, random, UUIDs, system properties
            ✅ System boundaries you don't own: payment gateways, OAuth providers
            ✅ Side effects you can't easily verify: email sent, SMS sent

            ── What NOT to Mock ───────────────────────────────────────────────
            ❌ The class under test itself (obvious but happens)
            ❌ Value objects / domain entities (just create them)
            ❌ Pure functions / utility classes (just call them)
            ❌ Simple data containers (Map, List, String)
            ❌ Things that have good fakes (prefer InMemoryRepository over mock)

            ── Mock vs Fake vs Real decision tree ────────────────────────────
            Is it a DB/external?
              Yes → Fake (InMemoryRepo) for unit, real for integration
            Is it slow/nondeterministic?
              Yes → Mock with thenReturn(fixedValue)
            Do you need to verify it was called?
              Yes → Mock (verify())
            Does it have complex behaviour you need?
              Yes → Fake or real object
            Otherwise → Use real object

            ── Test Pyramid ───────────────────────────────────────────────────
                    ┌──────────────────────┐
                    │   E2E / System  (5%) │  ← Selenium, real services
                    ├──────────────────────┤
                    │ Integration    (20%) │  ← TestContainers, real DB
                    ├──────────────────────┤
                    │ Unit          (75%)  │  ← Mockito, fast, isolated
                    └──────────────────────┘

            ── Mockito Anti-patterns ──────────────────────────────────────────
            ❌ Mocking the class under test:
               UserService svc = spy(new UserService(...));
               doReturn(user).when(svc).registerUser(...)  ← testing nothing!

            ❌ Over-stubbing: stubbing methods that aren't called in the test
               when(repo.findAll()).thenReturn(...)   ← never used → noise

            ❌ Asserting on mock return values (not behaviour):
               User u = mock(User.class);
               when(u.name()).thenReturn("Alice");
               assertEquals("Alice", u.name());   ← testing Mockito, not your code!

            ❌ Brittle verify() on internal implementation:
               verify(repo, times(2)).findById(...)  ← breaks if impl changes to cache

            ❌ Testing that constructors were called (too implementation-specific)

            ── Strict Stubbing (Mockito 2+ default with JUnit 5 Extension) ───
            @ExtendWith(MockitoExtension.class)   ← enables strict mode
            → UnnecessaryStubbingException if you stub something not called
            → Detects unused stubs early, keeps tests clean
            """);

        // Demonstrate: test with InMemoryRepository vs full mock
        System.out.println("Comparison: Mock vs Fake for UserService.registerUser()");

        // Approach 1: All mocks (verifies interactions)
        System.out.println("\n  Approach 1 — All mocks (interaction-focused):");
        {
            UserRepository repo  = mock(UserRepository.class);
            EmailService   email = mock(EmailService.class);
            AuditLogger    audit = mock(AuditLogger.class);
            when(repo.existsByEmail(anyString())).thenReturn(false);
            when(repo.save(any())).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                return new User(1L, u.name(), u.email(), u.active());
            });

            UserService svc = new UserService(repo, email, audit);
            User result = svc.registerUser("Olivia", "olivia@example.com");

            System.out.println("    result.id() = " + result.id());
            verify(email).sendWelcome(eq("olivia@example.com"), eq("Olivia"));
            verify(audit).log(eq("REGISTER"), eq(1L), anyString());
            System.out.println("    ✓ Email sent, audit logged");
        }

        // Approach 2: Fake repo, real objects (state-focused)
        System.out.println("\n  Approach 2 — Fake repo + mock emails (state-focused):");
        {
            UserRepository fakeRepo = new InMemoryUserRepository();
            EmailService   email    = mock(EmailService.class);
            AuditLogger    audit    = mock(AuditLogger.class);

            UserService svc = new UserService(fakeRepo, email, audit);
            User result = svc.registerUser("Paula", "paula@example.com");

            System.out.println("    result.id()    = " + result.id() + " (real sequence)");
            System.out.println("    findById works = " + fakeRepo.findById(result.id()).name());
            System.out.println("    findAll().size = " + fakeRepo.findAll().size());

            // Test duplicate email
            try {
                svc.registerUser("Paula2", "paula@example.com");
                System.out.println("    ❌ Should have thrown!");
            } catch (IllegalArgumentException e) {
                System.out.println("    ✓ Duplicate email blocked: " + e.getMessage());
            }
        }

        System.out.println("""

            SA-Level Testing Strategy:

            1. Domain logic → pure unit tests, no mocks (value objects, entities)
            2. Use cases / services → mock external deps, fake internal repos
            3. Adapters / repositories → integration test with TestContainers
            4. Controllers / APIs → slice test (Spring @WebMvcTest) or E2E

            The goal of testing is CONFIDENCE, not coverage %.
            A 95% coverage with brittle, over-mocked tests gives false confidence.
            A 70% coverage with well-designed tests catches real regressions.
            """);
    }
}
