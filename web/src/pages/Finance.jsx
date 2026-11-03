import React, { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Plus, Trash2 } from 'lucide-react';
import { api, qs } from '../api.js';
import { useLoad } from '../hooks.js';
import {
  Badge,
  Btn,
  DateField,
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
  TX,
  fmt,
  useConfirm,
  useNotify
} from '../ui.jsx';

const METHODS = { CASH: 'Tiền mặt', BANK_CARD: 'Thẻ ngân hàng', BANK_TRANSFER: 'Chuyển khoản', E_WALLET: 'Ví điện tử', OTHER: 'Khác' };

function shiftMonth(m, delta) {
  const [y, mo] = m.split('-').map(Number);
  const d = new Date(y, mo - 1 + delta, 1);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
}

export default function Finance() {
  const notify = useNotify();
  const confirm = useConfirm();
  const [month, setMonth] = useState(fmt.month());
  const [budgetModal, setBudgetModal] = useState(false);
  const [debtModal, setDebtModal] = useState(false);
  const [catName, setCatName] = useState('');
  const [catType, setCatType] = useState('EXPENSE');

  const data = useLoad(() => api.get('/api/finance' + qs({ month })), [month]);
  const tx = useLoad(() => api.get('/api/finance/transactions' + qs({ month })), [month]);

  useEffect(() => {
    setCatType('EXPENSE');
  }, []);

  if (data.loading || tx.loading) return <Spinner />;
  if (data.error) return <ErrorBox error={data.error} onRetry={data.reload} />;

  const { summary, budgets, series, categories, debts } = data.data;
  const transactions = tx.data || [];
  const maxSeries = Math.max(1, ...series.map((d) => Number(d.expense || 0)));

  const reloadAll = () => {
    data.reload();
    tx.reload();
  };

  const removeTx = async (t) => {
    if (!(await confirm('Xoá giao dịch "' + t.description + '"?', 'Xoá giao dịch'))) return;
    try {
      await api.del('/api/finance/transactions/' + t.id);
      reloadAll();
      notify('Đã xoá giao dịch');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const addCategory = async () => {
    if (!catName.trim()) return;
    try {
      await api.post('/api/finance/categories', { name: catName.trim(), type: catType });
      setCatName('');
      data.reload();
      notify('Đã thêm danh mục');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const settle = async (d) => {
    try {
      await api.post(`/api/finance/debts/${d.id}/settle`);
      data.reload();
      notify('Đã tất toán khoản nợ');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page>
      <div className="stat-grid">
        <div className="stat-card static">
          <div className="stat-label">Thu nhập</div>
          <div className="stat-value tone-ok">{fmt.money(summary.income)}</div>
        </div>
        <div className="stat-card static">
          <div className="stat-label">Chi tiêu</div>
          <div className="stat-value tone-danger">{fmt.money(summary.expense)}</div>
        </div>
        <div className="stat-card static">
          <div className="stat-label">Số dư</div>
          <div className={'stat-value ' + (Number(summary.net) < 0 ? 'tone-danger' : 'tone-ok')}>{fmt.money(summary.net)}</div>
        </div>
        <div className="stat-card static">
          <div className="stat-label">Giao dịch</div>
          <div className="stat-value">{transactions.length}</div>
        </div>
      </div>

      <Card title={'Giao dịch tháng ' + month} actions={
        <div className="month-nav">
          <IconBtn icon={ChevronLeft} title="Tháng trước" onClick={() => setMonth(shiftMonth(month, -1))} />
          <strong>{month}</strong>
          <IconBtn icon={ChevronRight} title="Tháng sau" onClick={() => setMonth(shiftMonth(month, 1))} />
        </div>
      }>
        <TxForm month={month} categories={categories} onSaved={reloadAll} onError={(m) => notify(m, 'error')} />
        <div className="spark finance-spark">
          {series.map((d) => (
            <span key={d.date} title={`${fmt.shortDate(d.date)}: chi ${fmt.money(d.expense)}`} style={{ height: Math.max(2, (Number(d.expense) / maxSeries) * 100) + '%' }} />
          ))}
        </div>
        {transactions.length === 0 ? (
          <Empty title="Chưa có giao dịch trong tháng" hint="Thêm giao dịch ở biểu mẫu phía trên" />
        ) : (
          <div className="table">
            <div className="tr th">
              <span>Ngày</span><span>Loại</span><span>Số tiền</span><span>Danh mục</span><span>Mô tả</span><span />
            </div>
            {transactions.map((t) => (
              <div className="tr" key={t.id}>
                <span>{fmt.date(t.date)}</span>
                <span><Badge tone={TX[t.type]?.tone}>{TX[t.type]?.label}</Badge></span>
                <span className={t.type === 'INCOME' ? 'tone-ok' : 'tone-danger'}>{fmt.money(t.amount)}</span>
                <span>{categories.find((c) => c.id === t.categoryId)?.name || '—'}</span>
                <span className="muted">{t.description || '—'}</span>
                <span><IconBtn icon={Trash2} title="Xoá" onClick={() => removeTx(t)} /></span>
              </div>
            ))}
          </div>
        )}
      </Card>

      <div className="grid-2">
        <Card title="Ngân sách" actions={<Btn icon={Plus} onClick={() => setBudgetModal(true)}>Thêm</Btn>}>
          {budgets.length === 0 ? (
            <Empty title="Chưa đặt ngân sách cho tháng này" />
          ) : (
            <div className="budget-list">
              {budgets.map((b) => (
                <div className="budget" key={b.budget.id + b.budget.categoryId}>
                  <div className="kv">
                    <span>{b.categoryName}</span>
                    <span className="muted">{fmt.money(b.spent)} / {fmt.money(b.budget.limitAmount)}</span>
                  </div>
                  <Progress value={Math.min(100, b.usedRatio * 100)} tone={b.usedRatio > 1 ? 'danger' : b.usedRatio > 0.8 ? 'warn' : 'ok'} />
                </div>
              ))}
            </div>
          )}
        </Card>

        <Card title="Danh mục" actions={
          <div className="inline-form">
            <Select value={catType} onChange={(e) => setCatType(e.target.value)}>
              <option value="EXPENSE">Chi</option>
              <option value="INCOME">Thu</option>
            </Select>
            <Input placeholder="Tên danh mục" value={catName} onChange={(e) => setCatName(e.target.value)} />
            <Btn icon={Plus} onClick={addCategory}>Thêm</Btn>
          </div>
        }>
          <div className="chip-wrap">
            {categories.map((c) => <span key={c.id} className="chip">{c.name} · {c.type === 'INCOME' ? 'thu' : 'chi'}</span>)}
          </div>
        </Card>

        <Card title="Khoản nợ" actions={<Btn icon={Plus} onClick={() => setDebtModal(true)}>Thêm</Btn>}>
          {debts.length === 0 ? (
            <Empty title="Không có khoản nợ" />
          ) : (
            <ul className="row-list">
              {debts.map((d) => (
                <li key={d.id}>
                  <span className="grow">{d.party} · {fmt.money(d.amount)}</span>
                  <Badge tone={d.direction === 'PAYABLE' ? 'danger' : 'ok'}>{d.direction === 'PAYABLE' ? 'Phải trả' : 'Phải thu'}</Badge>
                  {d.status === 'OPEN' ? <Btn variant="ghost" onClick={() => settle(d)}>Tất toán</Btn> : <Badge tone="muted">Đã xong</Badge>}
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      {budgetModal && (
        <BudgetModal
          month={month}
          categories={categories}
          onClose={() => setBudgetModal(false)}
          onSaved={() => { setBudgetModal(false); data.reload(); notify('Đã thêm ngân sách'); }}
          onError={(m) => notify(m, 'error')}
        />
      )}
      {debtModal && (
        <DebtModal
          onClose={() => setDebtModal(false)}
          onSaved={() => { setDebtModal(false); data.reload(); notify('Đã thêm khoản nợ'); }}
          onError={(m) => notify(m, 'error')}
        />
      )}
    </Page>
  );
}

function TxForm({ month, categories, onSaved, onError }) {
  const [form, setForm] = useState({ date: month + '-01', type: 'EXPENSE', amount: '', categoryId: '', method: 'CASH', description: '' });
  useEffect(() => {
    const today = new Date();
    const inMonth = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') === month;
    setForm((f) => ({ ...f, date: inMonth ? fmt.today() : month + '-01' }));
  }, [month]);
  const filteredCats = categories.filter((c) => c.type === form.type);
  const submit = async (e) => {
    e.preventDefault();
    if (!form.amount || !form.categoryId) {
      onError('Nhập số tiền và chọn danh mục');
      return;
    }
    try {
      await api.post('/api/finance/transactions', { ...form, amount: Number(form.amount) });
      setForm({ ...form, amount: '', description: '' });
      onSaved();
    } catch (err) {
      onError(err.message);
    }
  };
  return (
    <form className="inline-form tx-form" onSubmit={submit}>
      <Select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value, categoryId: '' })}>
        <option value="EXPENSE">Chi tiêu</option>
        <option value="INCOME">Thu nhập</option>
      </Select>
      <Input type="number" placeholder="Số tiền" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
      <Select value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}>
        <option value="">Danh mục…</option>
        {filteredCats.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </Select>
      <Select value={form.method} onChange={(e) => setForm({ ...form, method: e.target.value })}>
        {Object.entries(METHODS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
      </Select>
      <DateField value={form.date} onChange={(v) => setForm({ ...form, date: v })} />
      <Input placeholder="Mô tả" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
      <Btn variant="primary" icon={Plus} type="submit">Thêm</Btn>
    </form>
  );
}

function BudgetModal({ month, categories, onClose, onSaved, onError }) {
  const [form, setForm] = useState({ month, categoryId: '', limit: '', recurring: false });
  const submit = async () => {
    if (!form.categoryId || !form.limit) {
      onError('Chọn danh mục và nhập hạn mức');
      return;
    }
    try {
      await api.post('/api/finance/budgets', { ...form, limit: Number(form.limit) });
      onSaved();
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <Modal title="Ngân sách mới" onClose={onClose} footer={<><Btn onClick={onClose}>Hủy</Btn><Btn variant="primary" onClick={submit}>Lưu</Btn></>}>
      <div className="form-grid">
        <Field label="Tháng"><Input value={form.month} onChange={(e) => setForm({ ...form, month: e.target.value })} /></Field>
        <Field label="Danh mục">
          <Select value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}>
            <option value="">Chọn…</option>
            {categories.filter((c) => c.type === 'EXPENSE').map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
        </Field>
        <Field label="Hạn mức"><Input type="number" value={form.limit} onChange={(e) => setForm({ ...form, limit: e.target.value })} /></Field>
        <Field label="Lặp lại">
          <label className="checkbox"><input type="checkbox" checked={form.recurring} onChange={(e) => setForm({ ...form, recurring: e.target.checked })} /> Áp dụng hằng tháng</label>
        </Field>
      </div>
    </Modal>
  );
}

function DebtModal({ onClose, onSaved, onError }) {
  const [form, setForm] = useState({ direction: 'PAYABLE', party: '', amount: '', incurredDate: fmt.today(), dueDate: '', note: '' });
  const submit = async () => {
    if (!form.party || !form.amount) {
      onError('Nhập tên đối tác và số tiền');
      return;
    }
    try {
      await api.post('/api/finance/debts', { ...form, amount: Number(form.amount), incurredDate: form.incurredDate || null, dueDate: form.dueDate || null });
      onSaved();
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <Modal title="Khoản nợ mới" onClose={onClose} footer={<><Btn onClick={onClose}>Hủy</Btn><Btn variant="primary" onClick={submit}>Lưu</Btn></>}>
      <div className="form-grid">
        <Field label="Hướng">
          <Select value={form.direction} onChange={(e) => setForm({ ...form, direction: e.target.value })}>
            <option value="PAYABLE">Mình phải trả</option>
            <option value="RECEIVABLE">Mình phải thu</option>
          </Select>
        </Field>
        <Field label="Đối tác"><Input value={form.party} onChange={(e) => setForm({ ...form, party: e.target.value })} /></Field>
        <Field label="Số tiền"><Input type="number" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} /></Field>
        <Field label="Hạn"><DateField value={form.dueDate} onChange={(v) => setForm({ ...form, dueDate: v })} /></Field>
        <Field label="Ghi chú" wide><Input value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></Field>
      </div>
    </Modal>
  );
}
