import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, RotateCw } from 'lucide-react';
import { api } from '../api.js';
import { Btn, Spinner } from '../ui.jsx';

const POLL_MS = 1500;

/**
 * The "Học tập (EduSmart)" module is the EduSmart app itself: LifeDesk owns the
 * lifecycle of the bundled EduSmart backend and shows its UI full-bleed inside
 * this iframe — no second window, no external browser.
 */
export default function Learning() {
  const [status, setStatus] = useState(null);
  const [failed, setFailed] = useState('');
  const timer = useRef(null);

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

  const restart = async () => {
    setStatus(null);
    setFailed('');
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
    return <iframe className="edu-frame" title="EduSmart" src={status.url} />;
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
