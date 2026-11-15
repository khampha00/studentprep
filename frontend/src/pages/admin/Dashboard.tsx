import { useEffect, useState, useCallback } from 'react';
import axios from 'axios';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Accordion, AccordionItem, AccordionTrigger, AccordionContent } from '../../components/ui/accordion';
import { toast } from 'sonner';
import { getStoredToken, decodeJwtPayload } from '../../lib/auth';
import { useRowActionMenu } from '../../hooks/useRowActionMenu';
import { useExamScriptAudit } from '../../hooks/useExamScriptAudit';
import { ExamScriptAuditModal } from '../../components/admin/ExamScriptAuditModal';
import {
  FlaggedSessionsCard,
  type FlaggedSession,
} from '../../components/admin/FlaggedSessionsCard';

export default function Dashboard() {
  const [data, setData] = useState<{
    totalStudents: number;
    completedExams: number;
    averageScore: number;
    passRate: number;
  } | null>(null);

  const [liveSessions, setLiveSessions] = useState<{
    sessionId: string;
    studentName: string;
    timeLeft: number;
    status: string;
  }[]>([]);

  const [subjectAnalytics, setSubjectAnalytics] = useState<{
    subjectName: string;
    averageScore: number;
  }[]>([]);

  const [leaderboard, setLeaderboard] = useState<{
    studentName: string;
    score: number;
  }[]>([]);

  const [registrations, setRegistrations] = useState<{
    subjectName: string;
    enrollmentCount: number;
  }[]>([]);

  const [recentResults, setRecentResults] = useState<{
    id: string;
    studentName: string;
    totalScore: number;
    maxScore: number;
    gradedAt: string;
    topicBreakdown: Record<string, { correct: number; total: number }>;
  }[]>([]);

  const [flaggedSessions, setFlaggedSessions] = useState<FlaggedSession[]>([]);
  const [moderatingId, setModeratingId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const { openMenu, toggleMenu, closeMenu } = useRowActionMenu();
  const {
    auditScript,
    closeAuditScript,
    loadingScriptId,
    downloadingScriptId,
    inspectScript,
    downloadExamScript,
  } = useExamScriptAudit();

  const token = getStoredToken();
  const userRole = decodeJwtPayload(token)?.role;
  const isAdmin = userRole === 'ROLE_ADMIN';

  const fetchFlaggedSessions = useCallback(() => {
    axios.get('/api/v1/admin/analytics/flagged-sessions')
      .then((res) => {
        if (res?.data?.data) {
          setFlaggedSessions(Array.isArray(res.data.data) ? res.data.data : []);
        }
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    const fetchDashboard = () => axios.get('/api/v1/admin/analytics/dashboard').catch(() => null);
    const fetchLive = () => axios.get('/api/v1/admin/analytics/live-sessions').catch(() => null);
    const fetchSubjects = () => axios.get('/api/v1/admin/analytics/subjects').catch(() => null);
    const fetchLeaderboard = () => axios.get('/api/v1/admin/analytics/leaderboard?topN=5').catch(() => null);
    const fetchRegistrations = () => axios.get('/api/v1/admin/analytics/subject-registrations').catch(() => null);
    const fetchRecent = () => axios.get('/api/v1/admin/analytics/results?page=0&size=5').catch(() => null);
    const fetchFlagged = () => axios.get('/api/v1/admin/analytics/flagged-sessions').catch(() => null);

    Promise.all([
      fetchDashboard(),
      fetchLive(),
      fetchSubjects(),
      fetchLeaderboard(),
      fetchRegistrations(),
      fetchRecent(),
      fetchFlagged(),
    ])
      .then(([dashRes, liveRes, subjRes, leadRes, regRes, recRes, flagRes]) => {
        if (dashRes?.data?.data) setData(dashRes.data.data);
        if (liveRes?.data?.data) setLiveSessions(liveRes.data.data);
        if (subjRes?.data?.data) setSubjectAnalytics(subjRes.data.data);
        if (leadRes?.data?.data) setLeaderboard(leadRes.data.data);
        if (regRes?.data?.data) setRegistrations(regRes.data.data);
        if (recRes?.data?.data) {
          setRecentResults(Array.isArray(recRes.data.data) ? recRes.data.data : recRes.data.data.content || []);
        }
        if (flagRes?.data?.data) {
          setFlaggedSessions(Array.isArray(flagRes.data.data) ? flagRes.data.data : []);
        }
      })
      .catch(() => toast.error('Failed to load dashboard data'))
      .finally(() => setLoading(false));

    const pollInterval = setInterval(() => {
      fetchLive().then((res) => {
        if (res?.data?.data) setLiveSessions(res.data.data);
      });
      fetchFlaggedSessions();
    }, 10000);

    return () => clearInterval(pollInterval);
  }, [fetchFlaggedSessions]);

  const handleClearLate = async (sessionId: string) => {
    setModeratingId(sessionId);
    try {
      await axios.post(`/api/v1/admin/exams/sessions/${sessionId}/clear-late`);
      toast.success('Late submission cleared and promoted to valid submission.');
      fetchFlaggedSessions();
    } catch (err: any) {
      toast.error(err?.response?.data?.detail || 'Failed to clear late submission.');
    } finally {
      setModeratingId(null);
    }
  };

  const handleVoidSession = async (sessionId: string) => {
    setModeratingId(sessionId);
    try {
      await axios.post(`/api/v1/admin/exams/sessions/${sessionId}/void`);
      toast.success('Session result voided successfully.');
      fetchFlaggedSessions();
    } catch (err: any) {
      toast.error(err?.response?.data?.detail || 'Failed to void session.');
    } finally {
      setModeratingId(null);
    }
  };

  const handleGrantRetake = async (studentId: string, sessionId: string) => {
    setModeratingId(sessionId);
    try {
      await axios.post(`/api/v1/admin/students/${studentId}/grant-retake`);
      toast.success('Retake granted: Student session superseded for a new sitting.');
      fetchFlaggedSessions();
    } catch (err: any) {
      toast.error(err?.response?.data?.detail || 'Failed to grant retake.');
    } finally {
      setModeratingId(null);
    }
  };

  return (
    <div className="p-8 font-sans bg-slate-50 min-h-screen">
      <div className="w-full max-w-[100vw] mx-auto px-2 lg:px-8 space-y-8">
        <h1 className="text-3xl font-bold text-slate-900">Admin Dashboard</h1>

        {loading ? (
          <div className="text-slate-500">Loading metrics...</div>
        ) : (
          <>
            {data && (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-sm font-medium text-slate-500">Total Students</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="text-2xl font-bold text-[#008751]">{data.totalStudents}</div>
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-sm font-medium text-slate-500">Completed Exams</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="text-2xl font-bold text-[#008751]">{data.completedExams}</div>
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-sm font-medium text-slate-500">Average Score</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="text-2xl font-bold text-[#008751]">{data.averageScore.toFixed(1)}%</div>
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-sm font-medium text-slate-500">Pass Rate</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="text-2xl font-bold text-[#008751]">{data.passRate.toFixed(1)}%</div>
                  </CardContent>
                </Card>
              </div>
            )}

            <FlaggedSessionsCard
              title="Pending Moderation & Flagged Sessions"
              description="Review candidate sessions flagged for proctoring violations (Tab Switching), late offline submissions, or auto-expirations."
              emptyMessage="No sessions currently pending moderation or flagged for review."
              sessions={flaggedSessions}
              isAdmin={isAdmin}
              moderatingId={moderatingId}
              loadingScriptId={loadingScriptId}
              downloadingScriptId={downloadingScriptId}
              openMenu={openMenu}
              onToggleMenu={toggleMenu}
              onCloseMenu={closeMenu}
              onInspectScript={inspectScript}
              onDownloadScript={downloadExamScript}
              onClearLate={handleClearLate}
              onVoidSession={handleVoidSession}
              onGrantRetake={handleGrantRetake}
              titleLarge
            />

            <Accordion defaultValue={["live"]} className="space-y-4">
              <AccordionItem value="live" className="border-none">
                <AccordionTrigger className="hover:no-underline bg-slate-50 px-4 py-2 rounded-t-xl border border-slate-200">
                  <span className="font-bold text-slate-900 text-lg">Active Exam Sessions</span>
                </AccordionTrigger>
                <AccordionContent className="p-4 bg-slate-50 border-x border-b border-slate-200 rounded-b-xl">
                  {liveSessions.length === 0 ? (
                    <div className="text-slate-500 text-sm">No live exam sessions at the moment.</div>
                  ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                      {liveSessions.map((session) => (
                        <Card key={session.sessionId} className="bg-slate-50">
                          <CardContent className="p-4">
                            <div className="font-semibold text-slate-900 mb-1">{session.studentName}</div>
                            <div className="text-sm text-slate-500 mb-2">Session ID: {session.sessionId.slice(0, 8)}...</div>
                            <div className="flex justify-between items-center text-sm">
                              <span className="text-slate-600">Status: <span className="font-semibold text-emerald-600">{session.status}</span></span>
                              <span className="font-mono text-slate-700">Time Left: {Math.floor(session.timeLeft / 60)}m</span>
                            </div>
                          </CardContent>
                        </Card>
                      ))}
                    </div>
                  )}
                </AccordionContent>
              </AccordionItem>

              <AccordionItem value="analytics" className="border-none">
                <AccordionTrigger className="hover:no-underline bg-slate-50 px-4 py-2 rounded-t-xl border border-slate-200 mt-4">
                  <span className="font-bold text-slate-900 text-lg">Subject Performance & Analytics</span>
                </AccordionTrigger>
                <AccordionContent className="p-4 bg-slate-50 border-x border-b border-slate-200 rounded-b-xl">
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    {/* Subject Performance */}
                    <Card className="bg-slate-50">
                      <CardHeader>
                        <CardTitle className="text-lg font-bold text-slate-900">Subject Averages</CardTitle>
                      </CardHeader>
                      <CardContent>
                        {subjectAnalytics.length === 0 ? (
                          <div className="text-slate-500 text-sm">No subject analytics available.</div>
                        ) : (
                          <div className="space-y-4">
                            {subjectAnalytics.map((subject, i) => (
                              <div key={i} className="flex justify-between items-center p-3 bg-slate-50 rounded border border-slate-100">
                                <div className="font-semibold text-slate-900">{subject.subjectName}</div>
                                <div className="font-bold text-[#008751]">{subject.averageScore.toFixed(1)}%</div>
                              </div>
                            ))}
                          </div>
                        )}
                      </CardContent>
                    </Card>

                    {/* Top Students */}
                    <Card className="bg-slate-50">
                      <CardHeader>
                        <CardTitle className="text-lg font-bold text-slate-900">Top Performing Students</CardTitle>
                      </CardHeader>
                      <CardContent>
                        {leaderboard.length === 0 ? (
                          <div className="text-slate-500 text-sm">No leaderboard data available.</div>
                        ) : (
                          <div className="space-y-4">
                            {leaderboard.map((student, i) => (
                              <div key={i} className="flex justify-between items-center p-3 bg-slate-50 rounded border border-slate-100">
                                <div className="flex items-center gap-3">
                                  <div className="font-bold text-slate-700 w-6">{i + 1}.</div>
                                  <div className="font-semibold text-slate-900">{student.studentName}</div>
                                </div>
                                <div className="font-bold text-[#008751]">{student.score.toFixed(1)}</div>
                              </div>
                            ))}
                          </div>
                        )}
                      </CardContent>
                    </Card>

                    {/* Subject Registrations */}
                    <Card className="bg-slate-50">
                      <CardHeader>
                        <CardTitle className="text-lg font-bold text-slate-900">Subject Registrations</CardTitle>
                      </CardHeader>
                      <CardContent>
                        {registrations.length === 0 ? (
                          <div className="text-slate-500 text-sm">No registration data available.</div>
                        ) : (
                          <div className="space-y-4">
                            {registrations.map((subject, i) => (
                              <div key={i} className="flex justify-between items-center p-3 bg-slate-50 rounded border border-slate-100">
                                <div className="font-semibold text-slate-900">{subject.subjectName}</div>
                                <div className="font-bold text-slate-700">{subject.enrollmentCount} {subject.enrollmentCount === 1 ? 'Student' : 'Students'}</div>
                              </div>
                            ))}
                          </div>
                        )}
                      </CardContent>
                    </Card>
                  </div>
                </AccordionContent>
              </AccordionItem>

              <AccordionItem value="recent" className="border-none">
                <AccordionTrigger className="hover:no-underline bg-slate-50 px-4 py-2 rounded-t-xl border border-slate-200 mt-4">
                  <span className="font-bold text-slate-900 text-lg">Recent Completed Exams</span>
                </AccordionTrigger>
                <AccordionContent className="p-4 bg-slate-50 border-x border-b border-slate-200 rounded-b-xl">
                  {recentResults.length === 0 ? (
                    <div className="text-slate-500 text-sm">No recent exams available.</div>
                  ) : (
                    <div className="space-y-4">
                      {recentResults.map((res, i) => (
                        <Card key={i} className="bg-slate-50">
                          <CardContent className="p-4">
                            <div className="flex justify-between items-start mb-4">
                              <div>
                                <h3 className="font-bold text-slate-900 text-lg">{res.studentName}</h3>
                                <div className="text-sm text-slate-500">{new Date(res.gradedAt).toLocaleString()}</div>
                              </div>
                              <div className="text-right">
                                <div className="font-bold text-2xl text-[#008751]">{res.totalScore} / {res.maxScore}</div>
                                <div className="text-xs font-semibold text-slate-500">Total Score</div>
                              </div>
                            </div>
                            {res.topicBreakdown && Object.keys(res.topicBreakdown).length > 0 && (
                              <div className="mt-4 pt-4 border-t border-slate-200">
                                <h4 className="text-sm font-semibold text-slate-700 mb-2">Topic Breakdown</h4>
                                <div className="grid grid-cols-2 gap-y-2 gap-x-4">
                                  {Object.entries(res.topicBreakdown).map(([topic, stats]) => (
                                    <div key={topic} className="flex justify-between items-center text-sm">
                                      <span className="text-slate-600 truncate mr-2" title={topic}>{topic}</span>
                                      <span className="font-bold text-slate-900 whitespace-nowrap">{stats.correct} / {stats.total}</span>
                                    </div>
                                  ))}
                                </div>
                              </div>
                            )}
                          </CardContent>
                        </Card>
                      ))}
                    </div>
                  )}
                </AccordionContent>
              </AccordionItem>
            </Accordion>
          </>
        )}
      </div>

      <ExamScriptAuditModal
        auditScript={auditScript}
        downloadingScriptId={downloadingScriptId}
        onDownloadPdf={downloadExamScript}
        onClose={closeAuditScript}
      />
    </div>
  );
}
