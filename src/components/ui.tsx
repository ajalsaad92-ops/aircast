import type { ReactNode } from 'react';

export function Panel({
  title,
  children,
  flush,
  index = 0,
  action,
}: {
  title?: string;
  children: ReactNode;
  flush?: boolean;
  index?: number;
  action?: ReactNode;
}) {
  return (
    <section
      className={`panel reveal${flush ? ' panel--flush' : ''}`}
      style={{ ['--i' as string]: index }}
    >
      {title && (
        <h2 className="panel__title" style={flush ? { padding: 'var(--pad) var(--pad) 0' } : undefined}>
          <span>{title}</span>
          {action}
        </h2>
      )}
      {children}
    </section>
  );
}

export function Switch({
  checked,
  onChange,
  label,
  disabled,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      className="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    />
  );
}

export function Meta({ items }: { items: Array<{ k: string; v: string; accent?: boolean }> }) {
  return (
    <div className="meta">
      {items.map((item) => (
        <div className="meta__cell" key={item.k}>
          <div className="meta__k">{item.k}</div>
          <div className={`meta__v${item.accent ? ' meta__v--amber' : ''}`}>{item.v}</div>
        </div>
      ))}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

export function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span className="field__label">{label}</span>
      {children}
      {hint && <span className="field__hint">{hint}</span>}
    </label>
  );
}
