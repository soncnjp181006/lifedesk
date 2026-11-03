import React, { useState } from 'react';
import { Save } from 'lucide-react';
import { api } from '../api.js';
import {
  Btn,
  Card,
  Field,
  Input,
  Page,
  Select,
  useApp,
  useNotify
} from '../ui.jsx';

const THEMES = ['Deep Blue', 'Dark', 'Light', 'Mate', 'Monokai'];
const SLUG = { 'Deep Blue': 'deepblue', Dark: 'dark', Light: 'light', Mate: 'mate', Monokai: 'monokai' };

export default function Settings() {
  const app = useApp();
  const notify = useNotify();
  const cfg = app.boot.config;
  const [form, setForm] = useState({
    theme: cfg.theme,
    sidebarCollapsed: !!cfg.sidebarCollapsed,
    remindersEnabled: !!cfg.remindersEnabled,
    reminderMinutesBefore: cfg.reminderMinutesBefore,
    latexAssist: !!cfg.latexAssist,
    aiEnabled: !!cfg.ai.enabled,
    providerId: cfg.ai.providerId,
    includeUserData: !!cfg.ai.includeUserData,
    maxHistoryMessages: cfg.ai.maxHistoryMessages,
    ollamaBaseUrl: cfg.ai.ollama.baseUrl,
    ollamaModel: cfg.ai.ollama.model,
    kiloBaseUrl: cfg.ai.kilo.baseUrl,
    kiloModel: cfg.ai.kilo.model,
    kiloApiKey: ''
  });
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });

  const save = async () => {
    try {
      await api.put('/api/config', {
        theme: form.theme,
        sidebarCollapsed: form.sidebarCollapsed,
        remindersEnabled: form.remindersEnabled,
        reminderMinutesBefore: Number(form.reminderMinutesBefore) || 0,
        latexAssist: form.latexAssist,
        ai: {
          enabled: form.aiEnabled,
          providerId: form.providerId,
          includeUserData: form.includeUserData,
          maxHistoryMessages: Number(form.maxHistoryMessages) || 12,
          ollama: { baseUrl: form.ollamaBaseUrl, model: form.ollamaModel },
          kilo: { baseUrl: form.kiloBaseUrl, model: form.kiloModel, apiKey: form.kiloApiKey }
        }
      });
      document.documentElement.setAttribute('data-theme', SLUG[form.theme] || 'deepblue');
      await app.reloadBoot();
      notify('Đã lưu cài đặt');
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Page>
      <div className="toolbar toolbar-end">
        <Btn variant="primary" icon={Save} onClick={save}>Lưu cài đặt</Btn>
      </div>
      <div className="grid-2">
        <Card title="Giao diện">
          <div className="form-grid">
            <Field label="Chủ đề">
              <Select value={form.theme} onChange={set('theme')}>
                {THEMES.map((t) => <option key={t} value={t}>{t}</option>)}
              </Select>
            </Field>
            <Field label="Sidebar">
              <label className="checkbox"><input type="checkbox" checked={form.sidebarCollapsed} onChange={set('sidebarCollapsed')} /> Thu gọn sidebar</label>
            </Field>
            <Field label="Trợ gõ LaTeX">
              <label className="checkbox"><input type="checkbox" checked={form.latexAssist} onChange={set('latexAssist')} /> Bật gợi ý LaTeX</label>
            </Field>
          </div>
        </Card>

        <Card title="Nhắc việc">
          <div className="form-grid">
            <Field label="Bật nhắc việc">
              <label className="checkbox"><input type="checkbox" checked={form.remindersEnabled} onChange={set('remindersEnabled')} /> Nhắc trước hạn</label>
            </Field>
            <Field label="Nhắc trước (phút)">
              <Input type="number" min="0" value={form.reminderMinutesBefore} onChange={set('reminderMinutesBefore')} />
            </Field>
          </div>
        </Card>

        <Card title="Trợ lý AI">
          <div className="form-grid">
            <Field label="Bật AI">
              <label className="checkbox"><input type="checkbox" checked={form.aiEnabled} onChange={set('aiEnabled')} /> Dùng nhà cung cấp AI</label>
            </Field>
            <Field label="Nhà cung cấp">
              <Select value={form.providerId} onChange={set('providerId')}>
                {app.boot.providers.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </Select>
            </Field>
            <Field label="Gửi dữ liệu người dùng">
              <label className="checkbox"><input type="checkbox" checked={form.includeUserData} onChange={set('includeUserData')} /> Cho phép đưa dữ liệu vào ngữ cảnh</label>
            </Field>
            <Field label="Số tin nhắn lịch sử">
              <Input type="number" min="2" value={form.maxHistoryMessages} onChange={set('maxHistoryMessages')} />
            </Field>
            <Field label="Ollama URL"><Input value={form.ollamaBaseUrl} onChange={set('ollamaBaseUrl')} /></Field>
            <Field label="Ollama model"><Input value={form.ollamaModel} onChange={set('ollamaModel')} /></Field>
            <Field label="Kilo URL"><Input value={form.kiloBaseUrl} onChange={set('kiloBaseUrl')} /></Field>
            <Field label="Kilo model"><Input value={form.kiloModel} onChange={set('kiloModel')} /></Field>
            <Field label="Kilo API key" hint="Để trống để giữ key hiện tại"><Input type="password" value={form.kiloApiKey} onChange={set('kiloApiKey')} placeholder="••••••••" /></Field>
          </div>
        </Card>

        <Card title="Dữ liệu">
          <div className="kv"><span>Thư mục dữ liệu</span><code>{app.boot.dataDir}</code></div>
          <div className="kv"><span>Phiên bản schema</span><span>{cfg.schemaVersion}</span></div>
          <div className="kv"><span>Nhà cung cấp đang dùng</span><span>{app.boot.activeProvider}</span></div>
          <div className="kv"><span>Model</span><span>{app.boot.activeModel || '—'}</span></div>
        </Card>
      </div>
    </Page>
  );
}
