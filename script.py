import sys

def process_file(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

    # Insert grouping helper
    helper_code = """
  const groupQuestions = (questions: any[]) => {
    const groups: any[] = [];
    questions.forEach((q) => {
        if (q.context) {
            let group = groups.find(g => g.contextId === q.context.id);
            if (!group) {
                group = { contextId: q.context.id, passage: q.context.passage, questions: [] };
                groups.push(group);
            }
            group.questions.push(q);
        } else {
            groups.push({ contextId: null, passage: null, questions: [q] });
        }
    });
    return groups;
  };
"""
    content = content.replace("  if (!subject) {", helper_code + "\n  if (!subject) {")

    # Update ACTIVE mapping
    active_old = """
                  activeQuestions.map((q, idx) => (
                    <Card key={q.id} className="flex flex-col relative">
                      <CardHeader className="pb-2">
                        <CardTitle className="text-lg text-[#008751]">Question #{idx + 1}</CardTitle>
                      </CardHeader>
                      <CardContent className="flex-1 text-sm text-slate-700">
                        {q.content?.assets?.map((asset: string, i: number) => (
                          <div key={i} className="mb-4 text-center">
                            <img src={asset} alt="Diagram" className="max-w-full max-h-[300px] object-contain mx-auto rounded border border-slate-200" />
                          </div>
                        ))}
                        <div className="font-medium mb-4">
                          <MathText content={q.content?.text || q.content?.passage || "No text available"} />
                        </div>
                        <div className="space-y-2 mt-4 p-4 bg-slate-50 rounded-md">
                          {Object.entries(q.content?.options || {}).map(([k, v]) => (
                            <div key={k} className={`flex gap-3 items-start ${q.content?.correctOption === k ? 'text-[#008751] font-bold' : ''}`}>
                              <span className="shrink-0 mt-0.5 w-6">{k}:</span>
                              <div className="flex-1"><MathText content={String(v)} /></div>
                            </div>
                          ))}
                        </div>
                      </CardContent>
                      <div className="p-3 border-t border-slate-100 flex justify-between items-center">
                        <div className="text-sm font-semibold text-[#008751]">
                          Correct Answer: {q.content?.correctOption || 'N/A'}
                        </div>
                        <AlertDialog>
                          <AlertDialogTrigger>
                            <Button variant="ghost" size="sm" className="text-destructive hover:bg-destructive/10 h-8">
                              Delete Question
                            </Button>
                          </AlertDialogTrigger>
                          <AlertDialogContent>
                            <AlertDialogHeader>
                              <AlertDialogTitle>Delete Question</AlertDialogTitle>
                              <AlertDialogDescription>
                                Are you sure you want to delete this active question? This action cannot be undone.
                              </AlertDialogDescription>
                            </AlertDialogHeader>
                            <AlertDialogFooter>
                              <AlertDialogCancel>Cancel</AlertDialogCancel>
                              <AlertDialogAction
                                className="bg-destructive text-white hover:bg-destructive/90"
                                onClick={async () => {
                                  try {
                                    await axios.delete(`/api/v1/admin/questions/${q.id}`);
                                    toast.success('Question deleted');
                                    fetchActiveQuestions();
                                  } catch (e) {
                                    toast.error('Failed to delete question');
                                  }
                                }}
                              >
                                Delete
                              </AlertDialogAction>
                            </AlertDialogFooter>
                          </AlertDialogContent>
                        </AlertDialog>
                      </div>
                    </Card>
                  ))"""

    active_render_card = """
                    <Card key={q.id} className="flex flex-col relative h-full">
                      <CardHeader className="pb-2">
                        <CardTitle className="text-lg text-[#008751]">Question ID: {q.id.substring(0, 8)}</CardTitle>
                      </CardHeader>
                      <CardContent className="flex-1 text-sm text-slate-700">
                        {q.content?.assets?.map((asset: string, i: number) => (
                          <div key={i} className="mb-4 text-center">
                            <img src={asset} alt="Diagram" className="max-w-full max-h-[300px] object-contain mx-auto rounded border border-slate-200" />
                          </div>
                        ))}
                        <div className="font-medium mb-4">
                          <MathText content={q.content?.text || q.content?.passage || "No text available"} />
                        </div>
                        <div className="space-y-2 mt-4 p-4 bg-slate-50 rounded-md">
                          {Object.entries(q.content?.options || {}).map(([k, v]) => (
                            <div key={k} className={`flex gap-3 items-start ${q.content?.correctOption === k ? 'text-[#008751] font-bold' : ''}`}>
                              <span className="shrink-0 mt-0.5 w-6">{k}:</span>
                              <div className="flex-1"><MathText content={String(v)} /></div>
                            </div>
                          ))}
                        </div>
                      </CardContent>
                      <div className="p-3 border-t border-slate-100 flex justify-between items-center">
                        <div className="text-sm font-semibold text-[#008751]">
                          Correct Answer: {q.content?.correctOption || 'N/A'}
                        </div>
                        <AlertDialog>
                          <AlertDialogTrigger>
                            <Button variant="ghost" size="sm" className="text-destructive hover:bg-destructive/10 h-8">
                              Delete
                            </Button>
                          </AlertDialogTrigger>
                          <AlertDialogContent>
                            <AlertDialogHeader>
                              <AlertDialogTitle>Delete Question</AlertDialogTitle>
                              <AlertDialogDescription>
                                Are you sure you want to delete this active question? This action cannot be undone.
                              </AlertDialogDescription>
                            </AlertDialogHeader>
                            <AlertDialogFooter>
                              <AlertDialogCancel>Cancel</AlertDialogCancel>
                              <AlertDialogAction
                                className="bg-destructive text-white hover:bg-destructive/90"
                                onClick={async () => {
                                  try {
                                    await axios.delete(`/api/v1/admin/questions/${q.id}`);
                                    toast.success('Question deleted');
                                    fetchActiveQuestions();
                                  } catch (e) {
                                    toast.error('Failed to delete question');
                                  }
                                }}
                              >
                                Delete
                              </AlertDialogAction>
                            </AlertDialogFooter>
                          </AlertDialogContent>
                        </AlertDialog>
                      </div>
                    </Card>"""

    active_new = f"""
                  groupQuestions(activeQuestions).map((group, groupIdx) => {{
                    if (group.contextId) {{
                      return (
                        <div key={{group.contextId}} className="col-span-2 border-2 border-slate-300 rounded-xl p-4 bg-slate-50/50 space-y-4">
                          <div className="p-4 bg-white border border-slate-200 rounded-md shadow-sm">
                            <h4 className="font-bold text-slate-800 mb-2 uppercase tracking-wider text-xs">Shared Context</h4>
                            <div className="prose prose-slate max-w-none text-sm"><MathText content={{group.passage}} /></div>
                          </div>
                          <div className="grid grid-cols-2 gap-4">
                            {{group.questions.map((q: any) => (
{active_render_card}
                            ))}}
                          </div>
                        </div>
                      );
                    }} else {{
                      const q = group.questions[0];
                      return (
{active_render_card}
                      );
                    }}
                  }})
"""
    content = content.replace(active_old, active_new)


    draft_old = """
                  draftQuestions.map((q, idx) => (
                    <DraftQuestionCard
                      key={q.id}
                      initialQuestion={q}
                      idx={idx}
                      onApprove={handleApprove}
                      onReject={handleReject}
                    />
                  ))"""

    draft_new = """
                  groupQuestions(draftQuestions).map((group, groupIdx) => {
                    if (group.contextId) {
                      return (
                        <div key={group.contextId} className="col-span-2 border-2 border-slate-300 rounded-xl p-4 bg-slate-50/50 space-y-4">
                          <div className="p-4 bg-white border border-slate-200 rounded-md shadow-sm">
                            <h4 className="font-bold text-slate-800 mb-2 uppercase tracking-wider text-xs">Shared Context</h4>
                            <div className="prose prose-slate max-w-none text-sm"><MathText content={group.passage} /></div>
                          </div>
                          <div className="grid grid-cols-2 gap-4">
                            {group.questions.map((q: any, idx: number) => (
                              <DraftQuestionCard
                                key={q.id}
                                initialQuestion={q}
                                idx={idx}
                                onApprove={handleApprove}
                                onReject={handleReject}
                              />
                            ))}
                          </div>
                        </div>
                      );
                    } else {
                      const q = group.questions[0];
                      return (
                        <DraftQuestionCard
                          key={q.id}
                          initialQuestion={q}
                          idx={0}
                          onApprove={handleApprove}
                          onReject={handleReject}
                        />
                      );
                    }
                  })"""

    content = content.replace(draft_old, draft_new)

    with open(file_path, 'w') as f:
        f.write(content)

if __name__ == "__main__":
    process_file(r"c:\\Users\\User\\Desktop\\studentprep\\frontend\\src\\pages\\admin\\SubjectDetail.tsx")
