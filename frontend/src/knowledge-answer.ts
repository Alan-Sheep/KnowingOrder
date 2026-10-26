/** 私有知识回答契约：纯文本、Long字符串和溯源坐标在展示前校验。 */
export interface Evidence {
  chunkId: string; documentId: string; versionId: string; versionNo: number; title: string;
  chunkIndex: number; content: string; startOffset: number; endOffset: number; startLine: number; endLine: number;
}
export interface Citation { sourceId: number; evidence: Evidence; quote: string }
export interface Paragraph { text: string; citations: Citation[] }
export interface AnswerPart { id: number; question: string; status: string; paragraphs: Paragraph[]; gaps: string[] }
export interface KnowledgeAnswer { mode: string; status: string; message: string; parts: AnswerPart[]; evidence: Evidence[]; elapsedMs: number }
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const id = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n;
const integer = (value: unknown, min = 0): value is number => Number.isSafeInteger(value) && Number(value) >= min;
const text = (value: unknown, max: number): value is string => typeof value === 'string' && !!value.trim() && value.length <= max;
const invalid = (): never => { throw new Error('回答或引用数据格式不正确，请核对接口版本。'); };
const statuses = ['ANSWERED', 'PARTIAL', 'NO_EVIDENCE', 'CONFLICT', 'EVIDENCE_ONLY', 'MODEL_UNAVAILABLE',
  'OUT_OF_SCOPE', 'SCOPE_UNKNOWN', 'SCOPE_UNAVAILABLE'];

export function evidenceView(value: unknown): Evidence {
  if (!object(value) || !id(value.chunkId) || !id(value.documentId) || !id(value.versionId)
    || !integer(value.versionNo, 1) || !text(value.title, 255) || !integer(value.chunkIndex)
    || !text(value.content, 1000) || !integer(value.startOffset) || !integer(value.endOffset, 1)
    || value.endOffset - value.startOffset !== value.content.length || !integer(value.startLine, 1)
    || !integer(value.endLine, value.startLine)) return invalid();
  return { chunkId: value.chunkId, documentId: value.documentId, versionId: value.versionId,
    versionNo: value.versionNo, title: value.title, chunkIndex: value.chunkIndex, content: value.content,
    startOffset: value.startOffset, endOffset: value.endOffset, startLine: value.startLine, endLine: value.endLine };
}

/** 部分校验在单空间与跨空间回答间完全一致，抽出的evidence按sourceId-1对位。 */
function partsView(value: unknown, evidence: Evidence[]): AnswerPart[] {
  if (!Array.isArray(value)) return invalid();
  return value.map((part, index) => {
    if (!object(part) || part.id !== index + 1 || !text(part.question, 1200)
      || !statuses.includes(String(part.status))
      || !Array.isArray(part.paragraphs) || part.paragraphs.length > 3
      || !Array.isArray(part.gaps) || part.gaps.length > 3 || !part.gaps.every(gap => text(gap, 200))) return invalid();
    const paragraphs: Paragraph[] = part.paragraphs.map(paragraph => {
      if (!object(paragraph) || !text(paragraph.text, 1300) || !Array.isArray(paragraph.citations)
        || paragraph.citations.length < 1 || paragraph.citations.length > 3) return invalid();
      // 允许同段引用同一出处的多条原句（后端已按此契约放行）。
      const citations: Citation[] = paragraph.citations.map(citation => {
        if (!object(citation) || !integer(citation.sourceId, 1) || citation.sourceId > evidence.length
          || !text(citation.quote, 500)) return invalid();
        const source = evidence[citation.sourceId - 1]!;
        if (!source.content.includes(citation.quote) || JSON.stringify(evidenceView(citation.evidence)) !== JSON.stringify(source)) return invalid();
        return { sourceId: citation.sourceId, quote: citation.quote, evidence: source };
      });
      return { text: paragraph.text, citations };
    });
    const generated = ['ANSWERED', 'PARTIAL', 'CONFLICT'].includes(String(part.status));
    if (generated !== (paragraphs.length > 0) || (part.status === 'ANSWERED') !== (part.gaps.length === 0)) return invalid();
    return { id: part.id as number, question: part.question, status: String(part.status), paragraphs, gaps: part.gaps as string[] };
  });
}

export function answerView(value: unknown): KnowledgeAnswer {
  if (!object(value) || !['LOCAL_EVIDENCE', 'SYNTHETIC_MODEL'].includes(String(value.mode))
    || !statuses.includes(String(value.status))
    || !text(value.message, 600) || !integer(value.elapsedMs) || !Array.isArray(value.evidence) || value.evidence.length > 6
    || !Array.isArray(value.parts) || value.parts.length < 1 || value.parts.length > 3) return invalid();
  const evidence = value.evidence.map(evidenceView);
  if (new Set(evidence.map(item => item.chunkId)).size !== evidence.length) return invalid();
  const parts = partsView(value.parts, evidence);
  if (value.mode === 'LOCAL_EVIDENCE' && (parts.some(part => part.paragraphs.length)
    || !['EVIDENCE_ONLY', 'NO_EVIDENCE', 'SCOPE_UNAVAILABLE'].includes(String(value.status)))) return invalid();
  return { mode: String(value.mode), status: String(value.status), message: value.message, parts, evidence, elapsedMs: value.elapsedMs };
}

/** 跨空间回答：证据额外携带来源空间，前端按spaceId取回证据详情。 */
export interface SpaceEvidence { spaceId: string; spaceName: string; evidence: Evidence }
export interface CrossAnswer { mode: string; status: string; message: string; parts: AnswerPart[]; evidence: SpaceEvidence[]; elapsedMs: number }

function spaceEvidenceView(value: unknown): SpaceEvidence {
  if (!object(value) || !id(value.spaceId) || !text(value.spaceName, 255)) return invalid();
  return { spaceId: value.spaceId, spaceName: value.spaceName, evidence: evidenceView(value.evidence) };
}

export function crossAnswerView(value: unknown): CrossAnswer {
  if (!object(value) || !['LOCAL_EVIDENCE', 'SYNTHETIC_MODEL'].includes(String(value.mode))
    || !statuses.includes(String(value.status))
    // 跨空间合并最多9条依据；单空间answerView仍保持6条，不能混用两种容量契约。
    || !text(value.message, 600) || !integer(value.elapsedMs) || !Array.isArray(value.evidence) || value.evidence.length > 9
    || !Array.isArray(value.parts) || value.parts.length < 1 || value.parts.length > 3) return invalid();
  const evidence = value.evidence.map(spaceEvidenceView);
  if (new Set(evidence.map(item => item.evidence.chunkId)).size !== evidence.length) return invalid();
  const parts = partsView(value.parts, evidence.map(item => item.evidence));
  if (value.mode === 'LOCAL_EVIDENCE' && (parts.some(part => part.paragraphs.length)
    || !['EVIDENCE_ONLY', 'NO_EVIDENCE', 'SCOPE_UNAVAILABLE'].includes(String(value.status)))) return invalid();
  return { mode: String(value.mode), status: String(value.status), message: value.message, parts, evidence, elapsedMs: value.elapsedMs };
}
