import React, { useState } from 'react';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { api, qs } from '../api.js';
import { useDebounced, useLoad } from '../hooks.js';
import {
  Badge,
  Btn,
  DateField,
  Empty,
  ErrorBox,
  Field,
  IconBtn,
  Input,
  Modal,
  Page,
  PRIORITY,
  STATUS,
  Select,
  Spinner,
  Tabs,
  Textarea,
  fmt,
  useConfirm,
  useNotify
} from '../ui.jsx';

const SCOPES = [
  { value: 'all', label: 'Tất cả' },
  { value: 'today', label: 'Hôm nay' },
  { value: 'overdue', label: 'Quá hạn' },
  { value: 'upcoming', label: 'Sắp tới' }
];

const EMPTY = {
  title: '',
  notes: '',
  priority: 'MEDIUM',
  status: 'TODO',
  dueDate: '',
  dueTime: '',
  group: '',
  tags: '',
  recurrenceType: 'NONE',
  recurrenceInterval: 1
};

export default function Tasks() {
  const notify = useNotify();
  const confirm = useConfirm();
  const [scope, setScope] = useState('all');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [priority, setPriority] = useState('');
  const q = useDebounced(query);
  const [editing, setEditing] = useState(null);
  const { data, loading, error, reload } = useLoad(
    () => api.get('/api/tasks' + qs({ scope, q, status, priority })),
    [scope, q, status, priority]
  );

  const toggle = async (t) => {
    try {
      await api.post(`/api/tasks/${t.id}/status`, { status: t.status === 'DONE' ? 'TODO' : 'DONE' });
      reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const remove = async (t) => {
    if (!(await confirm(`Xoá vĩnh viễn "${t.title}"?`, 'Xoá công việc'))) return;
    try {
      await api.del(`/api/tasks/${t.id}`);
      notify('Đã xoá công việc');
      reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const openNew = () => setEditing({ ...EMPTY, id: null });
  const openEdit = (t) =>
    setEditing({
      id: t.id,
      title: t.title,
      notes: t.notes || '',
      priority: t.priority,
      status: t.status,
      dueDate: t.dueDate || '',
      dueTime: t.dueTime ? t.dueTime.slice(0, 5) : '',
      group: t.group || '',
      tags: (t.tags || []).join(', '),
      recurrenceType: t.recurrence?.type || 'NONE',
      recurrenceInterval: t.recurrence?.interval || 1
    });

  const save = async (form) => {
    if (!form.title.trim()) {
      notify('Tiêu đề không được để trống', 'error');
      return;
    }
    const body = {
      title: form.title.trim(),
      notes: form.notes,
      priority: form.priority,
      status: form.status,
      dueDate: form.dueDate || null,
      dueTime: form.dueTime || null,
      group: form.group.trim(),
      tags: form.tags.split(',').map((s) => s.trim()).filter(Boolean),
      recurrence: {
        type: form.recurrenceType,
        interval: Number(form.recurrenceInterval) || 1,
        daysOfWeek: []
      }
    };
    try {
      if (form.id) {
        await api.put(`/api/tasks/${form.id}`, body);
        notify('Đã cập nhật công việc');
      } else {
        await api.post('/api/tasks', body);
        notify('Đã thêm công việc');
      }
      setEditing(null);
      reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page fill>
      <div className="toolbar">
        <Tabs value={scope} onChange={setScope} items={SCOPES} />
        <div className="toolbar-fields">
          <Input placeholder="Tìm việc…" value={query} onChange={(e) => setQuery(e.target.value)} />
          <Select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Mọi trạng thái</option>
            {Object.entries(STATUS).map(([k, v]) => (
              <option key={k} value={k}>{v.label}</option>
            ))}
          </Select>
          <Select value={priority} onChange={(e) => setPriority(e.target.value)}>
            <option value="">Mọi ưu tiên</option>
            {Object.entries(PRIORITY).map(([k, v]) => (
              <option key={k} value={k}>{v.label}</option>
            ))}
          </Select>
          <Btn variant="primary" icon={Plus} onClick={openNew}>Thêm công việc</Btn>
        </div>
      </div>

      {loading ? (
        <Spinner />
      ) : error ? (
        <ErrorBox error={error} onRetry={reload} />
      ) : data.length === 0 ? (
        <Empty title="Chưa có công việc nào" hint="Bấm “Thêm công việc” để bắt đầu" />
      ) : (
        <div className="list">
          {data.map((t) => (
            <div key={t.id} className={'list-row' + (t.status === 'DONE' ? ' done' : '')}>
              <button className={'check' + (t.status === 'DONE' ? ' on' : '')} onClick={() => toggle(t)} title="Đánh dấu hoàn thành">
                {t.status === 'DONE' ? '✓' : ''}
              </button>
              <div className="grow">
                <div className="row-title">{t.title}</div>
                <div className="row-meta">
                  {t.group && <span>{t.group}</span>}
                  {t.tags?.map((tag) => <span className="chip" key={tag}>#{tag}</span>)}
                  {t.notes && <span className="muted">{t.notes.slice(0, 80)}</span>}
                </div>
              </div>
              <Badge tone={PRIORITY[t.priority]?.tone}>{PRIORITY[t.priority]?.label}</Badge>
              <Badge tone={STATUS[t.status]?.tone}>{STATUS[t.status]?.label}</Badge>
              <span className={'due' + (t.overdue ? ' overdue' : '')}>
                {t.dueDate ? fmt.date(t.dueDate) : '—'}
                {t.dueTime ? ' ' + t.dueTime.slice(0, 5) : ''}
              </span>
              <IconBtn icon={Pencil} title="Sửa" onClick={() => openEdit(t)} />
              <IconBtn icon={Trash2} title="Xoá" onClick={() => remove(t)} />
            </div>
          ))}
        </div>
      )}

      {editing && <TaskModal initial={editing} onClose={() => setEditing(null)} onSave={save} />}
    </Page>
  );
}

function TaskModal({ initial, onClose, onSave }) {
  const [form, setForm] = useState(initial);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  return (
    <Modal
      title={form.id ? 'Sửa công việc' : 'Công việc mới'}
      onClose={onClose}
      footer={
        <>
          <Btn onClick={onClose}>Hủy</Btn>
          <Btn variant="primary" onClick={() => onSave(form)}>Lưu</Btn>
        </>
      }
    >
      <div className="form-grid">
        <Field label="Tiêu đề" wide>
          <Input value={form.title} onChange={set('title')} autoFocus placeholder="Việc cần làm" />
        </Field>
        <Field label="Ưu tiên">
          <Select value={form.priority} onChange={set('priority')}>
            {Object.entries(PRIORITY).map(([k, v]) => (
              <option key={k} value={k}>{v.label}</option>
            ))}
          </Select>
        </Field>
        <Field label="Trạng thái">
          <Select value={form.status} onChange={set('status')}>
            {Object.entries(STATUS).map(([k, v]) => (
              <option key={k} value={k}>{v.label}</option>
            ))}
          </Select>
        </Field>
        <Field label="Hạn">
          <DateField value={form.dueDate} onChange={(v) => setForm({ ...form, dueDate: v })} />
        </Field>
        <Field label="Giờ">
          <Input type="time" value={form.dueTime} onChange={set('dueTime')} />
        </Field>
        <Field label="Nhóm">
          <Input value={form.group} onChange={set('group')} placeholder="Công việc, Học tập…" />
        </Field>
        <Field label="Tags">
          <Input value={form.tags} onChange={set('tags')} placeholder="tag1, tag2" />
        </Field>
        <Field label="Lặp lại">
          <Select value={form.recurrenceType} onChange={set('recurrenceType')}>
            <option value="NONE">Không lặp</option>
            <option value="DAILY">Hằng ngày</option>
            <option value="WEEKLY">Hằng tuần</option>
            <option value="MONTHLY">Hằng tháng</option>
          </Select>
        </Field>
        <Field label="Mỗi">
          <Input type="number" min="1" value={form.recurrenceInterval} onChange={set('recurrenceInterval')} />
        </Field>
        <Field label="Ghi chú" wide>
          <Textarea rows={4} value={form.notes} onChange={set('notes')} />
        </Field>
      </div>
    </Modal>
  );
}
