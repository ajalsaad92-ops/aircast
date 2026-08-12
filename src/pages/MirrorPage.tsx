import { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { useReceiver } from '../hooks/useReceiver';
import { useMirror } from '../hooks/useMirror';
import { Panel } from '../components/ui';
import { CopyIcon, ExternalIcon, RecordIcon, ScreenIcon, StopIcon } from '../components/Icons';
import { AirCast } from '../lib/aircast';

export function MirrorPage() {
  const { status, settings, t, showToast } = useReceiver();
  const enabled = (settings?.mirrorEnabled ?? false) && (status?.running ?? false);
  const { state, session, stream, stop } = useMirror(status?.ip, enabled);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [full, setFull] = useState(false);
  const mirrorUrl = status?.mirrorUrl ?? '';

  // `full` is a dependency because switching layouts unmounts the old <video>.
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    video.srcObject = stream;
    if (stream) void video.play().catch(() => undefined);
  }, [stream, full]);

  // A cast that has actually started should fill the TV without anyone reaching for
  // the remote. Leaving the app chrome around a live picture is the single most
  // common complaint about receivers that render mirroring inside their own UI.
  useEffect(() => {
    setFull(state === 'live');
  }, [state]);

  useEffect(() => {
    if (!mirrorUrl) {
      setQr(null);
      return;
    }
    let cancelled = false;
    void QRCode.toDataURL(mirrorUrl, {
      margin: 0,
      width: 320,
      color: { dark: '#0e1015', light: '#ffffff' },
    })
      .then((url) => {
        if (!cancelled) setQr(url);
      })
      .catch(() => setQr(null));
    return () => {
      cancelled = true;
    };
  }, [mirrorUrl]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(mirrorUrl);
      showToast(t('guide.copied'));
    } catch {
      showToast(mirrorUrl);
    }
  };

  const toggleRecording = async () => {
    if (status?.recording) {
      await AirCast.stopRecording();
      showToast(t('record.saved'));
    } else {
      const result = await AirCast.startRecording();
      if (result.cancelled) return;
      showToast(t('record.recording'));
    }
  };

  if (full) {
    return (
      <div
        className="fullstage"
        role="button"
        tabIndex={0}
        onClick={() => setFull(false)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ' || e.key === 'Escape') setFull(false);
        }}
      >
        <video ref={videoRef} autoPlay playsInline />
        <span className="livetag">
          <span className="livetag__dot" />
          {t('mirror.live')} · {session?.name}
        </span>
        <span className="fullstage__hint">{t('mirror.exitFullscreen')}</span>
      </div>
    );
  }

  return (
    <>
      <Panel index={0}>
        <div className="stage">
          <video ref={videoRef} playsInline autoPlay />
          {state !== 'live' && (
            <div className="stage__idle">
              <span className="stage__pulse" />
              <span>
                {!settings?.mirrorEnabled
                  ? t('mirror.disabled')
                  : !status?.running
                    ? t('state.hidden')
                    : state === 'negotiating'
                      ? `${session?.name ?? ''} …`
                      : state === 'failed'
                        ? t('mirror.tlsMissing')
                        : t('mirror.waiting')}
              </span>
            </div>
          )}
        </div>

        <button
          type="button"
          className={status?.recording ? 'btn btn--danger' : 'btn'}
          style={{ marginTop: 14 }}
          onClick={() => void toggleRecording()}
        >
          {status?.recording ? <StopIcon /> : <RecordIcon />}
          {status?.recording ? t('record.stop') : t('record.start')}
        </button>
        {state === 'live' && (
          <button
            type="button"
            className="btn btn--danger"
            style={{ marginTop: 10 }}
            onClick={() => void stop()}
          >
            <StopIcon /> {t('mirror.stop')}
          </button>
        )}
      </Panel>

      <Panel title={t('quest.title')} index={1}>
        <p style={{ margin: '0 0 14px', color: 'var(--ink-2)', fontSize: '0.87rem', lineHeight: 1.7 }}>
          {t('quest.desc')}
        </p>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 12 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', color: 'var(--ink-3)', fontSize: '0.82rem' }}>
            <span style={{ width: 22, height: 22, borderRadius: '50%', border: '1px solid rgba(242,167,59,0.4)', display: 'grid', placeItems: 'center', color: 'var(--amber)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>1</span>
            {t('quest.step1' as never)}
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', color: 'var(--ink-3)', fontSize: '0.82rem' }}>
            <span style={{ width: 22, height: 22, borderRadius: '50%', border: '1px solid rgba(242,167,59,0.4)', display: 'grid', placeItems: 'center', color: 'var(--amber)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>2</span>
            {t('quest.step2' as never)}
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', color: 'var(--ink-3)', fontSize: '0.82rem' }}>
            <span style={{ width: 22, height: 22, borderRadius: '50%', border: '1px solid rgba(242,167,59,0.4)', display: 'grid', placeItems: 'center', color: 'var(--amber)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>3</span>
            {t('quest.step3' as never)}
          </div>
        </div>
        <button
          type="button"
          className="btn btn--amber"
          onClick={() => void AirCast.openCastPage({})}
        >
          <ScreenIcon /> {t('quest.open')}
        </button>
        <p className="note">{t('quest.castFailed' as never)}</p>
        <p className="note note--muted">{t('quest.note')}</p>
      </Panel>

      {settings?.mirrorEnabled && (
        <Panel title={t('mirror.title')} index={2}>
          <p style={{ margin: '0 0 4px', color: 'var(--ink-2)', fontSize: '0.87rem' }}>
            {t('mirror.howto')}
          </p>
          <div className="urlbox">
            <code>{mirrorUrl || '—'}</code>
            <button
              type="button"
              className="btn btn--slim"
              onClick={() => void copy()}
              aria-label={t('guide.copy')}
            >
              <CopyIcon />
            </button>
            <button
              type="button"
              className="btn btn--slim"
              onClick={() => void AirCast.openExternal({ url: mirrorUrl })}
              aria-label={t('guide.open')}
            >
              <ExternalIcon />
            </button>
          </div>
          {qr && <img className="qr" src={qr} alt={mirrorUrl} />}
          <p className="note">{t('mirror.certNote')}</p>
          {!status?.tlsReady && status?.running && (
            <p className="note note--muted">{t('mirror.tlsMissing')}</p>
          )}
        </Panel>
      )}
    </>
  );
}
