import Prism from 'prismjs';
import 'prismjs/components/prism-java';
import 'prismjs/components/prism-typescript';
import 'prismjs/components/prism-jsx';
import 'prismjs/components/prism-tsx';

/** Satırdaki renklendirilmiş parça; {@code types} Prism token tipleri (ör. keyword, string). */
export interface Segment {
  text: string;
  types: string[];
}

const GRAMMAR_ALIASES: Record<string, string> = {
  java: 'java',
  typescript: 'typescript',
  ts: 'typescript',
  tsx: 'tsx',
  jsx: 'jsx',
  javascript: 'javascript',
  js: 'javascript',
};

/**
 * Kodu Prism ile token'lara ayırıp satırlara böler. Birden çok satıra yayılan token'lar (blok yorum, şablon metin)
 * her satırda aynı tiple devam eder; böylece satır numarası ve satır vurgusu HTML bölmeden yapılabilir.
 */
export function highlightLines(code: string, language: string | null | undefined): Segment[][] {
  const grammarName = GRAMMAR_ALIASES[(language ?? '').toLowerCase()];
  const grammar = grammarName ? Prism.languages[grammarName] : undefined;
  const lines: Segment[][] = [[]];
  const push = (text: string, types: string[]) => {
    const parts = text.split('\n');
    parts.forEach((part, i) => {
      if (i > 0) lines.push([]);
      if (part) lines[lines.length - 1].push({ text: part, types });
    });
  };
  const walk = (tokens: Array<string | Prism.Token>, types: string[]) => {
    for (const token of tokens) {
      if (typeof token === 'string') {
        push(token, types);
        continue;
      }
      const alias = token.alias ? (Array.isArray(token.alias) ? token.alias : [token.alias]) : [];
      const nested = [...types, token.type, ...alias];
      if (typeof token.content === 'string') push(token.content, nested);
      else if (Array.isArray(token.content)) walk(token.content, nested);
      else walk([token.content], nested);
    }
  };
  if (grammar) walk(Prism.tokenize(code, grammar), []);
  else push(code, []);
  return lines;
}
