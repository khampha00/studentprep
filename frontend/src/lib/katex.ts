import type { ComponentProps } from 'react';
import type ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';

type ReactMarkdownProps = ComponentProps<typeof ReactMarkdown>;
type RemarkPlugins = NonNullable<ReactMarkdownProps['remarkPlugins']>;
type RehypePlugins = NonNullable<ReactMarkdownProps['rehypePlugins']>;
type UrlTransform = NonNullable<ReactMarkdownProps['urlTransform']>;

interface HastNode {
  type?: string;
  properties?: {
    className?: unknown;
  };
  children?: HastNode[];
}

const DANGEROUS_PAIRED_TAGS_RE =
  /<\s*(script|style|iframe|object|embed|applet|svg|noscript|template|textarea|title)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi;

const DANGEROUS_SINGLE_TAGS_RE =
  /<\s*\/?\s*(script|style|iframe|object|embed|applet|svg|noscript|template|base|meta|link|form|frame|frameset|input|button)\b[^>]*>/gi;

const DANGEROUS_ATTR_TAGS_RE =
  /<[a-zA-Z][a-zA-Z0-9-]*(?:\s+[^<>]*?)?(?:\bon[a-z]+\s*=|srcdoc\s*=|(?:javascript|vbscript)\s*:|data\s*:\s*text\/html)[^<>]*>/gi;

const DANGEROUS_MARKDOWN_LINK_URL_RE =
  /(!?\[[^\]]*\]\(\s*)(?:(?:javascript|vbscript)\s*:|data\s*:\s*text\/html)[^)]*(\))/gi;

const SAFE_URL_PROTOCOL_RE = /^(https?|mailto)$/i;

/**
 * Returns true if the character at `index` is preceded by an even number of backslashes.
 */
function isUnescaped(text: string, index: number): boolean {
  let backslashes = 0;
  let pos = index - 1;
  while (pos >= 0 && text[pos] === '\\') {
    backslashes += 1;
    pos -= 1;
  }
  return backslashes % 2 === 0;
}

/**
 * Upgrades `\frac` to `\dfrac` and inserts `\mathstrut` inside `\sqrt{...}` for KaTeX legibility.
 */
function enhanceMathExpression(expr: string): string {
  return expr
    .replace(/\\frac(?![a-zA-Z])/g, '\\dfrac')
    .replace(
      /\\sqrt(\[[^\]]*\])?\{(?!\s*\\mathstrut\b)/g,
      (_match, optRoot: string | undefined) =>
        optRoot ? `\\sqrt${optRoot}{\\mathstrut ` : '\\sqrt{\\mathstrut '
    );
}

/**
 * Finds the matching closing unescaped `$` (single) without crossing a blank line or fenced code.
 */
function findClosingSingleDollar(text: string, startIndex: number): number {
  let j = startIndex;
  while (j < text.length) {
    if (text[j] === '\n' && text[j + 1] === '\n') {
      return -1;
    }
    if (text[j] === '$' && isUnescaped(text, j)) {
      if (text[j + 1] === '$') {
        j += 2;
        continue;
      }
      return j;
    }
    j += 1;
  }
  return -1;
}

/**
 * Finds the matching closing unescaped `$$`.
 */
function findClosingDoubleDollar(text: string, startIndex: number): number {
  let j = startIndex;
  while (j < text.length - 1) {
    if (text[j] === '$' && text[j + 1] === '$' && isUnescaped(text, j)) {
      return j;
    }
    j += 1;
  }
  return -1;
}

/**
 * Finds a matching unescaped delimiter sequence (`\)` or `\]`).
 */
function findClosingLatexDelimiter(
  text: string,
  startIndex: number,
  closing: '\\)' | '\\]',
  stopOnBlankLine: boolean
): number {
  let j = startIndex;
  while (j < text.length - 1) {
    if (stopOnBlankLine && text[j] === '\n' && text[j + 1] === '\n') {
      return -1;
    }
    if (text.startsWith(closing, j) && isUnescaped(text, j)) {
      return j;
    }
    j += 1;
  }
  return -1;
}

/**
 * Checks whether `index` begins a CommonMark fenced code block (``` or ~~~) and returns its end index.
 */
function matchFencedCodeBlockEnd(text: string, index: number): number {
  if (index !== 0 && text[index - 1] !== '\n') {
    return -1;
  }
  const lineEnd = text.indexOf('\n', index);
  const firstLine = lineEnd === -1 ? text.slice(index) : text.slice(index, lineEnd);
  const openMatch = /^ {0,3}(`{3,}|~{3,})/.exec(firstLine);
  if (!openMatch) {
    return -1;
  }
  const fence = openMatch[1];
  const fenceChar = fence[0];
  const fenceLen = fence.length;
  if (fenceChar === '`' && firstLine.slice(openMatch[0].length).includes('`')) {
    return -1;
  }
  if (lineEnd === -1) {
    return text.length;
  }

  let cursor = lineEnd + 1;
  const closePattern = new RegExp(`^ {0,3}${fenceChar}{${fenceLen},}[ \\t]*$`);
  while (cursor < text.length) {
    const nextNewline = text.indexOf('\n', cursor);
    const line = nextNewline === -1 ? text.slice(cursor) : text.slice(cursor, nextNewline);
    if (closePattern.test(line)) {
      return nextNewline === -1 ? text.length : nextNewline + 1;
    }
    if (nextNewline === -1) {
      return text.length;
    }
    cursor = nextNewline + 1;
  }
  return text.length;
}

function stripNullBytes(value: string): string {
  return value.includes('\0') ? value.split('\0').join('') : value;
}

function stripControlAndWhitespace(value: string): string {
  let out = '';
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i);
    if (code <= 0x1f || code === 0x7f || code === 0x20) {
      continue;
    }
    const ch = value[i];
    if (/\s/.test(ch)) {
      continue;
    }
    out += ch;
  }
  return out;
}

/**
 * Strips raw active/dangerous HTML markup and dangerous link schemes before Markdown parsing.
 */
export function sanitizeMarkdownInput(raw: string): string {
  return stripNullBytes(raw)
    .replace(/\r\n?/g, '\n')
    .replace(DANGEROUS_PAIRED_TAGS_RE, '')
    .replace(DANGEROUS_SINGLE_TAGS_RE, '')
    .replace(DANGEROUS_ATTR_TAGS_RE, '')
    .replace(DANGEROUS_MARKDOWN_LINK_URL_RE, '$1#$2');
}

/**
 * Normalizes both `\(...\)` inline math to `$...$` and `\[...\]` display math to fenced `$$\n...\n$$`
 * without corrupting escaped delimiters (`\\(`, `\\[`), existing `$...$`/`$$...$$` spans, or code blocks.
 */
export function normalizeKatexDelimiters(input: string): string {
  let out = '';
  let i = 0;

  while (i < input.length) {
    const fenceEnd = matchFencedCodeBlockEnd(input, i);
    if (fenceEnd !== -1) {
      out += input.slice(i, fenceEnd);
      i = fenceEnd;
      continue;
    }

    if (input[i] === '`') {
      let tickLen = 0;
      while (i + tickLen < input.length && input[i + tickLen] === '`') {
        tickLen += 1;
      }
      const marker = '`'.repeat(tickLen);
      let searchIdx = i + tickLen;
      let closeTickIdx = -1;
      while (searchIdx < input.length) {
        const found = input.indexOf(marker, searchIdx);
        if (found === -1) break;
        const beforeOk = found === 0 || input[found - 1] !== '`';
        const afterOk = found + tickLen >= input.length || input[found + tickLen] !== '`';
        if (beforeOk && afterOk) {
          closeTickIdx = found;
          break;
        }
        searchIdx = found + tickLen;
      }
      if (closeTickIdx !== -1) {
        out += input.slice(i, closeTickIdx + tickLen);
        i = closeTickIdx + tickLen;
      } else {
        out += marker;
        i += tickLen;
      }
      continue;
    }

    if (input[i] === '$' && isUnescaped(input, i)) {
      if (input[i + 1] === '$') {
        const closeDouble = findClosingDoubleDollar(input, i + 2);
        if (closeDouble !== -1) {
          const expr = enhanceMathExpression(input.slice(i + 2, closeDouble));
          out += `$$${expr}$$`;
          i = closeDouble + 2;
          continue;
        }
        out += '$$';
        i += 2;
        continue;
      }

      const closeSingle = findClosingSingleDollar(input, i + 1);
      if (closeSingle !== -1) {
        const expr = enhanceMathExpression(input.slice(i + 1, closeSingle));
        out += `$${expr}$`;
        i = closeSingle + 1;
        continue;
      }

      out += '$';
      i += 1;
      continue;
    }

    if (input.startsWith('\\[', i) && isUnescaped(input, i)) {
      const closeDisplay = findClosingLatexDelimiter(input, i + 2, '\\]', false);
      if (closeDisplay !== -1) {
        const expr = enhanceMathExpression(input.slice(i + 2, closeDisplay).trim());
        if (expr.length > 0) {
          out = out.replace(/[ \t]+$/, '');
          if (out.length > 0 && !out.endsWith('\n')) {
            out += '\n';
          }
          out += `$$\n${expr}\n$$`;
          let nextIdx = closeDisplay + 2;
          while (nextIdx < input.length && (input[nextIdx] === ' ' || input[nextIdx] === '\t')) {
            nextIdx += 1;
          }
          if (nextIdx < input.length && input[nextIdx] !== '\n') {
            out += '\n';
          }
          i = nextIdx;
          continue;
        }
      }
      out += '\\[';
      i += 2;
      continue;
    }

    if (input.startsWith('\\(', i) && isUnescaped(input, i)) {
      const closeInline = findClosingLatexDelimiter(input, i + 2, '\\)', true);
      if (closeInline !== -1) {
        const expr = enhanceMathExpression(input.slice(i + 2, closeInline).trim());
        if (expr.length > 0) {
          out += `$${expr}$`;
          i = closeInline + 2;
          continue;
        }
      }
      out += '\\(';
      i += 2;
      continue;
    }

    out += input[i];
    i += 1;
  }

  return out;
}

/**
 * Sanitizes untrusted text and normalizes inline/block KaTeX delimiters for safe Markdown rendering.
 */
export function prepareMathMarkdown(input: string | null | undefined): string {
  if (!input || typeof input !== 'string') {
    return '';
  }
  const sanitized = sanitizeMarkdownInput(input);
  return normalizeKatexDelimiters(sanitized);
}

/**
 * Enforces an explicit safe URL allowlist for rendered Markdown links and images.
 */
export const safeUrlTransform: UrlTransform = (value: string): string => {
  const cleaned = stripControlAndWhitespace(value);
  if (!cleaned) {
    return '';
  }
  const colon = cleaned.indexOf(':');
  const slash = cleaned.indexOf('/');
  const questionMark = cleaned.indexOf('?');
  const hash = cleaned.indexOf('#');

  if (
    colon === -1 ||
    (slash !== -1 && colon > slash) ||
    (questionMark !== -1 && colon > questionMark) ||
    (hash !== -1 && colon > hash) ||
    SAFE_URL_PROTOCOL_RE.test(cleaned.slice(0, colon))
  ) {
    return value.trim();
  }
  return '';
};

/**
 * Adds `not-prose` to rendered `.katex` elements so `@tailwindcss/typography` rules never distort math.
 */
function rehypeKatexNotProse() {
  return (tree: HastNode) => {
    const walk = (node: HastNode) => {
      if (node.type === 'element' && node.properties && Array.isArray(node.properties.className)) {
        if (
          node.properties.className.includes('katex') &&
          !node.properties.className.includes('not-prose')
        ) {
          node.properties.className.push('not-prose');
        }
      }
      if (node.children) {
        node.children.forEach(walk);
      }
    };
    walk(tree);
  };
}

export const MARKDOWN_REMARK_PLUGINS: RemarkPlugins = [remarkMath, remarkGfm];

export const MARKDOWN_REHYPE_PLUGINS: RehypePlugins = [
  [rehypeKatex, { trust: false, maxExpand: 1000, strict: false }],
  rehypeKatexNotProse,
];
