# Assignment 3 – Analysis

Ruslan Moldakan, SE-2520. Language: **Java 17**. All algorithms are in `src/Java/Algorithms.java`.

## 1. Commands

| Purpose | Command |
|---|---|
| Build, launch check | `python tools/run.py --setup` |
| Public checks | `python tools/run.py --check` |
| Run my own requests | `python tools/run.py --input tests/my_cases.jsonl` |
| Compile / run by hand | `javac --release 17 -d .build/Java src/Java/*.java` / `java -Xmx384m -cp .build/Java Main < requests.jsonl` |
| All my tests | `python tests/run_tests.py` (`--large` max-size inputs, `--mutants` deliberate bugs, `--bench` benchmark) |

## 2. Design notes

* **State reset:** every method creates all its working data (DSU, adjacency arrays, heap, visited arrays) inside the call
  and uses no `static` state, so one request cannot affect the next.
* **Deep DFS:** all DFS use an explicit stack plus a per-vertex resume pointer `next[v]`. Each adjacency list is read once
  (`O(n+m)`) and a 100 000-vertex chain cannot overflow the call stack.
* **Edge IDs** are kept: Kruskal sorts packed keys `((w+10^9)<<32)|id`; adjacency arrays (CSR) store the original id next to every arc.
* **64-bit:** costs/distances are `long` (sums up to about 10^14). Unreachable distance is `null`, never `-1`.

## 3. Methods

**DSU.** *Steps:* `parent[i]=i`, `size[i]=1`; for each `[u,v]` find both roots (iterative, path compression); if different, attach the
smaller tree to the larger, add sizes, `components--`, `largest=max(largest,size)`; append `[components,largest]`.
*Invariant:* each tree is one component, `size[root]` is its size, `components` = number of roots. *Correctness:* same root ⇔ connected;
repeated and self unions have equal roots and change nothing. *Edge cases:* `n=0`, `q=0`, self/repeated/reversed unions.
*Complexity:* `O(n + q·α(n))` time, `O(n+q)` memory.

**Kruskal.** *Steps:* sort edges by weight; take an edge iff `find(u)!=find(v)`; `components` = DSU count.
*Invariant:* after `i` edges the accepted ones are a minimum spanning forest of those `i` edges. *Correctness:* a skipped edge closes a cycle
in which it is a heaviest edge (cycle property); an accepted edge is the lightest one leaving a tree (cut property). Loops are never taken;
exactly `n−c` edges are chosen. *Edge cases:* `n=0`, no edges, isolated vertices, loops, parallel edges, ties, negative weights, sums above 2^31.
*Complexity:* `O(n + m log m)` time, `O(n+m)` memory.

**Prim.** *Steps:* CSR adjacency (loops skipped). For every unvisited vertex (restart ⇒ one pass per component, `components++`):
mark it, push its arcs into a primitive-`long` binary heap `(weight, arc)`; pop the cheapest arc, skip it if its head is visited (stale entry),
otherwise visit the head, record the edge id and push its arcs. *Invariant:* chosen edges form an MST of the visited set and the heap holds
all arcs leaving it. *Correctness:* the cheapest non-stale arc is a light edge across the cut (cut property); an empty heap means the component is complete.
*Edge cases:* as Kruskal, plus several components and a vertex with only a loop. *Complexity:* `O(n + m log m)` time (≤ 2m pushes), `O(n+m)` memory.

**Kosaraju SCC.** *Steps:* DFS on `G` recording finish order; build `Gᵀ`; for vertices in decreasing finish time, each unlabelled one starts a DFS on `Gᵀ`
that labels one SCC; condensation = distinct pairs `(label[u],label[v])` with different labels, de-duplicated with a `HashSet`.
*Invariant:* the next root has the largest finish time among unlabelled vertices. *Correctness:* if `C→C'` then the maximum finish time in `C` is larger,
so that root's SCC is a source in `G`, a sink in `Gᵀ`, and the DFS on `Gᵀ` reaches exactly this SCC. *Edge cases:* `n=0`, isolated vertices,
duplicate edges (pair appears once), loops, a 100 000-vertex chain and ring. *Complexity:* `O(n+m)` expected time, `O(n+m)` memory.

**Topological order (DFS).** *Steps:* states 0 unvisited / 1 active / 2 finished; an arc into an active vertex means a cycle
(`acyclic=false, order=[]`); an arc into a finished vertex is fine; the order is the reversed finish order. All components are started.
*Invariant:* active vertices are the DFS stack path. *Correctness:* a back arc closes a cycle; if no cycle exists, every arc `u→v` has
`finish(v) < finish(u)`. *Edge cases:* `n=0`, self-loop, cycle in a later component, diamond, parallel edges, deep chains. *Complexity:* `O(n+m)` time and memory.

**DAG shortest paths.** *Steps:* run the same cycle check on the **whole** graph (a cycle unreachable from the source still gives `acyclic=false`);
`dist[source]=0`; in topological order skip unreached vertices, relax outgoing arcs, store the **edge id** in `parent_edge`; unreached ⇒ `null`, `-1`.
*Invariant:* when a vertex is taken from the order its distance is final. *Correctness:* all arcs into it come from earlier vertices (induction);
negative weights are harmless. *Edge cases:* `n=1`, loops, unreachable cycle, unreachable vertices, real distance `-1`, negative edge out of an unreachable
vertex, ties/parallel edges, sums above 2^31. *Complexity:* `O(n+m)` time and memory.

**Route helper** `reconstructRoute(edges, parentEdge, source, target)` (and `reconstructRouteEdges`): follows `parentEdge` from the target back to the source and reverses.
Source ⇒ `[source]`; **unreachable target ⇒ empty array `[]`** (helper only; JSON distances stay `null`); invalid parent arrays throw. `O(L)` time and memory.
Real calls (`tests/java/RouteDemo.java`, output in `tests/route_demo_output.txt`):

| Case | Edges `[u,v,w]` (id = position) | `parent_edge` | target | route | edge IDs | check |
|---|---|---|---:|---|---|---|
| reachable | `[[0,1,3],[0,2,8],[1,2,-7],[2,3,4]]`, source 0 | `[-1,0,2,3,-1]` | 3 | `[0,1,2,3]` | `[0,2,3]` | directions ok, 3−7+4 = 0 = `distance[3]` |
| source | same | same | 0 | `[0]` | `[]` | total 0 = `distance[0]` |
| unreachable | same | same | 4 | `[]` | `null` | `distance[4]=null` |
| parallel edges | `[[0,1,5],[0,1,5],[0,1,9],[1,2,1]]` | `[-1,0,3]` | 2 | `[0,1,2]` | `[0,3]` | 5+1 = 6 = `distance[2]` (id 1 equally valid) |
| equal paths | `[[0,1,1],[1,3,1],[0,2,1],[2,3,1]]` | `[-1,0,2,3]` | 3 | `[0,2,3]` | `[2,3]` | 1+1 = 2 (`[0,1,3]` also valid) |

## 4. Testing

* `tests/java/AlgorithmTests.java`: 8949 checks – edge cases, route helper (also with the alternative valid parent edges), 1500 random small graphs per task,
  state reset, 100 000-vertex / 250 000-edge inputs and deep chains.
* `tests/my_cases.jsonl` (48 requests), `expected.jsonl` (hand-derived answers), `CASES.md` (why each answer is right), `actual.jsonl` (real answers), `check_cases.py`.
* `tests/check_large.py`: ten maximum-size requests through the real process with `-Xmx384m` (0.3–0.8 s each, 3.7 s as one batch).
* Ambiguous answers (forest ids, SCC labels, order, parent edges) are validated **by meaning** with independent checks: naive union-find and brute force over edge subsets
  (forests), Floyd–Warshall closure and iterative Tarjan (SCC), Bellman–Ford and Dijkstra with potentials (DAG). The validators accept other valid answers and reject wrong ones
  (tested); Kruskal and Prim chose different valid forests in 542 of 1500 random cases.
* `tests/mutation_check.py`: 7 of 7 deliberate bugs are detected.
* Results: public checks 36/36 and state reset pass; Java tests 0 failed; hand-written cases 48/48; max-size checks pass.

## 5. Kruskal vs Prim benchmark

**Graphs:** random multigraphs from `java.util.Random(20261025)`: `u,v = nextInt(n)`, `w = nextInt(2·10^9+1) − 10^9`; both methods get the same graph.
Sizes `(n,m)`: (5 000; 12 500), (25 000; 62 500), (100 000; 250 000). Before timing, costs/components of both are compared and forests are validated.
**Measured:** one call `Algorithms.kruskal(request)` / `prim(request)` on a parsed request (includes the same `Input.edges` conversion for both;
excludes JSON parsing/output, generation and compilation). **Timer:** `System.nanoTime`; 5 warm-up calls, then 9 timed runs per method, alternating, `System.gc()` before each run; median reported.
**Command:** `python tests/run_tests.py --bench` (raw output in `tests/bench/results.txt`). **Environment:** Linux x86_64 cloud container, 2 vCPU Intel Xeon 2.10 GHz, 8 GB RAM,
OpenJDK 21 (compiled with `--release 17`), `-Xmx384m`.

| n | m | components | MSF cost | method | 9 runs (ms) | median (ms) |
|---:|---:|---:|---:|---|---|---:|
| 5000 | 12500 | 36 | -2655363885690 | Kruskal | 4.38, 3.37, 3.57, 2.63, 2.36, 2.01, 1.62, 1.37, 1.42 | 2.36 |
| 5000 | 12500 | 36 | -2655363885690 | Prim | 3.55, 3.68, 3.23, 4.71, 2.04, 2.64, 4.16, 2.01, 2.11 | 3.23 |
| 25000 | 62500 | 154 | -13205370027201 | Kruskal | 11.22, 8.88, 8.67, 10.20, 8.40, 11.62, 10.55, 8.50, 10.88 | 10.20 |
| 25000 | 62500 | 154 | -13205370027201 | Prim | 16.16, 20.48, 16.22, 18.37, 15.25, 15.66, 17.38, 14.26, 21.17 | 16.22 |
| 100000 | 250000 | 704 | -52988343212559 | Kruskal | 49.04, 52.61, 44.90, 46.76, 43.53, 52.57, 53.59, 47.20, 45.63 | 47.20 |
| 100000 | 250000 | 704 | -52988343212559 | Prim | 108.14, 106.89, 97.19, 121.68, 92.27, 107.84, 100.53, 109.78, 110.18 | 107.84 |

**Observations.** Both methods give the same cost and component count on every graph. Kruskal is faster at every size; the ratio Prim/Kruskal is 1.37, 1.59, 2.28.
Kruskal's time per edge stays about 0.16–0.19 µs, Prim's rises from 0.26 to 0.43 µs at the largest size. The `Input.edges` conversion (0.5–8 ms) is the same for both.
A possible reason (not profiled) is that Kruskal sorts a flat `long[]` while Prim does up to `2m` heap operations with poor memory locality on a doubled adjacency structure.
**Asymptotics:** both are `O(n + m log m)`; the bound does not say which is faster, the constants do.
**Limitations:** one sparse random graph family, one shared 2-vCPU VM, Java 21 instead of 17, 9 runs per point; at the smallest size runs last only a few milliseconds and are noisy.
Dense graphs or many equal weights could change the ranking; an indexed heap for Prim was not implemented.

## 6. Sources and AI use

Sources: the assignment text and template repository, and the standard textbook algorithms (Kruskal, Prim, Kosaraju, DFS topological sort, DSU). No code was copied from other repositories.
AI tool: Claude helped write the tests and the analysis, and assisted with the benchmark.
My own verification: I wrote the implementation in Algorithms.java, read the algorithms, ran python tools/run.py --check and python tests/run_tests.py, and traced the examples from the assignment.