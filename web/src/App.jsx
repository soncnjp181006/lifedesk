import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import {
  Bell,
  BookOpen,
  Bot,
  HeartPulse,
  LayoutDashboard,
  LibraryBig,
  ListTodo,
  NotebookPen,
  PanelLeft,
  RefreshCw,
  Repeat,
  Search,
  Settings,
  Wallet
} from 'lucide-react';
import { api } from './api.js';
import { AppCtx, ConfirmHost, Spinner, fmt } from './ui.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Tasks from './pages/Tasks.jsx';
import Notes from './pages/Notes.jsx';
import Finance from './pages/Finance.jsx';
import Habits from './pages/Habits.jsx';
import Health from './pages/Health.jsx';
import Learning from './pages/Learning.jsx';
import AiChat from './pages/AiChat.jsx';
import Library from './pages/Library.jsx';
import SettingsPage from './pages/Settings.jsx';
import SearchPage from './pages/Search.jsx';

const GROUPS = [
  { label: 'Tổng quan', items: [{ to: '/', label: 'Tổng quan', icon: LayoutDashboard, end: true }] },
  {
    label: 'Quản lý',
    items: [
      { to: '/tasks', label: 'Công việc', icon: ListTodo },
      { to: '/notes', label: 'Ghi chú', icon: NotebookPen },
      { to: '/finance', label: 'Tài chính', icon: Wallet },
      { to: '/habits', label: 'Thói quen & Mục tiêu', icon: Repeat },
      { to: '/health', label: 'Sức khỏe', icon: HeartPulse },
      { to: '/learning', label: 'Học tập (EduSmart)', icon: BookOpen }
    ]
  },
  {
    label: 'Trợ lý',
    items: [
      { to: '/ai', label: 'Trợ lý AI', icon: Bot }
    ]
  },
  {
    label: 'Khác',
    items: [
      { to: '/library', label: 'Thư viện', icon: LibraryBig },
      { to: '/settings', label: 'Cài đặt', icon: Settings }
    ]
  }
];

const THEME_SLUG = {
  'Deep Blue': 'deepblue',
  Dark: 'dark',
  Light: 'light',
  Mate: 'mate',
  Monokai: 'monokai'
};

export default function App() {
  const [boot, setBoot] = useState(null);
  const [bootError, setBootError] = useState(null);
  const [collapsed, setCollapsed] = useState(false);
  const [toast, setToast] = useState(null);
  const [confirmState, setConfirmState] = useState(null);
  const [query, setQuery] = useState('');
  const searchRef = useRef(null);
  const [notifOpen, setNotifOpen] = useState(false);
  const [notifs, setNotifs] = useState({ items: [], unread: 0 });
  const navigate = useNavigate();
  const { pathname } = useLocation();

  const loadBoot = useCallback(() => {
    api
      .get('/api/bootstrap')
      .then((data) => {
        setBoot(data);
        setCollapsed(!!data.config.sidebarCollapsed);
        applyTheme(data.config.theme);
      })
      .catch((e) => setBootError(e.message));
  }, []);

  useEffect(() => {
    loadBoot();
  }, [loadBoot]);

  const loadNotifs = useCallback(() => {
    api.get('/api/notifications').then(setNotifs).catch(() => {});
  }, []);

  useEffect(() => {
    loadNotifs();
    const id = setInterval(loadNotifs, 30000);
    return () => clearInterval(id);
  }, [loadNotifs]);

  useEffect(() => {
    const shortcuts = ['/', '/tasks', '/notes', '/finance', '/habits', '/health', '/learning', '/ai', '/settings'];
    const onKey = (e) => {
      if (!e.ctrlKey) return;
      if (e.key.toLowerCase() === 'k') {
        e.preventDefault();
        searchRef.current?.focus();
        return;
      }
      if (/^[1-9]$/.test(e.key)) {
        e.preventDefault();
        const dest = shortcuts[Number(e.key) - 1];
        if (dest) navigate(dest);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [navigate]);

  const notify = useCallback((message, type = 'ok') => {
    setToast({ message, type });
    window.clearTimeout(notify._t);
    notify._t = window.setTimeout(() => setToast(null), 3200);
  }, []);

  const app = {
    boot,
    reloadBoot: loadBoot,
    collapsed,
    setCollapsed,
    notify,
    confirmState,
    setConfirmState,
    loadNotifs
  };

  if (bootError) {
    return (
      <div className="boot-error">
        <h2>Không kết nối được backend</h2>
        <p>{bootError}</p>
        <button className="btn" onClick={loadBoot}>
          Thử lại
        </button>
      </div>
    );
  }
  if (!boot) {
    return (
      <div className="boot">
        <Spinner label="Đang khởi động LifeDesk…" />
      </div>
    );
  }

  const submitSearch = (e) => {
    e.preventDefault();
    if (query.trim()) navigate('/search?q=' + encodeURIComponent(query.trim()));
  };

  return (
    <AppCtx.Provider value={app}>
      <div className={'shell' + (collapsed ? ' collapsed' : '')}>
        <aside className="sidebar">
          <div className="brand">
            <span className="brand-mark">L</span>
            {!collapsed && (
              <div>
                <strong>LifeDesk</strong>
                <small>Trợ lý cá nhân</small>
              </div>
            )}
          </div>
          <nav>
            {GROUPS.map((g) => (
              <div className="nav-group" key={g.label}>
                {!collapsed && <div className="nav-label">{g.label}</div>}
                {g.items.map((it) => (
                  <NavLink
                    key={it.to}
                    to={it.to}
                    end={it.end}
                    className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')}
                    title={it.label}
                  >
                    <it.icon size={17} />
                    {!collapsed && <span>{it.label}</span>}
                  </NavLink>
                ))}
              </div>
            ))}
          </nav>
        </aside>

        <div className="main">
          <header className="topbar">
            <button className="icon-btn" title="Thu gọn/Mở rộng sidebar" onClick={() => setCollapsed((c) => !c)}>
              <PanelLeft size={17} />
            </button>
            <form className="search-bar" onSubmit={submitSearch}>
              <Search size={16} />
              <input
                ref={searchRef}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Tìm kiếm toàn cục (Ctrl+K)"
              />
            </form>
            <div className="top-actions">
              <div className="notif-wrap">
                <button
                  className="icon-btn"
                  title="Thông báo"
                  onClick={() => {
                    setNotifOpen((o) => !o);
                    loadNotifs();
                  }}
                >
                  <Bell size={17} />
                  {notifs.unread > 0 && <span className="dot-badge">{notifs.unread}</span>}
                </button>
                {notifOpen && (
                  <div className="notif-pop">
                    <header>
                      <strong>Thông báo</strong>
                      <button
                        className="link"
                        onClick={async () => {
                          await api.post('/api/notifications/read-all');
                          loadNotifs();
                        }}
                      >
                        Đọc tất cả
                      </button>
                    </header>
                    <div className="notif-list">
                      {notifs.items.length === 0 && <p className="muted small">Chưa có thông báo.</p>}
                      {notifs.items.slice(0, 12).map((n) => (
                        <div key={n.id} className={'notif' + (n.read ? ' read' : '')}>
                          <strong>{n.title}</strong>
                          <span>{n.message}</span>
                          <em>{fmt.dateTime(n.createdAt)}</em>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
              <span className="theme-chip">{boot.config.theme}</span>
            </div>
          </header>

          <main className={'content' + (pathname === '/notes' ? ' content-notes' : '') + (pathname === '/learning' ? ' content-edu' : '')}>
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/tasks" element={<Tasks />} />
              <Route path="/notes" element={<Notes />} />
              <Route path="/finance" element={<Finance />} />
              <Route path="/habits" element={<Habits />} />
              <Route path="/health" element={<Health />} />
              <Route path="/learning" element={<Learning />} />
              <Route path="/ai" element={<AiChat />} />
              <Route path="/library" element={<Library />} />
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="/search" element={<SearchPage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </main>
        </div>
      </div>
      <ConfirmHost />
      {toast && <div className={'toast toast-' + toast.type}>{toast.message}</div>}
    </AppCtx.Provider>
  );
}

function applyTheme(name) {
  const slug = THEME_SLUG[name] || 'deepblue';
  document.documentElement.setAttribute('data-theme', slug);
}
