import sys

def process_file(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

    # Find where currentQ is defined
    content = content.replace("  const currentQ = questions[currentIdx];", "  const currentQ = questions[currentIdx];\n  const sharedContext = currentQ?.contextId ? exam.contexts[currentQ.contextId] : null;")

    old_block = """        <div className="md:col-span-3">
          <Card className="p-2 md:p-4">
            <CardHeader className="flex flex-row justify-between items-center mb-2 border-b-0">
              <CardTitle className="text-lg font-bold text-slate-800">Question {currentIdx + 1} of {questions.length}</CardTitle>
              <span className="bg-slate-100 text-slate-600 px-3 py-1 rounded-full text-xs font-semibold">{currentQ.subject}</span>
            </CardHeader>
            <CardContent>
              <div className="text-slate-800 text-lg leading-relaxed mb-8">
                {currentQ.content.assets && currentQ.content.assets.map((asset: any, i: number) => (
                  asset.type === 'IMAGE' && <img key={i} src={asset.url} alt={asset.alt} className="mb-4 max-w-md" />
                ))}
                <RichText text={currentQ.content.text || currentQ.content.passage || ''} />
              </div>
              <RadioGroup 
                value={exam.answers[currentQ.id]} 
                onValueChange={(val) => dispatch(answerQuestion({ questionId: currentQ.id, optionId: val }))}
                className="space-y-3"
              >
                {Object.entries(currentQ.content.options || {}).map(([optKey, optText], i) => {
                  const optId = optKey;
                  const isSelected = exam.answers[currentQ.id] === optId;
                  return (
                    <Label
                      key={i}
                      htmlFor={optId}
                      className={cn("flex items-center gap-4 w-full text-left px-5 py-4 rounded-lg border-2 transition-all cursor-pointer", isSelected ? "border-primary bg-primary/5 shadow-sm" : "border-slate-200 hover:border-slate-300 hover:bg-slate-50")}
                    >
                      <RadioGroupItem value={optId} id={optId} className={cn(isSelected ? "text-primary border-primary" : "")} />
                      <span className={cn("font-bold text-lg", isSelected ? "text-primary" : "text-slate-400")}>{optKey}</span>
                      <span className="font-medium text-slate-700 text-base flex-1">
                        <RichText text={String(optText)} />
                      </span>
                    </Label>
                  )
                })}
              </RadioGroup>
            </CardContent>
            <CardFooter className="flex justify-between mt-6 pt-6 border-t border-slate-100">
              <Button 
                variant="outline"
                onClick={() => setCurrentIdx(Math.max(0, currentIdx - 1))}
                disabled={currentIdx === 0}
              >
                Previous
              </Button>
              {currentIdx < questions.length - 1 ? (
                <Button 
                  onClick={() => setCurrentIdx(currentIdx + 1)}
                >
                  Next Question
                </Button>
              ) : (
                <SubmitButton>Submit Final</SubmitButton>
              )}
            </CardFooter>
          </Card>
        </div>"""

    new_block = """        <div className="md:col-span-3">
          <Card className="p-0 overflow-hidden flex flex-col min-h-[600px] shadow-sm">
            <CardHeader className="flex flex-row justify-between items-center mb-0 border-b p-4 bg-white shrink-0">
              <CardTitle className="text-lg font-bold text-slate-800">Question {currentIdx + 1} of {questions.length}</CardTitle>
              <span className="bg-slate-100 text-slate-600 px-3 py-1 rounded-full text-xs font-semibold">{currentQ.subject}</span>
            </CardHeader>
            <CardContent className="p-0 flex-1 flex flex-col md:flex-row relative">
              {sharedContext && (
                <div className="md:w-1/2 p-4 md:p-6 border-b md:border-b-0 md:border-r border-slate-200 bg-slate-50 overflow-y-auto max-h-[50vh] md:max-h-[65vh]">
                  <div className="mb-4">
                    <span className="text-xs font-bold uppercase tracking-wider text-slate-500 bg-slate-200 px-2 py-1 rounded">Shared Context</span>
                  </div>
                  <RichText text={sharedContext} />
                </div>
              )}
              <div className={cn("p-4 md:p-6 overflow-y-auto max-h-[65vh]", sharedContext ? "md:w-1/2" : "w-full")}>
                <div className="text-slate-800 text-lg leading-relaxed mb-8">
                  {currentQ.content.assets && currentQ.content.assets.map((asset: any, i: number) => (
                    asset.type === 'IMAGE' && <img key={i} src={asset.url} alt={asset.alt} className="mb-4 max-w-md" />
                  ))}
                  <RichText text={currentQ.content.text || currentQ.content.passage || ''} />
                </div>
                <RadioGroup 
                  value={exam.answers[currentQ.id]} 
                  onValueChange={(val) => dispatch(answerQuestion({ questionId: currentQ.id, optionId: val }))}
                  className="space-y-3"
                >
                  {Object.entries(currentQ.content.options || {}).map(([optKey, optText], i) => {
                    const optId = optKey;
                    const isSelected = exam.answers[currentQ.id] === optId;
                    return (
                      <Label
                        key={i}
                        htmlFor={optId}
                        className={cn("flex items-center gap-4 w-full text-left px-5 py-4 rounded-lg border-2 transition-all cursor-pointer", isSelected ? "border-primary bg-primary/5 shadow-sm" : "border-slate-200 hover:border-slate-300 hover:bg-slate-50")}
                      >
                        <RadioGroupItem value={optId} id={optId} className={cn(isSelected ? "text-primary border-primary" : "")} />
                        <span className={cn("font-bold text-lg", isSelected ? "text-primary" : "text-slate-400")}>{optKey}</span>
                        <span className="font-medium text-slate-700 text-base flex-1">
                          <RichText text={String(optText)} />
                        </span>
                      </Label>
                    )
                  })}
                </RadioGroup>
              </div>
            </CardContent>
            <CardFooter className="flex justify-between p-4 border-t border-slate-200 bg-white shrink-0">
              <Button 
                variant="outline"
                onClick={() => setCurrentIdx(Math.max(0, currentIdx - 1))}
                disabled={currentIdx === 0}
              >
                Previous
              </Button>
              {currentIdx < questions.length - 1 ? (
                <Button 
                  onClick={() => setCurrentIdx(currentIdx + 1)}
                >
                  Next Question
                </Button>
              ) : (
                <SubmitButton>Submit Final</SubmitButton>
              )}
            </CardFooter>
          </Card>
        </div>"""

    content = content.replace(old_block, new_block)

    with open(file_path, 'w') as f:
        f.write(content)

if __name__ == "__main__":
    process_file(r"c:\\Users\\User\\Desktop\\studentprep\\frontend\\src\\App.tsx")
