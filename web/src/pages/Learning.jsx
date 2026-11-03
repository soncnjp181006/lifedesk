import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, RotateCw } from 'lucide-react';
import { api } from '../api.js';
import { Btn, Spinner } from '../ui.jsx';

const POLL_MS = 1500;
const THEME_SLUGS = ['deepblue', 'dark', 'light', 'mate', 'monokai'];

function currentTheme() {
  const slug = document.documentElement.getAttribute('data-theme');
  return THEME_SLUGS.includes(slug) ? slug : 'deepblue';
}

/**
 * The "Học tập (EduSmart)" module is the EduSmart app itself: LifeDesk owns the
 * lifecycle of the bundled EduSmart backend and shows its UI full-bleed inside
 * this iframe — no second window, no external browser.
 *
 * EduSmart mirrors LifeDesk's palette: the active theme slug travels to the
 * iframe as ?theme=… (read before its first paint) and every later change is
 * pushed with postMessage, so both apps always show the same colours.
 */
export default function Learning() {
  const [status, setStatus] = useState(null);
  const [failed, setFailed] = useState('');
  const [theme, setTheme] = useState(currentTheme);
  const timer = useRef(null);
  const frame = useRef(null);
  const frameSrc = useRef('');

  const poll = useCallback(async () => {
    try {
      const s = await api.get('/api/edusmart');
      setStatus(s);
      if (s.state === 'RUNNING' || s.state === 'MISSING' || s.state === 'ERROR') {
        clearInterval(timer.current);
      }
    } catch (e) {
      setFailed(e.message);
    }
  }, []);

  useEffect(() => {
    poll();
    timer.current = setInterval(poll, POLL_MS);
    return () => clearInterval(timer.current);
  }, [poll]);

  const pushTheme = useCallback(() => {
    const win = frame.current && frame.current.contentWindow;
    if (!win || !status || !status.url) return;
    try {
      win.postMessage({ type: 'lifedesk:theme', theme }, new URL(status.url).origin);
    } catch (e) {
      /* iframe is detaching — nothing to sync */
    }
  }, [status, theme]);

  useEffect(() => {
    const observer = new MutationObserver(() => setTheme(currentTheme()));
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });
    return () => observer.disconnect();
  }, []);

  useEffect(pushTheme, [pushTheme]);

  const restart = async () => {
    setStatus(null);
    setFailed('');
    frameSrc.current = '';
    try {
      await api.post('/api/edusmart/restart', {});
    } catch (e) {
      setFailed(e.message);
      return;
    }
    clearInterval(timer.current);
    timer.current = setInterval(poll, POLL_MS);
    poll();
  };

  if (failed) {
    return (
      <div className="edu-boot">
        <AlertTriangle size={26} className="tone-danger" />
        <h3>Không kết nối được module Học tập</h3>
        <p className="muted">{failed}</p>
        <Btn icon={RotateCw} onClick={restart}>Thử lại</Btn>
      </div>
    );
  }

  if (!status) {
    return <div className="edu-boot"><Spinner label="Đang kết nối EduSmart…" /></div>;
  }

  if (status.state === 'RUNNING') {
    /* Frozen after the first mount: a later theme switch is pushed into the
       live document instead of reloading EduSmart back to its dashboard. */
    if (!frameSrc.current) {
      const url = new URL(status.url);
      url.searchParams.set('theme', theme);
      frameSrc.current = url.toString();
    }
    return (
      <iframe
        ref={frame}
        className="edu-frame"
        title="EduSmart"
        src={frameSrc.current}
        onLoad={pushTheme}
      />
    );
  }

  if (status.state === 'MISSING') {
    return (
      <div className="edu-boot">
        <AlertTriangle size={26} className="tone-warn" />
        <h3>Chưa có EduSmart</h3>
        <p className="muted">{status.error}</p>
      </div>
    );
  }

  if (status.state === 'ERROR') {
    return (
      <div className="edu-boot">
        <AlertTriangle size={26} className="tone-danger" />
        <h3>EduSmart gặp lỗi khi khởi động</h3>
        <p className="muted small">{status.error}</p>
        <Btn icon={RotateCw} onClick={restart}>Khởi động lại</Btn>
      </div>
    );
  }

  return (
    <div className="edu-boot">
      <Spinner label="Đang khởi động EduSmart…" />
      <p className="muted small">Lần khởi động đầu tiên có thể mất khoảng một phút.</p>
    </div>
  );
}
