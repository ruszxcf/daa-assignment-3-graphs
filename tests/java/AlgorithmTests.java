import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Self-checking test program for src/Java/Algorithms.java (default package, no framework).
 *
 * Ambiguous answers (forests, SCC labels, topological orders, parent edges) are NOT compared with
 * a stored output. They are checked by meaning with validators that are written independently of
 * the solution (naive label propagation, Floyd-Warshall closure, Bellman-Ford, brute force over
 * edge subsets, an iterative Tarjan and Dijkstra with potentials for the large cases).
 * Each validator is itself tested: it must accept other valid answers and reject corrupted ones.
 *
 * Run (from the repository root; see tests/README.md):  python tests/run_tests.py
 */
public final class AlgorithmTests {
    private static int passed;
    private static int failed;

    // ------------------------------------------------------------------ plumbing

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void expectOk(String error, String name) {
        check(error == null, name + (error == null ? "" : " -> " + error));
    }

    private static void expectRejected(String error, String name) {
        check(error != null, name + " (validator must REJECT this)");
    }

    /** Runs one request exactly like Main does and returns the JSON-decoded result (Lists, Longs, null). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> solve(String json) {
        Map<String, Object> request = Json.parseRequest(json);
        Map<String, Object> result;
        switch ((String) request.get("task")) {
            case "dsu": result = Algorithms.dsu(request); break;
            case "kruskal": result = Algorithms.kruskal(request); break;
            case "prim": result = Algorithms.prim(request); break;
            case "scc": result = Algorithms.scc(request); break;
            case "topo": result = Algorithms.topo(request); break;
            case "dag": result = Algorithms.dag(request); break;
            default: throw new IllegalArgumentException("task");
        }
        return (Map<String, Object>) Json.parse(Json.write(result)); // same bytes the grader would read
    }

    private static String arr(int[] a) {
        return Arrays.toString(a).replace(" ", "");
    }

    private static String arr(int[][] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i > 0 ? "," : "").append(arr(a[i]));
        }
        return sb.append("]").toString();
    }

    private static String req(String task, int n, int[][] rows, String rowsName) {
        return "{\"task\":\"" + task + "\",\"n\":" + n + ",\"" + rowsName + "\":" + arr(rows) + "}";
    }

    private static String dagReq(int n, int source, int[][] edges) {
        return "{\"task\":\"dag\",\"n\":" + n + ",\"source\":" + source + ",\"edges\":" + arr(edges) + "}";
    }

    private static int[] ints(Object o) {
        List<?> l = (List<?>) o;
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = Math.toIntExact(((Number) l.get(i)).longValue());
        }
        return r;
    }

    private static int[][] rows(Object o) {
        List<?> l = (List<?>) o;
        int[][] r = new int[l.size()][];
        for (int i = 0; i < r.length; i++) {
            r[i] = ints(l.get(i));
        }
        return r;
    }

    // ------------------------------------------------------------------ naive reference helpers

    /** Component label of every vertex by repeated label propagation (no DSU, no DFS). */
    private static int[] naiveComponents(int n, int[][] edges, int upto) {
        int[] label = new int[n];
        for (int i = 0; i < n; i++) {
            label[i] = i;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < upto; i++) {
                int a = label[edges[i][0]];
                int b = label[edges[i][1]];
                if (a != b) {
                    int lo = Math.min(a, b);
                    label[edges[i][0]] = lo;
                    label[edges[i][1]] = lo;
                    changed = true;
                }
            }
        }
        // labels may still be "stale" after one vertex changed; iterate to the fixed point above
        return label;
    }

    private static int countDistinct(int[] label) {
        Set<Integer> s = new HashSet<>();
        for (int x : label) {
            s.add(x);
        }
        return s.size();
    }

    private static int largestGroup(int[] label) {
        int best = 0;
        java.util.Map<Integer, Integer> c = new java.util.HashMap<>();
        for (int x : label) {
            best = Math.max(best, c.merge(x, 1, Integer::sum));
        }
        return best;
    }

    /** reach[u][v] = there is a path with at least one edge from u to v (Floyd-Warshall closure). */
    private static boolean[][] closure(int n, int[][] edges) {
        boolean[][] r = new boolean[n][n];
        for (int[] e : edges) {
            r[e[0]][e[1]] = true;
        }
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (r[i][k] && r[k][j]) {
                        r[i][j] = true;
                    }
                }
            }
        }
        return r;
    }

    private static boolean hasCycle(int n, int[][] edges) {
        boolean[][] r = closure(n, edges);
        for (int v = 0; v < n; v++) {
            if (r[v][v]) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ validators (null = valid)

    private static String validateDsu(int n, int[][] ops, Map<String, Object> res) {
        int[][] states = rows(res.get("states"));
        if (states.length != ops.length) {
            return "states length " + states.length + " != " + ops.length;
        }
        for (int i = 0; i < ops.length; i++) {
            int[] label = naiveComponents(n, ops, i + 1);
            int comps = countDistinct(label);
            int largest = n == 0 ? 0 : largestGroup(label);
            if (states[i].length != 2 || states[i][0] != comps || states[i][1] != largest) {
                return "state " + i + " is " + Arrays.toString(states[i]) + ", expected [" + comps + "," + largest + "]";
            }
        }
        return null;
    }

    /** Minimum spanning forest by exhaustive search over all edge subsets (m <= 14). */
    private static long bruteMinForest(int n, int[][] edges) {
        int m = edges.length;
        int comps = countDistinct(naiveComponents(n, edges, m));
        long best = Long.MAX_VALUE;
        for (int mask = 0; mask < (1 << m); mask++) {
            if (Integer.bitCount(mask) != n - comps) {
                continue;
            }
            int[][] sub = new int[Integer.bitCount(mask)][];
            long cost = 0;
            int k = 0;
            for (int i = 0; i < m; i++) {
                if ((mask >> i & 1) != 0) {
                    sub[k++] = edges[i];
                    cost += edges[i][2];
                }
            }
            if (countDistinct(naiveComponents(n, sub, sub.length)) == comps) { // spans every component
                best = Math.min(best, cost);
            }
        }
        return best;
    }

    private static String validateForest(int n, int[][] edges, Map<String, Object> res, Long expectedCost) {
        int[] ids = ints(res.get("edge_ids"));
        long cost = ((Number) res.get("cost")).longValue();
        int comps = ((Number) res.get("components")).intValue();
        int trueComps = countDistinct(naiveComponents(n, edges, edges.length));
        if (comps != trueComps) {
            return "components " + comps + " != " + trueComps;
        }
        if (ids.length != n - trueComps) {
            return "forest has " + ids.length + " edges, needs n-c=" + (n - trueComps);
        }
        Set<Integer> distinct = new HashSet<>();
        int[][] chosen = new int[ids.length][];
        long sum = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || ids[i] >= edges.length || !distinct.add(ids[i])) {
                return "bad or repeated edge id " + ids[i];
            }
            if (edges[ids[i]][0] == edges[ids[i]][1]) {
                return "self-loop selected";
            }
            chosen[i] = edges[ids[i]];
            sum += edges[ids[i]][2];
        }
        if (sum != cost) {
            return "cost " + cost + " != sum of chosen weights " + sum;
        }
        // n - c edges that span the same components as the graph => acyclic spanning forest
        if (countDistinct(naiveComponents(n, chosen, chosen.length)) != trueComps) {
            return "chosen edges contain a cycle / do not span the components";
        }
        if (expectedCost != null && cost != expectedCost) {
            return "cost " + cost + " is not minimum " + expectedCost;
        }
        return null;
    }

    private static String validateScc(int n, int[][] edges, Map<String, Object> res) {
        int[] comp = ints(res.get("component"));
        int[][] cond = rows(res.get("condensation"));
        if (comp.length != n) {
            return "component length";
        }
        boolean[][] r = closure(n, edges);
        for (int u = 0; u < n; u++) {
            if (comp[u] < 0) {
                return "negative label";
            }
            for (int v = 0; v < n; v++) {
                boolean mutual = u == v || (r[u][v] && r[v][u]);
                if (mutual != (comp[u] == comp[v])) {
                    return "vertices " + u + "," + v + " grouped incorrectly";
                }
            }
        }
        Set<String> want = new HashSet<>();
        for (int[] e : edges) {
            if (comp[e[0]] != comp[e[1]]) {
                want.add(comp[e[0]] + ">" + comp[e[1]]);
            }
        }
        Set<String> got = new HashSet<>();
        for (int[] p : cond) {
            if (p.length != 2 || !got.add(p[0] + ">" + p[1])) {
                return "malformed or duplicated condensation pair";
            }
        }
        return want.equals(got) ? null : "condensation " + got + " != " + want;
    }

    private static String validateTopo(int n, int[][] edges, Map<String, Object> res) {
        boolean acyclic = (Boolean) res.get("acyclic");
        int[] order = ints(res.get("order"));
        if (acyclic != !hasCycle(n, edges)) {
            return "acyclic flag is " + acyclic;
        }
        if (!acyclic) {
            return order.length == 0 ? null : "order must be empty for a cyclic graph";
        }
        int[] pos = new int[n];
        Arrays.fill(pos, -1);
        if (order.length != n) {
            return "order has " + order.length + " vertices";
        }
        for (int i = 0; i < n; i++) {
            if (order[i] < 0 || order[i] >= n || pos[order[i]] != -1) {
                return "order is not a permutation";
            }
            pos[order[i]] = i;
        }
        for (int[] e : edges) {
            if (pos[e[0]] >= pos[e[1]]) {
                return "edge " + e[0] + "->" + e[1] + " goes backwards";
            }
        }
        return null;
    }

    private static String validateDag(int n, int source, int[][] edges, Map<String, Object> res) {
        boolean acyclic = (Boolean) res.get("acyclic");
        List<?> dist = (List<?>) res.get("distance");
        int[] parent = ints(res.get("parent_edge"));
        if (acyclic != !hasCycle(n, edges)) {
            return "acyclic flag is " + acyclic;
        }
        if (!acyclic) {
            return dist.isEmpty() && parent.length == 0 ? null : "arrays must be empty for a cyclic graph";
        }
        // Bellman-Ford from the source (n-1 rounds), independent of any topological order.
        Long[] ref = new Long[n];
        ref[source] = 0L;
        for (int round = 0; round < n; round++) {
            for (int[] e : edges) {
                if (ref[e[0]] != null && (ref[e[1]] == null || ref[e[0]] + e[2] < ref[e[1]])) {
                    ref[e[1]] = ref[e[0]] + e[2];
                }
            }
        }
        if (dist.size() != n || parent.length != n) {
            return "array lengths";
        }
        for (int v = 0; v < n; v++) {
            Object got = dist.get(v);
            if (ref[v] == null ? got != null : (got == null || ((Number) got).longValue() != ref[v])) {
                return "distance[" + v + "] = " + got + ", expected " + ref[v];
            }
            if (v == source || ref[v] == null) {
                if (parent[v] != -1) {
                    return "parent_edge[" + v + "] must be -1";
                }
                continue;
            }
            int e = parent[v];
            if (e < 0 || e >= edges.length || edges[e][1] != v) {
                return "parent_edge[" + v + "] = " + e + " is not an edge ending at " + v;
            }
            if (ref[edges[e][0]] == null || ref[edges[e][0]] + edges[e][2] != ref[v]) {
                return "parent edge of " + v + " is not on a shortest path";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ random small graphs

    private static int[][] randomEdges(Random rnd, int n, int m, boolean weighted, int wlo, int whi, boolean loops) {
        int[][] e = new int[m][];
        for (int i = 0; i < m; i++) {
            int u = rnd.nextInt(n);
            int v = rnd.nextInt(n);
            while (!loops && n > 1 && u == v) {
                v = rnd.nextInt(n);
            }
            e[i] = weighted ? new int[] {u, v, wlo + rnd.nextInt(whi - wlo + 1)} : new int[] {u, v};
        }
        return e;
    }

    /** Random DAG: edges only go forward along a random vertex permutation. */
    private static int[][] randomDagEdges(Random rnd, int n, int m, int wlo, int whi) {
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        int[][] e = new int[m][];
        for (int i = 0; i < m; i++) {
            int a = rnd.nextInt(n);
            int b = rnd.nextInt(n);
            while (a == b) {
                b = rnd.nextInt(n);
            }
            if (a > b) {
                int t = a;
                a = b;
                b = t;
            }
            e[i] = new int[] {perm[a], perm[b], wlo + rnd.nextInt(whi - wlo + 1)};
        }
        return e;
    }

    private static void randomSmallTests() {
        Random rnd = new Random(20261025L);
        int cases = 1500;
        int differentForests = 0;
        for (int t = 0; t < cases; t++) {
            int n = rnd.nextInt(9); // 0..8, includes the empty graph
            int m = n == 0 ? 0 : rnd.nextInt(13);

            // DSU: includes repeated unions and self-unions
            int[][] ops = randomEdges(rnd, Math.max(n, 1), n == 0 ? 0 : m, false, 0, 0, true);
            expectOk(validateDsu(n, ops, solve(req("dsu", n, ops, "operations"))), "dsu random #" + t);

            // MST: small weight range => many ties, negatives, parallel edges and loops
            int[][] we = n == 0 ? new int[0][] : randomEdges(rnd, n, m, true, -3, 4, true);
            Long best = bruteMinForest(n, we);
            Map<String, Object> k = solve(req("kruskal", n, we, "edges"));
            Map<String, Object> p = solve(req("prim", n, we, "edges"));
            expectOk(validateForest(n, we, k, best), "kruskal random #" + t);
            expectOk(validateForest(n, we, p, best), "prim random #" + t);
            if (!Arrays.equals(ints(k.get("edge_ids")), ints(p.get("edge_ids")))) {
                differentForests++;
            }

            // directed graphs with loops, parallel edges, cycles
            int[][] de = n == 0 ? new int[0][] : randomEdges(rnd, n, m, false, 0, 0, true);
            expectOk(validateScc(n, de, solve(req("scc", n, de, "edges"))), "scc random #" + t);
            expectOk(validateTopo(n, de, solve(req("topo", n, de, "edges"))), "topo random #" + t);

            // DAG requests: half of them are true DAGs, half are arbitrary graphs (may contain cycles)
            if (n >= 1) {
                int source = rnd.nextInt(n);
                int[][] dw = (t % 2 == 0 && n >= 2)
                        ? randomDagEdges(rnd, n, m, -5, 6)
                        : randomEdges(rnd, n, m, true, -5, 6, t % 4 == 1);
                expectOk(validateDag(n, source, dw, solve(dagReq(n, source, dw))), "dag random #" + t);
            }
        }
        // Different-but-valid forests are accepted because validation is by meaning, not by equality.
        check(differentForests > 0, "random data contained cases where Kruskal and Prim chose different valid ids");
        System.out.println("  random small cases: " + cases + " per task, Kruskal/Prim picked different valid forests in "
                + differentForests);
    }

    // ------------------------------------------------------------------ validator self-tests

    @SuppressWarnings("unchecked")
    private static void validatorSelfTests() {
        // DSU: wrong state is rejected
        int[][] ops = {{0, 1}, {1, 2}, {0, 2}, {3, 3}};
        expectOk(validateDsu(4, ops, solve(req("dsu", 4, ops, "operations"))), "dsu spec example");
        Map<String, Object> bad = solve(req("dsu", 4, ops, "operations"));
        ((List<Object>) bad.get("states")).set(2, List.of(1L, 3L));
        expectRejected(validateDsu(4, ops, bad), "dsu with wrong component count");

        // Forest: tied optimum accepted in either variant, corrupted answers rejected
        int[][] tri = {{0, 1, 1}, {1, 2, 1}, {0, 2, 1}}; // three equal edges: three optimal forests
        for (int[] ids : new int[][] {{0, 1}, {1, 2}, {0, 2}}) {
            Map<String, Object> alt = solve(req("kruskal", 3, tri, "edges"));
            alt.put("edge_ids", toList(ids));
            expectOk(validateForest(3, tri, alt, 2L), "forest alternative optimum " + Arrays.toString(ids));
        }
        Map<String, Object> w = solve(req("kruskal", 3, tri, "edges"));
        w.put("cost", 3L);
        expectRejected(validateForest(3, tri, w, null), "forest with wrong cost");
        w = solve(req("kruskal", 3, tri, "edges"));
        w.put("edge_ids", toList(new int[] {0, 0}));
        expectRejected(validateForest(3, tri, w, null), "forest with repeated edge id");
        w = solve(req("kruskal", 3, tri, "edges"));
        w.put("edge_ids", toList(new int[] {0}));
        expectRejected(validateForest(3, tri, w, null), "forest with too few edges");
        int[][] heavy = {{0, 1, 1}, {1, 2, 1}, {0, 2, 5}};
        w = solve(req("kruskal", 3, heavy, "edges"));
        w.put("edge_ids", toList(new int[] {0, 2}));
        w.put("cost", 6L);
        expectRejected(validateForest(3, heavy, w, 2L), "forest that is valid but not minimum");

        // SCC: relabelled answer accepted, merged/split groups and missing condensation edge rejected
        int[][] g = {{0, 1}, {1, 0}, {1, 2}, {0, 2}, {2, 3}};
        Map<String, Object> s = solve(req("scc", 4, g, "edges"));
        expectOk(validateScc(4, g, s), "scc spec example");
        Map<String, Object> relabelled = solve(req("scc", 4, g, "edges"));
        relabelled.put("component", toList(new int[] {5, 5, 7, 9}));
        relabelled.put("condensation", List.of(List.of(5L, 7L), List.of(7L, 9L)));
        expectOk(validateScc(4, g, relabelled), "scc with the different (non-consecutive) labels of the spec");
        Map<String, Object> merged = solve(req("scc", 4, g, "edges"));
        merged.put("component", toList(new int[] {0, 0, 0, 1}));
        expectRejected(validateScc(4, g, merged), "scc that merged 2 into the {0,1} group");
        Map<String, Object> missing = solve(req("scc", 4, g, "edges"));
        missing.put("condensation", List.of(List.of(5L, 7L)));
        missing.put("component", toList(new int[] {5, 5, 7, 9}));
        expectRejected(validateScc(4, g, missing), "scc with a missing condensation pair");
        Map<String, Object> dup = solve(req("scc", 4, g, "edges"));
        dup.put("component", toList(new int[] {5, 5, 7, 9}));
        dup.put("condensation", List.of(List.of(5L, 7L), List.of(5L, 7L), List.of(7L, 9L)));
        expectRejected(validateScc(4, g, dup), "scc with a duplicated condensation pair");

        // Topo: other valid order accepted, backwards order and wrong acyclic flag rejected
        int[][] t = {{0, 2}, {1, 2}, {2, 3}};
        for (int[] order : new int[][] {{1, 0, 2, 3}, {0, 1, 2, 3}}) {
            Map<String, Object> alt = solve(req("topo", 4, t, "edges"));
            alt.put("order", toList(order));
            expectOk(validateTopo(4, t, alt), "topo alternative order " + Arrays.toString(order));
        }
        Map<String, Object> back = solve(req("topo", 4, t, "edges"));
        back.put("order", toList(new int[] {2, 0, 1, 3}));
        expectRejected(validateTopo(4, t, back), "topo order with a backward edge");
        Map<String, Object> flag = solve(req("topo", 4, t, "edges"));
        flag.put("acyclic", false);
        flag.put("order", toList(new int[0]));
        expectRejected(validateTopo(4, t, flag), "topo claiming a cycle in a DAG");
        int[][] cyc = {{0, 1}, {1, 0}};
        Map<String, Object> claim = solve(req("topo", 2, cyc, "edges"));
        claim.put("acyclic", true);
        claim.put("order", toList(new int[] {0, 1}));
        expectRejected(validateTopo(2, cyc, claim), "topo claiming a cycle-free order for a cycle");

        // DAG: spec example, -1 distance vs unreachable, bad parent edges
        int[][] d = {{0, 1, 3}, {0, 2, 8}, {1, 2, -7}, {2, 3, 4}};
        Map<String, Object> dr = solve(dagReq(5, 0, d));
        expectOk(validateDag(5, 0, d, dr), "dag spec example");
        List<Object> dl = new ArrayList<>((List<Object>) dr.get("distance"));
        dl.set(4, -1L);
        Map<String, Object> neg1 = solve(dagReq(5, 0, d));
        neg1.put("distance", dl);
        expectRejected(validateDag(5, 0, d, neg1), "dag reporting unreachable vertex as -1");
        Map<String, Object> wp = solve(dagReq(5, 0, d));
        wp.put("parent_edge", toList(new int[] {-1, 0, 1, 3, -1})); // edge 1 is 0->2 (cost 8), not shortest
        expectRejected(validateDag(5, 0, d, wp), "dag with a parent edge that is not on a shortest path");
        wp = solve(dagReq(5, 0, d));
        wp.put("parent_edge", toList(new int[] {-1, 0, 2, 2, -1})); // edge 2 does not end in vertex 3
        expectRejected(validateDag(5, 0, d, wp), "dag with a parent edge ending at the wrong vertex");
        // equal paths: both parent choices accepted
        int[][] tie = {{0, 1, 1}, {1, 3, 1}, {0, 2, 1}, {2, 3, 1}};
        for (int pe : new int[] {1, 3}) {
            Map<String, Object> alt = solve(dagReq(4, 0, tie));
            alt.put("parent_edge", toList(new int[] {-1, 0, 2, pe}));
            expectOk(validateDag(4, 0, tie, alt), "dag tied path through edge " + pe);
        }
    }

    private static List<Object> toList(int[] a) {
        List<Object> l = new ArrayList<>();
        for (int x : a) {
            l.add((long) x);
        }
        return l;
    }

    // ------------------------------------------------------------------ fixed edge cases

    private static void edgeCases() {
        // n = 0 everywhere
        Map<String, Object> r = solve("{\"task\":\"dsu\",\"n\":0,\"operations\":[]}");
        check(rows(r.get("states")).length == 0, "dsu n=0");
        r = solve("{\"task\":\"kruskal\",\"n\":0,\"edges\":[]}");
        check(((Number) r.get("cost")).longValue() == 0 && ints(r.get("edge_ids")).length == 0
                && ((Number) r.get("components")).intValue() == 0, "kruskal n=0");
        r = solve("{\"task\":\"prim\",\"n\":0,\"edges\":[]}");
        check(((Number) r.get("cost")).longValue() == 0 && ints(r.get("edge_ids")).length == 0
                && ((Number) r.get("components")).intValue() == 0, "prim n=0");
        r = solve("{\"task\":\"scc\",\"n\":0,\"edges\":[]}");
        check(ints(r.get("component")).length == 0 && rows(r.get("condensation")).length == 0, "scc n=0");
        r = solve("{\"task\":\"topo\",\"n\":0,\"edges\":[]}");
        check((Boolean) r.get("acyclic") && ints(r.get("order")).length == 0, "topo n=0");

        // DSU: n=1 self union; initial largest = 1; repeated union
        r = solve("{\"task\":\"dsu\",\"n\":1,\"operations\":[[0,0],[0,0]]}");
        check(Arrays.deepEquals(rows(r.get("states")), new int[][] {{1, 1}, {1, 1}}), "dsu n=1 self unions");

        // Weights: sums beyond 32 bit; negative weights; isolated vertices counted as components
        int[][] big = {{0, 1, 1_000_000_000}, {1, 2, 1_000_000_000}, {2, 3, 1_000_000_000}, {0, 3, 1_000_000_000},
                {4, 4, -1_000_000_000}};
        for (String task : new String[] {"kruskal", "prim"}) {
            r = solve(req(task, 6, big, "edges"));
            check(((Number) r.get("cost")).longValue() == 3_000_000_000L, task + " 64-bit sum (3e9 > 2^31)");
            check(((Number) r.get("components")).intValue() == 3, task + " isolated vertices + loop component");
            expectOk(validateForest(6, big, r, 3_000_000_000L), task + " 64-bit forest");
        }
        int[][] neg = {{0, 1, -1_000_000_000}, {1, 2, -1_000_000_000}, {0, 2, -1_000_000_000}};
        for (String task : new String[] {"kruskal", "prim"}) {
            r = solve(req(task, 3, neg, "edges"));
            check(((Number) r.get("cost")).longValue() == -2_000_000_000L, task + " minimum -2e9 (below -2^31)");
        }

        // SCC: duplicated parallel edges between two SCCs give exactly one condensation pair; loops ignored
        int[][] dupe = {{0, 1}, {1, 0}, {1, 2}, {1, 2}, {0, 2}, {2, 2}};
        r = solve(req("scc", 3, dupe, "edges"));
        check(rows(r.get("condensation")).length == 1, "scc condensation has the pair exactly once");
        expectOk(validateScc(3, dupe, r), "scc duplicates and loops");

        // topo: self-loop is a cycle; cycle away from vertex 0
        r = solve(req("topo", 3, new int[][] {{0, 1}, {2, 2}}, "edges"));
        check(!(Boolean) r.get("acyclic") && ints(r.get("order")).length == 0, "topo self-loop");
        r = solve(req("topo", 5, new int[][] {{0, 1}, {2, 3}, {3, 4}, {4, 2}}, "edges"));
        check(!(Boolean) r.get("acyclic"), "topo cycle in a later component");
        // diamond: finished vertex reached twice must not be reported as a cycle
        r = solve(req("topo", 4, new int[][] {{0, 1}, {0, 2}, {1, 3}, {2, 3}}, "edges"));
        check((Boolean) r.get("acyclic"), "topo diamond is not a cycle (edge into FINISHED vertex)");

        // DAG: cycle unreachable from the source still means acyclic=false
        r = solve(dagReq(4, 0, new int[][] {{0, 1, 5}, {2, 3, 1}, {3, 2, 1}}));
        check(!(Boolean) r.get("acyclic") && ((List<?>) r.get("distance")).isEmpty()
                && ints(r.get("parent_edge")).length == 0, "dag unreachable cycle");
        // unreachable vertex has null, and a real distance of -1 is NOT confused with it
        r = solve(dagReq(3, 0, new int[][] {{0, 1, -1}}));
        List<?> dist = (List<?>) r.get("distance");
        check(((Number) dist.get(1)).longValue() == -1L && dist.get(2) == null, "dag -1 vs null");
        // never relax out of an unreachable vertex: negative edge from unreachable vertex 1 must be ignored
        r = solve(dagReq(3, 0, new int[][] {{1, 2, -100}, {0, 2, 7}}));
        check(((Number) ((List<?>) r.get("distance")).get(2)).longValue() == 7L, "dag ignores unreachable tails");
        // source is not vertex 0 and has predecessors that cannot reach it back
        int[][] mid = {{0, 1, 4}, {1, 2, 1}, {2, 3, 1}};
        r = solve(dagReq(4, 1, mid));
        check(((List<?>) r.get("distance")).get(0) == null && ((Number) ((List<?>) r.get("distance")).get(3)).longValue() == 2L,
                "dag source in the middle");
        expectOk(validateDag(4, 1, mid, r), "dag source in the middle (validator)");
        // single vertex
        r = solve(dagReq(1, 0, new int[0][]));
        check((Boolean) r.get("acyclic") && ((Number) ((List<?>) r.get("distance")).get(0)).longValue() == 0
                && ints(r.get("parent_edge"))[0] == -1, "dag n=1");
        // loop on the source
        r = solve(dagReq(1, 0, new int[][] {{0, 0, -4}}));
        check(!(Boolean) r.get("acyclic"), "dag self-loop is a cycle (even with negative weight)");
    }

    // ------------------------------------------------------------------ route reconstruction

    private static long routeCost(int[][] edges, int[] ids) {
        long s = 0;
        for (int id : ids) {
            s += edges[id][2];
        }
        return s;
    }

    /** Checks direction (each consecutive pair is the chosen edge) and that total weight = distance. */
    private static String validateRoute(int[][] edges, int[] route, int[] edgeIds, int source, int target, Long distance) {
        if (distance == null) {
            return route.length == 0 && edgeIds == null ? null : "unreachable target must give empty route / null ids";
        }
        if (route.length == 0 || route[0] != source || route[route.length - 1] != target) {
            return "route must start at source and end at target: " + Arrays.toString(route);
        }
        if (edgeIds.length != route.length - 1) {
            return "edge id count";
        }
        for (int i = 0; i < edgeIds.length; i++) {
            if (edges[edgeIds[i]][0] != route[i] || edges[edgeIds[i]][1] != route[i + 1]) {
                return "edge " + edgeIds[i] + " does not go " + route[i] + "->" + route[i + 1] + " (direction)";
            }
        }
        return routeCost(edges, edgeIds) == distance ? null : "route weight " + routeCost(edges, edgeIds) + " != distance " + distance;
    }

    private static void routeTests() {
        // spec example: route to 3 is 0,1,2,3 with total cost 0
        int[][] ex = {{0, 1, 3}, {0, 2, 8}, {1, 2, -7}, {2, 3, 4}};
        Map<String, Object> r = solve(dagReq(5, 0, ex));
        int[] parent = ints(r.get("parent_edge"));
        List<?> dist = (List<?>) r.get("distance");
        check(Arrays.equals(Algorithms.reconstructRoute(ex, parent, 0, 3), new int[] {0, 1, 2, 3}), "route 0->3 is [0,1,2,3]");
        check(Arrays.equals(Algorithms.reconstructRouteEdges(ex, parent, 0, 3), new int[] {0, 2, 3}), "route 0->3 uses edge ids [0,2,3]");
        for (int target = 0; target < 5; target++) {
            Long d = dist.get(target) == null ? null : ((Number) dist.get(target)).longValue();
            expectOk(validateRoute(ex, Algorithms.reconstructRoute(ex, parent, 0, target),
                    Algorithms.reconstructRouteEdges(ex, parent, 0, target), 0, target, d), "route target " + target);
        }
        check(Arrays.equals(Algorithms.reconstructRoute(ex, parent, 0, 0), new int[] {0}), "route to the source is [source]");
        check(Algorithms.reconstructRoute(ex, parent, 0, 4).length == 0, "route to an unreachable target is empty");
        check(Algorithms.reconstructRouteEdges(ex, parent, 0, 4) == null, "edge ids to an unreachable target are null");

        // parallel edges: both ids are valid shortest edges, either route is accepted
        int[][] par = {{0, 1, 5}, {0, 1, 5}, {0, 1, 9}, {1, 2, 1}};
        r = solve(dagReq(3, 0, par));
        parent = ints(r.get("parent_edge"));
        int[] ids = Algorithms.reconstructRouteEdges(par, parent, 0, 2);
        expectOk(validateRoute(par, Algorithms.reconstructRoute(par, parent, 0, 2), ids, 0, 2, 6L), "route over parallel edges");
        check(parent[1] == 0 || parent[1] == 1, "parallel edges: parent is one of the two cheapest ids");
        for (int alt : new int[] {0, 1}) { // the other optimal choice must also reconstruct correctly
            int[] p2 = parent.clone();
            p2[1] = alt;
            expectOk(validateRoute(par, Algorithms.reconstructRoute(par, p2, 0, 2),
                    Algorithms.reconstructRouteEdges(par, p2, 0, 2), 0, 2, 6L), "route with parent edge id " + alt);
        }

        // equal-length paths
        int[][] tie = {{0, 1, 1}, {1, 3, 1}, {0, 2, 1}, {2, 3, 1}};
        r = solve(dagReq(4, 0, tie));
        parent = ints(r.get("parent_edge"));
        for (int via : new int[] {1, 3}) {
            int[] p2 = parent.clone();
            p2[3] = via;
            int[] route = Algorithms.reconstructRoute(tie, p2, 0, 3);
            expectOk(validateRoute(tie, route, Algorithms.reconstructRouteEdges(tie, p2, 0, 3), 0, 3, 2L), "tied route via parent " + via);
            check(route.length == 3 && (route[1] == 1 || route[1] == 2), "tied route has 3 vertices");
        }

        // corrupt parent arrays are reported, not looped on
        boolean threw = false;
        try {
            Algorithms.reconstructRoute(tie, new int[] {-1, 0, 2, 0}, 0, 3); // edge 0 is 0->1, not into 3
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "route rejects a parent edge with the wrong head");
        threw = false;
        try {
            Algorithms.reconstructRoute(new int[][] {{1, 2, 0}, {2, 1, 0}}, new int[] {-1, 1, 0}, 0, 2);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "route rejects a parent cycle");

        // long route: chain of 100000 vertices, no recursion, linear time
        int n = 100_000;
        int[][] chain = new int[n - 1][];
        for (int i = 0; i + 1 < n; i++) {
            chain[i] = new int[] {i, i + 1, -1_000_000_000};
        }
        r = solve(dagReq(n, 0, chain));
        parent = ints(r.get("parent_edge"));
        int[] route = Algorithms.reconstructRoute(chain, parent, 0, n - 1);
        check(route.length == n && route[0] == 0 && route[n - 1] == n - 1, "route over a 100000-vertex chain");
        check(routeCost(chain, Algorithms.reconstructRouteEdges(chain, parent, 0, n - 1)) == -1_000_000_000L * (n - 1),
                "chain route weight is -1e9*(n-1) (needs 64 bit)");
    }

    // ------------------------------------------------------------------ large / deep inputs

    /** Independent DSU used only to verify large answers. */
    private static final class RefDsu {
        final int[] p;
        final int[] sz;
        int comps;
        int largest;

        RefDsu(int n) {
            p = new int[n];
            sz = new int[n];
            for (int i = 0; i < n; i++) {
                p[i] = i;
                sz[i] = 1;
            }
            comps = n;
            largest = n > 0 ? 1 : 0;
        }

        int find(int x) {
            while (p[x] != x) {
                p[x] = p[p[x]];
                x = p[x];
            }
            return x;
        }

        boolean union(int a, int b) {
            a = find(a);
            b = find(b);
            if (a == b) {
                return false;
            }
            p[a] = b; // deliberately naive: no size heuristic, to be different from the solution
            sz[b] += sz[a];
            comps--;
            largest = Math.max(largest, sz[b]);
            return true;
        }
    }

    private static int[][] randomLarge(Random rnd, int n, int m, boolean weighted, int lo, int hi) {
        return randomEdges(rnd, n, m, weighted, lo, hi, true);
    }

    private static String validateForestLarge(int n, int[][] edges, Map<String, Object> res) {
        int[] ids = ints(res.get("edge_ids"));
        RefDsu all = new RefDsu(n);
        for (int[] e : edges) {
            all.union(e[0], e[1]);
        }
        if (((Number) res.get("components")).intValue() != all.comps || ids.length != n - all.comps) {
            return "components/edge count wrong";
        }
        RefDsu chosen = new RefDsu(n);
        boolean[] used = new boolean[edges.length];
        long sum = 0;
        for (int id : ids) {
            if (used[id] || !chosen.union(edges[id][0], edges[id][1])) {
                return "repeated id, loop or cycle at edge " + id;
            }
            used[id] = true;
            sum += edges[id][2];
        }
        return sum == ((Number) res.get("cost")).longValue() ? null : "cost != sum";
    }

    /** Iterative Tarjan: an independent SCC algorithm for cross-checking the Kosaraju answer. */
    private static int[] tarjan(int n, int[][] edges) {
        int[] head = new int[n];
        Arrays.fill(head, -1);
        int[] next = new int[edges.length];
        for (int i = 0; i < edges.length; i++) {
            next[i] = head[edges[i][0]];
            head[edges[i][0]] = i;
        }
        int[] index = new int[n];
        int[] low = new int[n];
        Arrays.fill(index, -1);
        boolean[] onStack = new boolean[n];
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int[] callStack = new int[n];
        int[] edgePtr = head.clone();
        int[] sccStack = new int[n];
        int sp = 0;
        int counter = 0;
        int labels = 0;
        for (int root = 0; root < n; root++) {
            if (index[root] != -1) {
                continue;
            }
            int cs = 0;
            callStack[cs++] = root;
            index[root] = low[root] = counter++;
            sccStack[sp++] = root;
            onStack[root] = true;
            while (cs > 0) {
                int v = callStack[cs - 1];
                if (edgePtr[v] != -1) {
                    int w = edges[edgePtr[v]][1];
                    edgePtr[v] = next[edgePtr[v]];
                    if (index[w] == -1) {
                        index[w] = low[w] = counter++;
                        sccStack[sp++] = w;
                        onStack[w] = true;
                        callStack[cs++] = w;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                } else {
                    cs--;
                    if (cs > 0) {
                        int parent = callStack[cs - 1];
                        low[parent] = Math.min(low[parent], low[v]);
                    }
                    if (low[v] == index[v]) {
                        int w;
                        do {
                            w = sccStack[--sp];
                            onStack[w] = false;
                            comp[w] = labels;
                        } while (w != v);
                        labels++;
                    }
                }
            }
        }
        return comp;
    }

    private static String samePartition(int[] a, int[] b) {
        java.util.Map<Integer, Integer> ab = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> ba = new java.util.HashMap<>();
        for (int i = 0; i < a.length; i++) {
            Integer x = ab.putIfAbsent(a[i], b[i]);
            Integer y = ba.putIfAbsent(b[i], a[i]);
            if ((x != null && x != b[i]) || (y != null && y != a[i])) {
                return "partitions differ at vertex " + i;
            }
        }
        return null;
    }

    private static String validateCondensationLarge(int[][] edges, int[] comp, int[][] cond) {
        Set<Long> want = new HashSet<>();
        for (int[] e : edges) {
            if (comp[e[0]] != comp[e[1]]) {
                want.add(((long) comp[e[0]] << 32) | comp[e[1]]);
            }
        }
        Set<Long> got = new HashSet<>();
        for (int[] p : cond) {
            if (!got.add(((long) p[0] << 32) | p[1])) {
                return "duplicate pair";
            }
        }
        return want.equals(got) ? null : "condensation differs (" + got.size() + " vs " + want.size() + ")";
    }

    private static void largeTests() {
        final int n = 100_000;
        final int m = 250_000;
        Random rnd = new Random(7);
        long t0 = System.nanoTime();

        // --- DSU: random unions, then long chains in both directions (degenerate for naive DSU)
        int[][] ops = randomLarge(rnd, n, m, false, 0, 0);
        verifyDsuLarge(n, ops, "dsu random 250000 unions");
        int[][] chainUp = new int[m][];
        for (int i = 0; i < m; i++) {
            chainUp[i] = i + 1 < n ? new int[] {i, i + 1} : new int[] {0, 0};
        }
        verifyDsuLarge(n, chainUp, "dsu chain 0-1-2-... (+ self unions)");
        int[][] chainDown = new int[m][];
        for (int i = 0; i < m; i++) {
            chainDown[i] = i + 1 < n ? new int[] {n - 1 - i, n - 2 - i} : new int[] {1, 0};
        }
        verifyDsuLarge(n, chainDown, "dsu chain n-1,n-2,...");

        // --- Kruskal / Prim: random weights over the whole +-1e9 range, and ties, and all-large weights
        int[][][] weightedGraphs = {
                randomLarge(rnd, n, m, true, -1_000_000_000, 1_000_000_000),
                randomLarge(rnd, n, m, true, 0, 3),
                randomLarge(rnd, n, m, true, 900_000_000, 1_000_000_000),
                randomLarge(rnd, 100, m, true, -1_000_000_000, 1_000_000_000), // very dense, many parallel edges
        };
        int[] sizes = {n, n, n, 100};
        for (int g = 0; g < weightedGraphs.length; g++) {
            Map<String, Object> k = solve(req("kruskal", sizes[g], weightedGraphs[g], "edges"));
            Map<String, Object> p = solve(req("prim", sizes[g], weightedGraphs[g], "edges"));
            expectOk(validateForestLarge(sizes[g], weightedGraphs[g], k), "kruskal large graph " + g);
            expectOk(validateForestLarge(sizes[g], weightedGraphs[g], p), "prim large graph " + g);
            check(((Number) k.get("cost")).longValue() == ((Number) p.get("cost")).longValue(),
                    "kruskal cost == prim cost on large graph " + g);
        }
        // exact known answer: a path of n vertices with weight 1e9 plus heavier-or-equal chords
        int[][] path = new int[m][];
        for (int i = 0; i < m; i++) {
            path[i] = i < n - 1 ? new int[] {i, i + 1, 1_000_000_000} : new int[] {rnd.nextInt(n), rnd.nextInt(n), 1_000_000_000};
        }
        for (String task : new String[] {"kruskal", "prim"}) {
            Map<String, Object> r = solve(req(task, n, path, "edges"));
            check(((Number) r.get("cost")).longValue() == 1_000_000_000L * (n - 1), task + " large exact cost 1e9*(n-1)");
        }

        // --- SCC, topo and DAG on deep chains and random graphs
        int[][] ring = new int[n][];
        for (int i = 0; i < n; i++) {
            ring[i] = new int[] {i, (i + 1) % n};
        }
        Map<String, Object> r = solve(req("scc", n, ring, "edges"));
        check(countDistinct(ints(r.get("component"))) == 1 && rows(r.get("condensation")).length == 0, "scc 100000-vertex ring");
        int[][] chain = new int[n - 1][];
        int[][] chainRev = new int[n - 1][]; // same chain, edges listed from the end: i+1 -> i is NOT used, ids reversed
        for (int i = 0; i < n - 1; i++) {
            chain[i] = new int[] {i, i + 1};
            chainRev[n - 2 - i] = new int[] {i, i + 1};
        }
        for (int[][] c : new int[][][] {chain, chainRev}) {
            r = solve(req("scc", n, c, "edges"));
            check(countDistinct(ints(r.get("component"))) == n && rows(r.get("condensation")).length == n - 1, "scc deep chain");
            r = solve(req("topo", n, c, "edges"));
            check((Boolean) r.get("acyclic") && Arrays.equals(ints(r.get("order")), iota(n)), "topo deep chain gives 0..n-1");
        }
        int[][] chainBack = new int[n - 1][]; // chain pointing to lower numbers: order must be n-1 .. 0
        for (int i = 0; i < n - 1; i++) {
            chainBack[i] = new int[] {i + 1, i};
        }
        r = solve(req("topo", n, chainBack, "edges"));
        int[] order = ints(r.get("order"));
        check((Boolean) r.get("acyclic") && order[0] == n - 1 && order[n - 1] == 0, "topo deep chain reversed numbering");
        int[][] ringTopo = ring;
        r = solve(req("topo", n, ringTopo, "edges"));
        check(!(Boolean) r.get("acyclic"), "topo detects the 100000-cycle");

        for (int round = 0; round < 3; round++) {
            int[][] g = randomLarge(rnd, n, m, false, 0, 0);
            r = solve(req("scc", n, g, "edges"));
            int[] comp = ints(r.get("component"));
            expectOk(samePartition(comp, tarjan(n, g)), "scc random large #" + round + " equals Tarjan partition");
            expectOk(validateCondensationLarge(g, comp, rows(r.get("condensation"))), "scc random large #" + round + " condensation");
            int[][] sparse = randomLarge(rnd, n, n * 6 / 10, false, 0, 0); // below the giant-component threshold
            r = solve(req("scc", n, sparse, "edges"));
            comp = ints(r.get("component"));
            expectOk(samePartition(comp, tarjan(n, sparse)), "scc sparse large #" + round + " equals Tarjan partition");
            expectOk(validateCondensationLarge(sparse, comp, rows(r.get("condensation"))), "scc sparse large #" + round + " condensation");
        }

        int[][] dagEdges = randomDagEdges(rnd, n, m, 0, 0);
        int[][] dagNoWeights = new int[m][];
        for (int i = 0; i < m; i++) {
            dagNoWeights[i] = new int[] {dagEdges[i][0], dagEdges[i][1]};
        }
        r = solve(req("topo", n, dagNoWeights, "edges"));
        check((Boolean) r.get("acyclic") && forwardOnly(dagNoWeights, ints(r.get("order")), n), "topo random large DAG order");
        // one back edge creating a cycle somewhere
        int[][] withCycle = Arrays.copyOf(dagNoWeights, m);
        withCycle[m - 1] = new int[] {dagNoWeights[0][1], dagNoWeights[0][0]};
        r = solve(req("topo", n, withCycle, "edges"));
        check(!(Boolean) r.get("acyclic"), "topo random large graph with a cycle");

        // DAG distances: reweighting with potentials makes every edge cost >= 0, so Dijkstra is an
        // independent oracle (true distance = d'(v) - p[source] + p[v]).
        for (int round = 0; round < 3; round++) {
            int[] pot = new int[n];
            for (int i = 0; i < n; i++) {
                pot[i] = rnd.nextInt(400_000_001) - 200_000_000;
            }
            int[][] dg = randomDagEdges(rnd, n, m, 0, 0);
            for (int[] e : dg) {
                int slack = rnd.nextInt(400_000_001);
                e[2] = pot[e[1]] - pot[e[0]] + slack; // |w| <= 8e8
            }
            int source = rnd.nextInt(n);
            r = solve(dagReq(n, source, dg));
            check((Boolean) r.get("acyclic"), "dag large acyclic #" + round);
            Long[] oracle = dijkstraWithPotentials(n, dg, pot, source);
            List<?> dist = (List<?>) r.get("distance");
            int[] parent = ints(r.get("parent_edge"));
            boolean ok = dist.size() == n;
            for (int v = 0; ok && v < n; v++) {
                Object got = dist.get(v);
                ok = oracle[v] == null ? got == null : (got != null && ((Number) got).longValue() == oracle[v]);
            }
            check(ok, "dag large distances equal Dijkstra-with-potentials #" + round);
            boolean parentsOk = true;
            for (int v = 0; parentsOk && v < n; v++) {
                if (v == source || oracle[v] == null) {
                    parentsOk = parent[v] == -1;
                } else {
                    int e = parent[v];
                    parentsOk = e >= 0 && dg[e][1] == v && oracle[dg[e][0]] != null && oracle[dg[e][0]] + dg[e][2] == oracle[v];
                }
            }
            check(parentsOk, "dag large parent edges lie on shortest paths #" + round);
        }
        // deep chain with the most negative weights: 64-bit distances, closed form
        int[][] negChain = new int[n - 1][];
        for (int i = 0; i < n - 1; i++) {
            negChain[i] = new int[] {i, i + 1, -1_000_000_000};
        }
        r = solve(dagReq(n, 0, negChain));
        List<?> dist = (List<?>) r.get("distance");
        check(((Number) dist.get(n - 1)).longValue() == -1_000_000_000L * (n - 1), "dag deep chain distance -1e9*(n-1)");
        // cycle far from the source in an otherwise huge DAG
        int[][] cyc = Arrays.copyOf(negChain, n);
        cyc[n - 1] = new int[] {n - 1, n - 2, 1};
        r = solve(dagReq(n, 0, cyc));
        check(!(Boolean) r.get("acyclic"), "dag deep chain with a back edge at the far end");

        System.out.printf("  large/deep tests finished in %.1f s%n", (System.nanoTime() - t0) / 1e9);
    }

    private static int[] iota(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    private static boolean forwardOnly(int[][] edges, int[] order, int n) {
        if (order.length != n) {
            return false;
        }
        int[] pos = new int[n];
        Arrays.fill(pos, -1);
        for (int i = 0; i < n; i++) {
            if (pos[order[i]] != -1) {
                return false;
            }
            pos[order[i]] = i;
        }
        for (int[] e : edges) {
            if (pos[e[0]] >= pos[e[1]]) {
                return false;
            }
        }
        return true;
    }

    private static Long[] dijkstraWithPotentials(int n, int[][] edges, int[] pot, int source) {
        int[] head = new int[n];
        Arrays.fill(head, -1);
        int[] next = new int[edges.length];
        for (int i = 0; i < edges.length; i++) {
            next[i] = head[edges[i][0]];
            head[edges[i][0]] = i;
        }
        long[] d = new long[n];
        Arrays.fill(d, Long.MAX_VALUE);
        d[source] = 0;
        PriorityQueue<long[]> pq = new PriorityQueue<>((x, y) -> Long.compare(x[0], y[0]));
        pq.add(new long[] {0, source});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int v = (int) top[1];
            if (top[0] > d[v]) {
                continue;
            }
            for (int e = head[v]; e != -1; e = next[e]) {
                int w = edges[e][1];
                long reduced = (long) edges[e][2] + pot[v] - pot[w];
                if (reduced < 0) {
                    throw new IllegalStateException("potentials broken");
                }
                if (d[v] + reduced < d[w]) {
                    d[w] = d[v] + reduced;
                    pq.add(new long[] {d[w], w});
                }
            }
        }
        Long[] out = new Long[n];
        for (int v = 0; v < n; v++) {
            if (d[v] != Long.MAX_VALUE) {
                out[v] = d[v] - pot[source] + pot[v];
            }
        }
        return out;
    }

    private static void verifyDsuLarge(int n, int[][] ops, String name) {
        Map<String, Object> r = solve(req("dsu", n, ops, "operations"));
        int[][] states = rows(r.get("states"));
        RefDsu ref = new RefDsu(n);
        boolean ok = states.length == ops.length;
        for (int i = 0; ok && i < ops.length; i++) {
            ref.union(ops[i][0], ops[i][1]);
            ok = states[i][0] == ref.comps && states[i][1] == ref.largest;
        }
        check(ok, name);
    }

    // ------------------------------------------------------------------ state reset

    private static void stateResetTest() {
        String[] batch = {
                req("dsu", 4, new int[][] {{0, 1}, {1, 2}, {0, 2}, {3, 3}}, "operations"),
                req("kruskal", 4, new int[][] {{0, 1, 4}, {1, 2, 2}, {0, 2, 3}}, "edges"),
                req("prim", 4, new int[][] {{0, 1, 4}, {1, 2, 2}, {0, 2, 3}}, "edges"),
                req("scc", 4, new int[][] {{0, 1}, {1, 0}, {1, 2}, {0, 2}, {2, 3}}, "edges"),
                req("topo", 4, new int[][] {{0, 2}, {1, 2}, {2, 3}}, "edges"),
                dagReq(5, 0, new int[][] {{0, 1, 3}, {0, 2, 8}, {1, 2, -7}, {2, 3, 4}}),
                // bigger, different n in between: arrays from a previous request must not leak
                req("dsu", 7, new int[][] {{5, 6}}, "operations"),
                req("kruskal", 0, new int[0][], "edges"),
                req("scc", 1, new int[0][], "edges"),
                dagReq(1, 0, new int[0][]),
        };
        List<String> first = new ArrayList<>();
        for (String b : batch) {
            first.add(Json.write(solve(b)));
        }
        boolean same = true;
        for (int pass = 0; pass < 3; pass++) {
            for (int i = batch.length - 1; i >= 0; i--) { // also in a different order
                same &= first.get(i).equals(Json.write(solve(batch[i])));
            }
        }
        check(same, "state reset: repeated and reordered requests in one process give identical results");
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        long start = System.nanoTime();
        System.out.println("validator self-tests");
        validatorSelfTests();
        System.out.println("fixed edge cases");
        edgeCases();
        System.out.println("route reconstruction");
        routeTests();
        System.out.println("randomised differential tests");
        randomSmallTests();
        System.out.println("state reset");
        stateResetTest();
        System.out.println("large and deep inputs");
        largeTests();
        System.out.printf("%d checks passed, %d failed (%.1f s)%n", passed, failed, (System.nanoTime() - start) / 1e9);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
