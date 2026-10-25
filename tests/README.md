# My tests

Run everything from the repository root (JDK 17+, Python 3.12+):

```sh
python tests/run_tests.py             # Java tests + 48 hand-written cases + route demo + public checks
python tests/run_tests.py --large     # + maximum-size inputs (n = 100000, m = 250000) through the real process
python tests/run_tests.py --mutants   # + break the solution on purpose; the tests must notice
python tests/run_tests.py --bench     # + Kruskal vs Prim benchmark (raw output: tests/bench/results.txt)
```

| File | What it is |
|---|---|
| `java/AlgorithmTests.java` | Self-checking test program: independent validators (and tests of the validators), edge cases, route helper, 1500 random graphs per task, state reset, large and deep inputs |
| `java/RouteDemo.java` | Calls the route-reconstruction helper on real `dag` answers; output saved in `route_demo_output.txt` |
| `make_cases.py` | Source of the hand-written cases: input, **hand-derived** expected answer and the reason. Writes the three files below |
| `my_cases.jsonl` | 48 requests (inputs) – run with `python tools/run.py --input tests/my_cases.jsonl` to see the actual answers |
| `expected.jsonl` | Expected answers (exact where unique, "any valid answer" otherwise – format in `make_cases.py`) |
| `CASES.md` | One line per case: what it checks and why the expected answer is right |
| `check_cases.py` | Runs `my_cases.jsonl`, saves `actual.jsonl`, checks every answer by meaning, repeats the batch in one process |
| `actual.jsonl` | The actual answers of the program for `my_cases.jsonl` (written by `check_cases.py`) |
| `check_large.py` | Ten maximum-size requests (deep chains, random graphs, ±10^9 weights) with `-Xmx384m`, timings and checks |
| `mutation_check.py`, `mutation_output.txt` | Deliberate bugs and the proof that the tests catch them |
| `bench/KruskalPrimBench.java`, `bench/results.txt` | Benchmark of Kruskal vs Prim on identical graphs of three sizes |

Ambiguous answers (forest ids, SCC labels, topological order, parent edges) are validated by meaning, never by equality
with a stored answer. See `ANALYSIS.md`, section 8, for the full description and results.
