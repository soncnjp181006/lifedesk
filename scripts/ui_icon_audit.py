"""Icon copy audit: no colour emoji in UI strings, and every named icon resolves.

JavaFX draws a colour emoji from the system emoji font: it ignores -ld-text, so it
cannot follow the theme, it looks different on every OS, and at 15px in a button it
reads as noise. The app already owns a monochrome Lucide set (src/main/resources/icons)
that inherits the palette through .ld-icon-part, so an emoji used as an icon is a
missing vector, not a style choice.

Deliberately allowed, because these are text-presentation glyphs that inherit the
label colour and are not rendered as colour emoji: arrows and typographic marks
(-> up/down/left/right), geometric shapes the toolbars already use (triangle
play/pause, small square, checkbox, outline square, bullet), and the dingbat check
and cross used in list markers. Unicode lists all of them with Emoji_Presentation=No.

Rules:
  1. no colour-emoji codepoint inside a string literal (comments are stripped: a
     comment that quotes an emoji as an example is documentation, not copy);
  2. every IconView("name") literal resolves to icons/name.svg;
  3. an icon name carried as *data* resolves too - the slots that forward it to an
     IconView later (EmptyState's first argument, ContextItem's last one) are read
     here, because a name that only appears inside a component call is invisible to
     rule 2 and paints a blank square just the same;
  4. IconPicker.ICONS is exactly the set of files on disk - it documents itself as
     "the full built-in set", so a file nobody can pick and a tile that renders blank
     are both caught here - and no name is listed twice (a duplicate shows the same
     tile twice in the grid);
  5. names built at runtime are not literals; the smoke gate asserts
     IconView.missingIcons() is empty after painting every module, which covers those
     paths as they actually run;
  6. a button with words is never given the icon-btn class - that class pins the box to
     a 34px square for icon-only controls, so the label is elided in every window size
     and no width probe can see it unless the bar exists on screen.
"""
import pathlib
import re
import sys

SRC = pathlib.Path('src/main/java')
ICONS = pathlib.Path('src/main/resources/icons')

# Colour-presentation emoji: these render from the emoji font, not from the label colour.
EMOJI_RANGES = [
    (0x1F000, 0x1FAFF),  # pictographs: books, films, cameras, documents, gamepad, ...
    (0x1FB00, 0x1FBFF),
    (0x23EC, 0x23FA),    # transport and clock symbols (pause, stop, hourglass, stopwatch)
    (0x2600, 0x2604),    # weather
    (0x2614, 0x2615),    # umbrella, hot beverage
    (0x2639, 0x263A),    # faces
    (0x267F, 0x267F),
    (0x26A0, 0x26A1),    # warning sign, high voltage
    (0x26BD, 0x26BE),
    (0x26F0, 0x26F5),
    (0x26FA, 0x26FA),
    (0x2705, 0x2705),    # white heavy check mark (colour) vs U+2713 check mark (text)
    (0x2728, 0x2728),
    (0x274C, 0x274E),
    (0x2753, 0x2757),
    (0x2795, 0x2797),
    (0x2B50, 0x2B55),
    (0xFE0F, 0xFE0F),    # emoji presentation selector
]


def is_emoji(ch: str) -> bool:
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in EMOJI_RANGES)


def code_without_comments(java: str) -> list:
    """(index, text) pairs for every string literal, so line numbers stay real.

    Scanning literals rather than whole lines is what keeps documentation arrows and
    commented-out examples out of the verdict while keeping user copy in it.
    """
    out = []
    i, n = 0, len(java)
    while i < n:
        c = java[i]
        if c == '/' and java.startswith('//', i):
            j = java.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and java.startswith('/*', i):
            j = java.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif java.startswith('"""', i):
            j = java.find('"""', i + 3)
            out.append((i, java[i + 3:(n if j < 0 else j)]))
            i = n if j < 0 else j + 3
        elif c == '"':
            j = i + 1
            while j < n and java[j] != '"':
                j += 2 if java[j] == '\\' else 1
            out.append((i, java[i + 1:min(j, n)]))
            i = min(j, n) + 1
        elif c == "'":
            j = i + 1
            while j < n and java[j] != "'":
                j += 2 if java[j] == '\\' else 1
            i = min(j, n) + 1
        else:
            i += 1
    return out


ICON_CALL = re.compile(r'IconView\(\s*"([^"]+)"')
# A record that carries an icon name as data (the AI context list builds its cell
# graphic from it later) hides the name from the rule above, so the final argument of
# its constructor is checked here too. Scoped to that one constructor call.
DATA_ITEM = re.compile(r'new ContextItem\(')
# EmptyState's first argument is an icon name that reaches IconView through the
# component, not through a literal IconView call, so it needs its own slot rule.
EMPTY_ICON = re.compile(r'(?:new EmptyState\(|EmptyState\.withAction\()\s*"([^"]+)"')
TEXT_BUTTON = re.compile(r'(?:Button|ToggleButton|MenuButton)\s+(\w+)\s*=\s*new\s+'
                         r'(?:Toggle|Menu)?Button\(\s*"([^"]*)"')
ICON_SHAPE = re.compile(r'^[a-z][a-z0-9]*(-[a-z0-9]+)*$')
PICKER_BLOCK = re.compile(r'ICONS\s*=\s*List\.of\((.*?)\);', re.S)
STRING_TOKEN = re.compile(r'"([^"]+)"')


def last_literal_arg(java: str, open_paren: int):
    """The final argument of a call, but only when that argument is a plain literal.

    Splitting on top-level commas (not regex over the whole statement) means an
    argument like `readContent(n.id)` or `.replace("x")` can never be mistaken for
    the icon slot.
    """
    depth = 0
    args = []
    current = []
    i = open_paren
    in_string = False
    while i < len(java):
        c = java[i]
        if in_string:
            current.append(c)
            if c == '\\':
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            current.append(c)
        elif c == '(':
            depth += 1
            if depth > 1:
                current.append(c)
        elif c == ')':
            depth -= 1
            if depth == 0:
                args.append(''.join(current).strip())
                break
            current.append(c)
        elif c == ',' and depth == 1:
            args.append(''.join(current).strip())
            current = []
        else:
            if depth >= 1:
                current.append(c)
        i += 1
    if not args:
        return None
    last = args[-1]
    m = re.fullmatch(r'"([^"]*)"', last)
    return m.group(1) if m else None


def files_on_disk() -> set:
    return {p.stem for p in ICONS.glob('*.svg')}


def main() -> int:
    problems = []
    literals = 0
    available = files_on_disk()
    if not available:
        print('  [MISS] icons/ directory is empty or missing')
        print('ICON_AUDIT=RED')
        return 1

    for path in sorted(SRC.rglob('*.java')):
        text = path.read_text(encoding='utf-8')
        rel = path.as_posix()
        for start, body in code_without_comments(text):
            bad = sorted({ch for ch in body if is_emoji(ch)})
            if bad:
                line = text[:start].count(chr(10)) + 1
                shown = ' '.join(f'{ch} U+{ord(ch):04X}' for ch in bad)
                problems.append(f'{rel}:{line}: chữ giao diện dùng emoji làm biểu tượng: {shown}')
        for m in ICON_CALL.finditer(text):
            literals += 1
            name = m.group(1)
            if name not in available:
                line = text[:m.start()].count(chr(10)) + 1
                problems.append(f'{rel}:{line}: IconView("{name}") không có icons/{name}.svg')
        for m in DATA_ITEM.finditer(text):
            name = last_literal_arg(text, m.end() - 1)
            if name is None or not ICON_SHAPE.match(name):
                continue
            literals += 1
            if name not in available:
                line = text[:m.start()].count(chr(10)) + 1
                problems.append(f'{rel}:{line}: ngữ cảnh gắn biểu tượng "{name}" '
                                f'nhưng không có icons/{name}.svg')
        # .icon-btn is a 34x34 square (hit-area rule). A button that carries words in
        # that class is clamped to 34px, so its label is elided at every window size.
        for m in TEXT_BUTTON.finditer(text):
            name, label = m.group(1), m.group(2)
            if label.strip() and re.search(r'\b' + re.escape(name)
                                           + r'\.getStyleClass\(\)\.add\("icon-btn"\)', text):
                line = text[:m.start()].count(chr(10)) + 1
                problems.append(f'{rel}:{line}: nút "{label}" mang lớp icon-btn '
                                f'(buộc rộng 34px) nên chữ bị cắt')
        for m in EMPTY_ICON.finditer(text):
            literals += 1
            name = m.group(1)
            if name not in available:
                line = text[:m.start()].count(chr(10)) + 1
                problems.append(f'{rel}:{line}: chỗ trống vẽ biểu tượng "{name}" '
                                f'nhưng không có icons/{name}.svg')
        if path.name == 'IconPicker.java':
            m = PICKER_BLOCK.search(text)
            if not m:
                problems.append(f'{rel}: không đọc được danh sách ICONS')
                continue
            listed = STRING_TOKEN.findall(m.group(1))
            names = set(listed)
            for dup in sorted({n for n in names if listed.count(n) > 1}):
                problems.append(f'{rel}: bộ chọn liệt kê "{dup}" hai lần (ô trùng lặp hiện ra 2 lần)')
            for missing in sorted(available - names):
                problems.append(f'{rel}: icons/{missing}.svg có tệp nhưng không hiện trong bộ chọn')
            for ghost in sorted(names - available):
                problems.append(f'{rel}: bộ chọn liệt kê "{ghost}" nhưng không có icons/{ghost}.svg')

    problems = sorted(set(problems))
    for p in problems:
        print('  [ICON] ' + p)
    print(f'icons literal-refs={literals} files={len(available)} problems={len(problems)}')
    print('ICON_AUDIT=' + ('GREEN' if not problems else 'RED'))
    return 0 if not problems else 1


if __name__ == '__main__':
    sys.exit(main())
