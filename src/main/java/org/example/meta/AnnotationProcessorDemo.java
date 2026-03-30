package org.example.meta;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * =============================================================================
 * BÀI 7.2 — Annotation Processing: Runtime & Compile-time
 * =============================================================================
 *
 * ANNOTATION PROCESSING có 2 giai đoạn:
 *
 *   1. RUNTIME PROCESSING (@Retention(RUNTIME)):
 *      → Đọc annotation qua Reflection khi app chạy
 *      → Cách hoạt động của: Spring @Autowired, Hibernate @Entity, JUnit @Test
 *      → Demo trong bài này
 *
 *   2. COMPILE-TIME PROCESSING (AbstractProcessor / javac plugin):
 *      → Chạy trong quá trình biên dịch (javac annotation processing round)
 *      → Sinh code mới (.java files) không cần reflection → fast startup
 *      → Cách hoạt động của: Lombok @Data, MapStruct @Mapper, Dagger @Inject
 *      → Quá phức tạp để demo trực tiếp → giải thích + show generated pattern
 *
 * ANNOTATION RETENTION:
 *   SOURCE   → only in .java, stripped by javac (e.g. @Override, @SuppressWarnings)
 *   CLASS    → in .class bytecode, NOT available at runtime (default!)
 *   RUNTIME  → in .class AND available via reflection at runtime
 *
 * META-ANNOTATIONS:
 *   @Retention  → khi nào annotation còn tồn tại
 *   @Target     → được đặt ở đâu (TYPE, FIELD, METHOD, PARAMETER, ...)
 *   @Inherited  → annotation trên class cha được kế thừa bởi class con
 *   @Repeatable → cho phép đặt annotation nhiều lần trên cùng element
 *   @Documented → annotation xuất hiện trong javadoc
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.meta.AnnotationProcessorDemo"
 */
public class AnnotationProcessorDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 7.2 — Annotation Processing: Runtime Processing");
        System.out.println("=".repeat(70));
        System.out.println();

        demo1_annotationDesign();
        demo2_metaAnnotations();
        demo3_repeatableAnnotations();
        demo4_annotationInheritance();
        demo5_miniValidationFramework();
        demo6_miniOrmMapper();
        demo7_miniEventSystem();
        demo8_miniDiContainer();
        demo9_compiletimeProcessing();
        printSAInsights();
    }

    // =========================================================================
    // ── Custom Annotation Definitions ──────────────────────────────────────
    // =========================================================================

    // Validation annotations
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface NotNull    { String message() default "must not be null"; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface NotBlank   { String message() default "must not be blank"; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface Size       { int min() default 0; int max() default Integer.MAX_VALUE;
                            String message() default "size out of range"; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface Min        { long value(); String message() default "value too small"; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface Email      { String message() default "invalid email format"; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface Pattern    { String regex(); String message() default "pattern mismatch"; }

    // ORM annotations
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    @interface Table      { String name() default ""; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface Id         {}

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface Column     { String name() default ""; boolean nullable() default true; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface Transient  {}   // exclude from ORM mapping

    // Event system annotations
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @interface EventHandler { Class<?> eventType(); int priority() default 0; }

    // DI annotations
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    @interface Component  { String value() default ""; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    @interface Inject     {}

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @interface PostConstruct {}

    // Repeatable annotation
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @Repeatable(Roles.class)
    @interface Role       { String value(); }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @interface Roles      { Role[] value(); }

    // Meta-annotation (annotation on annotation)
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.ANNOTATION_TYPE)
    @interface Constraint { Class<? extends Validator<?>> validatedBy(); }

    interface Validator<A extends Annotation> {
        void initialize(A annotation);
        boolean isValid(Object value);
        String getMessage();
    }

    // =========================================================================
    // DEMO 1 — Annotation Design Principles
    // =========================================================================
    static void demo1_annotationDesign() {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 1 — Annotation Design: Retention, Target, Elements");
        System.out.println("─".repeat(70));

        System.out.println("""

            ANNOTATION ANATOMY:
              @Retention(RetentionPolicy.RUNTIME)   ← visible at runtime via reflection
              @Target({ElementType.FIELD,            ← where it can be placed
                       ElementType.METHOD})
              @Documented                            ← appears in Javadoc
              @Inherited                             ← subclasses inherit from superclass
              public @interface MyAnnotation {
                  String value()    default "";      ← element with default
                  int    priority() default 0;
                  Class<?>[] types();                ← no default = required
              }

            ELEMENT TYPES (ElementType):
              TYPE         — class, interface, enum, record, annotation
              FIELD        — instance & static fields
              METHOD       — methods
              PARAMETER    — method parameters
              CONSTRUCTOR  — constructors
              LOCAL_VARIABLE — local vars (source-only, not in bytecode)
              ANNOTATION_TYPE — meta-annotations
              MODULE       — module-info.java
              RECORD_COMPONENT — record fields

            RETENTION LEVELS:
              SOURCE  → javac strips: @Override, @SuppressWarnings
              CLASS   → in bytecode, NOT reflectable (default if omitted!)
              RUNTIME → in bytecode, reflectable: Spring, JPA, Jackson annotations
            """);

        // Inspect our own annotations
        System.out.println("  [Inspecting @NotNull annotation metadata]");
        Retention retention = NotNull.class.getAnnotation(Retention.class);
        Target target       = NotNull.class.getAnnotation(Target.class);
        System.out.printf("    @Retention: %s%n", retention.value());
        System.out.printf("    @Target:    %s%n", Arrays.toString(target.value()));

        // Annotation element types
        System.out.println("\n  [Annotation element types on @Size]");
        for (Method m : Size.class.getDeclaredMethods()) {
            System.out.printf("    %-10s %s() default %s%n",
                m.getReturnType().getSimpleName(),
                m.getName(),
                m.getDefaultValue());
        }
    }

    // =========================================================================
    // DEMO 2 — Meta-annotations
    // =========================================================================

    // Meta-annotation: @Service is itself annotated with @Component
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    @Component("service")   // @Service IS-A @Component (composed annotation)
    @interface Service {}

    // Another composed annotation
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    @Component("repository")
    @interface RepositoryAnnotation {}

    @Service
    static class UserServiceImpl {}

    @RepositoryAnnotation
    static class UserRepositoryImpl {}

    static void demo2_metaAnnotations() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 2 — Meta-annotations: Annotations on Annotations");
        System.out.println("─".repeat(70));

        System.out.println("""

            Meta-annotation = annotation placed on another annotation.
            Enables COMPOSED ANNOTATIONS (Spring stereotype pattern):
              @Service   = @Component("service") + semantics
              @Repository = @Component("repository") + exception translation
              @Controller = @Component + request mapping

            Spring scans for @Component → finds @Service (which is @Component)
            → registers the bean. This is "annotation inheritance" via composition.
            """);

        // Show meta-annotation chain
        System.out.println("  [Meta-annotation chain: @Service → @Component]");
        Annotation[] serviceAnns = Service.class.getAnnotations();
        System.out.printf("  Annotations on @Service: %d%n", serviceAnns.length);
        for (Annotation a : serviceAnns) {
            System.out.printf("    %s%n", a.annotationType().getSimpleName());
            // Go one level deeper
            for (Annotation aa : a.annotationType().getAnnotations()) {
                if (!aa.annotationType().getPackageName().startsWith("java.lang"))
                    System.out.printf("      └─ %s%n", aa.annotationType().getSimpleName());
            }
        }

        // Find all @Component beans (including composed annotations)
        System.out.println("\n  [Discovering beans via meta-annotation scanning]");
        List<Class<?>> classes = List.of(UserServiceImpl.class, UserRepositoryImpl.class);

        for (Class<?> cls : classes) {
            // Direct @Component check
            Component direct = cls.getAnnotation(Component.class);
            if (direct != null) {
                System.out.printf("  Direct @Component: %s → value='%s'%n",
                    cls.getSimpleName(), direct.value());
                continue;
            }
            // Check meta-annotations (one level deep)
            for (Annotation ann : cls.getAnnotations()) {
                Component meta = ann.annotationType().getAnnotation(Component.class);
                if (meta != null) {
                    System.out.printf("  Meta @Component on %s (via @%s) → value='%s'%n",
                        cls.getSimpleName(),
                        ann.annotationType().getSimpleName(),
                        meta.value());
                }
            }
        }

        System.out.println("""
            This is how Spring ComponentScan works:
              • Walks classpath → for each class:
                  - Check if annotated with @Component (direct)
                  - Check if any annotation on the class is annotated with @Component (meta)
              → Both @Service and @Repository register as beans
            """);
    }

    // =========================================================================
    // DEMO 3 — Repeatable Annotations
    // =========================================================================
    static class SecureEndpoint {
        @Role("ADMIN")
        @Role("MANAGER")
        @Role("AUDITOR")
        public void sensitiveOperation() {}

        @Role("USER")
        public void publicOperation() {}
    }

    static void demo3_repeatableAnnotations() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 3 — @Repeatable: Multiple Annotations on Same Element");
        System.out.println("─".repeat(70));

        System.out.println("""

            @Repeatable allows placing same annotation multiple times.
            Java wraps them in a container annotation (@Roles).
            Use case: @Role("ADMIN") @Role("MANAGER") on same method.
            """);

        for (Method m : SecureEndpoint.class.getDeclaredMethods()) {
            // getAnnotationsByType handles both single and repeated
            Role[] roles = m.getAnnotationsByType(Role.class);
            System.out.printf("  Method %-20s → roles: %s%n",
                m.getName() + "()",
                Arrays.stream(roles).map(Role::value).collect(Collectors.joining(", ")));

            // Container annotation
            Roles container = m.getAnnotation(Roles.class);
            if (container != null) {
                System.out.printf("    Container @Roles has %d roles%n", container.value().length);
            }
        }

        // Role-based access check
        System.out.println("\n  [Role-based access check using @Repeatable]");
        String currentUserRole = "MANAGER";
        Method sensitive = SecureEndpoint.class.getMethod("sensitiveOperation");
        Role[] allowed = sensitive.getAnnotationsByType(Role.class);
        boolean hasAccess = Arrays.stream(allowed)
            .anyMatch(r -> r.value().equals(currentUserRole));
        System.out.printf("  User '%s' accessing sensitiveOperation: %s%n",
            currentUserRole, hasAccess ? "ALLOWED" : "DENIED");
    }

    // =========================================================================
    // DEMO 4 — Annotation Inheritance (@Inherited)
    // =========================================================================
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    @Inherited   // subclasses inherit this annotation
    @interface Auditable { String auditLog() default "default-log"; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    // NO @Inherited — subclasses do NOT inherit this
    @interface NonInheritable { String value() default ""; }

    @Auditable(auditLog = "transactions")
    @NonInheritable("parent-only")
    static class BaseEntity {}

    static class OrderEntity extends BaseEntity {}  // inherits @Auditable, NOT @NonInheritable

    static void demo4_annotationInheritance() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 4 — @Inherited: Annotation Inheritance via Subclassing");
        System.out.println("─".repeat(70));

        System.out.println("""

            @Inherited: annotation on superclass appears on subclass via getAnnotation().
            NOTE: only works for CLASS-level annotations, NOT methods/fields.
            """);

        System.out.println("  [BaseEntity annotations]");
        for (Annotation a : BaseEntity.class.getAnnotations()) {
            System.out.printf("    %s%n", a.annotationType().getSimpleName());
        }

        System.out.println("\n  [OrderEntity annotations (extends BaseEntity)]");
        for (Annotation a : OrderEntity.class.getAnnotations()) {
            System.out.printf("    %s ← %s%n",
                a.annotationType().getSimpleName(),
                OrderEntity.class.isAnnotationPresent(a.annotationType())
                    ? "INHERITED" : "DIRECT");
        }

        Auditable auditOnChild = OrderEntity.class.getAnnotation(Auditable.class);
        NonInheritable nonInhOnChild = OrderEntity.class.getAnnotation(NonInheritable.class);

        System.out.printf("%n  OrderEntity has @Auditable:       %b (value='%s')%n",
            auditOnChild != null, auditOnChild != null ? auditOnChild.auditLog() : "");
        System.out.printf("  OrderEntity has @NonInheritable:  %b%n", nonInhOnChild != null);

        System.out.println("""
            CAVEAT:
              @Inherited only applies to CLASS annotations — NOT methods/fields.
              If BaseEntity.save() has @Transactional, OrderEntity.save() does NOT
              inherit it via @Inherited — Spring re-checks method annotations per class.
            """);
    }

    // =========================================================================
    // DEMO 5 — Mini Validation Framework (like Bean Validation / JSR-380)
    // =========================================================================

    // Domain model with validation annotations
    static class CreateUserRequest {
        @NotNull
        @NotBlank
        @Size(min = 3, max = 50, message = "username must be 3-50 chars")
        private String username;

        @NotNull
        @Email
        private String email;

        @NotNull
        @Size(min = 8, message = "password min 8 chars")
        @Pattern(regex = ".*[A-Z].*", message = "password must contain uppercase")
        private String password;

        @Min(value = 18, message = "must be 18 or older")
        private int age;

        CreateUserRequest(String username, String email, String password, int age) {
            this.username = username;
            this.email    = email;
            this.password = password;
            this.age      = age;
        }
    }

    // Validation result
    record ValidationError(String field, String message) {}

    // The validator engine
    static List<ValidationError> validate(Object obj) throws IllegalAccessException {
        List<ValidationError> errors = new ArrayList<>();
        Class<?> cls = obj.getClass();

        for (Field field : cls.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(obj);
            String name  = field.getName();

            // @NotNull
            if (field.isAnnotationPresent(NotNull.class) && value == null) {
                errors.add(new ValidationError(name,
                    field.getAnnotation(NotNull.class).message()));
                continue; // skip further checks if null
            }

            if (value == null) continue;

            // @NotBlank
            if (field.isAnnotationPresent(NotBlank.class) && value instanceof String s
                    && s.isBlank()) {
                errors.add(new ValidationError(name,
                    field.getAnnotation(NotBlank.class).message()));
            }

            // @Size
            if (field.isAnnotationPresent(Size.class) && value instanceof String s) {
                Size sz = field.getAnnotation(Size.class);
                if (s.length() < sz.min() || s.length() > sz.max()) {
                    errors.add(new ValidationError(name, sz.message()));
                }
            }

            // @Min (for numbers)
            if (field.isAnnotationPresent(Min.class)) {
                long minVal = field.getAnnotation(Min.class).value();
                long actual = ((Number) value).longValue();
                if (actual < minVal) {
                    errors.add(new ValidationError(name,
                        field.getAnnotation(Min.class).message()
                            + " (min=" + minVal + ", actual=" + actual + ")"));
                }
            }

            // @Email (simple check)
            if (field.isAnnotationPresent(Email.class) && value instanceof String s) {
                if (!s.matches("^[\\w._%+\\-]+@[\\w.\\-]+\\.[a-zA-Z]{2,}$")) {
                    errors.add(new ValidationError(name,
                        field.getAnnotation(Email.class).message()));
                }
            }

            // @Pattern
            if (field.isAnnotationPresent(Pattern.class) && value instanceof String s) {
                Pattern pat = field.getAnnotation(Pattern.class);
                if (!s.matches(pat.regex())) {
                    errors.add(new ValidationError(name, pat.message()));
                }
            }
        }
        return errors;
    }

    static void demo5_miniValidationFramework() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 5 — Mini Validation Framework (Bean Validation style)");
        System.out.println("─".repeat(70));

        System.out.println("""

            Mimicking JSR-380 (Bean Validation 2.0):
              @NotNull, @NotBlank, @Size, @Min, @Email, @Pattern
              Validator reads annotations → applies rules → collects errors
            """);

        // Valid request
        System.out.println("  [Valid request]");
        CreateUserRequest valid = new CreateUserRequest(
            "alice", "alice@example.com", "SecurePass1", 25);
        List<ValidationError> errors1 = validate(valid);
        System.out.printf("  Errors: %d → %s%n", errors1.size(),
            errors1.isEmpty() ? "VALID" : errors1);

        // Invalid request
        System.out.println("\n  [Invalid request — multiple violations]");
        CreateUserRequest invalid = new CreateUserRequest(
            "ab",               // too short (< 3)
            "not-an-email",     // invalid email
            "weakpass",         // no uppercase
            16);                // under 18
        List<ValidationError> errors2 = validate(invalid);
        System.out.printf("  Errors: %d%n", errors2.size());
        errors2.forEach(e -> System.out.printf("    %-12s → %s%n", e.field(), e.message()));

        // Null request fields
        System.out.println("\n  [Null fields — @NotNull violations]");
        CreateUserRequest nullReq = new CreateUserRequest(null, null, "ValidPass1", 20);
        List<ValidationError> errors3 = validate(nullReq);
        errors3.forEach(e -> System.out.printf("    %-12s → %s%n", e.field(), e.message()));

        System.out.println("""
            PRODUCTION: use Hibernate Validator (RI for Bean Validation 3.0):
              <dependency>
                <groupId>org.hibernate.validator</groupId>
                <artifactId>hibernate-validator</artifactId>
              </dependency>
              ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
              Validator validator = factory.getValidator();
              Set<ConstraintViolation<T>> violations = validator.validate(object);
            """);
    }

    // =========================================================================
    // DEMO 6 — Mini ORM Mapper (like Hibernate / Spring Data JDBC)
    // =========================================================================

    @Table(name = "users")
    static class UserEntity {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "user_name", nullable = false)
        private String username;

        @Column(name = "email_address")
        private String email;

        @Column(name = "age")
        private Integer age;

        @Transient   // not persisted
        private String temporaryToken;

        UserEntity(Long id, String username, String email, int age) {
            this.id = id; this.username = username;
            this.email = email; this.age = age;
            this.temporaryToken = "temp-" + System.nanoTime();
        }

        @Override public String toString() {
            return "UserEntity{id=" + id + ", username='" + username +
                   "', email='" + email + "', age=" + age + "}";
        }
    }

    @Table(name = "products")
    static class ProductEntity {
        @Id @Column(name = "product_id")
        private Long id;
        @Column(name = "product_name", nullable = false)
        private String name;
        @Column(name = "price")
        private Double price;

        ProductEntity(Long id, String name, double price) {
            this.id = id; this.name = name; this.price = price;
        }
    }

    // Mini ORM
    static class MiniOrm {

        // Generate INSERT SQL
        String generateInsert(Object entity) throws IllegalAccessException {
            Class<?> cls = entity.getClass();
            Table table = cls.getAnnotation(Table.class);
            String tableName = (table != null && !table.name().isEmpty())
                ? table.name() : cls.getSimpleName().toLowerCase();

            List<String> cols  = new ArrayList<>();
            List<String> vals  = new ArrayList<>();

            for (Field f : cls.getDeclaredFields()) {
                if (f.isAnnotationPresent(Transient.class)) continue; // skip transient
                f.setAccessible(true);
                Object val = f.get(entity);

                Column col = f.getAnnotation(Column.class);
                String colName = (col != null && !col.name().isEmpty())
                    ? col.name() : camelToSnake(f.getName());

                cols.add(colName);
                vals.add(val == null ? "NULL"
                    : val instanceof String ? "'" + val + "'" : val.toString());
            }

            return String.format("INSERT INTO %s (%s) VALUES (%s);",
                tableName,
                String.join(", ", cols),
                String.join(", ", vals));
        }

        // Generate SELECT SQL
        String generateSelect(Class<?> cls, String whereClause) {
            Table table = cls.getAnnotation(Table.class);
            String tableName = (table != null && !table.name().isEmpty())
                ? table.name() : cls.getSimpleName().toLowerCase();

            List<String> cols = new ArrayList<>();
            for (Field f : cls.getDeclaredFields()) {
                if (f.isAnnotationPresent(Transient.class)) continue;
                Column col = f.getAnnotation(Column.class);
                String colName = (col != null && !col.name().isEmpty())
                    ? col.name() : camelToSnake(f.getName());
                String alias = f.getName();
                cols.add(colName + " AS " + alias);
            }

            String sql = "SELECT " + String.join(", ", cols)
                       + " FROM " + tableName;
            if (whereClause != null) sql += " WHERE " + whereClause;
            return sql + ";";
        }

        // Generate schema DDL
        String generateCreateTable(Class<?> cls) {
            Table table = cls.getAnnotation(Table.class);
            String tableName = (table != null && !table.name().isEmpty())
                ? table.name() : cls.getSimpleName().toLowerCase();

            List<String> colDefs = new ArrayList<>();
            for (Field f : cls.getDeclaredFields()) {
                if (f.isAnnotationPresent(Transient.class)) continue;
                Column col = f.getAnnotation(Column.class);
                String colName = (col != null && !col.name().isEmpty())
                    ? col.name() : camelToSnake(f.getName());
                String sqlType = javaToSqlType(f.getType());
                boolean nullable = (col == null || col.nullable());
                boolean isPk = f.isAnnotationPresent(Id.class);

                String def = colName + " " + sqlType;
                if (isPk)       def += " PRIMARY KEY";
                if (!nullable)  def += " NOT NULL";
                colDefs.add(def);
            }
            return "CREATE TABLE " + tableName + " (\n  "
                + String.join(",\n  ", colDefs) + "\n);";
        }

        private String javaToSqlType(Class<?> type) {
            if (type == Long.class || type == long.class)    return "BIGINT";
            if (type == Integer.class || type == int.class)  return "INT";
            if (type == String.class)                         return "VARCHAR(255)";
            if (type == Double.class || type == double.class) return "DECIMAL(10,2)";
            if (type == Boolean.class || type == boolean.class) return "BOOLEAN";
            return "TEXT";
        }

        private String camelToSnake(String camel) {
            return camel.replaceAll("([A-Z])", "_$1").toLowerCase();
        }
    }

    static void demo6_miniOrmMapper() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6 — Mini ORM Mapper (Hibernate/@Entity style)");
        System.out.println("─".repeat(70));

        System.out.println("""

            Mimicking JPA/Hibernate:
              @Table(name) → maps class to DB table
              @Column(name, nullable) → maps field to DB column
              @Id → marks primary key
              @Transient → excludes field from mapping
            """);

        MiniOrm orm = new MiniOrm();
        UserEntity user = new UserEntity(1L, "alice", "alice@example.com", 30);
        ProductEntity product = new ProductEntity(42L, "Laptop Pro", 1299.99);

        // Generate DDL
        System.out.println("  [Generated DDL — CREATE TABLE]");
        System.out.println(orm.generateCreateTable(UserEntity.class));
        System.out.println();
        System.out.println(orm.generateCreateTable(ProductEntity.class));

        // Generate INSERT
        System.out.println("\n  [Generated INSERT SQL]");
        System.out.println(orm.generateInsert(user));
        System.out.println(orm.generateInsert(product));

        // Generate SELECT
        System.out.println("\n  [Generated SELECT SQL]");
        System.out.println(orm.generateSelect(UserEntity.class, null));
        System.out.println(orm.generateSelect(UserEntity.class, "id = 1"));
        System.out.println(orm.generateSelect(ProductEntity.class, "price > 1000"));

        // Note: @Transient field excluded
        System.out.println("\n  [Note: @Transient 'temporaryToken' excluded from all SQL]");
        boolean hasToken = Arrays.stream(UserEntity.class.getDeclaredFields())
            .anyMatch(f -> f.getName().equals("temporaryToken")
                && !f.isAnnotationPresent(Transient.class));
        System.out.println("  temporaryToken in SQL: " + hasToken + " (correctly excluded)");
    }

    // =========================================================================
    // DEMO 7 — Mini Event System with @EventHandler
    // =========================================================================

    record OrderCreatedEvent(long orderId, String customer, double amount) {}
    record PaymentReceivedEvent(long orderId, double amount) {}
    record OrderShippedEvent(long orderId, String trackingId) {}

    static class OrderListener {
        @EventHandler(eventType = OrderCreatedEvent.class, priority = 10)
        public void onOrderCreated(OrderCreatedEvent event) {
            System.out.printf("    [OrderListener] Order #%d created for %s ($%.2f)%n",
                event.orderId(), event.customer(), event.amount());
        }

        @EventHandler(eventType = PaymentReceivedEvent.class, priority = 5)
        public void onPaymentReceived(PaymentReceivedEvent event) {
            System.out.printf("    [OrderListener] Payment $%.2f received for order #%d%n",
                event.amount(), event.orderId());
        }
    }

    static class ShippingListener {
        @EventHandler(eventType = OrderCreatedEvent.class, priority = 1)
        public void reserveInventory(OrderCreatedEvent event) {
            System.out.printf("    [ShippingListener] Reserving inventory for order #%d%n",
                event.orderId());
        }

        @EventHandler(eventType = OrderShippedEvent.class, priority = 10)
        public void onShipped(OrderShippedEvent event) {
            System.out.printf("    [ShippingListener] Order #%d shipped, tracking: %s%n",
                event.orderId(), event.trackingId());
        }
    }

    // Event bus backed by annotation scanning
    static class AnnotationEventBus {
        record HandlerEntry(Object instance, Method method, int priority) {}

        private final Map<Class<?>, List<HandlerEntry>> handlers = new HashMap<>();

        void register(Object listener) {
            for (Method m : listener.getClass().getDeclaredMethods()) {
                EventHandler ann = m.getAnnotation(EventHandler.class);
                if (ann == null) continue;
                handlers
                    .computeIfAbsent(ann.eventType(), k -> new ArrayList<>())
                    .add(new HandlerEntry(listener, m, ann.priority()));
            }
        }

        void publish(Object event) {
            List<HandlerEntry> entries = handlers.getOrDefault(
                event.getClass(), Collections.emptyList());

            entries.stream()
                .sorted(Comparator.comparingInt(HandlerEntry::priority).reversed()) // highest priority first
                .forEach(entry -> {
                    try {
                        entry.method().invoke(entry.instance(), event);
                    } catch (Exception e) {
                        System.err.println("Handler error: " + e.getCause().getMessage());
                    }
                });
        }

        void printStats() {
            System.out.println("  Registered event types:");
            handlers.forEach((type, list) ->
                System.out.printf("    %-30s → %d handler(s)%n",
                    type.getSimpleName(), list.size()));
        }
    }

    static void demo7_miniEventSystem() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 7 — Mini Event System with @EventHandler");
        System.out.println("─".repeat(70));

        System.out.println("""

            @EventHandler(eventType, priority) on methods →
              EventBus.register() scans annotations →
              EventBus.publish(event) dispatches to matching handlers by priority
            """);

        AnnotationEventBus bus = new AnnotationEventBus();
        bus.register(new OrderListener());
        bus.register(new ShippingListener());
        bus.printStats();

        System.out.println("\n  [Publishing events]");
        System.out.println("  → OrderCreatedEvent (2 handlers, sorted by priority):");
        bus.publish(new OrderCreatedEvent(1001L, "Alice", 299.99));

        System.out.println("\n  → PaymentReceivedEvent (1 handler):");
        bus.publish(new PaymentReceivedEvent(1001L, 299.99));

        System.out.println("\n  → OrderShippedEvent (1 handler):");
        bus.publish(new OrderShippedEvent(1001L, "TRK-987654"));
    }

    // =========================================================================
    // DEMO 8 — Mini DI Container with @Component, @Inject, @PostConstruct
    // =========================================================================

    @Component
    static class DatabaseService {
        private boolean connected = false;

        @PostConstruct
        void init() {
            connected = true;
            System.out.println("    [DatabaseService] @PostConstruct: connected to DB");
        }

        String query(String sql) {
            return connected ? "Result<" + sql + ">" : "ERROR: not connected";
        }
    }

    @Component
    static class CacheService {
        private final Map<String, Object> cache = new ConcurrentHashMap<>();

        @PostConstruct
        void init() {
            System.out.println("    [CacheService] @PostConstruct: cache initialized");
        }

        void put(String key, Object value) { cache.put(key, value); }
        Object get(String key)             { return cache.get(key); }
    }

    @Component
    static class ProductService {
        @Inject
        private DatabaseService db;

        @Inject
        private CacheService cache;

        @PostConstruct
        void init() {
            System.out.println("    [ProductService] @PostConstruct: dependencies injected");
        }

        String findProduct(String id) {
            Object cached = cache.get(id);
            if (cached != null) return "CACHED: " + cached;
            String result = db.query("SELECT * FROM products WHERE id=" + id);
            cache.put(id, result);
            return result;
        }
    }

    // Mini DI container
    static class MiniDIContainer {
        private final Map<Class<?>, Object> beans = new LinkedHashMap<>();

        void scan(Class<?>... classes) throws Exception {
            // Phase 1: instantiate all @Component classes
            for (Class<?> cls : classes) {
                if (cls.isAnnotationPresent(Component.class)) {
                    Constructor<?> ctor = cls.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    beans.put(cls, ctor.newInstance());
                    System.out.printf("    [DI] Instantiated: %s%n", cls.getSimpleName());
                }
            }

            // Phase 2: inject @Inject fields
            for (Object bean : beans.values()) {
                for (Field f : bean.getClass().getDeclaredFields()) {
                    if (!f.isAnnotationPresent(Inject.class)) continue;
                    f.setAccessible(true);
                    Object dep = beans.get(f.getType());
                    if (dep == null)
                        throw new RuntimeException("No bean for: " + f.getType().getSimpleName());
                    f.set(bean, dep);
                    System.out.printf("    [DI] Injected %s → %s.%s%n",
                        f.getType().getSimpleName(),
                        bean.getClass().getSimpleName(), f.getName());
                }
            }

            // Phase 3: call @PostConstruct methods
            for (Object bean : beans.values()) {
                for (Method m : bean.getClass().getDeclaredMethods()) {
                    if (!m.isAnnotationPresent(PostConstruct.class)) continue;
                    m.setAccessible(true);
                    m.invoke(bean);
                }
            }
        }

        @SuppressWarnings("unchecked")
        <T> T getBean(Class<T> type) {
            return (T) beans.get(type);
        }
    }

    static void demo8_miniDiContainer() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 8 — Mini DI Container (@Component, @Inject, @PostConstruct)");
        System.out.println("─".repeat(70));

        System.out.println("""

            3-phase DI lifecycle (like Spring ApplicationContext):
              Phase 1: Scan + instantiate @Component classes
              Phase 2: Inject @Inject fields (field injection)
              Phase 3: Call @PostConstruct init methods
            """);

        MiniDIContainer container = new MiniDIContainer();
        container.scan(DatabaseService.class, CacheService.class, ProductService.class);

        System.out.println("\n  [Using injected beans]");
        ProductService productSvc = container.getBean(ProductService.class);
        System.out.println("    " + productSvc.findProduct("P001"));
        System.out.println("    " + productSvc.findProduct("P001")); // from cache
        System.out.println("    " + productSvc.findProduct("P002"));

        System.out.println("""
            PRODUCTION Spring does this + more:
              • Circular dependency detection (A → B → A = error or proxy)
              • Scope: singleton (default), prototype, request, session
              • Lazy init: @Lazy annotation
              • Conditional: @ConditionalOnProperty, @Profile
              • Bean factory: @Bean methods in @Configuration classes
            """);
    }

    // =========================================================================
    // DEMO 9 — Compile-time Annotation Processing (explanation + patterns)
    // =========================================================================
    static void demo9_compiletimeProcessing() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 9 — Compile-time Processing: Lombok, MapStruct, Dagger");
        System.out.println("─".repeat(70));

        System.out.println("""
            COMPILE-TIME ANNOTATION PROCESSING = AbstractProcessor (JSR-269):
              • Runs during javac compilation (not at runtime)
              • Reads annotations → generates new .java files → compiled together
              • Zero reflection overhead → faster startup, GraalVM native-image friendly

            HOW IT WORKS:
              1. javac calls processor.process(annotations, roundEnvironment)
              2. Processor reads @Data on class Foo → generates FooBuilder, getters, setters
              3. Generated files compiled in next round
              4. Result: no runtime overhead — just normal Java code

            NOTABLE TOOLS:
            ┌──────────────┬──────────────────────────────────────────────────────┐
            │ Tool         │ Annotation → Generated Code                           │
            ├──────────────┼──────────────────────────────────────────────────────┤
            │ Lombok       │ @Data → getters/setters/equals/hashCode/toString      │
            │              │ @Builder → builder pattern class                       │
            │              │ @Slf4j → private static final Logger log = ...        │
            │ MapStruct    │ @Mapper → implementation of interface mapping          │
            │ Dagger2      │ @Inject → DI wiring code (no reflection!)             │
            │ Immutables   │ @Value → immutable value object implementation        │
            │ AutoValue    │ @AutoValue → value class with equals/hashCode         │
            │ Micronaut    │ @Singleton → AOT DI (no Spring reflection)            │
            └──────────────┴──────────────────────────────────────────────────────┘

            LOMBOK EXAMPLE (what you write vs what javac sees):
              You write:              Lombok generates:
              @Data                   public String getName() { return name; }
              public class User {     public void setName(String n) { name = n; }
                  String name;        public boolean equals(Object o) { ... }
                  int age;            public int hashCode() { ... }
              }                       public String toString() { ... }

            MAPSTRUCT EXAMPLE:
              @Mapper
              public interface UserMapper {
                  UserDto toDto(UserEntity entity);   // generates → UserMapperImpl.java
              }
              // Generated:
              // public class UserMapperImpl implements UserMapper {
              //     public UserDto toDto(UserEntity entity) {
              //         return new UserDto(entity.getName(), entity.getEmail(), ...);
              //     }
              // }

            WRITING YOUR OWN PROCESSOR (structure):
              @SupportedAnnotationTypes("com.example.MyAnnotation")
              @SupportedSourceVersion(SourceVersion.RELEASE_17)
              public class MyAnnotationProcessor extends AbstractProcessor {
                  @Override
                  public boolean process(Set<? extends TypeElement> annotations,
                                         RoundEnvironment roundEnv) {
                      for (Element el : roundEnv.getElementsAnnotatedWith(MyAnnotation.class)) {
                          // Generate source file
                          JavaFileObject file = processingEnv.getFiler()
                              .createSourceFile("GeneratedClass");
                          try (Writer w = file.openWriter()) {
                              w.write("public class GeneratedClass { ... }");
                          }
                      }
                      return true; // claim these annotations
                  }
              }
              // Register in: META-INF/services/javax.annotation.processing.Processor

            SA DECISION: Runtime vs Compile-time processing?
              Runtime:       simpler, flexible, dynamic → Spring, Hibernate
              Compile-time:  faster startup, native-image safe → Micronaut, Quarkus, Dagger
              Hybrid:        Spring AOT (Spring Boot 3+) generates code at build time
            """);
    }

    // =========================================================================
    // SA Insights
    // =========================================================================
    static void printSAInsights() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 7.2 — Annotation Processing Insights");
        System.out.println("=".repeat(70));
        System.out.println("""
            ANNOTATION LIFECYCLE:
              Design:   @Retention + @Target + elements với defaults
              Compose:  meta-annotation (@Service = @Component + semantics)
              Read:     cls.getAnnotation() / field.getAnnotationsByType()
              Act:      validator, ORM mapper, event bus, DI container

            RUNTIME ANNOTATION PATTERNS IN PRODUCTION:
            ┌────────────────────────────────┬──────────────────────────────────┐
            │ What you write                 │ Framework reads annotation and…  │
            ├────────────────────────────────┼──────────────────────────────────┤
            │ @Entity @Table @Column         │ Hibernate builds SQL schema/query│
            │ @NotNull @Size @Email          │ Hibernate Validator runs checks  │
            │ @Component @Autowired          │ Spring creates & wires beans     │
            │ @Transactional                 │ Spring AOP wraps with TX proxy   │
            │ @Test @BeforeEach              │ JUnit discovers & orders methods │
            │ @JsonProperty @JsonIgnore      │ Jackson maps JSON ↔ objects      │
            │ @GetMapping @RequestBody       │ Spring MVC routes HTTP requests  │
            └────────────────────────────────┴──────────────────────────────────┘

            ANNOTATION DESIGN RULES:
              ✓ Always @Retention(RUNTIME) if needed at runtime (easy to forget!)
              ✓ Default values for optional elements → simpler usage
              ✓ String message() on validation annotations → customizable errors
              ✓ Use meta-annotations for composed stereotypes (@Service, @Repository)
              ✓ @Inherited only for class-level annotations (not method/field)
              ✓ @Repeatable when multiple same annotations make sense (@Role)

            KEY INSIGHT:
              "Every Spring bean, every JPA entity, every JSON field mapping —
               all driven by annotations read via reflection at startup.
               Understanding annotation processing = understanding how frameworks
               bootstrap themselves from zero to running application."
            """);
    }
}
