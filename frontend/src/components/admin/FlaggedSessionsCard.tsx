import type { MouseEvent } from 'react';
import {
  ShieldAlert,
  Eye,
  Download,
  CheckCircle,
  Ban,
  RotateCcw,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
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
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm text-slate-600">
              <thead className="bg-slate-50 text-xs font-bold uppercase tracking-wider text-slate-700 border-b border-slate-200">
                <tr>
                  <th className="p-4">Candidate</th>
                  <th className="p-4">Reg Number</th>
                  <th className="p-4">Status</th>
                  <th className="p-4">Strikes</th>
                  <th className="p-4">Moderation</th>
                  <th className="p-4 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sessions.map((session) => (
                  <tr key={session.sessionId} className="hover:bg-slate-50/80 transition-colors">
                    <td className="p-4 font-semibold text-slate-900">{session.studentName}</td>
                    <td className="p-4 font-mono text-xs">{session.registrationNumber}</td>
                    <td className="p-4">
                      <span
                        className={`inline-block px-2.5 py-1 text-xs font-bold rounded-full ${
                          session.status === 'FLAGGED_TAB_SWITCH'
                            ? 'bg-red-100 text-red-800'
                            : session.status === 'LATE_SUBMISSION_FLAGGED'
                            ? 'bg-amber-100 text-amber-800'
                            : 'bg-slate-100 text-slate-800'
                        }`}
                      >
                        {session.status}
                      </span>
                    </td>
                    <td className="p-4 font-bold text-slate-800">{session.tabSwitchCount ?? 0}</td>
                    <td className="p-4">
                      <span className="text-xs font-semibold text-slate-700">
                        {session.moderationStatus || 'PENDING'}
                      </span>
                    </td>
                    <td className="p-4 text-right">
                      <RowActionMenu
                        menuId={`flagged-${session.sessionId}`}
                        openMenu={openMenu}
                        onToggle={onToggleMenu}
                        onClose={onCloseMenu}
                        ariaLabel="Session actions"
                        testId={`flagged-actions-${session.sessionId}`}
                        items={buildSessionItems(session)}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
