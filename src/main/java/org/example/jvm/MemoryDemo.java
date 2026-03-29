package org.example.jvm;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * DEMO 2: JVM Memory Areas - Heap vs Stack vs Metaspace
 *
 * Bai toan thuc te: Tai sao app bi OOM? Heap hay Metaspace?
 * Hieu memory areas giup ban doc GC log va chon dung flag tuning.
 */
public class MemoryDemo {

    // Static field -> nam trong Heap (tham chieu) + Metaspace (metadata)
    private static final List<String> STATIC_CACHE = new ArrayList<>();

    public static void main(String[] args) throws InterruptedException {

        // === PHAN 1: Doc thong tin memory hien tai ===
        printMemoryInfo("=== Trang thai Memory ban dau ===");

        // === PHAN 2: Stack vs Heap ===
        System.out.println("\n=== Stack vs Heap ===");
        demonstrateStackVsHeap();

        // === PHAN 3: Tao nhieu object -> quan sat Heap tang ===
        System.out.println("\n=== Tao 100,000 objects ===");
        List<byte[]> objects = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            objects.add(new byte[1024]); // moi object 1KB
        }
        printMemoryInfo("Sau khi tao 100,000 objects (100MB)");

        // === PHAN 4: Null reference -> GC co the thu hoi ===
        objects = null;
        System.gc(); // Suggest GC (JVM co the bo qua, nhung thuong chay trong demo)
        Thread.sleep(100);
        printMemoryInfo("Sau khi null + System.gc()");

        // === PHAN 5: Stack Overflow - recursion khong co base case ===
        System.out.println("\n=== Stack Overflow Demo (controlled) ===");
        try {
            infiniteRecursion(0);
        } catch (StackOverflowError e) {
            System.out.println("StackOverflowError bat duoc!");
            System.out.println("Nguyen nhan: moi method call them 1 Stack Frame");
            System.out.println("Default Stack size: ~512KB - 1MB per thread");
            System.out.println("-> Tuning: -Xss2m de tang stack size");
        }
    }

    static void demonstrateStackVsHeap() {
        // 'a' va 'b' la primitive -> nam TREN Stack (trong Stack Frame)
        int a = 10;
        int b = 20;

        // 'sb' la reference -> ban than reference nam tren Stack
        // Object StringBuilder nam tren Heap
        StringBuilder sb = new StringBuilder();
        sb.append("Hello");

        System.out.println("Primitives (a, b) nam tren Stack Frame cua method nay");
        System.out.println("StringBuilder object nam tren Heap");
        System.out.println("Reference 'sb' tro den Heap, con 'sb' itself nam tren Stack");

        // Khi method nay return, Stack Frame bi pop
        // 'a', 'b', 'sb' (reference) bi destroy
        // Nhung StringBuilder OBJECT tren Heap van con cho den khi GC
    }

    static void infiniteRecursion(int depth) {
        // Khong co base case -> Stack se day
        infiniteRecursion(depth + 1);
    }

    static void printMemoryInfo(String label) {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

        System.out.println("\n" + label);
        System.out.printf("  Heap Used    : %6d MB / %6d MB (max: %6d MB)%n",
                heap.getUsed() / (1024 * 1024),
                heap.getCommitted() / (1024 * 1024),
                heap.getMax() / (1024 * 1024));
        System.out.printf("  NonHeap Used : %6d MB (Metaspace + CodeCache)%n",
                nonHeap.getUsed() / (1024 * 1024));

        Runtime rt = Runtime.getRuntime();
        System.out.printf("  Free Memory  : %6d MB%n",
                rt.freeMemory() / (1024 * 1024));
        System.out.printf("  Processors   : %d%n", rt.availableProcessors());
    }
}
