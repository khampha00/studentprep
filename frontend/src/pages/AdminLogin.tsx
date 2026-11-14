import { useEffect, useState, type FormEvent } from 'react';
import axios from 'axios';
import { useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { Button } from '../components/ui/button';
import { Label } from '../components/ui/label';
import {
  clearStoredToken,
  decodeJwtPayload,
  extractProblemMessage,
  setStoredToken,
} from '../lib/auth';

export default function AdminLogin() {
  const [identifier, setIdentifier] = useState('');
  const [pin, setPin] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [searchParams] = useSearchParams();

  const errorParam = searchParams.get('error');
  const isSessionExpired = errorParam === 'session_expired';
  const isAuthExpired = errorParam === 'auth_expired';

  useEffect(() => {
    if (isSessionExpired) {
      toast.error('You have been logged out because your account was accessed from another device.');
    } else if (isAuthExpired) {
      toast.error('Your session has expired. Please sign in again.');
    }
  }, [isSessionExpired, isAuthExpired]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setErrorMessage(null);

    const cleanIdentifier = identifier.trim();
    const cleanPin = pin.trim();

    try {
      const res = await axios.post(
        '/api/v1/auth/login',
        { identifier: cleanIdentifier, pin: cleanPin },
        { withCredentials: true }
      );
      const token: unknown = res.data?.data?.accessToken;
      if (typeof token !== 'string' || !token.trim()) {
        const msg = 'Login failed: No access token received.';
        setErrorMessage(msg);
        toast.error(msg);
        return;
      }

      const payload = decodeJwtPayload(token);
      const role = payload?.role ?? res.data?.data?.role;
      if (role !== 'ROLE_ADMIN' && role !== 'ROLE_SUPERVISOR') {
        try {
          await axios.post(
            '/api/v1/auth/logout',
            {},
            {
              withCredentials: true,
              headers: { Authorization: `Bearer ${token}` },
            }
          );
        } catch {
          // Best-effort cleanup if a non-staff user authenticated on the admin portal
        }
        clearStoredToken();
        const msg = 'Access denied: Administrator or supervisor credentials are required.';
        setErrorMessage(msg);
        toast.error(msg);
        return;
      }

      setStoredToken(token);
      window.location.assign('/admin');
    } catch (error: unknown) {
      const msg = extractProblemMessage(error, 'Login failed. Please verify your credentials.');
      setErrorMessage(msg);
      toast.error(msg);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex items-center justify-center min-h-screen p-4 bg-slate-50 font-sans text-slate-900">
      <div className="w-full max-w-md bg-zinc-50 rounded-xl shadow-sm border border-slate-200 p-8">
        {isSessionExpired && (
          <div className="mb-5 p-3.5 bg-amber-50 border border-amber-400 rounded-lg text-sm text-amber-900 font-medium">
            <strong>Session Ended:</strong> Your account was signed in from another workstation.
          </div>
        )}
        {isAuthExpired && (
          <div className="mb-5 p-3.5 bg-amber-50 border border-amber-400 rounded-lg text-sm text-amber-900 font-medium">
            <strong>Session Expired:</strong> Please sign in again to continue.
          </div>
        )}
        {errorMessage && (
          <div role="alert" className="mb-5 p-3.5 bg-red-50 border border-red-400 rounded-lg text-sm text-red-900 font-medium">
            {errorMessage}
          </div>
        )}

        <h1 className="text-2xl font-bold text-slate-900 mb-6 text-center">Admin Login</h1>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="admin-identifier" className="text-slate-900">
              Username <span className="-ml-2 text-[18px] font-bold text-destructive">*</span>
            </Label>
            <input
              id="admin-identifier"
              type="text"
              required
              autoComplete="username"
              autoCapitalize="characters"
              autoCorrect="off"
              spellCheck={false}
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
              className="w-full px-3 py-2 bg-slate-50 text-slate-900 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751] focus:border-transparent"
              placeholder="Staff Identifier"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="admin-password" className="text-slate-900">
              Password <span className="-ml-2 text-[18px] font-bold text-destructive">*</span>
            </Label>
            <input
              id="admin-password"
              type="password"
              required
              autoComplete="current-password"
              value={pin}
              onChange={(e) => setPin(e.target.value)}
              className="w-full px-3 py-2 bg-slate-50 text-slate-900 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751] focus:border-transparent"
              placeholder="••••••••"
            />
          </div>
          <Button
            type="submit"
            disabled={isLoading}
            className="w-full mt-4 h-11 text-base bg-[#008751] hover:bg-[#007040] text-white"
          >
            {isLoading ? 'Logging in...' : 'Login'}
          </Button>
        </form>
      </div>
    </div>
  );
}
