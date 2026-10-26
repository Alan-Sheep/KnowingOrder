import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { validateAnswer } from '../src/api.ts';

/** 只访问已有本机后端，不读取凭据；付费调用必须显式 --run，串行且失败不重试。 */
export function scoreCase(sample, answer) {
  const parts = answer?.answers ?? [];
  const text = parts.map(part => part.answer ?? '').join('\n').toLowerCase();
  const urls = parts.flatMap(part => part.sources.map(source => source.url));
  const hit = sample.expectSources.some(domain => urls.some(value => {
    try { const host = new URL(value).hostname.toLowerCase(); return host === domain || host.endsWith(`.${domain}`); }
    catch { return false; }
  }));
  return { id: sample.id, status: answer?.status ?? 'REQUEST_FAILED', requestId: answer?.requestId ?? '',
    elapsedMillis: answer?.elapsedMillis ?? 0, partStatuses: parts.map(part => part.status),
    sourceCase: sample.expectSources.length > 0, retrieved: urls.length > 0, expectedSourceHit: hit,
    keywordCount: sample.keywords.length, keywordHits: sample.keywords.filter(word => text.includes(word.toLowerCase())).length,
    shouldRefuse: sample.shouldRefuse,
    refusedByContract: parts.length > 0 && parts.every(part => ['NO_EVIDENCE', 'INSUFFICIENT_EVIDENCE', 'CLARIFICATION_NEEDED'].includes(part.status)) };
}

export function metrics(results) {
  const sources = results.filter(row => row.sourceCase);
  const refusals = results.filter(row => row.shouldRefuse);
  const ratio = (count, total) => total ? count / total : 0;
  return {
    retrievalOnSourceCases: ratio(sources.filter(row => row.retrieved).length, sources.length),
    sourceHitRate: ratio(sources.filter(row => row.expectedSourceHit).length, sources.length),
    keywordCoverage: ratio(results.reduce((sum, row) => sum + row.keywordHits, 0), results.reduce((sum, row) => sum + row.keywordCount, 0)),
    refusalByContract: ratio(refusals.filter(row => row.refusedByContract).length, refusals.length),
    requestFailures: results.filter(row => row.status === 'REQUEST_FAILED').length
  };
}

async function main() {
  if (!process.argv.includes('--run')) {
    console.log('只读准备完成。真实30题验收请显式添加 --run；调用本机8080已有后端，不读取密钥。');
    return;
  }
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const lines = await readFile(resolve(root, 'backend/src/test/resources/eval/web-chat-v1.jsonl'), 'utf8');
  const samples = lines.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
  if (samples.length !== 30) throw new Error('黄金集必须为30题，未开始调用');
  const results = [];
  const target = resolve(root, 'backend/target/eval/http-web-chat-v1.json');
  await mkdir(resolve(root, 'backend/target/eval'), { recursive: true });
  const save = async () => writeFile(target, JSON.stringify({ evaluatedAt: new Date().toISOString(),
    transport: 'HTTP_LOOPBACK', runtimePromptVersionsVerified: false, completedCases: results.length,
    limitations: '按已有黄金集阈值统计。来源指标含候选资料，不证明答案引用正确；关键词不是事实准确率。失败超时不计正确拒答。运行版本须另核对IDEA启动时间。',
    metrics: metrics(results), cases: results }, null, 2) + '\n', 'utf8');
  for (const sample of samples) {
    let answer;
    try {
      const response = await fetch('http://127.0.0.1:8080/api/chat', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: sample.question, clientSubmittedAt: new Date().toISOString(),
          timeZone: 'Asia/Shanghai', answerLanguage: 'zh-CN' }), signal: AbortSignal.timeout(100000)
      });
      const payload = await response.json();
      if (response.ok && payload.code === 0 && validateAnswer(payload.data)) answer = payload.data;
    } catch { /* 不打印供应商响应、原始异常或重复提交；本题按失败计入指标。 */ }
    const result = scoreCase(sample, answer);
    results.push(result);
    await save();
    console.log(`真实评测 ${results.length}/30：${result.id}，状态=${result.status}，子状态=${result.partStatuses.join(',')}，requestId=${result.requestId}`);
  }
  const summary = metrics(results);
  console.log(`真实评测指标：${JSON.stringify(summary)}`);
  console.log(`报告：${target}`);
  if (summary.sourceHitRate <= 0.75 || summary.keywordCoverage <= 0.70
      || summary.retrievalOnSourceCases <= 0.90 || summary.refusalByContract !== 1 || summary.requestFailures) {
    process.exitCode = 1;
    console.log('质量门槛未全部达到；保留原始分母与失败记录，不重试、不放宽断言。');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await main();
