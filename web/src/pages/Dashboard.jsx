import React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  BookOpen,
  Check,
  HeartPulse,
  ListTodo,
  NotebookPen,
  Wallet
} from 'lucide-react';
import { api } from '../api.js';
import { useLoad } from '../hooks.js';
import {
  Badge,
  Btn,
  Card,
  Empty,
  ErrorBox,
  Page,
  Progress,
  Spinner,
  fmt,
  useNotify
} from '../ui.jsx';

export default function Dashboard() {
  const nav = useNavigate();
  const notify = useNotify();
  const { data, loading, error, reload } = useLoad(() => api.get('/api/dashboard'));

  if (loading) return <Spinner />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;

  const { tasks, notes, finance, habits, learning, health } = data;
  const maxSeries = Math.max(1, ...finance.series.map((d) => Number(d.expense || 0)));

  const toggleHabit = async (h) => {
    try {
      await api.post(`/api/habits/${h.id}/completion`, {
        date: fmt.today(),
        completed: !h.completedToday,
        reason: 'Tick nhanh từ Tổng quan'
      });
      reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page
      title="Tổng quan"
      subtitle="Tổng quan công việc, tài chính, thói quen và học tập của bạn"
      actions={<Btn icon={ListTodo} onClick={() => nav('/tasks')}>Mở công việc</Btn>}
    >
      <div className="stat-grid">
        <StatCard icon={ListTodo} label="Việc hôm nay" value={tasks.dueToday} hint={`${tasks.overdue} việc quá hạn`} onClick={() => nav('/tasks')} />
        <StatCard icon={Wallet} label="Số dư tháng" value={fmt.money(finance.summary.net)} hint={`thu ${fmt.money(finance.summary.income)} · chi ${fmt.money(finance.summary.expense)}`} tone={Number(finance.summary.net) < 0 ? 'danger' : 'ok'} onClick={() => nav('/finance')} />
        <StatCard icon={BookOpen} label="Thẻ đến hạn" value={learning.dueCards} hint={`${learning.courses} khóa học`} onClick={() => nav('/learning')} />
        <StatCard icon={NotebookPen} label="Ghi chú" value={notes.count} hint="mở tab Ghi chú để viết tiếp" onClick={() => nav('/notes')} />
      </div>

      <div className="grid-2">
        <Card title="Việc hôm nay" actions={<Btn variant="ghost" onClick={() => nav('/tasks')}>Xem tất cả</Btn>}>
          {tasks.dueTodayList.length === 0 ? (
            <Empty icon={Check} title="Không có việc đến hạn hôm nay" />
          ) : (
            <ul className="row-list">
              {tasks.dueTodayList.map((t) => (
                <li key={t.id}>
                  <span className="grow">{t.title}</span>
                  <Badge tone={t.overdue ? 'danger' : 'info'}>{t.overdue ? 'Quá hạn' : 'Hôm nay'}</Badge>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Quá hạn" actions={<Badge tone={tasks.overdue ? 'danger' : 'ok'}>{tasks.overdue}</Badge>}>
          {tasks.overdueList.length === 0 ? (
            <Empty icon={Check} title="Không có việc quá hạn" />
          ) : (
            <ul className="row-list">
              {tasks.overdueList.map((t) => (
                <li key={t.id}>
                  <span className="grow">{t.title}</span>
                  <span className="muted small">trễ {fmt.shortDate(t.dueDate)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Sắp tới (7 ngày)">
          {tasks.upcoming.length === 0 ? (
            <Empty icon={ListTodo} title="Trống trong 7 ngày tới" />
          ) : (
            <ul className="row-list">
              {tasks.upcoming.map((t) => (
                <li key={t.id}>
                  <span className="grow">{t.title}</span>
                  <span className="muted small">{fmt.shortDate(t.dueDate)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Thói quen hôm nay">
          {habits.length === 0 ? (
            <Empty icon={Check} title="Chưa có thói quen" hint="Tạo thói quen ở tab Thói quen & Mục tiêu" />
          ) : (
            <ul className="row-list">
              {habits.slice(0, 6).map((h) => (
                <li key={h.id}>
                  <button className={'check' + (h.completedToday ? ' on' : '')} onClick={() => toggleHabit(h)}>
                    {h.completedToday && <Check size={13} />}
                  </button>
                  <span className="grow">{h.name}</span>
                  <span className="muted small">chuỗi {h.streak}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Chi tiêu 30 ngày" actions={<Btn variant="ghost" onClick={() => nav('/finance')}>Tài chính</Btn>}>
          <div className="spark">
            {finance.series.map((d) => (
              <span
                key={d.date}
                title={`${fmt.shortDate(d.date)}: ${fmt.money(d.expense)}`}
                style={{ height: Math.max(2, (Number(d.expense) / maxSeries) * 100) + '%' }}
              />
            ))}
          </div>
          <div className="spark-foot">
            <span>{fmt.money(finance.summary.expense)} chi tháng này</span>
            <span className="muted">Ngân sách: {finance.budgets.length} mục</span>
          </div>
        </Card>

        <Card title="Học tập" actions={<Btn variant="ghost" onClick={() => nav('/learning')}>Mở</Btn>}>
          <div className="kv">
            <span>Thẻ đến hạn</span>
            <strong>{learning.dueCards}</strong>
          </div>
          {learning.decks.slice(0, 4).map((d) => (
            <div className="kv" key={d.id}>
              <span>{d.name}</span>
              <span className="muted">{d.dueCount} thẻ</span>
            </div>
          ))}
          {learning.decks.length === 0 && <p className="muted small">Chưa có bộ thẻ.</p>}
        </Card>

        <Card title="Sức khỏe 7 ngày" actions={<Btn variant="ghost" onClick={() => nav('/health')}>Chi tiết</Btn>}>
          <div className="kv">
            <span><HeartPulse size={14} /> Giấc ngủ TB</span>
            <strong>{health.avgSleep7 == null ? '—' : health.avgSleep7.toFixed(1) + ' giờ'}</strong>
          </div>
          <div className="kv">
            <span>Vận động TB</span>
            <strong>{health.avgExercise7 == null ? '—' : Math.round(health.avgExercise7) + ' phút'}</strong>
          </div>
          <div className="kv">
            <span>Số ngày ghi</span>
            <strong>{health.entries}</strong>
          </div>
        </Card>
      </div>
    </Page>
  );
}

function StatCard({ icon: Icon, label, value, hint, tone, onClick }) {
  return (
    <button className="stat-card" onClick={onClick}>
      <span className={'stat-icon' + (tone ? ' tone-' + tone : '')}><Icon size={18} /></span>
      <div>
        <div className="stat-label">{label}</div>
        <div className={'stat-value' + (tone ? ' tone-' + tone : '')}>{value}</div>
        {hint && <div className="stat-hint">{hint}</div>}
      </div>
    </button>
  );
}
