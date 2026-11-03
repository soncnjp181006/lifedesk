import React, { useState } from 'react';
import { Check, FolderPlus, Pencil, Plus, Trash2, X } from 'lucide-react';
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
  const [group, setGroup] = useState('');
  const q = useDebounced(query);
  const [editing, setEditing] = useState(null);
  const [managing, setManaging] = useState(false);
  const { data, loading, error, reload } = useLoad(
    () => api.get('/api/tasks' + qs({ scope, q, status, priority, group })),
    [scope, q, status, priority, group]
  );
  /* [{name, tasks}] — saved names merged with whatever the tasks already carry. */
  const { data: groupRows, reload: reloadGroups } = useLoad(() => api.get('/api/tasks/groups'), []);
  const groups = groupRows || [];

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
      reloadGroups();
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
          <Select value={group} onChange={(e) => setGroup(e.target.value)}>
            <option value="">Mọi nhóm</option>
            {groups.map((g) => (
              <option key={g.name} value={g.name}>{g.name}</option>
            ))}
          </Select>
          <Btn icon={FolderPlus} onClick={() => setManaging(true)}>Nhóm</Btn>
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

      {editing && (
        <TaskModal
          initial={editing}
          groups={groups}
          onClose={() => setEditing(null)}
          onSave={save}
        />
      )}
      {managing && (
        <GroupsModal
          groups={groups}
          onClose={() => setManaging(false)}
          onChanged={() => { reloadGroups(); reload(); }}
        />
      )}
    </Page>
  );
}

function TaskModal({ initial, groups, onClose, onSave }) {
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
        <Field label="Nhóm" hint="Thêm nhóm ở nút “Nhóm” trên thanh công cụ">
          <Select value={form.group} onChange={set('group')}>
            <option value="">Chưa có nhóm</option>
            {/* keeps a group typed before this change visible while editing */}
            {form.group && !groups.some((g) => g.name === form.group) && (
              <option value={form.group}>{form.group}</option>
            )}
            {groups.map((g) => (
              <option key={g.name} value={g.name}>{g.name}</option>
            ))}
          </Select>
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
        <Field label="Ghi chú" className="wide">
          <Textarea rows={4} value={form.notes} onChange={set('notes')} />
        </Field>
      </div>
    </Modal>
  );
}

function GroupsModal({ groups, onClose, onChanged }) {
  const notify = useNotify();
  const [name, setName] = useState('');
  const [rename, setRename] = useState(null);
  const [busy, setBusy] = useState(false);

  const run = async (req, done) => {
    setBusy(true);
    try {
      await req();
      notify(done);
      onChanged();
      return true;
    } catch (e) {
      notify(e.message, 'error');
      return false;
    } finally {
      setBusy(false);
    }
  };

  const add = async (e) => {
    e.preventDefault();
    const v = name.trim();
    if (!v) {
      notify('Tên nhóm không được để trống', 'error');
      return;
    }
    if (await run(() => api.post('/api/tasks/groups', { name: v }), `Đã thêm nhóm “${v}”`)) setName('');
  };

  const applyRename = async (from) => {
    const to = rename.to.trim();
    if (!to || to === from) {
      setRename(null);
      return;
    }
    if (await run(() => api.put('/api/tasks/groups', { from, to }), `Đã đổi tên “${from}” thành “${to}”`)) setRename(null);
  };

  const remove = (g) =>
    run(() => api.del('/api/tasks/groups' + qs({ name: g.name })), `Đã xoá nhóm “${g.name}”`);

  return (
    <Modal title="Quản lý nhóm" onClose={onClose} footer={<Btn onClick={onClose}>Xong</Btn>}>
      <div className="group-box">
        <form className="inline-form" onSubmit={add}>
          <Input
            placeholder="Tên nhóm mới (Học tập, Gia đình…)"
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={busy}
            autoFocus
          />
          <Btn variant="primary" icon={Plus} type="submit" disabled={busy}>Thêm</Btn>
        </form>

        {groups.length === 0 ? (
          <Empty icon={FolderPlus} title="Chưa có nhóm nào" hint="Thêm nhóm để chọn nhanh khi tạo công việc" />
        ) : (
          <div className="list">
            {groups.map((g) => (
              <div className="list-row" key={g.name}>
                {rename?.from === g.name ? (
                  <>
                    <div className="grow">
                      <Input
                        value={rename.to}
                        disabled={busy}
                        autoFocus
                        onChange={(e) => setRename({ ...rename, to: e.target.value })}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') {
                            e.preventDefault();
                            applyRename(g.name);
                          } else if (e.key === 'Escape') {
                            setRename(null);
                          }
                        }}
                      />
                    </div>
                    <IconBtn icon={Check} title="Lưu tên mới" onClick={() => applyRename(g.name)} />
                    <IconBtn icon={X} title="Huỷ đổi tên" onClick={() => setRename(null)} />
                  </>
                ) : (
                  <>
                    <div className="grow">
                      <div className="row-title">{g.name}</div>
                      <div className="row-meta">{g.tasks > 0 ? `${g.tasks} công việc` : 'Chưa dùng'}</div>
                    </div>
                    <Badge tone={g.tasks > 0 ? 'info' : 'muted'}>{g.tasks}</Badge>
                    <IconBtn icon={Pencil} title="Đổi tên" onClick={() => setRename({ from: g.name, to: g.name })} />
                    <IconBtn
                      icon={Trash2}
                      title={g.tasks > 0 ? 'Xoá nhóm (còn công việc)' : 'Xoá nhóm'}
                      onClick={() => remove(g)}
                    />
                  </>
                )}
              </div>
            ))}
          </div>
        )}

        <p className="group-note">
          Nhóm rỗng xoá ngay; nhóm còn công việc phải chuyển hết công việc sang nhóm khác trước.
          Đổi tên sẽ cập nhật luôn mọi công việc đang thuộc nhóm đó.
        </p>
      </div>
    </Modal>
  );
}
