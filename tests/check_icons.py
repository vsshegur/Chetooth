"""Icon-name lint: every Material Symbols name the Kotlin code passes to an icon API must exist.

There is no generated R class: icons are loaded with resources.getIdentifier("ic_<name>"), so a
typo only shows up on the phone as an empty icon. This script finds string literals in icon
positions (IconView, setIcon, icon(), buttons, chips, list items, explain sheets, grid cells, enum
entries with an icon, named `icon = "…"` arguments, Icons.turnName) and checks each against
res/drawable/ic_<name>.xml. Icons.kt draws nothing by hand any more (2.1): an unknown name renders an
empty 24 dp icon on the phone, so this lint is the only guard. A FALLBACKS set in Icons.kt, if one is
ever added back, still counts as known.

Usage: python3 tests/check_icons.py [project dir]     (build.sh runs it in the lint stage)
Exit status 1 lists every unknown name with file:line.
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent)

names = set()
for f in (root / "res" / "drawable").glob("ic_*.xml"):
    stem = f.stem[3:]
    names.add(stem[:-5] if stem.endswith("_fill") else stem)

icons_kt = (root / "Icons.kt").read_text(encoding="utf-8")
fallback_match = re.search(r"FALLBACKS\s*=\s*setOf\(([^)]*)\)", icons_kt)
fallbacks = set(re.findall(r'"([a-z0-9_]+)"', fallback_match.group(1))) if fallback_match else set()

NAME = r'"([a-z][a-z0-9_]*)"'
TEXT = r'"(?:[^"\\]|\\.)*"'
ARG = r'[^,()]+'
PATTERNS = [
    # first argument after a context
    rf'\b(?:IconView|StatChip|MetricTile|InlineStat|IconActionButton|ExtendedFab)\(\s*{ARG},\s*{NAME}',
    rf'\bicon\(\s*{ARG},\s*{NAME}',
    # name as first argument
    rf'\b(?:setIcon|setNavigation|addAction|emptyState|icon|Stat|Fact)\(\s*{NAME}',
    # (text, icon) helpers and value classes
    rf'\b(?:filledButton|tonalButton|outlinedButton|assistChip|filterChip|statusChip|NavItem|GroupItem)\(\s*{TEXT},\s*{NAME}',
    rf'\b(?:ActionButton|Chip)\(\s*{ARG},\s*{TEXT},\s*{ARG},\s*{NAME}',
    rf'\b(?:ListItem|SwitchListItem)\(\s*{ARG},\s*{TEXT},\s*{ARG},\s*{NAME}',
    # explain sheets, SpecGrid/SummaryStrip cells, display-setup steps: (icon, text, ...)
    rf'\b(?:explain|ExplainSheet|Step)\(\s*{NAME}',
    rf'\.add\(\s*{NAME},\s*{TEXT}',
    # named arguments
    rf'\b(?:icon|emptyIcon|iconName|leadingIcon)\s*=\s*{NAME}',
    # enum entries such as HOME("Home", "home")
    rf'^\s*[A-Z][A-Z0-9_]*\(\s*{TEXT},\s*{NAME}',
]
compiled = [re.compile(p) for p in PATTERNS]
# Icons.turnName: `NavTurn.X -> "name"` and `null -> "name"` lines inside that function.
turn_line = re.compile(rf'^\s*(?:null|else|NavTurn\.[A-Z_]+(?:,\s*NavTurn\.[A-Z_]+)*)\s*->\s*{NAME}\s*$')

checked = 0
unknown = []
for path in sorted(root.glob("*.kt")):
    in_turn_name = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith(("//", "*", "/*")):
            continue
        if path.name == "Icons.kt":
            if "fun turnName(" in line:
                in_turn_name = True
            elif in_turn_name and stripped == "}":
                in_turn_name = False
        found = [m.group(1) for pattern in compiled for m in pattern.finditer(line)]
        if in_turn_name:
            match = turn_line.match(line)
            if match:
                found.append(match.group(1))
        for name in found:
            checked += 1
            if name not in names and name not in fallbacks:
                unknown.append(f"{path.name}:{number}: unknown icon \"{name}\"")

if unknown:
    print("\n".join(unknown), file=sys.stderr)
    print(f"check_icons: {len(unknown)} unknown icon name(s); add res/drawable/ic_<name>.xml or fix the name",
          file=sys.stderr)
    sys.exit(1)
print(f"check_icons: {checked} icon references, all in res/drawable ({len(names)} names) "
      f"or drawn in code ({len(fallbacks)})")
