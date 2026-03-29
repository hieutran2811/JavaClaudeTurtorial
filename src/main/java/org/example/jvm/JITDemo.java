package org.example.jvm;

import java.util.stream.LongStream;

/**
 * DEMO 3: JIT Compiler - Tai sao Java "chay cham luc dau, nhanh sau"
 *
 * Bai toan thuc te: Tai sao load test dau tien luon cham hon?
 * -> Warm-up period cua JIT. Quan trong khi lam performance testing.
 *
 * JIT Co che:
 * - Lan dau: Interpreter chay (cham, khong compile)
 * - Sau ~1,500 lan goi (C1 threshold): Compile nhe (tier 1-3)
 * - Sau ~10,000 lan goi (C2 threshold): Compile day du + optimize (tier 4)
 *
 * Optimizations JIT thuc hien:
 * - Inlining: thay the method call bang noi dung method
 * - Loop unrolling: giam overhead cua vong lap
 * - Escape analysis: object khong thoat khoi method -> allocate tren Stack (khong Heap!)
 * - Dead code elimination
 */
public class JITDemo {

    public static void main(String[] args) {
        System.out.println("=== JIT Warm-up Demo ===");
        System.out.println("Chay method 5 lan, quan sat thoi gian giam dan:\n");

        for (int round = 1; round <= 5; round++) {
            long start = System.nanoTime();

            // Tinh tong 10 trieu so
            long sum = computeSum(10_000_000L);

            long elapsed = System.nanoTime() - start;
            System.out.printf("Round %d: sum=%d, time=%,d ns (%.2f ms)%n",
                    round, sum, elapsed, elapsed / 1_000_000.0);
        }

        System.out.println("\n-> Round sau nhanh hon round truoc: JIT dang warm up");
        System.out.println("-> Round 3-5 on dinh: JIT da compile xong (steady state)");

        // === Escape Analysis Demo ===
        System.out.println("\n=== Escape Analysis ===");
        System.out.println("Doi tuong khong 'thoat' khoi method -> JIT co the");
        System.out.println("allocate tren Stack thay vi Heap -> giam GC pressure");

        long start = System.nanoTime();
        long result = sumWithObjects(1_000_000);
        long elapsed = System.nanoTime() - start;
        System.out.printf("sumWithObjects: result=%d, time=%,d ns%n", result, elapsed);
        System.out.println("-> Neu JIT optimize, Point objects se khong appear tren Heap");
        System.out.println("   Kiem tra bang: -XX:+PrintEscapeAnalysis -XX:+EliminateAllocations");
    }

    // Method don gian -> JIT se inline va optimize manh
    static long computeSum(long n) {
        long sum = 0;
        for (long i = 1; i <= n; i++) {
            sum += i;
        }
        return sum;
    }

    // Object Point khong "escape" ra ngoai method -> Escape Analysis
    static long sumWithObjects(int n) {
        long total = 0;
        for (int i = 0; i < n; i++) {
            Point p = new Point(i, i * 2); // JIT co the eliminate allocation nay
            total += p.sum();
        }
        return total;
    }

    record Point(int x, int y) {
        long sum() { return x + y; }
    }
}
