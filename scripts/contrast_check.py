import re, sys, pathlib

# Repo-relative: this used to hard-code one developer's Downloads folder, so the
# gate died with FileNotFoundError before printing a single ratio on every other
# machine and nothing was ever wired to run it.
CSS_DIR = pathlib.Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "css"
REQUIRED = [
    "-ld-bg", "-ld-surface", "-ld-card", "-ld-input", "-ld-elev", "-ld-border",
    "-ld-hairline",
    "-ld-text", "-ld-text-dim", "-ld-accent", "-ld-accent-text", "-ld-accent-soft",
    "-ld-danger", "-ld-ok", "-ld-warn", "-ld-info",
    "-ld-danger-soft", "-ld-ok-soft", "-ld-warn-soft", "-ld-info-soft",
    "-ld-chart-1", "-ld-chart-2", "-ld-chart-3", "-ld-chart-4", "-ld-chart-5",
]

def parse(path):
    toks = {}
    for name, val in re.findall(r"(-ld-[a-z0-9\-]+)\s*:\s*(#[0-9a-fA-F]{6})", path.read_text(encoding="utf-8")):
        toks[name] = val
    return toks

def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def lum(hexstr):
    h = hexstr.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * srgb_to_lin(r) + 0.7152 * srgb_to_lin(g) + 0.0722 * srgb_to_lin(b)

def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)

# (label, fg token, bg token, minimum)
PAIRS = [
    ("body text on app bg",            "-ld-text",   "-ld-bg",       4.5),
    ("body text on surface",           "-ld-text",   "-ld-surface",  4.5),
    ("body text on card",              "-ld-text",   "-ld-card",     4.5),
    ("body text on input",             "-ld-text",   "-ld-input",    4.5),
    ("body text on elev (hover)",      "-ld-text",   "-ld-elev",     4.5),
    ("dim text on card",               "-ld-text-dim","-ld-card",    4.5),
    ("dim text on bg",                 "-ld-text-dim","-ld-bg",      4.5),
    ("dim text on surface",            "-ld-text-dim","-ld-surface", 4.5),
    ("placeholder on input",           "-ld-text-dim","-ld-input",   4.5),
    ("dim text on elev",               "-ld-text-dim","-ld-elev",    4.5),
    ("primary button label",           "-ld-accent-text","-ld-accent",4.5),
    ("nav active label",               "-ld-accent", "-ld-accent-soft", 4.5),
    ("accent text on card",            "-ld-accent", "-ld-card",     4.5),
    ("chip info on accent-soft",       "-ld-accent", "-ld-accent-soft", 4.5),
    ("danger banner text",             "-ld-danger", "-ld-danger-soft", 4.5),
    ("ok chip text",                   "-ld-ok",     "-ld-ok-soft",  4.5),
    ("warn chip text",                 "-ld-warn",   "-ld-warn-soft", 4.5),
    ("info chip text",                 "-ld-info",   "-ld-info-soft", 4.5),
    ("danger text on card",            "-ld-danger", "-ld-card",     4.5),
    ("ok text on card",                "-ld-ok",     "-ld-card",     4.5),
    ("warn text on card",              "-ld-warn",   "-ld-card",     4.5),
    ("info text on card",              "-ld-info",   "-ld-card",     4.5),
    ("selection bar text",             "-ld-text",   "-ld-accent-soft", 4.5),
    # -ld-border is a 1px STRUCTURAL divider (card/table/panel edges). WCAG 1.4.11's
    # 3:1 applies to components whose state must be identified from the mark itself;
    # that job here belongs to -ld-accent (rails, focus) and -ld-text-dim (thumb),
    # which are checked at 3:1. Dividers only need to be visibly present.
    ("divider visible on card",        "-ld-border", "-ld-card",       1.5),
    ("divider visible on bg",          "-ld-border", "-ld-bg",         1.5),
    ("nav rail glyph on card",         "-ld-accent", "-ld-card",     3.0),
    ("scroll thumb on surface",        "-ld-text-dim", "-ld-surface",  3.0),
]

fail = 0
for theme in ("dark", "light", "mate", "monokai", "deepblue"):
    path = CSS_DIR / f"{theme}.css"
    toks = parse(path)
    missing = [t for t in REQUIRED if t not in toks]
    extra = [t for t in toks if t not in REQUIRED]
    print(f"\n=== {theme} ===  tokens={len(toks)} missing={missing} extra={extra}")
    if missing:
        fail += 1
    worst = []
    for label, fg, bg, minimum in PAIRS:
        r = ratio(toks[fg], toks[bg])
        mark = "OK " if r >= minimum else "FAIL"
        if r < minimum:
            fail += 1
            worst.append((label, round(r, 2), minimum))
        print(f"  [{mark}] {r:5.2f}:1 (min {minimum}) {label}: {toks[fg]} on {toks[bg]}")
    for s in range(5):
        c = toks[f"-ld-chart-{s + 1}"]
        r = ratio(c, toks["-ld-card"])
        print(f"  [{'OK ' if r >= 3 else 'FAIL'}] {r:5.2f}:1 (min 3.0) chart-{s + 1} series on card: {c}")
        if r < 3:
            fail += 1

print(f"\nTOTAL FAILURES: {fail}")
sys.exit(1 if fail else 0)
