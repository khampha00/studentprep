import { useState, useEffect } from 'react';
import axios from 'axios';
import { toast } from 'sonner';

export default function StudentsPage() {
  const [students, setStudents] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
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

  useEffect(() => {
    fetchStudents();
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
    } catch (error) {
      toast.error('Failed to upload file');
      console.error(error);
    } finally {
      setIsUploading(false);
    }
  };

  return (
    <div className="p-8 font-sans">
      <div className="flex justify-between items-center mb-8">
        <h1 className="text-3xl font-bold text-slate-900">Students</h1>
        <button 
          onClick={() => { setShowModal(true); setResult(null); setFile(null); }}
          className="bg-[#008751] hover:bg-[#007040] text-white px-4 py-2 rounded font-medium"
        >
          Bulk Register CSV
        </button>
      </div>

      {loading ? (
        <div className="text-slate-500">Loading students...</div>
      ) : (
        <div className="overflow-x-auto border border-slate-200 rounded-lg">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-200">
                <th className="p-4 font-semibold text-slate-700">Name</th>
                <th className="p-4 font-semibold text-slate-700">Reg Number</th>
                <th className="p-4 font-semibold text-slate-700">State</th>
                <th className="p-4 font-semibold text-slate-700">Center</th>
              </tr>
            </thead>
            <tbody>
              {students.map((student, idx) => (
                <tr key={idx} className="border-b border-slate-200 bg-slate-50">
                  <td className="p-4 text-slate-900 font-medium">{student.name}</td>
                  <td className="p-4 text-slate-900">{student.registrationNumber}</td>
                  <td className="p-4 text-slate-900">{student.state}</td>
                  <td className="p-4 text-slate-900">{student.examCenter}</td>
                </tr>
              ))}
              {students.length === 0 && (
                <tr>
                  <td colSpan={4} className="p-4 text-center text-slate-500">No students registered</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {showModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-slate-50 rounded-lg shadow-lg w-full max-w-lg p-6 max-h-[90vh] overflow-y-auto">
            <h2 className="text-xl font-bold text-slate-900 mb-4">Bulk Register CSV</h2>
            
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
