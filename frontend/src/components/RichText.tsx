import React from 'react';
import ReactMarkdown from 'react-markdown';
import {
  MARKDOWN_REHYPE_PLUGINS,
  MARKDOWN_REMARK_PLUGINS,
  prepareMathMarkdown,
  safeUrlTransform,
} from '@/lib/katex';
import { cn } from '@/lib/utils';

interface RichTextProps {
  text: string;
  className?: string;
  inline?: boolean;
}

const INLINE_MARKDOWN_COMPONENTS = {
  p: ({ children }: { children?: React.ReactNode }) => <span>{children}</span>,
};

export const RichText = React.memo(function RichText({
  text,
  className,
  inline = false,
}: RichTextProps) {
  const processedText = prepareMathMarkdown(text);

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
          {processedText}
        </ReactMarkdown>
      </span>
    );
  }

  return (
    <div
      className={cn(
        'prose prose-slate max-w-none text-slate-900 [&>*:first-child]:mt-0 [&>*:last-child]:mb-0',
        className
      )}
    >
      <ReactMarkdown
        skipHtml
        urlTransform={safeUrlTransform}
        remarkPlugins={MARKDOWN_REMARK_PLUGINS}
        rehypePlugins={MARKDOWN_REHYPE_PLUGINS}
      >
        {processedText}
      </ReactMarkdown>
    </div>
  );
});

export default RichText;
