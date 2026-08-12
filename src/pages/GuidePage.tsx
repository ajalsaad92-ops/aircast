import { useReceiver } from '../hooks/useReceiver';
import { Panel } from '../components/ui';
import { CopyIcon, ExternalIcon } from '../components/Icons';
import { AirCast } from '../lib/aircast';

export function GuidePage() {
  const { status, t, showToast } = useReceiver();
  const name = status?.deviceName ?? t('app.name');
  const landing = status?.landingUrl ?? '';

  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      showToast(t('guide.copied'));
    } catch {
      showToast(value);
    }
  };

  return (
    <>
      <Panel title={t('guide.title')} index={0}>
        <p style={{ margin: 0, color: 'var(--ink-2)', fontSize: '0.87rem' }}>
          {t('guide.browser.1')}
        </p>
        <div className="urlbox">
          <code>{landing || '—'}</code>
          <button
            type="button"
            className="btn btn--slim"
            onClick={() => void copy(landing)}
            aria-label={t('guide.copy')}
          >
            <CopyIcon />
          </button>
          <button
            type="button"
            className="btn btn--slim"
            onClick={() => void AirCast.openExternal({ url: landing })}
            aria-label={t('guide.open')}
          >
            <ExternalIcon />
          </button>
        </div>
      </Panel>

      <Panel title={t('guide.ios')} index={1}>
        <ol className="steps">
          <li>{t('guide.ios.1')}</li>
          <li>{t('guide.ios.2')}</li>
          <li>{t('guide.ios.3', { name })}</li>
        </ol>
        <p className="note">{t('guide.ios.note')}</p>
      </Panel>

      <Panel title={t('guide.android')} index={2}>
        <ol className="steps">
          <li>{t('guide.android.1')}</li>
          <li>{t('guide.android.2', { name })}</li>
          <li>{t('guide.android.3')}</li>
        </ol>
      </Panel>

      <Panel title={t('guide.windows')} index={3}>
        <ol className="steps">
          <li>{t('guide.windows.1')}</li>
          <li>{t('guide.windows.2')}</li>
        </ol>
      </Panel>

      <Panel title={t('settings.notSupported')} index={4}>
        <p className="note note--muted" style={{ marginTop: 0 }}>
          {t('settings.notSupported.body')}
        </p>
      </Panel>
    </>
  );
}
