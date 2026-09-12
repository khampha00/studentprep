import { useEffect, useState } from 'react';
import axios from 'axios';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { toast } from 'sonner';

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

  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchDashboard = () => axios.get('/api/v1/admin/analytics/dashboard').catch(() => null);
    const fetchLive = () => axios.get('/api/v1/admin/analytics/live-sessions').catch(() => null);
    const fetchSubjects = () => axios.get('/api/v1/admin/analytics/subjects').catch(() => null);

    Promise.all([fetchDashboard(), fetchLive(), fetchSubjects()])
      .then(([dashRes, liveRes, subjRes]) => {
        if (dashRes?.data?.data) setData(dashRes.data.data);
        if (liveRes?.data?.data) setLiveSessions(liveRes.data.data);
        if (subjRes?.data?.data) setSubjectAnalytics(subjRes.data.data);
      })
      .catch(() => toast.error('Failed to load dashboard data'))
      .finally(() => setLoading(false));

    const pollInterval = setInterval(() => {
      fetchLive().then(res => {
        if (res?.data?.data) setLiveSessions(res.data.data);
      });
    }, 10000);

    return () => clearInterval(pollInterval);
  }, []);

  return (
    <div className="p-8 font-sans">
      <div className="max-w-7xl mx-auto space-y-8">
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
                    <CardTitle className="text-sm font-medium text-slate-500">Average Score (%)</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="text-2xl font-bold text-[#008751]">{data.averageScore.toFixed(2)}%</div>
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-sm font-medium text-slate-500">Pass Rate (%)</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="text-2xl font-bold text-[#008751]">{data.passRate.toFixed(2)}%</div>
                  </CardContent>
                </Card>
              </div>
            )}

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
              {/* Live Monitoring */}
              <Card>
                <CardHeader>
                  <CardTitle className="text-lg font-bold text-slate-900">Live Monitoring</CardTitle>
                </CardHeader>
                <CardContent>
                  {liveSessions.length === 0 ? (
                    <div className="text-slate-500 text-sm">No active sessions currently.</div>
                  ) : (
                    <div className="space-y-4">
                      {liveSessions.filter(s => s.status === 'IN_PROGRESS').map((session, i) => (
                        <div key={i} className="flex justify-between items-center p-3 bg-slate-50 rounded border border-slate-100">
                          <div>
                            <div className="font-semibold text-slate-900">{session.studentName}</div>
                            <div className="text-xs text-slate-500">ID: {session.sessionId}</div>
                          </div>
                          <div className="text-right">
                            <div className="text-sm font-bold text-slate-700">{Math.floor(session.timeLeft / 60)}m {session.timeLeft % 60}s</div>
                            <div className="text-xs text-blue-600 font-semibold">{session.status}</div>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </CardContent>
              </Card>

              {/* Subject Analytics */}
              <Card>
                <CardHeader>
                  <CardTitle className="text-lg font-bold text-slate-900">Subject Analytics</CardTitle>
                </CardHeader>
                <CardContent>
                  {subjectAnalytics.length === 0 ? (
                    <div className="text-slate-500 text-sm">No subject data available.</div>
                  ) : (
                    <div className="space-y-4">
                      {subjectAnalytics.map((subject, i) => (
                        <div key={i} className="space-y-1">
                          <div className="flex justify-between text-sm">
                            <span className="font-semibold text-slate-700">{subject.subjectName}</span>
                            <span className="font-bold text-slate-900">{subject.averageScore.toFixed(1)}%</span>
                          </div>
                          <div className="w-full bg-slate-100 rounded-full h-2">
                            <div 
                              className="bg-[#008751] h-2 rounded-full" 
                              style={{ width: `${Math.min(100, Math.max(0, subject.averageScore))}%` }}
                            ></div>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </CardContent>
              </Card>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
