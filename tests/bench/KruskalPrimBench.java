import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Kruskal vs Prim on IDENTICAL graphs of three sizes.
 *
 * Measured region (same for both methods): one call of Algorithms.kruskal(request) /
 * Algorithms.prim(request) on an already parsed request. The call contains Input.edges(...) (the
 * List -> int[][] conversion, identical for both), the algorithm itself and the construction of
 * the result map. It does NOT contain JSON parsing, JSON output, graph generation or compilation.
 * The conversion alone is timed separately ("input conversion") so it can be subtracted.
 *
 * Timer: System.nanoTime(). Warm-up: WARMUP untimed calls per method and size (JIT). Each method
 * is then measured RUNS times, alternating Kruskal/Prim, with System.gc() (untimed) before each run.
 * Before any timing the two answers are checked: same cost and component count and a valid forest.
 *
 * Usage: java -Xmx384m -cp .build/Java:.build/tests KruskalPrimBench [seed]
 */
public final class KruskalPrimBench {
    private static final int WARMUP = 5;
    private static final int RUNS = 9;
    private static final int[][] SIZES = {{5_000, 12_500}, {25_000, 62_500}, {100_000, 250_000}};

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static Map<String, Object> buildRequest(String task, int n, int m, long seed) {
        Random rnd = new Random(seed); // same seed => same graph for both tasks
        StringBuilder sb = new StringBuilder("{\"task\":\"" + task + "\",\"n\":" + n + ",\"edges\":[");
        for (int i = 0; i < m; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(rnd.nextInt(n)).append(',').append(rnd.nextInt(n)).append(',')
                    .append(rnd.nextInt(2_000_000_001) - 1_000_000_000).append(']');
        }
        return Json.parseRequest(sb.append("]}").toString());
    }

    /** Independent validity check (naive union-find) of the forest returned for the request. */
    private static void verify(Map<String, Object> request, Map<String, Object> result, int n) {
        int[][] edges = Input.edges(request);
        int[] ids = (int[]) result.get("edge_ids");
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            p[i] = i;
        }
        long sum = 0;
        boolean[] used = new boolean[edges.length];
        for (int id : ids) {
            int a = find(p, edges[id][0]);
            int b = find(p, edges[id][1]);
            if (used[id] || a == b) {
                throw new IllegalStateException("not a forest");
            }
            used[id] = true;
            p[a] = b;
            sum += edges[id][2];
        }
        int[] q = new int[n];
        for (int i = 0; i < n; i++) {
            q[i] = i;
        }
        int comps = n;
        for (int[] e : edges) {
            int a = find(q, e[0]);
            int b = find(q, e[1]);
            if (a != b) {
                q[a] = b;
                comps--;
            }
        }
        if (sum != (Long) result.get("cost") || ids.length != n - comps || comps != (Integer) result.get("components")) {
            throw new IllegalStateException("forest does not span the graph / cost mismatch");
        }
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1e6);
    }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20261025L;
        System.out.println("seed=" + seed + ", warm-up=" + WARMUP + " calls, timed runs=" + RUNS
                + ", timer=System.nanoTime, java=" + System.getProperty("java.version")
                + ", vm=" + System.getProperty("java.vm.name")
                + ", os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + ", cpus=" + Runtime.getRuntime().availableProcessors()
                + ", maxHeap=" + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MiB");
        System.out.println();

        StringBuilder table = new StringBuilder();
        table.append("| n | m | components | MSF cost | method | runs (ms) | median (ms) |\n");
        table.append("|---:|---:|---:|---:|---|---|---:|\n");
        StringBuilder summary = new StringBuilder();
        summary.append("| n | m | input conversion (median, ms) | Kruskal median (ms) | Prim median (ms) | Prim / Kruskal |\n");
        summary.append("|---:|---:|---:|---:|---:|---:|\n");

        for (int[] size : SIZES) {
            int n = size[0];
            int m = size[1];
            Map<String, Object> kReq = buildRequest("kruskal", n, m, seed);
            Map<String, Object> pReq = buildRequest("prim", n, m, seed);

            Map<String, Object> kRes = Algorithms.kruskal(kReq);
            Map<String, Object> pRes = Algorithms.prim(pReq);
            verify(kReq, kRes, n);
            verify(pReq, pRes, n);
            if (!kRes.get("cost").equals(pRes.get("cost")) || !kRes.get("components").equals(pRes.get("components"))) {
                throw new IllegalStateException("Kruskal and Prim disagree for n=" + n);
            }

            for (int i = 0; i < WARMUP; i++) {
                Algorithms.kruskal(kReq);
                Algorithms.prim(pReq);
                Input.edges(kReq);
            }
            long[] kt = new long[RUNS];
            long[] pt = new long[RUNS];
            long[] ct = new long[RUNS];
            for (int i = 0; i < RUNS; i++) {
                System.gc();
                long t = System.nanoTime();
                Algorithms.kruskal(kReq);
                kt[i] = System.nanoTime() - t;

                System.gc();
                t = System.nanoTime();
                Algorithms.prim(pReq);
                pt[i] = System.nanoTime() - t;

                System.gc();
                t = System.nanoTime();
                Input.edges(kReq);
                ct[i] = System.nanoTime() - t;
            }
            for (String method : new String[] {"Kruskal", "Prim"}) {
                long[] t = method.equals("Kruskal") ? kt : pt;
                List<String> runs = new ArrayList<>();
                for (long v : t) {
                    runs.add(ms(v));
                }
                table.append("| ").append(n).append(" | ").append(m).append(" | ").append(kRes.get("components"))
                        .append(" | ").append(kRes.get("cost")).append(" | ").append(method).append(" | ")
                        .append(String.join(", ", runs)).append(" | ").append(ms(median(t))).append(" |\n");
            }
            summary.append("| ").append(n).append(" | ").append(m).append(" | ").append(ms(median(ct)))
                    .append(" | ").append(ms(median(kt))).append(" | ").append(ms(median(pt))).append(" | ")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", (double) median(pt) / median(kt))).append(" |\n");
        }
        System.out.println("Raw measurements (identical graph for both methods; costs verified equal, forests verified valid):\n");
        System.out.println(table);
        System.out.println("Summary:\n");
        System.out.println(summary);
    }
}
