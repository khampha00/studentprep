import type { MouseEvent } from 'react';
import { MoreVertical } from 'lucide-react';
import type { OpenMenuState, RowActionMenuItem } from '../../hooks/useRowActionMenu';

const TONE_CLASSES: Record<NonNullable<RowActionMenuItem['tone']>, string> = {
  default: 'text-slate-700 hover:bg-slate-50',
  emerald: 'text-[#008751] hover:bg-emerald-50/60',
  red: 'text-red-700 hover:bg-red-50/60',
  blue: 'text-blue-700 hover:bg-blue-50/60',
  muted: 'text-slate-400',
};

interface RowActionMenuProps {
  menuId: string;
  openMenu: OpenMenuState | null;
  onToggle: (id: string, e: MouseEvent<HTMLButtonElement>) => void;
  onClose: () => void;
  items: RowActionMenuItem[];
  ariaLabel?: string;
  testId?: string;
}

export function RowActionMenu({
  menuId,
  openMenu,
  onToggle,
  onClose,
  items,
  ariaLabel = 'Row actions',
  testId,
}: RowActionMenuProps) {
  const isOpen = openMenu?.id === menuId;

  return (
    <>
      <button
        type="button"
        onClick={(e) => onToggle(menuId, e)}
        data-testid={testId}
        aria-label={ariaLabel}
        aria-expanded={isOpen}
        aria-haspopup="menu"
        className="inline-flex items-center justify-center w-8 h-8 rounded-md border border-slate-200 bg-white hover:bg-slate-100 text-slate-700 transition-colors cursor-pointer"
      >
        <MoreVertical className="w-4 h-4" />
      </button>

      {isOpen && openMenu && (
        <>
          <div className="fixed inset-0 z-40" onClick={onClose} />
          <div
            role="menu"
            style={{ top: openMenu.top, right: openMenu.right }}
            className="fixed z-50 w-48 rounded-lg border border-slate-200 bg-white py-1.5 shadow-lg text-left"
          >
            {items.map((item) => {
              const toneClass = TONE_CLASSES[item.tone || 'default'];
              return (
                <div key={item.key}>
                  {item.dividerBefore && <div className="my-1 border-t border-slate-100" />}
                  <button
                    type="button"
                    role="menuitem"
                    disabled={item.disabled}
                    data-testid={item.testId}
                    title={item.title}
                    onClick={() => {
                      onClose();
                      item.onClick();
                    }}
                    className={`w-full px-3.5 py-2 text-xs font-semibold flex items-center gap-2 transition-colors ${toneClass} ${
                      item.disabled
                        ? 'opacity-40 cursor-not-allowed'
                        : 'cursor-pointer'
                    }`}
                  >
                    {item.icon}
                    {item.label}
                  </button>
                </div>
              );
            })}
          </div>
        </>
      )}
    </>
  );
}
