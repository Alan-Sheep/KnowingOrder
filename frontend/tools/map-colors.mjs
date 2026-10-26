import { readFileSync, writeFileSync } from 'node:fs';

// 把组件样式块里的硬编码色值统一映射到设计令牌。
// 只处理 <style> 之后的内容，不动模板与脚本。

function hexToHsl(hex) {
  const r = parseInt(hex.slice(1, 3), 16) / 255;
  const g = parseInt(hex.slice(3, 5), 16) / 255;
  const b = parseInt(hex.slice(5, 7), 16) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  let h = 0;
  let s = 0;
  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    if (max === r) h = ((g - b) / d + (g < b ? 6 : 0));
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h /= 6;
  }
  return { h: h * 360, s: s * 100, l: l * 100 };
}

function mapHex(hex) {
  const { h, s, l } = hexToHsl(hex);
  const green = (h >= 90 && h <= 175) && s >= 8;
  const amber = h > 20 && h <= 62 && s >= 20;
  const red = (h <= 20 || h >= 340) && s >= 25;
  const blue = h > 175 && h < 250 && s >= 12;

  if (green) {
    if (l >= 96.5) return 'var(--n-25)';
    if (l >= 93) return 'var(--brand-50)';
    if (l >= 86) return 'var(--brand-100)';
    if (l >= 77) return 'var(--brand-200)';
    if (l >= 64) return 'var(--brand-300)';
    if (l >= 50) return 'var(--brand-400)';
    if (l >= 39) return 'var(--brand-500)';
    if (l >= 31) return 'var(--brand-600)';
    if (l >= 23) return 'var(--brand-700)';
    if (l >= 15) return 'var(--brand-800)';
    return 'var(--brand-900)';
  }
  if (amber) {
    if (l >= 94) return 'var(--amber-soft)';
    if (l >= 84) return 'var(--warning-border)';
    if (l >= 55) return 'var(--amber-400)';
    if (l >= 40) return 'var(--amber-500)';
    return 'var(--amber-600)';
  }
  if (red) {
    if (l >= 92) return 'var(--danger-soft)';
    if (l >= 82) return 'var(--danger-border)';
    if (l >= 50) return 'var(--rose-400)';
    if (l >= 35) return 'var(--danger)';
    return 'var(--danger-fg)';
  }
  if (blue) {
    if (l >= 93) return 'var(--blue-soft)';
    if (l >= 82) return 'var(--info-border)';
    if (l >= 55) return 'var(--blue-400)';
    if (l >= 38) return 'var(--blue-500)';
    return 'var(--blue-600)';
  }
  // 中性色
  // 注意：40%–56% 这一档在现有代码里几乎都是“次要文字”，
  // 若按表面色映射到 n-300 会造成 1.9:1 的不可读对比度，因此统一落到 n-500。
  if (l >= 98) return 'var(--n-0)';
  if (l >= 95) return 'var(--n-25)';
  if (l >= 90) return 'var(--n-50)';
  if (l >= 83) return 'var(--n-100)';
  if (l >= 76) return 'var(--n-150)';
  if (l >= 68) return 'var(--n-200)';
  if (l >= 56) return 'var(--n-250)';
  if (l >= 44) return 'var(--n-500)';
  if (l >= 34) return 'var(--n-600)';
  if (l >= 26) return 'var(--n-700)';
  return 'var(--n-800)';
}

const targets = process.argv.slice(2).filter(a => !a.startsWith('--'));
const dryRun = process.argv.includes('--dry');

for (const file of targets) {
  let text = readFileSync(file, 'utf8');
  const start = text.indexOf('<style');
  if (start < 0) { console.log(`${file}: 无样式块，跳过`); continue; }
  const head = text.slice(0, start);
  let style = text.slice(start);

  const counts = new Map();
  style = style.replace(/#([0-9a-fA-F]{6})\b/g, (match, hex) => {
    const token = mapHex('#' + hex);
    counts.set(match.toLowerCase(), token);
    return token;
  });
  // 3 位缩写
  style = style.replace(/#([0-9a-fA-F]{3})\b/g, (match, hex) => {
    const full = '#' + hex.split('').map(c => c + c).join('');
    const token = mapHex(full);
    counts.set(match.toLowerCase(), token);
    return token;
  });

  const uniq = new Map();
  for (const [hex, token] of counts) {
    const key = `${hex} -> ${token}`;
    uniq.set(key, (uniq.get(key) ?? 0) + 1);
  }
  console.log(`\n=== ${file} ===`);
  console.log(`替换 ${counts.size} 种色值`);
  if (process.env.VERBOSE) {
    for (const key of [...uniq.keys()].sort()) console.log('  ' + key);
  }
  if (!dryRun) {
    writeFileSync(file, head + style);
    console.log('  已写入');
  }
}
