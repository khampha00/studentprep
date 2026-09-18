import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Toaster } from './components/ui/sonner';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

import 'katex/dist/katex.min.css';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

import axios from 'axios';
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

axios.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token && config.url?.startsWith('/api')) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

let isRefreshing = false;
let failedQueue: any[] = [];

const processQueue = (error: any, token: string | null = null) => {
  failedQueue.forEach((prom) => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve(token);
    }
  });
  failedQueue = [];
};

axios.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    if (originalRequest?.url?.includes('/api/v1/auth/refresh') || originalRequest?.url?.includes('/api/v1/auth/login')) {
      return Promise.reject(error);
    }

    if (error.response && error.response.status === 401 && !originalRequest._retry) {
      // If the 401 is due to concurrent login on another device, evict immediately
      const isAnotherDevice = error.response.data?.message === 'Session active on another device';
      if (isAnotherDevice) {
        localStorage.removeItem('token');
        if (window.location.pathname.startsWith('/admin')) {
          window.location.href = '/admin?error=session_expired';
        } else {
          window.location.href = '/?error=session_expired';
        }
        return Promise.reject(error);
      }

      if (isRefreshing) {
        return new Promise(function (resolve, reject) {
          failedQueue.push({ resolve, reject });
        })
          .then((token) => {
            originalRequest.headers.Authorization = 'Bearer ' + token;
            return axios(originalRequest);
          })
          .catch((err) => {
            return Promise.reject(err);
          });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const res = await axios.post('/api/v1/auth/refresh', {}, { withCredentials: true });
        const newToken = res.data.data.accessToken;
        localStorage.setItem('token', newToken);
        axios.defaults.headers.common.Authorization = 'Bearer ' + newToken;
        originalRequest.headers.Authorization = 'Bearer ' + newToken;
        processQueue(null, newToken);
        return axios(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError, null);
        localStorage.removeItem('token');
        if (window.location.pathname.startsWith('/admin')) {
          window.location.href = '/admin?error=session_expired';
        } else {
          window.location.href = '/?error=session_expired';
        }
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }
    return Promise.reject(error);
  }
);

function AdminProtectedRoute({ children }: { children: React.ReactNode }) {
  const token = localStorage.getItem('token');
  if (!token) {
    return <AdminLogin />;
  }
  return <>{children}</>;
}

function StudentProtectedRoute({ children }: { children: React.ReactNode }) {
  const token = localStorage.getItem('token');
  if (!token) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Login />} />
        
        <Route path="/dashboard" element={
          <StudentProtectedRoute>
            <Dashboard />
          </StudentProtectedRoute>
        } />
        
        <Route path="/exam" element={
          <StudentProtectedRoute>
            <ExamDashboard />
          </StudentProtectedRoute>
        } />
        
        <Route path="/result" element={
          <StudentProtectedRoute>
            <ResultPage />
          </StudentProtectedRoute>
        } />
        
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
      <Toaster 
        position="top-center" 
        toastOptions={{
          classNames: {
            success: '!bg-[#008751] !text-white !border-[#008751] [&_svg]:text-white',
            error: '!bg-red-600 !text-white !border-red-600 [&_svg]:text-white',
          }
        }}
      />
    </BrowserRouter>
  );
}
