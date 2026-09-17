import { useState, useEffect } from 'react';
import axios from 'axios';
import { toast } from 'sonner';
import { Download, FileText, Key } from 'lucide-react';

export default function StudentsPage() {
  const [students, setStudents] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [availableSubjects, setAvailableSubjects] = useState<string[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [showModal, setShowModal] = useState(false);

  const fetchStudents = () => {
    setLoading(true);
    axios.get('/api/v1/admin/students')
      .then(res => {
        setStudents(res.data.data);
      })
      .catch(() => {
        toast.error('Failed to load students');
      })
      .finally(() => {
        setLoading(false);
      });
  };

  const fetchSubjects = () => {
    axios.get('/api/v1/subjects')
      .then(res => {
        const subjs = res.data?.data?.map((s: any) => s.name) || [];
        setAvailableSubjects(subjs);
      })
      .catch(() => {});
  };

  useEffect(() => {
    fetchStudents();
    fetchSubjects();
  }, []);

  const handleUpload = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;

    setIsUploading(true);
    const formData = new FormData();
    formData.append('csvFile', file);

    try {
      const response = await axios.post('/api/v1/admin/students/bulk', formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      });
      setResult(response.data.data);
      toast.success('Upload completed');
      setFile(null);
      fetchStudents();
      fetchSubjects();
    } catch (error) {
      toast.error('Failed to upload file');
      console.error(error);
    } finally {
      setIsUploading(false);
    }
  };

  const handleDownloadTemplate = () => {
    const header = "Name,State,Exam Center,Subject 1,Subject 2,Subject 3,Subject 4\n";
    // Pick 4 real subjects from the database if available, otherwise sensible defaults
    const subjectsToUse = availableSubjects.length >= 4
      ? availableSubjects.slice(0, 4)
      : [...availableSubjects, 'Mathematics', 'English', 'Biology', 'Chemistry'].slice(0, 4);
    const sample = `John Doe,Lagos,CBT Center 1,${subjectsToUse.join(',')}\n`;
    const blob = new Blob([header + sample], { type: 'text/csv' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = "student_upload_template.csv";
    a.click();
    window.URL.revokeObjectURL(url);
  };

  const handleExportCredentials = async () => {
    try {
      const response = await axios.get('/api/v1/admin/students/export', {
        responseType: 'blob'
      });
      const url = window.URL.createObjectURL(new Blob([response.data], { type: 'text/csv' }));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', 'student_credentials.csv');
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
      toast.success('Credentials exported successfully');
    } catch (error) {
      toast.error('Failed to export credentials');
      console.error(error);
    }
  };

  const handleDownloadSlip = async (studentId: string, regNumber: string) => {
    try {
      const response = await axios.get(`/api/v1/admin/students/${studentId}/slip`, {
        responseType: 'blob'
      });
      const url = window.URL.createObjectURL(new Blob([response.data], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `${regNumber}-slip.pdf`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
      toast.success('Registration slip downloaded successfully');
    } catch (error) {
      toast.error('Failed to download slip');
      console.error(error);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Students</h1>
          <p className="text-xs text-slate-500 mt-0.5">Manage candidate registrations, credentials, and examination slips</p>
        </div>
        <div className="flex items-center gap-2.5">
          {students.length > 0 && (
            <button 
              onClick={handleExportCredentials}
              className="inline-flex items-center gap-1.5 px-3.5 py-2 border border-slate-300 bg-white hover:bg-slate-100 text-slate-700 rounded-lg text-sm font-medium shadow-sm transition-colors cursor-pointer"
              title="Download all candidate Registration Numbers & PINs in CSV"
            >
              <Download className="w-4 h-4 text-slate-600" />
              Download All Credentials (CSV)
            </button>
          )}
          <button 
            onClick={() => {
              setShowModal(true);
              setResult(null);
              setFile(null);
            }}
            className="inline-flex items-center gap-1.5 bg-[#008751] hover:bg-[#007040] text-white px-4 py-2 rounded-lg font-medium shadow-sm transition-colors text-sm cursor-pointer"
          >
            Bulk Register CSV
          </button>
        </div>
      </div>

      {loading ? (
        <div className="text-slate-500">Loading students...</div>
      ) : (
        <div className="overflow-x-auto border border-slate-200 rounded-lg shadow-xs">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-slate-100/70 border-b border-slate-200 text-xs font-semibold text-slate-700">
                <th className="p-4">Candidate Name</th>
                <th className="p-4">Registration Number</th>
                <th className="p-4">Login PIN</th>
                <th className="p-4">State</th>
                <th className="p-4">Exam Center</th>
                <th className="p-4 text-right">Registration Slip</th>
              </tr>
            </thead>
            <tbody>
              {students.map((student, idx) => (
                <tr key={idx} className="border-b border-slate-200 hover:bg-slate-50 transition-colors">
                  <td className="p-4 text-slate-900 font-medium">{student.name}</td>
                  <td className="p-4 text-slate-900 font-mono font-bold text-xs">
                    {student.registrationNumber}
                  </td>
                  <td className="p-4">
                    <span className="inline-flex items-center gap-1 px-2.5 py-1 bg-emerald-50 text-emerald-800 border border-emerald-200 rounded font-mono font-bold text-xs">
                      <Key className="w-3 h-3 text-emerald-600" />
                      {student.pin || '12345'}
                    </span>
                  </td>
                  <td className="p-4 text-slate-700 text-sm">{student.state}</td>
                  <td className="p-4 text-slate-700 text-sm">{student.examCenter || 'N/A'}</td>
                  <td className="p-4 text-right">
                    <button
                      onClick={() => handleDownloadSlip(student.id, student.registrationNumber)}
                      className="inline-flex items-center gap-1.5 px-3 py-1 bg-white border border-[#008751]/40 text-[#008751] hover:bg-emerald-50 rounded-md text-xs font-semibold shadow-xs transition-colors cursor-pointer"
                      title="Download printable exam slip PDF"
                    >
                      <FileText className="w-3.5 h-3.5" />
                      Download Slip (PDF)
                    </button>
                  </td>
                </tr>
              ))}
              {students.length === 0 && (
                <tr>
                  <td colSpan={6} className="p-8 text-center text-slate-500 text-sm">
                    No students registered yet. Click "Bulk Register CSV" to enroll candidates.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {showModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-slate-50 rounded-lg shadow-lg w-full max-w-lg p-6 max-h-[90vh] overflow-y-auto">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-xl font-bold text-slate-900">Bulk Register CSV</h2>
              <button 
                type="button"
                onClick={handleDownloadTemplate}
                className="text-sm font-medium text-[#008751] hover:text-[#007040] underline underline-offset-2"
              >
                Download Template
              </button>
            </div>
            
            <form onSubmit={handleUpload} className="space-y-4">
              <div>
                <label className="flex items-center gap-2 block text-sm font-medium text-slate-700 mb-2">
                  Select CSV File<span className="-ml-2 text-[18px] font-bold text-destructive">*</span>
                </label>
                <input
                  type="file"
                  accept=".csv"
                  onChange={(e) => setFile(e.target.files?.[0] || null)}
                  className="block w-full text-sm text-slate-500 file:mr-4 file:py-2 file:px-4 file:rounded file:border-0 file:text-sm file:font-semibold file:bg-slate-100 file:text-slate-700 hover:file:bg-slate-200"
                />
                <p className="mt-1.5 text-xs text-slate-500">
                  Tip: Subject names are matched against your subjects table (case-insensitive). Any new subjects in the CSV will also be created automatically.
                </p>

                {availableSubjects.length > 0 && (
                  <div className="mt-3 p-3 bg-slate-100 rounded-md border border-slate-200 text-xs">
                    <span className="font-semibold text-slate-700 block mb-1.5">
                      Your Created Subjects ({availableSubjects.length}):
                    </span>
                    <div className="flex flex-wrap gap-1.5">
                      {availableSubjects.map((s, idx) => (
                        <span key={idx} className="px-2 py-0.5 bg-white border border-slate-300 rounded text-slate-800 font-medium">
                          {s}
                        </span>
                      ))}
                    </div>
                  </div>
                )}
              </div>
              <div className="flex gap-2 justify-end mt-6">
                <button 
                  type="button" 
                  onClick={() => setShowModal(false)}
                  className="px-4 py-2 bg-slate-100 text-slate-700 hover:bg-slate-200 rounded font-medium"
                >
                  Cancel
                </button>
                <button 
                  type="submit" 
                  disabled={!file || isUploading}
                  className="px-4 py-2 bg-[#008751] hover:bg-[#007040] text-white rounded font-medium disabled:opacity-50"
                >
                  {isUploading ? 'Uploading...' : 'Upload CSV'}
                </button>
              </div>
            </form>

            {result && (
              <div className="mt-8 p-4 bg-slate-50 rounded-lg border border-slate-200">
                <h3 className="font-bold text-lg mb-2">Upload Results</h3>
                <p className="text-slate-700">Created: <span className="font-bold text-green-600">{result.created}</span></p>
                {result.errors && result.errors.length > 0 && (
                  <div className="mt-4">
                    <p className="font-bold text-red-600 mb-2">Errors ({result.errors.length}):</p>
                    <ul className="list-disc pl-5 text-sm text-slate-600 max-h-40 overflow-y-auto space-y-1">
                      {result.errors.map((err: string, i: number) => (
                        <li key={i}>{err}</li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
