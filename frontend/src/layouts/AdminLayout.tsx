import { useState } from 'react';
import { Outlet, NavLink } from 'react-router-dom';
import { LayoutDashboard, BookOpen, Users, LogOut, ChevronLeft, ChevronRight } from 'lucide-react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '../components/ui/alert-dialog';
import { clearStoredToken } from '../lib/auth';

export default function AdminLayout() {
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);

  const handleLogout = async () => {
    try {
      const token = localStorage.getItem('token');
      await fetch('/api/v1/auth/logout', {
        method: 'POST',
        credentials: 'include',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
    } catch {
      // best-effort logout cleanup
    }
    clearStoredToken();
    window.location.assign('/admin');
  };

  return (
    <div className="h-screen overflow-hidden bg-slate-50 flex font-sans text-slate-900">
      <aside
        className={`${isSidebarOpen ? 'w-64' : 'w-20'} bg-zinc-50 border-r border-slate-200 shadow-sm flex flex-col shrink-0 transition-all duration-300 ease-in-out relative`}
      >
        <div className={`p-4 border-b border-slate-200 flex items-center ${isSidebarOpen ? 'justify-between' : 'justify-center'}`}>
          {isSidebarOpen && <h1 className="text-xl font-bold text-[#008751] truncate">Admin Panel</h1>}
          {!isSidebarOpen && <h1 className="text-xl font-bold text-[#008751]">AP</h1>}
        </div>

        <button
          type="button"
          onClick={() => setIsSidebarOpen(!isSidebarOpen)}
          className="absolute -right-3 top-6 bg-slate-50 border border-slate-200 text-slate-600 rounded-full p-1 shadow-sm hover:bg-slate-100 z-10"
          aria-label={isSidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}
        >
          {isSidebarOpen ? <ChevronLeft className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
        </button>

        <nav className="flex-1 p-4 flex flex-col gap-2 overflow-hidden">
          <NavLink
            to="/admin"
            end
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2 rounded-md transition-colors ${isActive ? 'bg-slate-100 text-[#008751] font-semibold' : 'text-slate-700 hover:bg-slate-100'
              } ${!isSidebarOpen && 'justify-center px-0'}`
            }
            title="Overview"
          >
            <LayoutDashboard className="h-5 w-5 shrink-0" />
            {isSidebarOpen && <span className="truncate">Overview</span>}
          </NavLink>
          <NavLink
            to="/admin/subjects"
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2 rounded-md transition-colors ${isActive ? 'bg-slate-100 text-[#008751] font-semibold' : 'text-slate-700 hover:bg-slate-100'
              } ${!isSidebarOpen && 'justify-center px-0'}`
            }
            title="Subjects"
          >
            <BookOpen className="h-5 w-5 shrink-0" />
            {isSidebarOpen && <span className="truncate">Subjects</span>}
          </NavLink>
          <NavLink
            to="/admin/students"
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2 rounded-md transition-colors ${isActive ? 'bg-slate-100 text-[#008751] font-semibold' : 'text-slate-700 hover:bg-slate-100'
              } ${!isSidebarOpen && 'justify-center px-0'}`
            }
            title="Students"
          >
            <Users className="h-5 w-5 shrink-0" />
            {isSidebarOpen && <span className="truncate">Students</span>}
          </NavLink>
          <NavLink
            to="/admin/results"
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2 rounded-md transition-colors ${isActive ? 'bg-slate-100 text-[#008751] font-semibold' : 'text-slate-700 hover:bg-slate-100'
              } ${!isSidebarOpen && 'justify-center px-0'}`
            }
            title="Results"
          >
            <BookOpen className="h-5 w-5 shrink-0" />
            {isSidebarOpen && <span className="truncate">Results</span>}
          </NavLink>
        </nav>

        <div className="p-4 border-t border-slate-200 mt-auto">
          <AlertDialog>
            <AlertDialogTrigger
              render={
                <button
                  type="button"
                  title="Logout"
                  className={`flex items-center gap-3 px-3 py-2 w-full rounded-md transition-colors text-slate-700 hover:bg-red-50 hover:text-red-600 font-medium ${!isSidebarOpen && 'justify-center px-0'}`}
                >
                  <LogOut className="h-5 w-5 shrink-0" />
                  {isSidebarOpen && <span>Logout</span>}
                </button>
              }
            />
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Confirm Logout</AlertDialogTitle>
                <AlertDialogDescription>
                  Are you sure you want to Logout?
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction onClick={handleLogout} className="bg-destructive text-white hover:bg-destructive/90">
                  Logout
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      </aside>

      <main className="flex-1 overflow-auto bg-slate-50">
        <Outlet />
      </main>
    </div>
  );
}
