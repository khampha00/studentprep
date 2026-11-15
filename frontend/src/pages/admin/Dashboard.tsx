import { useEffect, useState, useCallback } from 'react';
import axios from 'axios';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Accordion, AccordionItem, AccordionTrigger, AccordionContent } from '../../components/ui/accordion';
import { Button } from '../../components/ui/button';
import { toast } from 'sonner';
import { getStoredToken, decodeJwtPayload } from '../../lib/auth';
import { ShieldAlert, CheckCircle, Ban, RotateCcw } from 'lucide-react';

interface FlaggedSession {
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

            {/* Pending Moderation & Flagged Sessions Section */}
            <Card className="border-t-4 border-t-amber-500 bg-white shadow-sm">
              <CardHeader className="pb-4 border-b border-slate-100 flex flex-row items-center justify-between">
                <div>
                  <CardTitle className="text-xl font-bold text-slate-900 flex items-center gap-2">
                    <ShieldAlert className="w-5 h-5 text-amber-600" />
                    Pending Moderation & Flagged Sessions
                  </CardTitle>
                  <p className="text-xs text-slate-500 mt-1">
                    Review candidate sessions flagged for proctoring violations (Tab Switching), late offline submissions, or auto-expirations.
                  </p>
                </div>
                {!isAdmin && (
                  <span className="text-xs font-semibold bg-slate-100 text-slate-600 px-2.5 py-1 rounded-full border border-slate-200">
                    Read-only (Supervisor)
                  </span>
                )}
              </CardHeader>
              <CardContent className="p-0">
                {flaggedSessions.length === 0 ? (
                  <div className="p-6 text-sm text-slate-500 text-center">
                    No sessions currently pending moderation or flagged for review.
                  </div>
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
                        {flaggedSessions.map((session) => (
                          <tr key={session.sessionId} className="hover:bg-slate-50/80 transition-colors">
                            <td className="p-4 font-semibold text-slate-900">{session.studentName}</td>
                            <td className="p-4 font-mono text-xs">{session.registrationNumber}</td>
                            <td className="p-4">
                              <span className={`inline-block px-2.5 py-1 text-xs font-bold rounded-full ${
                                session.status === 'FLAGGED_TAB_SWITCH'
                                  ? 'bg-red-100 text-red-800'
                                  : session.status === 'LATE_SUBMISSION_FLAGGED'
                                  ? 'bg-amber-100 text-amber-800'
                                  : 'bg-slate-100 text-slate-800'
                              }`}>
                                {session.status}
                              </span>
                            </td>
                            <td className="p-4 font-bold text-slate-800">{session.tabSwitchCount ?? 0}</td>
                            <td className="p-4">
                              <span className="text-xs font-semibold text-slate-700">
                                {session.moderationStatus || 'PENDING'}
                              </span>
                            </td>
                            <td className="p-4 text-right space-x-2">
                              {session.status === 'LATE_SUBMISSION_FLAGGED' && (
                                <Button
                                  size="sm"
                                  variant="outline"
                                  disabled={!isAdmin || moderatingId === session.sessionId}
                                  onClick={() => handleClearLate(session.sessionId)}
                                  className="text-xs text-emerald-700 border-emerald-300 hover:bg-emerald-50 h-8"
                                >
                                  <CheckCircle className="w-3.5 h-3.5 mr-1" />
                                  Clear Late
                                </Button>
                              )}
                              <Button
                                size="sm"
                                variant="outline"
                                disabled={!isAdmin || moderatingId === session.sessionId}
                                onClick={() => handleVoidSession(session.sessionId)}
                                className="text-xs text-red-700 border-red-300 hover:bg-red-50 h-8"
                              >
                                <Ban className="w-3.5 h-3.5 mr-1" />
                                Void
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                disabled={!isAdmin || moderatingId === session.sessionId}
                                onClick={() => handleGrantRetake(session.studentId, session.sessionId)}
                                className="text-xs text-blue-700 border-blue-300 hover:bg-blue-50 h-8"
                              >
                                <RotateCcw className="w-3.5 h-3.5 mr-1" />
                                Grant Retake
                              </Button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </CardContent>
            </Card>

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
    </div>
  );
}
