import { useState, useEffect, type FormEvent } from 'react';
import axios from 'axios';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { Button } from '../../components/ui/button';
import { Label } from '../../components/ui/label';
import {
  clearStoredToken,
  decodeJwtPayload,
  extractProblemDataSync,
  extractProblemMessage,
  getStoredToken,
  setStoredToken,
} from '../../lib/auth';

function RequiredAsterisk() {
  return <span className="-ml-2 text-[18px] font-bold text-destructive">*</span>;
}

export default function Login() {
  const [identifier, setIdentifier] = useState('');
  const [pin, setPin] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [blockedError, setBlockedError] = useState<string | null>(null);
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const wasTerminated = searchParams.get('terminated') === 'malpractice';
  const errorParam = searchParams.get('error');
  const isSessionExpired = errorParam === 'session_expired';
  const isAuthExpired = errorParam === 'auth_expired';

  useEffect(() => {
    if (wasTerminated) {
      toast.error('Your exam was terminated due to malpractice (Tab Switching). Contact your supervisor for further instructions.');
      return;
    }
    if (isSessionExpired) {
      toast.error('You have been logged out because your account was accessed from another device.');
      return;
    }
    if (isAuthExpired) {
      toast.error('Your session has expired. Please sign in again.');
      return;
    }

    const token = getStoredToken();
    if (token) {
      const payload = decodeJwtPayload(token);
      if (payload?.role === 'ROLE_ADMIN' || payload?.role === 'ROLE_SUPERVISOR') {
        navigate('/admin', { replace: true });
      } else if (payload?.role === 'ROLE_STUDENT') {
        navigate('/dashboard', { replace: true });
      } else {
        clearStoredToken();
      }
    }
  }, [wasTerminated, isSessionExpired, isAuthExpired, navigate]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setBlockedError(null);

    const cleanId = identifier.trim();
    const cleanPin = pin.trim();

    try {
      const res = await axios.post(
        '/api/v1/auth/login',
        { identifier: cleanId, pin: cleanPin },
        { withCredentials: true }
      );
      const token: unknown = res.data?.data?.accessToken;
      if (typeof token !== 'string' || !token.trim()) {
        throw new Error('Authentication succeeded without an access token');
      }
      setStoredToken(token);

      const payload = decodeJwtPayload(token);
      if (payload?.role === 'ROLE_ADMIN' || payload?.role === 'ROLE_SUPERVISOR') {
        navigate('/admin');
      } else {
        navigate('/dashboard');
      }
    } catch (error: unknown) {
      const problem = extractProblemDataSync(error);
      const status = axios.isAxiosError(error) ? error.response?.status : undefined;
      let errorMsg = extractProblemMessage(error, 'Login failed. Please check your credentials.');

      if (problem?.code === 'PIN_RESET_REQUIRED') {
        errorMsg =
          problem.detail ||
          'Your PIN has been invalidated and must be reset by an administrator.';
      } else if (
        status === 409 ||
        problem?.code === 'ACTIVE_SESSION_CONFLICT' ||
        problem?.code === 'ACTIVE_EXAM_LOCKED' ||
        problem?.code === 'CONCURRENT_SESSION_CONFLICT'
      ) {
        errorMsg =
          problem?.detail ||
          'An active session or examination is already open on another workstation. Contact an administrator to unlock your session.';
      }

      if (status === 403 || status === 409 || status === 429) {
        setBlockedError(errorMsg);
      }
      toast.error(errorMsg);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex items-center justify-center min-h-screen p-4 bg-slate-50 font-sans text-slate-900">
      <div className="w-full max-w-md bg-zinc-50 rounded-xl shadow-sm border border-slate-200 p-8">
        {isSessionExpired && (
          <div className="mb-6 p-4 bg-amber-50 border-2 border-amber-500 rounded-lg text-sm text-amber-900 font-semibold">
            ⚠️ <strong>Session Ended:</strong> You were logged out because this account was logged into on another device. Only one active session is allowed per user.
          </div>
        )}
        {isAuthExpired && (
          <div className="mb-6 p-4 bg-amber-50 border-2 border-amber-500 rounded-lg text-sm text-amber-900 font-semibold">
            <strong>Session Expired:</strong> Please sign in again to continue.
          </div>
        )}
        {(wasTerminated || blockedError) && (
          <div role="alert" className="mb-6 p-4 bg-red-50 border-2 border-red-500 rounded-lg text-sm text-red-900 font-semibold">
            {blockedError ? (
              <div>🚫 <strong>{blockedError}</strong></div>
            ) : (
              <div>⚠️ Your previous exam was <strong>terminated and flagged</strong> due to exam rule violations (Tab Switching). This incident has been reported for administrative review.</div>
            )}
          </div>
        )}
        <h1 className="text-2xl font-bold text-slate-900 mb-6 text-center">StudentPrep Portal</h1>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="student-identifier" className="text-slate-900">
              JAMB Registration Number <RequiredAsterisk />
            </Label>
            <input
              id="student-identifier"
              type="text"
              required
              autoComplete="username"
              autoCapitalize="characters"
              autoCorrect="off"
              spellCheck={false}
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value.toUpperCase())}
              className="w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751] focus:border-transparent text-slate-900"
              placeholder="e.g. 123456JF"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="student-pin" className="text-slate-900">
              PIN <RequiredAsterisk />
            </Label>
            <input
              id="student-pin"
              type="password"
              inputMode="numeric"
              required
              autoComplete="current-password"
              value={pin}
              onChange={(e) => setPin(e.target.value.trim())}
              className="w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751] focus:border-transparent text-slate-900"
              placeholder="••••••••"
            />
          </div>
          <Button
            type="submit"
            disabled={isLoading}
            className="w-full mt-4 h-11 text-base bg-[#008751] hover:bg-[#007040] text-white"
          >
            {isLoading ? 'Authenticating...' : 'Login'}
          </Button>
        </form>
      </div>
    </div>
  );
}
