import { useMemo, type MouseEvent } from 'react';
import {
  ShieldAlert,
  Eye,
  Download,
  CheckCircle,
  Ban,
  RotateCcw,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { DataTable, type DataTableColumn, type DataTableFilterConfig } from '../ui/data-table';
import { RowActionMenu } from './RowActionMenu';
import type { OpenMenuState, RowActionMenuItem } from '../../hooks/useRowActionMenu';

export interface FlaggedSession {
  sessionId: string;
  studentId: string;
  studentName: string;
  registrationNumber: string;
  status: string;
  tabSwitchCount: number;
  startTime: string;
  expectedEndTime?: string;
  endTime?: string;
  resultId?: string;
  moderationStatus?: string;
}

interface FlaggedSessionsCardProps {
  title: string;
  description: string;
  emptyMessage: string;
  sessions: FlaggedSession[];
  isAdmin: boolean;
  moderatingId: string | null;
  loadingScriptId: string | null;
  downloadingScriptId: string | null;
  openMenu: OpenMenuState | null;
  onToggleMenu: (id: string, e: MouseEvent<HTMLButtonElement>) => void;
  onCloseMenu: () => void;
  onInspectScript: (sessionId: string) => void;
  onDownloadScript: (
    sessionId: string,
    studentName?: string,
    registrationNumber?: string
  ) => void;
  onClearLate: (sessionId: string) => void;
  onVoidSession: (sessionId: string) => void;
  onGrantRetake: (studentId: string, sessionId: string) => void;
  titleLarge?: boolean;
}

export function FlaggedSessionsCard({
  title,
  description,
  emptyMessage,
  sessions,
  isAdmin,
  moderatingId,
  loadingScriptId,
  downloadingScriptId,
  openMenu,
  onToggleMenu,
  onCloseMenu,
  onInspectScript,
  onDownloadScript,
  onClearLate,
  onVoidSession,
  onGrantRetake,
  titleLarge = false,
}: FlaggedSessionsCardProps) {
  const buildSessionItems = (session: FlaggedSession): RowActionMenuItem[] => {
    const items: RowActionMenuItem[] = [
      {
        key: 'inspect-script',
        label: loadingScriptId === session.sessionId ? 'Loading...' : 'Inspect Script',
        icon: <Eye className="w-3.5 h-3.5 text-[#008751]" />,
        disabled: loadingScriptId === session.sessionId,
        onClick: () => onInspectScript(session.sessionId),
      },
      {
        key: 'script-pdf',
        label: 'Script (PDF)',
        icon: <Download className="w-3.5 h-3.5" />,
        tone: 'emerald',
        disabled: downloadingScriptId === session.sessionId,
        onClick: () =>
          onDownloadScript(
            session.sessionId,
            session.studentName,
            session.registrationNumber
          ),
      },
    ];

    if (session.status === 'LATE_SUBMISSION_FLAGGED') {
      items.push({
        key: 'clear-late',
        label: 'Clear Late',
        icon: <CheckCircle className="w-3.5 h-3.5" />,
        tone: 'emerald',
        dividerBefore: true,
        disabled: !isAdmin || moderatingId === session.sessionId,
        onClick: () => onClearLate(session.sessionId),
      });
    }

    items.push(
      {
        key: 'void',
        label: 'Void',
        icon: <Ban className="w-3.5 h-3.5" />,
        tone: 'red',
        dividerBefore: session.status !== 'LATE_SUBMISSION_FLAGGED',
        disabled: !isAdmin || moderatingId === session.sessionId,
        onClick: () => onVoidSession(session.sessionId),
      },
      {
        key: 'grant-retake',
        label: 'Grant Retake',
        icon: <RotateCcw className="w-3.5 h-3.5" />,
        tone: 'blue',
        disabled: !isAdmin || moderatingId === session.sessionId,
        onClick: () => onGrantRetake(session.studentId, session.sessionId),
      }
    );

    return items;
  };

  const columns: DataTableColumn<FlaggedSession>[] = [
    {
      key: 'studentName',
      header: 'Candidate',
      sortable: true,
      sortType: 'text',
      sortValue: (s) => s.studentName,
      searchValue: (s) => s.studentName,
      render: (s) => <span className="font-semibold text-slate-900">{s.studentName}</span>,
    },
    {
      key: 'registrationNumber',
      header: 'Reg Number',
      sortable: true,
      sortType: 'text',
      sortValue: (s) => s.registrationNumber,
      searchValue: (s) => s.registrationNumber,
      render: (s) => <span className="font-mono text-xs text-slate-700">{s.registrationNumber}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      sortable: true,
      sortType: 'text',
      sortValue: (s) => s.status,
      searchValue: (s) => s.status,
      render: (s) => (
        <span
          className={`inline-block px-2.5 py-1 text-xs font-bold rounded-full ${
            s.status === 'FLAGGED_TAB_SWITCH'
              ? 'bg-red-100 text-red-800'
              : s.status === 'LATE_SUBMISSION_FLAGGED'
              ? 'bg-amber-100 text-amber-800'
              : 'bg-slate-100 text-slate-800'
          }`}
        >
          {s.status}
        </span>
      ),
    },
    {
      key: 'tabSwitchCount',
      header: 'Strikes',
      sortable: true,
      sortType: 'number',
      sortValue: (s) => s.tabSwitchCount ?? 0,
      render: (s) => (
        <span className="font-bold text-slate-800 tabular-nums">{s.tabSwitchCount ?? 0}</span>
      ),
    },
    {
      key: 'moderationStatus',
      header: 'Moderation',
      sortable: true,
      sortType: 'text',
      sortValue: (s) => s.moderationStatus || 'PENDING',
      searchValue: (s) => s.moderationStatus || 'PENDING',
      render: (s) => (
        <span className="text-xs font-semibold text-slate-700">
          {s.moderationStatus || 'PENDING'}
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      hideLabelOnMobile: true,
      render: (s) => (
        <RowActionMenu
          menuId={`flagged-${s.sessionId}`}
          openMenu={openMenu}
          onToggle={onToggleMenu}
          onClose={onCloseMenu}
          ariaLabel="Session actions"
          testId={`flagged-actions-${s.sessionId}`}
          items={buildSessionItems(s)}
        />
      ),
    },
  ];

  const filters: DataTableFilterConfig<FlaggedSession>[] = useMemo(() => {
    const statusValues = Array.from(new Set(sessions.map((s) => s.status).filter(Boolean))).sort();
    const moderationValues = Array.from(
      new Set(sessions.map((s) => s.moderationStatus || 'PENDING'))
    ).sort();

    return [
      {
        key: 'status',
        label: 'Status',
        options: statusValues.map((status) => ({ value: status, label: status })),
        predicate: (row, selected) => row.status === selected,
      },
      {
        key: 'moderation',
        label: 'Moderation',
        options: moderationValues.map((mod) => ({ value: mod, label: mod })),
        predicate: (row, selected) => (row.moderationStatus || 'PENDING') === selected,
      },
    ];
  }, [sessions]);

  return (
    <Card className="border-t-4 border-t-amber-500 bg-white shadow-sm">
      <CardHeader className="pb-4 border-b border-slate-100 flex flex-row items-center justify-between">
        <div>
          <CardTitle
            className={`${
              titleLarge ? 'text-xl' : 'text-lg'
            } font-bold text-slate-900 flex items-center gap-2`}
          >
            <ShieldAlert className="w-5 h-5 text-amber-600" />
            {title}
          </CardTitle>
          <p className="text-xs text-slate-500 mt-1">{description}</p>
        </div>
        {!isAdmin && (
          <span className="text-xs font-semibold bg-slate-100 text-slate-600 px-2.5 py-1 rounded-full border border-slate-200">
            Read-only (Supervisor)
          </span>
        )}
      </CardHeader>
      <CardContent className="p-0">
        {sessions.length === 0 ? (
          <div className="p-6 text-sm text-slate-500 text-center">{emptyMessage}</div>
        ) : (
          <DataTable
            caption={title}
            data={sessions}
            columns={columns}
            getRowKey={(s) => s.sessionId}
            mode="client"
            defaultPageSize={10}
            pageSizeOptions={[5, 10, 20, 50]}
            itemLabel={['session', 'sessions']}
            searchPlaceholder="Search candidate or reg no..."
            filters={filters}
            emptyMessage={emptyMessage}
            className="border-0 rounded-none rounded-b-xl shadow-none"
          />
        )}
      </CardContent>
    </Card>
  );
}
