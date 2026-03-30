package org.example.io;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/**
 * =============================================================================
 * BÀI 6.3 — NIO.2: Path, WatchService, AsynchronousChannel
 * =============================================================================
 *
 * NIO.2 (Java 7, JSR-203) = bổ sung quan trọng cho java.nio:
 *   1. Path API       — thay thế java.io.File (OS-agnostic, immutable, fluent)
 *   2. Files utility  — static helpers: copy, move, walk, find, readAttributes
 *   3. WatchService   — file system event listener (inotify/FSEvents/ReadDirectoryChanges)
 *   4. Async Channels — true async I/O với CompletionHandler / Future
 *   5. File Attributes — read/write metadata: permissions, owner, timestamps
 *
 * ASYNC I/O (AIO) vs NIO SELECTOR:
 *   NIO Selector: app calls select() → OS says "ready" → app does read/write
 *   AIO:          app calls read() → OS does read → OS calls back app when done
 *
 *   Selector = readiness notification (you pull)
 *   AIO      = completion notification (OS pushes)
 *
 *   AIO advantage: no need to manage Selector loop — simpler code for some cases
 *   AIO disadvantage: callback hell (CompletionHandler chaining is complex)
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.io.NIO2Demo"
 */
public class NIO2Demo {

    static Path TEMP_DIR;

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 6.3 — NIO.2: Path, WatchService, Async Channels");
        System.out.println("=".repeat(70));
        System.out.println();

        TEMP_DIR = Files.createTempDirectory("java-nio2-demo-");
        System.out.println("Working directory: " + TEMP_DIR);
        System.out.println();

        try {
            demo1_pathApiAdvanced();
            demo2_fileAttributesAndPermissions();
            demo3_watchService();
            demo4_directoryWalker();
            demo5_asyncFileChannel();
            demo6_asyncServerSocketChannel();
            demo7_fileSystemProvider();
        } finally {
            cleanup();
        }

        printSAInsights();
    }

    // =========================================================================
    // DEMO 1 — Path API: immutable, composable, OS-agnostic
    // =========================================================================
    static void demo1_pathApiAdvanced() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 1 — Path API: Immutable & OS-agnostic File References");
        System.out.println("─".repeat(70));

        System.out.println("""

            Path vs File:
            ┌──────────────────────┬──────────────────────────────────────────┐
            │ java.io.File         │ java.nio.file.Path                       │
            ├──────────────────────┼──────────────────────────────────────────┤
            │ Mutable              │ Immutable (like String)                  │
            │ OS-specific sep      │ OS-agnostic (Path.of handles it)        │
            │ No Symbolic link     │ Follows/detects symlinks                 │
            │ Limited metadata     │ Full attribute support                   │
            │ No watch support     │ Integrates with WatchService             │
            └──────────────────────┴──────────────────────────────────────────┘
            """);

        // Path construction
        Path abs = Path.of("/usr/local/bin/java");      // absolute
        Path rel = Path.of("src", "main", "java");      // relative, OS separator
        Path fromStr = Paths.get("target/classes");     // legacy API, same result

        System.out.println("  [Path Construction]");
        System.out.printf("  abs:     %s%n", abs);
        System.out.printf("  rel:     %s%n", rel);
        System.out.printf("  fromStr: %s%n", fromStr);

        // Path decomposition
        Path p = TEMP_DIR.resolve("subdir/report-2026.csv");
        System.out.println("\n  [Path Decomposition: " + p + "]");
        System.out.printf("  getFileName():   %s%n", p.getFileName());
        System.out.printf("  getParent():     %s%n", p.getParent());
        System.out.printf("  getRoot():       %s%n", p.getRoot());
        System.out.printf("  getNameCount():  %d%n", p.getNameCount());
        System.out.printf("  getName(0):      %s%n", p.getName(0));
        System.out.printf("  subpath(0,2):    %s%n", p.subpath(0, Math.min(2, p.getNameCount())));

        // Path manipulation
        System.out.println("\n  [Path Manipulation]");
        Path base = TEMP_DIR.resolve("data");
        Path child = base.resolve("2026/january/report.csv");
        Path sibling = child.resolveSibling("report-backup.csv");
        Path relative = base.relativize(child);

        System.out.printf("  base:     %s%n", base);
        System.out.printf("  child:    %s%n", child);
        System.out.printf("  sibling:  %s%n", sibling);
        System.out.printf("  relative: %s  (base → child)%n", relative);

        // Normalization & comparison
        Path messy = TEMP_DIR.resolve("a/../b/./c");
        System.out.printf("%n  messy:     %s%n", messy);
        System.out.printf("  normalize: %s%n", messy.normalize());

        // toAbsolutePath vs toRealPath (toRealPath resolves symlinks, requires existence)
        Path realTemp = TEMP_DIR.toRealPath();   // resolves symlinks
        System.out.printf("  toRealPath: %s%n", realTemp);

        // Path comparison
        Path p1 = Path.of("src/Main.java");
        Path p2 = Path.of("src/Main.java");
        Path p3 = Path.of("src/Other.java");
        System.out.printf("%n  p1.equals(p2): %b%n", p1.equals(p2));
        System.out.printf("  p1.equals(p3): %b%n", p1.equals(p3));
        System.out.printf("  p1.compareTo(p3): %d (lexicographic)%n", p1.compareTo(p3));

        // startsWith / endsWith
        Path longPath = Path.of("/home/user/projects/app/src/Main.java");
        System.out.printf("  startsWith '/home/user': %b%n",
            longPath.startsWith(Path.of("/home/user")));
        System.out.printf("  endsWith   'src/Main.java': %b%n",
            longPath.endsWith(Path.of("src/Main.java")));

        // Path.of with URI
        System.out.printf("%n  Path.of(URI): %s%n",
            Path.of(TEMP_DIR.toUri()));
    }

    // =========================================================================
    // DEMO 2 — File Attributes & Permissions
    // =========================================================================
    static void demo2_fileAttributesAndPermissions() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 2 — File Attributes, Metadata & Permissions");
        System.out.println("─".repeat(70));

        Path file = TEMP_DIR.resolve("attributes-demo.txt");
        Files.writeString(file, "Attribute demo content", StandardCharsets.UTF_8);

        System.out.println("\n  [Basic Attributes via Files]");
        System.out.printf("  size:             %d bytes%n", Files.size(file));
        System.out.printf("  isRegularFile:    %b%n", Files.isRegularFile(file));
        System.out.printf("  isDirectory:      %b%n", Files.isDirectory(file));
        System.out.printf("  isHidden:         %b%n", Files.isHidden(file));
        System.out.printf("  isReadable:       %b%n", Files.isReadable(file));
        System.out.printf("  isWritable:       %b%n", Files.isWritable(file));
        System.out.printf("  isExecutable:     %b%n", Files.isExecutable(file));
        System.out.printf("  lastModifiedTime: %s%n", Files.getLastModifiedTime(file));
        System.out.printf("  creationTime:     %s%n",
            Files.readAttributes(file, BasicFileAttributes.class).creationTime());

        // BasicFileAttributes — single read() call → all attributes at once
        System.out.println("\n  [BasicFileAttributes — single syscall]");
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        System.out.printf("  creationTime:   %s%n", attrs.creationTime());
        System.out.printf("  lastModified:   %s%n", attrs.lastModifiedTime());
        System.out.printf("  lastAccess:     %s%n", attrs.lastAccessTime());
        System.out.printf("  size:           %d%n", attrs.size());
        System.out.printf("  isSymbolicLink: %b%n", attrs.isSymbolicLink());
        System.out.printf("  fileKey:        %s%n", attrs.fileKey()); // inode-like unique id

        // Modify timestamps
        FileTime newTime = FileTime.fromMillis(System.currentTimeMillis() - 86_400_000L); // 1 day ago
        Files.setLastModifiedTime(file, newTime);
        System.out.printf("%n  After setLastModifiedTime: %s%n",
            Files.getLastModifiedTime(file));

        // POSIX permissions (Unix/Linux/Mac only)
        System.out.println("\n  [POSIX Permissions (Unix only)]");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            System.out.printf("  Current perms: %s%n", PosixFilePermissions.toString(perms));

            // Set permissions
            Set<PosixFilePermission> newPerms = PosixFilePermissions.fromString("rw-r--r--");
            Files.setPosixFilePermissions(file, newPerms);
            System.out.printf("  After chmod:   %s%n",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

            // Create file with specific permissions
            Path restrictedFile = TEMP_DIR.resolve("secret.txt");
            FileAttribute<Set<PosixFilePermission>> attr =
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")); // owner only
            Files.createFile(restrictedFile, attr);
            System.out.printf("  secret.txt perms: %s%n",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(restrictedFile)));
        } else {
            System.out.println("  (POSIX permissions not available on Windows)");
            // Windows: use DosFileAttributes
            DosFileAttributes dosAttrs = Files.readAttributes(file, DosFileAttributes.class);
            System.out.printf("  DOS isReadOnly: %b%n", dosAttrs.isReadOnly());
            System.out.printf("  DOS isArchive:  %b%n", dosAttrs.isArchive());
            System.out.printf("  DOS isSystem:   %b%n", dosAttrs.isSystem());
        }

        // Symbolic link handling
        System.out.println("\n  [Symbolic Link Handling]");
        Path symlink = TEMP_DIR.resolve("link-to-file.txt");
        try {
            Files.createSymbolicLink(symlink, file);
            System.out.printf("  Created symlink: %s → %s%n",
                symlink.getFileName(), Files.readSymbolicLink(symlink).getFileName());
            System.out.printf("  isSymbolicLink:          %b%n", Files.isSymbolicLink(symlink));
            System.out.printf("  isRegularFile (follow):  %b%n", Files.isRegularFile(symlink));
            System.out.printf("  isRegularFile (nofollow):%b%n",
                Files.isRegularFile(symlink, LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException | IOException e) {
            System.out.println("  Symlinks require elevated permissions on Windows (skip)");
        }
    }

    // =========================================================================
    // DEMO 3 — WatchService: File System Event Monitoring
    // =========================================================================
    static void demo3_watchService() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 3 — WatchService: File System Event Monitoring");
        System.out.println("─".repeat(70));

        System.out.println("""

            WatchService — OS-native file system events:
              Linux:   inotify (kernel subsystem)
              macOS:   FSEvents (kernel extension)
              Windows: ReadDirectoryChangesW (Win32 API)

            Use cases:
              • Hot-reload config files (Spring Boot DevTools, Quarkus)
              • Log file tailer (tail -f equivalent)
              • Asset pipeline: recompile CSS/JS on change
              • Database WAL watcher for replication

            Events:
              ENTRY_CREATE — file/dir created in watched directory
              ENTRY_MODIFY — file modified (content or attributes)
              ENTRY_DELETE — file/dir deleted
              OVERFLOW     — event queue overflowed (some events lost)
            """);

        Path watchDir = TEMP_DIR.resolve("watched");
        Files.createDirectory(watchDir);

        AtomicInteger eventCount = new AtomicInteger(0);
        List<String> capturedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch eventsReady = new CountDownLatch(3); // expect 3 events
        AtomicBoolean stopWatch = new AtomicBoolean(false);

        // ── WatchService thread ──
        Thread watchThread = Thread.ofVirtual().start(() -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {

                // Register directory for all event types
                watchDir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

                System.out.printf("  [Watcher] Monitoring: %s%n", watchDir);

                while (!stopWatch.get()) {
                    // poll with timeout (non-blocking alternative to take())
                    WatchKey key = watcher.poll(500, TimeUnit.MILLISECONDS);
                    if (key == null) continue; // timeout, no events

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();

                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            System.out.println("  [Watcher] WARNING: event queue overflow!");
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                        Path changed = watchDir.resolve(pathEvent.context());
                        int count = pathEvent.count(); // repeated events batched

                        String msg = String.format("  [Watcher] %-15s | %s (×%d)",
                            kind.name(), changed.getFileName(), count);
                        capturedEvents.add(msg);
                        System.out.println(msg);
                        eventCount.incrementAndGet();
                        eventsReady.countDown();
                    }

                    // CRITICAL: reset key to receive further events
                    boolean valid = key.reset();
                    if (!valid) {
                        System.out.println("  [Watcher] Directory no longer accessible!");
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // Give watcher time to register
        Thread.sleep(200);

        // ── Trigger file events ──
        System.out.println("\n  [Triggering file system events...]");

        // Event 1: CREATE
        Path newFile = watchDir.resolve("config.yaml");
        Files.writeString(newFile, "server:\n  port: 8080\n", StandardCharsets.UTF_8);
        System.out.println("  [Test] Created config.yaml");
        Thread.sleep(100);

        // Event 2: MODIFY
        Files.writeString(newFile, "server:\n  port: 9090\n", StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("  [Test] Modified config.yaml");
        Thread.sleep(100);

        // Event 3: DELETE
        Files.delete(newFile);
        System.out.println("  [Test] Deleted config.yaml");

        // Wait for all events
        boolean allCaptured = eventsReady.await(3, TimeUnit.SECONDS);
        stopWatch.set(true);
        watchThread.join(1000);

        System.out.printf("%n  Events captured: %d (expected ≥3, allCaptured=%b)%n",
            eventCount.get(), allCaptured);

        System.out.println("""

            WatchService CAVEATS:
              • key.reset() MUST be called after processing — without it, no more events!
              • WatchService watches DIRECTORIES, not individual files
              • Recursive watching: register each subdirectory separately (WatchService is NOT recursive)
              • OVERFLOW: fast file changes may coalesce — event.count() > 1
              • macOS: polling-based fallback possible (higher latency ~2s vs 10ms on Linux)

            PRODUCTION PATTERN (recursive hot-reload):
              void registerRecursive(Path dir, WatchService ws) throws IOException {
                  dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                  try (var s = Files.walk(dir)) {
                      s.filter(Files::isDirectory)
                       .forEach(d -> { try { d.register(ws, ...); } catch (...) {} });
                  }
              }
              // On ENTRY_CREATE of a directory → register it too
            """);
    }

    // =========================================================================
    // DEMO 4 — Directory Walker: Files.walk, find, walkFileTree
    // =========================================================================
    static void demo4_directoryWalker() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 4 — Directory Walker: walk, find, walkFileTree");
        System.out.println("─".repeat(70));

        // Setup a directory tree
        Path tree = TEMP_DIR.resolve("project");
        Files.createDirectories(tree.resolve("src/main/java/com/example"));
        Files.createDirectories(tree.resolve("src/test/java/com/example"));
        Files.createDirectories(tree.resolve("target/classes"));

        String[] sourceFiles = {"Main.java", "Service.java", "Repository.java"};
        String[] testFiles   = {"MainTest.java", "ServiceTest.java"};
        String[] classFiles  = {"Main.class", "Service.class", "Repository.class"};

        for (String f : sourceFiles)
            Files.writeString(tree.resolve("src/main/java/com/example/" + f),
                "// " + f, StandardCharsets.UTF_8);
        for (String f : testFiles)
            Files.writeString(tree.resolve("src/test/java/com/example/" + f),
                "// " + f, StandardCharsets.UTF_8);
        for (String f : classFiles)
            Files.write(tree.resolve("target/classes/" + f), new byte[]{0xCA, (byte)0xFE});

        // Files.walk — depth-first traversal
        System.out.println("  [Files.walk — full tree]");
        try (Stream<Path> walk = Files.walk(tree)) {
            walk.forEach(p -> {
                int depth = tree.relativize(p).getNameCount();
                String indent = "  ".repeat(depth);
                boolean isDir = Files.isDirectory(p);
                System.out.printf("  %s%s%s%n", indent, p.getFileName(), isDir ? "/" : "");
            });
        }

        // Files.find — walk + filter in one call
        System.out.println("\n  [Files.find — only .java files, max depth 10]");
        try (Stream<Path> found = Files.find(tree, 10,
                (path, attrs) -> attrs.isRegularFile()
                    && path.toString().endsWith(".java"))) {
            found.map(tree::relativize)
                 .forEach(p -> System.out.println("    " + p));
        }

        // Files.list — shallow directory listing (non-recursive)
        System.out.println("\n  [Files.list — top-level only (non-recursive)]");
        try (Stream<Path> list = Files.list(tree)) {
            list.forEach(p -> System.out.printf("    %s%s%n",
                p.getFileName(), Files.isDirectory(p) ? "/" : ""));
        }

        // walkFileTree with FileVisitor — full control over traversal
        System.out.println("\n  [walkFileTree with FileVisitor — count + filter]");
        AtomicInteger javaCount  = new AtomicInteger();
        AtomicInteger classCount = new AtomicInteger();
        AtomicLong    totalSize  = new AtomicLong();

        Files.walkFileTree(tree, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                totalSize.addAndGet(attrs.size());
                String name = file.getFileName().toString();
                if (name.endsWith(".java"))  javaCount.incrementAndGet();
                if (name.endsWith(".class")) classCount.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Skip target directory (like mvn clean)
                if (dir.getFileName().toString().equals("target")) {
                    System.out.println("    [Skipping target/ directory]");
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.println("  Failed to visit: " + file + " → " + exc.getMessage());
                return FileVisitResult.CONTINUE; // skip unreadable files, don't abort
            }
        });

        System.out.printf("    .java files:  %d%n", javaCount.get());
        System.out.printf("    .class files: %d (skipped — in target/)%n", classCount.get());
        System.out.printf("    Total size:   %d bytes%n", totalSize.get());

        // Recursive delete (common utility)
        System.out.println("\n  [Recursive delete — delete entire tree]");
        long deleted = Files.walk(tree)
            .sorted(Comparator.reverseOrder()) // delete children before parents
            .peek(p -> {})
            .mapToLong(p -> {
                try { Files.delete(p); return 1; }
                catch (IOException e) { return 0; }
            }).sum();
        System.out.printf("    Deleted %d entries%n", deleted);

        System.out.println("""
            FileVisitResult options:
              CONTINUE      — continue traversal normally
              SKIP_SUBTREE  — skip this directory's contents (preVisitDirectory only)
              SKIP_SIBLINGS — skip remaining siblings in current directory
              TERMINATE     — stop entire walk immediately
            """);
    }

    // =========================================================================
    // DEMO 5 — AsynchronousFileChannel: true async I/O
    // =========================================================================
    static void demo5_asyncFileChannel() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 5 — AsynchronousFileChannel: Async File I/O");
        System.out.println("─".repeat(70));

        System.out.println("""

            AsynchronousFileChannel — OS-level async I/O (AIO):
              • read/write return immediately (no blocking!)
              • Result delivered via: Future<Integer> OR CompletionHandler callback
              • OS uses thread pool (or kernel async I/O) to complete operation
              • Good for: high-throughput file I/O without blocking threads

            TWO ASYNC PATTERNS:
              1. Future<Integer>         — pull model: future.get() blocks until done
              2. CompletionHandler       — push model: callback fired on completion
            """);

        Path asyncFile = TEMP_DIR.resolve("async-test.bin");
        int DATA_SIZE = 64 * 1024; // 64KB

        // Prepare data
        byte[] writeData = new byte[DATA_SIZE];
        new Random(42).nextBytes(writeData);

        // ── Pattern 1: Future-based async write ──
        System.out.println("  [Pattern 1: Future-based async write]");
        try (AsynchronousFileChannel afc = AsynchronousFileChannel.open(
                asyncFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {

            ByteBuffer buf = ByteBuffer.wrap(writeData);
            long writeStart = System.nanoTime();

            Future<Integer> writeFuture = afc.write(buf, 0); // async write at position 0
            System.out.println("    write() returned immediately — doing other work...");

            // Simulate other work while I/O in progress
            double sideWork = IntStream.range(0, 1000).asDoubleStream()
                .map(Math::sqrt).sum();

            int bytesWritten = writeFuture.get(); // wait for completion
            long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
            System.out.printf("    Written: %,d bytes in %d ms (sideWork=%.1f)%n",
                bytesWritten, writeMs, sideWork);
        }

        // ── Pattern 2: CompletionHandler async read ──
        System.out.println("\n  [Pattern 2: CompletionHandler async read (callback)]");
        CountDownLatch readLatch = new CountDownLatch(1);
        AtomicInteger bytesReadRef = new AtomicInteger();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try (AsynchronousFileChannel afc = AsynchronousFileChannel.open(
                asyncFile, StandardOpenOption.READ)) {

            ByteBuffer readBuf = ByteBuffer.allocate(DATA_SIZE);

            afc.read(readBuf, 0, readBuf, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer result, ByteBuffer attachment) {
                    // Called on OS thread pool — NOT the caller thread
                    bytesReadRef.set(result);
                    attachment.flip();
                    System.out.printf("    CompletionHandler.completed(): read %,d bytes%n", result);
                    System.out.printf("    Thread: %s%n", Thread.currentThread().getName());
                    readLatch.countDown();
                }

                @Override
                public void failed(Throwable exc, ByteBuffer attachment) {
                    errorRef.set(exc);
                    System.err.println("    CompletionHandler.failed(): " + exc.getMessage());
                    readLatch.countDown();
                }
            });

            System.out.println("    read() call returned — waiting for callback...");
            boolean done = readLatch.await(5, TimeUnit.SECONDS);
            System.out.printf("    Done: %b, bytes read: %,d%n", done, bytesReadRef.get());
        }

        // ── Pattern 3: Chained async read → process → write (pipeline) ──
        System.out.println("\n  [Pattern 3: Async pipeline — read → transform → write]");
        Path outputFile = TEMP_DIR.resolve("async-output.bin");
        CountDownLatch pipelineDone = new CountDownLatch(1);
        AtomicLong pipelineStart = new AtomicLong(System.nanoTime());

        try (AsynchronousFileChannel reader = AsynchronousFileChannel.open(asyncFile, StandardOpenOption.READ);
             AsynchronousFileChannel writer = AsynchronousFileChannel.open(outputFile,
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            ByteBuffer buf = ByteBuffer.allocate(DATA_SIZE);

            // Step 1: async read
            reader.read(buf, 0, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer bytesRead, Void unused) {
                    buf.flip();

                    // Step 2: transform (XOR each byte — trivial example)
                    ByteBuffer transformed = ByteBuffer.allocate(buf.remaining());
                    while (buf.hasRemaining()) transformed.put((byte)(buf.get() ^ 0xFF));
                    transformed.flip();

                    // Step 3: async write result
                    writer.write(transformed, 0, null, new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer bytesWritten, Void unused2) {
                            long ms = (System.nanoTime() - pipelineStart.get()) / 1_000_000;
                            System.out.printf("    Pipeline complete: read %,d → transform → write %,d bytes in %d ms%n",
                                bytesRead, bytesWritten, ms);
                            pipelineDone.countDown();
                        }
                        @Override
                        public void failed(Throwable exc, Void unused2) { pipelineDone.countDown(); }
                    });
                }
                @Override
                public void failed(Throwable exc, Void unused) { pipelineDone.countDown(); }
            });

            pipelineDone.await(5, TimeUnit.SECONDS);
        }

        System.out.println("""
            AsynchronousFileChannel vs FileChannel:
            ┌──────────────────────────┬──────────────────────────────────────┐
            │ FileChannel (blocking)    │ AsynchronousFileChannel (AIO)        │
            ├──────────────────────────┼──────────────────────────────────────┤
            │ Blocks caller thread     │ Returns immediately                  │
            │ Simple sequential code   │ CompletionHandler chaining           │
            │ Good: config, reports    │ Good: high-throughput I/O pipelines  │
            │ Works with Selector      │ Does NOT work with Selector          │
            └──────────────────────────┴──────────────────────────────────────┘

            SA ADVICE: In most apps, use:
              • Files.readString/writeString for small files
              • FileChannel + ByteBuffer for large sequential files
              • AsynchronousFileChannel only if I/O is TRUE bottleneck at scale
              • Virtual Threads + blocking I/O is simpler and often equivalent
            """);
    }

    // =========================================================================
    // DEMO 6 — AsynchronousServerSocketChannel: async TCP server
    // =========================================================================
    static void demo6_asyncServerSocketChannel() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6 — AsynchronousServerSocketChannel: Async TCP Server");
        System.out.println("─".repeat(70));

        System.out.println("""

            AsynchronousServerSocketChannel — NIO.2 async TCP server:
              accept() returns immediately → CompletionHandler called when client connects
              No Selector needed — OS manages completion notifications

            Architecture: 1 server channel, OS thread pool handles completions
            """);

        int PORT = 19885;
        AtomicInteger clientsServed = new AtomicInteger(0);
        CountDownLatch allServed = new CountDownLatch(3);
        AtomicBoolean serverStop = new AtomicBoolean(false);

        // ── Async Server ──
        AsynchronousServerSocketChannel server =
            AsynchronousServerSocketChannel.open()
                .bind(new InetSocketAddress("localhost", PORT));

        System.out.printf("  [Async Server] listening on port %d%n", PORT);

        // Recursive accept pattern (each accept triggers next accept)
        CompletionHandler<AsynchronousSocketChannel, Void> acceptHandler =
            new CompletionHandler<>() {
                @Override
                public void completed(AsynchronousSocketChannel client, Void unused) {
                    // Accept next client BEFORE processing current one
                    if (!serverStop.get()) {
                        server.accept(null, this); // recurse for next client
                    }

                    // Handle current client
                    ByteBuffer buf = ByteBuffer.allocate(128);
                    try {
                        // Read request (blocking for demo simplicity, would chain CompletionHandler in prod)
                        Future<Integer> readFuture = client.read(buf);
                        int n = readFuture.get(2, TimeUnit.SECONDS);
                        buf.flip();
                        String request = StandardCharsets.UTF_8.decode(buf).toString().strip();
                        String response = "ASYNC-ECHO: " + request;

                        // Write response
                        Future<Integer> writeFuture = client.write(
                            StandardCharsets.UTF_8.encode(response));
                        writeFuture.get(2, TimeUnit.SECONDS);

                        System.out.printf("    Served client %d: \"%s\" → \"%s\"%n",
                            clientsServed.incrementAndGet(), request, response);
                        client.close();
                        allServed.countDown();
                    } catch (Exception e) {
                        try { client.close(); } catch (Exception ignored) {}
                    }
                }

                @Override
                public void failed(Throwable exc, Void unused) {
                    if (!serverStop.get())
                        System.err.println("  Accept failed: " + exc.getMessage());
                }
            };

        server.accept(null, acceptHandler); // start first accept

        Thread.sleep(100);

        // ── 3 async clients ──
        System.out.println("  [Sending 3 async client requests...]");
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            final int id = i;
            CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> {
                try (AsynchronousSocketChannel ch = AsynchronousSocketChannel.open()) {
                    Future<Void> conn = ch.connect(new InetSocketAddress("localhost", PORT));
                    conn.get(2, TimeUnit.SECONDS);

                    String msg = "Hello-" + id;
                    Future<Integer> write = ch.write(StandardCharsets.UTF_8.encode(msg));
                    write.get(2, TimeUnit.SECONDS);

                    ByteBuffer resp = ByteBuffer.allocate(128);
                    Future<Integer> read = ch.read(resp);
                    read.get(2, TimeUnit.SECONDS);
                    resp.flip();
                    return StandardCharsets.UTF_8.decode(resp).toString().strip();
                } catch (Exception e) {
                    return "ERROR: " + e.getMessage();
                }
            });
            futures.add(cf);
        }

        // Wait and print results
        allServed.await(5, TimeUnit.SECONDS);
        for (int i = 0; i < futures.size(); i++) {
            System.out.printf("  Client-%d response: \"%s\"%n", i, futures.get(i).get(1, TimeUnit.SECONDS));
        }

        serverStop.set(true);
        server.close();
        System.out.printf("  Total clients served: %d%n", clientsServed.get());

        System.out.println("""
            ASYNC CHANNEL THREAD MODEL:
              AsynchronousChannelGroup — shared thread pool for all async channels
              Default: AsynchronousChannelGroup.withFixedThreadPool(N, factory)
              Completion handlers run on THIS thread pool (not caller thread!)
              → CompletionHandler must be thread-safe
              → Long-running handler blocks pool thread → deadlock risk

            PRODUCTION: use Netty or Vert.x instead of raw AIO
              → They handle thread pool, backpressure, buffer pooling for you
            """);
    }

    // =========================================================================
    // DEMO 7 — FileSystem Provider: ZIP FileSystem
    // =========================================================================
    static void demo7_fileSystemProvider() throws Exception {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 7 — FileSystem Provider: ZIP as FileSystem");
        System.out.println("─".repeat(70));

        System.out.println("""

            Java NIO.2 supports pluggable FileSystems:
              Default:  file system provider (local OS FS)
              ZIP/JAR:  treat ZIP as a virtual file system (built-in since Java 7)
              SFTP:     third-party (Apache SSHD, JSch)
              S3:       third-party (Amazon SDK FileSystem provider)

            ZIP FileSystem: read/write ZIP files using standard Path/Files API!
            """);

        Path zipFile = TEMP_DIR.resolve("archive.zip");

        // Create ZIP FileSystem and write files into it
        System.out.println("  [Creating ZIP archive via FileSystem API]");
        Map<String, String> env = Map.of("create", "true");

        try (FileSystem zipFs = FileSystems.newFileSystem(zipFile, env)) {
            // Write files into ZIP as if it were a real directory
            Path zipRoot = zipFs.getPath("/");

            // Create directory inside ZIP
            Path zipSrcDir = zipFs.getPath("/src");
            Files.createDirectory(zipSrcDir);

            // Write files
            Files.writeString(zipFs.getPath("/README.txt"),
                "This is inside a ZIP file!\n", StandardCharsets.UTF_8);
            Files.writeString(zipFs.getPath("/src/Main.java"),
                "public class Main { public static void main(String[] a) {} }\n",
                StandardCharsets.UTF_8);
            Files.writeString(zipFs.getPath("/config.properties"),
                "host=localhost\nport=8080\n", StandardCharsets.UTF_8);

            System.out.println("    Files written into ZIP:");
            try (Stream<Path> walk = Files.walk(zipRoot)) {
                walk.filter(p -> !p.equals(zipRoot))
                    .forEach(p -> System.out.printf("      %s (%d bytes)%n",
                        p,
                        Files.isRegularFile(p) ? silentSize(p) : 0));
            }
        }

        System.out.printf("  ZIP file size on disk: %,d bytes%n", Files.size(zipFile));

        // Read from ZIP FileSystem
        System.out.println("\n  [Reading from ZIP archive]");
        try (FileSystem zipFs = FileSystems.newFileSystem(zipFile, (ClassLoader) null)) {
            String readme = Files.readString(zipFs.getPath("/README.txt"), StandardCharsets.UTF_8);
            System.out.printf("    /README.txt: \"%s\"%n", readme.strip());

            List<String> configLines = Files.readAllLines(
                zipFs.getPath("/config.properties"), StandardCharsets.UTF_8);
            System.out.printf("    /config.properties: %s%n", configLines);

            // List ZIP contents
            System.out.println("    ZIP contents:");
            try (Stream<Path> walk = Files.walk(zipFs.getPath("/"))) {
                walk.filter(Files::isRegularFile)
                    .forEach(p -> System.out.printf("      %s%n", p));
            }
        }

        // JAR is also a ZIP — read class from JAR
        System.out.println("\n  [ZIP FileSystem use cases]");
        System.out.println("""
            USE CASES:
              • Deploy config: zip config + resources, read via ZipFileSystem at startup
              • JAR inspection: open JARs with ZipFileSystem, read manifest, classes
              • APK/WAR unpacking: no temp directory needed, read in-place
              • Package assets: game resources, plugin JARs
              • Atomic deploy: write to .zip → rename to replace (atomic swap)
            """);
    }

    static long silentSize(Path p) {
        try { return Files.size(p); } catch (Exception e) { return -1; }
    }

    // =========================================================================
    // Cleanup
    // =========================================================================
    static void cleanup() {
        try {
            try (Stream<Path> walk = Files.walk(TEMP_DIR)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            }
            System.out.println("\n  [Temp files cleaned up]");
        } catch (Exception e) {
            System.out.println("  [Cleanup warning: " + e.getMessage() + "]");
        }
    }

    // =========================================================================
    // SA Insights
    // =========================================================================
    static void printSAInsights() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 6.3 — NIO.2 Insights");
        System.out.println("=".repeat(70));
        System.out.println("""

            NIO.2 COMPONENT SUMMARY:
            ┌───────────────────────────┬────────────────────────────────────────┐
            │ Component                 │ Use Case                                │
            ├───────────────────────────┼────────────────────────────────────────┤
            │ Path                      │ Immutable file reference (replace File) │
            │ Files                     │ Static utilities: copy, move, walk, read│
            │ WatchService              │ Hot-reload, log tailer, change detection│
            │ AsynchronousFileChannel   │ Non-blocking large-file I/O pipelines   │
            │ AsynchronousServerSocket  │ Async TCP server (use Netty in practice)│
            │ ZipFileSystem             │ Read/write ZIP/JAR as filesystem        │
            └───────────────────────────┴────────────────────────────────────────┘

            PATH RULES:
              ✓ Use Path.of() / Paths.get() — never new File() in new code
              ✓ Path is immutable — safe to share across threads
              ✓ normalize() before comparing paths (removes ./ ../)
              ✓ toRealPath() for canonical path (resolves symlinks, requires existence)

            WATCHSERVICE RULES:
              ✓ key.reset() after processing — mandatory, else no more events
              ✓ Watch directories, not files — register parent dir, filter by name
              ✓ OVERFLOW: handle gracefully — do full rescan on overflow
              ✓ Recursive: manually register each subdirectory + on ENTRY_CREATE dirs

            ASYNC I/O RULES:
              ✓ CompletionHandler runs on pool thread — must be thread-safe
              ✓ Do NOT block inside CompletionHandler — starves thread pool
              ✓ Chain CompletionHandlers for pipelines — but consider complexity
              ✓ Virtual Threads (Java 21) + blocking I/O = simpler async in practice

            WALKFILETREE RULES:
              ✓ visitFileFailed: return CONTINUE, not TERMINATE (skip unreadable)
              ✓ Recursive delete: sorted(reverseOrder()) → children before parents
              ✓ SKIP_SUBTREE in preVisitDirectory to exclude build artifacts

            SA INSIGHT:
              "NIO.2 Path + Files API = modern file I/O foundation.
               WatchService = foundation for hot-reload in any framework.
               AsynchronousFileChannel = only when blocking is proven bottleneck.
               For everything else: Virtual Threads make blocking I/O scale."
            """);
    }
}
