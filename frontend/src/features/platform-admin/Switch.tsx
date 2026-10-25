/** A switch, not a checkbox: these are settings that take effect at once, not a form to submit. */
export function Switch({ on, busy, label, onChange }: { on: boolean; busy: boolean; label: string; onChange: () => void }) {
  return (
    <button
      type="button"
      className={`swtch${on ? ' swtch--on' : ''}`}
      role="switch"
      aria-checked={on}
      aria-label={label}
      disabled={busy}
      onClick={onChange}
    >
      <span className="swtch__dot" />
    </button>
  );
}
