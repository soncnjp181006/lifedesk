import React, { useEffect, useRef, useState } from 'react';
import { Plus, Send, Trash2 } from 'lucide-react';
import { api } from '../api.js';
import { useLoad } from '../hooks.js';
import {
  Btn,
  Empty,
  ErrorBox,
  IconBtn,
  Page,
  Spinner,
  fmt,
  useConfirm,
  useNotify,
  useApp
} from '../ui.jsx';

export default function AiChat() {
  const notify = useNotify();
  const confirm = useConfirm();
  const app = useApp();
  const convs = useLoad(() => api.get('/api/ai/conversations'));
  const [active, setActive] = useState(null);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [messages, setMessages] = useState([]);
  const endRef = useRef(null);

  const loadConversation = async (id) => {
    setActive(id);
    if (!id) {
      setMessages([]);
      return;
    }
    try {
      const c = await api.get('/api/ai/conversations/' + id);
      setMessages(c.messages || []);
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  useEffect(() => {
    if (!active && convs.data?.length) loadConversation(convs.data[0].id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [convs.data]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const newConversation = async () => {
    try {
      const c = await api.post('/api/ai/conversations', { title: 'Hội thoại mới' });
      await convs.reload();
      loadConversation(c.id);
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const removeConversation = async (id) => {
    if (!(await confirm('Xoá hội thoại này?', 'Xoá hội thoại'))) return;
    try {
      await api.del('/api/ai/conversations/' + id);
      if (active === id) setActive(null);
      setMessages([]);
      convs.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const send = async (e) => {
    e.preventDefault();
    const message = input.trim();
    if (!message || sending) return;
    setInput('');
    setMessages((m) => [...m, { role: 'user', content: message }]);
    setSending(true);
    try {
      const res = await api.post('/api/ai/chat', { conversationId: active || '', message });
      setActive(res.conversation.id);
      setMessages(res.conversation.messages || []);
      convs.reload();
    } catch (err) {
      notify(err.message, 'error');
      setMessages((m) => [...m, { role: 'assistant', content: '⚠ ' + err.message }]);
    } finally {
      setSending(false);
    }
  };

  if (convs.loading) return <Spinner />;
  if (convs.error) return <ErrorBox error={convs.error} onRetry={convs.reload} />;

  return (
    <Page fill>
      <div className="chat-layout">
        <aside className="chat-side">
          <div className="chat-side-head">
            <Btn variant="primary" icon={Plus} onClick={newConversation}>Hội thoại mới</Btn>
          </div>
          {convs.data.length === 0 && <p className="muted small pad">Chưa có hội thoại.</p>}
          {convs.data.map((c) => (
            <div key={c.id} className={'chat-conv' + (active === c.id ? ' active' : '')}>
              <button className="grow" onClick={() => loadConversation(c.id)}>
                <span className="row-title">{c.title}</span>
                <span className="row-meta">{fmt.dateTime(c.updatedAt)}</span>
              </button>
              <IconBtn icon={Trash2} title="Xoá" onClick={() => removeConversation(c.id)} />
            </div>
          ))}
        </aside>
        <section className="chat-main">
          <div className="chat-scroll">
            {messages.length === 0 ? (
              <Empty title="Hỏi trợ lý về việc hôm nay, chi tiêu, thói quen…" />
            ) : (
              messages.map((m, i) => (
                <div key={i} className={'bubble ' + (m.role === 'user' ? 'user' : 'ai')}>
                  <div className="bubble-role">{m.role === 'user' ? 'Bạn' : 'Trợ lý'}</div>
                  <div className="bubble-text">{m.content}</div>
                </div>
              ))
            )}
            {sending && <div className="bubble ai"><div className="bubble-text muted">Đang trả lời…</div></div>}
            <div ref={endRef} />
          </div>
          <form className="chat-input" onSubmit={send}>
            <input
              className="input"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="Nhập câu hỏi…"
              disabled={sending}
            />
            <Btn variant="primary" icon={Send} type="submit" disabled={sending || !input.trim()}>Gửi</Btn>
          </form>
        </section>
      </div>
    </Page>
  );
}
