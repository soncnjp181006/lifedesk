import React, { useState } from 'react';
import { Bookmark, Check, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { api } from '../api.js';
import { useLoad } from '../hooks.js';
import {
  Badge,
  Btn,
  Card,
  Empty,
  ErrorBox,
  IconBtn,
  Input,
  Page,
  Spinner,
  fmt,
  useNotify
} from '../ui.jsx';

export default function RssPage() {
  const notify = useNotify();
  const data = useLoad(() => api.get('/api/rss'));
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [filter, setFilter] = useState('all');

  if (data.loading) return <Spinner />;
  if (data.error) return <ErrorBox error={data.error} onRetry={data.reload} />;
  const { feeds, articles } = data.data;

  const addFeed = async (e) => {
    e.preventDefault();
    if (!url.trim()) return;
    try {
      await api.post('/api/rss/feeds', { url: url.trim(), folder: '' });
      setUrl('');
      data.reload();
      notify('Đã thêm feed');
    } catch (err) {
      notify(err.message, 'error');
    }
  };

  const refresh = async () => {
    setBusy(true);
    try {
      await api.post('/api/rss/refresh');
      data.reload();
      notify('Đã làm mới feed');
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const removeFeed = async (f) => {
    try {
      await api.del('/api/rss/feeds/' + f.id);
      data.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const markRead = async (a, read) => {
    try {
      await api.post('/api/rss/mark-read', { url: a.url, read });
      data.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const save = async (a) => {
    try {
      await api.post('/api/rss/save', { url: a.url });
      data.reload();
      notify('Đã lưu bài viết');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const shown = articles.filter((a) => filter === 'all' || (filter === 'unread' && !a.read) || (filter === 'saved' && a.saved));

  return (
    <Page
      title="RSS"
      subtitle="Đọc tin từ các feed bạn theo dõi"
      actions={<Btn icon={RefreshCw} onClick={refresh} disabled={busy}>{busy ? 'Đang làm mới…' : 'Làm mới'}</Btn>}
    >
      <Card title="Feed" actions={
        <form className="inline-form" onSubmit={addFeed}>
          <Input placeholder="https://…" value={url} onChange={(e) => setUrl(e.target.value)} />
          <Btn icon={Plus} type="submit">Thêm</Btn>
        </form>
      }>
        {feeds.length === 0 ? <Empty title="Chưa có feed" hint="Dán URL RSS/Atom để bắt đầu" /> : (
          <ul className="row-list">
            {feeds.map((f) => (
              <li key={f.id}>
                <span className="grow">{f.title}</span>
                <span className="muted small">{f.url}</span>
                <IconBtn icon={Trash2} title="Xoá feed" onClick={() => removeFeed(f)} />
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="Bài viết" actions={
        <div className="inline-form">
          <Btn variant={filter === 'all' ? 'primary' : 'default'} onClick={() => setFilter('all')}>Tất cả</Btn>
          <Btn variant={filter === 'unread' ? 'primary' : 'default'} onClick={() => setFilter('unread')}>Chưa đọc</Btn>
          <Btn variant={filter === 'saved' ? 'primary' : 'default'} onClick={() => setFilter('saved')}>Đã lưu</Btn>
        </div>
      }>
        {shown.length === 0 ? <Empty title="Chưa có bài viết" hint="Bấm Làm mới để tải feed" /> : (
          <div className="article-list">
            {shown.map((a) => (
              <div className={'article' + (a.read ? ' read' : '')} key={a.url}>
                <div className="grow">
                  <div className="row-title">{a.title}</div>
                  <div className="row-meta">
                    <Badge tone="info">{a.feedTitle}</Badge>
                    <span className="muted small">{fmt.dateTime(a.published)}</span>
                  </div>
                  {a.summary && <p className="muted small clamp">{a.summary.replace(/<[^>]+>/g, ' ').slice(0, 180)}</p>}
                </div>
                <IconBtn icon={Check} title={a.read ? 'Đánh dấu chưa đọc' : 'Đánh dấu đã đọc'} onClick={() => markRead(a, !a.read)} />
                <IconBtn icon={Bookmark} title="Lưu bài" onClick={() => save(a)} />
              </div>
            ))}
          </div>
        )}
      </Card>
    </Page>
  );
}
