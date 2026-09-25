import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { uploadImport } from '@/api/imports';
import { Button } from '@/design-system/Console';
import { FormError } from '@/design-system/Dialog';
import { errorText } from '@/features/admin/AdminParts';

/**
 * "Import from Excel" wherever a list can be added to by hand. The file goes
 * through the same import as the Data import page, and this lands on its
 * preview: nothing is applied from here, only staged.
 */
export function ImportFileButton({ vesselId, label = 'Import from Excel' }: { vesselId: number; label?: string }) {
  const navigate = useNavigate();
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const upload = async (file: File | undefined) => {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const batch = await uploadImport(file, vesselId);
      navigate(`/fleet/import?batch=${batch.id}`);
    } catch (e) {
      setError(errorText(e, 'The file could not be read.'));
      setBusy(false);
    } finally {
      if (input.current) input.current.value = '';
    }
  };

  return (
    <>
      <input
        ref={input}
        type="file"
        accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        hidden
        onChange={(e) => upload(e.target.files?.[0])}
      />
      <Button onClick={() => input.current?.click()} disabled={busy} title={error ?? undefined}>
        {busy ? 'Reading…' : label}
      </Button>
      <FormError message={error} />
    </>
  );
}
