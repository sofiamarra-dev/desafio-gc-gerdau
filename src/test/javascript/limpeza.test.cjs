const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../main/resources/static');
const html = fs.readFileSync(path.join(root,'limpeza.html'),'utf8');
const script = fs.readFileSync(path.join(root,'limpeza.js'),'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
class Element {
  constructor(tag) { this.tag = tag; this.hidden = false; this.disabled = false; this.textContent = ''; this.children = []; this.events = {}; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  addEventListener(name,handler) { this.events[name] = handler; }
  async click() { if (!this.disabled) await this.events.click?.(); await flush(); }
}
const item = (id,description) => ({catalog_id:id,codigo_servico:id,descricao_item:description,codigo_fornecedor:'12',unidade_medida:'H'});
const current = item('new','PINTOR HE 50%');
const existing = {...item('old','SV PINTOR HE 50%'),similaridade_semantica:.91,semantic_exact:false};
const detail = (changes = {}) => ({id:'draft',status:'DRAFT',total:2,pending:1,verified:1,replaced:0,skip_verified:true,created_at:'2026-10-29T12:00:00Z',current,results:[existing],analysis_ready:true,automatic:null,...changes});
async function setup(handle, confirmAnswer = true) {
  const elements = {};
  for (const match of html.matchAll(/<([\w-]+)[^>]*\bid="([^"]+)"[^>]*>/g)) {
    const element = elements[match[2]] = new Element(match[1]);
    element.hidden = /\bhidden\b/.test(match[0]); element.checked = /\bchecked\b/.test(match[0]);
  }
  const calls = [], confirmations = [];
  const fetch = async (url,options = {}) => {
    const body = options.body ? JSON.parse(options.body) : undefined;
    calls.push({url,body});
    const response = await handle(url,body);
    return response?.httpStatus ? {ok:false,status:response.httpStatus,json:async () => ({error:response.error})} : {ok:true,status:200,json:async () => response};
  };
  const document = {getElementById:id => { assert.ok(elements[id],id); return elements[id]; },createElement:tag => new Element(tag)};
  vm.runInNewContext(script,{document,fetch,setTimeout,confirm:message => {confirmations.push(message);return confirmAnswer;}});
  await flush();
  return {elements,calls,confirmations};
}
function buttons(node) { return [node,...node.children.flatMap(child => buttons(child))].filter(child => child.tag === 'button'); }
function allText(node) { return [node.textContent,...node.children.map(allText)].join(' '); }

test('resumes saved manual analysis and sends the current identifier with the keep-current decision', async () => {
  let after = false;
  const state = detail();
  const {elements:e,calls} = await setup((url,body) => {
    if (url === '/api/cleanups') return [state];
    if (url === '/api/cleanups/draft') return state;
    if (url.endsWith('/decision')) { after = true; return detail({pending:0,verified:1,replaced:1,current:null,analysis_ready:false,results:[]}); }
    throw new Error(`Unexpected ${url}`);
  });
  await e['cleanup-list'].children[0].children[0].click();
  assert.equal(e['cleanup-review'].hidden,false);
  assert.equal(e['current-description'].textContent,current.descricao_item);
  const choices = buttons(e['cleanup-candidates']);
  assert.equal(choices.length,2);
  await choices[1].click();
  assert.ok(after);
  assert.deepEqual(calls.find(call => call.url.endsWith('/decision')).body,{action:'KEEP_CURRENT',candidate_id:'old',current_id:'new'});
  assert.equal(e['cleanup-summary'].hidden,false);
  assert.equal(e['apply-cleanup'].disabled,false);
  assert.equal(calls.filter(call => call.url.endsWith('/apply')).length,0);
});

test('creates with the optional filter and loops over automatic decisions without applying the base', async () => {
  let next = 0;
  const {elements:e,calls,confirmations} = await setup((url,body) => {
    if (url === '/api/cleanups' && !body) return [];
    if (url === '/api/cleanups') return detail({pending:2,verified:0,analysis_ready:false});
    if (url.endsWith('/next')) {
      next++;
      return detail({pending:next === 1 ? 1 : 0,verified:1,replaced:next === 1 ? 0 : 1,analysis_ready:false,current:null,automatic:{item:next === 1 ? existing : current,kept:existing}});
    }
    throw new Error(`Unexpected ${url}`);
  });
  assert.equal(e['skip-verified'].checked,true);
  e['skip-verified'].checked = false;
  await e['start-cleanup'].click();
  assert.deepEqual(calls.find(call => call.url === '/api/cleanups' && call.body).body,{skip_verified:false});
  assert.equal(next,2);
  assert.equal(e['automatic-log'].children.length,2);
  assert.match(allText(e['automatic-log']),/100% pelos embeddings/);
  assert.equal(e['cleanup-summary'].hidden,false);
  assert.equal(confirmations.length,0);
  assert.equal(calls.filter(call => call.url.endsWith('/apply')).length,0);
});

test('contains and near-perfect rounded scores stay manual and descriptions are rendered as text', async () => {
  const unsafe = '<img src=x onerror=alert(1)> PINTOR';
  const state = detail({current:item('new',unsafe),results:[{...existing,descricao_item:unsafe,similaridade_semantica:.99999999,correspondencia_palavras:true,correspondencia_exata:true}]});
  const {elements:e,calls} = await setup(url => url === '/api/cleanups' ? [state] : state);
  await e['cleanup-list'].children[0].children[0].click();
  assert.equal(e['current-description'].textContent,unsafe);
  assert.match(allText(e['cleanup-candidates']),/99,9999%/);
  assert.doesNotMatch(allText(e['cleanup-candidates']),/100%/);
  assert.equal(e['cleanup-review'].hidden,false);
  assert.equal(calls.filter(call => call.body).length,0);
  assert.doesNotMatch(script,/innerHTML/);
});

test('applying requires confirmation and sends confirmed=true only after acceptance', async () => {
  const complete = detail({pending:0,replaced:1,current:null,analysis_ready:false});
  let applied = false;
  const {elements:e,calls,confirmations} = await setup(url => {
    if (url === '/api/cleanups') return [complete];
    if (url.endsWith('/apply')) { applied = true; return {...complete,status:'APPLIED'}; }
    return complete;
  });
  await e['cleanup-list'].children[0].children[0].click();
  assert.equal(applied,false);
  await e['apply-cleanup'].click();
  assert.ok(applied);
  assert.equal(confirmations.length,1);
  assert.deepEqual(calls.find(call => call.url.endsWith('/apply')).body,{confirmed:true});
  assert.equal(e['apply-cleanup'].hidden,true);
  assert.equal(e['cleanup-status'].textContent,'Aplicada à base');
});

test('declining application leaves the base untouched', async () => {
  const complete = detail({pending:0,current:null,analysis_ready:false});
  const {elements:e,calls} = await setup(url => url === '/api/cleanups' ? [complete] : complete,false);
  await e['cleanup-list'].children[0].children[0].click();
  await e['apply-cleanup'].click();
  assert.equal(calls.filter(call => call.url.endsWith('/apply')).length,0);
});

test('a stale-source rejection keeps the copy visible and reports the server error', async () => {
  const complete = detail({pending:0,current:null,analysis_ready:false});
  const {elements:e} = await setup(url => url === '/api/cleanups' ? [complete] : url.endsWith('/apply') ? {httpStatus:409,error:'O catálogo mudou. Inicie uma nova cópia.'} : complete);
  await e['cleanup-list'].children[0].children[0].click();
  await e['apply-cleanup'].click();
  assert.match(e['cleanup-notice'].textContent,/catálogo mudou/);
  assert.equal(e['cleanup-status'].textContent,'Aguardando confirmação');
  assert.equal(e['apply-cleanup'].hidden,false);
  assert.equal(e['apply-cleanup'].disabled,false);
});

test('pause waits for the in-flight record and prevents another comparison', async () => {
  let release;
  const comparison = new Promise(resolve => { release = resolve; });
  const initial = detail({analysis_ready:false,pending:2,verified:0,results:[]});
  const {elements:e,calls} = await setup(url => url === '/api/cleanups' ? [initial] : url.endsWith('/next') ? comparison : initial);
  await e['cleanup-list'].children[0].children[0].click();
  const running = e['continue-cleanup'].click();
  await flush();
  assert.equal(e['pause-cleanup'].hidden,false);
  await e['pause-cleanup'].click();
  release(detail({analysis_ready:false,automatic:{item:existing,kept:existing}}));
  await running;
  assert.equal(calls.filter(call => call.url.endsWith('/next')).length,1);
  assert.match(e['cleanup-notice'].textContent,/pausada/);
  assert.equal(e['continue-cleanup'].disabled,false);
});

test('a failed comparison leaves the record pending and lets the user retry', async () => {
  let fail = true;
  const initial = detail({analysis_ready:false,results:[]});
  const {elements:e,calls} = await setup(url => {
    if (url === '/api/cleanups') return [initial];
    if (url.endsWith('/next')) {
      if (fail) return {httpStatus:503,error:'Python indisponível.'};
      return detail();
    }
    return initial;
  });
  await e['cleanup-list'].children[0].children[0].click();
  await e['continue-cleanup'].click();
  assert.match(e['cleanup-notice'].textContent,/Python indisponível/);
  assert.equal(e['count-pending'].textContent,'1');
  assert.equal(e['continue-cleanup'].disabled,false);
  assert.equal(e['apply-cleanup'].disabled,true);
  fail = false;
  await e['continue-cleanup'].click();
  assert.equal(e['cleanup-review'].hidden,false);
  assert.equal(calls.filter(call => call.url.endsWith('/decision')).length,0);
});
