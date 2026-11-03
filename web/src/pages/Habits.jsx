import React, { useMemo, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { api, qs } from '../api.js';
import { useLoad } from '../hooks.js';
import {
  Badge,
  Btn,
  DateField,
  Card,
  DAYS,
  DAY_LABEL,
  Empty,
  ErrorBox,
  Field,
  IconBtn,
  Input,
  Modal,
  Page,
  Progress,
  Spinner,
  Textarea,
  fmt,
  useConfirm,
  useNotify
} from '../ui.jsx';

function mondayOf(date) {
  const d = new Date(date + 'T00:00:00');
  const day = d.getDay();
  const diff = day === 0 ? -6 : 1 - day;
  d.setDate(d.getDate() + diff);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

const GOAL_STATUS = {
  ACTIVE: { label: 'Đang làm', tone: 'warn' },
  DONE: { label: 'Hoàn thành', tone: 'ok' },
  CANCELLED: { label: 'Đã hủy', tone: 'muted' }
};

export default function Habits() {
  const notify = useNotify();
  const confirm = useConfirm();
  const [start, setStart] = useState(mondayOf(fmt.today()));
  const [habitModal, setHabitModal] = useState(false);
  const [goalModal, setGoalModal] = useState(false);
  const days = useMemo(() => Array.from({ length: 7 }, (_, i) => {
    const d = new Date(start + 'T00:00:00');
    d.setDate(d.getDate() + i);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }), [start]);

  const data = useLoad(async () => {
    const [week, goals] = await Promise.all([
      api.get('/api/habits/week' + qs({ start })),
      api.get('/api/habits')
    ]);
    return { week, goals: goals.goals || [] };
  }, [start]);

  if (data.loading) return <Spinner />;
  if (data.error) return <ErrorBox error={data.error} onRetry={data.reload} />;

  const week = data.data.week;
  const weekGoals = (data.data.goals || [])
    .filter((g) => g.targetDate && g.targetDate >= days[0] && g.targetDate <= days[6])
    .sort((a, b) => a.targetDate.localeCompare(b.targetDate));

  const toggle = async (habitId, date, completed) => {
    try {
      await api.post(`/api/habits/${habitId}/completion`, {
        date,
        completed,
        reason: date < fmt.today() ? 'Tick bù từ giao diện web' : ''
      });
      data.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const removeHabit = async (h) => {
    if (!(await confirm(`Xoá thói quen "${h.name}"?`, 'Xoá thói quen'))) return;
    try {
      await api.del('/api/habits/' + h.id);
      data.reload();
      notify('Đã xoá thói quen');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const removeGoal = async (g) => {
    if (!(await confirm(`Xoá mục tiêu "${g.title}"?`, 'Xoá mục tiêu'))) return;
    try {
      await api.del('/api/habits/goals/' + g.id);
      data.reload();
      notify('Đã xoá mục tiêu');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page>
      <Card title="Tuần này" actions={
        <div className="inline-form">
          <Btn variant="primary" icon={Plus} onClick={() => setHabitModal(true)}>Thói quen mới</Btn>
          <Btn onClick={() => setStart(mondayOf(fmt.today()))}>Tuần hiện tại</Btn>
          <DateField value={start} onChange={(v) => v && setStart(mondayOf(v))} />
        </div>
      }>
        {week.length === 0 && weekGoals.length === 0 ? (
          <Empty title="Chưa có thói quen" hint="Tạo thói quen đầu tiên để bắt đầu theo dõi" />
        ) : (
          <>
            {week.length > 0 && (
              <div className="habit-table">
                <div className="habit-row head">
                  <span>Thói quen</span>
                  {days.map((d) => (
                    <span key={d} className="habit-day-head">
                      {DAY_LABEL[DAYS[(new Date(d + 'T00:00:00').getDay() + 6) % 7]]}<br /><small>{fmt.shortDate(d)}</small>
                    </span>
                  ))}
                  <span>Chuỗi</span>
                  <span />
                </div>
                {week.map((h) => {
                  const done = h.week.filter((d) => d.completed).length;
                  return (
                    <div className="habit-row" key={h.id}>
                      <span className="grow">{h.name}</span>
                      {h.week.map((d) => {
                        const future = d.date > fmt.today();
                        return (
                          <button
                            key={d.date}
                            className={'habit-cell' + (d.completed ? ' on' : '') + (d.scheduled ? '' : ' off')}
                            disabled={future || !d.scheduled}
                            title={future ? 'Chưa tới ngày' : d.completed ? 'Bỏ đánh dấu' : 'Đánh dấu hoàn thành'}
                            onClick={() => toggle(h.id, d.date, !d.completed)}
                          >
                            {d.completed ? '✓' : ''}
                          </button>
                        );
                      })}
                      <Badge tone="info">{done}/7</Badge>
                      <IconBtn icon={Trash2} title="Xoá thói quen" onClick={() => removeHabit(h)} />
                    </div>
                  );
                })}
              </div>
            )}

            {weekGoals.length > 0 && (
              <div className="week-goals">
                <div className="week-goals-title">Mục tiêu đến hạn trong tuần</div>
                {weekGoals.map((g) => {
                  const idx = (new Date(g.targetDate + 'T00:00:00').getDay() + 6) % 7;
                  return (
                    <div className="week-goal" key={g.id}>
                      <span className="week-goal-day">{DAY_LABEL[DAYS[idx]]} · {fmt.shortDate(g.targetDate)}</span>
                      <span className="grow">{g.title}</span>
                      <Badge tone={GOAL_STATUS[g.status]?.tone}>{GOAL_STATUS[g.status]?.label || g.status}</Badge>
                      <span className="muted small">{g.manualProgress || 0}%</span>
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}
      </Card>

      <Card title="Mục tiêu" actions={<Btn icon={Plus} onClick={() => setGoalModal(true)}>Mục tiêu mới</Btn>}>
        {data.data.goals.length === 0 ? (
          <Empty title="Chưa có mục tiêu" />
        ) : (
          <div className="goal-grid">
            {data.data.goals.map((g) => (
              <div className="goal-card" key={g.id}>
                <div className="kv">
                  <strong>{g.title}</strong>
                  <IconBtn icon={Trash2} title="Xoá" onClick={() => removeGoal(g)} />
                </div>
                {g.description && <p className="muted small">{g.description}</p>}
                <Progress value={g.manualProgress || 0} tone="ok" />
                <div className="kv small muted">
                  <span><Badge tone={GOAL_STATUS[g.status]?.tone}>{GOAL_STATUS[g.status]?.label || g.status}</Badge></span>
                  <span>{g.targetDate ? fmt.date(g.targetDate) : 'Không hạn'}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {habitModal && (
        <HabitModal
          onClose={() => setHabitModal(false)}
          onSaved={() => { setHabitModal(false); data.reload(); notify('Đã thêm thói quen'); }}
          onError={(m) => notify(m, 'error')}
        />
      )}
      {goalModal && (
        <GoalModal
          onClose={() => setGoalModal(false)}
          onSaved={() => { setGoalModal(false); data.reload(); notify('Đã thêm mục tiêu'); }}
          onError={(m) => notify(m, 'error')}
        />
      )}
    </Page>
  );
}

function HabitModal({ onClose, onSaved, onError }) {
  const [form, setForm] = useState({ name: '', description: '', days: [] });
  const toggleDay = (d) => setForm({ ...form, days: form.days.includes(d) ? form.days.filter((x) => x !== d) : [...form.days, d] });
  const submit = async () => {
    if (!form.name.trim()) {
      onError('Nhập tên thói quen');
      return;
    }
    try {
      await api.post('/api/habits', form);
      onSaved();
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <Modal title="Thói quen mới" size="wide" onClose={onClose} footer={<><Btn onClick={onClose}>Hủy</Btn><Btn variant="primary" onClick={submit}>Lưu</Btn></>}>
      <div className="form-grid form-grid-stack">
        <Field label="Tên"><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus /></Field>
        <Field label="Mô tả"><Textarea rows={7} placeholder="Mô tả ngắn: làm thế nào, cần chuẩn bị gì…" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
        <Field label="Ngày tập" hint="Để trống = mọi ngày">
          <div className="chip-wrap">
            {DAYS.map((d) => (
              <button type="button" key={d} className={'chip toggle' + (form.days.includes(d) ? ' on' : '')} onClick={() => toggleDay(d)}>
                {DAY_LABEL[d]}
              </button>
            ))}
          </div>
        </Field>
      </div>
    </Modal>
  );
}

function GoalModal({ onClose, onSaved, onError }) {
  const [form, setForm] = useState({ title: '', description: '', targetDate: '' });
  const submit = async () => {
    if (!form.title.trim()) {
      onError('Nhập tên mục tiêu');
      return;
    }
    try {
      await api.post('/api/habits/goals', { ...form, targetDate: form.targetDate || null });
      onSaved();
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <Modal title="Mục tiêu mới" size="wide" onClose={onClose} footer={<><Btn onClick={onClose}>Hủy</Btn><Btn variant="primary" onClick={submit}>Lưu</Btn></>}>
      <div className="form-grid form-grid-stack">
        <Field label="Tên mục tiêu"><Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} autoFocus /></Field>
        <Field label="Mô tả"><Textarea rows={7} placeholder="Mô tả mục tiêu, lý do muốn đạt, mốc thời gian…" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
        <div className="field-narrow">
          <Field label="Hạn"><DateField value={form.targetDate} onChange={(v) => setForm({ ...form, targetDate: v })} /></Field>
        </div>
      </div>
    </Modal>
  );
}
