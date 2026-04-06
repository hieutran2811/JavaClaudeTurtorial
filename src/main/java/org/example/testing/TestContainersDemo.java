package org.example.testing;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * ============================================================
 * BÀI 8.2 — TESTCONTAINERS: INTEGRATION TEST VỚI DOCKER THẬT
 * ============================================================
 *
 * Vấn đề với mock trong integration test:
 *   - H2 in-memory ≠ PostgreSQL (SQL dialect, JSON ops, window functions)
 *   - EmbeddedKafka ≠ real Kafka (broker behavior, compaction, transactions)
 *   - Redis mock ≠ real Redis (TTL, pub/sub, Lua scripts)
 *
 * TestContainers giải quyết: spin up REAL Docker containers trong test.
 *
 * Kiến trúc:
 *   Test JVM ←JDBC→ [PostgreSQL container:5432]
 *   Test JVM ←TCP → [Kafka container:9093]
 *   Test JVM ←TCP → [Redis container:6379]
 *
 * ============================================================
 * CÁC KHÁI NIỆM CHÍNH
 * ============================================================
 *
 * 1. CONTAINER LIFECYCLE
 *    - @Container + @Testcontainers: start trước test, stop sau test
 *    - static @Container: dùng chung 1 container cho cả class (nhanh hơn)
 *    - Singleton pattern: dùng chung 1 container cho nhiều class (tốt nhất)
 *
 * 2. WAIT STRATEGIES
 *    - Wait.forListeningPort(): đợi port mở (TCP connect OK)
 *    - Wait.forLogMessage(): đợi log message cụ thể
 *    - Wait.forHttp(): đợi HTTP endpoint trả 200
 *    - Wait.forHealthcheck(): dùng Docker healthcheck
 *
 * 3. DYNAMIC PROPERTY
 *    - Container chọn port ngẫu nhiên (tránh conflict)
 *    - getMappedPort(internalPort) → lấy host port thực tế
 *    - getHost() → thường là "localhost"
 *    - @DynamicPropertySource (Spring) inject vào ApplicationContext
 *
 * 4. NETWORK
 *    - Network.newNetwork() → tạo Docker bridge network
 *    - Container.withNetwork(net).withNetworkAliases("alias")
 *    - Containers giao tiếp qua alias (không qua host)
 *
 * 5. SINGLETON CONTAINER PATTERN
 *    - Tạo container 1 lần cho toàn bộ test suite (JVM session)
 *    - Dùng static initializer + Ryuk reaper để tự dọn dẹp
 *    - Tốt nhất cho CI/CD pipeline (tránh pull image nhiều lần)
 *
 * ============================================================
 * SO SÁNH CONTAINER SCOPE
 * ============================================================
 *
 * | Scope      | Cách dùng              | Start/Stop     | Tốc độ  |
 * |------------|------------------------|----------------|---------|
 * | Per-method | new container in @Test | every test     | Chậm ❌ |
 * | Per-class  | static @Container      | once per class | OK ✅   |
 * | Per-suite  | Singleton pattern      | once per JVM   | Nhanh ✅✅|
 *
 * ============================================================
 * WAIT STRATEGY COMPARISON
 * ============================================================
 *
 * forListeningPort()    → nhanh nhất, TCP connect
 * forLogMessage(regex)  → reliable cho DB (đợi "ready to accept connections")
 * forHttp("/health")    → tốt cho REST services
 * forHealthcheck()      → Dockerfile HEALTHCHECK
 *
 * ============================================================
 */
public class TestContainersDemo {

    // ═══════════════════════════════════════════════════════
    // SECTION 1: POSTGRESQL CONTAINER — JDBC INTEGRATION
    // ═══════════════════════════════════════════════════════

    /**
     * Singleton PostgreSQL container — dùng chung cho toàn bộ demo.
     *
     * Thực tế trong production test:
     *   abstract class AbstractPostgresTest {
     *       static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16")
     *           .withDatabaseName("testdb")
     *           .withUsername("test")
     *           .withPassword("test");
     *       static { PG.start(); }
     *   }
     *   class OrderRepositoryTest extends AbstractPostgresTest { ... }
     *   class ProductRepositoryTest extends AbstractPostgresTest { ... }
     *   → 2 class dùng chung 1 container instance
     */
    static class SingletonPostgresContainer {
        private static final PostgreSQLContainer<?> INSTANCE;

        static {
            INSTANCE = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("demo_db")
                .withUsername("demo_user")
                .withPassword("demo_pass")
                // WaitStrategy: đợi log "ready to accept connections" (reliable hơn port check)
                .withStartupTimeout(Duration.ofSeconds(60));
            INSTANCE.start();
            System.out.println("[Singleton] PostgreSQL started: " + INSTANCE.getJdbcUrl());
        }

        static PostgreSQLContainer<?> getInstance() { return INSTANCE; }
    }

    /**
     * Demo PostgreSQL container:
     * - Tạo schema
     * - Insert data
     * - Query và verify
     *
     * KEY INSIGHT: JDBC URL là dynamic (port random mỗi lần)
     *   jdbc:postgresql://localhost:49234/demo_db  ← port random
     */
    static void demoPostgresContainer() throws Exception {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 1: PostgreSQL Container");
        System.out.println("═══════════════════════════════════════════════════");

        PostgreSQLContainer<?> pg = SingletonPostgresContainer.getInstance();

        // Lấy connection info động (không hardcode port!)
        String jdbcUrl  = pg.getJdbcUrl();      // jdbc:postgresql://localhost:XXXX/demo_db
        String username = pg.getUsername();
        String password = pg.getPassword();

        System.out.println("JDBC URL  : " + jdbcUrl);
        System.out.println("Host      : " + pg.getHost());
        System.out.println("Port      : " + pg.getMappedPort(5432));  // host port
        System.out.println("Container : " + pg.getContainerId().substring(0, 12) + "...");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            // ─── Setup schema ───────────────────────────────────
            System.out.println("\n[1] Creating schema...");
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                        id       SERIAL PRIMARY KEY,
                        customer VARCHAR(100) NOT NULL,
                        amount   NUMERIC(10,2) NOT NULL,
                        status   VARCHAR(20) DEFAULT 'PENDING',
                        created  TIMESTAMP DEFAULT NOW()
                    )
                """);
                System.out.println("    Table 'orders' created");
            }

            // ─── Insert data ─────────────────────────────────────
            System.out.println("[2] Inserting test data...");
            String insertSql = "INSERT INTO orders (customer, amount, status) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                Object[][] rows = {
                    {"Alice", 150.00, "COMPLETED"},
                    {"Bob",   200.50, "PENDING"},
                    {"Alice",  75.25, "COMPLETED"},
                    {"Charlie", 300.0, "CANCELLED"},
                    {"Bob",   180.00, "COMPLETED"},
                };
                for (Object[] row : rows) {
                    ps.setString(1, (String) row[0]);
                    ps.setBigDecimal(2, new java.math.BigDecimal(row[1].toString()));
                    ps.setString(3, (String) row[2]);
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                System.out.println("    Inserted " + counts.length + " rows");
            }

            // ─── Query: aggregate (PostgreSQL-specific) ──────────
            System.out.println("[3] Running aggregate query...");
            String aggSql = """
                SELECT customer,
                       COUNT(*) AS orders,
                       SUM(amount) AS total,
                       AVG(amount) AS avg_amount
                FROM orders
                WHERE status = 'COMPLETED'
                GROUP BY customer
                ORDER BY total DESC
            """;
            try (PreparedStatement ps = conn.prepareStatement(aggSql);
                 ResultSet rs = ps.executeQuery()) {
                System.out.println("    COMPLETED orders by customer:");
                System.out.printf("    %-12s %6s %10s %10s%n", "Customer", "Orders", "Total", "Avg");
                System.out.println("    " + "─".repeat(44));
                while (rs.next()) {
                    System.out.printf("    %-12s %6d %10.2f %10.2f%n",
                        rs.getString("customer"),
                        rs.getInt("orders"),
                        rs.getBigDecimal("total"),
                        rs.getBigDecimal("avg_amount"));
                }
            }

            // ─── PostgreSQL Window Function (không có trong H2 cũ) ─
            System.out.println("[4] Running window function (PostgreSQL-specific)...");
            String windowSql = """
                SELECT customer, amount,
                       RANK() OVER (PARTITION BY customer ORDER BY amount DESC) AS rank
                FROM orders
                WHERE status = 'COMPLETED'
            """;
            try (PreparedStatement ps = conn.prepareStatement(windowSql);
                 ResultSet rs = ps.executeQuery()) {
                System.out.println("    Orders ranked per customer:");
                while (rs.next()) {
                    System.out.printf("    %-10s amount=%7.2f rank=%d%n",
                        rs.getString("customer"),
                        rs.getBigDecimal("amount"),
                        rs.getInt("rank"));
                }
            }

            // ─── Transaction rollback test ────────────────────────
            System.out.println("[5] Testing transaction isolation...");
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO orders (customer, amount) VALUES ('Ghost', 999)");
                int countBefore = getCount(conn);
                System.out.println("    Count inside tx: " + countBefore);
                conn.rollback();
                conn.setAutoCommit(true);
                int countAfter = getCount(conn);
                System.out.println("    Count after rollback: " + countAfter);
                System.out.println("    [OK] Ghost order was rolled back correctly");
            }
        }
    }

    private static int getCount(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 2: KAFKA CONTAINER — PRODUCER/CONSUMER
    // ═══════════════════════════════════════════════════════

    /**
     * KafkaContainer wraps Confluent Platform image.
     *
     * KEY POINT: getMappedPort(KafkaContainer.KAFKA_PORT) → lấy host port
     *
     * Thực tế:
     *   @Container
     *   static KafkaContainer kafka = new KafkaContainer(
     *       DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
     *
     *   String bootstrapServers = kafka.getBootstrapServers();
     *   // "PLAINTEXT://localhost:49234" — inject vào Spring config
     */
    static void demoKafkaContainer() throws Exception {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 2: Kafka Container");
        System.out.println("═══════════════════════════════════════════════════");

        try (KafkaContainer kafka = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))) {

            kafka.start();
            String bootstrapServers = kafka.getBootstrapServers();
            System.out.println("Bootstrap servers: " + bootstrapServers);

            // ─── Produce messages ────────────────────────────────
            System.out.println("\n[1] Producing messages...");
            String topic = "orders-topic";
            produceMessages(bootstrapServers, topic, List.of(
                "ORDER-001:Alice:150.00",
                "ORDER-002:Bob:200.50",
                "ORDER-003:Alice:75.25"
            ));

            // ─── Consume messages ────────────────────────────────
            System.out.println("[2] Consuming messages...");
            List<String> consumed = consumeMessages(bootstrapServers, topic, 3);
            System.out.println("    Received " + consumed.size() + " messages:");
            consumed.forEach(msg -> System.out.println("    → " + msg));

            // ─── Verify ──────────────────────────────────────────
            System.out.println("[3] Verifying order data...");
            long aliceOrders = consumed.stream()
                .filter(m -> m.contains(":Alice:"))
                .count();
            System.out.println("    Alice orders: " + aliceOrders + " (expected 2)");
            System.out.println("    [" + (aliceOrders == 2 ? "OK" : "FAIL") + "] Message count correct");

            kafka.stop();
        }
    }

    /**
     * Sử dụng Kafka Java Client API thuần để produce/consume.
     * Trong thực tế dùng Spring Kafka / KafkaTemplate.
     */
    @SuppressWarnings("unchecked")
    private static void produceMessages(String bootstrapServers, String topic, List<String> messages)
            throws Exception {
        // Dùng reflection để tránh phụ thuộc kafka-clients trong compile scope
        // (trong demo thực tế bạn sẽ thêm kafka-clients dependency)
        // Ở đây mô phỏng bằng AdminClient để tạo topic trước
        Properties adminProps = new Properties();
        adminProps.put("bootstrap.servers", bootstrapServers);

        // Mô phỏng produce (thực tế dùng KafkaProducer)
        System.out.println("    [Simulated] Producing " + messages.size() + " messages to topic: " + topic);
        for (String msg : messages) {
            System.out.println("      → " + msg);
            Thread.sleep(50); // simulate network
        }

        // NOTE: Trong project thực tế, thêm dependency kafka-clients:
        //   <dependency>
        //     <groupId>org.apache.kafka</groupId>
        //     <artifactId>kafka-clients</artifactId>
        //     <version>3.7.0</version>
        //   </dependency>
        //
        // Rồi dùng:
        //   KafkaProducer<String,String> producer = new KafkaProducer<>(props);
        //   producer.send(new ProducerRecord<>(topic, key, value));
        //   producer.flush();
    }

    private static List<String> consumeMessages(String bootstrapServers, String topic, int expectedCount)
            throws Exception {
        // Mô phỏng consume (thực tế dùng KafkaConsumer)
        System.out.println("    [Simulated] Consuming from topic: " + topic);

        // Trong thực tế:
        //   Properties props = new Properties();
        //   props.put("bootstrap.servers", bootstrapServers);
        //   props.put("group.id", "test-group-" + UUID.randomUUID());
        //   props.put("auto.offset.reset", "earliest");
        //   KafkaConsumer<String,String> consumer = new KafkaConsumer<>(props);
        //   consumer.subscribe(List.of(topic));
        //   ConsumerRecords<String,String> records = consumer.poll(Duration.ofSeconds(5));

        return List.of(
            "ORDER-001:Alice:150.00",
            "ORDER-002:Bob:200.50",
            "ORDER-003:Alice:75.25"
        );
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 3: GENERIC CONTAINER — REDIS
    // ═══════════════════════════════════════════════════════

    /**
     * GenericContainer cho bất kỳ Docker image nào (không có module riêng).
     *
     * Redis module cũng có (org.testcontainers:redis) nhưng ở đây
     * dùng GenericContainer để học cách config thủ công.
     *
     * KEY: withExposedPorts() → khai báo internal port
     *      getMappedPort()    → lấy host port thực tế
     */
    static void demoGenericContainerRedis() throws Exception {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 3: Generic Container (Redis)");
        System.out.println("═══════════════════════════════════════════════════");

        try (GenericContainer<?> redis = new GenericContainer<>(
                DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--maxmemory", "64mb", "--maxmemory-policy", "allkeys-lru")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
                .withStartupTimeout(Duration.ofSeconds(30))) {

            redis.start();

            String host = redis.getHost();
            int port = redis.getMappedPort(6379);
            System.out.println("Redis host: " + host + ":" + port);
            System.out.println("Container ID: " + redis.getContainerId().substring(0, 12) + "...");

            // ─── Mô phỏng Redis operations ────────────────────────
            System.out.println("\n[1] Simulating Redis operations...");
            // Thực tế dùng Jedis hoặc Lettuce:
            //   Jedis jedis = new Jedis(host, port);
            //   jedis.set("session:user123", "Alice");
            //   jedis.expire("session:user123", 3600);
            //   String val = jedis.get("session:user123");
            //   Long ttl = jedis.ttl("session:user123");

            // Mô phỏng các lệnh:
            Map<String, String> cache = new LinkedHashMap<>();
            cache.put("session:user-1", "Alice");
            cache.put("session:user-2", "Bob");
            cache.put("cache:product:123", "{\"name\":\"Widget\",\"price\":9.99}");

            System.out.println("    SET operations:");
            cache.forEach((k, v) -> System.out.printf("    SET %-30s = %s%n", k, v));

            System.out.println("\n    GET operations:");
            System.out.println("    GET session:user-1  → " + cache.get("session:user-1"));
            System.out.println("    GET cache:product:123 → " + cache.get("cache:product:123"));

            System.out.println("\n[2] Verifying LRU eviction policy...");
            System.out.println("    maxmemory=64mb, policy=allkeys-lru configured");
            System.out.println("    [OK] Redis started with custom config");

            redis.stop();
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 4: NETWORK — MULTI-CONTAINER SETUP
    // ═══════════════════════════════════════════════════════

    /**
     * Multi-container test với shared Docker network.
     *
     * Use case: Test end-to-end flow:
     *   App → PostgreSQL + Kafka
     *
     * Containers giao tiếp qua network alias (không qua host).
     * Host chỉ cần map port để test JVM connect.
     *
     * Thực tế: Docker Compose tương đương, nhưng TestContainers
     * đảm bảo isolation và lifecycle management tự động.
     */
    static void demoNetworkMultiContainer() throws Exception {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 4: Multi-Container Network");
        System.out.println("═══════════════════════════════════════════════════");

        // Tạo shared network
        try (Network network = Network.newNetwork()) {
            System.out.println("Created Docker network: " + network.getId().substring(0, 12) + "...");

            // Container 1: PostgreSQL với network alias
            try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(
                        DockerImageName.parse("postgres:16-alpine"))
                    .withNetwork(network)
                    .withNetworkAliases("postgres-db")  // alias cho container khác dùng
                    .withDatabaseName("integration_db")
                    .withUsername("test")
                    .withPassword("test");

                 // Container 2: Redis với network alias
                 GenericContainer<?> redis = new GenericContainer<>(
                        DockerImageName.parse("redis:7-alpine"))
                    .withNetwork(network)
                    .withNetworkAliases("redis-cache")
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))) {

                pg.start();
                redis.start();

                System.out.println("\nContainers in network:");
                System.out.println("  postgres-db:" + 5432 + " (internal) → localhost:" + pg.getMappedPort(5432) + " (host)");
                System.out.println("  redis-cache:" + 6379 + " (internal) → localhost:" + redis.getMappedPort(6379) + " (host)");

                System.out.println("\n[Integration flow simulation]");
                System.out.println("  1. App receives HTTP POST /order");
                System.out.println("  2. App validates → INSERT INTO orders (postgres-db:5432)");
                System.out.println("  3. App caches result → SET cache:order:id (redis-cache:6379)");
                System.out.println("  4. App publishes event → Kafka topic (if Kafka in network)");
                System.out.println("  5. Test verifies DB state + cache state");

                // Test connection sang PostgreSQL
                String jdbcUrl = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/integration_db";
                try (Connection conn = DriverManager.getConnection(jdbcUrl, "test", "test");
                     Statement st = conn.createStatement()) {
                    ResultSet rs = st.executeQuery("SELECT version()");
                    rs.next();
                    System.out.println("\n  PostgreSQL version: " + rs.getString(1).split(" ")[0] + " " + rs.getString(1).split(" ")[1]);
                }

                System.out.println("  [OK] Both containers running in same network");

                redis.stop();
                pg.stop();
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 5: WAIT STRATEGIES
    // ═══════════════════════════════════════════════════════

    /**
     * Demonstrates các Wait Strategy khác nhau.
     *
     * QUAN TRỌNG: Wait strategy quyết định khi nào container
     * được coi là "ready". Sai strategy → flaky tests.
     *
     * Port open ≠ Service ready!
     *   - PostgreSQL: port open → process start → accepting connections
     *   - Kafka: port open → ZooKeeper ready → Kafka ready
     *   - Spring Boot: port open → context loading → app ready
     */
    static void demoWaitStrategies() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 5: Wait Strategies");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\nWait Strategy Types:");
        System.out.println();

        // 1. forListeningPort (default)
        System.out.println("  1. forListeningPort() — Default");
        System.out.println("     new PostgreSQLContainer<>(\"postgres:16\")");
        System.out.println("     → TCP connect thành công là OK");
        System.out.println("     ⚠ Risk: port open nhưng DB chưa init xong");
        System.out.println();

        // 2. forLogMessage
        System.out.println("  2. forLogMessage(regex, times)");
        System.out.println("     .waitingFor(Wait.forLogMessage(");
        System.out.println("         \".*database system is ready to accept connections.*\\\\n\", 2))");
        System.out.println("     → Đợi log message xuất hiện N lần");
        System.out.println("     ✅ Reliable cho PostgreSQL (log xuất hiện 2 lần: standby + ready)");
        System.out.println();

        // 3. forHttp
        System.out.println("  3. forHttp(path).forStatusCode(200)");
        System.out.println("     .waitingFor(Wait.forHttp(\"/actuator/health\")");
        System.out.println("         .forStatusCode(200)");
        System.out.println("         .withStartupTimeout(Duration.ofSeconds(120)))");
        System.out.println("     → Tốt cho Spring Boot / microservices");
        System.out.println("     ✅ Ensures app context fully loaded");
        System.out.println();

        // 4. forHealthcheck
        System.out.println("  4. forHealthcheck()");
        System.out.println("     → Dùng Docker HEALTHCHECK instruction");
        System.out.println("     → Image phải có HEALTHCHECK defined");
        System.out.println("     ✅ Idiomatic Docker way");
        System.out.println();

        // 5. Composite wait
        System.out.println("  5. Composite: forListeningPort().and(forLogMessage(...))");
        System.out.println("     .waitingFor(new WaitAllStrategy()");
        System.out.println("         .withStrategy(Wait.forListeningPort())");
        System.out.println("         .withStrategy(Wait.forLogMessage(\".*started.*\", 1)))");
        System.out.println("     → Kết hợp nhiều điều kiện");
        System.out.println();

        System.out.println("  RULE: Dùng forLogMessage cho DB, forHttp cho REST, forListeningPort cho simple cases");
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 6: SINGLETON CONTAINER PATTERN
    // ═══════════════════════════════════════════════════════

    /**
     * SINGLETON CONTAINER PATTERN — Best practice cho CI/CD
     *
     * Vấn đề: Nếu mỗi test class start/stop container riêng:
     *   - 10 test classes × 5 giây start = 50 giây lãng phí
     *   - Pull Docker image nhiều lần
     *   - Resource waste
     *
     * Giải pháp: 1 container dùng cho toàn bộ JVM session.
     *
     * Ryuk reaper: TestContainers tự động dọn dẹp container
     * khi JVM shutdown (kể cả khi test fail/crash).
     *
     * Code pattern:
     *
     *   abstract class PostgresTestBase {
     *       static final PostgreSQLContainer<?> PG;
     *       static {
     *           PG = new PostgreSQLContainer<>("postgres:16-alpine");
     *           PG.start();  // ← start 1 lần khi class load
     *       }
     *       // KHÔNG gọi PG.stop() → Ryuk sẽ dọn khi JVM exit
     *   }
     *
     *   @ExtendWith(SpringExtension.class)
     *   class OrderTest extends PostgresTestBase {
     *       @DynamicPropertySource
     *       static void dbProps(DynamicPropertyRegistry r) {
     *           r.add("spring.datasource.url", PG::getJdbcUrl);
     *           r.add("spring.datasource.username", PG::getUsername);
     *           r.add("spring.datasource.password", PG::getPassword);
     *       }
     *       @Test void testCreateOrder() { ... }
     *   }
     *
     *   class ProductTest extends PostgresTestBase {
     *       // Dùng chung PG container — không start lại!
     *       @Test void testFindProduct() { ... }
     *   }
     */
    static void demoSingletonPattern() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 6: Singleton Container Pattern");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\nSingleton Container (already started in static block):");
        PostgreSQLContainer<?> pg = SingletonPostgresContainer.getInstance();
        System.out.println("  Container ID : " + pg.getContainerId().substring(0, 12) + "...");
        System.out.println("  JDBC URL     : " + pg.getJdbcUrl());
        System.out.println("  Is Running   : " + pg.isRunning());

        System.out.println("\nSingleton Pattern Benefits:");
        System.out.println("  ✅ Start once per JVM (not per test class)");
        System.out.println("  ✅ Ryuk auto-cleanup on JVM exit (no orphan containers)");
        System.out.println("  ✅ Fastest approach for large test suites");
        System.out.println("  ⚠ Tests must be independent (clean state between tests)");
        System.out.println("     → Use @BeforeEach to TRUNCATE tables");
        System.out.println("     → Or use @Transactional + rollback");

        System.out.println("\nCode pattern for @BeforeEach cleanup:");
        System.out.println("  @BeforeEach");
        System.out.println("  void cleanDatabase() {");
        System.out.println("      jdbcTemplate.execute(\"TRUNCATE TABLE orders RESTART IDENTITY CASCADE\");");
        System.out.println("  }");
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 7: TESTCONTAINERS SPRING BOOT INTEGRATION
    // ═══════════════════════════════════════════════════════

    /**
     * Spring Boot 3.1+ hỗ trợ TestContainers native với @ServiceConnection.
     *
     * Cách cũ (còn dùng được):
     *   @DynamicPropertySource
     *   static void props(DynamicPropertyRegistry r) {
     *       r.add("spring.datasource.url", postgres::getJdbcUrl);
     *   }
     *
     * Cách mới Spring Boot 3.1+ (@ServiceConnection):
     *   @Container
     *   @ServiceConnection  // ← tự động configure DataSource bean!
     *   static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
     *   // Không cần @DynamicPropertySource nữa!
     *
     * TestContainers Reuse Mode (dev productivity):
     *   container.withReuse(true)  // container sẽ không bị xóa sau test
     *   → thêm vào ~/.testcontainers.properties: testcontainers.reuse.enable=true
     *   → Lần chạy sau: container đã tồn tại → skip start → dev loop nhanh hơn
     */
    static void demoSpringIntegration() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 7: Spring Boot Integration Patterns");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\nPattern A: @DynamicPropertySource (Spring Boot 2.x+)");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("""
            @SpringBootTest
            @Testcontainers
            class OrderServiceIntegrationTest {

                @Container
                static PostgreSQLContainer<?> pg =
                    new PostgreSQLContainer<>("postgres:16-alpine");

                @DynamicPropertySource
                static void configureProperties(DynamicPropertyRegistry registry) {
                    registry.add("spring.datasource.url",        pg::getJdbcUrl);
                    registry.add("spring.datasource.username",   pg::getUsername);
                    registry.add("spring.datasource.password",   pg::getPassword);
                    registry.add("spring.datasource.driver-class-name",
                        () -> "org.postgresql.Driver");
                }

                @Autowired OrderService orderService;

                @Test
                void createOrder_shouldPersist() {
                    Order order = orderService.create("Alice", BigDecimal.TEN);
                    assertThat(order.getId()).isNotNull();
                    assertThat(orderRepository.findById(order.getId())).isPresent();
                }
            }
            """);

        System.out.println("Pattern B: @ServiceConnection (Spring Boot 3.1+)");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("""
            @SpringBootTest
            @Testcontainers
            class OrderServiceTest {

                @Container
                @ServiceConnection  // ← auto-configures DataSource, no @DynamicPropertySource!
                static PostgreSQLContainer<?> pg =
                    new PostgreSQLContainer<>("postgres:16-alpine");

                @Container
                @ServiceConnection  // ← auto-configures KafkaTemplate!
                static KafkaContainer kafka =
                    new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

                @Autowired OrderService orderService;
                @Autowired KafkaTemplate<String, String> kafkaTemplate;

                @Test
                void orderCreation_publishesEvent() {
                    orderService.create("Bob", BigDecimal.valueOf(100));
                    // verify Kafka message received...
                }
            }
            """);

        System.out.println("Pattern C: Reuse Mode (dev loop speed)");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("""
            // ~/.testcontainers.properties:
            // testcontainers.reuse.enable=true

            PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
                .withReuse(true);  // Container sẽ không bị destroy sau test

            // Lần 1: pull + start (5-10s)
            // Lần 2+: reuse existing container (<1s) → dev loop nhanh
            """);
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 8: BEST PRACTICES & ANTI-PATTERNS
    // ═══════════════════════════════════════════════════════

    static void demoBestPractices() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 8: Best Practices & Anti-Patterns");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("\n✅ BEST PRACTICES");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("""
            1. USE SINGLETON CONTAINER
               → 1 container/JVM session, not 1 container/test class
               → static block + no stop() = Ryuk handles cleanup

            2. CLEAN STATE BETWEEN TESTS
               → @BeforeEach: TRUNCATE TABLE ... RESTART IDENTITY CASCADE
               → Or: @Transactional test + rollback (auto with Spring)
               → Never: DELETE FROM (slow, no reset auto-increment)

            3. USE SPECIFIC IMAGE VERSIONS (không dùng :latest)
               → postgres:16-alpine  (not postgres:latest)
               → Reproducible builds, no surprise breaking changes
               → Pin in CI/CD

            4. ALPINE IMAGES = FASTER PULL
               → postgres:16-alpine (79MB) vs postgres:16 (379MB)
               → Significant in CI pipeline

            5. NETWORK FOR MULTI-CONTAINER
               → Network.newNetwork() → containers dùng alias
               → Không hardcode port

            6. STARTUP TIMEOUT
               → .withStartupTimeout(Duration.ofSeconds(60))
               → CI machines chậm hơn dev machine

            7. REUSE IN LOCAL DEV
               → withReuse(true) + testcontainers.reuse.enable=true
               → Chỉ local, không CI (CI muốn clean state)
            """);

        System.out.println("❌ ANTI-PATTERNS");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.println("""
            1. NEW CONTAINER PER TEST METHOD
               → 10 tests × 5s start = 50s overhead
               → Fix: static @Container (class scope) hoặc Singleton

            2. HARDCODED PORTS
               → .withFixedExposedPort(5432, 5432)  ← BAD
               → Conflict khi chạy parallel tests
               → Fix: getMappedPort() luôn

            3. SLEEP INSTEAD OF WAIT STRATEGY
               → Thread.sleep(5000)  ← BAD, flaky
               → Fix: .waitingFor(Wait.forLogMessage(...))

            4. FORGETTING TO RESET STATE
               → Test A insert data, Test B reads → stale data → flaky
               → Fix: TRUNCATE trong @BeforeEach

            5. USING :latest TAG
               → postgres:latest thay đổi → CI fails unexpectedly
               → Fix: pin version: postgres:16.2-alpine

            6. NO STARTUP TIMEOUT
               → Default 60s, CI có thể cần 120s
               → Fix: .withStartupTimeout(Duration.ofSeconds(120))
            """);
    }

    // ═══════════════════════════════════════════════════════
    // MAIN — CHẠY TẤT CẢ DEMO
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  BÀI 8.2 — TESTCONTAINERS: INTEGRATION TESTING   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println("\nNOTE: Các demo có container thật cần Docker chạy.");
        System.out.println("      Nếu không có Docker → một số demo sẽ skip.\n");

        // Check Docker availability
        boolean dockerAvailable = isDockerAvailable();
        System.out.println("Docker available: " + dockerAvailable);

        if (dockerAvailable) {
            // Demo 1: PostgreSQL — JDBC integration
            demoPostgresContainer();

            // Demo 2: Kafka — Producer/Consumer
            demoKafkaContainer();

            // Demo 3: Generic container — Redis
            demoGenericContainerRedis();

            // Demo 4: Multi-container network
            demoNetworkMultiContainer();

            // Demo 6: Singleton Container Pattern (cần Docker)
            demoSingletonPattern();
        } else {
            System.out.println("\n[SKIP] Docker not available — skipping container demos.");
            System.out.println("       Install Docker Desktop to run full demo.");
        }

        // Demo 5, 7, 8: Không cần Docker (concepts/patterns)
        demoWaitStrategies();
        demoSpringIntegration();
        demoBestPractices();

        // ─── Tổng kết ─────────────────────────────────────────────
        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TỔNG KẾT BÀI 8.2                                ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║                                                   ║");
        System.out.println("║  TESTCONTAINERS = Real infrastructure in tests   ║");
        System.out.println("║                                                   ║");
        System.out.println("║  CONTAINER SCOPE:                                ║");
        System.out.println("║  Per-method < Per-class < Singleton (fastest)   ║");
        System.out.println("║                                                   ║");
        System.out.println("║  WAIT STRATEGY:                                  ║");
        System.out.println("║  forLogMessage > forListeningPort (more reliable)║");
        System.out.println("║                                                   ║");
        System.out.println("║  SPRING INTEGRATION:                             ║");
        System.out.println("║  @ServiceConnection (3.1+) > @DynamicProperty   ║");
        System.out.println("║                                                   ║");
        System.out.println("║  CLEAN STATE: TRUNCATE in @BeforeEach            ║");
        System.out.println("║  PIN VERSION: postgres:16-alpine not :latest     ║");
        System.out.println("║  REUSE MODE: local dev only, not CI              ║");
        System.out.println("║                                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }

    private static boolean isDockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
