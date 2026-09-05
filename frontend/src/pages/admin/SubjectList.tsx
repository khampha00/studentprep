import { useState, useEffect } from 'react';
import axios from 'axios';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
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
} from '../../components/ui/alert-dialog';
import { toast } from 'sonner';
import { Link } from 'react-router-dom';
import { Trash2, Edit2 } from 'lucide-react';

export default function SubjectList() {
  const [subjects, setSubjects] = useState<any[]>([]);
  const [newSubjectName, setNewSubjectName] = useState('');
  const [isCreatingSubject, setIsCreatingSubject] = useState(false);
  const [editingSubject, setEditingSubject] = useState<string | null>(null);
  const [editSubjectName, setEditSubjectName] = useState('');

  useEffect(() => {
    fetchSubjects();
  }, []);

  const fetchSubjects = async () => {
    try {
      const res = await axios.get('/api/v1/admin/subjects');
      setSubjects(res.data);
    } catch (e) {
      console.error(e);
      toast.error('Failed to fetch subjects');
    }
  };

  const handleCreateSubject = async () => {
    if (!newSubjectName) return;
    setIsCreatingSubject(true);
    try {
      await axios.post('/api/v1/admin/subjects', { name: newSubjectName });
      toast.success('Subject created');
      setNewSubjectName('');
      await fetchSubjects();
    } catch (e) {
      console.error(e);
      toast.error('Failed to create subject');
    } finally {
      setIsCreatingSubject(false);
    }
  };

  const handleDeleteSubject = async (id: string, e: React.MouseEvent) => {
    e.preventDefault();
    try {
      await axios.delete(`/api/v1/admin/subjects/${id}`);
      toast.success('Subject deleted successfully');
      await fetchSubjects();
    } catch (error: any) {
      if (error.response?.status === 400) {
        toast.error('Cannot delete: Subject has active questions');
      } else {
        toast.error('Failed to delete subject');
      }
    }
  };

  const handleSaveEdit = async (id: string, e: React.MouseEvent) => {
    e.preventDefault();
    if (!editSubjectName) return;

    try {
      await axios.put(`/api/v1/admin/subjects/${id}`, { name: editSubjectName });
      toast.success('Subject updated');
      setEditingSubject(null);
      await fetchSubjects();
    } catch (error) {
      toast.error('Failed to update subject');
    }
  };

  return (
    <div className="p-8 font-sans">
      <div className="max-w-7xl mx-auto space-y-8">
        <h1 className="text-3xl font-bold text-slate-900">Manage Subjects</h1>

        <Card className="border-0 shadow-sm overflow-hidden">
          <CardHeader><CardTitle>Create New Subject</CardTitle></CardHeader>
          <CardContent>
            <div className="flex flex-col gap-2">
              <label className="text-sm font-semibold text-slate-700">Subject Name</label>              <div className="flex gap-2 max-w-md">
                <input
                  className="px-3 py-2 border border-slate-300 rounded-md focus:outline-none focus:ring-2 focus:ring-[#008751] flex-1"
                  placeholder="New Subject Name"
                  value={newSubjectName}
                  onChange={e => setNewSubjectName(e.target.value)}
                />
                <Button onClick={handleCreateSubject} disabled={isCreatingSubject || !newSubjectName} className="bg-[#008751]">
                  Create
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>

        <div className="flex flex-col gap-3">
          {subjects.map(s => (
            <div key={s.id} className="bg-white border border-slate-200 rounded-lg shadow-sm flex items-center justify-between p-4 hover:border-slate-300 transition-colors">

              {/* Left side: Subject Name or Edit Input */}
              <div className="flex-1 flex items-center">
                {editingSubject === s.id ? (
                  <div className="flex gap-2 w-full max-w-md">
                    <input
                      className="px-3 py-1.5 border border-slate-300 rounded-md text-sm flex-1 focus:outline-none focus:ring-2 focus:ring-[#008751]"
                      value={editSubjectName}
                      onChange={e => setEditSubjectName(e.target.value)}
                      autoFocus
                    />
                    <Button size="sm" className="bg-[#008751]" onClick={(e) => handleSaveEdit(s.id, e)}>Save</Button>
                    <Button size="sm" variant="ghost" onClick={() => setEditingSubject(null)}>Cancel</Button>
                  </div>
                ) : (
                  <Link to={`/admin/subjects/${s.id}`} className="block">
                    <h3 className="text-lg font-semibold text-[#008751] hover:underline cursor-pointer">
                      {s.name}
                    </h3>
                    <p className="text-sm text-slate-500 mt-1">Manage question bank and ingest PDFs</p>
                  </Link>
                )}
              </div>

              {/* Right side: Actions */}
              <div className="flex items-center gap-2 pl-4 border-l border-slate-100 ml-4">
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-slate-500 hover:text-slate-900"
                  onClick={(e) => {
                    e.preventDefault();
                    setEditingSubject(s.id);
                    setEditSubjectName(s.name);
                  }}
                >
                  <Edit2 className="w-4 h-4 mr-2" /> Edit
                </Button>
                <AlertDialog>
                  <AlertDialogTrigger>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-destructive hover:bg-destructive/10"
                      onClick={(e) => e.preventDefault()}
                    >
                      <Trash2 className="w-4 h-4 mr-2" /> Delete
                    </Button>
                  </AlertDialogTrigger>
                  <AlertDialogContent onClick={(e) => e.stopPropagation()}>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Delete Subject</AlertDialogTitle>
                      <AlertDialogDescription>
                        Are you sure you want to delete <strong className="text-slate-900 font-semibold">{s.name}</strong>?
                        This action cannot be undone.
                      </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                      <AlertDialogCancel>Cancel</AlertDialogCancel>
                      <AlertDialogAction
                        className="bg-destructive text-white hover:bg-destructive/90"
                        onClick={(e) => handleDeleteSubject(s.id, e)}
                      >
                        Delete
                      </AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              </div>
            </div>
          ))}

          {subjects.length === 0 && (
            <div className="text-center p-8 bg-slate-50 border border-slate-200 border-dashed rounded-lg">
              <p className="text-slate-500">No subjects found. Create one above.</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
