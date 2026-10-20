import { Agent } from '@earendil-works/pi-agent-core';
import { Type, AssistantMessageEventStream, getCurrentSystemPrompt, getCurrentTools } from '@earendil-works/pi-ai';
import { loadSkillsFromDir, formatSkillsForPrompt } from '../vendor/pi/core/skills';
import { files, setFiles, readFileSync } from './skill-fs';

// The host owns HTTPS, credentials, database permissions and durable storage.
// Pi owns the agent loop, tool validation/execution, events and cancellation.
type Host = (method: string, args: any) => Promise<any>;
const emptyUsage = () => ({ input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } });
const domain = Type.Union(['TRAINING', 'FINANCE', 'LEARNING', 'SLEEP', 'PROJECT', 'OTHER'].map(v => Type.Literal(v)));
const title = Type.String({ minLength: 1, maxLength: 80 });
const date = Type.String({ pattern: '^\\d{4}-\\d{2}-\\d{2}$' });
const record = Type.Object({ domain, title, date, minutes: Type.Optional(Type.Integer({ minimum: 0, maximum: 1440 })),
  amountFen: Type.Optional(Type.Integer({ minimum: 0, maximum: 999999999999 })),
  moneyKind: Type.Optional(Type.Union(['EXPENSE','INCOME','TRANSFER','REFUND'].map(v => Type.Literal(v)))),
  category: Type.Optional(Type.String({ maxLength: 40 })), count: Type.Optional(Type.Integer({minimum:0,maximum:100000})),
  countUnit: Type.Optional(Type.Union(['次','组','个','页','题'].map(v=>Type.Literal(v)))),
  planId: Type.Optional(Type.String({maxLength:128})), note: Type.Optional(Type.String({maxLength:2000})), completesPlan: Type.Optional(Type.Boolean()) }, { additionalProperties: false });
const plan = Type.Object({ domain, title, date, time: Type.String({ maxLength: 5 }),
  expectedMinutes: Type.Integer({ minimum: 0, maximum: 1440 }), ref: Type.Optional(Type.String({maxLength:64})),
  parentId: Type.Optional(Type.String({maxLength:128})), priority: Type.Optional(Type.Integer({minimum:0,maximum:3})),
  deadline: Type.Optional(Type.String({maxLength:19})), project: Type.Optional(Type.String({maxLength:100})),
  reminderMinutes: Type.Optional(Type.Integer({minimum:0,maximum:10080})) }, { additionalProperties: false });
const task = Type.Object({...plan.properties,
  time:Type.Optional(plan.properties.time), expectedMinutes:Type.Optional(plan.properties.expectedMinutes)}, {additionalProperties:false});
const change = Type.Object({id:Type.String({minLength:1,maxLength:128}),
  title:Type.Optional(title), date:Type.Optional(date), time:Type.Optional(Type.String({maxLength:5})),
  expectedMinutes:Type.Optional(Type.Integer({minimum:0,maximum:1440})),
  priority:Type.Optional(Type.Integer({minimum:0,maximum:3})),deadline:Type.Optional(Type.String({maxLength:19})),
  project:Type.Optional(Type.String({maxLength:100})),
  status:Type.Optional(Type.Union(['TODO','IN_PROGRESS','SKIPPED','ARCHIVED'].map(v=>Type.Literal(v)))),
  reminderMinutes:Type.Optional(Type.Union([Type.Integer({minimum:0,maximum:10080}),Type.Null()]))}, {additionalProperties:false});

export const toolSchemas = [
  { name: 'read', description: 'Read a listed SKILL.md or a bundled skill reference. Paths are virtual, read-only.',
    parameters: Type.Object({ path: Type.String({ maxLength: 240 }) }, { additionalProperties: false }) },
  { name: 'search_records', description: 'Read actual local records within granted history/finance permissions. Never assume access.',
    parameters: Type.Object({ query: Type.Optional(Type.String({ maxLength: 100 })), domain: Type.Optional(domain),
      from: Type.Optional(date), to: Type.Optional(date) }, { additionalProperties: false }) },
  { name: 'list_plans', description: 'Read local plans and goals, within granted permissions.',
    parameters: Type.Object({ from: Type.Optional(date), to: Type.Optional(date),query:Type.Optional(Type.String({maxLength:100})) }, { additionalProperties: false }) },
  { name: 'memory_read', description: 'Read persistent preferences within the independent memory permission.',
    parameters: Type.Object({}, { additionalProperties: false }) },
  {name:'create_tasks',description:'Create tasks directly when the user explicitly asks to arrange them. Dates must be clear; omit unknown time/duration. Saves atomically and returns actual IDs. Exact duplicates are reused. Users can undo.',
    parameters:Type.Object({tasks:Type.Array(task,{minItems:1,maxItems:180})},{additionalProperties:false})},
  {name:'update_tasks',description:'Update only explicitly requested existing tasks by their real IDs from list_plans. Omitted fields stay unchanged. No deletions or invented completion records. Saves atomically with undo.',
    parameters:Type.Object({changes:Type.Array(change,{minItems:1,maxItems:180})},{additionalProperties:false})},
  { name: 'propose_draft', description: 'Stage records/plans for user review. This never saves actual records. Call once with the whole proposal.',
    parameters: Type.Object({ records: Type.Array(record, { maxItems: 20 }), plans: Type.Array(plan, { maxItems: 180 }),
      memories: Type.Optional(Type.Array(Type.Object({text:Type.String({minLength:1,maxLength:1000})},{additionalProperties:false}),{maxItems:20})) }, { additionalProperties: false }) }
];

function wireMessages(messages: any[]) {
  const result: any[] = [{ role: 'system', content: getCurrentSystemPrompt(messages) }];
  for (const m of messages) {
    if (m.role === 'system') continue;
    if (m.role === 'user') result.push({ role: 'user', content: m.content.map((c: any) => c.type === 'image'
      ? { type: 'image_url', image_url: { url: `data:${c.mimeType};base64,${c.data}` } }
      : { type: 'text', text: c.text }) });
    else if (m.role === 'assistant') {
      const calls = m.content.filter((c: any) => c.type === 'toolCall');
      result.push({ role: 'assistant', content: m.content.filter((c: any) => c.type === 'text').map((c: any) => c.text).join('\n'),
        ...(m.content.some((c:any)=>c.type==='thinking') ? {reasoning_content:m.content.filter((c:any)=>c.type==='thinking').map((c:any)=>c.thinking).join('\n')} : {}),
        ...(calls.length ? { tool_calls: calls.map((c: any) => ({ id: c.id, type: 'function', function: { name: c.name, arguments: JSON.stringify(c.arguments) } })) } : {}) });
    } else if (m.role === 'toolResult') result.push({ role: 'tool', tool_call_id: m.toolCallId,
      content: m.content.filter((c: any) => c.type === 'text').map((c: any) => c.text).join('\n') });
  }
  return result;
}

export async function run(input: any, host: Host, signal?: AbortSignal) {
  setFiles(input.skills || {});
  const skills = loadSkillsFromDir({ dir: '/skills', source: 'user' });
  let turns = 0, calls = 0;
  const trace: any[] = [];
  const model: any = { id: input.model, name: input.model, api: 'openai-completions', provider: 'boji',
    baseUrl: '', reasoning: false, input: ['text','image'], contextWindow: 32000, maxTokens: 4096, cost: emptyUsage().cost };
  const agent = new Agent({
    initialState: { model, systemPrompt: input.system + formatSkillsForPrompt(skills.skills),
      tools: toolSchemas.filter(spec => !input.allowedTools || input.allowedTools.includes(spec.name)).map(spec => ({ ...spec, label: spec.name, execute: async (_id: string, args: any) => {
        if (++calls > 24) throw Error('工具调用达到上限');
        let output: any;
        if (spec.name === 'read') {
          if (!args.path.startsWith('/skills/') || args.path.split('/').includes('..')) throw Error('路径不可访问');
          output = readFileSync(args.path);
        } else output = await host(spec.name, args);
        return { content: [{ type: 'text', text: typeof output === 'string' ? output : JSON.stringify(output) }], details: {} };
      } })) },
    toolExecution: 'sequential',
    streamFn: (_model, context, options) => {
      const stream = new AssistantMessageEventStream();
      void (async () => {
        const message: any = { role: 'assistant', content: [], api: model.api, provider: model.provider, model: model.id,
          usage: emptyUsage(), stopReason: 'stop', timestamp: Date.now() };
        try {
          if (options?.signal?.aborted) throw Error('已停止');
          if (++turns > 8) throw Error('本轮步骤达到上限，请缩小任务');
          const response = await host('model', { model: model.id, messages: wireMessages(context.messages),
            tools: getCurrentTools(context.messages).map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } })),
            stream: false, max_tokens: 8192, thinking: {type:input.thinking ? 'enabled' : 'disabled'} });
          if (options?.signal?.aborted) throw Error('已停止');
          const choice = response.choices?.[0];
          if (!choice?.message || choice.message.refusal) throw Error('模型未能完成本次请求');
          if (choice.message.content) message.content.push({ type: 'text', text: String(choice.message.content).slice(0, 16000) });
          if (typeof choice.message.reasoning_content === 'string') message.content.push({type:'thinking',thinking:choice.message.reasoning_content});
          for (const c of choice.message.tool_calls || []) {
            if (!c.id || c.type !== 'function' || typeof c.function?.arguments !== 'string') throw Error('工具调用格式无效');
            message.content.push({ type: 'toolCall', id: c.id, name: c.function.name, arguments: JSON.parse(c.function.arguments) });
          }
          message.stopReason = choice.finish_reason === 'length' ? 'length' : message.content.some((c: any) => c.type === 'toolCall') ? 'toolUse' : 'stop';
          const usage = response.usage;
          if (usage) Object.assign(message.usage, { input: usage.prompt_tokens || 0, output: usage.completion_tokens || 0, totalTokens: usage.total_tokens || 0 });
          stream.push({ type: 'done', reason: message.stopReason, message });
        } catch (e: any) {
          message.stopReason = options?.signal?.aborted ? 'aborted' : 'error';
          // Never echo a provider's raw response/errors to the transcript.
          message.errorMessage = e?.safeMessage || '请求未完成，请检查连接或缩小任务后重试。';
          stream.push({ type: 'error', reason: message.stopReason, error: message });
        } finally { stream.end(); }
      })();
      return stream;
    }
  });
  // Restore only complete turn groups, supplied by the native scoped session store.
  if (input.history?.length) agent.state.messages = [agent.state.messages[0], ...input.history.filter((m: any) => m.role !== 'system')];
  agent.subscribe(async event => {
    if (event.type === 'tool_execution_start' || event.type === 'tool_execution_end') {
      const item = { type: event.type, tool: event.toolName, ...(event.type === 'tool_execution_end' ? { error: event.isError } : {}) };
      trace.push(item);
      await host('status', item);
    }
  });
  const abort = () => agent.abort();
  signal?.addEventListener('abort', abort, { once: true });
  try {
    const content: any[] = [{ type: 'text', text: input.text }];
    if (input.image) content.push({ type: 'image', data: input.image, mimeType: input.mimeType || 'image/jpeg' });
    await agent.prompt({ role: 'user', content, timestamp: Date.now() });
    const last: any = agent.state.messages.filter(m => m.role === 'assistant').at(-1);
    const success = !!last && last.stopReason === 'stop';
    const text = success ? last.content.filter((c: any) => c.type === 'text').map((c: any) => c.text).join('\n') : last?.errorMessage || '处理未完成';
    // Images are transient. Persist text/tool transcript only; Android bounds total bytes/turns.
    const history = agent.state.messages.filter(m => m.role !== 'system').map((m: any) => ({ ...m, content: m.content.filter((c: any) => c.type !== 'image') }));
    return { text: text || '已整理，请核对草稿。', success, history, trace, diagnostics: skills.diagnostics };
  } finally { signal?.removeEventListener('abort', abort); }
}

const pending = new Map<number, { resolve: (value: any) => void; reject: (error: any) => void }>();
export function inspectSkill(text: string) {
  const previous = files;
  try {
    setFiles({ '/skills/imported/SKILL.md': text });
    const result = loadSkillsFromDir({ dir: '/skills', source: 'user' });
    if (!result.skills.length || result.diagnostics.length) return { valid: false, name: '' };
    return { valid: true, name: result.skills[0].name };
  } finally { setFiles(previous); }
}
let sequence = 0, controller: AbortController | undefined;
const native = (globalThis as any).BojiNative;
export function receive(id: number, response: any) {
  const item = pending.get(id); if (!item) return; pending.delete(id);
  if (response.ok) item.resolve(response.value);
  else item.reject(Object.assign(Error(response.error || '工具请求失败'), { safeMessage: response.error }));
}
export function cancel() { controller?.abort(); pending.forEach(p => p.reject(Error('已停止'))); pending.clear(); }
export async function start(input: any) {
  cancel(); controller = new AbortController();
  const host: Host = (method, args) => new Promise((resolve, reject) => {
    const id = ++sequence; pending.set(id, { resolve, reject });
    native.postMessage(JSON.stringify({ runId: input.runId, id, method, args }));
  });
  try { native.complete(JSON.stringify({ runId: input.runId, ...(await run(input, host, controller.signal)) })); }
  catch { native.complete(JSON.stringify({ runId: input.runId, success: false, text: '助手运行中断，请重试。' })); }
}
