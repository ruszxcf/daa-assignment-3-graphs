"""Writes tests/my_cases.jsonl (inputs), tests/expected.jsonl (hand-derived expected answers)
and tests/CASES.md (what each case checks and why the expected answer is right).

The expected answers below were worked out BY HAND from the assignment text, not copied from the
program output. For ambiguous tasks the expected record states only what is unique:

  dsu              exact "states"
  kruskal / prim   "cost", "components" (any minimum spanning forest is accepted)
  scc              "groups" (the SCCs as vertex lists) and "condensation" written with the smallest
                   vertex of each SCC as its name, e.g. [0, 2] = edge from SCC{0,..} to SCC{2,..}
  topo             "acyclic" (the order is validated by meaning)
  dag              "acyclic" and exact "distance" (parent edges are validated by meaning)

Run from the repository root:  python tests/make_cases.py
"""
import json
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
B = 1_000_000_000

CASES = []


def case(name, request, expected, why):
    CASES.append({"name": name, "input": request, "expected": expected, "why": why})


# ---------------------------------------------------------------- DSU
case("dsu: assignment example",
     {"task": "dsu", "n": 4, "operations": [[0, 1], [1, 2], [0, 2], [3, 3]]},
     {"states": [[3, 2], [2, 3], [2, 3], [2, 3]]},
     "Union 0-1: 3 components, largest 2. 1-2 joins vertex 2: 2 components, largest 3. 0-2 is already "
     "connected and 3-3 is a self-union, so nothing changes.")
case("dsu: empty graph", {"task": "dsu", "n": 0, "operations": []}, {"states": []},
     "n=0 has no operations and therefore no states.")
case("dsu: no operations", {"task": "dsu", "n": 5, "operations": []}, {"states": []},
     "The initial state is not part of the output, so q=0 gives an empty list.")
case("dsu: single vertex self-unions",
     {"task": "dsu", "n": 1, "operations": [[0, 0], [0, 0]]}, {"states": [[1, 1], [1, 1]]},
     "One vertex: 1 component of size 1 before and after every self-union.")
case("dsu: repeated and reversed unions",
     {"task": "dsu", "n": 3, "operations": [[0, 1], [0, 1], [1, 0]]},
     {"states": [[2, 2], [2, 2], [2, 2]]},
     "The first union merges 0 and 1; repeating it (in either direction) must not change the partition.")
case("dsu: two big components merge",
     {"task": "dsu", "n": 6, "operations": [[0, 1], [2, 3], [4, 5], [1, 2], [3, 4]]},
     {"states": [[5, 2], [4, 2], [3, 2], [2, 4], [1, 6]]},
     "Three pairs (6->3 components, largest 2). 1-2 merges {0,1} and {2,3} (2 components, largest 4); "
     "3-4 merges that with {4,5} (1 component, largest 6). Checks that size is added, not rank.")

# ---------------------------------------------------------------- Kruskal and Prim (same graphs)
MST = []


def mst(name, n, edges, cost, components, why):
    MST.append((name, n, edges, cost, components, why))


mst("assignment example", 4, [[0, 1, 4], [1, 2, 2], [0, 2, 3]], 5, 2,
    "Edges of weight 2 and 3 connect {0,1,2}; vertex 3 is isolated, so 2 components and cost 5.")
mst("empty graph", 0, [], 0, 0, "n=0: cost 0, no edges, 0 components.")
mst("no edges", 3, [], 0, 3, "Three isolated vertices: cost 0, 3 components.")
mst("loops, parallel edges, negative weights", 3,
    [[0, 0, -5], [0, 1, 2], [0, 1, -1], [1, 2, 4], [1, 2, 4]], 3, 1,
    "The -5 loop must never be chosen. Between 0 and 1 the cheaper parallel edge (-1) wins; between 1 "
    "and 2 either weight-4 edge. Cost -1+4=3.")
mst("tied optimum (triangle)", 3, [[0, 1, 1], [1, 2, 1], [0, 2, 1]], 2, 1,
    "Any two of the three equal edges are optimal: cost 2. Different valid ids are accepted.")
mst("sum beyond 32 bit", 4, [[0, 1, B], [1, 2, B], [2, 3, B], [0, 3, B]], 3 * B, 1,
    "A 4-cycle of weight 1e9 needs 3 edges: 3,000,000,000 > 2^31-1.")
mst("sum below -32 bit", 3, [[0, 1, -B], [1, 2, -B], [0, 2, -B]], -2 * B, 1,
    "Two of three edges of weight -1e9: -2,000,000,000 < -2^31.")
mst("two components + isolated + loop component", 7,
    [[0, 1, 5], [1, 2, 1], [0, 2, 2], [3, 4, 7], [5, 5, 1]], 10, 4,
    "{0,1,2}: take 1 and 2 (cost 3); {3,4}: 7; vertex 5 only has a loop; vertex 6 is isolated. "
    "Components: {0,1,2},{3,4},{5},{6}=4, cost 10. Forces Prim to restart in every component.")
mst("heavy chords never replace the light path", 4,
    [[0, 1, -3], [1, 2, -3], [2, 3, -3], [0, 3, 100], [0, 2, 50]], -9, 1,
    "The path 0-1-2-3 (-9) is spanning and every other edge is heavier than the path edges it replaces.")

for name, n, edges, cost, comps, why in MST:
    for task in ("kruskal", "prim"):
        case(f"{task}: {name}", {"task": task, "n": n, "edges": edges},
             {"cost": cost, "components": comps}, why)

# ---------------------------------------------------------------- SCC
case("scc: assignment example",
     {"task": "scc", "n": 4, "edges": [[0, 1], [1, 0], [1, 2], [0, 2], [2, 3]]},
     {"groups": [[0, 1], [2], [3]], "condensation": [[0, 2], [2, 3]]},
     "0 and 1 reach each other; 2 and 3 are single SCCs. Edges 1->2 and 0->2 both give the pair "
     "{0,1}->{2}, which must appear once.")
case("scc: empty graph", {"task": "scc", "n": 0, "edges": []}, {"groups": [], "condensation": []},
     "n=0: both outputs empty.")
case("scc: isolated vertices", {"task": "scc", "n": 3, "edges": []},
     {"groups": [[0], [1], [2]], "condensation": []}, "No edges: every vertex is its own SCC.")
case("scc: duplicate edges and loops",
     {"task": "scc", "n": 3, "edges": [[0, 1], [1, 0], [1, 2], [1, 2], [0, 2], [2, 2]]},
     {"groups": [[0, 1], [2]], "condensation": [[0, 2]]},
     "Three edges lead from {0,1} to {2}; the pair appears exactly once. The loop 2->2 is internal.")
case("scc: one big cycle",
     {"task": "scc", "n": 4, "edges": [[0, 1], [1, 2], [2, 3], [3, 0]]},
     {"groups": [[0, 1, 2, 3]], "condensation": []}, "A directed 4-cycle is a single SCC.")
case("scc: two cycles joined by a duplicated link",
     {"task": "scc", "n": 6, "edges": [[0, 1], [1, 0], [2, 3], [3, 2], [1, 2], [0, 3], [3, 4], [4, 5]]},
     {"groups": [[0, 1], [2, 3], [4], [5]], "condensation": [[0, 2], [2, 4], [4, 5]]},
     "SCCs {0,1},{2,3},{4},{5}. Edges 1->2 and 0->3 both give {0,1}->{2,3}; then {2,3}->{4}->{5}.")
case("scc: edges listed against the DFS direction",
     {"task": "scc", "n": 5, "edges": [[4, 3], [3, 2], [2, 1], [1, 0], [0, 4]]},
     {"groups": [[0, 1, 2, 3, 4]], "condensation": []},
     "0->4->3->2->1->0 is one cycle, whatever the order of the vertices and of the edge list.")

# ---------------------------------------------------------------- topological order
case("topo: assignment example",
     {"task": "topo", "n": 4, "edges": [[0, 2], [1, 2], [2, 3]]}, {"acyclic": True},
     "A DAG; 0 and 1 must precede 2, 2 precedes 3 (orders 0,1,2,3 and 1,0,2,3 are both valid).")
case("topo: empty graph", {"task": "topo", "n": 0, "edges": []}, {"acyclic": True},
     "n=0: acyclic with an empty order.")
case("topo: self-loop", {"task": "topo", "n": 3, "edges": [[0, 1], [2, 2]]}, {"acyclic": False},
     "A self-loop is a directed cycle: acyclic=false and an empty order.")
case("topo: cycle in a later component",
     {"task": "topo", "n": 5, "edges": [[0, 1], [2, 3], [3, 4], [4, 2]]}, {"acyclic": False},
     "The component {0,1} is fine, but 2->3->4->2 is a cycle; every component must be checked.")
case("topo: diamond (edge into a finished vertex)",
     {"task": "topo", "n": 4, "edges": [[0, 1], [0, 2], [1, 3], [2, 3]]}, {"acyclic": True},
     "Vertex 3 is reached twice; the second arrival hits a FINISHED vertex, which is not a cycle.")
case("topo: parallel edges and isolated vertex",
     {"task": "topo", "n": 3, "edges": [[0, 1], [0, 1]]}, {"acyclic": True},
     "Parallel edges are not a cycle. Vertex 2 must still appear in the order.")

# ---------------------------------------------------------------- DAG shortest paths
case("dag: assignment example",
     {"task": "dag", "n": 5, "source": 0, "edges": [[0, 1, 3], [0, 2, 8], [1, 2, -7], [2, 3, 4]]},
     {"acyclic": True, "distance": [0, 3, -4, 0, None]},
     "0->1->2 costs 3-7=-4 (better than 8), then +4 gives 0 for vertex 3. Vertex 4 is unreachable (null).")
case("dag: cycle unreachable from the source",
     {"task": "dag", "n": 4, "source": 0, "edges": [[0, 1, 5], [2, 3, 1], [3, 2, 1]]},
     {"acyclic": False, "distance": []},
     "The cycle 2<->3 cannot be reached from 0, but the whole graph is not acyclic, so acyclic=false.")
case("dag: distance -1 is not 'unreachable'",
     {"task": "dag", "n": 3, "source": 0, "edges": [[0, 1, -1]]},
     {"acyclic": True, "distance": [0, -1, None]},
     "Vertex 1 has the real distance -1; vertex 2 has no path and must be null.")
case("dag: negative edge out of an unreachable vertex",
     {"task": "dag", "n": 3, "source": 0, "edges": [[1, 2, -100], [0, 2, 7]]},
     {"acyclic": True, "distance": [0, None, 7]},
     "Vertex 1 is unreachable, so its -100 edge must not be relaxed: distance of 2 stays 7.")
case("dag: source in the middle",
     {"task": "dag", "n": 4, "source": 1, "edges": [[0, 1, 4], [1, 2, 1], [2, 3, 1]]},
     {"acyclic": True, "distance": [None, 0, 1, 2]},
     "Starting at 1: vertex 0 lies before the source and is unreachable.")
case("dag: equal shortest paths",
     {"task": "dag", "n": 4, "source": 0, "edges": [[0, 1, 1], [1, 3, 1], [0, 2, 1], [2, 3, 1]]},
     {"acyclic": True, "distance": [0, 1, 1, 2]},
     "Vertex 3 can be reached via 1 or via 2 with cost 2; either parent edge (1 or 3) is accepted.")
case("dag: parallel edges",
     {"task": "dag", "n": 2, "source": 0, "edges": [[0, 1, 5], [0, 1, 3], [0, 1, 3]]},
     {"acyclic": True, "distance": [0, 3]},
     "The cheapest parallel edges have weight 3 (ids 1 and 2); parent_edge may be 1 or 2, never 0.")
case("dag: 64-bit distances",
     {"task": "dag", "n": 4, "source": 0, "edges": [[0, 1, -B], [1, 2, -B], [2, 3, -B]]},
     {"acyclic": True, "distance": [0, -B, -2 * B, -3 * B]},
     "Three edges of -1e9: -3,000,000,000 does not fit into 32 bits.")
case("dag: single vertex", {"task": "dag", "n": 1, "source": 0, "edges": []},
     {"acyclic": True, "distance": [0]}, "Only the source: distance 0, parent_edge -1.")
case("dag: self-loop on the source",
     {"task": "dag", "n": 1, "source": 0, "edges": [[0, 0, -4]]},
     {"acyclic": False, "distance": []},
     "A self-loop is a cycle even though its weight is negative and it touches the source.")
case("dag: edges listed in reverse topological order",
     {"task": "dag", "n": 4, "source": 3, "edges": [[1, 0, 1], [2, 1, 1], [3, 2, 1]]},
     {"acyclic": True, "distance": [3, 2, 1, 0]},
     "Topological order is 3,2,1,0 and the edge list is in the opposite order, so a left-to-right scan "
     "of the edge list would fail; dist = 0,1,2,3 along 3->2->1->0.")

# ---------------------------------------------------------------- write files
with open(HERE / "my_cases.jsonl", "w", encoding="utf-8") as f_in, \
        open(HERE / "expected.jsonl", "w", encoding="utf-8") as f_out:
    for c in CASES:
        f_in.write(json.dumps(c["input"], separators=(",", ":")) + "\n")
        f_out.write(json.dumps({"name": c["name"], **c["expected"]}, separators=(",", ":")) + "\n")

with open(HERE / "CASES.md", "w", encoding="utf-8") as f:
    f.write("# Hand-written test cases\n\n")
    f.write("Generated by `python tests/make_cases.py`. Line *i* of `my_cases.jsonl` (input) belongs to line *i* of\n")
    f.write("`expected.jsonl` (hand-derived expected answer, see the header of `make_cases.py` for the format).\n")
    f.write("`python tests/check_cases.py` runs the program, stores the answers in `actual.jsonl` and checks every\n")
    f.write("answer by meaning (valid forest + minimum cost, exact SCC partition and condensation, order of\n")
    f.write("dependencies, shortest distances and parent edges).\n\n")
    f.write("| # | Case | Why the expected answer is right |\n|---:|---|---|\n")
    for i, c in enumerate(CASES, 1):
        f.write(f"| {i} | {c['name']} | {c['why']} |\n")
print(f"wrote {len(CASES)} cases")
