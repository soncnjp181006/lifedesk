import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, CalendarDays, ChevronLeft, ChevronRight, X } from 'lucide-react';

export const AppCtx = createContext(null);
export const useApp = () => useContext(AppCtx);

export function Page({ fill = false, flush = false, children }) {
  return (
    <div className={'page' + (fill ? ' page-fill' : '') + (flush ? ' page-flush' : '')}>
      {children}
    </div>
  );
}

export function Card({ title, actions, className = '', children }) {
  return (
    <section className={'card ' + className}>
      {(title || actions) && (
        <header className="card-head">
          <h2>{title}</h2>
          <div className="card-actions">{actions}</div>
        </header>
      )}
      {children}
    </section>
  );
}

export function Btn({ variant = 'default', icon: Icon, children, ...props }) {
  return (
    <button className={'btn btn-' + variant} {...props}>
      {Icon && <Icon size={15} />}
      {children}
    </button>
  );
}

export function IconBtn({ icon: Icon, title, className = '', ...props }) {
  return (
    <button className={'icon-btn ' + className} title={title} aria-label={title} {...props}>
      <Icon size={16} />
    </button>
  );
}

export function Field({ label, hint, children }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Input(props) {
  return <input className="input" {...props} />;
}

export function Select({ children, ...props }) {
  return (
    <select className="input" {...props}>
      {children}
    </select>
  );
}

export function Textarea(props) {
  return <textarea className="input textarea" {...props} />;
}

export function Badge({ tone = 'default', children }) {
  return <span className={'badge badge-' + tone}>{children}</span>;
}

export function Empty({ icon: Icon, title, hint }) {
  return (
    <div className="empty">
      {Icon && <Icon size={28} strokeWidth={1.5} />}
      <strong>{title}</strong>
      {hint && <span>{hint}</span>}
    </div>
  );
}

export function Spinner({ label = 'Đang tải…' }) {
  return <div className="spinner">{label}</div>;
}

export function ErrorBox({ error, onRetry }) {
  return (
    <div className="error-box">
      <AlertTriangle size={16} />
      <span>{error}</span>
      {onRetry && (
        <button className="link" onClick={onRetry}>
          Thử lại
        </button>
      )}
    </div>
  );
}

export function Modal({ title, onClose, children, footer }) {
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal">
        <header className="modal-head">
          <h3>{title}</h3>
          <IconBtn icon={X} title="Đóng" onClick={onClose} />
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-foot">{footer}</footer>}
      </div>
    </div>
  );
}

export function Progress({ value, max = 100, tone }) {
  const pct = max > 0 ? Math.min(100, Math.max(0, (value / max) * 100)) : 0;
  return (
    <div className="progress">
      <span style={{ width: pct + '%' }} className={tone ? 'tone-' + tone : ''} />
    </div>
  );
}

export function Tabs({ value, onChange, items }) {
  return (
    <div className="tabs">
      {items.map((it) => (
        <button
          key={it.value}
          className={'tab' + (value === it.value ? ' active' : '')}
          onClick={() => onChange(it.value)}
        >
          {it.label}
        </button>
      ))}
    </div>
  );
}

export function ConfirmHost() {
  const app = useApp();
  if (!app.confirmState) return null;
  const { title, message, resolve } = app.confirmState;
  const done = (v) => {
    resolve(v);
    app.setConfirmState(null);
  };
  return (
    <Modal
      title={title || 'Xác nhận'}
      onClose={() => done(false)}
      footer={
        <>
          <Btn onClick={() => done(false)}>Hủy</Btn>
          <Btn variant="danger" onClick={() => done(true)}>
            Đồng ý
          </Btn>
        </>
      }
    >
      <p className="confirm-text">{message}</p>
    </Modal>
  );
}

export function useConfirm() {
  const app = useApp();
  return useCallback(
    (message, title) => new Promise((resolve) => app.setConfirmState({ message, title, resolve })),
    [app.setConfirmState]
  );
}

export function useNotify() {
  const app = useApp();
  return useCallback((message, type = 'ok') => app.notify(message, type), [app.notify]);
}

const pad2 = (n) => String(n).padStart(2, '0');

export function isoToDisplay(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
  return m ? `${m[3]}/${m[2]}/${m[1]}` : '';
}

function dateFromIso(iso) {
  if (!iso) return null;
  const p = iso.split('-').map(Number);
  return p.length === 3 && p.every(Number.isFinite) ? new Date(p[0], p[1] - 1, p[2]) : null;
}

function parseDateInput(raw) {
  const s = (raw || '').trim();
  if (!s) return '';
  let y;
  let mo;
  let d;
  let m = /^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$/.exec(s);
  if (m) {
    d = +m[1];
    mo = +m[2];
    y = +m[3];
  } else {
    m = /^(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})$/.exec(s);
    if (!m) return null;
    y = +m[1];
    mo = +m[2];
    d = +m[3];
  }
  const date = new Date(y, mo - 1, d);
  if (date.getFullYear() !== y || date.getMonth() !== mo - 1 || date.getDate() !== d) return null;
  return `${y}-${pad2(mo)}-${pad2(d)}`;
}

/**
 * Date input for JavaFX WebView: <input type="date"> falls back to free text
 * there, so this pairs a dd/mm/yyyy text field with a real calendar popover.
 * Value in/out is ISO (yyyy-mm-dd) to match the REST API.
 */
export function DateField({ value = '', onChange, placeholder = 'dd/mm/yyyy', clearable = true, className = '' }) {
  const [text, setText] = useState(isoToDisplay(value));
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState(null);
  const [view, setView] = useState(() => dateFromIso(value) || new Date());
  const wrapRef = useRef(null);
  const btnRef = useRef(null);

  useEffect(() => {
    setText(isoToDisplay(value));
    const d = dateFromIso(value);
    if (d) setView(d);
  }, [value]);

  useEffect(() => {
    if (!open) return undefined;
    const rect = btnRef.current ? btnRef.current.getBoundingClientRect() : null;
    if (rect) {
      const width = 288;
      setPos({
        left: Math.max(10, Math.min(rect.left, window.innerWidth - width - 10)),
        top: Math.max(10, Math.min(rect.bottom + 6, window.innerHeight - 330)),
        width
      });
    }
    const close = (e) => {
      if (e.target && e.target.closest && e.target.closest('.date-pop')) return;
      if (wrapRef.current && wrapRef.current.contains(e.target)) return;
      setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  const commit = () => {
    const iso = parseDateInput(text);
    if (iso === null) {
      setText(isoToDisplay(value));
      return;
    }
    if (iso !== (value || '')) onChange && onChange(iso);
    setText(isoToDisplay(iso));
  };

  const choose = (iso) => {
    onChange && onChange(iso);
    setText(isoToDisplay(iso));
    setOpen(false);
  };

  const y = view.getFullYear();
  const mo = view.getMonth();
  const firstOffset = (new Date(y, mo, 1).getDay() + 6) % 7;
  const daysInMonth = new Date(y, mo + 1, 0).getDate();
  const now = new Date();
  const todayIso = `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
  const cells = [];
  for (let i = 0; i < firstOffset; i += 1) cells.push(null);
  for (let d = 1; d <= daysInMonth; d += 1) cells.push(`${y}-${pad2(mo + 1)}-${pad2(d)}`);

  return (
    <div className={'date-field ' + className} ref={wrapRef}>
      <input
        className="input date-input"
        value={text}
        placeholder={placeholder}
        inputMode="numeric"
        onChange={(e) => setText(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            commit();
          } else if (e.key === 'Escape') {
            setOpen(false);
            setText(isoToDisplay(value));
          }
        }}
      />
      <button type="button" tabIndex={-1} className="date-btn" ref={btnRef} title="Chọn ngày" onClick={() => setOpen((o) => !o)}>
        <CalendarDays size={15} />
      </button>
      {open && pos && createPortal(
        <div className="date-pop" style={pos} onMouseDown={(e) => e.stopPropagation()}>
          <div className="date-pop-head">
            <button type="button" className="icon-btn" title="Tháng trước" onClick={() => setView(new Date(y, mo - 1, 1))}>
              <ChevronLeft size={15} />
            </button>
            <strong>Tháng {mo + 1} · {y}</strong>
            <button type="button" className="icon-btn" title="Tháng sau" onClick={() => setView(new Date(y, mo + 1, 1))}>
              <ChevronRight size={15} />
            </button>
          </div>
          <div className="date-week">
            {['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'].map((d) => <span key={d}>{d}</span>)}
          </div>
          <div className="date-grid">
            {cells.map((iso, i) => (iso === null
              ? <span key={'blank' + i} />
              : (
                <button
                  type="button"
                  key={iso}
                  className={'date-cell' + (iso === value ? ' selected' : '') + (iso === todayIso ? ' today' : '')}
                  onClick={() => choose(iso)}
                >
                  {Number(iso.slice(8))}
                </button>
              )))}
          </div>
          <div className="date-pop-foot">
            <button type="button" className="link" onClick={() => choose(todayIso)}>Hôm nay</button>
            {clearable && value && (
              <button type="button" className="link" onClick={() => { onChange && onChange(''); setText(''); setOpen(false); }}>
                Xoá
              </button>
            )}
          </div>
        </div>,
        document.body
      )}
    </div>
  );
}

export const fmt = {
  money: (v) =>
    new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 }).format(Number(v || 0)) + ' đ',
  date: (v) => (v ? new Date(v + 'T00:00:00').toLocaleDateString('vi-VN') : '—'),
  shortDate: (v) => (v ? new Date(v + 'T00:00:00').toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' }) : '—'),
  dateTime: (v) => (v ? new Date(v).toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' }) : '—'),
  weekday: (v) => {
    const d = new Date(v + 'T00:00:00');
    return ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'][d.getDay()];
  },
  today: () => {
    const d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  },
  month: () => {
    const d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
  }
};

export const PRIORITY = {
  LOW: { label: 'Thấp', tone: 'muted' },
  MEDIUM: { label: 'Trung bình', tone: 'info' },
  HIGH: { label: 'Cao', tone: 'warn' },
  URGENT: { label: 'Khẩn', tone: 'danger' }
};

export const STATUS = {
  TODO: { label: 'Cần làm', tone: 'info' },
  IN_PROGRESS: { label: 'Đang làm', tone: 'warn' },
  DONE: { label: 'Hoàn thành', tone: 'ok' },
  CANCELLED: { label: 'Đã hủy', tone: 'muted' }
};

export const TX = {
  INCOME: { label: 'Thu nhập', tone: 'ok' },
  EXPENSE: { label: 'Chi tiêu', tone: 'danger' }
};

export const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
export const DAY_LABEL = { MONDAY: 'T2', TUESDAY: 'T3', WEDNESDAY: 'T4', THURSDAY: 'T5', FRIDAY: 'T6', SATURDAY: 'T7', SUNDAY: 'CN' };
