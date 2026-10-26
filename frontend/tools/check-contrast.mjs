import { readFileSync } from 'node:fs';

// 校验设计令牌中文字色在对应背景上的对比度，输出 WCAG 等级。
// 正文要求 ≥ 4.5:1 (AA)，大字号 ≥ 3:1。

const tokens = readFileSync(new URL('../src/styles/tokens.css', import.meta.url), 'utf8');

function resolve(name, seen = new Set()) {
  if (seen.has(name)) return null;
  seen.add(name);
  const re = new RegExp(`--${name}:\\s*([^;]+);`);
  const m = tokens.match(re);
  if (!m) return null;
  const value = m[1].trim();
  const ref = value.match(/^var\(--([\w-]+)\)$/);
  if (ref) return resolve(ref[1], seen);
  const hex = value.match(/^#([0-9a-fA-F]{6})$/);
  return hex ? '#' + hex[1] : null;
}

function luminance(hex) {
  const channel = c => {
    const v = parseInt(c, 16) / 255;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  };
  const r = channel(hex.slice(1, 3));
  const g = channel(hex.slice(3, 5));
  const b = channel(hex.slice(5, 7));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function ratio(fg, bg) {
  const a = luminance(fg);
  const b = luminance(bg);
  const [hi, lo] = a > b ? [a, b] : [b, a];
  return (hi + 0.05) / (lo + 0.05);
}

const backgrounds = [
  ['白卡片 n-0', 'n-0'],
  ['画布 bg-app', null, '#f1f4ef'],
  ['浅底 n-50', 'n-50'],
  ['品牌浅底 brand-50', 'brand-50'],
  ['深色焦点块 brand-800', 'brand-800']
];

const foregrounds = [
  ['正文 text-primary', 'text-primary'],
  ['次要 text-secondary', 'text-secondary'],
  ['三级 text-tertiary', 'text-tertiary'],
  ['占位 text-placeholder', 'text-placeholder'],
  ['品牌文字 text-brand', 'text-brand'],
  ['焦点块文字 focus-block-text', 'focus-block-text']
];

console.log('WCAG 对比度检查（AA 正文字号需 ≥ 4.5:1）\n');
for (const [label, tokenName, fixed] of backgrounds) {
  const bg = fixed ?? resolve(tokenName);
  if (!bg) { console.log(`跳过 ${label}：无法解析`); continue; }
  console.log(`${label}  ${bg}`);
  for (const [name, fgToken] of foregrounds) {
    const fg = resolve(fgToken);
    if (!fg) continue;
    const r = ratio(fg, bg);
    const flag = r >= 4.5 ? 'AA ' : r >= 3 ? 'AA-large' : '未达标';
    // 深色底上只有浅色文字有参考意义
    if (bg === resolve('brand-800') && !['focus-block-text', 'text-inverse'].includes(fgToken)) continue;
    if (bg !== resolve('brand-800') && fgToken === 'focus-block-text') continue;
    console.log(`  ${name.padEnd(28)} ${fg}  ${r.toFixed(2).padStart(5)}:1  ${flag}`);
  }
  console.log('');
}
