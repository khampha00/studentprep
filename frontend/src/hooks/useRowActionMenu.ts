import { useState, useEffect, type MouseEvent, type ReactNode } from 'react';

export interface RowActionMenuItem {
  key: string;
  label: string;
  icon?: ReactNode;
  onClick: () => void;
  disabled?: boolean;
  tone?: 'default' | 'emerald' | 'red' | 'blue' | 'muted';
  dividerBefore?: boolean;
  title?: string;
  testId?: string;
}

export interface OpenMenuState {
  id: string;
  top: number;
  right: number;
}

export function useRowActionMenu() {
  const [openMenu, setOpenMenu] = useState<OpenMenuState | null>(null);

  useEffect(() => {
    if (!openMenu) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpenMenu(null);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [openMenu]);

  const toggleMenu = (id: string, e: MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    if (openMenu?.id === id) {
      setOpenMenu(null);
      return;
    }
    const rect = e.currentTarget.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const top = spaceBelow < 220 ? Math.max(12, rect.top - 190) : rect.bottom + 4;
    const right = Math.max(12, window.innerWidth - rect.right);
    setOpenMenu({ id, top, right });
  };

  const closeMenu = () => setOpenMenu(null);

  return { openMenu, toggleMenu, closeMenu };
}
