#!/usr/bin/env python3
"""Repository quality gates.

Why this exists: the previous tree grew to 19 files over 600 lines, 8 pairs of
parallel UI generations, 17 hand-written workflows and 47 locale directories,
because every round added code and nothing ever failed when a boundary was
crossed. These checks make the boundaries mechanical: a violation fails CI
instead of being noticed six months later.

Four checks, each with a documented escape hatch:

  1. file size      - a file may not exceed its recorded budget. Budgets live in
                      scripts/quality-baseline.json: an oversized file is allowed
                      to exist only if it is already listed there, and it may
                      shrink but never grow. New files start with no budget.
  2. locales        - only the maintained string tables may exist. Adding a locale
                      is a decision, not an accident.
  3. workflows      - .github/workflows may hold at most MAX_WORKFLOWS files. The
                      old tree accumulated 17; each new one must replace one.
  4. swallowed errors - shell scripts may not end a command chain with `|| true`
                      or a trailing `echo done`, which is how a failure turns into
                      a green UI (see feature/tee/README.md for the incident).

Run locally:  python scripts/check_quality.py
Exit code 0 = all gates pass.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "scripts" / "quality-baseline.json"

MAX_LINES = 400          # soft limit for any new source file
MAX_WORKFLOWS = 14       # 13 upstream + the guards workflow; must not grow past this
ALLOWED_LOCALES = {"values", "values-night", "values-zh-rCN"}
SOURCE_SUFFIXES = {".kt", ".kts", ".java", ".rs", ".c", ".h", ".py", ".sh"}
SKIP_DIRS = {".git", "build", "target", "node_modules", "third_party", "generated-images"}

problems = []


def rel(p):
    return str(p.relative_to(ROOT)).replace("\\", "/")


def source_files():
    for p in ROOT.rglob("*"):
        if not p.is_file() or p.suffix not in SOURCE_SUFFIXES:
            continue
        parts = set(p.relative_to(ROOT).parts)
        if parts & SKIP_DIRS:
            continue
        yield p


def count_lines(p):
    try:
        return sum(1 for _ in p.open("r", encoding="utf-8", errors="replace"))
    except OSError:
        return 0


def check_file_sizes():
    baseline = {}
    if BASELINE.exists():
        raw = json.loads(BASELINE.read_text(encoding="utf-8")).get("oversized", {})
        # entries are {"lines": n, "reason": "..."}; accept a bare int too so an
        # older baseline keeps working
        for key, value in raw.items():
            baseline[key] = value["lines"] if isinstance(value, dict) else int(value)
    for p in source_files():
        n = count_lines(p)
        key = rel(p)
        budget = baseline.get(key)
        if budget is None:
            if n > MAX_LINES:
                problems.append(
                    "%s: %d lines (limit %d). Split it, or record a budget in "
                    "scripts/quality-baseline.json with a reason." % (key, n, MAX_LINES))
        elif n > budget:
            problems.append(
                "%s: %d lines, budget %d - a budgeted file may shrink, not grow."
                % (key, n, budget))


def check_locales():
    """No new locales beyond the recorded set.

    Upstream ships ~46 translated string tables and they cost us nothing: a
    missing key there falls back to English. What must not happen again is *our*
    strings being scattered into 47 directories - the old tree did exactly that
    and one cleanup round deleted 139 entries across 39 files. So the recorded
    set is the ceiling: it may shrink, never grow.
    """
    res = ROOT / "manager" / "app" / "src" / "main" / "res"
    if not res.exists():
        return
    recorded = set()
    if BASELINE.exists():
        recorded = set(json.loads(BASELINE.read_text(encoding="utf-8")).get("locales", []))
    for d in sorted(res.iterdir()):
        if not d.is_dir() or not d.name.startswith("values"):
            continue
        if d.name in ALLOWED_LOCALES or d.name in recorded:
            continue
        problems.append(
            "res/%s: new locale. The ceiling is %d recorded locales; translatarions for "
            "our own strings go into values/ and values-zh-rCN/ only."
            % (d.name, len(recorded)))


def check_workflows():
    wf = ROOT / ".github" / "workflows"
    if not wf.exists():
        return
    files = sorted(p for p in wf.iterdir() if p.suffix in (".yml", ".yaml"))
    if len(files) > MAX_WORKFLOWS:
        problems.append(
            ".github/workflows: %d files (max %d). Remove one before adding another."
            % (len(files), MAX_WORKFLOWS))


SWALLOW_PATTERNS = [
    (re.compile(r"\|\|\s*true\b"), "`|| true` turns a failure into success"),
    (re.compile(r"^\s*echo\s+['\"]?done['\"]?\s*$"), "a trailing `echo done` hides earlier failures"),
]
# Upstream's own scripts are not ours to police: skipped, or the check becomes a
# permanent list of other people's style.
OUR_AREAS = ("feature/", "manager/", "scripts/")


def check_swallowed_errors():
    for p in source_files():
        if p.suffix not in {".sh"}:
            continue
        key = rel(p)
        if not key.startswith(OUR_AREAS):
            continue
        for i, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            stripped = line.lstrip()
            if stripped.startswith("#"):
                continue
            # `# allowed: <why>` on the same line is the escape hatch, and the
            # reason is mandatory - an unexplained allowance is how the old tree
            # ended up with silently-succeeding failures everywhere.
            if "# allowed:" in line:
                continue
            for pattern, why in SWALLOW_PATTERNS:
                if pattern.search(line):
                    problems.append("%s:%d: %s (append `# allowed: <why>` if intended)"
                                    % (key, i, why))


def check_line_endings():
    """Scripts must be LF and BOM-free.

    Not cosmetic: CI caught a CRLF script failing `bash -n` with
    `syntax error near unexpected token $'{\\r'`, and the same file would fail on
    the device under Android's sh. A shebang line with CRLF is ignored silently,
    which is worse than a loud failure.
    """
    for p in source_files():
        if p.suffix != ".sh":
            continue
        key = rel(p)
        data = p.read_bytes()
        if data.startswith(b"\xef\xbb\xbf"):
            problems.append("%s: starts with a UTF-8 BOM; the shebang stops working" % key)
        if b"\r\n" in data:
            problems.append("%s: CRLF line endings; `sh -n` fails and so does the device" % key)


STRING_TABLES = (
    ROOT / "manager/app/src/main/res/values/strings.xml",
    ROOT / "manager/app/src/main/res/values-zh-rCN/strings.xml",
)
STRING_ELEMENT = re.compile(r'<string name="([^"]+)">(.*?)</string>', re.S)


def check_string_escapes():
    """Apostrophes inside a string resource must be escaped.

    Measured cost of skipping this: `stealth_note` shipped with a bare `app's`, which
    `:app:compileReleaseKotlin` does not look at - resources are compiled later. CI's
    release build then died in `mergeReleaseResources` with the misleading message
    "Invalid unicode escape sequence in string", and the whole push had to be redone.
    aapt2 wants `\\'` and `\\"`; a bare apostrophe is an error.
    """
    for table in STRING_TABLES:
        if not table.is_file():
            continue
        text = table.read_text(encoding="utf-8", errors="replace")
        for match in STRING_ELEMENT.finditer(text):
            name, value = match.group(1), match.group(2)
            line = text.count("\n", 0, match.start()) + 1
            for i, ch in enumerate(value):
                if ch != "'":
                    continue
                if i > 0 and value[i - 1] == "\\":
                    continue
                problems.append(
                    "%s:%d: string `%s` has an unescaped apostrophe; write \\' "
                    "(aapt2 fails the release build over it)" % (rel(table), line, name))
                break


def main():
    check_file_sizes()
    check_locales()
    check_workflows()
    check_swallowed_errors()
    check_line_endings()
    check_string_escapes()
    if problems:
        print("quality gates failed (%d):" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1
    print("quality gates: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
