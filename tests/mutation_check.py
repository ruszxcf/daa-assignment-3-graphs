"""Sanity check of the tests themselves: break the solution on purpose and make sure the tests notice.

    python tests/mutation_check.py

Each "mutant" is a copy of src/Java/Algorithms.java with ONE deliberate bug. The Java test program
(tests/java/AlgorithmTests.java) is compiled against it and run; it must report failing checks.
The real source tree is never modified.
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

MUTANTS = [
    ("topo: arc into an ACTIVE vertex is not treated as a cycle",
     "if (state[w] == 1) {\n                        return null; // back edge -> directed cycle\n                    }",
     "if (state[w] == 1) { }"),
    ("dag: relaxes edges out of unreachable vertices",
     "if (!reached[v]) {\n                continue; // never relax out of an unreachable vertex\n            }", ""),
    ("kruskal: processes edges from the heaviest to the lightest",
     "Arrays.sort(keys);",
     "Arrays.sort(keys); for (int i = 0; i < keys.length / 2; i++) { long t = keys[i]; keys[i] = keys[keys.length - 1 - i]; keys[keys.length - 1 - i] = t; }"),
    ("scc: repeated condensation pairs are not removed", "seenPairs.add((long) cu * n + cv)", "true"),
    ("prim: does not restart in the next component", "components++;", "components++; if (components > 1) break;"),
    ("dsu: 'largest' is never updated", "if (size[ra] > largest) {", "if (false) {"),
    ("route: parent edge direction is not checked and ids of reverse edges are accepted",
     "if (edges[e][1] != v) {", "if (false) {"),
]


# Output-equivalent mutants: they only slow the program down, so no test of the ANSWERS can see them.
# (Union by size is a performance heuristic; with path compression alone the DSU is still correct.)
EXPECTED_SURVIVORS = [
    ("dsu: union by size attaches the LARGER tree below the smaller (performance only)",
     "if (size[ra] < size[rb]) {", "if (size[ra] > size[rb]) {"),
]


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", **kw)


def main():
    source = (ROOT / "src" / "Java" / "Algorithms.java").read_text(encoding="utf-8")
    sep = os.pathsep
    survived = 0
    for name, old, new in MUTANTS + EXPECTED_SURVIVORS:
        expected_survivor = (name, old, new) in EXPECTED_SURVIVORS
        assert source.count(old) == 1, f"pattern for '{name}' not found exactly once"
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            src, out = tmp / "src", tmp / "out"
            src.mkdir()
            out.mkdir()
            for f in (ROOT / "src" / "Java").glob("*.java"):
                shutil.copy(f, src / f.name)
            (src / "Algorithms.java").write_text(source.replace(old, new), encoding="utf-8")
            built = run(["javac", "--release", "17", "-d", str(out), *map(str, src.glob("*.java"))])
            built = built if built.returncode else run(
                ["javac", "--release", "17", "-cp", str(out), "-d", str(out), str(ROOT / "tests" / "java" / "AlgorithmTests.java")])
            if built.returncode:
                print(f"?  {name}: mutant does not compile\n{built.stderr}")
                continue
            result = run(["java", "-Xmx384m", "-cp", str(out) + sep + str(out), "AlgorithmTests"])
            failing = sum(1 for line in result.stdout.splitlines() if line.startswith("FAIL"))
            caught = failing > 0 or result.returncode != 0
            if expected_survivor:
                print(f"{'caught  ' if caught else 'survived'} ({failing:5d} failing checks)  {name}  [expected: output-equivalent]")
                continue
            survived += not caught
            print(f"{'caught  ' if caught else 'SURVIVED'} ({failing:5d} failing checks)  {name}")
    print(f"{len(MUTANTS) - survived} of {len(MUTANTS)} deliberate bugs were detected")
    return 1 if survived else 0


if __name__ == "__main__":
    sys.exit(main())
