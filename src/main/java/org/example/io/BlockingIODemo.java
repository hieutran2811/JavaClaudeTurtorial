package org.example.io;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * =============================================================================
 * BÀI 6.1 — Blocking I/O: Stream, BufferedIO, RandomAccessFile, MappedFile
 * =============================================================================
 *
 * Java I/O EVOLUTION:
 *   java.io  (Java 1.0) — Stream-based, blocking, character/byte oriented
 *   java.nio (Java 1.4) — Buffer/Channel, non-blocking capable, memory-mapped
 *   java.nio.file (Java 7) — Path API, Files utility, WatchService
 *
 * BLOCKING vs NON-BLOCKING:
 *   Blocking  = thread waits until data ready (read/write completes)
 *   Non-blocking = thread returns immediately if data not ready (NIO Selector)
 *   → Bài này: Blocking I/O (java.io + FileChannel blocking mode)
 *   → Bài 6.2: Non-blocking NIO (Selector, ByteBuffer, Channel)
 *
 * STREAM HIERARCHY:
 *   InputStream  → FileInputStream, BufferedInputStream, DataInputStream
 *   OutputStream → FileOutputStream, BufferedOutputStream, DataOutputStream
 *   Reader       → InputStreamReader, BufferedReader, FileReader
 *   Writer       → OutputStreamWriter, BufferedWriter, FileWriter
 *
 * KEY RULE — Always Buffer:
 *   FileInputStream (unbuffered) → 1 syscall per byte → terrible performance
 *   BufferedInputStream (8KB buffer) → 1 syscall per 8KB → 1000x faster
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.io.BlockingIODemo"
 */
public class BlockingIODemo {

    // Temp directory for demo files
    static Path TEMP_DIR;

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 6.1 — Blocking I/O: Stream, Buffer, Channel, MMap");
        System.out.println("=".repeat(70));
        System.out.println();

        TEMP_DIR = Files.createTempDirectory("java-io-demo-");
        System.out.println("Working directory: " + TEMP_DIR);
        System.out.println();

        try {
            demo1_streamBasics();
            demo2_bufferedVsUnbuffered();
            demo3_textIoWithEncoding();
            demo4_dataStreams();
            demo5_randomAccessFile();
            demo6_fileChannelAndByteBuffer();
            demo7_memoryMappedFile();
            demo8_filesUtilityApi();
            demo9_bufferSizeTuning();
        } finally {
            cleanup();
        }

        printSAInsights();
    }

    // =========================================================================
    // DEMO 1 — Stream Basics: read/write byte streams
    // =========================================================================
    static void demo1_streamBasics() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 1 — Stream Basics: InputStream / OutputStream");
        System.out.println("─".repeat(70));

        System.out.println("""

            STREAM = sequence of bytes, unidirectional, sequential access.
            Luôn dùng try-with-resources để đảm bảo close() được gọi.
            """);

        Path file = TEMP_DIR.resolve("basics.bin");

        // Write bytes
        System.out.println("  [Writing bytes with FileOutputStream]");
        try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
            byte[] data = "Hello, Java I/O World!\n".getBytes(StandardCharsets.UTF_8);
            fos.write(data);
            fos.write(42);          // single byte
            fos.write(new byte[]{10, 20, 30, 40, 50}); // byte array
        }
        System.out.printf("    Written: %d bytes%n", Files.size(file));

        // Read bytes
        System.out.println("  [Reading bytes with FileInputStream]");
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            byte[] buf = new byte[32];
            int bytesRead = fis.read(buf);  // reads UP TO buf.length bytes
            System.out.printf("    Read %d bytes: \"%s\"%n",
                bytesRead, new String(buf, 0, Math.min(bytesRead, 23), StandardCharsets.UTF_8).trim());

            // Read single byte
            int b = fis.read();  // returns -1 at EOF
            System.out.printf("    Next byte value: %d (ASCII '%c')%n", b, (char) b);
        }

        // Read ALL bytes (Java 9+)
        byte[] allBytes = Files.readAllBytes(file);
        System.out.printf("  [Files.readAllBytes]: %d bytes total%n", allBytes.length);

        System.out.println("""
            IMPORTANT:
              • fis.read(buf) may return LESS than buf.length (partial read)
              • Loop until return -1 for complete reads:
                  int n; while ((n = fis.read(buf)) != -1) { process(buf, n); }
              • Files.readAllBytes() = convenience, loads entire file into RAM
                → Only for small files (<100MB), use streaming for large files
            """);
    }

    // =========================================================================
    // DEMO 2 — Buffered vs Unbuffered: Performance comparison
    // =========================================================================
    static void demo2_bufferedVsUnbuffered() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 2 — Buffered vs Unbuffered: Performance Impact");
        System.out.println("─".repeat(70));

        System.out.println("""

            WHY BUFFERING MATTERS:
              FileOutputStream.write(1 byte) → 1 system call (kernel transition)
              System call overhead: ~1-10 microseconds each
              Writing 1MB unbuffered: 1,048,576 syscalls → seconds
              Writing 1MB buffered:   128 syscalls (8KB buffer) → milliseconds
            """);

        int DATA_SIZE = 512 * 1024; // 512KB
        byte[] data = new byte[DATA_SIZE];
        new Random(42).nextBytes(data);

        // Unbuffered write (byte by byte)
        Path unbufferedFile = TEMP_DIR.resolve("unbuffered.bin");
        System.out.println("  [Unbuffered write — 1 byte at a time (1 syscall per byte)]");
        long start = System.nanoTime();
        try (FileOutputStream fos = new FileOutputStream(unbufferedFile.toFile())) {
            for (byte b : data) fos.write(b);  // 512K syscalls!
        }
        long unbufferedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("    Unbuffered write: %,d ms for %,d bytes%n", unbufferedMs, DATA_SIZE);

        // Buffered write
        Path bufferedFile = TEMP_DIR.resolve("buffered.bin");
        System.out.println("  [Buffered write — 8KB buffer (default)]");
        start = System.nanoTime();
        try (BufferedOutputStream bos = new BufferedOutputStream(
                new FileOutputStream(bufferedFile.toFile()))) {
            for (byte b : data) bos.write(b);  // buffered: ~64 syscalls
        }
        long bufferedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("    Buffered write:   %,d ms for %,d bytes%n", bufferedMs, DATA_SIZE);

        // Bulk write (fastest)
        Path bulkFile = TEMP_DIR.resolve("bulk.bin");
        System.out.println("  [Bulk write — single write(byte[]) call]");
        start = System.nanoTime();
        try (FileOutputStream fos = new FileOutputStream(bulkFile.toFile())) {
            fos.write(data);  // 1 syscall for entire array
        }
        long bulkMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("    Bulk write:       %,d ms for %,d bytes%n", bulkMs, DATA_SIZE);

        if (unbufferedMs > 0) {
            System.out.printf("%n  Speedup — buffered vs unbuffered: %.1fx%n",
                (double) unbufferedMs / Math.max(bufferedMs, 1));
            System.out.printf("  Speedup — bulk vs unbuffered:     %.1fx%n",
                (double) unbufferedMs / Math.max(bulkMs, 1));
        }

        System.out.println("""
            RULE: Always wrap FileInputStream/FileOutputStream with Buffered*:
              new BufferedInputStream(new FileInputStream(file))
              new BufferedOutputStream(new FileOutputStream(file))

            Or use Files API which handles buffering internally:
              Files.write(path, bytes)
              Files.copy(src, dst)
            """);
    }

    // =========================================================================
    // DEMO 3 — Text I/O with Encoding
    // =========================================================================
    static void demo3_textIoWithEncoding() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 3 — Text I/O: Encoding, Reader/Writer, BufferedReader");
        System.out.println("─".repeat(70));

        System.out.println("""

            ENCODING PITFALLS:
              FileReader/FileWriter — dùng platform default charset (DANGER!)
              On Windows: Cp1252 (or UTF-8 in Java 18+)
              On Linux:   UTF-8
              → Code chạy đúng trên Linux, sai trên Windows → prod incident!

            FIX: luôn specify charset explicitly:
              new InputStreamReader(fis, StandardCharsets.UTF_8)
              new OutputStreamWriter(fos, StandardCharsets.UTF_8)
            """);

        Path textFile = TEMP_DIR.resolve("text.txt");
        String content = "Hello World\nChinese: 中文\nVietnamese: Tiếng Việt\nEmoji: ☕🚀\n";

        // Write with explicit UTF-8
        System.out.println("  [Writing text with explicit UTF-8 encoding]");
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(textFile.toFile()), StandardCharsets.UTF_8))) {
            writer.write(content);
        }
        System.out.printf("    Written %d chars as %d UTF-8 bytes%n",
            content.length(), Files.size(textFile));

        // Read line by line (streaming — memory efficient)
        System.out.println("  [Reading line by line with BufferedReader]");
        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(textFile.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.printf("    Line %d: %s%n", ++lineCount, line);
            }
        }

        // Modern API: Files.lines() (Java 8+) — lazy stream, line by line
        System.out.println("\n  [Files.lines() — lazy Stream<String>]");
        try (var lines = Files.lines(textFile, StandardCharsets.UTF_8)) {
            long count = lines
                .filter(l -> !l.isBlank())
                .peek(l -> System.out.println("    > " + l))
                .count();
            System.out.printf("    Non-blank lines: %d%n", count);
        } // stream closed → file closed automatically

        // Charset detection basics
        System.out.println("""
            CHARSET ENCODING GUIDE:
            ┌──────────────┬──────────────────────────────────────────────────┐
            │ Charset      │ Use Case                                          │
            ├──────────────┼──────────────────────────────────────────────────┤
            │ UTF-8        │ Default for everything: web, files, APIs          │
            │ UTF-16       │ Windows native (Java String internal encoding)    │
            │ ISO-8859-1   │ Legacy HTTP, Latin languages                      │
            │ US-ASCII     │ Config files, protocols (safe subset of UTF-8)   │
            └──────────────┴──────────────────────────────────────────────────┘

            BOM (Byte Order Mark):
              UTF-8 BOM = EF BB BF at file start (optional, Windows Notepad adds it)
              → strip BOM if present: if first char == \\uFEFF, skip it
              → Apache Commons IO: BOMInputStream handles this
            """);
    }

    // =========================================================================
    // DEMO 4 — DataInputStream/DataOutputStream: typed binary protocol
    // =========================================================================
    static void demo4_dataStreams() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 4 — DataStreams: Typed Binary Read/Write");
        System.out.println("─".repeat(70));

        System.out.println("""

            DataOutputStream: write typed primitives in big-endian binary format.
            DataInputStream:  read them back exactly.
            Use case: binary file formats, custom network protocols, serialization.
            """);

        Path dataFile = TEMP_DIR.resolve("typed.dat");

        // Write typed data
        System.out.println("  [Writing typed binary data]");
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(dataFile.toFile())))) {
            dos.writeInt(42);                       // 4 bytes
            dos.writeLong(System.currentTimeMillis()); // 8 bytes
            dos.writeDouble(3.14159265358979);      // 8 bytes
            dos.writeBoolean(true);                 // 1 byte
            dos.writeUTF("Hello binary!");          // 2-byte length prefix + UTF-8 bytes
        }
        System.out.printf("    File size: %d bytes (4+8+8+1+2+13)%n", Files.size(dataFile));

        // Read typed data back
        System.out.println("  [Reading typed binary data back]");
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(dataFile.toFile())))) {
            int   intVal  = dis.readInt();
            long  longVal = dis.readLong();
            double dblVal = dis.readDouble();
            boolean boolVal = dis.readBoolean();
            String strVal = dis.readUTF();

            System.out.printf("    int:     %d%n", intVal);
            System.out.printf("    long:    %d%n", longVal);
            System.out.printf("    double:  %.5f%n", dblVal);
            System.out.printf("    boolean: %b%n", boolVal);
            System.out.printf("    String:  \"%s\"%n", strVal);
        }

        System.out.println("""
            DataStream vs ObjectStream vs JSON:
            ┌──────────────────┬───────────────────────────────────────────────┐
            │ Format           │ Use Case                                       │
            ├──────────────────┼───────────────────────────────────────────────┤
            │ DataStream       │ Custom binary protocol, max performance        │
            │ ObjectStream     │ Java-only serialization (avoid in new code)   │
            │ JSON (Jackson)   │ Interop, REST APIs, human-readable             │
            │ Protobuf / Avro  │ Schema-driven, compact, cross-language         │
            └──────────────────┴───────────────────────────────────────────────┘

            AVOID Java ObjectSerialization (Serializable):
              • Security: gadget chain attacks (Log4Shell-style)
              • Fragile: serialVersionUID mismatch breaks compatibility
              • Alternative: Jackson, Gson, Protobuf, records + JSON
            """);
    }

    // =========================================================================
    // DEMO 5 — RandomAccessFile: seek to any position
    // =========================================================================
    static void demo5_randomAccessFile() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 5 — RandomAccessFile: Seek & Update at Any Position");
        System.out.println("─".repeat(70));

        System.out.println("""

            RandomAccessFile: read AND write at arbitrary file positions.
            Use case:
              • Binary file formats with fixed-size records (database pages)
              • Update header/checksum after writing body
              • Append to log without reading entire file
              • Implement simple file-based index
            """);

        Path rafFile = TEMP_DIR.resolve("records.dat");
        int RECORD_SIZE = 16; // fixed-size record: 4 (id) + 8 (value) + 4 (checksum)

        // Write 5 fixed-size records
        System.out.println("  [Writing 5 fixed-size records]");
        try (RandomAccessFile raf = new RandomAccessFile(rafFile.toFile(), "rw")) {
            for (int i = 0; i < 5; i++) {
                raf.writeInt(i);                  // 4 bytes: record id
                raf.writeLong((long) i * 1000);   // 8 bytes: value
                raf.writeInt(i * 7 % 31);         // 4 bytes: simple checksum
            }
        }
        System.out.printf("    File size: %d bytes (%d records × %d bytes)%n",
            Files.size(rafFile), 5, RECORD_SIZE);

        // Read specific record by index (O(1) seek)
        System.out.println("\n  [Random access — read record #3 directly]");
        try (RandomAccessFile raf = new RandomAccessFile(rafFile.toFile(), "r")) {
            int targetRecord = 3;
            long offset = (long) targetRecord * RECORD_SIZE;
            raf.seek(offset);                   // jump to record position

            int id       = raf.readInt();
            long value   = raf.readLong();
            int checksum = raf.readInt();
            System.out.printf("    Record[%d]: id=%d, value=%d, checksum=%d, offset=%d%n",
                targetRecord, id, value, checksum, offset);
        }

        // Update record in-place (no full file rewrite)
        System.out.println("\n  [Update record #2 in-place (seek + overwrite)]");
        try (RandomAccessFile raf = new RandomAccessFile(rafFile.toFile(), "rw")) {
            int targetRecord = 2;
            long offset = (long) targetRecord * RECORD_SIZE + 4; // skip id (4 bytes) → value
            raf.seek(offset);
            raf.writeLong(99999L);   // overwrite value field only
            System.out.printf("    Updated record[%d] value to 99999 at offset %d%n",
                targetRecord, offset);
        }

        // Verify update
        System.out.println("  [Verify all records after update]");
        try (RandomAccessFile raf = new RandomAccessFile(rafFile.toFile(), "r")) {
            int numRecords = (int) (raf.length() / RECORD_SIZE);
            for (int i = 0; i < numRecords; i++) {
                raf.seek((long) i * RECORD_SIZE);
                int id       = raf.readInt();
                long value   = raf.readLong();
                int checksum = raf.readInt();
                System.out.printf("    Record[%d]: id=%d, value=%d, checksum=%d%s%n",
                    i, id, value, checksum, i == 2 ? " ← updated" : "");
            }
        }

        System.out.println("""
            RandomAccessFile modes:
              "r"  — read only
              "rw" — read + write (creates file if not exists)
              "rws" — rw + sync metadata to storage on each write (safe but slow)
              "rwd" — rw + sync data (not metadata) on each write

            SA Use Case: Write-Ahead Log (WAL)
              raf.seek(raf.length());       // append position
              raf.write(logEntry);          // write log
              raf.getFD().sync();           // fsync → durable
              // Then update in-memory index
            """);
    }

    // =========================================================================
    // DEMO 6 — FileChannel & ByteBuffer
    // =========================================================================
    static void demo6_fileChannelAndByteBuffer() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 6 — FileChannel & ByteBuffer: NIO-style File I/O");
        System.out.println("─".repeat(70));

        System.out.println("""

            FileChannel = NIO channel for file I/O.
            ByteBuffer  = off-heap or heap buffer for bulk data transfer.

            ByteBuffer lifecycle:
              allocate → write data (put) → flip() → read data (get) → clear()/compact()

              Position: current read/write cursor
              Limit:    end of readable data (after flip)
              Capacity: total buffer size

            ByteBuffer modes:
              [--- data ---][  empty  ]
              0         position    capacity   ← WRITE MODE (after put)

              flip() →
              [--- data ---][  empty  ]
              0          limit     capacity   ← READ MODE (after flip)
              position=0
            """);

        Path chanFile = TEMP_DIR.resolve("channel.bin");

        // Write via FileChannel
        System.out.println("  [Write with FileChannel + ByteBuffer]");
        try (FileChannel fc = FileChannel.open(chanFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            ByteBuffer buf = ByteBuffer.allocate(64);

            // Fill buffer — WRITE MODE
            buf.putInt(12345);
            buf.putDouble(Math.PI);
            buf.put("NIO!".getBytes(StandardCharsets.US_ASCII));

            buf.flip();   // switch to READ MODE: limit = position, position = 0

            System.out.printf("    Buffer after flip: position=%d, limit=%d, capacity=%d%n",
                buf.position(), buf.limit(), buf.capacity());

            int written = fc.write(buf);    // writes limit-position bytes
            System.out.printf("    Bytes written: %d%n", written);
        }

        // Read via FileChannel
        System.out.println("\n  [Read with FileChannel + ByteBuffer]");
        try (FileChannel fc = FileChannel.open(chanFile, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(64);
            int bytesRead = fc.read(buf);   // fills buffer from channel
            buf.flip();                      // switch to READ MODE

            System.out.printf("    Bytes read: %d%n", bytesRead);
            System.out.printf("    Int value:    %d%n", buf.getInt());
            System.out.printf("    Double value: %.10f%n", buf.getDouble());
            byte[] strBytes = new byte[4];
            buf.get(strBytes);
            System.out.printf("    String:       \"%s\"%n",
                new String(strBytes, StandardCharsets.US_ASCII));
        }

        // FileChannel transferTo: zero-copy file transfer
        System.out.println("\n  [FileChannel.transferTo — zero-copy transfer]");
        Path destFile = TEMP_DIR.resolve("channel-copy.bin");
        try (FileChannel src = FileChannel.open(chanFile, StandardOpenOption.READ);
             FileChannel dst = FileChannel.open(destFile,
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            long transferred = src.transferTo(0, src.size(), dst);
            System.out.printf("    Transferred %d bytes via transferTo (zero-copy)%n", transferred);
        }

        System.out.println("""
            ByteBuffer types:
              ByteBuffer.allocate(N)        — heap buffer (GC managed)
              ByteBuffer.allocateDirect(N)  — off-heap buffer (no GC, faster I/O)

            Direct buffer: OS dapat langsung membaca → avoid data copy kernel↔userspace
              → Best for: high-throughput network I/O, NIO Selector
              → Downside: allocation is slow, not GC'd → pool and reuse!

            transferTo() / transferFrom(): ZERO-COPY
              → OS-level sendfile() syscall (Linux) / TransmitFile (Windows)
              → Data never copies to userspace → ideal for file serving
              → Used internally by Netty, Tomcat static file serving
            """);
    }

    // =========================================================================
    // DEMO 7 — Memory-Mapped Files (MMap)
    // =========================================================================
    static void demo7_memoryMappedFile() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 7 — Memory-Mapped Files: mmap() for Large Files");
        System.out.println("─".repeat(70));

        System.out.println("""

            MEMORY-MAPPED FILE = map file into virtual address space.
              • OS manages page faults: reads from disk only when accessed
              • Write to MappedByteBuffer → OS writes to file (eventually)
              • Shared across processes: IPC via shared memory file
              • FASTEST I/O for large files read multiple times (OS page cache)

            Use cases:
              • Database engines (RocksDB, LMDB, Chronicle Map)
              • Log file processing (sequential scan, random access)
              • Shared memory IPC between JVM processes
            """);

        Path mmapFile = TEMP_DIR.resolve("mmap.dat");
        int FILE_SIZE = 1024 * 1024; // 1MB

        // Create and write via mmap
        System.out.println("  [Creating 1MB memory-mapped file]");
        try (RandomAccessFile raf = new RandomAccessFile(mmapFile.toFile(), "rw");
             FileChannel fc = raf.getChannel()) {

            // Map entire file into memory
            MappedByteBuffer mmap = fc.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);

            System.out.printf("    MappedByteBuffer: capacity=%,d bytes, isDirect=%b%n",
                mmap.capacity(), mmap.isDirect());

            // Write data like a ByteBuffer (goes directly to OS page cache)
            long start = System.nanoTime();
            for (int i = 0; i < FILE_SIZE / 4; i++) {
                mmap.putInt(i);   // write 4 bytes at a time
            }
            mmap.force();   // flush dirty pages to disk (like fsync)
            long writeMs = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("    mmap write 1MB: %d ms%n", writeMs);

            // Random read access
            start = System.nanoTime();
            long checksum = 0;
            Random rng = new Random(42);
            for (int i = 0; i < 10_000; i++) {
                int pos = (rng.nextInt(FILE_SIZE / 4)) * 4; // random int-aligned position
                mmap.position(pos);
                checksum += mmap.getInt();
            }
            long readMs = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("    mmap random read 10,000 positions: %d ms (checksum=%d)%n",
                readMs, checksum);
        }

        // Sequential scan comparison: mmap vs FileInputStream
        System.out.println("\n  [Sequential scan: mmap vs BufferedInputStream]");

        // mmap scan
        long start = System.nanoTime();
        long mmapSum = 0;
        try (RandomAccessFile raf = new RandomAccessFile(mmapFile.toFile(), "r");
             FileChannel fc = raf.getChannel()) {
            MappedByteBuffer mmap = fc.map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE);
            IntBuffer intBuf = mmap.asIntBuffer();
            while (intBuf.hasRemaining()) mmapSum += intBuf.get();
        }
        long mmapMs = (System.nanoTime() - start) / 1_000_000;

        // Buffered stream scan
        start = System.nanoTime();
        long streamSum = 0;
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(mmapFile.toFile()), 65536))) {
            try {
                while (true) streamSum += dis.readInt();
            } catch (EOFException ignored) {}
        }
        long streamMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("    mmap sequential scan:   %,d ms (sum=%d)%n", mmapMs, mmapSum);
        System.out.printf("    stream sequential scan: %,d ms (sum=%d)%n", streamMs, streamSum);
        System.out.printf("    Results match: %b%n", mmapSum == streamSum);

        System.out.println("""
            MMAP CAVEATS:
              • MappedByteBuffer NOT GC'd predictably → can exhaust virtual address space
              • Unmapping requires reflection hack (Unsafe.invokeCleaner) pre-Java 14
              • Java 14+: Arena API (preview) for deterministic off-heap memory
              • Large files on 32-bit JVM: can only map 2GB max (virtual address limit)
              • force() = fsync equivalent, expensive — batch writes, call infrequently
            """);
    }

    // =========================================================================
    // DEMO 8 — Files Utility API (Java 7+ NIO.2)
    // =========================================================================
    static void demo8_filesUtilityApi() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 8 — Files API (NIO.2): Modern File Operations");
        System.out.println("─".repeat(70));

        System.out.println("""

            java.nio.file.Files — static utility methods, tất cả dùng Path (không File).
            Path = immutable, OS-independent file/directory reference.
            """);

        // Path operations
        Path base = TEMP_DIR.resolve("demo8");
        Files.createDirectories(base);

        Path file1 = base.resolve("hello.txt");
        Path file2 = base.resolve("world.txt");
        Path copyDest = base.resolve("hello-copy.txt");
        Path subDir = base.resolve("subdir");

        // Write
        Files.writeString(file1, "Hello, NIO.2!\n", StandardCharsets.UTF_8);
        Files.write(file2, List.of("Line 1", "Line 2", "Line 3"), StandardCharsets.UTF_8);
        System.out.printf("  Created: %s (%d bytes)%n", file1.getFileName(), Files.size(file1));
        System.out.printf("  Created: %s (%d bytes)%n", file2.getFileName(), Files.size(file2));

        // Read
        String content = Files.readString(file1, StandardCharsets.UTF_8);
        List<String> lines = Files.readAllLines(file2, StandardCharsets.UTF_8);
        System.out.printf("  Read file1: \"%s\"%n", content.strip());
        System.out.printf("  Read file2 lines: %s%n", lines);

        // Copy, Move, Delete
        Files.copy(file1, copyDest, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("  Copied to: %s%n", copyDest.getFileName());

        Files.createDirectory(subDir);
        Path moved = subDir.resolve("world-moved.txt");
        Files.move(file2, moved, StandardCopyOption.ATOMIC_MOVE);
        System.out.printf("  Moved world.txt → subdir/world-moved.txt%n");

        // File attributes
        System.out.println("\n  [File attributes]");
        System.out.printf("  %s — size=%d, lastModified=%s%n",
            file1.getFileName(),
            Files.size(file1),
            Files.getLastModifiedTime(file1));
        System.out.printf("  isRegularFile=%b, isDirectory=%b, isReadable=%b%n",
            Files.isRegularFile(file1), Files.isDirectory(file1), Files.isReadable(file1));

        // Walk directory tree
        System.out.println("\n  [Walk directory tree]");
        try (var stream = Files.walk(base)) {
            stream.forEach(p -> {
                String indent = "  ".repeat((int)(base.relativize(p).getNameCount()));
                System.out.printf("    %s%s%s%n",
                    indent, p.getFileName(),
                    Files.isDirectory(p) ? "/" : "");
            });
        }

        // Glob pattern matching
        System.out.println("\n  [Find files matching *.txt]");
        try (var stream = Files.find(base, 5,
                (p, attrs) -> attrs.isRegularFile() && p.toString().endsWith(".txt"))) {
            stream.forEach(p -> System.out.printf("    Found: %s%n", base.relativize(p)));
        }

        System.out.println("""
            KEY Files API METHODS:
              Files.writeString / readString     — text, Java 11+
              Files.write / readAllLines         — text with charset, Java 7+
              Files.copy / move / delete         — file operations
              Files.walk / find / list           — directory traversal
              Files.newBufferedReader/Writer      — streaming text I/O
              Files.newInputStream/OutputStream   — streaming binary I/O
              Files.createTempFile/Directory      — temp files (auto-cleanup with deleteOnExit)
              Files.isSameFile                   — follows symlinks for comparison
            """);
    }

    // =========================================================================
    // DEMO 9 — Buffer Size Tuning: Find optimal buffer size
    // =========================================================================
    static void demo9_bufferSizeTuning() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 9 — Buffer Size Tuning: Finding the Sweet Spot");
        System.out.println("─".repeat(70));

        System.out.println("""

            Buffer size affects throughput:
              Too small → too many syscalls → high overhead
              Too large → wastes memory, no further gain past OS page size
              Sweet spot: typically 8KB–64KB (matches OS page cache granularity)
            """);

        // Create a large test file
        Path testFile = TEMP_DIR.resolve("buffer-test.bin");
        int FILE_SIZE = 2 * 1024 * 1024; // 2MB
        byte[] fileData = new byte[FILE_SIZE];
        new Random(42).nextBytes(fileData);
        Files.write(testFile, fileData);

        int[] bufferSizes = {512, 1024, 4096, 8192, 16384, 32768, 65536};
        System.out.println("  [Read throughput vs buffer size]");
        System.out.println("  Buffer Size | Time (ms) | Throughput (MB/s)");
        System.out.println("  " + "─".repeat(45));

        int RUNS = 5;
        for (int bufSize : bufferSizes) {
            // Warm up
            readWithBuffer(testFile, bufSize, fileData.length);

            // Measure
            long totalNs = 0;
            for (int r = 0; r < RUNS; r++) {
                totalNs += readWithBuffer(testFile, bufSize, fileData.length);
            }
            long avgMs = totalNs / RUNS / 1_000_000;
            double throughput = avgMs > 0 ? (FILE_SIZE / 1e6) / (avgMs / 1000.0) : 999.0;

            System.out.printf("  %11s | %9d | %17.1f%n",
                formatBytes(bufSize), avgMs, throughput);
        }

        System.out.println("""
            OBSERVATIONS:
              • 512 bytes: many syscalls → slow
              • 8KB-16KB:  sweet spot on most OS/hardware (matches page size)
              • 32KB+:     diminishing returns, same throughput
              • Optimal depends on: SSD vs HDD, OS page cache, file size

            SA RECOMMENDATION:
              • Default: 8192 (Java's BufferedInputStream default) — good enough
              • Tuning: 16384 or 32768 for sequential large-file processing
              • Direct I/O (bypass OS cache): needs O_DIRECT flag (JVM doesn't expose)
                → Use custom native lib or FileChannel + ByteBuffer.allocateDirect()
            """);
    }

    static long readWithBuffer(Path file, int bufSize, int fileSize) throws Exception {
        byte[] buf = new byte[bufSize];
        long start = System.nanoTime();
        try (InputStream is = new FileInputStream(file.toFile())) {
            int total = 0;
            int n;
            while ((n = is.read(buf)) != -1) total += n;
        }
        return System.nanoTime() - start;
    }

    static String formatBytes(int bytes) {
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes + " B";
    }

    // =========================================================================
    // Cleanup
    // =========================================================================
    static void cleanup() {
        try {
            // Delete temp files
            try (var stream = Files.walk(TEMP_DIR)) {
                stream.sorted(Comparator.reverseOrder())
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
        System.out.println("  TỔNG KẾT BÀI 6.1 — Blocking I/O Insights");
        System.out.println("=".repeat(70));
        System.out.println("""

            I/O SELECTION GUIDE:
            ┌──────────────────────────────────┬──────────────────────────────────┐
            │ Scenario                         │ Tool                              │
            ├──────────────────────────────────┼──────────────────────────────────┤
            │ Small text file (<10MB)          │ Files.readString / readAllLines   │
            │ Large text file (line by line)   │ Files.lines() / BufferedReader    │
            │ Binary data (typed fields)       │ DataInputStream/DataOutputStream  │
            │ Random access (seek + update)    │ RandomAccessFile / FileChannel    │
            │ High-throughput file copy        │ FileChannel.transferTo (zero-copy)│
            │ Large file scan (multiple times) │ MappedByteBuffer (OS page cache) │
            │ Directory listing / tree walk    │ Files.walk / Files.find           │
            │ Config/properties loading        │ Files.readString → parse          │
            └──────────────────────────────────┴──────────────────────────────────┘

            ENCODING RULES (non-negotiable):
              ✓ NEVER use FileReader/FileWriter without charset (platform-dependent)
              ✓ ALWAYS specify StandardCharsets.UTF_8 explicitly
              ✓ Java 18+ default charset = UTF-8, but explicit is safer

            PERFORMANCE RULES:
              ✓ ALWAYS buffer raw FileInputStream/FileOutputStream
              ✓ Use Files API (internally buffered) for simple operations
              ✓ Buffer size 8KB–16KB is sweet spot for sequential reads
              ✓ FileChannel.transferTo for zero-copy file serving
              ✓ MappedByteBuffer for large files accessed multiple times

            RESOURCE RULES:
              ✓ ALWAYS try-with-resources for any I/O resource
              ✓ Files.lines() → wrap in try-with-resources (lazy stream holds file handle)
              ✓ MappedByteBuffer: call force() before process exits for durability

            KEY INSIGHT:
              "Buffering là optimization #1 cho file I/O.
               transferTo() là optimization #1 cho file serving.
               MappedByteBuffer là optimization #1 cho large random-access."
            """);
    }
}
