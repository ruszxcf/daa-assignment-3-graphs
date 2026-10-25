"""One command for all of my tests (Java solution).

    python tests/run_tests.py            # unit/differential tests + hand-written cases + public checks
    python tests/run_tests.py --large    # additionally: maximum-size process-level test (about 15 s)
    python tests/run_tests.py --mutants  # additionally: break the solution on purpose, tests must notice
    python tests/run_tests.py --bench    # additionally: Kruskal vs Prim benchmark, saves tests/bench/results.txt

Needs JDK 17+ and Python 3.12+ (same as tools/run.py). Everything is compiled into .build/ (git-ignored).
"""
import argparse
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD = ROOT / ".build"


def step(title, command, **kwargs):
    print(f"\n=== {title}")
    sys.stdout.flush()
    return subprocess.run(command, cwd=ROOT, **kwargs).returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--large", action="store_true")
    parser.add_argument("--bench", action="store_true")
    parser.add_argument("--mutants", action="store_true", help="also run tests/mutation_check.py (about 1 minute)")
    args = parser.parse_args()
    py = sys.executable
    sep = os.pathsep

    status = step("compile solution (javac --release 17)",
                  ["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(BUILD / "Java"),
                   *map(str, sorted((ROOT / "src" / "Java").glob("*.java")))])
    if status:
        return status
    classes = str(BUILD / "Java") + sep + str(BUILD / "tests")
    status = step("compile tests",
                  ["javac", "--release", "17", "-encoding", "UTF-8", "-cp", str(BUILD / "Java"),
                   "-d", str(BUILD / "tests"),
                   *map(str, sorted((ROOT / "tests" / "java").glob("*.java"))),
                   *map(str, sorted((ROOT / "tests" / "bench").glob("*.java")))])
    if status:
        return status

    failed = 0
    failed += bool(step("Java tests: validators, edge cases, route helper, random differential, large/deep",
                        ["java", "-Xmx384m", "-cp", classes, "AlgorithmTests"]))
    failed += bool(step("route reconstruction demo (saved output: tests/route_demo_output.txt)",
                        ["java", "-cp", classes, "RouteDemo"]))
    failed += bool(step("hand-written cases (tests/my_cases.jsonl -> tests/actual.jsonl)",
                        [py, "tests/check_cases.py"]))
    failed += bool(step("public checks (tools/run.py --check)", [py, "tools/run.py", "--check"]))
    if args.large:
        failed += bool(step("maximum-size inputs through the real process (-Xmx384m)", [py, "tests/check_large.py"]))
    if args.mutants:
        failed += bool(step("mutation check: do the tests notice deliberate bugs?", [py, "tests/mutation_check.py"]))
    if args.bench:
        out = ROOT / "tests" / "bench" / "results.txt"
        print("\n=== Kruskal vs Prim benchmark")
        result = subprocess.run(["java", "-Xmx384m", "-cp", classes, "KruskalPrimBench"], cwd=ROOT,
                                capture_output=True, text=True, encoding="utf-8")
        text = "\n".join(line for line in result.stdout.splitlines())
        out.write_text(text + "\n", encoding="utf-8")
        print(text)
        failed += bool(result.returncode)
    print("\nALL TESTS PASSED" if not failed else f"\n{failed} STEP(S) FAILED")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
