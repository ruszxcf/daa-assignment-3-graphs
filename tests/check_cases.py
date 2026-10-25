"""Runs tests/my_cases.jsonl through the selected program and checks every answer by meaning.

    python tests/check_cases.py            # writes tests/actual.jsonl and prints PASS/FAIL per case

The checks are written independently of the solution (naive union-find, brute-force reachability,
Bellman-Ford). For ambiguous answers (forest ids, SCC labels, topological order, parent edges) any
valid answer is accepted and an invalid one is rejected; `--selftest` proves both directions.
"""
import itertools
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import run  # noqa: E402  (the assignment's launcher: build() and execute())

HERE = pathlib.Path(__file__).resolve().parent


def components(n, edges):
    """Component id per vertex (tiny union-find with path halving; independent of the Java code)."""
    p = list(range(n))

    def find(x):
        while p[x] != x:
            p[x] = p[p[x]]
            x = p[x]
        return x

    for e in edges:
        p[find(e[0])] = find(e[1])
    return [find(v) for v in range(n)]


def reach(n, edges):
    r = [[False] * n for _ in range(n)]
    for u, v in (e[:2] for e in edges):
        r[u][v] = True
    for k, i, j in itertools.product(range(n), repeat=3):  # k outermost: Floyd-Warshall closure
        if r[i][k] and r[k][j]:
            r[i][j] = True
    return r


def has_cycle(n, edges):
    r = reach(n, edges)
    return any(r[v][v] for v in range(n))


def check_forest(req, ans, exp):
    n, edges = req["n"], req["edges"]
    ids = ans["edge_ids"]
    comps = len(set(components(n, edges)))
    if ans["components"] != exp["components"] or ans["components"] != comps:
        return f"components {ans['components']} (expected {exp['components']})"
    if len(ids) != n - comps or len(set(ids)) != len(ids):
        return "wrong number of edges or repeated edge id"
    if any(not 0 <= i < len(edges) or edges[i][0] == edges[i][1] for i in ids):
        return "invalid id or self-loop chosen"
    chosen = [edges[i] for i in ids]
    if len(set(components(n, chosen))) != comps:
        return "chosen edges contain a cycle"
    cost = sum(e[2] for e in chosen)
    if cost != ans["cost"] or cost != exp["cost"]:
        return f"cost {ans['cost']} (sum of ids {cost}, expected {exp['cost']})"
    return None


def check_scc(req, ans, exp):
    n, edges = req["n"], req["edges"]
    comp, cond = ans["component"], ans["condensation"]
    groups = {}
    for v, c in enumerate(comp):
        if not isinstance(c, int) or c < 0:
            return "labels must be non-negative integers"
        groups.setdefault(c, []).append(v)
    got_groups = sorted(sorted(g) for g in groups.values())
    if got_groups != sorted(sorted(g) for g in exp["groups"]):
        return f"groups {got_groups}"
    name = {c: min(g) for c, g in groups.items()}  # name each SCC by its smallest vertex
    pairs = [(name[a], name[b]) for a, b in cond if a in name and b in name]
    if len(pairs) != len(cond) or len(set(pairs)) != len(pairs):
        return "condensation has unknown labels or duplicate pairs"
    if sorted(pairs) != sorted(tuple(p) for p in exp["condensation"]):
        return f"condensation {sorted(pairs)}"
    return None


def check_topo(req, ans, exp):
    n, edges = req["n"], req["edges"]
    if ans["acyclic"] != exp["acyclic"] or exp["acyclic"] == has_cycle(n, edges):
        return f"acyclic={ans['acyclic']}"
    order = ans["order"]
    if not ans["acyclic"]:
        return None if order == [] else "order must be empty"
    if sorted(order) != list(range(n)):
        return "order is not a permutation of all vertices"
    pos = {v: i for i, v in enumerate(order)}
    bad = [e for e in edges if pos[e[0]] >= pos[e[1]]]
    return f"edge {bad[0]} points backwards" if bad else None


def check_dag(req, ans, exp):
    n, edges, source = req["n"], req["edges"], req["source"]
    if ans["acyclic"] != exp["acyclic"]:
        return f"acyclic={ans['acyclic']}"
    if not ans["acyclic"]:
        return None if ans["distance"] == [] and ans["parent_edge"] == [] else "arrays must be empty"
    if ans["distance"] != exp["distance"]:
        return f"distance {ans['distance']}"
    # parent edges: right head, on a shortest path, and the chain leads back to the source
    dist, parent = ans["distance"], ans["parent_edge"]
    for v in range(n):
        if v == source or dist[v] is None:
            if parent[v] != -1:
                return f"parent_edge[{v}] must be -1"
            continue
        e = parent[v]
        if not 0 <= e < len(edges) or edges[e][1] != v:
            return f"parent_edge[{v}] does not end in {v}"
        tail = edges[e][0]
        if dist[tail] is None or dist[tail] + edges[e][2] != dist[v]:
            return f"parent edge of {v} is not on a shortest path"
        steps, x = 0, v
        while x != source:
            x = edges[parent[x]][0]
            steps += 1
            if steps > n:
                return "parent edges do not lead to the source"
    return None


def check_dsu(req, ans, exp):
    return None if ans["states"] == exp["states"] else f"states {ans['states']}"


CHECKERS = {"dsu": check_dsu, "kruskal": check_forest, "prim": check_forest,
            "scc": check_scc, "topo": check_topo, "dag": check_dag}


def selftest():
    """The checkers must accept another valid answer and reject corrupted ones."""
    tri = {"task": "kruskal", "n": 3, "edges": [[0, 1, 1], [1, 2, 1], [0, 2, 1]]}
    exp = {"cost": 2, "components": 1}
    ok = [check_forest(tri, {"cost": 2, "components": 1, "edge_ids": ids}, exp) is None
          for ids in ([0, 1], [1, 2], [0, 2])]
    bad = [check_forest(tri, {"cost": c, "components": 1, "edge_ids": ids}, exp) is not None
           for c, ids in ((3, [0, 1]), (2, [0, 0]), (1, [0]), (2, [0, 1, 2]))]
    g = {"task": "scc", "n": 4, "edges": [[0, 1], [1, 0], [1, 2], [0, 2], [2, 3]]}
    e = {"groups": [[0, 1], [2], [3]], "condensation": [[0, 2], [2, 3]]}
    ok.append(check_scc(g, {"component": [5, 5, 7, 9], "condensation": [[5, 7], [7, 9]]}, e) is None)
    bad += [check_scc(g, {"component": [5, 5, 5, 9], "condensation": [[5, 9]]}, e) is not None,
            check_scc(g, {"component": [5, 5, 7, 9], "condensation": [[5, 7]]}, e) is not None,
            check_scc(g, {"component": [5, 5, 7, 9], "condensation": [[5, 7], [5, 7], [7, 9]]}, e) is not None]
    t = {"task": "topo", "n": 4, "edges": [[0, 2], [1, 2], [2, 3]]}
    ok += [check_topo(t, {"acyclic": True, "order": o}, {"acyclic": True}) is None
           for o in ([1, 0, 2, 3], [0, 1, 2, 3])]
    bad += [check_topo(t, {"acyclic": True, "order": o}, {"acyclic": True}) is not None
            for o in ([2, 0, 1, 3], [0, 1, 2], [0, 0, 2, 3])]
    d = {"task": "dag", "n": 4, "source": 0, "edges": [[0, 1, 1], [1, 3, 1], [0, 2, 1], [2, 3, 1]]}
    de = {"acyclic": True, "distance": [0, 1, 1, 2]}
    ok += [check_dag(d, {"acyclic": True, "distance": [0, 1, 1, 2], "parent_edge": [-1, 0, 2, p]}, de) is None
           for p in (1, 3)]
    bad += [check_dag(d, {"acyclic": True, "distance": [0, 1, 1, 2], "parent_edge": [-1, 0, 2, p]}, de) is not None
            for p in (0, 2, -1)]
    assert all(ok), f"checker rejected a valid answer: {ok}"
    assert all(bad), f"checker accepted an invalid answer: {bad}"
    print(f"selftest: {len(ok)} valid answers accepted, {len(bad)} invalid answers rejected")


def main():
    selftest()
    requests = [json.loads(line) for line in (HERE / "my_cases.jsonl").read_text(encoding="utf-8").splitlines()]
    expected = [json.loads(line) for line in (HERE / "expected.jsonl").read_text(encoding="utf-8").splitlines()]
    command = run.build(run.select_language(None))
    answers = run.execute(command, requests)
    with open(HERE / "actual.jsonl", "w", encoding="utf-8") as f:
        for a in answers:
            f.write(json.dumps(a, separators=(",", ":")) + "\n")
    failures = 0
    for i, (req, ans, exp) in enumerate(zip(requests, answers, expected), 1):
        problem = CHECKERS[req["task"]](req, ans, exp)
        failures += problem is not None
        print(("PASS" if problem is None else f"FAIL ({problem})") + f"  #{i} {exp['name']}")
    # state reset: the same requests twice and in reverse order in ONE process must give the same answers
    again = run.execute(command, requests + requests[::-1])
    same = again[:len(requests)] == answers and again[len(requests):][::-1] == answers
    print(("PASS" if same else "FAIL") + "  state reset: repeated + reversed batch in one process gives identical answers")
    failures += not same
    print(f"{len(requests)} cases, {failures} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
