import type { ReactNode } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Toaster } from './components/ui/sonner';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';
import axios, { type InternalAxiosRequestConfig } from 'axios';

import 'katex/dist/katex.min.css';

import AdminLayout from './layouts/AdminLayout';
import AdminDashboard from './pages/admin/Dashboard';
import SubjectList from './pages/admin/SubjectList';
import SubjectDetail from './pages/admin/SubjectDetail';
import StudentsPage from './pages/admin/StudentsPage';
import ResultsPage from './pages/admin/ResultsPage';
import AdminLogin from './pages/AdminLogin';

import Login from './pages/auth/Login';
import Dashboard from './pages/student/Dashboard';
import ExamDashboard from './pages/exam/ExamDashboard';
import ResultPage from './pages/student/ResultPage';
import { ErrorBoundary } from './components/ErrorBoundary';
import {
  clearStoredToken,
  decodeJwtPayload,
  getStoredToken,
  isSessionEvictedProblem,
  parseErrorResponseData,
  setAuthRedirecting,
  setStoredToken,
} from './lib/auth';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

interface RetryableRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
}

axios.interceptors.request.use((config) => {
  const url = config.url ?? '';
  if (url.startsWith('/api')) {
    if (url.includes('/api/v1/auth/')) {
      config.withCredentials = true;
    }
    const token = localStorage.getItem('token');
    if (token && !url.includes('/api/v1/auth/login') && !url.includes('/api/v1/auth/refresh')) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

let isRefreshing = false;
let isRedirecting = false;
let failedQueue: { resolve: (value: string) => void; reject: (reason?: unknown) => void }[] = [];

const processQueue = (error: unknown, token: string | null = null) => {
  failedQueue.forEach((prom) => {
    if (error || !token) {
      prom.reject(error);
    } else {
      prom.resolve(token);
    }
  });
  failedQueue = [];
};

const redirectToLogin = (errorCode: 'session_expired' | 'auth_expired') => {
  if (isRedirecting) {
    return;
  }
  isRedirecting = true;
  setAuthRedirecting(true);
  if (window.location.pathname.startsWith('/admin')) {
    window.location.href = `/admin?error=${errorCode}`;
  } else {
    window.location.href = `/?error=${errorCode}`;
  }
};

axios.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!axios.isAxiosError(error)) {
      return Promise.reject(error);
    }
    const originalRequest = error.config as RetryableRequestConfig | undefined;
    const requestUrl = originalRequest?.url ?? '';

    if (
      requestUrl.includes('/api/v1/auth/refresh') ||
      requestUrl.includes('/api/v1/auth/login') ||
      requestUrl.includes('/api/v1/auth/logout')
    ) {
      return Promise.reject(error);
    }

    if (error.response?.status === 401 && originalRequest && !originalRequest._retry) {
      const problemData = await parseErrorResponseData(error.response.data);
      if (isSessionEvictedProblem(problemData)) {
        clearStoredToken();
        processQueue(error, null);
        redirectToLogin('session_expired');
        return Promise.reject(error);
      }

      if (isRefreshing) {
        return new Promise<string>((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        }).then((token) => {
          originalRequest.headers.Authorization = `Bearer ${token}`;
          return axios(originalRequest);
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const res = await axios.post('/api/v1/auth/refresh', {}, { withCredentials: true });
        const newToken: unknown = res.data?.data?.accessToken;
        if (typeof newToken !== 'string' || !newToken.trim()) {
          throw new Error('Refresh response did not include an access token');
        }
        setStoredToken(newToken);
        originalRequest.headers.Authorization = `Bearer ${newToken}`;
        processQueue(null, newToken);
        return axios(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError, null);
        if (
          axios.isAxiosError(refreshError) &&
          (refreshError.response?.status === 401 || refreshError.response?.status === 403)
        ) {
          const refreshProblem = await parseErrorResponseData(refreshError.response.data);
          const errorCode = isSessionEvictedProblem(refreshProblem) ? 'session_expired' : 'auth_expired';
          clearStoredToken();
          redirectToLogin(errorCode);
        }
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);

function AdminProtectedRoute({ children }: { children: ReactNode }) {
  const token = getStoredToken();
  if (!token) {
    return <AdminLogin />;
  }
  const payload = decodeJwtPayload(token);
  if (!payload?.role) {
    clearStoredToken();
    return <AdminLogin />;
  }
  if (payload.role !== 'ROLE_ADMIN' && payload.role !== 'ROLE_SUPERVISOR') {
    return <Navigate to="/dashboard" replace />;
  }
  return <>{children}</>;
}

function StudentProtectedRoute({ children }: { children: ReactNode }) {
  const token = getStoredToken();
  if (!token) {
    return <Navigate to="/" replace />;
  }
  const payload = decodeJwtPayload(token);
  if (!payload?.role) {
    clearStoredToken();
    return <Navigate to="/" replace />;
  }
  if (payload.role !== 'ROLE_STUDENT') {
    return <Navigate to="/admin" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <BrowserRouter>
      <ErrorBoundary>
        <Routes>
          <Route path="/" element={<Login />} />

          <Route
            path="/dashboard"
            element={
              <StudentProtectedRoute>
                <Dashboard />
              </StudentProtectedRoute>
            }
          />

          <Route
            path="/exam"
            element={
              <StudentProtectedRoute>
                <ExamDashboard />
              </StudentProtectedRoute>
            }
          />

          <Route
            path="/result"
            element={
              <StudentProtectedRoute>
                <ResultPage />
              </StudentProtectedRoute>
            }
          />

          <Route
            path="/admin"
            element={
              <AdminProtectedRoute>
                <AdminLayout />
              </AdminProtectedRoute>
            }
          >
            <Route index element={<AdminDashboard />} />
            <Route path="subjects" element={<SubjectList />} />
            <Route path="subjects/:id" element={<SubjectDetail />} />
            <Route path="students" element={<StudentsPage />} />
            <Route path="results" element={<ResultsPage />} />
          </Route>
        </Routes>
      </ErrorBoundary>
      <Toaster
        position="bottom-right"
        toastOptions={{
          classNames: {
            success: '!bg-[#008751] !text-white !border-[#008751] [&_svg]:text-white',
            error: '!bg-red-600 !text-white !border-red-600 [&_svg]:text-white',
          },
        }}
      />
    </BrowserRouter>
  );
}
