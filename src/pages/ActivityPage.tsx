import { useEffect, useRef } from 'react';
import { useReceiver } from '../hooks/useReceiver';
import { Empty, Panel } from '../components/ui';
import { formatSince, protocolLabel } from '../lib/format';

export function ActivityPage() {
  const { logs, status, lang, t, clearLogs } = useReceiver();
  const endRef = useRef<HTMLDivElement | null>(null);

  // Keep the newest line in view, the way a console does.
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' });
  }, [logs.length]);

  return (
    <>
      <Panel title={t('sessions.title')} flush index={0}>
        {status && status.sessions.length > 0 ? (
          <div className="rows">
            {status.sessions.map((session) => (
              <div className="row" key={session.id}>
                <span className={`row__badge row__badge--${session.protocol}`}>
                  {protocolLabel(session.protocol)}
                </span>
                <div className="row__main">
                  <div className="row__name">{session.name}</div>
                  <div className="row__meta">
                    {session.ip} · {t('sessions.since')} {formatSince(session.startedAt, lang)}
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <Empty>{t('sessions.empty')}</Empty>
        )}
      </Panel>

      <Panel
        title={t('activity.title')}
        flush
        index={1}
        action={
          <button type="button" className="btn btn--slim" onClick={() => void clearLogs()}>
            {t('activity.clear')}
          </button>
        }
      >
        {logs.length === 0 ? (
          <Empty>{t('activity.empty')}</Empty>
        ) : (
          <div className="log">
            {logs.map((entry, index) => (
              <span className={`log__line log__line--${entry.level}`} key={`${index}-${entry.line}`}>
                {entry.line}
              </span>
            ))}
            <div ref={endRef} />
          </div>
        )}
      </Panel>
    </>
  );
}
