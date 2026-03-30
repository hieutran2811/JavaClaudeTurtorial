package org.example.tooling;

import java.util.*;
import java.util.stream.*;
import java.util.function.*;

/**
 * ============================================================
 * BÀI 10.2 — MAVEN ADVANCED: BUILD TOOL MASTERY
 * ============================================================
 *
 * Maven = build automation tool + dependency management + project structure.
 * Hiểu Maven sâu giúp: CI/CD nhanh hơn, dependency conflict ít hơn,
 * multi-module project sạch hơn, release process đáng tin cậy hơn.
 *
 * ============================================================
 * MAVEN LIFECYCLE
 * ============================================================
 *
 * 3 built-in lifecycles:
 *   default  → build + deploy
 *   clean    → cleanup
 *   site     → documentation
 *
 * DEFAULT LIFECYCLE PHASES (theo thứ tự):
 *   validate    → check POM valid, all info available
 *   initialize  → set properties, create directories
 *   compile     → compile main source (src/main/java)
 *   test-compile→ compile test source (src/test/java)
 *   test        → run unit tests (Surefire)
 *   package     → create JAR/WAR/ZIP
 *   verify      → run integration tests (Failsafe)
 *   install     → copy to local ~/.m2/repository
 *   deploy      → upload to remote repository (Nexus/Artifactory)
 *
 * KEY: Chạy phase N = chạy TẤT CẢ phase từ 1 đến N.
 *   mvn package  = validate+compile+test+package (không install)
 *   mvn install  = ..+package+verify+install
 *   mvn test -DskipTests=false  (default: run tests)
 *   mvn package -DskipTests     (skip tests for speed)
 *
 * ============================================================
 * GOALS vs PHASES
 * ============================================================
 *
 * Phase: abstract step in lifecycle (compile, test, package)
 * Goal:  concrete task from a plugin (compiler:compile, surefire:test)
 *
 * Plugin binds goals to phases:
 *   maven-compiler-plugin:compile → bound to compile phase
 *   maven-surefire-plugin:test    → bound to test phase
 *
 * mvn compiler:compile   → run specific goal (not full lifecycle)
 * mvn compile            → run lifecycle up to compile phase
 *
 * ============================================================
 * DEPENDENCY SCOPES
 * ============================================================
 *
 *   compile  → classpath: main+test+runtime. Transitive. Default.
 *   test     → classpath: test only. NOT transitive.
 *   provided → compile+test only. Runtime provided by container (Servlet API).
 *   runtime  → test+runtime only. Not needed to compile.
 *   optional → compile, NOT transitive (hint: "consumer decides").
 *   import   → BOM import only (in dependencyManagement section).
 *
 * ============================================================
 * DEPENDENCY CONFLICT RESOLUTION
 * ============================================================
 *
 * Maven uses "nearest definition wins":
 *   A → B → C:1.0
 *   A → D → C:2.0  ← C:2.0 wins if D is declared before B? NO.
 *   A → C:2.0      ← C:2.0 wins (nearest = shortest path)
 *
 * Force version:
 *   <dependencyManagement> → set version for all transitive deps
 *   <exclusions>           → exclude specific transitive dep
 *
 * ============================================================
 */
public class MavenAdvancedDemo {

    // ═══════════════════════════════════════════════════════
    // SECTION 1: MAVEN LIFECYCLE VISUALIZATION
    // ═══════════════════════════════════════════════════════

    static void demoLifecycle() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 1: Maven Lifecycle & Phases");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            DEFAULT LIFECYCLE (most common):
            ──────────────────────────────────────────────────
            Phase            Plugin Goal Bound               Output
            ────────────────────────────────────────────────────────────────
            validate         (none)                          POM validation
            initialize       (none)
            generate-sources (optional) → code generation   generated-sources/
            compile          compiler:compile                target/classes/
            test-compile     compiler:testCompile            target/test-classes/
            test             surefire:test                   target/surefire-reports/
            package          jar:jar (or war:war)            target/app-1.0.jar
            verify           failsafe:verify                 integration test results
            install          install:install                 ~/.m2/repository/...
            deploy           deploy:deploy                   Nexus/Artifactory

            MOST USED COMMANDS:
            ──────────────────────────────────────────────────
            mvn compile               → compile only
            mvn test                  → compile + run unit tests
            mvn package               → compile + test + package JAR
            mvn package -DskipTests   → skip tests (faster, use in dev only)
            mvn verify                → + integration tests
            mvn install               → + copy to local repo (for multi-module)
            mvn deploy                → + upload to remote repo (CI/CD)
            mvn clean package         → delete target/ then build fresh
            mvn dependency:tree       → show full dependency tree
            mvn dependency:analyze    → find unused/undeclared deps
            mvn versions:display-dependency-updates → show available upgrades

            CLEAN LIFECYCLE:
            ──────────────────────────────────────────────────
            mvn clean  → pre-clean → clean → post-clean
                       → deletes target/ directory
            """);

        System.out.println("PHASE ORDERING (chạy package = chạy tất cả phases trước đó):");
        String[] phases = {
            "validate", "initialize", "generate-sources", "process-sources",
            "generate-resources", "process-resources", "compile", "process-classes",
            "generate-test-sources", "process-test-sources", "test-compile",
            "process-test-classes", "test", "prepare-package", "package",
            "pre-integration-test", "integration-test", "post-integration-test",
            "verify", "install", "deploy"
        };
        Set<String> highlighted = Set.of("compile","test","package","verify","install","deploy");
        for (int i = 0; i < phases.length; i++) {
            String mark = highlighted.contains(phases[i]) ? " ◄ KEY" : "";
            System.out.printf("  %2d. %-35s%s%n", i+1, phases[i], mark);
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 2: PLUGIN CONFIGURATION
    // ═══════════════════════════════════════════════════════

    static void demoPluginConfiguration() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 2: Essential Plugin Configurations");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            ── 1. maven-compiler-plugin ───────────────────────
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-compiler-plugin</artifactId>
              <version>3.13.0</version>
              <configuration>
                <release>21</release>          <!-- Java 21 -->
                <!-- Or: <source>21</source><target>21</target> -->
                <encoding>UTF-8</encoding>
                <parameters>true</parameters>  <!-- keep method param names (Spring needs this) -->
                <compilerArgs>
                  <arg>--enable-preview</arg>  <!-- enable preview features -->
                </compilerArgs>
                <annotationProcessorPaths>     <!-- e.g., Lombok, MapStruct -->
                  <path>
                    <groupId>org.projectlombok</groupId>
                    <artifactId>lombok</artifactId>
                    <version>1.18.32</version>
                  </path>
                </annotationProcessorPaths>
              </configuration>
            </plugin>

            ── 2. maven-surefire-plugin (Unit Tests) ──────────
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-surefire-plugin</artifactId>
              <version>3.2.5</version>
              <configuration>
                <includes>
                  <include>**/*Test.java</include>    <!-- *Test.java pattern -->
                  <include>**/*Spec.java</include>
                </includes>
                <excludes>
                  <exclude>**/*IT.java</exclude>      <!-- exclude integration tests -->
                </excludes>
                <parallel>methods</parallel>          <!-- run test methods in parallel -->
                <threadCount>4</threadCount>
                <forkCount>1</forkCount>              <!-- 1 JVM fork per module -->
                <argLine>-Xmx512m -XX:+UseG1GC</argLine>
                <systemPropertyVariables>
                  <db.url>jdbc:h2:mem:test</db.url>   <!-- system properties for tests -->
                </systemPropertyVariables>
              </configuration>
            </plugin>

            ── 3. maven-failsafe-plugin (Integration Tests) ───
            <!-- Failsafe: run *IT.java tests in verify phase -->
            <!-- Key difference from Surefire: build DOESN'T fail on test failure during
                 integration-test phase → allows post-integration-test cleanup to run -->
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-failsafe-plugin</artifactId>
              <version>3.2.5</version>
              <executions>
                <execution>
                  <goals>
                    <goal>integration-test</goal>  <!-- runs *IT tests -->
                    <goal>verify</goal>            <!-- fails build if IT failed -->
                  </goals>
                </execution>
              </executions>
              <configuration>
                <includes>
                  <include>**/*IT.java</include>
                  <include>**/*IntegrationTest.java</include>
                </includes>
                <argLine>-Xmx1g</argLine>
              </configuration>
            </plugin>

            ── 4. maven-shade-plugin (Fat JAR / Uber JAR) ─────
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-shade-plugin</artifactId>
              <version>3.5.2</version>
              <executions>
                <execution>
                  <phase>package</phase>
                  <goals><goal>shade</goal></goals>
                  <configuration>
                    <createDependencyReducedPom>true</createDependencyReducedPom>
                    <transformers>
                      <transformer implementation="...ManifestResourceTransformer">
                        <mainClass>com.example.Main</mainClass>
                      </transformer>
                      <!-- Merge META-INF/services files (SPI providers) -->
                      <transformer implementation="...ServicesResourceTransformer"/>
                    </transformers>
                    <filters>
                      <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                          <exclude>META-INF/*.SF</exclude>  <!-- remove signatures -->
                          <exclude>META-INF/*.DSA</exclude>
                        </excludes>
                      </filter>
                    </filters>
                  </configuration>
                </execution>
              </executions>
            </plugin>

            ── 5. maven-enforcer-plugin (Governance) ──────────
            <!-- Enforce rules: Java version, Maven version, no banned deps -->
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-enforcer-plugin</artifactId>
              <version>3.4.1</version>
              <executions>
                <execution>
                  <id>enforce</id>
                  <goals><goal>enforce</goal></goals>
                  <configuration>
                    <rules>
                      <requireJavaVersion><version>[21,)</version></requireJavaVersion>
                      <requireMavenVersion><version>[3.9,)</version></requireMavenVersion>
                      <bannedDependencies>
                        <excludes>
                          <exclude>log4j:log4j</exclude>         <!-- CVE-2021-44228! -->
                          <exclude>commons-logging:commons-logging</exclude>
                        </excludes>
                      </bannedDependencies>
                      <dependencyConvergence/>  <!-- all versions of same dep must match -->
                    </rules>
                    <fail>true</fail>  <!-- fail build if rule violated -->
                  </configuration>
                </execution>
              </executions>
            </plugin>

            ── 6. versions-maven-plugin ────────────────────────
            <!-- mvn versions:display-dependency-updates → show outdated deps -->
            <!-- mvn versions:use-latest-releases        → auto-update pom.xml -->
            <!-- mvn versions:set -DnewVersion=2.0.0    → set project version -->
            <plugin>
              <groupId>org.codehaus.mojo</groupId>
              <artifactId>versions-maven-plugin</artifactId>
              <version>2.16.2</version>
            </plugin>
            """);
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 3: MULTI-MODULE PROJECT
    // ═══════════════════════════════════════════════════════

    static void demoMultiModule() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 3: Multi-Module Project Structure");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            PROJECT STRUCTURE:
            ──────────────────────────────────────────────────
            my-app/                          ← Parent POM (aggregator)
            ├── pom.xml                      ← <packaging>pom</packaging>
            ├── my-app-domain/               ← Domain module (no Spring, no DB)
            │   ├── pom.xml
            │   └── src/main/java/.../domain/
            ├── my-app-application/          ← Application services, use cases
            │   ├── pom.xml
            │   └── src/main/java/.../application/
            ├── my-app-infrastructure/       ← DB, Kafka, HTTP clients
            │   ├── pom.xml
            │   └── src/main/java/.../infrastructure/
            └── my-app-web/                  ← Spring Boot, Controllers, DI wiring
                ├── pom.xml                  ← The runnable module
                └── src/main/java/.../web/

            PARENT POM (my-app/pom.xml):
            ──────────────────────────────────────────────────
            <project>
              <groupId>com.example</groupId>
              <artifactId>my-app</artifactId>
              <version>1.0.0-SNAPSHOT</version>
              <packaging>pom</packaging>   <!-- ← REQUIRED for parent POM -->

              <modules>
                <module>my-app-domain</module>
                <module>my-app-application</module>
                <module>my-app-infrastructure</module>
                <module>my-app-web</module>
              </modules>

              <properties>
                <java.version>21</java.version>
                <spring-boot.version>3.3.0</spring-boot.version>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>

              <!-- dependencyManagement: set versions, NOT add to classpath -->
              <dependencyManagement>
                <dependencies>
                  <!-- Spring Boot BOM: controls ALL Spring versions -->
                  <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-dependencies</artifactId>
                    <version>${spring-boot.version}</version>
                    <type>pom</type>
                    <scope>import</scope>  <!-- ← import BOM -->
                  </dependency>
                  <!-- Internal modules: set version for cross-module deps -->
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>my-app-domain</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>

              <!-- build config inherited by all children -->
              <build>
                <pluginManagement>
                  <plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <version>3.13.0</version>
                      <configuration><release>21</release></configuration>
                    </plugin>
                  </plugins>
                </pluginManagement>
              </build>
            </project>

            CHILD POM (my-app-domain/pom.xml):
            ──────────────────────────────────────────────────
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0-SNAPSHOT</version>
              </parent>

              <artifactId>my-app-domain</artifactId>
              <!-- version inherited from parent -->
              <!-- groupId inherited from parent -->

              <dependencies>
                <!-- No version needed: managed by parent dependencyManagement -->
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>

            CHILD POM (my-app-web/pom.xml):
            ──────────────────────────────────────────────────
            <dependencies>
              <!-- Internal module dependencies -->
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>my-app-domain</artifactId>
                <!-- version from parent dependencyManagement -->
              </dependency>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>my-app-application</artifactId>
              </dependency>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>my-app-infrastructure</artifactId>
              </dependency>
              <!-- Spring Boot: version from BOM, no version needed -->
              <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-web</artifactId>
              </dependency>
            </dependencies>

            REACTOR (multi-module build):
            ──────────────────────────────────────────────────
            mvn install                    → build all modules in dependency order
            mvn install -pl my-app-domain  → build specific module only
            mvn install -pl my-app-web -am → -am: also build dependencies of target
            mvn install -pl my-app-web -amd→ -amd: also build modules depending on target

            Build order (Reactor determines automatically from inter-module deps):
              1. my-app-domain         (no internal deps)
              2. my-app-application    (depends on domain)
              3. my-app-infrastructure (depends on domain)
              4. my-app-web            (depends on all above)
            """);
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 4: PROFILES
    // ═══════════════════════════════════════════════════════

    static void demoProfiles() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 4: Maven Profiles");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            Profiles: activate different config for different environments.
            Activation: -P flag, environment variable, file existence, JDK version.

            ── Profile definition in pom.xml ──────────────────
            <profiles>
              <!-- Dev profile: fast build, H2 database, skip IT -->
              <profile>
                <id>dev</id>
                <activation>
                  <activeByDefault>true</activeByDefault>  <!-- default profile -->
                </activation>
                <properties>
                  <db.url>jdbc:h2:mem:devdb</db.url>
                  <db.driver>org.h2.Driver</db.driver>
                  <log.level>DEBUG</log.level>
                </properties>
                <build>
                  <plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-surefire-plugin</artifactId>
                      <configuration>
                        <skipTests>false</skipTests>
                      </configuration>
                    </plugin>
                  </plugins>
                </build>
              </profile>

              <!-- Staging profile -->
              <profile>
                <id>staging</id>
                <activation>
                  <property>
                    <name>env</name>
                    <value>staging</value>  <!-- -Denv=staging activates this -->
                  </property>
                </activation>
                <properties>
                  <db.url>jdbc:postgresql://staging-db:5432/appdb</db.url>
                  <db.driver>org.postgresql.Driver</db.driver>
                  <log.level>INFO</log.level>
                </properties>
              </profile>

              <!-- Production profile -->
              <profile>
                <id>production</id>
                <activation>
                  <property><name>env</name><value>prod</value></property>
                </activation>
                <properties>
                  <db.url>${DB_URL}</db.url>    <!-- from env variable -->
                  <db.driver>org.postgresql.Driver</db.driver>
                  <log.level>WARN</log.level>
                </properties>
                <build>
                  <plugins>
                    <!-- Production: run integration tests -->
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-failsafe-plugin</artifactId>
                      <executions>
                        <execution>
                          <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                          </goals>
                        </execution>
                      </executions>
                    </plugin>
                  </plugins>
                </build>
              </profile>

              <!-- CI profile: activated by env variable -->
              <profile>
                <id>ci</id>
                <activation>
                  <property>
                    <name>env.CI</name>  <!-- activated when CI=true env var exists -->
                  </property>
                </activation>
                <properties>
                  <surefire.failIfNoSpecifiedTests>false</surefire.failIfNoSpecifiedTests>
                </properties>
              </profile>

              <!-- JDK-specific: only activate on Java 21+ -->
              <profile>
                <id>java21-features</id>
                <activation>
                  <jdk>[21,)</jdk>
                </activation>
                <build>
                  <plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <configuration>
                        <release>21</release>
                        <compilerArgs>
                          <arg>--enable-preview</arg>
                        </compilerArgs>
                      </configuration>
                    </plugin>
                  </plugins>
                </build>
              </profile>
            </profiles>

            ── Usage ───────────────────────────────────────────
            mvn package -P dev              → activate dev profile
            mvn package -P staging          → activate staging
            mvn package -Denv=prod          → activate prod via property
            mvn package -P dev,ci           → activate multiple profiles
            mvn help:active-profiles        → show which profiles are active
            mvn package -P !dev             → deactivate a profile

            ── Resource filtering with profiles ────────────────
            # src/main/resources/application.properties
            spring.datasource.url=${db.url}
            spring.datasource.driver-class-name=${db.driver}
            logging.level.root=${log.level}

            # pom.xml build section:
            <resources>
              <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>  <!-- replace ${db.url} with profile value -->
              </resource>
            </resources>
            """);
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 5: DEPENDENCY MANAGEMENT
    // ═══════════════════════════════════════════════════════

    static void demoDependencyManagement() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 5: Dependency Management & Conflict Resolution");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            ── DEPENDENCY SCOPES ───────────────────────────────
            Scope       Compile  Test  Runtime  Transitive
            ─────────── ──────── ───── ──────── ──────────
            compile     ✅       ✅    ✅       ✅ (default)
            test        ❌       ✅    ❌       ❌
            provided    ✅       ✅    ❌       ❌ (Servlet API, Lombok)
            runtime     ❌       ✅    ✅       ✅ (JDBC driver)
            optional    ✅       ✅    ✅       ❌ (library with optional feature)
            system      ✅       ✅    ❌       ❌ (avoid: uses local file path)

            Examples:
              junit-jupiter      → scope: test
              lombok             → scope: provided (annotation processor, not runtime)
              postgresql-driver  → scope: runtime (only needed to run, not compile)
              servlet-api        → scope: provided (Tomcat provides at runtime)

            ── BOM (Bill of Materials) ─────────────────────────
            BOM = POM with only dependencyManagement, no code.
            Import BOM → all versions controlled, no need to specify each.

            <dependencyManagement>
              <dependencies>
                <!-- Spring Boot BOM: controls spring-*, jackson-*, logback-*, etc. -->
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-dependencies</artifactId>
                  <version>3.3.0</version>
                  <type>pom</type>
                  <scope>import</scope>
                </dependency>
                <!-- Testcontainers BOM -->
                <dependency>
                  <groupId>org.testcontainers</groupId>
                  <artifactId>testcontainers-bom</artifactId>
                  <version>1.19.7</version>
                  <type>pom</type>
                  <scope>import</scope>
                </dependency>
              </dependencies>
            </dependencyManagement>

            <!-- Now, no version needed: -->
            <dependency>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-web</artifactId>
              <!-- version comes from BOM -->
            </dependency>

            ── CONFLICT RESOLUTION ─────────────────────────────
            Problem: A→B→Guava:20, A→C→Guava:32 → which Guava?
            Maven rule: "nearest definition wins" (shortest path).
            If same depth: first declaration wins.

            # See conflict:
            mvn dependency:tree -Dverbose | grep omitted

            # Solution 1: declare version in your pom (nearest wins):
            <dependency>
              <groupId>com.google.guava</groupId>
              <artifactId>guava</artifactId>
              <version>32.1.3-jre</version>  <!-- you control the version -->
            </dependency>

            # Solution 2: dependencyManagement (centralized):
            <dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>32.1.3-jre</version>
                </dependency>
              </dependencies>
            </dependencyManagement>

            # Solution 3: exclude the transitive dep:
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>library-b</artifactId>
              <exclusions>
                <exclusion>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>  <!-- exclude library-b's guava -->
                </exclusion>
              </exclusions>
            </dependency>

            ── DEPENDENCY ANALYSIS COMMANDS ────────────────────
            mvn dependency:tree                     → full tree
            mvn dependency:tree -Dincludes=guava    → filter by artifact
            mvn dependency:analyze                  → unused declared, used undeclared
            mvn dependency:resolve -Dclassifier=sources → download sources
            mvn dependency:go-offline               → download all to ~/.m2 (offline CI)
            """);
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 6: CI/CD PIPELINE INTEGRATION
    // ═══════════════════════════════════════════════════════

    static void demoCiCdIntegration() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 6: CI/CD Pipeline Integration");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            ── SETTINGS.XML (~/.m2/settings.xml) ──────────────
            <!-- Repository credentials, mirrors, proxies -->
            <settings>
              <servers>
                <server>
                  <id>nexus-releases</id>
                  <username>${env.NEXUS_USER}</username>
                  <password>${env.NEXUS_PASS}</password>
                </server>
              </servers>

              <mirrors>
                <!-- Force all downloads through Nexus (corporate proxy) -->
                <mirror>
                  <id>nexus-central</id>
                  <mirrorOf>*</mirrorOf>
                  <url>https://nexus.company.com/repository/maven-proxy/</url>
                </mirror>
              </mirrors>

              <profiles>
                <profile>
                  <id>nexus</id>
                  <repositories>
                    <repository>
                      <id>nexus-snapshots</id>
                      <url>https://nexus.company.com/repository/maven-snapshots/</url>
                      <snapshots><enabled>true</enabled></snapshots>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
              <activeProfiles><activeProfile>nexus</activeProfile></activeProfiles>
            </settings>

            ── GITHUB ACTIONS PIPELINE ──────────────────────────
            # .github/workflows/build.yml
            name: CI
            on: [push, pull_request]

            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4

                  - name: Set up JDK 21
                    uses: actions/setup-java@v4
                    with:
                      java-version: '21'
                      distribution: 'temurin'
                      cache: maven              # ← cache ~/.m2 between runs

                  - name: Build and test
                    run: mvn verify -B           # -B: batch mode (no color, no interactive)

                  - name: Publish test results
                    uses: actions/upload-artifact@v4
                    if: always()               # even if tests fail
                    with:
                      name: test-results
                      path: '**/target/surefire-reports/*.xml'

                  - name: Check mutation score
                    run: mvn test-compile org.pitest:pitest-maven:mutationCoverage -B

              deploy:
                needs: build
                if: github.ref == 'refs/heads/main'
                steps:
                  - name: Deploy to Nexus
                    run: mvn deploy -P production -B
                    env:
                      NEXUS_USER: ${{ secrets.NEXUS_USER }}
                      NEXUS_PASS: ${{ secrets.NEXUS_PASS }}

            ── VERSIONING STRATEGIES ────────────────────────────
            SNAPSHOT vs RELEASE:
              1.0.0-SNAPSHOT  → in development, can change, maven re-downloads
              1.0.0           → immutable release, cached in local repo

            Semantic Versioning: MAJOR.MINOR.PATCH
              MAJOR: breaking API change
              MINOR: new feature, backwards compatible
              PATCH: bug fix

            maven-release-plugin workflow:
              mvn release:prepare   → bump version, create tag, commit pom.xml
              mvn release:perform   → checkout tag, build, deploy to Nexus

            # Or: manual versioning
              mvn versions:set -DnewVersion=2.0.0        → set version
              mvn versions:set -DnewVersion=2.1.0-SNAPSHOT → back to snapshot
              mvn versions:commit                          → remove backup files

            ── USEFUL MAVEN WRAPPER ────────────────────────────
            # mvnw: wrapper script — no Maven installation needed on CI
            ./mvnw verify    # uses .mvn/wrapper/maven-wrapper.properties
                             # downloads correct Maven version automatically
            # Generate wrapper:
            mvn wrapper:wrapper -Dmaven=3.9.6
            """);
    }

    // ═══════════════════════════════════════════════════════
    // SECTION 7: COMMON PITFALLS & BEST PRACTICES
    // ═══════════════════════════════════════════════════════

    static void demoBestPractices() {
        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  DEMO 7: Pitfalls & Best Practices");
        System.out.println("═══════════════════════════════════════════════════");

        System.out.println("""
            ✅ BEST PRACTICES
            ──────────────────────────────────────────────────
            1. ALWAYS use BOM for Spring Boot / Quarkus
               → No version drift between spring-core, spring-web, spring-data

            2. ALWAYS declare versions in <properties>
               <junit.version>5.10.2</junit.version>
               → One place to update, consistent across modules

            3. USE <dependencyManagement> in parent POM
               → Children don't specify versions → no version drift

            4. SEPARATE unit tests (*Test) from integration tests (*IT)
               → Surefire runs *Test (fast), Failsafe runs *IT (slow, in verify)
               → mvn test → fast feedback, mvn verify → full check

            5. USE maven-enforcer-plugin in CI
               → Enforce Java version, Maven version, no banned deps
               → Ban log4j 1.x (security), ban commons-logging (use slf4j)

            6. PIN plugin versions (not just dependency versions)
               <pluginManagement> in parent POM
               → Reproducible builds: same plugin version = same output

            7. USE mvn -B (batch mode) in CI
               → No ANSI colors, no interactive prompts → cleaner logs

            8. CACHE ~/.m2 in CI
               → GitHub Actions: cache: maven in setup-java step
               → Saves 2-5 minutes per build

            9. USE Maven Wrapper (mvnw)
               → No "install Maven first" step needed
               → Consistent Maven version across dev machines and CI

            10. RUN mvn dependency:analyze periodically
                → Remove unused declared dependencies
                → Add missing direct dependencies (don't rely on transitive)

            ❌ ANTI-PATTERNS
            ──────────────────────────────────────────────────
            1. VERSION RANGES: <version>[1.0,2.0)</version>
               → Non-deterministic builds (different result each run)
               → Pin exact versions

            2. SNAPSHOT DEPENDENCIES in production build
               → 1.0-SNAPSHOT can change → non-reproducible
               → Use RELEASE versions in CI deploy

            3. IMPORTING WHOLE BOM and adding conflicting version
               → BOM says guava=32, you add guava=20 → confusion
               → Override in <dependencyManagement> if needed, document why

            4. HUGE MONOLITHIC POM (500+ lines)
               → Split into multi-module
               → Or use pluginManagement in parent to centralize

            5. COMPILING WITH -source 8 -target 8 but using Java 21
               → Use <release>21</release> instead
               → -source/-target allow using Java 21 JDK to produce Java 8 bytecode
               → But doesn't prevent using Java 21 API → runtime ClassNotFound on Java 8

            6. COMMITTING target/ directory
               → Add target/ to .gitignore
               → Build artifacts should NOT be in git

            7. STORING CREDENTIALS IN POM
               → Never: <password>mySecretPassword</password> in pom.xml
               → Use settings.xml (not committed) or env variables: ${env.MY_PASS}

            QUICK REFERENCE:
            ──────────────────────────────────────────────────
            mvn clean install -T 4            → parallel build (4 threads)
            mvn clean install -T 1C           → 1 thread per CPU core
            mvn dependency:tree > deps.txt    → save dep tree to file
            mvn help:effective-pom            → show final computed POM
            mvn help:effective-settings       → show merged settings.xml
            mvn -pl module-a,module-b install → build specific modules
            mvn validate                      → just validate POM (fast check)
            """);
    }

    // ═══════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  BÀI 10.2 — MAVEN ADVANCED                       ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        demoLifecycle();
        demoPluginConfiguration();
        demoMultiModule();
        demoProfiles();
        demoDependencyManagement();
        demoCiCdIntegration();
        demoBestPractices();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  TỔNG KẾT BÀI 10.2                               ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║                                                   ║");
        System.out.println("║  LIFECYCLE: validate→compile→test→package        ║");
        System.out.println("║    →verify→install→deploy (each includes prev)   ║");
        System.out.println("║                                                   ║");
        System.out.println("║  SUREFIRE: unit tests (*Test) in test phase      ║");
        System.out.println("║  FAILSAFE: integration tests (*IT) in verify     ║");
        System.out.println("║    → build doesn't fail mid-IT, verify does      ║");
        System.out.println("║                                                   ║");
        System.out.println("║  MULTI-MODULE: parent POM + <modules>            ║");
        System.out.println("║    dependencyManagement = version control center ║");
        System.out.println("║    BOM import = all versions aligned             ║");
        System.out.println("║                                                   ║");
        System.out.println("║  PROFILES: -P flag / -Dprop=val / activeByDef   ║");
        System.out.println("║    Resource filtering: ${db.url} in .properties  ║");
        System.out.println("║                                                   ║");
        System.out.println("║  CONFLICT: nearest definition wins               ║");
        System.out.println("║    Fix: declare in depMgmt / exclusions          ║");
        System.out.println("║                                                   ║");
        System.out.println("║  CI: mvn verify -B, cache ~/.m2, mvnw wrapper   ║");
        System.out.println("║  NEVER commit credentials in pom.xml             ║");
        System.out.println("║                                                   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }
}
