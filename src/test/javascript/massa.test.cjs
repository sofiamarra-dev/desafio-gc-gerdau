const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticPath = path.resolve(__dirname, '../../main/resources/static');
const script = fs.readFileSync(path.join(staticPath, 'massa.js'), 'utf8');
const html = fs.readFileSync(path.join(staticPath, 'massa.html'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

class Element {
  constructor(tag = 'div') { this.tag = tag; this.hidden = false; this.disabled = false; this.textContent = ''; this.children = []; this.listeners = {}; this.dataset = {}; }
  replaceChildren(...children) { this.children = children; }
  append(...children) { this.children.push(...children); }
  setAttribute(name, value) { this[name] = value; }
  addEventListener(event, handler) { this.listeners[event] = handler; }
  async click() { if (!this.disabled) await this.listeners.click?.({preventDefault() {}}); await flush(); }
}
function explanation(original, activity, qualifiers, standardized = original, needs_review = false) {
  return {original, activity, qualifiers, standardized, needs_review, dictionary_version:'test:1'};
}
async function setup(descriptions, analyze) {
  const elements = {};
  for (const match of html.matchAll(/<([\w-]+)[^>]*\bid="([^"]+)"[^>]*>/g)) {
    const el = elements[match[2]] = new Element(match[1]);
    el.hidden = /\bhidden\b/.test(match[0]);
  }
  const rows = descriptions.map((description, i) => ({id:String(i+1), row_number:i+1, status:'PENDING', fields:{descricao_item:description}}));
  const calls = [], confirmations = [];
  const walk = el => [el, ...el.children.flatMap(walk)];
  const fetch = async (url, options = {}) => {
    const body = options.body ? JSON.parse(options.body) : undefined;
    calls.push({url, body});
    let data;
    if (url === '/api/catalog/batches') data = [{id:'b', filename:'Teste', total:rows.length, pending:rows.filter(r => r.status === 'PENDING').length}];
    else if (url.includes('/items?')) data = rows.filter(r => r.row_number > Number(new URL(url, 'http://test').searchParams.get('after')));
    else if (url === '/api/search') data = {results:[], query_parts:await analyze(body.query)};
    else if (url.endsWith('/decision')) { rows.find(r => url.includes(`/items/${r.id}/`)).status = 'CREATED'; data = {}; }
    else throw new Error(`Unexpected request ${url}`);
    return {ok:true, json:async () => data};
  };
  const document = {
    getElementById:id => { assert.ok(elements[id], `Missing element ${id}`); return elements[id]; },
    createElement:tag => new Element(tag),
    querySelectorAll:selector => {
      const match = selector.match(/^#([\w-]+) button$/);
      assert.ok(match, selector);
      return walk(elements[match[1]]).filter(e => e.tag === 'button');
    }
  };
  vm.runInNewContext(script, {document, fetch, confirm:message => {confirmations.push(message); return true;}, URL, FormData:class {}});
  await flush();
  await elements['batch-list'].children[0].children[0].click();
  return {elements, rows, calls, confirmations};
}

test('shows standardized description, activity and percentage qualifiers without changing original', async () => {
  const original = 'SV PINTOR HORA EXTRA 50%';
  const {elements:e, rows} = await setup([original], q => explanation(q,'PINTOR',['SV','HE 50%'],'SV PINTOR HE 50%'));
  assert.equal(e['request-description'].textContent, original);
  assert.equal(e['request-standardized'].textContent, 'SV PINTOR HE 50%');
  assert.equal(e['request-activity'].textContent, 'PINTOR');
  assert.deepEqual(e['request-qualifiers'].children.map(el => el.textContent), ['SV','HE 50%']);
  assert.equal(e['request-separation'].hidden, false);
  assert.equal(e['request-separation-warning'].hidden, true);
  assert.equal(e['create-request'].disabled, false);
  assert.equal(rows[0].fields.descricao_item, original);
});

test('warns on missing activity and clears preview after completion', async () => {
  const {elements:e, confirmations, calls, rows} = await setup(['HE 50%'], q => explanation(q,'',['HE 50%'],q,true));
  assert.equal(e['request-separation-warning'].hidden, false);
  await e['create-request'].click();
  assert.match(confirmations[0], /Nenhuma atividade principal/);
  assert.deepEqual(JSON.parse(JSON.stringify(calls.find(c => c.url.endsWith('/decision')).body)), {action:'CREATE'});
  assert.equal(rows[0].fields.descricao_item, 'HE 50%');
  assert.equal(e['request-separation'].hidden, true);
  assert.equal(e['request-separation-warning'].hidden, true);
  assert.equal(e['request-standardized'].textContent, '');
  assert.equal(e['create-request'].disabled, true);
});

test('does not show the previous line after a failed search and supports retry', async () => {
  let fail = true;
  const {elements:e} = await setup(['PINTOR HE', 'MOTOR'], q => {
    if (q === 'MOTOR' && fail) throw new Error('Python indisponível');
    return explanation(q, q === 'MOTOR' ? 'MOTOR' : 'PINTOR', q === 'MOTOR' ? [] : ['HE']);
  });
  await e['next-request'].click();
  assert.equal(e['request-description'].textContent, 'MOTOR');
  assert.equal(e['request-standardized'].textContent, '');
  assert.equal(e['request-qualifiers'].children.length, 0);
  assert.equal(e['request-separation'].hidden, true);
  assert.equal(e['create-request'].disabled, true);
  fail = false;
  await e['retry-request'].click();
  assert.equal(e['request-activity'].textContent, 'MOTOR');
  assert.equal(e['request-qualifiers'].children[0].textContent, 'Nenhum qualificador identificado');
  assert.equal(e['create-request'].disabled, false);
});

test('missing explanation in an older Python response prevents uninformed creation', async () => {
  const {elements:e, calls} = await setup(['PINTOR'], () => undefined);
  assert.equal(e['request-separation'].hidden, true);
  assert.match(e['request-separation-status'].textContent, /Reinicie o Python/);
  assert.equal(e['create-request'].disabled, true);
  await e['create-request'].click();
  assert.equal(calls.filter(c => c.url.endsWith('/decision')).length, 0);
});

test('refreshes the current line after changing the dictionary and renders descriptions as text', async () => {
  let changed = false;
  const original = '<img src=x> PINTOR HX';
  const {elements:e} = await setup([original], q => changed
    ? explanation(q,'<img src=x> PINTOR',['HE'],'<img src=x> PINTOR HE')
    : explanation(q,q,[]));
  assert.equal(e['request-activity'].textContent, original);
  changed = true;
  await e['retry-request'].click();
  assert.equal(e['request-description'].textContent, original);
  assert.equal(e['request-standardized'].textContent, '<img src=x> PINTOR HE');
  assert.equal(e['request-qualifiers'].children[0].textContent, 'HE');
});
