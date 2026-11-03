import React, { useState } from 'react';
import { Book, Clapperboard, Gamepad2, Music, Plus, Trash2, Tv } from 'lucide-react';
import { api } from '../api.js';
import { useLoad } from '../hooks.js';
import {
  Badge,
  Btn,
  Card,
  Empty,
  ErrorBox,
  Field,
  IconBtn,
  Input,
  Modal,
  Page,
  Progress,
  Select,
  Spinner,
  fmt,
  useConfirm,
  useNotify
} from '../ui.jsx';

const KIND = {
  BOOK: { label: 'Sách', icon: Book },
  MOVIE: { label: 'Phim', icon: Clapperboard },
  SERIES: { label: 'Series', icon: Tv },
  GAME: { label: 'Game', icon: Gamepad2 },
  MUSIC: { label: 'Nhạc', icon: Music },
  COURSE: { label: 'Khóa học', icon: Book }
};
const STATUS = {
  PLANNED: { label: 'Dự định', tone: 'muted' },
  IN_PROGRESS: { label: 'Đang xem/đọc', tone: 'warn' },
  DONE: { label: 'Hoàn thành', tone: 'ok' },
  DROPPED: { label: 'Bỏ dở', tone: 'danger' }
};

export default function Library() {
  const notify = useNotify();
  const confirm = useConfirm();
  const data = useLoad(() => api.get('/api/library'));
  const [modal, setModal] = useState(false);
  const [kind, setKind] = useState('BOOK');

  if (data.loading) return <Spinner />;
  if (data.error) return <ErrorBox error={data.error} onRetry={data.reload} />;
  const { items, stats } = data.data;
  const shown = items.filter((i) => i.kind === kind);

  const tick = async (item) => {
    try {
      await api.post(`/api/library/tick/${item.id}`);
      data.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const update = async (item, patch) => {
    try {
      await api.put('/api/library/' + item.id, patch);
      data.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const remove = async (item) => {
    if (!(await confirm(`Xoá "${item.title}" khỏi thư viện?`, 'Xoá mục'))) return;
    try {
      await api.del('/api/library/' + item.id);
      data.reload();
      notify('Đã xoá');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page>
      <div className="toolbar">
        <div className="tabs">
          {Object.entries(KIND).map(([k, v]) => (
            <button key={k} className={'tab' + (kind === k ? ' active' : '')} onClick={() => setKind(k)}>
              <v.icon size={14} /> {v.label}
            </button>
          ))}
        </div>
        <Btn variant="primary" icon={Plus} onClick={() => setModal(true)}>Thêm mục</Btn>
      </div>

      {shown.length === 0 ? <Empty title="Chưa có mục nào" hint="Thêm sách, phim, game… để theo dõi" /> : (
        <div className="grid-3">
          {shown.map((item) => (
            <Card key={item.id} title={item.title} actions={<IconBtn icon={Trash2} title="Xoá" onClick={() => remove(item)} />}>
              <div className="kv">
                <span className="muted">{item.creator || '—'} {item.year ? '· ' + item.year : ''}</span>
                <Badge tone={STATUS[item.status]?.tone}>{STATUS[item.status]?.label}</Badge>
              </div>
              <Progress value={item.progressCurrent} max={item.progressTotal || 100} tone="ok" />
              <div className="kv small muted">
                <span>{item.progressCurrent}/{item.progressTotal || 100}</span>
                <span>{item.rating ? item.rating + '/10' : 'chưa chấm'}</span>
              </div>
              <div className="inline-form">
                <Btn onClick={() => tick(item)}>+1 tiến độ</Btn>
                <Select value={item.status} onChange={(e) => update(item, { status: e.target.value })}>
                  {Object.entries(STATUS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
                </Select>
                <Select value={String(item.rating || 0)} onChange={(e) => update(item, { rating: Number(e.target.value) })}>
                  {Array.from({ length: 11 }, (_, i) => <option key={i} value={i}>{i}/10</option>)}
                </Select>
              </div>
              {item.completedAt && <div className="muted small">Xong ngày {fmt.date(item.completedAt)}</div>}
            </Card>
          ))}
        </div>
      )}

      {modal && (
        <AddModal
          onClose={() => setModal(false)}
          onSaved={() => { setModal(false); data.reload(); notify('Đã thêm vào thư viện'); }}
          onError={(m) => notify(m, 'error')}
        />
      )}
    </Page>
  );
}

function AddModal({ onClose, onSaved, onError }) {
  const [form, setForm] = useState({ title: '', kind: 'BOOK', creator: '', year: new Date().getFullYear(), coverUrl: '' });
  const submit = async () => {
    if (!form.title.trim()) return onError('Nhập tên');
    try {
      await api.post('/api/library', { ...form, year: Number(form.year) || 0 });
      onSaved();
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <Modal title="Thêm vào thư viện" onClose={onClose} footer={<><Btn onClick={onClose}>Hủy</Btn><Btn variant="primary" onClick={submit}>Lưu</Btn></>}>
      <div className="form-grid">
        <Field label="Tên" wide><Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} autoFocus /></Field>
        <Field label="Loại">
          <Select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
            {Object.entries(KIND).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </Select>
        </Field>
        <Field label="Tác giả / đạo diễn"><Input value={form.creator} onChange={(e) => setForm({ ...form, creator: e.target.value })} /></Field>
        <Field label="Năm"><Input type="number" value={form.year} onChange={(e) => setForm({ ...form, year: e.target.value })} /></Field>
        <Field label="Ảnh bìa (URL)"><Input value={form.coverUrl} onChange={(e) => setForm({ ...form, coverUrl: e.target.value })} /></Field>
      </div>
    </Modal>
  );
}
