import React from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Search as SearchIcon } from 'lucide-react';
import { api, qs } from '../api.js';
import { useLoad } from '../hooks.js';
import { Badge, Card, Empty, ErrorBox, Page, Spinner } from '../ui.jsx';

const ROUTE = {
  TASK: '/tasks',
  NOTE: '/notes',
  TRANSACTION: '/finance',
  HABIT: '/habits',
  GOAL: '/habits',
  COURSE: '/learning',
  FLASHCARD: '/learning',
  HEALTH: '/health'
};

export default function SearchPage() {
  const [params] = useSearchParams();
  const q = params.get('q') || '';
  const data = useLoad(() => (q ? api.get('/api/search' + qs({ q })) : Promise.resolve([])), [q]);

  return (
    <Page>
      {!q ? (
        <Empty icon={SearchIcon} title="Chưa có từ khoá" />
      ) : data.loading ? (
        <Spinner />
      ) : data.error ? (
        <ErrorBox error={data.error} onRetry={data.reload} />
      ) : data.data.length === 0 ? (
        <Empty icon={SearchIcon} title="Không tìm thấy kết quả" />
      ) : (
        <div className="grid-2">
          {data.data.map((g) => (
            <Card key={g.type} title={g.displayType} actions={<Badge tone="info">{g.results.length}</Badge>}>
              <div className="row-list">
                {g.results.map((r) => (
                  <Link key={r.doc.docId} className="search-hit" to={ROUTE[r.doc.type] || '/'}>
                    <span className="row-title">{r.doc.title}</span>
                    <span className="row-meta">{r.doc.content?.slice(0, 140)}</span>
                  </Link>
                ))}
              </div>
            </Card>
          ))}
        </div>
      )}
    </Page>
  );
}
