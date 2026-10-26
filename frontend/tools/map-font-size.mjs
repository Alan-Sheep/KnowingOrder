import { readFileSync, writeFileSync } from 'node:fs';

// 把硬编码的 px 字号映射到字号阶梯令牌。
// 小于 12px 的一律提升，避免中文在 1440 屏上不可读。

const MAP = [
  [8, 'var(--fs-2xs)'],
  [9, 'var(--fs-2xs)'],
  [10, 'var(--fs-2xs)'],
  [10.5, 'var(--fs-2xs)'],
  [11, 'var(--fs-2xs)'],
  [11.5, 'var(--fs-xs)'],
  [12, 'var(--fs-xs)'],
  [12.5, 'var(--fs-xs)'],
  [13, 'var(--fs-sm)'],
  [13.5, 'var(--fs-sm)'],
  [14, 'var(--fs-base)'],
  [14.5, 'var(--fs-base)'],
  [15, 'var(--fs-md)'],
  [16, 'var(--fs-lg)'],
  [17, 'var(--fs-lg)'],
  [18, 'var(--fs-xl)'],
  [19, 'var(--fs-xl)'],
  [20, 'var(--fs-xl)'],
  [21, 'var(--fs-2xl)'],
  [23, 'var(--fs-2xl)'],
  [24, 'var(--fs-2xl)'],
  [25, 'var(--fs-2xl)'],
  [26, 'var(--fs-2xl)'],
  [27, 'var(--fs-2xl)'],
  [28, 'var(--fs-3xl)'],
  [30, 'var(--fs-3xl)'],
  [32, 'var(--fs-3xl)'],
  [33, 'var(--fs-3xl)'],
  [34, 'var(--fs-4xl)'],
  [36, 'var(--fs-4xl)'],
  [38, 'var(--fs-4xl)'],
  [42, 'var(--fs-5xl)'],
  [44, 'var(--fs-5xl)'],
];

const lookup = new Map(MAP);

const targets = process.argv.slice(2).filter(a => !a.startsWith('--'));
const dryRun = process.argv.includes('--dry');

for (const file of targets) {
  const text = readFileSync(file, 'utf8');
  const before = new Map();
  // 只匹配 font-size: NNpx，不碰 clamp()、rem、em 与 line-height
  let out = text.replace(/(font-size:\s*)([\d.]+)px/g, (match, prefix, num) => {
    const value = Number(num);
    let token = lookup.get(value);
    if (!token) {
      // 落在档位之间时，取最近的较大档
      const candidate = MAP.find(([px]) => px >= value);
      token = candidate ? candidate[1] : 'var(--fs-4xl)';
    }
    before.set(num, (before.get(num) ?? 0) + 1);
    return prefix + token;
  });
  const changed = [...before.entries()].sort((a, b) => Number(a[0]) - Number(b[0]));
  console.log(`\n=== ${file} ===`);
  console.log('  ' + changed.map(([px, n]) => `${px}px×${n}`).join('  '));
  if (!dryRun) writeFileSync(file, out);
}
