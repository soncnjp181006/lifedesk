import React, { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { api } from '../api.js';
import { useLoad } from '../hooks.js';
import {
  Btn,
  Card,
  DateField,
  Empty,
  ErrorBox,
  Field,
  IconBtn,
  Input,
  Modal,
  Page,
  Spinner,
  Textarea,
  fmt,
  useConfirm,
  useNotify
} from '../ui.jsx';

export default function Health() {
  const notify = useNotify();
  const confirm = useConfirm();
  const data = useLoad(() => api.get('/api/health'));
  const [form, setForm] = useState({ date: fmt.today(), sleepHours: '', exerciseMinutes: '', weightKg: '', note: '' });
  const [metricModal, setMetricModal] = useState(false);

  if (data.loading) return <Spinner />;
  if (data.error) return <ErrorBox error={data.error} onRetry={data.reload} />;

  const { entries, metrics, avgSleep7, avgExercise7, avgWeight7, disclaimer } = data.data;
  const sorted = [...entries].sort((a, b) => b.date.localeCompare(a.date));

  const submit = async (e) => {
    e.preventDefault();
    try {
      await api.post('/api/health', {
        date: form.date,
        sleepHours: form.sleepHours === '' ? null : Number(form.sleepHours),
        exerciseMinutes: form.exerciseMinutes === '' ? null : Number(form.exerciseMinutes),
        weightKg: form.weightKg === '' ? null : Number(form.weightKg),
        customMetrics: {},
        note: form.note
      });
      notify('Đã lưu chỉ số sức khỏe');
      data.reload();
      setForm({ date: fmt.today(), sleepHours: '', exerciseMinutes: '', weightKg: '', note: '' });
    } catch (err) {
      notify(err.message, 'error');
    }
  };

  const edit = (entry) => {
    setForm({
      date: entry.date,
      sleepHours: entry.sleepHours ?? '',
      exerciseMinutes: entry.exerciseMinutes ?? '',
      weightKg: entry.weightKg ?? '',
      note: entry.note || ''
    });
  };

  const remove = async (entry) => {
    if (!(await confirm(`Xoá dữ liệu ngày ${fmt.date(entry.date)}?`, 'Xoá dữ liệu sức khỏe'))) return;
    try {
      await api.del('/api/health/' + entry.date);
      data.reload();
      notify('Đã xoá dữ liệu');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page>
      <div className="stat-grid">
        <div className="stat-card static"><div className="stat-label">Giấc ngủ TB (7 ngày)</div><div className="stat-value">{avgSleep7 == null ? '—' : avgSleep7.toFixed(1) + ' giờ'}</div></div>
        <div className="stat-card static"><div className="stat-label">Vận động TB (7 ngày)</div><div className="stat-value">{avgExercise7 == null ? '—' : Math.round(avgExercise7) + ' phút'}</div></div>
        <div className="stat-card static"><div className="stat-label">Cân nặng TB (7 ngày)</div><div className="stat-value">{avgWeight7 == null ? '—' : avgWeight7.toFixed(1) + ' kg'}</div></div>
        <div className="stat-card static"><div className="stat-label">Số ngày ghi</div><div className="stat-value">{entries.length}</div></div>
      </div>

      <Card title="Ghi chỉ số">
        <form className="inline-form wrap" onSubmit={submit}>
          <DateField value={form.date} onChange={(v) => setForm({ ...form, date: v })} />
          <Input type="number" step="0.1" placeholder="Giấc ngủ (giờ)" value={form.sleepHours} onChange={(e) => setForm({ ...form, sleepHours: e.target.value })} />
          <Input type="number" placeholder="Vận động (phút)" value={form.exerciseMinutes} onChange={(e) => setForm({ ...form, exerciseMinutes: e.target.value })} />
          <Input type="number" step="0.1" placeholder="Cân nặng (kg)" value={form.weightKg} onChange={(e) => setForm({ ...form, weightKg: e.target.value })} />
          <Input placeholder="Ghi chú" value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
          <Btn variant="primary" type="submit" icon={Plus}>Lưu ngày</Btn>
        </form>
        <p className="muted small">{disclaimer}</p>
      </Card>

      <Card title="Lịch sử" actions={<Btn icon={Plus} onClick={() => setMetricModal(true)}>Chỉ số riêng</Btn>}>
        {sorted.length === 0 ? (
          <Empty title="Chưa có dữ liệu sức khỏe" />
        ) : (
          <div className="table">
            <div className="tr th"><span>Ngày</span><span>Giấc ngủ</span><span>Vận động</span><span>Cân nặng</span><span>Ghi chú</span><span /></div>
            {sorted.map((e) => (
              <div className="tr" key={e.id} onClick={() => edit(e)}>
                <span>{fmt.date(e.date)}</span>
                <span>{e.sleepHours ?? '—'}</span>
                <span>{e.exerciseMinutes ?? '—'}</span>
                <span>{e.weightKg ?? '—'}</span>
                <span className="muted">{e.note || '—'}</span>
                <span onClick={(ev) => ev.stopPropagation()}><IconBtn icon={Trash2} title="Xoá" onClick={() => remove(e)} /></span>
              </div>
            ))}
          </div>
        )}
        {metrics.length > 0 && (
          <div className="chip-wrap pad-top">
            {metrics.map((m) => <span className="chip" key={m.id}>{m.name}{m.unit ? ' · ' + m.unit : ''}</span>)}
          </div>
        )}
      </Card>

      {metricModal && (
        <MetricModal
          onClose={() => setMetricModal(false)}
          onSaved={() => { setMetricModal(false); data.reload(); notify('Đã thêm chỉ số'); }}
          onError={(m) => notify(m, 'error')}
        />
      )}
    </Page>
  );
}

function MetricModal({ onClose, onSaved, onError }) {
  const [form, setForm] = useState({ name: '', unit: '', target: '' });
  const submit = async () => {
    if (!form.name.trim()) {
      onError('Nhập tên chỉ số');
      return;
    }
    try {
      await api.post('/api/health/metrics', {
        name: form.name.trim(),
        unit: form.unit,
        target: form.target === '' ? null : Number(form.target)
      });
      onSaved();
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <Modal title="Chỉ số riêng" onClose={onClose} footer={<><Btn onClick={onClose}>Hủy</Btn><Btn variant="primary" onClick={submit}>Lưu</Btn></>}>
      <div className="form-grid">
        <Field label="Tên"><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus /></Field>
        <Field label="Đơn vị"><Input value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} placeholder="mmHg, bước…" /></Field>
        <Field label="Mục tiêu"><Input type="number" value={form.target} onChange={(e) => setForm({ ...form, target: e.target.value })} /></Field>
      </div>
    </Modal>
  );
}
