import { useEffect, useRef, type ReactNode } from 'react';
import './sbs.css';

/**
 * A modal dialog for one decision or one short form.
 *
 * <p>Escape and the scrim both close it; focus moves into it on open and back
 * to what opened it on close, so keyboard users are never stranded behind it.
 */
export function Dialog({
  title,
  subtitle,
  onClose,
  children,
  footer,
  width = 560,
}: {
  title: string;
  subtitle?: ReactNode;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  width?: number;
}) {
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const first = panel.current?.querySelector<HTMLElement>(
      'input, textarea, select, button:not(.dialog__close)',
    );
    (first ?? panel.current)?.focus();

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      opener?.focus?.();
    };
  }, [onClose]);

  return (
    <div className="dialog-layer">
      <button type="button" className="dialog-scrim" aria-label="Close" onClick={onClose} />
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        ref={panel}
        tabIndex={-1}
        style={{ width: `min(${width}px, calc(100vw - 32px))` }}
      >
        <header className="dialog__head">
          <div>
            <h2 className="dialog__title">{title}</h2>
            {subtitle && <p className="dialog__sub">{subtitle}</p>}
          </div>
          <button type="button" className="dialog__close" onClick={onClose} aria-label="Close">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </button>
        </header>
        <div className="dialog__body">{children}</div>
        {footer && <footer className="dialog__foot">{footer}</footer>}
      </div>
    </div>
  );
}

/** A labelled form control with optional hint and error, laid out once. */
export function Field({
  label,
  htmlFor,
  hint,
  children,
}: {
  label: string;
  htmlFor: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="ffield">
      <label className="ffield__label" htmlFor={htmlFor}>
        {label}
      </label>
      {children}
      {hint && <p className="ffield__hint">{hint}</p>}
    </div>
  );
}

export function FormError({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p className="form-error" role="alert">
      {message}
    </p>
  );
}
