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

export default function AdminLayout() {
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);

  const handleLogout = () => {
    localStorage.removeItem('token');
    window.location.href = '/admin'; // Redirects to login
  };

  return (
    <div className="h-screen overflow-hidden bg-slate-50 flex font-sans">
      <aside
        className={`${isSidebarOpen ? 'w-64' : 'w-20'} bg-white border-r border-slate-200 shadow-sm flex flex-col shrink-0 transition-all duration-300 ease-in-out relative`}
      >
        <div className={`p-4 border-b border-slate-200 flex items-center ${isSidebarOpen ? 'justify-between' : 'justify-center'}`}>
          {isSidebarOpen && <h1 className="text-xl font-bold text-[#008751] truncate">Admin Panel</h1>}
          {!isSidebarOpen && <h1 className="text-xl font-bold text-[#008751]">AP</h1>}
        </div>

        {/* Toggle Button */}
        <button
          onClick={() => setIsSidebarOpen(!isSidebarOpen)}
          className="absolute -right-3 top-6 bg-white border border-slate-200 text-slate-500 rounded-full p-1 shadow-sm hover:bg-slate-50 z-10"
        >
          {isSidebarOpen ? <ChevronLeft className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
        </button>

        <nav className="flex-1 p-4 flex flex-col gap-2 overflow-hidden">
          <NavLink
            to="/admin"
            end
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2 rounded-md transition-colors ${isActive ? 'bg-slate-100 text-[#008751] font-semibold' : 'text-slate-600 hover:bg-slate-50'
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
              `flex items-center gap-3 px-3 py-2 rounded-md transition-colors ${isActive ? 'bg-slate-100 text-[#008751] font-semibold' : 'text-slate-600 hover:bg-slate-50'
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
              `flex items-center gap-3 px-3 py-2 rounded-md transition-colors ${isActive ? 'bg-slate-100 text-[#008751] font-semibold' : 'text-slate-600 hover:bg-slate-50'
              } ${!isSidebarOpen && 'justify-center px-0'}`
            }
            title="Students"
          >
            <Users className="h-5 w-5 shrink-0" />
            {isSidebarOpen && <span className="truncate">Students</span>}
          </NavLink>
        </nav>

        <div className="p-4 border-t border-slate-200 mt-auto">
          <AlertDialog>
            <AlertDialogTrigger>
              <button
                title="Logout"
                className={`flex items-center gap-3 px-3 py-2 w-full rounded-md transition-colors text-slate-600 hover:bg-red-50 hover:text-red-600 font-medium ${!isSidebarOpen && 'justify-center px-0'}`}
              >
                <LogOut className="h-5 w-5 shrink-0" />
                {isSidebarOpen && <span>Logout</span>}
              </button>
            </AlertDialogTrigger>
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

      <main className="flex-1 overflow-auto">
        <Outlet />
      </main>
    </div>
  );
}
