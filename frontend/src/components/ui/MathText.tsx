import type { ReactNode } from 'react';
import ReactMarkdown from 'react-markdown';
import 'katex/dist/katex.min.css';
import {
  MARKDOWN_REHYPE_PLUGINS,
  MARKDOWN_REMARK_PLUGINS,
  prepareMathMarkdown,
  safeUrlTransform,
} from '@/lib/katex';
import { cn } from '@/lib/utils';

interface MathTextProps {
  content: string;
  className?: string;
  inline?: boolean;
}

const INLINE_MARKDOWN_COMPONENTS = {
  p: ({ children }: { children?: ReactNode }) => <span>{children}</span>,
};

export function MathText({ content, className = '', inline = false }: MathTextProps) {
  const processedContent = prepareMathMarkdown(content);

  if (inline) {
    return (
      <span
        className={cn(
          'inline-block align-baseline text-slate-900 [&>p]:inline [&>p]:m-0',
          className
        )}
      >
        <ReactMarkdown
          skipHtml
          urlTransform={safeUrlTransform}
          remarkPlugins={MARKDOWN_REMARK_PLUGINS}
          rehypePlugins={MARKDOWN_REHYPE_PLUGINS}
          components={INLINE_MARKDOWN_COMPONENTS}
        >
          {processedContent}
        </ReactMarkdown>
      </span>
    );
  }

  return (
    <div
      className={cn(
        'prose prose-sm prose-slate max-w-none text-slate-900 [&>*:first-child]:mt-0 [&>*:last-child]:mb-0',
        className
      )}
    >
      <ReactMarkdown
        skipHtml
        urlTransform={safeUrlTransform}
        remarkPlugins={MARKDOWN_REMARK_PLUGINS}
        rehypePlugins={MARKDOWN_REHYPE_PLUGINS}
      >
        {processedContent}
      </ReactMarkdown>
    </div>
  );
}
