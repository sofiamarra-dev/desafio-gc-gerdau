const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.join(__dirname,'../../main/resources/static');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
class Element{
  constructor(){this.children=[];this.events={};this.hidden=false;this.disabled=false;this.textContent='';this.value='';this.checked=false;this.dataset={};}
  append(...children){this.children.push(...children);}replaceChildren(...children){this.children=children;}
  addEventListener(name,fn){this.events[name]=fn;}async click(){if(!this.disabled)await this.events.click?.({preventDefault(){}});await flush();}
}
const item={catalog_id:'selected',codigo_servico:'A',descricao_item:'PINTOR',grupo_servico:'Civil',cleanup_passes:'0',required_cleanup_passes:'2',verification_status:'PENDING'};
const candidate={...item,catalog_id:'candidate',descricao_item:'PINTOR INDUSTRIAL',contains:true,similarity:.2};
const summary={id:'session',version:7,dirty:false,acknowledged:true,automatic_removed:0,active:2,pending:2,removed:0};
async function setup(kind,handle,{readonly=false,confirm=true,acknowledge}={}){
  const detail=kind==='detail',html=fs.readFileSync(path.join(root,detail?'revisao.html':kind==='limpeza'?'limpeza.html':'dashboard.html'),'utf8'),elements={},calls=[],timers=[],windowEvents={};
  for(const match of html.matchAll(/<[^>]*\bid="([^"]+)"[^>]*>/g)){const el=elements[match[1]]=new Element();el.hidden=/\bhidden\b/.test(match[0]);el.checked=/\bchecked\b/.test(match[0]);}
  for(const [id,value]of Object.entries({'page-size':'20',sort:'codigo_servico',order:'asc'}))if(elements[id])elements[id].value=value;
  const location={search:detail?(readonly?'?analysis=index&selected=selected':'?selected=selected'):'',href:''};
  const walk=el=>[el,...el.children.flatMap(walk)];
  const document={body:{dataset:{browser:kind,readonly:String(readonly)}},getElementById:id=>{assert.ok(elements[id],id);return elements[id];},createElement:()=>new Element(),querySelectorAll:selector=>selector==='#code-rows button'?walk(elements['code-rows']).filter(el=>el.type==='button'):Object.values(elements).flatMap(walk).filter(el=>el.type==='button')};
  const fetch=async(url,options={})=>{const body=options.body?JSON.parse(options.body):undefined;calls.push({url,body});const data=await handle(url,body);return {ok:true,json:async()=>data};};
  let current=summary;
  const window={addEventListener:(name,fn)=>windowEvents[name]=fn,cleanupGuard:{
    update:data=>current={...current,...data},allowInternalNavigation(){},
    acknowledge:async data=>{calls.push({url:'guard:acknowledge',body:data});return acknowledge?acknowledge(data):({...data,acknowledged:true,version:data.version+1});},
    save:async()=>{const r=await fetch('/api/cleanups/session/save',{body:JSON.stringify({version:current.version})});const data=await r.json();await windowEvents['cleanup-saved']?.({detail:data});return data;}
  }};
  vm.runInNewContext(fs.readFileSync(path.join(root,detail?'review-detail.js':'catalog-browser.js'),'utf8'),{window,document,location,fetch,URLSearchParams,FormData:class{*[Symbol.iterator](){}},history:{replaceState(){}},setTimeout:fn=>{timers.push(fn);return timers.length;},clearTimeout(){},confirm:()=>confirm});
  await flush();await flush();return {elements,calls,location,timers,windowEvents};
}
test('dashboard requests each page from server and resets pagination when sorting changes',async()=>{
  const {elements:e,calls}=await setup('dashboard',url=>{const page=Number(new URL(url,'http://test').searchParams.get('page'));return {items:[item],total:45,page,size:20,pages:3};});
  assert.equal(e['code-rows'].children.length,1);await e.next.click();assert.match(calls.at(-1).url,/page=2/);assert.match(e['page-status'].textContent,/Página 2 de 3/);
  e.sort.value='descricao_item';await e.sort.events.change();await flush();assert.match(calls.at(-1).url,/page=1/);assert.match(calls.at(-1).url,/sort=descricao_item/);
});

test('page jumps move five or ten pages and clamp to first and last pages',async()=>{
  const {elements:e,calls}=await setup('dashboard',url=>{
    const page=Number(new URL(url,'http://test').searchParams.get('page'));
    return {items:[item],total:460,page,size:20,pages:23};
  });
  const lastPage=()=>Number(new URL(calls.at(-1).url,'http://test').searchParams.get('page'));
  assert.equal(e['back-five'].disabled,true);assert.equal(e['back-ten'].disabled,true);
  await e['forward-five'].click();assert.equal(lastPage(),6);
  await e['forward-ten'].click();assert.equal(lastPage(),16);
  await e['forward-ten'].click();assert.equal(lastPage(),23);
  assert.equal(e['forward-five'].disabled,true);assert.equal(e['forward-ten'].disabled,true);
  await e['back-five'].click();assert.equal(lastPage(),18);
  await e['back-ten'].click();assert.equal(lastPage(),8);
  await e['back-ten'].click();assert.equal(lastPage(),1);
  assert.equal(e['back-ten'].disabled,true);
  await e['forward-fifty'].click();assert.equal(lastPage(),23);
  await e['back-fifty'].click();assert.equal(lastPage(),1);
});
test('cleanup opens automatically, announces duplicate removal and does not save without an explicit click',async()=>{
  let pages=0;
  const {elements:e,calls,timers,location}=await setup('limpeza',(url,body)=>{
    if(url.endsWith('/status'))return {...summary,acknowledged:false,dirty:true,automatic_removed:3};
    if(url.endsWith('/open'))return {...summary,acknowledged:false,dirty:true,automatic_removed:3};
    if(url.includes('/session?')){pages++;return pages===1?{...summary,dirty:true,analysis_status:{status:'RUNNING',progress:64}}:{...summary,dirty:true,items:[item],page:1,pages:1,catalog_total:1,analysis_status:{status:'READY'}};}
    throw Error(url);
  });
  assert.equal(calls.filter(call=>call.url.endsWith('/open')).length,1);assert.equal(calls.find(call=>call.url==='guard:acknowledge').body.automatic_removed,3);
  assert.match(e['browser-message'].textContent,/segundo plano/);await timers.shift()();await flush();assert.equal(e['code-rows'].children.length,1);
  await e['code-rows'].children[0].children.at(-1).children[0].click();assert.equal(location.href,'revisao.html?selected=selected');
  assert.equal(calls.filter(call=>/\/decision|\/save$/.test(call.url)).length,0);assert.equal(e['save-cleanup'].disabled,false);
});
test('save button is the explicit persistence action and uses the latest session version',async()=>{
  let saved=false;
  const {elements:e,calls}=await setup('limpeza',(url,body)=>{
    if(url.endsWith('/save')){saved=true;return {...summary,dirty:false,version:8};}
    if(url.endsWith('/status')||url.endsWith('/open'))return {...summary,dirty:!saved};
    return {...summary,dirty:!saved,items:[item],page:1,pages:1,catalog_total:1,analysis_status:{status:'READY'}};
  });
  assert.equal(saved,false);await e['save-cleanup'].click();assert.equal(saved,true);
  assert.equal(calls.find(call=>call.url.endsWith('/save')).body.version,7);assert.equal(e['save-cleanup'].disabled,true);
});
test('manual review remains blocked until the duplicate summary is acknowledged',async()=>{
  let resolveAck;
  const pending=new Promise(resolve=>resolveAck=resolve);
  const {elements:e,calls}=await setup('limpeza',url=>url.includes('/session?')?{...summary,items:[item],page:1,pages:1,analysis_status:{status:'READY'}}:{...summary,acknowledged:false},{acknowledge:()=>pending});
  assert.equal(e['code-rows'].children.length,0);assert.equal(calls.filter(call=>call.url.includes('/session?')).length,0);
  resolveAck({...summary,acknowledged:true});await flush();await flush();assert.equal(e['code-rows'].children.length,1);
});
test('keeping the similar code stages only the selected pair and optimistic version',async()=>{
  const {elements:e,calls,location}=await setup('detail',(url,body)=>url.endsWith('/status')?summary:body?{...summary,dirty:true,version:8}:{...summary,selected:item,results:[candidate]});
  await e['similar-codes'].children[0].children.at(-1).children[1].click();
  const decision=calls.find(call=>call.url.endsWith('/decision'));assert.deepEqual(JSON.parse(JSON.stringify(decision.body)),{selected:'selected',candidate:'candidate',action:'KEEP_SIMILAR',version:7});
  assert.equal(location.href,'limpeza.html');assert.equal(calls.filter(call=>call.url.endsWith('/save')).length,0);
});
test('keeping initial without exclusion requests verification increase without saving automatically',async()=>{
  const {elements:e,calls}=await setup('detail',(url,body)=>url.endsWith('/status')?summary:body?{...summary,dirty:true,version:8}:{...summary,selected:{...item,cleanup_passes:'1',verification_pending:true},results:[candidate]});
  assert.match(e['selected-context'].textContent,/1\/2.*pendente de salvar/);await e['keep-code'].click();
  assert.equal(calls.find(call=>call.url.endsWith('/decision')).body.action,'KEEP');assert.equal(calls.filter(call=>call.url.endsWith('/save')).length,0);
});
test('declining removal never sends a mutation',async()=>{
  const {elements:e,calls}=await setup('detail',url=>url.endsWith('/status')?summary:{...summary,selected:item,results:[candidate]},{confirm:false});
  await e['similar-codes'].children[0].children.at(-1).children[0].click();assert.equal(calls.filter(call=>call.body).length,0);
});
test('dashboard detail remains read-only for normal accounts',async()=>{
  const {elements:e,calls}=await setup('detail',()=>({selected:item,results:[candidate]}),{readonly:true});assert.equal(e['keep-code'].hidden,true);assert.equal(e['similar-codes'].children[0].children.length,3);assert.match(calls[0].url,/\/api\/catalog\/dashboard\/index\/items\/selected/);
});

test('keeping a code directly from the list stages KEEP without saving or excluding a similar code',async()=>{
  let kept=false;
  const {elements:e,calls}=await setup('limpeza',(url,body)=>{
    if(url.endsWith('/decision')){kept=true;return {...summary,dirty:true,version:8};}
    const state={...summary,dirty:kept,version:kept?8:7};
    if(url.endsWith('/status')||url.endsWith('/open'))return state;
    return {...state,items:[{...item,cleanup_passes:kept?'1':'0',verification_status:kept?'PARTIAL':'PENDING',verification_pending:kept}],page:1,pages:1,catalog_total:1,analysis_status:{status:'READY'}};
  });
  assert.equal(e.stats.children.length,2);
  const actions=e['code-rows'].children[0].children.at(-1);
  assert.equal(actions.children[0].textContent,'Revisar 20 semelhantes');
  assert.equal(actions.children[1].textContent,'Manter código');
  await actions.children[1].click();
  assert.deepEqual(JSON.parse(JSON.stringify(calls.find(c=>c.url.endsWith('/decision')).body)),{selected:'selected',candidate:null,action:'KEEP',version:7});
  assert.equal(calls.some(c=>c.url.endsWith('/save')),false);
  assert.match(e['code-rows'].children[0].children[3].textContent,/1\/2.*pendente de salvar/);
});

test('comparison heading identifies the selected code group',async()=>{
  const {elements:e}=await setup('detail',url=>url.endsWith('/status')?summary:{...summary,selected:item,results:[candidate]});
  assert.match(e['selected-context'].textContent,/Grupo Civil/);
});

test('page jump labels are compact while accessible names retain their meaning',()=>{
  for(const name of ['dashboard.html','limpeza.html']){
    const html=fs.readFileSync(path.join(root,name),'utf8');
    for(const button of html.matchAll(/<button[^>]*id="(?:back|forward)-(?:five|ten|fifty)"[^>]*>(.*?)<\/button>/g))assert.doesNotMatch(button[1],/páginas/);
    assert.match(html,/aria-label="Avançar 50 páginas"/);
  }
});

test('returning from browser cache removes outdated rows without saving the database',async()=>{
  let removed=false;
  const {elements:e,calls,windowEvents}=await setup('limpeza',url=>{
    const state={...summary,dirty:removed,automatic_removed:removed?1:0};
    if(url.endsWith('/status')||url.endsWith('/open'))return state;
    return {...state,items:removed?[item]:[item,candidate],page:1,pages:1,catalog_total:removed?1:2,analysis_status:{status:'READY'}};
  });
  assert.equal(e['code-rows'].children.length,2);removed=true;
  windowEvents.pageshow({persisted:true});
  assert.equal(e['code-rows'].children.length,0,'Cache antigo deve sumir imediatamente');
  await flush();await flush();
  assert.equal(e['code-rows'].children.length,1);
  assert.equal(calls.some(call=>call.url.endsWith('/save')),false);
});
