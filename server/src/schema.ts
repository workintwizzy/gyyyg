import { z } from 'zod';
import { ApiError, invalidProvider } from './errors.js';

export const requestSchema = z.object({
  requestId: z.uuid(), context: z.record(z.string().max(80), z.unknown()),
  messages: z.array(z.object({role: z.enum(['user', 'assistant']), content: z.string().min(1).max(3000)}).strict()).max(8).default([]),
  prompt: z.string().trim().min(1).max(3000), fingerprint: z.string().min(1).max(128)
}).strict();
export type AiRequest = z.infer<typeof requestSchema>;
export const operationSchema = z.object({
  type: z.enum(['reorder', 'replace', 'reduceSets', 'weight', 'reps', 'rest', 'stage', 'schedule']),
  exerciseId: z.string().min(1).max(100), previous: z.string().max(500), value: z.string().min(1).max(500)
}).strict();
export const proposalCandidateSchema = z.object({
  summary: z.string().trim().min(1).max(500), reason: z.string().trim().min(1).max(1500),
  evidenceIds: z.array(z.string().max(100)).max(30), operations: z.array(operationSchema).min(1).max(12)
}).strict();
export const insightSchema = z.object({
  text: z.string().trim().min(1).max(12000), evidenceIds: z.array(z.string().max(100)).max(30),
  sourceIds: z.array(z.string().max(100)).max(20)
}).strict();
export type Insight = z.infer<typeof insightSchema>;
export type ProposalCandidate = z.infer<typeof proposalCandidateSchema>;
export function assertContext(request: AiRequest, maxBytes: number): void {
  if (Buffer.byteLength(JSON.stringify(request.context)) > maxBytes) throw new ApiError(413, 'CONTEXT_TOO_LARGE', 'Слишком большой контекст.');
  const inspect = (value: unknown, depth: number): void => {
    if (depth > 12) throw new ApiError(400, 'INVALID_CONTEXT', 'Слишком глубокий контекст.');
    if (Array.isArray(value)) { if (value.length > 200) throw new ApiError(400, 'INVALID_CONTEXT', 'Слишком много записей.'); value.forEach(v => inspect(v, depth + 1)); }
    else if (value && typeof value === 'object') for (const [key, v] of Object.entries(value)) {
      if (/secret|password|api.?key|access.?token|refresh.?token|contacts/i.test(key)) throw new ApiError(400, 'PRIVATE_FIELD', 'Контекст содержит запрещённое поле.');
      inspect(v, depth + 1);
    }
  };
  inspect(request.context, 0);
}
function ids(context: Record<string, unknown>, key: string): Set<string> {
  return new Set(Array.isArray(context[key]) ? context[key].filter((v): v is string => typeof v === 'string') : []);
}
export function parseInsight(raw: string, request: AiRequest): Insight {
  try {
    const result = insightSchema.parse(JSON.parse(raw));
    if (result.evidenceIds.some(id => !ids(request.context, 'evidenceIds').has(id)) || result.sourceIds.some(id => !ids(request.context, 'sourceIds').has(id))) throw invalidProvider();
    return result;
  } catch { throw invalidProvider(); }
}
export function parseProposal(raw: string, request: AiRequest): ProposalCandidate {
  try {
    const result = proposalCandidateSchema.parse(JSON.parse(raw));
    if (result.evidenceIds.some(id => !ids(request.context, 'evidenceIds').has(id))) throw invalidProvider();
    const allowed = ids(request.context, 'exerciseIds');
    if (!allowed.size || result.operations.some(op => !allowed.has(op.exerciseId) || (op.type==='replace'&&!allowed.has(op.value)))) throw invalidProvider();
    return result;
  } catch { throw invalidProvider(); }
}
