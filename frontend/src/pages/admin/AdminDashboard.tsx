import React from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';

export default function AdminDashboard() {
  return (
    <div className="p-8 font-sans">
      <div className="max-w-7xl mx-auto space-y-8">
        <h1 className="text-3xl font-bold text-slate-900">Overview</h1>

        <div className="grid grid-cols-3 gap-6">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-slate-500">System Status</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold text-[#008751]">Online</div>
              <p className="text-xs text-slate-400 mt-1">All services operational</p>
            </CardContent>
          </Card>
          
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-slate-500">Quick Actions</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-slate-600">Navigate to the <strong>Subjects</strong> tab on the left to manage Question Banks or ingest new PDFs.</p>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
