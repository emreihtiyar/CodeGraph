import { describe, expect, it } from 'vitest';
import { highlightLines } from './codeLines.ts';

const text = (line: { text: string }[]) => line.map((s) => s.text).join('');

describe('highlightLines', () => {
  it('splits highlighted code into lines without losing text', () => {
    const code = 'public class A {\n  /* çok\n     satırlı */\n  String s = "x";\n}';
    const lines = highlightLines(code, 'java');
    expect(lines.map(text)).toEqual(code.split('\n'));
    expect(lines[0][0]).toEqual({ text: 'public', types: ['keyword'] });
    // blok yorum ikinci satırda da yorum olarak kalır
    expect(lines[2][0].types).toContain('comment');
    expect(lines[3].find((s) => s.text === '"x"')?.types).toContain('string');
  });

  it('handles tsx and unknown languages', () => {
    const tsx = 'const a = <Screen id={1} />;';
    expect(highlightLines(tsx, 'tsx').map(text)).toEqual([tsx]);
    expect(highlightLines('a\n\nb', 'cobol')).toEqual([[{ text: 'a', types: [] }], [], [{ text: 'b', types: [] }]]);
  });
});
