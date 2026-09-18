import { useState, useEffect } from 'react';
import axios from 'axios';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { Button } from '../../components/ui/button';
import { Label } from '../../components/ui/label';

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

  useEffect(() => {
    if (wasTerminated) {
      toast.error('Your exam was terminated due to malpractice (Tab Switching). Contact your supervisor for further instructions.');
    }
  }, [wasTerminated]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setBlockedError(null);
    
    const cleanId = identifier.trim();
    const cleanPin = pin.trim();

    try {
      const res = await axios.post('/api/v1/auth/login', { identifier: cleanId, pin: cleanPin });
      const token = res.data.data.accessToken;
      localStorage.setItem('token', token);
      
      const base64Url = token.split('.')[1];
      const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
      const payload = JSON.parse(atob(base64));
      if (payload.role === 'ROLE_ADMIN' || payload.role === 'ROLE_SUPERVISOR') {
         navigate('/admin');
      } else {
         navigate('/dashboard');
      }
    } catch (e: any) {
      const errorMsg = e.response?.data?.detail || e.response?.data?.message || 'Login failed. Please check your credentials.';
      if (e.response?.status === 403) {
        setBlockedError(errorMsg);
      }
      toast.error(errorMsg);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex items-center justify-center min-h-screen p-4 bg-slate-50">
      <div className="w-full max-w-md bg-white rounded-xl shadow-sm border border-slate-200 p-8">
        {(wasTerminated || blockedError) && (
          <div className="mb-6 p-4 bg-red-50 border-2 border-red-500 rounded-lg text-sm text-red-900 font-semibold">
            {blockedError ? (
              <div>🚫 <strong>{blockedError}</strong></div>
            ) : (
              <div>⚠️ Your previous exam was <strong>terminated and flagged</strong> due to exam rule violations (Tab Switching). This incident has been reported for administrative review.</div>
            )}
          </div>
        )}
        <h1 className="text-2xl font-bold text-slate-900 mb-6 text-center">StudentPrep Portal</h1>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1">
            <Label className="flex items-start text-slate-700">
              JAMB Registration Number <RequiredAsterisk />
            </Label>
            <input 
              type="text" 
              required 
              autoCapitalize="characters"
              autoCorrect="off"
              spellCheck={false}
              value={identifier} 
              onChange={e => setIdentifier(e.target.value.toUpperCase())} 
              className="w-full px-3 py-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent text-slate-900" 
              placeholder="e.g. JAMB-2026-XXXXXX" 
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label className="flex items-start text-slate-700">
              PIN <RequiredAsterisk />
            </Label>
            <input 
              type="password" 
              inputMode="numeric"
              required 
              value={pin} 
              onChange={e => setPin(e.target.value.trim())} 
              className="w-full px-3 py-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent text-slate-900" 
              placeholder="••••••••" 
            />
          </div>
          <Button type="submit" disabled={isLoading} className="w-full mt-4 h-11 text-base">
            {isLoading ? 'Authenticating...' : 'Login'}
          </Button>
        </form>
      </div>
    </div>
  );
}
