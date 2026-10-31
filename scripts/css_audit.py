"""Fail when base.css carries a style class that nothing in the app ever applies.

Why a script and not a reading pass: the stylesheet is ~2000 lines, and "dead"
can only be claimed after the name was searched across every Java source, the
WebView templates and the other stylesheets — including names that Java builds
at runtime ("md-h" + level), which a literal search would wrongly report dead.

JavaFX skins create nodes with style classes that never appear in our source
(.track, .list-cell, .chart-legend, ...). Those are listed in SKIN_CLASSES.
Anything outside both lists with zero hits is dead, and the gate fails on it so
the stylesheet cannot re-accrete.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CSS_DIR = ROOT / "src" / "main" / "resources" / "css"
JAVA_DIR = ROOT / "src" / "main" / "java"

# Class names JavaFX's own skins put on nodes it builds.
SKIN_CLASSES = {
    "arrow", "arrow-button", "arrow-head", "bar", "button", "button-bar", "cell",
    "check-box", "check-menu-item", "combo-box", "combo-box-popup", "content",
    "content-area", "dialog-pane", "dot", "filler", "focus-indicator", "header",
    "header-panel", "label", "left", "list-cell", "list-view", "mark", "menu-bar",
    "menu-item", "pane", "placeholder", "radio", "radio-button", "radio-menu-item",
    "region", "right", "selected", "separator", "spinner-arrows", "split-pane-divider",
    "sub-header", "tab", "tab-content-area", "tab-header-area", "tab-header-background",
    "tab-label", "table-cell", "table-row-cell", "text", "text-area", "text-field",
    "text-input", "thumb", "title", "toggle-button", "tooltip", "track", "tree-cell",
    "virtual-flow", "viewport", "box", "password-field", "input-line", "day-cell",
    "day-name-cell", "month-year-pane", "column-header", "column-header-background",
    "graphic-header", "header-area", "increment-arrow", "decrement-arrow",
    "increment-button", "decrement-button", "show-hide-handle", "side-panel",
    "cancel-button", "clear-button", "svg-pane", "root",
    # Default style classes of the controls themselves — JavaFX sets them, our
    # source only ever names the control type, so a literal search finds nothing.
    "accordion", "axis", "chart", "combo-box-base", "context-menu", "date-picker",
    "date-picker-popup", "hyperlink", "menu-button", "pagination", "popup",
    "progress-bar", "scroll-bar", "scroll-pane", "spinner", "split-pane",
    "tab-pane", "table-view", "text-flow", "titled-pane", "tree-view",
    "week-number-cell", "web-view",
}
# Chart nodes are built by javafx-charts; their default style classes are fixed.
SKIN_PREFIXES = ("chart-", "series", "default-color", "axis-", "zero-line")
# Classes Java assembles at runtime, verified by hand: MarkdownFlow builds
# "md-h" + level and AiChatView builds "chat-bubble-" + role, so those literal
# names appear nowhere. Everything else (chip-*, md-fmt-*) is written out in
# full in Java and is therefore checked normally.
DYNAMIC_PREFIXES = ("md-h", "chat-bubble-")


def css_classes():
    found = {}
    for css in sorted(CSS_DIR.glob("*.css")):
        text = css.read_text(encoding="utf-8")
        text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
        for line in text.splitlines():
            if "{" not in line:
                continue
            selector = line.split("{", 1)[0]
            for cls in re.findall(r"\.([A-Za-z][A-Za-z0-9_-]*)", selector):
                found.setdefault(cls, set()).add(css.name)
    return found


def main():
    corpus = []
    for p in JAVA_DIR.rglob("*.java"):
        corpus.append(p.read_text(encoding="utf-8", errors="replace"))
    for p in ROOT.joinpath("src", "main", "resources").rglob("*"):
        if p.is_file() and p.suffix != ".css":
            corpus.append(p.read_text(encoding="utf-8", errors="replace"))
    blob = "\n".join(corpus)

    classes = css_classes()
    checked = 0
    dead = []
    for cls in sorted(classes):
        if cls in SKIN_CLASSES:
            continue
        if cls.startswith(SKIN_PREFIXES) or cls.startswith(DYNAMIC_PREFIXES):
            continue
        checked += 1
        if f'"{cls}"' not in blob and f"'{cls}'" not in blob:
            dead.append(cls)

    print(f"CSS classes={len(classes)} checked(ours)={checked} dead={len(dead)}")
    for cls in dead:
        print("  DEAD ." + cls + "  (" + ", ".join(sorted(classes[cls])) + ")")
    if dead:
        print("CSS_AUDIT=RED")
        return 1
    print("CSS_AUDIT=GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
