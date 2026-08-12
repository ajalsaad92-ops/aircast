import { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { useReceiver } from '../hooks/useReceiver';
import { useMirror } from '../hooks/useMirror';
import { Panel } from '../components/ui';
import { CopyIcon, ExternalIcon, RecordIcon, StopIcon } from '../components/Icons';
import { AirCast } from '../lib/aircast';

export function MirrorPage() {
  const { status, settings, t, showToast } = useReceiver();
  const enabled = (settings?.mirrorEnabled ?? false) && (status?.running ?? false);
  const { state, session, stream, stop } = useMirror(status?.ip, enabled);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const mirrorUrl = status?.mirrorUrl ?? '';

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    video.srcObject = stream;
    if (stream) void video.play().catch(() => undefined);
  }, [stream]);

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

  return (
    <>
      <Panel index={0}>
        <div className="stage">
          <video ref={videoRef} muted={false} playsInline autoPlay />
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
          {state === 'live' && (
            <span className="livetag">
              <span className="livetag__dot" />
              {t('mirror.live')} · {session?.name}
            </span>
          )}
        </div>

        {state === 'live' ? (
          <button
            type="button"
            className="btn btn--danger"
            style={{ marginTop: 14 }}
            onClick={() => void stop()}
          >
            <StopIcon /> {t('mirror.stop')}
          </button>
        ) : (
          <button
            type="button"
            className={status?.recording ? 'btn btn--danger' : 'btn'}
            style={{ marginTop: 14 }}
            onClick={() => void toggleRecording()}
          >
            {status?.recording ? <StopIcon /> : <RecordIcon />}
            {status?.recording ? t('record.stop') : t('record.start')}
          </button>
        )}
      </Panel>

      {settings?.mirrorEnabled && (
        <Panel title={t('mirror.title')} index={1}>
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
