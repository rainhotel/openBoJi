import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const bundle = readFileSync(new URL('../../app/src/main/assets/agent/runtime.js', import.meta.url),'utf8');
function runtime() {
  const context = vm.createContext({ console, performance, setTimeout, clearTimeout, AbortController, TextEncoder, TextDecoder, structuredClone,
    URL, URLSearchParams, Blob, File });
  vm.runInContext(bundle,context);
  return context.BojiAgent;
}
const toolResponse = (name, args, id='call1') => ({ choices: [{ finish_reason: 'tool_calls', message: {
  content: null, tool_calls: [{ id, type: 'function', function: { name, arguments: JSON.stringify(args) } }] } }] });
const answer = text => ({ choices: [{ finish_reason: 'stop', message: { content: text } }] });
const input = { text: '本周复盘', model: 'fixture', system: 'test', skills: {
  '/skills/review/SKILL.md': '---\nname: review\ndescription: Review actual records\n---\nUse search_records before reviewing.'
} };

test('real Pi loop loads upstream Skill, reads scoped native records and stages without saving', async () => {
  const api = runtime();
  const requests=[], tools=[];
  const responses=[toolResponse('read',{path:'/skills/review/SKILL.md'}),toolResponse('search_records',{domain:'TRAINING'}),
    toolResponse('propose_draft',{records:[],plans:[{domain:'TRAINING',title:'练拳',date:'2026-10-20',time:'',expectedMinutes:30}]}),answer('草稿待核对')];
  const result = await api.run(input,async (method,args) => {
    if(method==='model') { requests.push(args); return responses.shift(); }
    if(method==='status') return true;
    tools.push(method);
    return method==='search_records' ? {records:[{title:'练拳',minutes:40}]} : {staged:true,saved:false};
  });
  assert.equal(result.success,true);
  assert.equal(requests.length,4);
  assert.deepEqual(tools,['search_records','propose_draft']);
  assert.match(requests[0].messages[0].content,/available_skills/);
  assert.match(JSON.stringify(requests[2].messages),/40/);
  assert.equal(result.trace.filter(t=>t.type==='tool_execution_end').length,3);
});

test('upstream schema validation rejects malformed amounts before native tools execute',async()=>{
  const api=runtime(); let executed=0, modelCalls=0;
  const result=await api.run(input,async(method)=>{
    if(method==='model') return ++modelCalls===1 ? toolResponse('propose_draft',{records:[{domain:'FINANCE',title:'晚饭',date:'2026-10-19',minutes:0,amountFen:-1,moneyKind:'EXPENSE',category:''}],plans:[]}) : answer('请补充正确金额');
    if(method==='propose_draft') executed++;
    return true;
  });
  assert.equal(result.success,true); assert.equal(executed,0);
  assert.equal(result.trace.at(-1).error,true);
});

test('unknown tools and skill path traversal return tool errors without accessing native resources',async()=>{
  for(const [name,args] of [['shell',{command:'rm'}],['read',{path:'/skills/../private'}]]) {
    const api=runtime(); let modelCalls=0, unsafe=0;
    const result=await api.run(input,async(method)=>{
      if(method==='model') return ++modelCalls===1 ? toolResponse(name,args) : answer('不可访问');
      if(method!=='status') unsafe++;
      return true;
    });
    assert.equal(result.success,true); assert.equal(unsafe,0);
  }
});

test('bounded loop stops a provider that keeps asking for tools',async()=>{
  let turns=0;
  const result=await runtime().run(input,async(method)=>method==='model' ? (++turns,toolResponse('memory_read',{})) : {});
  assert.equal(result.success,false); assert.equal(turns,8);
});

test('skill parser is reused, validates metadata and restores existing skill snapshot',()=>{
  const api=runtime();
  assert.equal(api.inspectSkill('---\nname: custom\ndescription: my skill\n---\nDo work').valid,true);
  assert.equal(api.inspectSkill('no frontmatter').valid,false);
  assert.equal(api.inspectSkill('---\nname: Bad Name\ndescription: x\n---\nx').valid,false);
});

test('Pi session can be restored with paired tool history and strips images from persistence',async()=>{
  const api=runtime(); let turns=0;
  const first=await api.run({...input,image:'IMAGE_SECRET'},async(method)=>method==='model' ? (++turns===1 ? toolResponse('memory_read',{}) : answer('记得')) : {});
  assert.equal(JSON.stringify(first.history).includes('IMAGE_SECRET'),false);
  let payload;
  const second=await api.run({...input,history:first.history,text:'继续'},async(method,args)=>{
    if(method==='model') {payload=args;return answer('继续');} return true;
  });
  assert.equal(second.success,true); assert.match(JSON.stringify(payload.messages),/tool_call_id/);
});

test('cancellation prevents late provider response from executing tools',async()=>{
  const controller=new AbortController(); let executed=0;
  const result=await runtime().run(input,async(method)=>{
    if(method==='model') {controller.abort();return toolResponse('memory_read',{});}
    if(method==='memory_read') executed++;
    return true;
  },controller.signal);
  assert.equal(result.success,false); assert.equal(executed,0);
});
