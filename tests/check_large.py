"""Process-level test with inputs at the LIMITS of the assignment (n = 100000, m/q = 250000).

    python tests/check_large.py

The inputs are generated from fixed seeds (nothing is stored), sent through the real program
(build + `java -Xmx384m ...` exactly as tools/run.py does) and checked in Python:
  * Kruskal and Prim must agree on cost/components and both must return a valid forest;
  * deep chains (100000 vertices) must not overflow the stack and must give closed-form answers;
  * 64-bit sums/distances must not overflow;
  * all requests in one process (state reset) and the wall-clock time of the whole batch.
"""
import json
import pathlib
import random
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import run  # noqa: E402

N, M, B = 100_000, 250_000, 1_000_000_000


class Dsu:
    def __init__(self, n):
        self.p = list(range(n))
        self.size = [1] * n
        self.comps, self.largest = n, (1 if n else 0)

    def find(self, x):
        while self.p[x] != x:
            self.p[x] = self.p[self.p[x]]
            x = self.p[x]
        return x

    def union(self, a, b):
        a, b = self.find(a), self.find(b)
        if a == b:
            return False
        if self.size[a] < self.size[b]:
            a, b = b, a
        self.p[b] = a
        self.size[a] += self.size[b]
        self.comps -= 1
        self.largest = max(self.largest, self.size[a])
        return True


def build_requests():
    rnd = random.Random(2026_10_25)
    rand_pairs = lambda n, m: [[rnd.randrange(n), rnd.randrange(n)] for _ in range(m)]
    weighted = [[u, v, rnd.randint(-B, B)] for u, v in rand_pairs(N, M)]
    perm = list(range(N))
    rnd.shuffle(perm)
    dag_pairs = []
    for _ in range(M):
        a, b = sorted(rnd.sample(range(N), 2))
        dag_pairs.append([perm[a], perm[b]])
    dag_weighted = [[u, v, rnd.randint(-B, B)] for u, v in dag_pairs]
    chain = [[i, i + 1] for i in range(N - 1)]
    return {
        "dsu random": {"task": "dsu", "n": N, "operations": rand_pairs(N, M)},
        "dsu chain": {"task": "dsu", "n": N, "operations": chain + [[0, 0]] * (M - len(chain))},
        "kruskal random": {"task": "kruskal", "n": N, "edges": weighted},
        "prim random": {"task": "prim", "n": N, "edges": weighted},
        "scc random": {"task": "scc", "n": N, "edges": rand_pairs(N, M)},
        "scc chain": {"task": "scc", "n": N, "edges": chain},
        "topo chain": {"task": "topo", "n": N, "edges": chain},
        "topo random DAG": {"task": "topo", "n": N, "edges": dag_pairs},
        "dag chain -1e9": {"task": "dag", "n": N, "source": 0, "edges": [[i, i + 1, -B] for i in range(N - 1)]},
        "dag random": {"task": "dag", "n": N, "source": perm[0], "edges": dag_weighted},
    }


def verify(name, req, ans):
    if name.startswith("dsu"):
        d, states = Dsu(N), ans["states"]
        assert len(states) == len(req["operations"]), "wrong number of states"
        for (u, v), s in zip(req["operations"], states):
            d.union(u, v)
            assert s == [d.comps, d.largest], f"state mismatch {s} vs {[d.comps, d.largest]}"
    elif name.endswith("random") and req["task"] in ("kruskal", "prim"):
        all_edges = Dsu(N)
        for u, v, _ in req["edges"]:
            all_edges.union(u, v)
        assert ans["components"] == all_edges.comps and len(ans["edge_ids"]) == N - all_edges.comps
        chosen, total = Dsu(N), 0
        assert len(set(ans["edge_ids"])) == len(ans["edge_ids"])
        for i in ans["edge_ids"]:
            u, v, w = req["edges"][i]
            assert chosen.union(u, v), "cycle or self-loop in the forest"
            total += w
        assert total == ans["cost"], "cost != sum of chosen weights"
    elif name == "scc random":
        comp, edges = ans["component"], req["edges"]
        pairs = {(comp[u], comp[v]) for u, v in edges if comp[u] != comp[v]}
        assert len(ans["condensation"]) == len(pairs) and {tuple(p) for p in ans["condensation"]} == pairs
        # every edge inside one SCC joins vertices with equal labels; labels are non-negative
        assert min(comp) >= 0
    elif name == "scc chain":
        assert len(set(ans["component"])) == N and len(ans["condensation"]) == N - 1
    elif name == "topo chain":
        assert ans["acyclic"] and ans["order"] == list(range(N))
    elif name == "topo random DAG":
        pos = {v: i for i, v in enumerate(ans["order"])}
        assert ans["acyclic"] and len(pos) == N and all(pos[u] < pos[v] for u, v in req["edges"])
    elif name == "dag chain -1e9":
        assert ans["acyclic"] and ans["distance"][N - 1] == -B * (N - 1)
        assert ans["parent_edge"][N - 1] == N - 2 and ans["parent_edge"][0] == -1
    elif name == "dag random":
        dist, par, src = ans["distance"], ans["parent_edge"], req["source"]
        assert ans["acyclic"] and dist[src] == 0 and par[src] == -1
        for v in range(N):  # every parent edge ends at v and is tight
            if v != src and dist[v] is not None:
                u, w_head, w = req["edges"][par[v]]
                assert w_head == v and dist[u] is not None and dist[u] + w == dist[v]
        for u, v, w in req["edges"]:  # no edge can still improve a distance (optimality certificate)
            if dist[u] is not None:
                assert dist[v] is not None and dist[v] <= dist[u] + w
    return True


def main():
    command = run.build(run.select_language(None))
    requests = build_requests()
    names = list(requests)
    failures = 0
    answers = {}
    print(f"{'request':18} {'seconds (own JVM, -Xmx384m)':>28}")
    for name in names:
        t = time.perf_counter()
        try:
            answers[name] = run.execute(command, [requests[name]])[0]
            status = f"{time.perf_counter() - t:6.2f}"
        except (ValueError, subprocess.TimeoutExpired) as error:
            status = f"FAILED: {str(error)[:200]}"
            failures += 1
        print(f"{name:18} {status:>28}")
    for name, ans in answers.items():
        try:
            verify(name, requests[name], ans)
        except AssertionError as error:
            failures += 1
            print(f"FAIL {name}: {error}")
    if "kruskal random" in answers and "prim random" in answers:
        k, p = answers["kruskal random"], answers["prim random"]
        same = (k["cost"], k["components"]) == (p["cost"], p["components"])
        print(("PASS" if same else "FAIL") + f" kruskal and prim agree: cost={k['cost']} components={k['components']}")
        failures += not same
    # whole batch in ONE process (state reset between big requests, one 20-second budget)
    t = time.perf_counter()
    try:
        batch = run.execute(command, [requests[n] for n in names])
        print(f"whole batch of {len(names)} maximum-size requests in one process: {time.perf_counter() - t:.2f} s")
        for name, ans in zip(names, batch):
            if ans != answers.get(name):
                print(f"FAIL {name}: answer differs between single and batch run")
                failures += 1
    except (ValueError, subprocess.TimeoutExpired) as error:
        print("FAIL whole batch:", str(error)[:200])
        failures += 1
    print("all large checks passed" if not failures else f"{failures} FAILURES")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
