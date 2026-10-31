"""Every way to open an editor for a selected row should cost one step.

A table whose toolbar has "Sửa" / "Đổi tên" / a pencil button forces two actions:
click the row, then find and press the button. Double-click on the row, or a
selection listener that opens the record directly, is the same flow in one step —
and the app already uses both idioms (Tasks, Finance, Courses, Files, Notes).

This audit does not demand a specific idiom. It demands that a control edited
through a button also react to something on the control itself, so the second
trip across the screen is optional rather than required.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path('src/main/java/com/lifedesk/ui')
EDIT_AFFORDANCE = re.compile(
    r'new Button\(\s*"(?:Sửa|Đổi tên|Hiệu chỉnh)[^"]*"|IconView\("pencil"')
SELECTION_USE = re.compile(r'(\w+)\.getSelectionModel\(\)')
PAGE_HELPER = re.compile(r'\bpage\(')
HAND_ROLLED_PAGE = re.compile(
    r'new VBox\(\s*(\d+)\s*,\s*(?:header|headerRow|titleRow)\b')


def one_step_paths(ctrl: str):
    """Handlers attached to the control itself. Each names the control, so a
    double-click on one table cannot excuse every other table in the file."""
    return (ctrl + r'\.setOnMouseClicked\(',
            ctrl + r'\.getSelectionModel\(\)\.selectedItemProperty\(\)\.addListener\(',
            ctrl + r'\.setOnKeyReleased\(')


DELEGATION = re.compile(r'->\s*(\w+)\s*\(\s*\)')


def controls_edited(text: str, body: str, depth: int = 0) -> set:
    """Selections read by a handler, following one hop of `-> method()` delegation.

    Without the hop the audit would go green the moment a button body became a
    one-liner — which is exactly what extracting a shared handler does.
    """
    found = set(SELECTION_USE.findall(body))
    if depth >= 2:
        return found
    for name in DELEGATION.findall(body):
        m = re.search(r'(?:private|protected)[\w<> ,.]*?' + re.escape(name)
                      + r'\s*\([^)]*\)\s*\{', text)
        if m:
            found |= controls_edited(text, handler_body(text, m.end() - 1), depth + 1)
    return found


def handler_body(text: str, start: int) -> str:
    """The brace-balanced block that opens at or after `start`."""
    i = text.find('{', start)
    if i < 0:
        return ''
    depth = 0
    for j in range(i, len(text)):
        if text[j] == '{':
            depth += 1
        elif text[j] == '}':
            depth -= 1
            if depth == 0:
                return text[i:j]
    return ''


# An empty page that says Bấm "X" makes the user read a sentence and then hunt for
# the control. EmptyState.withAction() puts the control in the sentence's place.
# The one exception is keyed on the exact sentence, not on the file: the health
# placeholder points at a form that is already on screen, and offering to submit it
# empty would be worse than the prose.
PROSE_ONLY_ALLOWED = (
    'Chưa ghi ngày nào. Nhập số liệu rồi bấm "Lưu hôm nay".',
)
PROSE_BUTTON = re.compile(r'[Bb]ấm +"([^"]+)"')
EMPTY_CALL = re.compile(r'new EmptyState\(([^;]*?)\);', re.S)


def prose_command_problems():
    out = []
    for path in sorted(ROOT.rglob('*.java')):
        text = path.read_text(encoding='utf-8')
        for m in EMPTY_CALL.finditer(text):
            # Java writes the quoted label as \"Thêm feed\"; unescape before looking,
            # otherwise the rule matches nothing and reports a clean sheet forever.
            whole = re.sub(r'\s+', ' ', m.group(1).replace('\\', '')).strip()
            named = PROSE_BUTTON.search(whole)
            if not named:
                continue
            if any(a in whole for a in PROSE_ONLY_ALLOWED):
                continue
            line = text[:m.start()].count(chr(10)) + 1
            out.append(f'{path.relative_to(ROOT)}:{line}: trạng thái rỗng nhắc '
                       f'bấm "{named.group(1)}" thay vì đặt nút đó vào chỗ trống')
    return out


def page_rhythm_problems():
    """One gap between a page header and what it heads.

    Every screen used to write its own `new VBox(10|12|14, header, ...)`; three picked
    10, four picked 12, one picked 14, so the same header-to-body distance looked
    different from page to page. View.page() is now the only way to build that stack,
    and a literal spacing in front of a header is the mistake this rule keeps out.
    """
    out = []
    uses = 0
    for path in sorted((ROOT / 'views').glob('*.java')):
        text = path.read_text(encoding='utf-8')
        uses += len(PAGE_HELPER.findall(text))
        for m in HAND_ROLLED_PAGE.finditer(text):
            line = text[:m.start()].count(chr(10)) + 1
            out.append(f'{path.relative_to(ROOT)}:{line}: tự dựng VBox({m.group(1)}) '
                       f'cho đầu trang thay vì gọi page(...)')
    return out, uses


def main() -> int:
    problems = []
    measured = 0
    for path in sorted(ROOT.rglob('*.java')):
        text = path.read_text(encoding='utf-8')
        for m in EDIT_AFFORDANCE.finditer(text):
            controls = sorted(controls_edited(text, handler_body(text, m.start())))
            if not controls:
                continue
            measured += 1
            for ctrl in controls:
                if any(re.search(pat, text) for pat in one_step_paths(re.escape(ctrl))):
                    continue
                line = text[:m.start()].count('\n') + 1
                problems.append(
                    f'{path.relative_to(ROOT)}:{line}: nút sửa đọc '
                    f'{ctrl}.getSelectionModel() nhưng {ctrl} chưa có lối mở một bước')
    prose = prose_command_problems()
    rhythm, page_uses = page_rhythm_problems()
    for p in problems:
        print('  [MISS] ' + p)
    for p in prose:
        print('  [PROSE] ' + p)
    for p in rhythm:
        print('  [RHYTHM] ' + p)
    problems += prose
    # Reach: a rule that matched nothing because its pattern drifted would otherwise
    # report a clean sheet. The helper has to be in use for its absence to mean much.
    if page_uses < 10:
        problems.append(f'page(...) chỉ được dùng {page_uses} lần (cần >= 10) — '
                        f'quy tắc nhịp trang không đo gì')
    print(f'edit-buttons measured={measured} missing-one-step={len(problems)}')
    print(f'page-rhythm uses={page_uses} violations={len(rhythm)}')
    print('WIRING_AUDIT=' + ('GREEN' if not problems else 'RED'))
    return 0 if not problems else 1


if __name__ == '__main__':
    sys.exit(main())
