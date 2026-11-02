import React, { useEffect, useState } from 'react';
import { ChevronLeft, ExternalLink, Folder, FolderPlus, Search, Star, Trash2 } from 'lucide-react';
import { api, qs } from '../api.js';
import { useDebounced, useLoad } from '../hooks.js';
import {
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

export default function Files() {
  const notify = useNotify();
  const [path, setPath] = useState('');
  const [query, setQuery] = useState('');
  const q = useDebounced(query);
  const roots = useLoad(() => api.get('/api/files'));
  const listing = useLoad(
    () => path ? api.get('/api/files/search' + qs({ path, q })) : Promise.resolve([]),
    [path, q]
  );
  const browse = useLoad(() => api.get('/api/files' + qs({ path })), [path]);

  useEffect(() => {
    if (!path && roots.data?.roots?.length) setPath(roots.data.roots[0]);
  }, [roots.data, path]);

  if (roots.loading) return <Spinner />;
  if (roots.error) return <ErrorBox error={roots.error} onRetry={roots.reload} />;

  const items = q ? listing.data || [] : browse.data?.items || [];
  const favorites = roots.data?.favorites || [];

  const open = async (item) => {
    if (item.directory) {
      setQuery('');
      setPath(item.path);
      return;
    }
    try {
      const res = await api.post('/api/files/open', { path: item.path });
      if (!res.opened) notify('Không mở được tệp này', 'error');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const addFavorite = async () => {
    if (!path) return;
    try {
      await api.post('/api/files/favorites', { path, label: path.split(/[\\/]/).pop() || path });
      roots.reload();
      notify('Đã thêm vào yêu thích');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const removeFavorite = async (p) => {
    try {
      await api.del('/api/files/favorites?path=' + encodeURIComponent(p));
      roots.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page
      title="Tệp"
      subtitle="Duyệt thư mục được cấp quyền và mở bằng ứng dụng mặc định"
      actions={<Btn icon={FolderPlus} onClick={addFavorite}>Yêu thích thư mục này</Btn>}
    >
      <div className="toolbar">
        <div className="toolbar-fields">
          <select className="input" value={path} onChange={(e) => setPath(e.target.value)}>
            {roots.data.roots.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
          <Input placeholder="Tìm trong thư mục…" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
      </div>

      <div className="grid-2">
        <Card title="Yêu thích">
          {favorites.length === 0 ? <Empty title="Chưa có thư mục yêu thích" /> : (
            <ul className="row-list">
              {favorites.map((f) => (
                <li key={f.path}>
                  <button className="link grow" onClick={() => setPath(f.path)}>{f.label || f.path}</button>
                  <IconBtn icon={Trash2} title="Bỏ yêu thích" onClick={() => removeFavorite(f.path)} />
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title={q ? `Kết quả cho "${q}"` : path} actions={
          path && <div className="inline-form">
            {browse.data?.parent && <IconBtn icon={ChevronLeft} title="Lên thư mục cha" onClick={() => setPath(browse.data.parent)} />}
          </div>
        }>
          {!path ? <Empty title="Chưa cấp quyền thư mục nào" hint="Thêm allowedRoots trong Cài đặt" /> :
            (q ? listing : browse).loading ? <Spinner /> :
            (q ? listing : browse).error ? <ErrorBox error={(q ? listing : browse).error} onRetry={(q ? listing : browse).reload} /> :
            items.length === 0 ? <Empty title={q ? 'Không tìm thấy tệp phù hợp' : 'Thư mục trống'} /> : (
              <div className="file-list">
                {items.map((it) => (
                  <button key={it.path} className="file-row" onClick={() => open(it)} title={it.path}>
                    {it.directory ? <Folder size={15} /> : <ExternalLink size={15} />}
                    <span className="grow">{it.name}</span>
                    {!it.directory && <span className="muted small">{Math.round((it.sizeBytes || 0) / 1024)} KB</span>}
                  </button>
                ))}
              </div>
            )}
        </Card>
      </div>
    </Page>
  );
}
