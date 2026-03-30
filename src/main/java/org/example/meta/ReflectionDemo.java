package org.example.meta;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * =============================================================================
 * BÀI 7.1 — Reflection & Dynamic Proxy
 * =============================================================================
 *
 * REFLECTION = khả năng inspect và modify program structure AT RUNTIME.
 *   • Đọc class metadata: fields, methods, constructors, annotations
 *   • Tạo object, gọi method, đọc/ghi field mà không biết type lúc compile
 *   • Nền tảng của: Spring DI, Hibernate ORM, Jackson JSON, JUnit
 *
 * JDK DYNAMIC PROXY = tạo proxy object AT RUNTIME implement bất kỳ interface nào.
 *   • Proxy.newProxyInstance() → tạo class mới trong memory
 *   • InvocationHandler.invoke() → intercept mọi method call
 *   • Spring AOP (interface-based) dùng JDK Proxy
 *   • Spring AOP (class-based) dùng CGLIB (subclass proxy)
 *
 * HIỆU NĂNG:
 *   Direct call:         ~1ns
 *   Reflection invoke:   ~100ns (100x slower — JIT có thể optimize sau warmup)
 *   Dynamic Proxy:       ~200ns (InvocationHandler overhead)
 *   → Không dùng reflection trong tight loop; OK cho init/framework code
 *
 * JAVA 9+ MODULE SYSTEM:
 *   setAccessible(true) bị chặn nếu module không export package
 *   → Cần --add-opens flag hoặc module-info.java config
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.meta.ReflectionDemo"
 */
public class ReflectionDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 7.1 — Reflection & Dynamic Proxy");
        System.out.println("=".repeat(70));
        System.out.println();

        demo1_classIntrospection();
        demo2_fieldAccess();
        demo3_methodInvocation();
        demo4_constructorAndInstantiation();
        demo5_annotationReading();
        demo6_genericTypeInfo();
        demo7_jdkDynamicProxy();
        demo8_proxyPatterns();
        demo9_reflectionPerformance();
        printSAInsights();
    }

    // =========================================================================
    // Sample domain classes for demos
    // =========================================================================

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    @interface Audited {
        String value() default "system";
        boolean logArgs() default true;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface NotNull {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Column {
        String name() default "";
        int length() default 255;
    }

    @Audited("admin")
    static class UserService {
        @NotNull
        @Column(name = "user_name", length = 100)
        private String username;

        private int age;
        public static int instanceCount = 0;

        public UserService(String username, int age) {
            this.username = username;
            this.age = age;
            instanceCount++;
        }

        // Default constructor for reflection demo
        public UserService() {
            this("default", 0);
        }

        @Audited(logArgs = false)
        public String findById(int id) {
            return "User#" + id + ":" + username;
        }

        public boolean save(String name, int age) {
            return name != null && age > 0;
        }

        private String internalHelper(String data) {
            return "internal:" + data;
        }

        @Override
        public String toString() {
            return "UserService{username='" + username + "', age=" + age + "}";
        }
    }

    interface Repository<T, ID> {
        T findById(ID id);
        boolean save(T entity);
        void delete(ID id);
    }

    interface CacheAware {
        void clearCache();
    }

    // =========================================================================
    // DEMO 1 — Class Introspection
    // =========================================================================
    static void demo1_classIntrospection() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 1 — Class Introspection: Metadata at Runtime");
        System.out.println("─".repeat(70));

        Class<?> clazz = UserService.class;

        System.out.println("\n  [Basic Class Info]");
        System.out.printf("  getName():           %s%n", clazz.getName());
        System.out.printf("  getSimpleName():     %s%n", clazz.getSimpleName());
        System.out.printf("  getPackageName():    %s%n", clazz.getPackageName());
        System.out.printf("  getSuperclass():     %s%n", clazz.getSuperclass());
        System.out.printf("  isInterface():       %b%n", clazz.isInterface());
        System.out.printf("  isEnum():            %b%n", clazz.isEnum());
        System.out.printf("  isRecord():          %b%n", clazz.isRecord());
        System.out.printf("  isAnnotation():      %b%n", clazz.isAnnotation());
        System.out.printf("  getModifiers():      %s%n",
            Modifier.toString(clazz.getModifiers()));

        System.out.println("\n  [Interfaces implemented]");
        Arrays.stream(clazz.getInterfaces())
            .forEach(i -> System.out.println("    implements " + i.getSimpleName()));
        if (clazz.getInterfaces().length == 0)
            System.out.println("    (none)");

        System.out.println("\n  [Fields — getDeclaredFields() vs getFields()]");
        System.out.println("    getDeclaredFields() — all declared (including private), no inherited:");
        for (Field f : clazz.getDeclaredFields()) {
            System.out.printf("      %-10s %-15s %s%n",
                Modifier.toString(f.getModifiers()), f.getType().getSimpleName(), f.getName());
        }
        System.out.println("    getFields() — public only, includes inherited:");
        for (Field f : clazz.getFields()) {
            System.out.printf("      %-10s %-15s %s%n",
                Modifier.toString(f.getModifiers()), f.getType().getSimpleName(), f.getName());
        }

        System.out.println("\n  [Methods — getDeclaredMethods()]");
        for (Method m : clazz.getDeclaredMethods()) {
            String params = Arrays.stream(m.getParameterTypes())
                .map(Class::getSimpleName)
                .reduce((a, b) -> a + ", " + b).orElse("");
            System.out.printf("    %-10s %s %s(%s)%n",
                Modifier.toString(m.getModifiers()),
                m.getReturnType().getSimpleName(),
                m.getName(), params);
        }

        System.out.println("\n  [Constructors]");
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            String params = Arrays.stream(c.getParameterTypes())
                .map(Class::getSimpleName)
                .reduce((a, b) -> a + ", " + b).orElse("");
            System.out.printf("    %s(%s)%n", clazz.getSimpleName(), params);
        }

        // Class object from different sources
        System.out.println("\n  [Obtaining Class object — 3 ways]");
        Class<?> fromLiteral  = UserService.class;          // compile-time
        Class<?> fromInstance = new UserService().getClass(); // runtime
        Class<?> fromForName  = Class.forName(
            "org.example.meta.ReflectionDemo$UserService"); // dynamic loading

        System.out.println("    fromLiteral == fromInstance: " + (fromLiteral == fromInstance));
        System.out.println("    fromLiteral == fromForName:  " + (fromLiteral == fromForName));
        System.out.println("    (all three return same Class object — cached by JVM)");
    }

    // =========================================================================
    // DEMO 2 — Field Access: read/write including private
    // =========================================================================
    static void demo2_fieldAccess() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 2 — Field Access: Read/Write Including Private Fields");
        System.out.println("─".repeat(70));

        System.out.println("""

            setAccessible(true) — bypass Java access control (private/protected).
            Java 9+: blocked by module system unless --add-opens specified.
            Legitimate use: frameworks, serialization, testing.
            """);

        UserService svc = new UserService("alice", 30);
        Class<?> clazz = svc.getClass();

        // Read private field
        System.out.println("  [Read private field 'username']");
        Field usernameField = clazz.getDeclaredField("username");
        usernameField.setAccessible(true);   // bypass private access
        String currentUsername = (String) usernameField.get(svc);
        System.out.printf("    Current username: %s%n", currentUsername);

        // Write private field
        System.out.println("  [Write private field 'username']");
        usernameField.set(svc, "bob");
        System.out.printf("    After set: %s%n", usernameField.get(svc));
        System.out.printf("    Object state: %s%n", svc);

        // Read static field
        System.out.println("\n  [Read static field 'instanceCount']");
        Field staticField = clazz.getDeclaredField("instanceCount");
        System.out.printf("    instanceCount = %d%n", staticField.get(null)); // null for static

        // Field type information
        System.out.println("\n  [Field metadata]");
        for (Field f : clazz.getDeclaredFields()) {
            f.setAccessible(true);
            System.out.printf("    %-15s | type=%-10s | static=%-5b | value=%s%n",
                f.getName(),
                f.getType().getSimpleName(),
                Modifier.isStatic(f.getModifiers()),
                f.get(Modifier.isStatic(f.getModifiers()) ? null : svc));
        }

        // Practical pattern: copy fields between objects (simple object mapper)
        System.out.println("\n  [Shallow field copy — basis of object mappers]");
        UserService source = new UserService("charlie", 25);
        UserService target = new UserService();

        for (Field f : source.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            f.set(target, f.get(source));
        }
        System.out.printf("    Source: %s%n", source);
        System.out.printf("    Target (after copy): %s%n", target);
    }

    // =========================================================================
    // DEMO 3 — Method Invocation
    // =========================================================================
    static void demo3_methodInvocation() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 3 — Method Invocation: Dynamic Method Calls");
        System.out.println("─".repeat(70));

        UserService svc = new UserService("diana", 28);
        Class<?> clazz = svc.getClass();

        // Invoke public method by name
        System.out.println("  [Invoke public method by name]");
        Method findById = clazz.getDeclaredMethod("findById", int.class);
        Object result = findById.invoke(svc, 42);
        System.out.printf("    findById(42) = \"%s\"%n", result);

        // Invoke private method
        System.out.println("\n  [Invoke private method]");
        Method helper = clazz.getDeclaredMethod("internalHelper", String.class);
        helper.setAccessible(true);
        Object helperResult = helper.invoke(svc, "test-data");
        System.out.printf("    internalHelper(\"test-data\") = \"%s\"%n", helperResult);

        // invoke with multiple params
        System.out.println("\n  [Invoke with multiple parameters]");
        Method save = clazz.getDeclaredMethod("save", String.class, int.class);
        Object saved = save.invoke(svc, "newUser", 22);
        System.out.printf("    save(\"newUser\", 22) = %s%n", saved);

        // Invoke static method
        System.out.println("\n  [Invoke static method via reflection]");
        Method toStr = String.class.getMethod("valueOf", int.class);
        Object str = toStr.invoke(null, 12345);  // null for static
        System.out.printf("    String.valueOf(12345) = \"%s\"%n", str);

        // Method lookup strategies
        System.out.println("\n  [Method lookup: getMethod vs getDeclaredMethod]");
        System.out.println("    getMethod('toString'):        " +
            clazz.getMethod("toString").getDeclaringClass().getSimpleName() +
            " (includes inherited public)");
        System.out.println("    getDeclaredMethod('toString'): " +
            clazz.getDeclaredMethod("toString").getDeclaringClass().getSimpleName() +
            " (this class only)");

        // Exception handling in reflection
        System.out.println("\n  [Exception wrapping in reflection]");
        Method saveMethod = clazz.getDeclaredMethod("save", String.class, int.class);
        try {
            saveMethod.invoke(svc, null, -1); // will return false, no exception here
        } catch (InvocationTargetException e) {
            // Actual exception thrown by method is WRAPPED in InvocationTargetException
            System.out.println("    InvocationTargetException wraps: "
                + e.getCause().getClass().getSimpleName());
            System.out.println("    Unwrap with: e.getCause()");
        }

        // MethodHandle — faster alternative to reflection (Java 7+)
        System.out.println("\n  [MethodHandle — faster alternative to Method.invoke()]");
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType type = MethodType.methodType(String.class, int.class);
        MethodHandle mh = lookup.findVirtual(UserService.class, "findById", type);
        String mhResult = (String) mh.invoke(svc, 99);
        System.out.printf("    MethodHandle invoke: \"%s\"%n", mhResult);
        System.out.println("    MethodHandle: JIT-friendly (can inline), faster than Method.invoke");
    }

    // =========================================================================
    // DEMO 4 — Constructor & Instantiation
    // =========================================================================
    static void demo4_constructorAndInstantiation() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 4 — Constructor & Dynamic Instantiation");
        System.out.println("─".repeat(70));

        System.out.println("""

            Use cases: plugin system, DI container, deserialization, factory patterns.
            """);

        // Default constructor
        System.out.println("  [newInstance via default constructor]");
        Constructor<?> defaultCtor = UserService.class.getDeclaredConstructor();
        UserService instance1 = (UserService) defaultCtor.newInstance();
        System.out.printf("    Created: %s%n", instance1);

        // Parameterized constructor
        System.out.println("\n  [newInstance via parameterized constructor]");
        Constructor<?> paramCtor = UserService.class
            .getDeclaredConstructor(String.class, int.class);
        UserService instance2 = (UserService) paramCtor.newInstance("eve", 35);
        System.out.printf("    Created: %s%n", instance2);

        // Class.forName + newInstance — plugin loading pattern
        System.out.println("\n  [Class.forName — dynamic class loading (plugin pattern)]");
        String className = "org.example.meta.ReflectionDemo$UserService";
        Class<?> loaded = Class.forName(className);
        Constructor<?> ctor = loaded.getDeclaredConstructor(String.class, int.class);
        Object plugin = ctor.newInstance("plugin-user", 1);
        System.out.printf("    Dynamic load + instantiate: %s%n", plugin);

        // Array creation via reflection
        System.out.println("\n  [Array creation via reflection]");
        Object arr = Array.newInstance(String.class, 5);
        Array.set(arr, 0, "first");
        Array.set(arr, 4, "last");
        System.out.printf("    String[5]: [0]=%s, [4]=%s, length=%d%n",
            Array.get(arr, 0), Array.get(arr, 4), Array.getLength(arr));

        // Simple DI container — practical reflection use
        System.out.println("\n  [Simple DI Container via reflection]");
        SimpleContainer container = new SimpleContainer();
        container.register(UserService.class);
        UserService resolved = container.resolve(UserService.class);
        System.out.printf("    Resolved: %s%n", resolved);
    }

    // Simple DI container implementation
    static class SimpleContainer {
        private final Map<Class<?>, Object> singletons = new HashMap<>();

        void register(Class<?> clazz) throws Exception {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            singletons.put(clazz, ctor.newInstance());
        }

        @SuppressWarnings("unchecked")
        <T> T resolve(Class<T> clazz) {
            return (T) singletons.get(clazz);
        }
    }

    // =========================================================================
    // DEMO 5 — Annotation Reading & Processing
    // =========================================================================
    static void demo5_annotationReading() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 5 — Annotation Reading: Runtime Metadata Processing");
        System.out.println("─".repeat(70));

        System.out.println("""

            Annotations with @Retention(RUNTIME) are visible via reflection.
            Foundation of: Spring (@Component, @Autowired), JPA (@Entity, @Column),
            Bean Validation (@NotNull, @Size), JUnit (@Test, @BeforeEach).
            """);

        Class<?> clazz = UserService.class;

        // Class-level annotation
        System.out.println("  [Class-level @Audited annotation]");
        Audited classAudit = clazz.getAnnotation(Audited.class);
        if (classAudit != null) {
            System.out.printf("    @Audited value='%s', logArgs=%b%n",
                classAudit.value(), classAudit.logArgs());
        }

        // Field-level annotations
        System.out.println("\n  [Field-level annotations]");
        for (Field f : clazz.getDeclaredFields()) {
            if (f.isAnnotationPresent(NotNull.class)) {
                System.out.printf("    @NotNull on field: %s%n", f.getName());
            }
            Column col = f.getAnnotation(Column.class);
            if (col != null) {
                System.out.printf("    @Column on '%s': name='%s', length=%d%n",
                    f.getName(), col.name().isEmpty() ? f.getName() : col.name(), col.length());
            }
        }

        // Method-level annotations
        System.out.println("\n  [Method-level annotations]");
        for (Method m : clazz.getDeclaredMethods()) {
            Audited methodAudit = m.getAnnotation(Audited.class);
            if (methodAudit != null) {
                System.out.printf("    @Audited on method '%s': logArgs=%b%n",
                    m.getName(), methodAudit.logArgs());
            }
        }

        // Practical: simple validator using annotations
        System.out.println("\n  [Annotation-driven validator (like Bean Validation)]");
        UserService valid   = new UserService("frank", 30);
        UserService invalid = new UserService();

        // Set username to null via reflection to trigger validation
        Field usernameField = clazz.getDeclaredField("username");
        usernameField.setAccessible(true);
        usernameField.set(invalid, null);

        System.out.printf("    validate(%s): %s%n",
            "valid", validateObject(valid) ? "PASS" : "FAIL");
        System.out.printf("    validate(%s): %s%n",
            "invalid (null username)", validateObject(invalid) ? "PASS" : "FAIL");

        // Annotation on parameters
        System.out.println("\n  [Parameter annotations (basis of @RequestParam, @PathVariable)]");
        Method m = clazz.getDeclaredMethod("findById", int.class);
        Parameter[] params = m.getParameters();
        for (Parameter p : params) {
            System.out.printf("    param: %-10s type=%-10s annotatedWith=%s%n",
                p.getName(), p.getType().getSimpleName(),
                Arrays.toString(p.getAnnotations()));
        }
    }

    static boolean validateObject(Object obj) throws Exception {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.isAnnotationPresent(NotNull.class)) {
                f.setAccessible(true);
                if (f.get(obj) == null) return false;
            }
        }
        return true;
    }

    // =========================================================================
    // DEMO 6 — Generic Type Information at Runtime
    // =========================================================================
    static void demo6_genericTypeInfo() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6 — Generic Type Information at Runtime");
        System.out.println("─".repeat(70));

        System.out.println("""

            TYPE ERASURE: generics erased at compile time — List<String> becomes List at runtime.
            BUT: superclass generic type, field generic type, method return type SURVIVE erasure.
            Jackson/Gson dùng trick này để deserialize List<User> correctly.
            """);

        // Generic type on field
        class Container {
            List<String>         names    = new ArrayList<>();
            Map<String, Integer> scores   = new HashMap<>();
            Optional<UserService> optUser = Optional.empty();
        }

        System.out.println("  [Generic field types via ParameterizedType]");
        for (Field f : Container.class.getDeclaredFields()) {
            Type genericType = f.getGenericType();
            System.out.printf("    %-20s → %s%n", f.getName(), genericType.getTypeName());

            if (genericType instanceof ParameterizedType pt) {
                System.out.printf("      rawType:  %s%n", pt.getRawType().getTypeName());
                for (Type arg : pt.getActualTypeArguments()) {
                    System.out.printf("      typeArg:  %s%n", arg.getTypeName());
                }
            }
        }

        // Generic superclass — the TypeToken trick
        System.out.println("\n  [TypeToken trick — capture generic type at compile time]");
        // Jackson/Gson/Spring use this to preserve generic type info:
        abstract class TypeToken<T> {
            final Type captured;
            TypeToken() {
                // Superclass generic type survives erasure!
                this.captured = ((ParameterizedType)
                    getClass().getGenericSuperclass()).getActualTypeArguments()[0];
            }
        }

        TypeToken<List<UserService>> token = new TypeToken<>() {};
        System.out.printf("    TypeToken captured: %s%n", token.captured.getTypeName());

        // Generic method return type
        System.out.println("\n  [Generic method return types]");
        for (Method m : Repository.class.getMethods()) {
            System.out.printf("    %-20s → return: %s%n",
                m.getName(), m.getGenericReturnType().getTypeName());
        }

        // Type variables on class
        System.out.println("\n  [Type parameters on class declaration]");
        for (TypeVariable<?> tv : Repository.class.getTypeParameters()) {
            System.out.printf("    TypeVariable: %s%n", tv.getName());
        }
    }

    // =========================================================================
    // DEMO 7 — JDK Dynamic Proxy
    // =========================================================================
    static void demo7_jdkDynamicProxy() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 7 — JDK Dynamic Proxy: Runtime Interface Implementation");
        System.out.println("─".repeat(70));

        System.out.println("""

            JDK Dynamic Proxy:
              • Proxy.newProxyInstance(ClassLoader, Class<?>[], InvocationHandler)
              • Creates a NEW CLASS at runtime implementing the given interfaces
              • All method calls → routed through InvocationHandler.invoke()
              • Requires target to implement an INTERFACE (not class!)

            Spring AOP: uses JDK Proxy for @Transactional, @Cacheable on interfaces
                        uses CGLIB for classes without interfaces (subclass proxy)
            """);

        // Real implementation
        Repository<String, Integer> realRepo = new Repository<>() {
            @Override public String  findById(Integer id) { return "User#" + id; }
            @Override public boolean save(String entity)  { System.out.println("    [DB] saving: " + entity); return true; }
            @Override public void    delete(Integer id)   { System.out.println("    [DB] deleting: " + id); }
        };

        // Logging InvocationHandler
        InvocationHandler loggingHandler = (proxy, method, methodArgs) -> {
            System.out.printf("    → [LOG] %s(%s)%n",
                method.getName(),
                methodArgs != null ? Arrays.toString(methodArgs) : "");
            long start = System.nanoTime();
            Object result = method.invoke(realRepo, methodArgs);
            System.out.printf("    ← [LOG] %s returned %s in %.2fms%n",
                method.getName(), result,
                (System.nanoTime() - start) / 1_000_000.0);
            return result;
        };

        // Create proxy
        @SuppressWarnings("unchecked")
        Repository<String, Integer> proxy = (Repository<String, Integer>)
            Proxy.newProxyInstance(
                Repository.class.getClassLoader(),
                new Class<?>[]{ Repository.class },
                loggingHandler
            );

        System.out.println("  [Calling methods on proxy]");
        proxy.findById(42);
        proxy.save("Alice");
        proxy.delete(99);

        // Proxy class info
        System.out.printf("%n  proxy.getClass():    %s%n", proxy.getClass().getName());
        System.out.printf("  isProxyClass:        %b%n",
            Proxy.isProxyClass(proxy.getClass()));
        System.out.printf("  getInvocationHandler: %s%n",
            Proxy.getInvocationHandler(proxy).getClass().getSimpleName());

        // Multi-interface proxy
        System.out.println("\n  [Multi-interface proxy — implements 2 interfaces]");
        Object multiProxy = Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[]{ Repository.class, CacheAware.class },
            (p, method, mArgs) -> {
                if (method.getName().equals("clearCache")) {
                    System.out.println("    [CACHE] clearing cache");
                    return null;
                }
                return method.invoke(realRepo, mArgs);
            }
        );

        ((Repository<?, ?>) multiProxy).findById(1);
        ((CacheAware) multiProxy).clearCache();
    }

    // =========================================================================
    // DEMO 8 — Proxy Patterns: Timing, Retry, Circuit Breaker
    // =========================================================================
    static void demo8_proxyPatterns() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 8 — Proxy Patterns: Timing, Retry, Transaction");
        System.out.println("─".repeat(70));

        System.out.println("""

            Proxy pattern = intercept method calls to add cross-cutting concerns:
              • Timing / Metrics (Micrometer @Timed)
              • Retry (@Retryable)
              • Transaction boundary (@Transactional)
              • Security check (@PreAuthorize)
              • Caching (@Cacheable)
            """);

        // Generic proxy factory
        System.out.println("  [Timing Proxy]");

        interface OrderService {
            String processOrder(int orderId);
            boolean cancelOrder(int orderId);
        }

        OrderService realService = new OrderService() {
            @Override public String  processOrder(int id) {
                sleepMs(30); return "processed-" + id;
            }
            @Override public boolean cancelOrder(int id) {
                sleepMs(15); return true;
            }
        };

        // Timing proxy
        Map<String, Long> timings = new ConcurrentHashMap<>();
        OrderService timedService = createProxy(OrderService.class, realService,
            (method, args, proceed) -> {
                long start = System.nanoTime();
                Object result = proceed.get();
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                timings.put(method.getName(), elapsed);
                System.out.printf("    [TIMER] %s took %dms%n", method.getName(), elapsed);
                return result;
            });

        timedService.processOrder(1);
        timedService.cancelOrder(2);
        System.out.println("    Captured timings: " + timings);

        // Retry proxy
        System.out.println("\n  [Retry Proxy — retries on exception up to 3 times]");
        AtomicInteger callCount = new AtomicInteger(0);
        OrderService flakyService = new OrderService() {
            @Override public String processOrder(int id) {
                int call = callCount.incrementAndGet();
                if (call < 3) throw new RuntimeException("Transient failure #" + call);
                return "success-on-attempt-" + call;
            }
            @Override public boolean cancelOrder(int id) { return true; }
        };

        OrderService retryService = createProxy(OrderService.class, flakyService,
            (method, args, proceed) -> {
                int maxRetries = 3;
                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    try {
                        return proceed.get();
                    } catch (Exception e) {
                        if (attempt == maxRetries) throw e;
                        System.out.printf("    [RETRY] attempt %d failed: %s%n",
                            attempt, e.getMessage());
                        sleepMs(20);
                    }
                }
                throw new RuntimeException("unreachable");
            });

        String retryResult = retryService.processOrder(10);
        System.out.printf("    Final result: %s%n", retryResult);

        // Transaction proxy
        System.out.println("\n  [Transaction Proxy — commit / rollback boundary]");
        OrderService txService = createProxy(OrderService.class, realService,
            (method, args, proceed) -> {
                System.out.printf("    [TX] BEGIN transaction for %s%n", method.getName());
                try {
                    Object result = proceed.get();
                    System.out.printf("    [TX] COMMIT — %s succeeded%n", method.getName());
                    return result;
                } catch (Exception e) {
                    System.out.printf("    [TX] ROLLBACK — %s failed: %s%n",
                        method.getName(), e.getMessage());
                    throw e;
                }
            });

        txService.processOrder(3);
    }

    // Generic interceptor interface
    @FunctionalInterface
    interface Interceptor {
        Object intercept(Method method, Object[] args,
                         ThrowingSupplier<Object> proceed) throws Exception;
    }

    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @SuppressWarnings("unchecked")
    static <T> T createProxy(Class<T> iface, T target, Interceptor interceptor) {
        return (T) Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[]{ iface },
            (proxy, method, args) ->
                interceptor.intercept(method, args, () -> method.invoke(target, args))
        );
    }

    static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================================
    // DEMO 9 — Reflection Performance
    // =========================================================================
    static void demo9_reflectionPerformance() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 9 — Reflection Performance: Direct vs Reflect vs MethodHandle");
        System.out.println("─".repeat(70));

        System.out.println("""

            Reflection cost breakdown:
              Method.getDeclaredMethod()  — expensive (string search) → CACHE the Method object
              method.setAccessible(true)  — security check → do once
              method.invoke(obj, args)    — ~100ns overhead (autoboxing + dispatch)
              MethodHandle.invoke()       — JIT-inlineable → approaches direct call speed
            """);

        UserService svc = new UserService("perf-user", 1);
        Method method = UserService.class.getDeclaredMethod("findById", int.class);
        method.setAccessible(true);

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle mh = lookup.findVirtual(UserService.class, "findById",
            MethodType.methodType(String.class, int.class));

        int WARMUP = 10_000;
        int MEASURE = 200_000;

        // Warm up JIT
        for (int i = 0; i < WARMUP; i++) {
            svc.findById(i);
            method.invoke(svc, i);
            mh.invoke(svc, i);
        }

        // Direct call
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) svc.findById(i);
        long directNs = (System.nanoTime() - start) / MEASURE;

        // Method.invoke
        start = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) method.invoke(svc, i);
        long reflectNs = (System.nanoTime() - start) / MEASURE;

        // MethodHandle.invoke
        start = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) mh.invoke(svc, i);
        long mhNs = (System.nanoTime() - start) / MEASURE;

        // getDeclaredMethod every time (anti-pattern)
        start = System.nanoTime();
        int LOOKUP_MEASURE = 5_000;
        for (int i = 0; i < LOOKUP_MEASURE; i++)
            UserService.class.getDeclaredMethod("findById", int.class).invoke(svc, i);
        long lookupNs = (System.nanoTime() - start) / LOOKUP_MEASURE;

        System.out.printf("  Direct call:                  %,3d ns/op (baseline)%n", directNs);
        System.out.printf("  Cached Method.invoke():       %,3d ns/op (%.1fx overhead)%n",
            reflectNs, (double) reflectNs / Math.max(directNs, 1));
        System.out.printf("  Cached MethodHandle.invoke(): %,3d ns/op (%.1fx overhead)%n",
            mhNs, (double) mhNs / Math.max(directNs, 1));
        System.out.printf("  getDeclaredMethod each call:  %,3d ns/op (%.1fx overhead — AVOID!)%n",
            lookupNs, (double) lookupNs / Math.max(directNs, 1));

        System.out.println("""
            PERFORMANCE RULES:
              ✓ Cache Method/Field objects — lookup is expensive (string + search)
              ✓ Call setAccessible(true) ONCE — not per invocation
              ✓ Use MethodHandle for hot paths (JIT can inline)
              ✓ Java 21+: MethodHandles + invokedynamic approach in frameworks
              ✓ For extreme performance: code generation (ByteBuddy, ASM)
            """);
    }

    // =========================================================================
    // SA Insights
    // =========================================================================
    static void printSAInsights() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 7.1 — Reflection & Dynamic Proxy Insights");
        System.out.println("=".repeat(70));
        System.out.println("""

            REFLECTION USE CASE MAP:
            ┌────────────────────────────────┬─────────────────────────────────────┐
            │ Use Case                       │ Reflection API                       │
            ├────────────────────────────────┼─────────────────────────────────────┤
            │ DI container                   │ getDeclaredConstructor + newInstance │
            │ ORM mapping (@Column)          │ getDeclaredFields + getAnnotation    │
            │ JSON serialize/deserialize     │ getDeclaredFields + get/set          │
            │ Bean Validation (@NotNull)     │ getDeclaredFields + getAnnotation    │
            │ Test framework (@Test)         │ getDeclaredMethods + getAnnotation   │
            │ Plugin loading                 │ Class.forName + newInstance           │
            │ Spring AOP proxy               │ Proxy.newProxyInstance               │
            │ Generic type capture           │ getGenericSuperclass TypeToken trick │
            └────────────────────────────────┴─────────────────────────────────────┘

            DYNAMIC PROXY INTERCEPT PATTERNS:
            ┌──────────────────────┬──────────────────────────────────────────────┐
            │ Pattern              │ What to intercept                             │
            ├──────────────────────┼──────────────────────────────────────────────┤
            │ @Transactional       │ begin/commit/rollback around method           │
            │ @Cacheable           │ return cached value, skip method if hit       │
            │ @Retryable           │ catch exception → retry with backoff          │
            │ @Timed               │ record execution time → Micrometer            │
            │ @PreAuthorize        │ check SecurityContext before invoke            │
            │ @Async               │ submit to thread pool → return Future         │
            └──────────────────────┴──────────────────────────────────────────────┘

            REFLECTION SAFETY RULES:
              ✓ Cache Method/Field/Constructor — never lookup in hot path
              ✓ Handle InvocationTargetException — unwrap with getCause()
              ✓ setAccessible(true) may fail in Java 9+ modules — handle gracefully
              ✓ Prefer MethodHandle over Method.invoke for performance-sensitive code
              ✓ Avoid reflection for new code — use records, interfaces, generics

            JDK PROXY vs CGLIB:
              JDK Proxy: target MUST implement interface — pure Java, no extra lib
              CGLIB:     works on classes (subclass) — Spring uses when no interface
              ByteBuddy: modern alternative, faster, used in Mockito, Hibernate

            KEY INSIGHT:
              "Reflection is the foundation that makes every Java framework possible.
               Understanding it lets you debug Spring, Hibernate, Jackson issues
               that otherwise appear as magic — because it IS magic, demystified."
            """);
    }
}
