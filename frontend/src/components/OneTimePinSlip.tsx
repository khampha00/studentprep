import { Printer, ShieldAlert } from 'lucide-react';
import { Button } from './ui/button';

export interface OneTimePinSlipEntry {
  name: string;
  registrationNumber: string;
  subjects?: string[];
  oneTimePin: string;
}

interface OneTimePinSlipProps {
  title: string;
  subtitle?: string;
  entries: OneTimePinSlipEntry[];
  onDone?: () => void;
  doneLabel?: string;
}

export default function OneTimePinSlip({
  title,
  subtitle,
  entries,
  onDone,
  doneLabel = 'Done',
}: OneTimePinSlipProps) {
  if (entries.length === 0) {
    return null;
  }

  return (
    <div
      data-testid="one-time-pin-slip"
      className="bg-slate-50 border-2 border-[#008751] rounded-xl p-5 space-y-4 text-slate-900 font-sans"
    >
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-slate-200 pb-3">
        <div>
          <h3 className="text-lg font-bold text-slate-900">{title}</h3>
          {subtitle && <p className="text-xs text-slate-600 mt-0.5">{subtitle}</p>}
        </div>
        <div className="flex items-center gap-2 print:hidden">
          <Button
            type="button"
            size="sm"
            onClick={() => window.print()}
            className="bg-[#008751] hover:bg-[#007040] text-white"
          >
            <Printer className="w-4 h-4 mr-1.5" />
            Print One-Time Slip
          </Button>
          {onDone && (
            <Button type="button" size="sm" variant="outline" onClick={onDone}>
              {doneLabel}
            </Button>
          )}
        </div>
      </div>

      <div className="flex items-start gap-2.5 p-3 rounded-lg bg-amber-50 border border-amber-300 text-amber-950 text-xs font-medium">
        <ShieldAlert className="w-4 h-4 shrink-0 mt-0.5 text-amber-700" />
        <span>
          <strong>One-Time Credential Notice:</strong> This temporary PIN is shown once only. Print or distribute this slip now—after closing, the PIN cannot be retrieved from the system.
        </span>
      </div>

      <div className="space-y-3 max-h-80 overflow-y-auto pr-1">
        {entries.map((entry) => (
          <div
            key={`${entry.registrationNumber}-${entry.oneTimePin}`}
            className="bg-zinc-50 border border-slate-300 rounded-lg p-4 shadow-xs"
          >
            <div className="grid grid-cols-2 gap-y-2 gap-x-4 text-sm">
              <div className="col-span-2">
                <div className="text-[11px] uppercase tracking-wider font-semibold text-slate-500">
                  Candidate Name
                </div>
                <div className="font-semibold text-slate-900">{entry.name}</div>
              </div>
              <div>
                <div className="text-[11px] uppercase tracking-wider font-semibold text-slate-500">
                  Registration Number
                </div>
                <div className="font-mono font-bold text-slate-900">{entry.registrationNumber}</div>
              </div>
              <div>
                <div className="text-[11px] uppercase tracking-wider font-semibold text-slate-500">
                  One-Time Login PIN
                </div>
                <div
                  data-testid="revealed-one-time-pin"
                  className="inline-block mt-0.5 px-2.5 py-0.5 bg-emerald-50 border border-[#008751] rounded font-mono font-bold text-base text-[#008751]"
                >
                  {entry.oneTimePin}
                </div>
              </div>
              {entry.subjects && entry.subjects.length > 0 && (
                <div className="col-span-2 pt-2 border-t border-slate-200">
                  <div className="text-[11px] uppercase tracking-wider font-semibold text-slate-500">
                    Enrolled Subjects
                  </div>
                  <div className="text-xs font-medium text-slate-800 mt-0.5">
                    {entry.subjects.join(', ')}
                  </div>
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
