import React, { useEffect, useState } from 'react';
import { BookOpen, Check, Plus, Trash2, X } from 'lucide-react';
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
  Tabs,
  fmt,
  useConfirm,
  useNotify
} from '../ui.jsx';

const COURSE_STATUS = {
  PLANNED: { label: 'Dự định', tone: 'muted' },
  IN_PROGRESS: { label: 'Đang học', tone: 'warn' },
  PAUSED: { label: 'Tạm dừng', tone: 'info' },
  COMPLETED: { label: 'Hoàn thành', tone: 'ok' }
};

export default function Learning() {
  const notify = useNotify();
  const confirm = useConfirm();
  const [tab, setTab] = useState('courses');
  const [courseModal, setCourseModal] = useState(false);
  const [deckModal, setDeckModal] = useState(false);
  const [cardModal, setCardModal] = useState(false);
  const [selectedDeck, setSelectedDeck] = useState(null);
  const data = useLoad(() => api.get('/api/learning'));
  const cards = useLoad(() => selectedDeck ? api.get(`/api/learning/decks/${selectedDeck}/cards`) : Promise.resolve([]), [selectedDeck]);

  if (data.loading) return <Spinner />;
  if (data.error) return <ErrorBox error={data.error} onRetry={data.reload} />;
  const { courses, decks, dueCards, sessions } = data.data;

  const progressCourse = async (c, delta) => {
    try {
      await api.put('/api/learning/courses/' + c.id, { progressPercent: Math.max(0, Math.min(100, (c.progressPercent || 0) + delta)) });
      data.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const removeCourse = async (c) => {
    if (!(await confirm(`Xoá khóa học "${c.title}"?`, 'Xoá khóa học'))) return;
    try {
      await api.del('/api/learning/courses/' + c.id);
      data.reload();
      notify('Đã xoá khóa học');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const removeDeck = async (d) => {
    if (!(await confirm(`Xoá bộ thẻ "${d.name}" và toàn bộ thẻ bên trong?`, 'Xoá bộ thẻ'))) return;
    try {
      await api.del('/api/learning/decks/' + d.id);
      if (selectedDeck === d.id) setSelectedDeck(null);
      data.reload();
      notify('Đã xoá bộ thẻ');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const removeCard = async (c) => {
    try {
      await api.del('/api/learning/cards/' + c.id);
      cards.reload();
      data.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page
      title="Học tập"
      subtitle="Khóa học, flashcard và phiên ôn tập"
      actions={
        tab === 'courses'
          ? <Btn variant="primary" icon={Plus} onClick={() => setCourseModal(true)}>Khóa học mới</Btn>
          : tab === 'cards'
            ? <Btn variant="primary" icon={Plus} onClick={() => setDeckModal(true)}>Bộ thẻ mới</Btn>
            : null
      }
    >
      <div className="toolbar">
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            { value: 'courses', label: 'Khóa học (' + courses.length + ')' },
            { value: 'cards', label: 'Flashcard · ' + dueCards + ' đến hạn' },
            { value: 'sessions', label: 'Phiên học (' + sessions.length + ')' }
          ]}
        />
      </div>

      {tab === 'courses' && (
        <div className="grid-2">
          {courses.length === 0 && <Empty title="Chưa có khóa học" />}
          {courses.map((c) => (
            <Card key={c.id} title={c.title} actions={<IconBtn icon={Trash2} title="Xoá" onClick={() => removeCourse(c)} />}>
              <div className="kv">
                <span className="muted">{c.source || 'Không nguồn'}</span>
                <Badge tone={COURSE_STATUS[c.status]?.tone}>{COURSE_STATUS[c.status]?.label}</Badge>
              </div>
              <Progress value={c.progressPercent} tone="ok" />
              <div className="kv small muted">
                <span>{c.progressPercent}%</span>
                <span>{c.tags?.map((t) => '#' + t).join(' ') || ''}</span>
              </div>
              <div className="inline-form">
                <Btn onClick={() => progressCourse(c, -10)}>−10%</Btn>
                <Btn onClick={() => progressCourse(c, 10)}>+10%</Btn>
              </div>
            </Card>
          ))}
        </div>
      )}

      {tab === 'cards' && (
        <div className="learning-grid">
          <Card title="Bộ thẻ">
            {decks.length === 0 ? <Empty title="Chưa có bộ thẻ" /> : (
              <div className="deck-list">
                {decks.map((d) => (
                  <button key={d.id} className={'deck' + (selectedDeck === d.id ? ' active' : '')} onClick={() => setSelectedDeck(d.id)}>
                    <span className="grow">{d.name}</span>
                    <Badge tone={d.dueCount ? 'warn' : 'ok'}>{d.dueCount} đến hạn</Badge>
                    <span className="muted small">{d.cardCount} thẻ</span>
                    <span onClick={(e) => { e.stopPropagation(); removeDeck(d); }}><Trash2 size={14} /></span>
                  </button>
                ))}
              </div>
            )}
          </Card>
          <Card title={selectedDeck ? 'Thẻ trong bộ' : 'Chọn một bộ thẻ'} actions={selectedDeck && <Btn icon={Plus} onClick={() => setCardModal(true)}>Thêm thẻ</Btn>}>
            {cards.loading ? <Spinner /> : !selectedDeck ? <Empty title="Chọn bộ thẻ bên trái" /> : (cards.data || []).length === 0 ? <Empty title="Bộ thẻ trống" /> : (
              <>
                <ReviewBox cards={cards.data} onReviewed={() => { cards.reload(); data.reload(); }} onError={(m) => notify(m, 'error')} />
                <ul className="row-list">
                  {cards.data.map((c) => (
                    <li key={c.id}>
                      <span className="grow"><strong>{c.front}</strong> <span className="muted">→ {c.back}</span></span>
                      <span className="muted small">hộp {c.box}</span>
                      <IconBtn icon={Trash2} title="Xoá thẻ" onClick={() => removeCard(c)} />
                    </li>
                  ))}
                </ul>
              </>
            )}
          </Card>
        </div>
      )}

      {tab === 'sessions' && (
        <Card title="Phiên học gần đây">
          {sessions.length === 0 ? <Empty title="Chưa có phiên học" /> : (
            <ul className="row-list">
              {sessions.map((s) => (
                <li key={s.id}>
                  <span className="grow">{s.topic || 'Phiên học'}</span>
                  <span className="muted small">{fmt.date(s.date)}</span>
                  <Badge tone="info">{s.minutes} phút</Badge>
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}

      {courseModal && (
        <CourseModal onClose={() => setCourseModal(false)} onSaved={() => { setCourseModal(false); data.reload(); notify('Đã thêm khóa học'); }} onError={(m) => notify(m, 'error')} />
      )}
      {deckModal && (
        <DeckModal courses={courses} onClose={() => setDeckModal(false)} onSaved={(d) => { setDeckModal(false); setSelectedDeck(d.id); data.reload(); notify('Đã thêm bộ thẻ'); }} onError={(m) => notify(m, 'error')} />
      )}
      {cardModal && selectedDeck && (
        <CardModal deckId={selectedDeck} onClose={() => setCardModal(false)} onSaved={() => { setCardModal(false); cards.reload(); data.reload(); notify('Đã thêm thẻ'); }} onError={(m) => notify(m, 'error')} />
      )}
    </Page>
  );
}

function ReviewBox({ cards, onReviewed, onError }) {
  const due = cards.filter((c) => !c.dueDate || c.dueDate <= fmt.today());
  const [idx, setIdx] = useState(0);
  const [revealed, setRevealed] = useState(false);
  useEffect(() => {
    setIdx(0);
    setRevealed(false);
  }, [cards.length]);
  const card = due[idx];
  if (!card) return null;
  const answer = async (correct) => {
    try {
      await api.post(`/api/learning/cards/${card.id}/review`, { correct });
      setIdx((i) => i + 1);
      setRevealed(false);
      onReviewed();
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <div className="review-box">
      <div className="muted small">Ôn tập · còn {Math.max(0, due.length - idx)} thẻ đến hạn</div>
      <div className="review-front">{card.front}</div>
      {revealed ? (
        <>
          <div className="review-back">{card.back}</div>
          <div className="inline-form">
            <Btn variant="danger" icon={X} onClick={() => answer(false)}>Sai</Btn>
            <Btn variant="primary" icon={Check} onClick={() => answer(true)}>Đúng</Btn>
          </div>
        </>
      ) : (
        <Btn onClick={() => setRevealed(true)}>Hiện đáp án</Btn>
      )}
    </div>
  );
}

function CourseModal({ onClose, onSaved, onError }) {
  const [form, setForm] = useState({ title: '', source: '', link: '', tags: '' });
  const submit = async () => {
    if (!form.title.trim()) return onError('Nhập tên khóa học');
    try {
      await api.post('/api/learning/courses', {
        title: form.title.trim(), source: form.source, link: form.link,
        tags: form.tags.split(',').map((s) => s.trim()).filter(Boolean)
      });
      onSaved();
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <Modal title="Khóa học mới" onClose={onClose} footer={<><Btn onClick={onClose}>Hủy</Btn><Btn variant="primary" onClick={submit}>Lưu</Btn></>}>
      <div className="form-grid">
        <Field label="Tên" wide><Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} autoFocus /></Field>
        <Field label="Nguồn"><Input value={form.source} onChange={(e) => setForm({ ...form, source: e.target.value })} /></Field>
        <Field label="Link"><Input value={form.link} onChange={(e) => setForm({ ...form, link: e.target.value })} /></Field>
        <Field label="Tags" wide><Input value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} /></Field>
      </div>
    </Modal>
  );
}

function DeckModal({ courses, onClose, onSaved, onError }) {
  const [form, setForm] = useState({ name: '', courseId: '' });
  const submit = async () => {
    if (!form.name.trim()) return onError('Nhập tên bộ thẻ');
    try {
      const deck = await api.post('/api/learning/decks', form);
      onSaved(deck);
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <Modal title="Bộ thẻ mới" onClose={onClose} footer={<><Btn onClick={onClose}>Hủy</Btn><Btn variant="primary" onClick={submit}>Lưu</Btn></>}>
      <div className="form-grid">
        <Field label="Tên" wide><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus /></Field>
        <Field label="Khóa học">
          <Select value={form.courseId} onChange={(e) => setForm({ ...form, courseId: e.target.value })}>
            <option value="">Độc lập</option>
            {courses.map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}
          </Select>
        </Field>
      </div>
    </Modal>
  );
}

function CardModal({ deckId, onClose, onSaved, onError }) {
  const [form, setForm] = useState({ front: '', back: '' });
  const submit = async () => {
    if (!form.front.trim() || !form.back.trim()) return onError('Nhập cả mặt trước và sau');
    try {
      await api.post('/api/learning/cards', { deckId, ...form });
      onSaved();
    } catch (e) {
      onError(e.message);
    }
  };
  return (
    <Modal title="Thẻ mới" onClose={onClose} footer={<><Btn onClick={onClose}>Hủy</Btn><Btn variant="primary" onClick={submit}>Lưu</Btn></>}>
      <div className="form-grid">
        <Field label="Mặt trước" wide><Input value={form.front} onChange={(e) => setForm({ ...form, front: e.target.value })} autoFocus /></Field>
        <Field label="Mặt sau" wide><Input value={form.back} onChange={(e) => setForm({ ...form, back: e.target.value })} /></Field>
      </div>
    </Modal>
  );
}
