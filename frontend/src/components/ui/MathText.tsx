import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import remarkGfm from 'remark-gfm';
import 'katex/dist/katex.min.css';

interface MathTextProps {
  content: string;
  className?: string;
}

export function MathText({ content, className = '' }: MathTextProps) {
  // 1. Replace \frac with \dfrac for spacious fractions
  // 2. Add \mathstrut inside \sqrt to fix the KaTeX bug where the root line intersects superscripts
  const processedContent = content 
    ? content.replace(/\\frac/g, '\\dfrac').replace(/\\sqrt\{/g, '\\sqrt{\\mathstrut ')
    : '';

  return (
    <div className={`prose prose-sm prose-slate max-w-none ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkMath, remarkGfm]}
        rehypePlugins={[
          rehypeKatex,
          () => (tree) => {
            const walk = (node: any) => {
              if (node.type === 'element' && node.properties && node.properties.className) {
                if (Array.isArray(node.properties.className) && node.properties.className.includes('katex')) {
                  node.properties.className.push('not-prose');
                }
              }
              if (node.children) {
                node.children.forEach(walk);
              }
            };
            walk(tree);
          }
        ]}
      >
        {processedContent}
      </ReactMarkdown>
    </div>
  );
}
