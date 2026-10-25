import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Graph algorithms for Assignment 3: DSU, Kruskal, Prim, Kosaraju SCC,
 * DFS topological order and shortest paths in a DAG (+ route reconstruction).
 *
 * <p>Every public task method is a pure function of its request: all state
 * (arrays, heaps, DSU) is created inside the call, so nothing leaks between
 * requests handled by the same process. All DFS traversals use an explicit
 * stack with a per-vertex "resume position", so deep graphs (100 000-vertex
 * chains) cannot overflow the call stack and every adjacency list is scanned
 * exactly once.
 */
public final class Algorithms {
    private Algorithms() {
    }

    /** Shifts weights from [-1e9, 1e9] into [0, 2e9] so they can be packed into sort keys. */
    private static final long WEIGHT_OFFSET = 1_000_000_000L;

    // ------------------------------------------------------------------
    // Shared building blocks
    // ------------------------------------------------------------------

    /** Disjoint-set union: path compression (iterative) + union by size. */
    static final class Dsu {
        private final int[] parent;
        private final int[] size;
        private int components;
        private int largest;

        Dsu(int n) {
            parent = new int[n];
            size = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
                size[i] = 1;
            }
            components = n;
            largest = n > 0 ? 1 : 0;
        }

        /** Returns the representative of x and flattens the path behind it. */
        int find(int x) {
            int root = x;
            while (parent[root] != root) {
                root = parent[root];
            }
            while (parent[x] != root) { // second pass: path compression
                int next = parent[x];
                parent[x] = root;
                x = next;
            }
            return root;
        }

        /** Merges the sets of a and b; returns false if they were already joined. */
        boolean union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra == rb) {
                return false;
            }
            if (size[ra] < size[rb]) { // attach the smaller tree below the larger one
                int tmp = ra;
                ra = rb;
                rb = tmp;
            }
            parent[rb] = ra;
            size[ra] += size[rb];
            components--;
            if (size[ra] > largest) {
                largest = size[ra];
            }
            return true;
        }

        int components() {
            return components;
        }

        int largest() {
            return largest;
        }
    }

    /**
     * Compressed adjacency lists (CSR). Arc k of vertex v lives at
     * {@code start[v] <= k < start[v + 1]}; {@code to[k]} is its head and
     * {@code id[k]} is the ORIGINAL position of the edge in the input list.
     */
    static final class Csr {
        final int[] start;
        final int[] to;
        final int[] id;

        private Csr(int[] start, int[] to, int[] id) {
            this.start = start;
            this.to = to;
            this.id = id;
        }

        /** Directed graph; {@code reverse=true} gives the transposed graph. Self-loops are kept. */
        static Csr directed(int n, int[][] edges, boolean reverse) {
            int[] start = new int[n + 1];
            for (int[] e : edges) {
                start[(reverse ? e[1] : e[0]) + 1]++;
            }
            for (int v = 0; v < n; v++) {
                start[v + 1] += start[v];
            }
            int[] fill = Arrays.copyOf(start, n);
            int[] to = new int[edges.length];
            int[] id = new int[edges.length];
            for (int i = 0; i < edges.length; i++) {
                int from = reverse ? edges[i][1] : edges[i][0];
                int head = reverse ? edges[i][0] : edges[i][1];
                int k = fill[from]++;
                to[k] = head;
                id[k] = i;
            }
            return new Csr(start, to, id);
        }

        /** Undirected graph: each non-loop edge is stored as two arcs with the same edge id. */
        static Csr undirected(int n, int[][] edges) {
            int[] start = new int[n + 1];
            int arcs = 0;
            for (int[] e : edges) {
                if (e[0] != e[1]) {
                    start[e[0] + 1]++;
                    start[e[1] + 1]++;
                    arcs += 2;
                }
            }
            for (int v = 0; v < n; v++) {
                start[v + 1] += start[v];
            }
            int[] fill = Arrays.copyOf(start, n);
            int[] to = new int[arcs];
            int[] id = new int[arcs];
            for (int i = 0; i < edges.length; i++) {
                int u = edges[i][0];
                int v = edges[i][1];
                if (u == v) {
                    continue;
                }
                int k = fill[u]++;
                to[k] = v;
                id[k] = i;
                k = fill[v]++;
                to[k] = u;
                id[k] = i;
            }
            return new Csr(start, to, id);
        }
    }

    /** Binary min-heap of primitive longs (no boxing). Capacity is fixed at construction. */
    static final class LongHeap {
        private final long[] a;
        private int size;

        LongHeap(int capacity) {
            a = new long[Math.max(capacity, 1)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(long value) {
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (a[p] <= value) {
                    break;
                }
                a[i] = a[p];
                i = p;
            }
            a[i] = value;
        }

        long pop() {
            long top = a[0];
            long last = a[--size];
            int i = 0;
            int half = size >>> 1;
            while (i < half) {
                int child = 2 * i + 1;
                if (child + 1 < size && a[child + 1] < a[child]) {
                    child++;
                }
                if (a[child] >= last) {
                    break;
                }
                a[i] = a[child];
                i = child;
            }
            if (size > 0) {
                a[i] = last;
            }
            return top;
        }
    }

    /** Packs (weight, index) into one sortable long: weight first, then index. */
    private static long key(long weight, int index) {
        return ((weight + WEIGHT_OFFSET) << 32) | index;
    }

    private static int keyIndex(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------
    // 1. DSU
    // ------------------------------------------------------------------

    /** DSU with path compression and union by size; returns states. */
    static Map<String, Object> dsu(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] operations = Input.operations(input);

        Dsu dsu = new Dsu(n);
        int[][] states = new int[operations.length][];
        for (int i = 0; i < operations.length; i++) {
            dsu.union(operations[i][0], operations[i][1]); // repeated / self unions change nothing
            states[i] = new int[] {dsu.components(), dsu.largest()};
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("states", states);
        return result;
    }

    // ------------------------------------------------------------------
    // 2. Kruskal
    // ------------------------------------------------------------------

    /** Kruskal: sort edges by weight, accept an edge iff it joins two different DSU sets. */
    static Map<String, Object> kruskal(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);
        int m = edges.length;

        // Sorting packed (weight, original id) keys keeps the original ids with the edges.
        long[] keys = new long[m];
        for (int i = 0; i < m; i++) {
            keys[i] = key(edges[i][2], i);
        }
        Arrays.sort(keys);

        Dsu dsu = new Dsu(n);
        int[] chosen = new int[Math.max(n - 1, 0)];
        int count = 0;
        long cost = 0;
        for (long k : keys) {
            int id = keyIndex(k);
            if (dsu.union(edges[id][0], edges[id][1])) { // self-loops fail here: find(u) == find(u)
                chosen[count++] = id;
                cost += edges[id][2];
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cost", cost);
        result.put("edge_ids", Arrays.copyOf(chosen, count));
        result.put("components", dsu.components());
        return result;
    }

    // ------------------------------------------------------------------
    // 3. Prim
    // ------------------------------------------------------------------

    /** Prim with a lazy priority queue; restarts from every still-unvisited vertex. */
    static Map<String, Object> prim(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);

        Csr g = Csr.undirected(n, edges); // loops are never useful, so they are not stored
        boolean[] visited = new boolean[n];
        LongHeap heap = new LongHeap(g.to.length); // every arc is pushed at most once
        int[] chosen = new int[Math.max(n - 1, 0)];
        int count = 0;
        long cost = 0;
        int components = 0;

        for (int root = 0; root < n; root++) {
            if (visited[root]) {
                continue;
            }
            components++;
            visited[root] = true;
            pushArcs(g, edges, heap, visited, root);
            while (!heap.isEmpty()) {
                long top = heap.pop();
                int arc = keyIndex(top);
                int v = g.to[arc];
                if (visited[v]) { // stale entry: v was reached by a cheaper edge meanwhile
                    continue;
                }
                visited[v] = true;
                chosen[count++] = g.id[arc];
                cost += edges[g.id[arc]][2];
                pushArcs(g, edges, heap, visited, v);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cost", cost);
        result.put("edge_ids", Arrays.copyOf(chosen, count));
        result.put("components", components);
        return result;
    }

    /** Pushes the arcs of v that lead to unvisited vertices; a key is (weight, arc index). */
    private static void pushArcs(Csr g, int[][] edges, LongHeap heap, boolean[] visited, int v) {
        for (int k = g.start[v]; k < g.start[v + 1]; k++) {
            if (!visited[g.to[k]]) {
                heap.push(key(edges[g.id[k]][2], k));
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. Kosaraju SCC
    // ------------------------------------------------------------------

    /** Kosaraju: DFS on G for finish order, then DFS on G^T in decreasing finish order. */
    static Map<String, Object> scc(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);

        Csr forward = Csr.directed(n, edges, false);
        Csr backward = Csr.directed(n, edges, true);

        // Pass 1: iterative DFS on G, recording vertices by finish time.
        int[] finish = new int[n];
        int finished = 0;
        boolean[] seen = new boolean[n];
        int[] next = Arrays.copyOf(forward.start, n); // resume position inside each adjacency list
        int[] stack = new int[n];
        for (int root = 0; root < n; root++) {
            if (seen[root]) {
                continue;
            }
            int top = 0;
            stack[top++] = root;
            seen[root] = true;
            while (top > 0) {
                int v = stack[top - 1];
                if (next[v] < forward.start[v + 1]) {
                    int w = forward.to[next[v]++];
                    if (!seen[w]) {
                        seen[w] = true;
                        stack[top++] = w;
                    }
                } else {
                    top--;
                    finish[finished++] = v;
                }
            }
        }

        // Pass 2: DFS on the transposed graph; each tree is exactly one SCC.
        int[] component = new int[n];
        Arrays.fill(component, -1);
        int labels = 0;
        for (int i = n - 1; i >= 0; i--) {
            int root = finish[i];
            if (component[root] != -1) {
                continue;
            }
            int label = labels++;
            int top = 0;
            stack[top++] = root;
            component[root] = label;
            while (top > 0) {
                int v = stack[--top];
                for (int k = backward.start[v]; k < backward.start[v + 1]; k++) {
                    int w = backward.to[k];
                    if (component[w] == -1) {
                        component[w] = label;
                        stack[top++] = w;
                    }
                }
            }
        }

        // Condensation: one pair per distinct (labelU, labelV) with labelU != labelV.
        Set<Long> seenPairs = new HashSet<>(Math.max(16, edges.length * 2));
        int[][] buffer = new int[edges.length][];
        int pairs = 0;
        for (int[] e : edges) {
            int cu = component[e[0]];
            int cv = component[e[1]];
            if (cu != cv && seenPairs.add((long) cu * n + cv)) {
                buffer[pairs++] = new int[] {cu, cv};
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("component", component);
        result.put("condensation", Arrays.copyOf(buffer, pairs));
        return result;
    }

    // ------------------------------------------------------------------
    // 5. Topological order via three-state DFS
    // ------------------------------------------------------------------

    /**
     * Three-state iterative DFS (0 = unvisited, 1 = active, 2 = finished) over all components.
     * Returns the vertices in reverse finish order, or {@code null} if a directed cycle exists
     * (an arc into an ACTIVE vertex; self-loops are such arcs).
     */
    static int[] topologicalOrder(int n, Csr g) {
        byte[] state = new byte[n];
        int[] next = Arrays.copyOf(g.start, n);
        int[] stack = new int[n];
        int[] order = new int[n];
        int placed = n; // fill from the back = reversed finish order

        for (int root = 0; root < n; root++) {
            if (state[root] != 0) {
                continue;
            }
            int top = 0;
            stack[top++] = root;
            state[root] = 1;
            while (top > 0) {
                int v = stack[top - 1];
                if (next[v] < g.start[v + 1]) {
                    int w = g.to[next[v]++];
                    if (state[w] == 1) {
                        return null; // back edge -> directed cycle
                    }
                    if (state[w] == 0) {
                        state[w] = 1;
                        stack[top++] = w;
                    } // state 2: already finished, a forward/cross edge, no cycle
                } else {
                    state[v] = 2;
                    top--;
                    order[--placed] = v;
                }
            }
        }
        return order;
    }

    /** Three-state DFS and reverse finish order; returns acyclic and order. */
    static Map<String, Object> topo(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);

        int[] order = topologicalOrder(n, Csr.directed(n, edges, false));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acyclic", order != null);
        result.put("order", order != null ? order : new int[0]);
        return result;
    }

    // ------------------------------------------------------------------
    // 6. Shortest paths in a DAG + route reconstruction
    // ------------------------------------------------------------------

    /** Whole-graph cycle check, then relaxation in topological order; returns acyclic, distance, parent_edge. */
    static Map<String, Object> dag(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int source = Input.source(input);
        int[][] edges = Input.edges(input);

        Csr g = Csr.directed(n, edges, false);
        int[] order = topologicalOrder(n, g); // checks EVERY component, also those unreachable from source

        Map<String, Object> result = new LinkedHashMap<>();
        if (order == null) {
            result.put("acyclic", false);
            result.put("distance", new Long[0]);
            result.put("parent_edge", new int[0]);
            return result;
        }

        long[] dist = new long[n];
        boolean[] reached = new boolean[n];
        int[] parentEdge = new int[n];
        Arrays.fill(parentEdge, -1);
        reached[source] = true; // dist[source] = 0

        for (int v : order) {
            if (!reached[v]) {
                continue; // never relax out of an unreachable vertex
            }
            for (int k = g.start[v]; k < g.start[v + 1]; k++) {
                int w = g.to[k];
                long candidate = dist[v] + edges[g.id[k]][2];
                if (!reached[w] || candidate < dist[w]) {
                    reached[w] = true;
                    dist[w] = candidate;
                    parentEdge[w] = g.id[k];
                }
            }
        }

        Long[] distance = new Long[n];
        for (int v = 0; v < n; v++) {
            distance[v] = reached[v] ? Long.valueOf(dist[v]) : null; // null = unreachable
        }
        result.put("acyclic", true);
        result.put("distance", distance);
        result.put("parent_edge", parentEdge);
        return result;
    }

    /**
     * Route reconstruction helper (not a JSON task).
     *
     * <p>Follows {@code parentEdge} backwards from {@code target} to {@code source} and returns the
     * vertices in travel order {@code source ... target}. For {@code target == source} the result is
     * {@code [source]}. For an UNREACHABLE target the result is an EMPTY array (a real route always
     * has at least one vertex, so the empty array cannot be confused with a route).
     * Runs in O(L) time and memory for a route of L vertices.
     *
     * @throws IllegalArgumentException if parentEdge is not a valid shortest-path tree (wrong head
     *                                  of an edge, or a loop that never reaches the source)
     */
    public static int[] reconstructRoute(int[][] edges, int[] parentEdge, int source, int target) {
        int[] edgeIds = reconstructRouteEdges(edges, parentEdge, source, target);
        if (edgeIds == null) {
            return new int[0];
        }
        int[] route = new int[edgeIds.length + 1];
        route[0] = source;
        for (int i = 0; i < edgeIds.length; i++) {
            route[i + 1] = edges[edgeIds[i]][1];
        }
        return route;
    }

    /**
     * Same walk as {@link #reconstructRoute}, but returns the edge IDs in travel order
     * (empty for target == source) or {@code null} when the target is unreachable.
     */
    public static int[] reconstructRouteEdges(int[][] edges, int[] parentEdge, int source, int target) {
        int count = 0;
        int v = target;
        while (v != source) {
            int e = parentEdge[v];
            if (e < 0) {
                return null; // no incoming shortest-path edge: unreachable from the source
            }
            if (edges[e][1] != v) {
                throw new IllegalArgumentException("parent edge " + e + " does not end in vertex " + v);
            }
            v = edges[e][0];
            if (++count > parentEdge.length) {
                throw new IllegalArgumentException("parent edges contain a cycle");
            }
        }
        int[] ids = new int[count];
        v = target;
        for (int i = count - 1; i >= 0; i--) {
            ids[i] = parentEdge[v];
            v = edges[ids[i]][0];
        }
        return ids;
    }
}
