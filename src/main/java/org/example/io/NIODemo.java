package org.example.io;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * =============================================================================
 * BÀI 6.2 — NIO: ByteBuffer, Channel, Selector (Non-blocking I/O)
 * =============================================================================
 *
 * TẠI SAO CẦN NON-BLOCKING I/O?
 *   Blocking model (java.io):
 *     • 1 thread per connection → 10,000 connections = 10,000 threads
 *     • Thread overhead: ~1MB stack × 10,000 = 10GB RAM chỉ cho threads
 *     • C10K Problem: blocking model không scale ở high concurrency
 *
 *   Non-blocking NIO model (java.nio):
 *     • 1 Selector thread manages N channels
 *     • Thread chỉ active khi có data ready → không block
 *     • 1 thread có thể serve 10,000 connections (Netty, Undertow dùng pattern này)
 *
 * NIO COMPONENTS:
 *   Channel  — connection to I/O source (file, socket, pipe)
 *   Buffer   — container for data (ByteBuffer, CharBuffer, IntBuffer, ...)
 *   Selector — multiplexer: monitor multiple channels, notify khi ready
 *   SelectionKey — channel registration token, holds interest ops + attachment
 *
 * INTEREST OPS (events Selector watch for):
 *   OP_ACCEPT  (16) — ServerSocketChannel ready to accept new connection
 *   OP_CONNECT (8)  — SocketChannel finished connecting
 *   OP_READ    (1)  — SocketChannel has data to read
 *   OP_WRITE   (4)  — SocketChannel ready to write (buffer not full)
 *
 * EVENT-LOOP PATTERN (Reactor Pattern):
 *   while (true) {
 *     selector.select();              // BLOCK until ≥1 channel is ready
 *     for (SelectionKey key : selector.selectedKeys()) {
 *       if (key.isAcceptable()) handleAccept(key);
 *       if (key.isReadable())   handleRead(key);
 *       if (key.isWritable())   handleWrite(key);
 *       selectedKeys.remove(key);    // MUST remove manually!
 *     }
 *   }
 *
 * Chạy: mvn compile exec:java -Dexec.mainClass="org.example.io.NIODemo"
 */
public class NIODemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  BÀI 6.2 — NIO: ByteBuffer, Channel, Selector");
        System.out.println("=".repeat(70));
        System.out.println();

        demo1_byteBufferDeepDive();
        demo2_socketChannelBlocking();
        demo3_nonBlockingEchoServer();
        demo4_selectorMultiplexing();
        demo5_pipeChannel();
        demo6_scatterGather();
        demo7_blockingVsNioThroughput();
        printSAInsights();
    }

    // =========================================================================
    // DEMO 1 — ByteBuffer Deep Dive: state machine
    // =========================================================================
    static void demo1_byteBufferDeepDive() {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 1 — ByteBuffer Deep Dive: position / limit / capacity");
        System.out.println("─".repeat(70));

        System.out.println("""

            ByteBuffer là STATE MACHINE với 3 pointers:
              capacity  — total buffer size (fixed)
              limit     — last valid byte position
              position  — current read/write cursor

            WRITE MODE:    [=== written ===][... empty ...]
                           0           position            capacity
                                       limit=capacity

            after flip():  [=== written ===][... empty ...]
                           0             limit             capacity
                           position=0
            READ MODE: reads from 0 to limit

            after clear(): reset all → write mode again (data still there, just overwritten)
            after compact(): unread data moved to front → continue writing
            """);

        ByteBuffer buf = ByteBuffer.allocate(16);
        printBufferState("  after allocate(16)  ", buf);

        // Write phase
        buf.putInt(42);
        buf.putInt(100);
        printBufferState("  after put 2 ints    ", buf);

        // Flip: write → read mode
        buf.flip();
        printBufferState("  after flip()        ", buf);

        // Read one int
        int first = buf.getInt();
        System.out.printf("  getInt() → %d%n", first);
        printBufferState("  after getInt()      ", buf);

        // compact(): move unread bytes to front, continue writing
        buf.compact();
        printBufferState("  after compact()     ", buf);
        buf.putInt(999);
        buf.flip();
        printBufferState("  after put+flip again", buf);
        System.out.printf("  remaining: %d=%d, %d=%d%n",
            buf.getInt(), 100, buf.getInt(), 999);

        System.out.println();

        // Buffer types comparison
        System.out.println("  [Buffer allocation types]");
        ByteBuffer heap   = ByteBuffer.allocate(1024);       // heap, GC managed
        ByteBuffer direct = ByteBuffer.allocateDirect(1024); // off-heap, faster I/O
        ByteBuffer wrapped = ByteBuffer.wrap(new byte[]{1,2,3,4,5}); // wraps existing array

        System.out.printf("  heap.isDirect()    = %b (GC managed, slower I/O)%n", heap.isDirect());
        System.out.printf("  direct.isDirect()  = %b (off-heap, faster I/O, slower alloc)%n", direct.isDirect());
        System.out.printf("  wrapped.hasArray() = %b (backed by byte[])%n", wrapped.hasArray());

        // View buffers (same memory, different type)
        ByteBuffer source = ByteBuffer.allocate(32);
        source.putLong(123456789L).putLong(987654321L);
        source.flip();
        LongBuffer longView = source.asLongBuffer();    // same data, long-typed access
        System.out.printf("%n  LongBuffer view: %d, %d%n",
            longView.get(), longView.get());

        System.out.println("""
            BUFFER RULES:
              flip()    before reading from buffer (write → read mode)
              clear()   before writing again (discards unread data)
              compact() to preserve unread data then continue writing
              rewind()  re-read from position=0 (limit stays)
              mark()/reset() bookmark a position to return to
            """);
    }

    static void printBufferState(String label, ByteBuffer buf) {
        System.out.printf("  %s | pos=%2d, lim=%2d, cap=%2d, remaining=%d%n",
            label, buf.position(), buf.limit(), buf.capacity(), buf.remaining());
    }

    // =========================================================================
    // DEMO 2 — SocketChannel Blocking Mode (NIO channel, blocking behavior)
    // =========================================================================
    static void demo2_socketChannelBlocking() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 2 — SocketChannel (Blocking Mode): NIO Channel vs Old Socket");
        System.out.println("─".repeat(70));

        System.out.println("""

            SocketChannel có thể chạy ở 2 mode:
              Blocking (default): channel.configureBlocking(true)
                → read/write blocks until complete (like java.io)
                → easier to use, not scalable
              Non-blocking:       channel.configureBlocking(false)
                → read/write returns immediately if not ready
                → must pair with Selector for event-driven I/O

            Demo: simple blocking HTTP GET via SocketChannel
            """);

        // Echo server ở background để demo blocking SocketChannel
        int PORT = 19876;
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress("localhost", PORT));
        serverChannel.configureBlocking(true);

        CountDownLatch serverReady = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        Thread serverThread = Thread.ofVirtual().start(() -> {
            try {
                serverReady.countDown();
                SocketChannel client = serverChannel.accept(); // blocks
                ByteBuffer readBuf = ByteBuffer.allocate(256);
                int n = client.read(readBuf);
                readBuf.flip();
                String msg = StandardCharsets.UTF_8.decode(readBuf).toString();
                receivedMessage.set(msg);

                // Echo back
                ByteBuffer response = StandardCharsets.UTF_8.encode("ECHO: " + msg);
                client.write(response);
                client.close();
            } catch (Exception e) {
                if (!e.getMessage().contains("closed")) e.printStackTrace();
            }
        });

        serverReady.await();

        // Client connects via SocketChannel
        System.out.println("  [Client connecting via SocketChannel (blocking)]");
        try (SocketChannel clientChannel = SocketChannel.open()) {
            clientChannel.configureBlocking(true);
            clientChannel.connect(new InetSocketAddress("localhost", PORT));

            // Send message
            String message = "Hello NIO SocketChannel!";
            ByteBuffer sendBuf = StandardCharsets.UTF_8.encode(message);
            clientChannel.write(sendBuf);
            System.out.printf("    Sent: \"%s\"%n", message);

            // Receive echo
            ByteBuffer recvBuf = ByteBuffer.allocate(256);
            clientChannel.read(recvBuf);
            recvBuf.flip();
            String response = StandardCharsets.UTF_8.decode(recvBuf).toString();
            System.out.printf("    Received: \"%s\"%n", response);
        }

        serverThread.join(1000);
        serverChannel.close();

        System.out.println("""
            CHANNEL vs SOCKET:
            ┌──────────────────────┬───────────────────────────────────────────┐
            │ java.net.Socket      │ java.nio.channels.SocketChannel            │
            ├──────────────────────┼───────────────────────────────────────────┤
            │ Always blocking      │ Blocking or non-blocking mode              │
            │ InputStream/Output   │ ByteBuffer read/write                      │
            │ Cannot use Selector  │ Works with Selector (non-blocking mode)    │
            │ Simple to use        │ More complex, more powerful                │
            └──────────────────────┴───────────────────────────────────────────┘
            """);
    }

    // =========================================================================
    // DEMO 3 — Non-Blocking Echo Server with Selector
    // =========================================================================
    static void demo3_nonBlockingEchoServer() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 3 — Non-Blocking Echo Server: Selector Event Loop");
        System.out.println("─".repeat(70));

        System.out.println("""

            REACTOR PATTERN (single-threaded NIO server):
              1. Register ServerSocketChannel with Selector for OP_ACCEPT
              2. selector.select() — waits for any event
              3. OP_ACCEPT → accept() new SocketChannel → register for OP_READ
              4. OP_READ   → read data → process → register for OP_WRITE
              5. OP_WRITE  → write response → deregister OP_WRITE → wait OP_READ
              6. Repeat

            1 thread handling multiple connections — this is how Netty/Undertow work!
            """);

        int PORT = 19877;
        int NUM_CLIENTS = 5;
        CountDownLatch allDone = new CountDownLatch(NUM_CLIENTS);
        AtomicInteger echoCount = new AtomicInteger(0);
        AtomicBoolean serverStop = new AtomicBoolean(false);

        // ── Non-blocking server thread ──
        Thread serverThread = Thread.ofVirtual().start(() -> {
            try (Selector selector = Selector.open();
                 ServerSocketChannel ssc = ServerSocketChannel.open()) {

                ssc.bind(new InetSocketAddress("localhost", PORT));
                ssc.configureBlocking(false);                   // NON-BLOCKING
                ssc.register(selector, SelectionKey.OP_ACCEPT); // watch for connections

                System.out.println("    [Server] NIO Selector server started on port " + PORT);

                while (!serverStop.get() || selector.keys().size() > 1) {
                    int ready = selector.select(100); // timeout 100ms
                    if (ready == 0) continue;

                    Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();  // CRITICAL: must remove manually

                        if (!key.isValid()) continue;

                        if (key.isAcceptable()) {
                            // New connection arrived
                            ServerSocketChannel server = (ServerSocketChannel) key.channel();
                            SocketChannel client = server.accept();
                            if (client != null) {
                                client.configureBlocking(false);
                                client.register(selector, SelectionKey.OP_READ,
                                    ByteBuffer.allocate(256)); // attach buffer as state
                            }

                        } else if (key.isReadable()) {
                            // Data ready to read
                            SocketChannel client = (SocketChannel) key.channel();
                            ByteBuffer buf = (ByteBuffer) key.attachment();
                            buf.clear();

                            int n = client.read(buf);
                            if (n == -1) {
                                // Client closed connection
                                key.cancel();
                                client.close();
                                continue;
                            }
                            buf.flip();

                            // Prepare echo response
                            String received = StandardCharsets.UTF_8.decode(buf).toString().strip();
                            String echo = "ECHO[" + echoCount.incrementAndGet() + "]: " + received;
                            ByteBuffer response = StandardCharsets.UTF_8.encode(echo);
                            key.attach(response);                   // switch attachment to response
                            key.interestOps(SelectionKey.OP_WRITE); // now interested in write

                        } else if (key.isWritable()) {
                            // Channel ready to write
                            SocketChannel client = (SocketChannel) key.channel();
                            ByteBuffer response = (ByteBuffer) key.attachment();
                            client.write(response);

                            if (!response.hasRemaining()) {
                                // All written — back to reading
                                ByteBuffer readBuf = ByteBuffer.allocate(256);
                                key.attach(readBuf);
                                key.interestOps(SelectionKey.OP_READ);
                                allDone.countDown(); // signal: this exchange complete
                            }
                        }
                    }
                }
                System.out.println("    [Server] Shutting down cleanly");
            } catch (Exception e) {
                if (!e.getMessage().contains("closed")) e.printStackTrace();
            }
        });

        Thread.sleep(100); // let server start

        // ── Multiple clients ──
        System.out.println("    [Clients] Sending " + NUM_CLIENTS + " concurrent requests...");
        List<Thread> clients = new ArrayList<>();
        for (int i = 0; i < NUM_CLIENTS; i++) {
            final int id = i;
            clients.add(Thread.ofVirtual().start(() -> {
                try (SocketChannel ch = SocketChannel.open()) {
                    ch.configureBlocking(true);
                    ch.connect(new InetSocketAddress("localhost", PORT));

                    String msg = "Message-" + id;
                    ch.write(StandardCharsets.UTF_8.encode(msg));

                    ByteBuffer resp = ByteBuffer.allocate(256);
                    ch.read(resp);
                    resp.flip();
                    System.out.printf("    Client-%d received: \"%s\"%n",
                        id, StandardCharsets.UTF_8.decode(resp).toString().strip());
                } catch (Exception e) {
                    System.out.println("    Client-" + id + " error: " + e.getMessage());
                }
            }));
        }

        // Wait for all exchanges to complete
        boolean done = allDone.await(5, TimeUnit.SECONDS);
        System.out.printf("    All %d echoes completed: %b%n", NUM_CLIENTS, done);

        serverStop.set(true);
        serverThread.join(2000);

        System.out.println("""
            KEY OBSERVATIONS:
              • 1 server thread handled all 5 clients concurrently
              • selector.select() blocked until I/O was ready — no spin wait
              • Each SocketChannel state stored in SelectionKey attachment
              • OP_WRITE only registered when there's data to write (avoid busy loop!)
            """);
    }

    // =========================================================================
    // DEMO 4 — Selector: Interest Ops & SelectionKey lifecycle
    // =========================================================================
    static void demo4_selectorMultiplexing() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 4 — Selector Internals: Keys, Interest Ops, Ready Ops");
        System.out.println("─".repeat(70));

        System.out.println("""
            SELECTOR KEY SETS:
              selector.keys()         — ALL registered channels (even not ready)
              selector.selectedKeys() — channels ready for registered ops this cycle
              cancelled keys          — key.cancel() → removed at next select()

            SelectionKey.interestOps():
              Can change between select() calls:
                key.interestOps(OP_READ);            // watch only read
                key.interestOps(OP_READ | OP_WRITE); // watch both
                key.interestOps(0);                  // pause watching (temporarily)

            Attachment — store per-connection state on the key:
                key.attach(new ConnectionState());   // set
                ConnectionState s = (ConnectionState) key.attachment(); // get

            COMMON MISTAKE — not removing from selectedKeys():
                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();   // ← MUST DO THIS — Selector never removes it!
                    // handle key...
                }

            WITHOUT iter.remove(): key stays in selectedKeys() → processed again
            next iteration even if no new event → infinite loop / stale processing
            """);

        // Demonstrate selector key lifecycle
        try (Selector selector = Selector.open();
             ServerSocketChannel ssc = ServerSocketChannel.open()) {

            ssc.bind(new InetSocketAddress("localhost", 19878));
            ssc.configureBlocking(false);

            SelectionKey key = ssc.register(selector, SelectionKey.OP_ACCEPT);
            System.out.printf("  Keys after register:   %d key(s)%n", selector.keys().size());
            System.out.printf("  Interest ops: ACCEPT=%b%n",
                (key.interestOps() & SelectionKey.OP_ACCEPT) != 0);

            // Modify interest ops
            key.interestOps(0);    // pause monitoring
            System.out.printf("  After interestOps(0):  interest=%d (paused)%n", key.interestOps());

            key.interestOps(SelectionKey.OP_ACCEPT); // resume
            System.out.printf("  After re-enable:       interest=%d (active)%n", key.interestOps());

            // Attach state
            key.attach(Map.of("server", true, "port", 19878));
            @SuppressWarnings("unchecked")
            Map<String, Object> attachment = (Map<String, Object>) key.attachment();
            System.out.printf("  Attachment: %s%n", attachment);

            // Cancel
            key.cancel();
            selector.selectNow(); // process cancellation
            System.out.printf("  After cancel+select:   %d key(s), key.isValid()=%b%n",
                selector.keys().size(), key.isValid());
        }

        System.out.println();
    }

    // =========================================================================
    // DEMO 5 — Pipe: In-process non-blocking channel
    // =========================================================================
    static void demo5_pipeChannel() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 5 — Pipe: In-Process Channel for Thread Communication");
        System.out.println("─".repeat(70));

        System.out.println("""

            Pipe = unidirectional channel connecting two threads in same JVM.
              Pipe.SinkChannel   — write end (producer)
              Pipe.SourceChannel — read end (consumer)
            Use case: producer-consumer with NIO (instead of BlockingQueue)
            """);

        Pipe pipe = Pipe.open();
        Pipe.SinkChannel sink     = pipe.sink();
        Pipe.SourceChannel source = pipe.source();
        source.configureBlocking(false); // non-blocking read

        CountDownLatch latch = new CountDownLatch(3);

        // Producer thread
        Thread producer = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 1; i <= 3; i++) {
                    String msg = "data-chunk-" + i;
                    ByteBuffer buf = StandardCharsets.UTF_8.encode(msg);
                    sink.write(buf);
                    System.out.printf("    [Producer] wrote: \"%s\"%n", msg);
                    Thread.sleep(50);
                }
                sink.close();
            } catch (Exception e) { e.printStackTrace(); }
        });

        // Consumer: poll with Selector
        Thread consumer = Thread.ofVirtual().start(() -> {
            try (Selector sel = Selector.open()) {
                source.register(sel, SelectionKey.OP_READ);
                ByteBuffer buf = ByteBuffer.allocate(64);

                while (latch.getCount() > 0) {
                    sel.select(200);
                    Iterator<SelectionKey> iter = sel.selectedKeys().iterator();
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (key.isReadable()) {
                            buf.clear();
                            int n = source.read(buf);
                            if (n > 0) {
                                buf.flip();
                                String data = StandardCharsets.UTF_8.decode(buf).toString();
                                System.out.printf("    [Consumer] read:  \"%s\"%n", data);
                                latch.countDown();
                            } else if (n == -1) {
                                key.cancel();
                            }
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        });

        latch.await(3, TimeUnit.SECONDS);
        producer.join(1000);
        consumer.join(1000);
        source.close();

        System.out.println("""
            Pipe vs BlockingQueue:
              BlockingQueue — thread-safe, blocking or timed, rich API
              Pipe          — NIO-based, integrates with Selector, lower-level
            Use Pipe when: consumer is NIO event loop and must not block
            """);
    }

    // =========================================================================
    // DEMO 6 — Scatter/Gather: vectored I/O
    // =========================================================================
    static void demo6_scatterGather() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 6 — Scatter/Gather: Vectored I/O");
        System.out.println("─".repeat(70));

        System.out.println("""

            SCATTER READ: read from channel into MULTIPLE buffers in one call
              channel.read(ByteBuffer[] buffers)
              → OS fills header buffer first, then body buffer (1 syscall)

            GATHER WRITE: write from MULTIPLE buffers into channel in one call
              channel.write(ByteBuffer[] buffers)
              → OS sends header + body atomically (1 syscall)

            Use case: network protocol with fixed header + variable body:
              [4 bytes: magic][4 bytes: length][N bytes: body]
            """);

        Path scatterFile = Files.createTempFile("scatter-", ".bin");

        try {
            // GATHER WRITE: write header + body in one vectored call
            System.out.println("  [Gather write: header + body → single write() call]");
            ByteBuffer header = ByteBuffer.allocate(8);
            header.putInt(0xDEADBEEF);  // magic number
            header.putInt(12);           // body length
            header.flip();

            ByteBuffer body = StandardCharsets.UTF_8.encode("Hello Gather");
            System.out.printf("    Header: %d bytes, Body: %d bytes%n",
                header.remaining(), body.remaining());

            try (FileChannel fc = FileChannel.open(scatterFile,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                ByteBuffer[] bufs = {header, body};
                long written = fc.write(bufs);  // gather write
                System.out.printf("    Total written: %d bytes (1 write() call)%n", written);
            }

            // SCATTER READ: read into separate buffers in one call
            System.out.println("\n  [Scatter read: header + body from single read() call]");
            ByteBuffer readHeader = ByteBuffer.allocate(8);
            ByteBuffer readBody   = ByteBuffer.allocate(64);

            try (FileChannel fc = FileChannel.open(scatterFile, StandardOpenOption.READ)) {
                ByteBuffer[] bufs = {readHeader, readBody};
                long totalRead = fc.read(bufs);   // scatter read
                System.out.printf("    Total read: %d bytes%n", totalRead);
            }

            readHeader.flip();
            readBody.flip();
            int magic  = readHeader.getInt();
            int length = readHeader.getInt();
            String bodyText = StandardCharsets.UTF_8.decode(readBody).toString();

            System.out.printf("    Magic:  0x%X%n", magic);
            System.out.printf("    Length: %d%n", length);
            System.out.printf("    Body:   \"%s\"%n", bodyText);

        } finally {
            Files.deleteIfExists(scatterFile);
        }

        System.out.println("""
            BENEFIT OF SCATTER/GATHER:
              • Single syscall instead of N separate write() calls
              • Atomic write: header + body never interleaved with other writes
              • Important for network protocols to avoid partial messages
              • Used internally in: NFS, Kafka log segments, database WAL
            """);
    }

    // =========================================================================
    // DEMO 7 — Blocking vs NIO Throughput Comparison
    // =========================================================================
    static void demo7_blockingVsNioThroughput() throws Exception {
        System.out.println("─".repeat(70));
        System.out.println("DEMO 7 — Blocking vs NIO: Throughput & Concurrency Comparison");
        System.out.println("─".repeat(70));

        System.out.println("""

            BENCHMARK: Echo server — N concurrent clients, measure total throughput

            BLOCKING MODEL:
              ServerSocket.accept() → new Thread per client → thread blocks on read()
              Thread overhead: ~1MB stack + OS scheduling
              Good for: low concurrency (<100 connections), simple code

            NIO SELECTOR MODEL:
              1 Selector thread monitors N SocketChannels
              No thread per connection → handles thousands efficiently
              Good for: high concurrency (1000+ connections), throughput

            NOTE: With Virtual Threads (Java 21), blocking I/O becomes competitive
                  again because virtual threads are cheap (bài 2.6).
                  NIO Selector model is still used in frameworks for max control.
            """);

        int PORT_BLK = 19880;
        int PORT_NIO = 19881;
        int NUM_CLIENTS = 20;
        int MSGS_PER_CLIENT = 10;
        int MSG_SIZE = 256;

        // ── Blocking Echo Server ──
        AtomicLong blockingTotal = new AtomicLong(0);
        ServerSocket blkServer = new ServerSocket(PORT_BLK);
        ExecutorService blkPool = Executors.newCachedThreadPool();
        AtomicBoolean blkStop = new AtomicBoolean(false);

        Thread blkAcceptor = Thread.ofVirtual().start(() -> {
            while (!blkStop.get()) {
                try {
                    blkServer.setSoTimeout(200);
                    Socket client = blkServer.accept();
                    blkPool.submit(() -> {
                        try (InputStream in = client.getInputStream();
                             OutputStream out = client.getOutputStream()) {
                            byte[] buf = new byte[MSG_SIZE];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                                blockingTotal.addAndGet(n);
                            }
                        } catch (Exception ignored) {}
                    });
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (!blkStop.get()) e.printStackTrace();
                }
            }
        });

        // ── NIO Selector Echo Server ──
        AtomicLong nioTotal = new AtomicLong(0);
        AtomicBoolean nioStop = new AtomicBoolean(false);
        CountDownLatch nioReady = new CountDownLatch(1);

        Thread nioServer = Thread.ofVirtual().start(() -> {
            try (Selector selector = Selector.open();
                 ServerSocketChannel ssc = ServerSocketChannel.open()) {
                ssc.bind(new InetSocketAddress("localhost", PORT_NIO));
                ssc.configureBlocking(false);
                ssc.register(selector, SelectionKey.OP_ACCEPT);
                nioReady.countDown();

                ByteBuffer buf = ByteBuffer.allocate(MSG_SIZE * 2);
                while (!nioStop.get() || selector.keys().size() > 1) {
                    selector.select(100);
                    Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (!key.isValid()) continue;

                        if (key.isAcceptable()) {
                            SocketChannel ch = ((ServerSocketChannel)key.channel()).accept();
                            if (ch != null) {
                                ch.configureBlocking(false);
                                ch.register(selector, SelectionKey.OP_READ);
                            }
                        } else if (key.isReadable()) {
                            SocketChannel ch = (SocketChannel) key.channel();
                            buf.clear();
                            int n = ch.read(buf);
                            if (n == -1) { key.cancel(); ch.close(); continue; }
                            if (n > 0) {
                                nioTotal.addAndGet(n);
                                buf.flip();
                                ch.write(buf); // echo back
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (!nioStop.get()) e.printStackTrace();
            }
        });

        Thread.sleep(150);
        nioReady.await();

        byte[] payload = new byte[MSG_SIZE];
        Arrays.fill(payload, (byte) 'X');

        // ── Run clients against BLOCKING server ──
        long blkStart = System.nanoTime();
        List<Thread> blkClients = new ArrayList<>();
        CountDownLatch blkDone = new CountDownLatch(NUM_CLIENTS);

        for (int i = 0; i < NUM_CLIENTS; i++) {
            blkClients.add(Thread.ofVirtual().start(() -> {
                try (Socket s = new Socket("localhost", PORT_BLK)) {
                    OutputStream out = s.getOutputStream();
                    InputStream  in  = s.getInputStream();
                    byte[] rbuf = new byte[MSG_SIZE];
                    for (int m = 0; m < MSGS_PER_CLIENT; m++) {
                        out.write(payload);
                        int total = 0;
                        while (total < MSG_SIZE) total += in.read(rbuf, total, MSG_SIZE - total);
                    }
                } catch (Exception e) { e.printStackTrace(); }
                blkDone.countDown();
            }));
        }
        blkDone.await(10, TimeUnit.SECONDS);
        long blkMs = (System.nanoTime() - blkStart) / 1_000_000;

        // ── Run clients against NIO server ──
        long nioStart = System.nanoTime();
        CountDownLatch nioDone = new CountDownLatch(NUM_CLIENTS);

        for (int i = 0; i < NUM_CLIENTS; i++) {
            Thread.ofVirtual().start(() -> {
                try (SocketChannel ch = SocketChannel.open(
                        new InetSocketAddress("localhost", PORT_NIO))) {
                    ch.configureBlocking(true);
                    ByteBuffer send = ByteBuffer.wrap(payload);
                    ByteBuffer recv = ByteBuffer.allocate(MSG_SIZE);
                    for (int m = 0; m < MSGS_PER_CLIENT; m++) {
                        send.rewind();
                        ch.write(send);
                        recv.clear();
                        while (recv.hasRemaining()) ch.read(recv);
                    }
                } catch (Exception e) { e.printStackTrace(); }
                nioDone.countDown();
            });
        }
        nioDone.await(10, TimeUnit.SECONDS);
        long nioMs = (System.nanoTime() - nioStart) / 1_000_000;

        // Cleanup
        blkStop.set(true);
        nioStop.set(true);
        blkServer.close();
        blkPool.shutdown();
        blkAcceptor.join(1000);
        nioServer.join(1000);

        long totalBytes = (long) NUM_CLIENTS * MSGS_PER_CLIENT * MSG_SIZE;
        System.out.printf("  %d clients × %d msgs × %d bytes = %,d bytes total%n",
            NUM_CLIENTS, MSGS_PER_CLIENT, MSG_SIZE, totalBytes);
        System.out.printf("  Blocking server: %,d ms  (%.1f MB/s, %d threads)%n",
            blkMs, (totalBytes / 1e6) / (blkMs / 1000.0), NUM_CLIENTS);
        System.out.printf("  NIO Selector:    %,d ms  (%.1f MB/s, 1 thread)%n",
            nioMs, (totalBytes / 1e6) / (nioMs / 1000.0));

        System.out.println("""
            INSIGHT:
              At low concurrency: blocking ≈ NIO in throughput
              At high concurrency (1000+ connections): NIO wins dramatically
              Memory: NIO = O(1) threads, Blocking = O(N) threads

            MODERN APPROACH (Java 21+):
              Virtual Threads + blocking I/O = NIO-like scalability with simple code
              Netty/Undertow still use NIO Selector for max performance + control
            """);
    }

    // =========================================================================
    // SA Insights
    // =========================================================================
    static void printSAInsights() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TỔNG KẾT BÀI 6.2 — NIO Insights");
        System.out.println("=".repeat(70));
        System.out.println("""

            NIO COMPONENT SUMMARY:
            ┌─────────────────┬────────────────────────────────────────────────┐
            │ Component       │ Role                                            │
            ├─────────────────┼────────────────────────────────────────────────┤
            │ ByteBuffer      │ Data container; flip/clear/compact lifecycle   │
            │ Channel         │ NIO I/O endpoint (File, Socket, Pipe, ...)      │
            │ Selector        │ Multiplexer: 1 thread monitors N channels       │
            │ SelectionKey    │ Channel+Selector registration, carries state    │
            └─────────────────┴────────────────────────────────────────────────┘

            WHEN TO USE WHAT:
            ┌──────────────────────────┬──────────────────────────────────────┐
            │ Scenario                 │ Choice                                │
            ├──────────────────────────┼──────────────────────────────────────┤
            │ Simple file I/O          │ java.io (BufferedReader/Writer)       │
            │ High-perf file I/O       │ FileChannel + ByteBuffer             │
            │ Zero-copy file serving   │ FileChannel.transferTo()              │
            │ Low concurrency network  │ java.net.Socket (simple, blocking)   │
            │ High concurrency network │ NIO Selector OR Virtual Threads       │
            │ Framework-level I/O      │ Netty (NIO under the hood)           │
            │ Modern Java (21+)        │ Virtual Threads + blocking I/O        │
            └──────────────────────────┴──────────────────────────────────────┘

            BYTEBUFFER RULES (tattoo these on your wrist):
              flip()    — write mode → read mode  (BEFORE reading)
              clear()   — reset to write mode      (BEFORE writing, discards data)
              compact() — preserve unread data → write mode (continue partial write)
              rewind()  — re-read from start        (limit unchanged)

            SELECTOR RULES:
              ✓ ALWAYS iter.remove() after processing a key
              ✓ NEVER register OP_WRITE unless you have data to write (busy loop!)
              ✓ key.cancel() + closeChannel in same try block
              ✓ selectedKeys() ≠ keys(): selectedKeys is subset that fired this cycle
              ✓ Selector is NOT thread-safe — use 1 thread per Selector

            SA INSIGHT:
              "NIO Selector = Reactor pattern at OS level.
               Netty wraps this with: Boss thread (accept) + Worker threads (read/write).
               Virtual Threads (Java 21) make blocking I/O viable at scale —
               but NIO remains the choice for ultra-low-latency and framework code."
            """);
    }
}
