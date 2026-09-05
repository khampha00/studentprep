import React, { useState } from 'react';
import axios from 'axios';
import { Button } from '../components/ui/button';
import { Label } from '../components/ui/label';
import { toast } from 'sonner';

export default function AdminLogin() {
  const [identifier, setIdentifier] = useState('');
  const [pin, setPin] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    try {
      const res = await axios.post('/api/v1/auth/login', { identifier, pin });
      const token = res.data.token;
      if (token) {
        localStorage.setItem('token', token);
        window.location.href = '/admin';
      } else {
        toast.error('Login failed: No token received');
      }
    } catch (error: any) {
      toast.error(error.response?.data?.message || 'Login failed');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex items-center justify-center min-h-screen p-4 bg-slate-50">
      <div className="w-full max-w-md bg-white rounded-xl shadow-sm border border-slate-200 p-8">
        <h1 className="text-2xl font-bold text-slate-900 mb-6 text-center">Admin Login</h1>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1">
            <Label className="flex items-start text-slate-700">
              Username <span className="-ml-2 text-[18px] font-bold text-destructive">*</span>
            </Label>
            <input 
              type="text" 
              required 
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
              className="w-full px-3 py-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent" 
              placeholder="Admin Username" 
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label className="flex items-start text-slate-700">
              Password <span className="-ml-2 text-[18px] font-bold text-destructive">*</span>
            </Label>
            <input 
              type="password" 
              required 
              value={pin}
              onChange={(e) => setPin(e.target.value)}
              className="w-full px-3 py-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent" 
              placeholder="••••••••" 
            />
          </div>
          <Button type="submit" disabled={isLoading} className="w-full mt-4 h-11 text-base">
            {isLoading ? 'Logging in...' : 'Login'}
          </Button>
        </form>
      </div>
    </div>
  );
}
