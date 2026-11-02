import React, { useEffect, useRef, useState } from 'react';
import {
  Folder,
  FolderPlus,
  Pencil,
  Plus,
  Save,
  Star,
  Trash2
} from 'lucide-react';
import { api } from '../api.js';
import { useDebounced, useLoad } from '../hooks.js';
import {
  Btn,
  Empty,
  ErrorBox,
  Field,
  IconBtn,
  Input,
  Modal,
  Page,
  Select,
  Spinner,
  fmt,
  useConfirm,
  useNotify
} from '../ui.jsx';

const PREFIX_RE = /^(\s*)(#{1,6}\s+|>\s+|[-*+]\s+\[[ xX]\]\s+|[-*+]\s+|\d+\.\s+)/;

function kindOfPrefix(full) {
  const t = (full || '').trim();
  if (/^#{1,6}$/.test(t)) return 'h' + t.length;
  if (t === '>') return 'quote';
  if (/^[-*+]\s*\[[ xX]\]$/.test(t)) return 'task';
  if (/^[-*+]$/.test(t)) return 'ul';
  if (/^\d+\.$/.test(t)) return 'ol';
  return '';
}

function runLength(text, pos, step, ch) {
  let n = 0;
  while (pos + step * n >= 0 && pos + step * n < text.length && text[pos + step * n] === ch) {
    n += 1;
  }
  return n;
}

/** Emphasis state after scanning the text up to pos (bold/italic share '*'). */
function emphasisAt(text, pos) {
  let bold = false;
  let italic = false;
  for (let i = 0; i < pos;) {
    if (text[i] === '*') {
      let j = i;
      while (j < text.length && text[j] === '*') j += 1;
      const n = j - i;
      if (n >= 2) bold = !bold;
      if (n % 2 === 1) italic = !italic;
      i = j;
    } else {
      i += 1;
    }
  }
  return { bold, italic };
}

function strikeAt(text, pos) {
  let strike = false;
  for (let i = 0; i < pos;) {
    if (text[i] === '~') {
      let j = i;
      while (j < text.length && text[j] === '~') j += 1;
      if (j - i >= 2) strike = !strike;
      i = j;
    } else {
      i += 1;
    }
  }
  return strike;
}

function inlineCodeAt(text, pos) {
  let code = false;
  for (let i = 0; i < pos;) {
    if (text[i] === '`') {
      let j = i;
      while (j < text.length && text[j] === '`') j += 1;
      if (j - i === 1) code = !code;
      i = j;
    } else {
      i += 1;
    }
  }
  return code;
}

function inFence(text, pos) {
  const before = text.slice(0, pos).split('\n');
  let fences = 0;
  for (const line of before) {
    if (/^\s*```/.test(line)) fences += 1;
  }
  return fences % 2 === 1;
}

function lineFormats(text, start, end) {
  const from = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
  let to = text.indexOf('\n', Math.max(from, end));
  if (to === -1) to = text.length;
  const lines = text.slice(from, to).split('\n').filter((l) => l.trim().length > 0);
  const kinds = lines.map((l) => {
    const m = l.match(PREFIX_RE);
    return m ? kindOfPrefix(m[2]) : '';
  });
  const has = (k) => lines.length > 0 && kinds.every((x) => x === k);
  return {
    h1: has('h1'),
    h2: has('h2'),
    h3: has('h3'),
    ul: has('ul'),
    ol: has('ol'),
    task: has('task'),
    quote: has('quote')
  };
}

function selectionHasWrap(text, start, end, marker) {
  const len = marker.length;
  if (end - start < 2 * len) return false;
  if (marker === '*') {
    return text[start] === '*' && text[start - 1] !== '*'
      && text[end - len] === '*' && text[end - len - 1] !== '*';
  }
  return text.slice(start, start + len) === marker && text.slice(end - len, end) === marker;
}

/** Locate the marker pair that wraps pos for a given inline format. */
function findEnclosingSpan(text, pos, kind) {
  const ch = kind === 'strike' ? '~' : kind === 'code' ? '`' : '*';
  const n = kind === 'bold' || kind === 'strike' ? 2 : 1;
  let bold = false;
  let italic = false;
  let strike = false;
  let code = false;
  const isOn = () => (kind === 'bold' ? bold : kind === 'italic' ? italic
    : kind === 'strike' ? strike : code);
  let open = null;
  for (let i = 0; i < text.length;) {
    if (text[i] !== ch) {
      i += 1;
      continue;
    }
    let j = i;
    while (j < text.length && text[j] === ch) j += 1;
    if (i < pos && pos < j) return null;
    const before = isOn();
    if (ch === '*') {
      if (j - i >= 2) bold = !bold;
      if ((j - i) % 2 === 1) italic = !italic;
    } else if (ch === '~') {
      if (j - i >= 2) strike = !strike;
    } else if (j - i === 1) {
      code = !code;
    }
    const after = isOn();
    if (j <= pos) {
      if (!before && after) open = { start: i, len: j - i };
      else if (before && !after) open = null;
    } else if (before && !after) {
      if (!open) return null;
      return { openStart: open.start, openLen: open.len, closeStart: i, closeLen: j - i, n };
    }
    i = j;
  }
  return null;
}

/** Which toolbar formats apply at the caret/selection (Word-like active state). */
function computeFormats(text, start, end) {
  const caret = start === end;
  const starsStart = emphasisAt(text, start);
  const starsEnd = emphasisAt(text, end);
  const strikeStart = strikeAt(text, start);
  const strikeEnd = strikeAt(text, end);
  const codeStart = inlineCodeAt(text, start);
  const codeEnd = inlineCodeAt(text, end);

  const inlineOr = (atCaret, atEnd, marker) =>
    atCaret && atEnd || selectionHasWrap(text, start, end, marker);

  return {
    bold: caret ? starsStart.bold : inlineOr(starsStart.bold, starsEnd.bold, '**'),
    italic: caret ? starsStart.italic : inlineOr(starsStart.italic, starsEnd.italic, '*'),
    strike: caret ? strikeStart : inlineOr(strikeStart, strikeEnd, '~~'),
    code: caret ? codeStart : inlineOr(codeStart, codeEnd, '`'),
    codeblock: inFence(text, start),
    ...lineFormats(text, start, end)
  };
}

export default function Notes() {
  const notify = useNotify();
  const confirm = useConfirm();
  const [folder, setFolder] = useState('');
  const [selectedId, setSelectedId] = useState(null);
  const [draft, setDraft] = useState(null);
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState('');
  const [folderModal, setFolderModal] = useState(null);
  const [newNote, setNewNote] = useState(false);
  const editorRef = useRef(null);
  const placeholderRef = useRef(null);
  const lastSavedSig = useRef('');
  const [formats, setFormats] = useState({});

  const meta = useLoad(async () => {
    const [notes, folders] = await Promise.all([api.get('/api/notes'), api.get('/api/notes/folders')]);
    return { notes, folders };
  }, []);
  const notes = meta.data?.notes || [];
  const folders = meta.data?.folders || [];
  const visible = notes.filter((n) => !folder || n.folder === folder || n.folder.startsWith(folder + '/'));

  useEffect(() => {
    placeholderRef.current = null;
    if (!selectedId) {
      setDraft(null);
      return;
    }
    let alive = true;
    api
      .get('/api/notes/' + selectedId)
      .then((d) => {
        if (!alive) return;
        setDraft({ ...d.note, content: d.content, tagsText: (d.note.tags || []).join(', ') });
      })
      .catch((e) => notify(e.message, 'error'));
    return () => {
      alive = false;
    };
    // notify is stable; depending on it here used to re-fetch and overwrite
    // every keystroke because the hook returned a new function per render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId]);

  const contentDebounced = useDebounced(draft?.content || '', 350);
  const previewSeq = useRef(0);
  useEffect(() => {
    if (!draft) return;
    const seq = previewSeq.current + 1;
    previewSeq.current = seq;
    api
      .post('/api/markdown', { text: contentDebounced })
      .then((d) => {
        // Local requests can resolve out of order; a late preview for the
        // previous markdown (e.g. still italic after the markers were removed)
        // must never overwrite the newest one.
        if (previewSeq.current === seq) setPreview(d.html);
      })
      .catch(() => {});
  }, [contentDebounced, draft?.id]);

  const sigOf = (title, folder, tagsText, starred, content) =>
    JSON.stringify([
      (title || '').trim(),
      folder || '',
      (tagsText || '').split(',').map((t) => t.trim()).filter(Boolean),
      !!starred,
      content || ''
    ]);

  const signature = draft
    ? sigOf(draft.title, draft.folder, draft.tagsText, draft.starred, draft.content)
    : '';

  useEffect(() => {
    lastSavedSig.current = signature;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft?.id]);

  const save = async (silent = false) => {
    if (!draft) return;
    setSaving(true);
    try {
      const out = await api.put('/api/notes/' + draft.id, {
        title: draft.title || 'Ghi chú',
        folder: draft.folder || '',
        tags: (draft.tagsText || '').split(',').map((s) => s.trim()).filter(Boolean),
        starred: !!draft.starred,
        content: draft.content || ''
      });
      lastSavedSig.current = sigOf(out.note.title, out.note.folder,
        (out.note.tags || []).join(', '), out.note.starred, out.content);
      // Never write the response back into the draft: the user may have typed
      // while the PUT was in flight, and replacing the controlled value would
      // drop those keystrokes. The list only needs refreshed metadata.
      meta.reload();
      if (!silent) notify('Đã lưu ghi chú');
    } catch (e) {
      if (!silent) notify(e.message, 'error');
    } finally {
      setSaving(false);
    }
  };

  useEffect(() => {
    if (!draft || signature === lastSavedSig.current) return;
    const t = setTimeout(() => save(true), 1500);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature]);

  useEffect(() => {
    const onKey = (e) => {
      if (e.ctrlKey && e.key.toLowerCase() === 's') {
        e.preventDefault();
        save(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft]);

  const refreshFormats = (text, s, e) => {
    const el = editorRef.current;
    const t = text !== undefined ? text : (el ? el.value : (draft?.content ?? ''));
    const start = s !== undefined ? s : (el ? el.selectionStart : 0);
    const end = e !== undefined ? e : (el ? el.selectionEnd : start);
    setFormats(computeFormats(t, start, end));
  };

  useEffect(() => {
    const onSelectionChange = () => {
      const el = editorRef.current;
      if (el && document.activeElement === el) refreshFormats();
    };
    document.addEventListener('selectionchange', onSelectionChange);
    return () => document.removeEventListener('selectionchange', onSelectionChange);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft?.id]);

  useEffect(() => {
    if (draft) refreshFormats(draft.content, 0, 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft?.id]);

  useEffect(() => {
    // Recompute after any content change commit (covers programmatic loads and
    // keystrokes whose input event ran before the caret settled).
    if (draft) refreshFormats();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft?.content]);

  const mdClass = (key) => 'md-btn' + (formats[key] ? ' active' : '');

  const replaceRange = (from, to, insert, selectStart, selectEnd) => {
    const next = draft.content.slice(0, from) + insert + draft.content.slice(to);
    setDraft({ ...draft, content: next });
    requestAnimationFrame(() => {
      const el = editorRef.current;
      if (!el) return;
      el.focus();
      const max = next.length;
      el.setSelectionRange(Math.min(selectStart, max), Math.min(selectEnd, max));
      refreshFormats(next, selectStart, selectEnd);
    });
  };

  const countRun = (text, pos, step, ch) => {
    let n = 0;
    while (pos + step * n >= 0 && pos + step * n < text.length && text[pos + step * n] === ch) {
      n += 1;
    }
    return n;
  };

  /** Toggle an inline marker: selecting formatted text and pressing again unwraps it. */
  const toggleInline = (marker, placeholder) => {
    const el = editorRef.current;
    if (!el) return;
    const text = draft.content;
    const start = el.selectionStart;
    const end = el.selectionEnd;
    const len = marker.length;
    const ch = marker[0];

    // The untouched placeholder we inserted with the previous click: pressing the
    // same tool again removes the whole snippet, not just its markdown markers.
    const ph = placeholderRef.current;
    if (ph && ph.marker === marker && ph.start === start && ph.end === end
        && text.slice(start, end) === ph.text
        && text.slice(start - len, start) === marker
        && text.slice(end, end + len) === marker) {
      placeholderRef.current = null;
      replaceRange(start - len, end + len, '', start - len, start - len);
      return;
    }

    // Caret inside an existing styled span (e.g. typing inside **bold**):
    // pressing the same tool removes that span instead of nesting a new one.
    if (start === end) {
      const kind = marker === '**' ? 'bold' : marker === '*' ? 'italic'
        : marker === '~~' ? 'strike' : 'code';
      const active = kind === 'bold' ? emphasisAt(text, start).bold
        : kind === 'italic' ? emphasisAt(text, start).italic
          : kind === 'strike' ? strikeAt(text, start) : inlineCodeAt(text, start);
      if (active) {
        const span = findEnclosingSpan(text, start, kind);
        if (span) {
          const openCut = span.openStart + span.openLen - span.n;
          const newText = text.slice(0, openCut)
            + text.slice(span.openStart + span.openLen, span.closeStart)
            + text.slice(span.closeStart + span.n);
          placeholderRef.current = null;
          const caret = Math.max(0, start - span.n);
          replaceRange(0, text.length, newText, caret, caret);
          return;
        }
      }
    }

    if (end - start >= 2 * len) {
      const sel = text.slice(start, end);
      const plainStart = marker === '*'
        ? sel.startsWith('*') && !sel.startsWith('**')
        : sel.startsWith(marker);
      const plainEnd = marker === '*'
        ? sel.endsWith('*') && !sel.endsWith('**')
        : sel.endsWith(marker);
      if (plainStart && plainEnd) {
        replaceRange(start, end, sel.slice(len, sel.length - len), start, end - 2 * len);
        return;
      }
    }

    const leftRun = countRun(text, start - 1, -1, ch);
    const rightRun = countRun(text, end, 1, ch);
    // For '*', an odd run means the innermost emphasis is italic; an even run is
    // bold (**). This keeps italic and bold toggles independent when nested.
    const hasPair = marker === '*'
      ? leftRun % 2 === 1 && rightRun % 2 === 1
      : leftRun >= len && rightRun >= len;
    if (hasPair) {
      replaceRange(start - len, end + len, text.slice(start, end), start - len, end - len);
      return;
    }

    const picked = text.slice(start, end);
    if (picked) {
      placeholderRef.current = null;
      replaceRange(start, end, marker + picked + marker, start + len, start + len + picked.length);
    } else {
      replaceRange(start, end, marker + placeholder + marker,
          start + len, start + len + placeholder.length);
      placeholderRef.current = {
        marker,
        start: start + len,
        end: start + len + placeholder.length,
        text: placeholder
      };
    }
  };

  /** Toggle a line prefix; a different list/heading type replaces the old one. */
  const toggleLines = (marker) => {
    const el = editorRef.current;
    if (!el) return;
    const text = draft.content;
    const start = el.selectionStart;
    const end = el.selectionEnd;
    const from = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
    let to = text.indexOf('\n', Math.max(from, end));
    if (to === -1) to = text.length;
    const lines = text.slice(from, to).split('\n');
    const wanted = kindOfPrefix(marker);
    const nonEmpty = lines.filter((l) => l.trim().length > 0);
    const kinds = nonEmpty.map((l) => {
      const m = l.match(PREFIX_RE);
      return m ? kindOfPrefix(m[2]) : '';
    });
    const allHaveWanted = nonEmpty.length > 0 && kinds.every((k) => k === wanted);
    let numbered = 0;
    const nextLines = lines.map((line) => {
      if (!line.trim()) return line;
      const m = line.match(PREFIX_RE);
      const indent = m ? m[1] : (line.match(/^\s*/) || [''])[0];
      const body = m ? line.slice(m[0].length) : line.slice(indent.length);
      if (allHaveWanted) return indent + body;
      numbered += 1;
      return indent + (wanted === 'ol' ? numbered + '. ' : marker) + body;
    });
    const insert = nextLines.join('\n');
    replaceRange(from, to, insert, from, from + insert.length);
  };

  const insertBlock = (block) => {
    const el = editorRef.current;
    if (!el) return;
    const pos = el.selectionStart;
    const next = draft.content.slice(0, pos) + block + draft.content.slice(pos);
    setDraft({ ...draft, content: next });
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(pos + block.length, pos + block.length);
    });
  };

  const insertCodeBlock = () => {
    const el = editorRef.current;
    if (!el) return;
    const start = el.selectionStart;
    const end = el.selectionEnd;
    const picked = draft.content.slice(start, end) || 'code';
    const block = '\n```\n' + picked + '\n```\n';
    replaceRange(start, end, block, start + 5, start + 5 + picked.length);
  };

  const insertLink = () => {
    const el = editorRef.current;
    if (!el) return;
    const start = el.selectionStart;
    const end = el.selectionEnd;
    const picked = draft.content.slice(start, end) || 'mô tả';
    replaceRange(start, end, '[' + picked + '](https://)', start + 1, start + 1 + picked.length);
  };

  const createNote = async (title) => {
    if (!title.trim()) return;
    try {
      const newMeta = await api.post('/api/notes', {
        title: title.trim(),
        folder: folder || '',
        content: '# ' + title.trim() + '\n\n',
        tags: []
      });
      setNewNote(false);
      meta.reload();
      setSelectedId(newMeta.id);
      notify('Đã tạo ghi chú');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const deleteNote = async () => {
    if (!draft) return;
    if (!(await confirm(`Xoá vĩnh viễn "${draft.title}"?`, 'Xoá ghi chú'))) return;
    try {
      await api.del('/api/notes/' + draft.id);
      setSelectedId(null);
      meta.reload();
      notify('Đã xoá ghi chú');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const saveFolder = async (name, rename) => {
    if (!name.trim()) return;
    try {
      if (rename) {
        const path = await api.put('/api/notes/folders', { path: rename, newName: name.trim() });
        setFolder(path);
      } else {
        const path = await api.post('/api/notes/folders', {
          path: folder ? folder + '/' + name.trim() : name.trim()
        });
        setFolder(path);
      }
      setFolderModal(null);
      meta.reload();
      notify(rename ? 'Đã đổi tên thư mục' : 'Đã tạo thư mục');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const deleteFolder = async () => {
    if (!folder) {
      notify('Chọn một thư mục trước', 'error');
      return;
    }
    if (!(await confirm(`Xoá thư mục "${folder}" và toàn bộ ghi chú bên trong?`, 'Xoá thư mục'))) return;
    try {
      await api.post('/api/notes/folders/delete', { path: folder });
      setFolder('');
      setSelectedId(null);
      meta.reload();
      notify('Đã xoá thư mục');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page
      title="Ghi chú"
      subtitle="Markdown, thư mục lồng nhau và xem trước trực tiếp"
      actions={<Btn variant="primary" icon={Plus} onClick={() => setNewNote(true)}>Ghi chú mới</Btn>}
    >
      <div className="notes-layout">
        <aside className="notes-side">
          <div className="notes-tools">
            <span className="muted small">Thư mục</span>
            <div>
              <IconBtn icon={FolderPlus} title="Tạo thư mục con" onClick={() => setFolderModal({ rename: null })} />
              <IconBtn icon={Pencil} title="Đổi tên thư mục" onClick={() => folder ? setFolderModal({ rename: folder }) : notify('Chọn thư mục trước', 'error')} />
              <IconBtn icon={Trash2} title="Xoá thư mục" onClick={deleteFolder} />
            </div>
          </div>
          <button className={'folder-row' + (folder === '' ? ' active' : '')} onClick={() => setFolder('')}>
            <Folder size={14} /> Tất cả ghi chú
          </button>
          {folders.map((f) => (
            <button key={f} className={'folder-row depth-' + f.split('/').length + (folder === f ? ' active' : '')} onClick={() => setFolder(f)}>
              <Folder size={14} /> {f.split('/').pop()}
            </button>
          ))}

          <div className="notes-sep" />
          <div className="muted small note-count">{visible.length} ghi chú</div>
          <div className="note-list">
            {visible.length === 0 && <p className="muted small pad">Chưa có ghi chú.</p>}
            {visible.map((n) => (
              <button
                key={n.id}
                className={'note-item' + (selectedId === n.id ? ' active' : '')}
                onClick={() => setSelectedId(n.id)}
              >
                <div className="row-title">
                  {n.starred && <Star size={12} className="star" />} {n.title}
                </div>
                <div className="row-meta">
                  <span>{n.folder || '(gốc)'}</span>
                  <span>{fmt.dateTime(n.updatedAt)}</span>
                </div>
              </button>
            ))}
          </div>
        </aside>

        <section className="notes-main">
          {meta.loading && !meta.data ? (
            <Spinner />
          ) : meta.error && !meta.data ? (
            <ErrorBox error={meta.error} onRetry={meta.reload} />
          ) : !draft ? (
            <Empty title="Chọn hoặc tạo một ghi chú để bắt đầu" />
          ) : (
            <>
              <div className="note-head">
                <Input
                  className="note-title"
                  value={draft.title}
                  onChange={(e) => setDraft({ ...draft, title: e.target.value })}
                  placeholder="Tiêu đề"
                />
                <IconBtn
                  icon={Star}
                  title="Đánh dấu quan trọng"
                  className={draft.starred ? 'starred' : ''}
                  onClick={() => setDraft({ ...draft, starred: !draft.starred })}
                />
                <Btn icon={Save} variant="primary" onClick={save} disabled={saving}>
                  {saving ? 'Đang lưu…' : 'Lưu'}
                </Btn>
                <Btn variant="danger" icon={Trash2} onClick={deleteNote}>Xoá</Btn>
              </div>
              <div className="note-meta">
                <Select value={draft.folder} onChange={(e) => setDraft({ ...draft, folder: e.target.value })}>
                  <option value="">(gốc)</option>
                  {folders.map((f) => <option key={f} value={f}>{f}</option>)}
                </Select>
                <Input
                  placeholder="tags, cách nhau bằng dấu phẩy"
                  value={draft.tagsText}
                  onChange={(e) => setDraft({ ...draft, tagsText: e.target.value })}
                />
              </div>
              <div className="md-toolbar" onMouseDown={(e) => e.preventDefault()}>
                <button className={mdClass('bold')} aria-pressed={!!formats.bold} title="Đậm" style={{ fontWeight: 700 }} onClick={() => toggleInline('**', 'đậm')}>B</button>
                <button className={mdClass('italic')} aria-pressed={!!formats.italic} title="Nghiêng" style={{ fontStyle: 'italic' }} onClick={() => toggleInline('*', 'nghiêng')}>I</button>
                <button className={mdClass('strike')} aria-pressed={!!formats.strike} title="Gạch ngang" style={{ textDecoration: 'line-through' }} onClick={() => toggleInline('~~', 'gạch')}>S</button>
                <button className={mdClass('h1')} aria-pressed={!!formats.h1} title="Tiêu đề 1" onClick={() => toggleLines('# ')}>H1</button>
                <button className={mdClass('h2')} aria-pressed={!!formats.h2} title="Tiêu đề 2" onClick={() => toggleLines('## ')}>H2</button>
                <button className={mdClass('h3')} aria-pressed={!!formats.h3} title="Tiêu đề 3" onClick={() => toggleLines('### ')}>H3</button>
                <button className={mdClass('ul')} aria-pressed={!!formats.ul} title="Danh sách" onClick={() => toggleLines('- ')}>•</button>
                <button className={mdClass('ol')} aria-pressed={!!formats.ol} title="Danh sách số" onClick={() => toggleLines('1. ')}>1.</button>
                <button className={mdClass('task')} aria-pressed={!!formats.task} title="Checklist" onClick={() => toggleLines('- [ ] ')}>☐</button>
                <button className={mdClass('quote')} aria-pressed={!!formats.quote} title="Trích dẫn" onClick={() => toggleLines('> ')}>❝</button>
                <button className={mdClass('code')} aria-pressed={!!formats.code} title="Code inline" onClick={() => toggleInline('`', 'code')}>`x`</button>
                <button className={mdClass('codeblock')} aria-pressed={!!formats.codeblock} title="Khối code" onClick={insertCodeBlock}>{'{ }'}</button>
                <button className="md-btn" title="Liên kết" onClick={insertLink}>[ ]</button>
                <button className="md-btn" title="Bảng 2 cột" onClick={() => insertBlock('\n| Cột 1 | Cột 2 |\n| --- | --- |\n|  |  |\n')}>▦</button>
              </div>
              <div className="editor-split">
                <textarea
                  ref={editorRef}
                  className="note-editor"
                  value={draft.content}
                  onChange={(e) => {
                    const next = e.target.value;
                    placeholderRef.current = null;
                    setDraft({ ...draft, content: next });
                    refreshFormats(next, e.target.selectionStart, e.target.selectionEnd);
                  }}
                  onSelect={() => refreshFormats()}
                  onKeyUp={() => refreshFormats()}
                  onClick={() => refreshFormats()}
                  onFocus={() => refreshFormats()}
                  spellCheck={false}
                />
                <div className="note-preview md" dangerouslySetInnerHTML={{ __html: preview }} />
              </div>
            </>
          )}
        </section>
      </div>

      {folderModal && (
        <NameModal
          title={folderModal.rename ? 'Đổi tên thư mục' : 'Thư mục mới'}
          label="Tên thư mục"
          initial={folderModal.rename ? folderModal.rename.split('/').pop() : ''}
          onClose={() => setFolderModal(null)}
          onSubmit={(name) => saveFolder(name, folderModal.rename)}
        />
      )}
      {newNote && (
        <NameModal
          title="Ghi chú mới"
          label="Tiêu đề"
          initial=""
          onClose={() => setNewNote(false)}
          onSubmit={createNote}
        />
      )}
    </Page>
  );
}

function NameModal({ title, label, initial, onClose, onSubmit }) {
  const [value, setValue] = useState(initial);
  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <>
          <Btn onClick={onClose}>Hủy</Btn>
          <Btn variant="primary" onClick={() => onSubmit(value)}>Đồng ý</Btn>
        </>
      }
    >
      <Field label={label}>
        <Input value={value} onChange={(e) => setValue(e.target.value)} autoFocus onKeyDown={(e) => e.key === 'Enter' && onSubmit(value)} />
      </Field>
    </Modal>
  );
}
