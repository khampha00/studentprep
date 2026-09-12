import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkMath from 'remark-math';
import remarkGfm from 'remark-gfm';
import rehypeKatex from 'rehype-katex';

export const RichText = React.memo(function RichText({ text }: { text: string }) {
  const processedText = text.replace(/\\\((.*?)\\\)/g, '$$$1$$');
  return (
    <div className="prose prose-slate max-w-none">
      <ReactMarkdown
        remarkPlugins={[remarkMath, remarkGfm]}
        rehypePlugins={[rehypeKatex]}
      >
        {processedText}
      </ReactMarkdown>
    </div>
  );
});

export default RichText;
