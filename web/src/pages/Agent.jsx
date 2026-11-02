import React, { useState } from 'react';
import { Play, Sparkles } from 'lucide-react';
import { api } from '../api.js';
import { useLoad } from '../hooks.js';
import {
  Badge,
  Btn,
  Card,
  Empty,
  ErrorBox,
  Input,
  Page,
  Spinner,
  useNotify
} from '../ui.jsx';

export default function Agent() {
  const notify = useNotify();
  const workflows = useLoad(() => api.get('/api/agent/workflows'));
  const [running, setRunning] = useState(null);
  const [steps, setSteps] = useState([]);
  const [instruction, setInstruction] = useState('');
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);

  if (workflows.loading) return <Spinner />;
  if (workflows.error) return <ErrorBox error={workflows.error} onRetry={workflows.reload} />;

  const run = async (wf) => {
    setRunning(wf.id);
    setSteps([]);
    setAnswer('');
    setBusy(true);
    try {
      const out = await api.post('/api/agent/run', { workflowId: wf.id });
      setSteps(out || []);
      notify('Workflow hoàn tất');
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const command = async (e) => {
    e.preventDefault();
    if (!instruction.trim()) return;
    setRunning('command');
    setSteps([]);
    setAnswer('');
    setBusy(true);
    try {
      const out = await api.post('/api/agent/command', { instruction });
      setSteps(out.steps || []);
      setAnswer(out.answer || '');
    } catch (err) {
      notify(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Page title="Agent" subtitle="Chạy workflow định sẵn hoặc ra lệnh bằng ngôn ngữ tự nhiên">
      <div className="grid-2">
        <Card title="Workflow">
          {workflows.data.length === 0 ? <Empty title="Chưa có workflow" /> : (
            <div className="workflow-list">
              {workflows.data.map((wf) => (
                <div className="workflow" key={wf.id}>
                  <div>
                    <strong>{wf.name}</strong>
                    <p className="muted small">{wf.description}</p>
                    <div className="chip-wrap">
                      {wf.steps.map((s) => <span className="chip" key={s}>{s}</span>)}
                    </div>
                  </div>
                  <Btn variant="primary" icon={Play} disabled={busy} onClick={() => run(wf)}>
                    {running === wf.id && busy ? 'Đang chạy…' : 'Chạy'}
                  </Btn>
                </div>
              ))}
            </div>
          )}
        </Card>

        <Card title="Lệnh tự do" actions={<Sparkles size={16} />}>
          <form className="inline-form" onSubmit={command}>
            <Input
              placeholder='Ví dụ: "tạo việc ôn thi vào thứ 6"'
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              disabled={busy}
            />
            <Btn variant="primary" type="submit" disabled={busy || !instruction.trim()}>Gửi lệnh</Btn>
          </form>
          <p className="muted small">Agent dùng cùng nhà cung cấp AI đã chọn trong Cài đặt.</p>
        </Card>
      </div>

      {(steps.length > 0 || answer) && (
        <Card title="Kết quả">
          {steps.map((s) => (
            <div className="step" key={s.index}>
              <Badge tone={s.ok ? 'ok' : 'danger'}>{s.ok ? 'OK' : 'Lỗi'}</Badge>
              <div className="grow">
                <strong>{s.label}</strong>
                {s.result && <p className="muted small">{s.result}</p>}
              </div>
            </div>
          ))}
          {answer && <div className="agent-answer">{answer}</div>}
        </Card>
      )}
    </Page>
  );
}
