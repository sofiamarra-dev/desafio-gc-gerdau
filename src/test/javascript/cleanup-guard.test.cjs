const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const script=fs.readFileSync(path.join(__dirname,'../../main/resources/static/cleanup-guard.js'),'utf8');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
class Element {
  constructor(){this.children=[];this.events={};this.textContent='';this.disabled=false;}
  append(...children){this.children.push(...children);}
  setAttribute(name,value){this[name]=value;}
  addEventListener(name,handler){this.events[name]=handler;}
  showModal(){this.open=true;}close(){this.open=false;}remove(){this.removed=true;}
  async click(){if(!this.disabled)await this.events.click?.();await flush();}
}
function setup(handle=()=>({dirty:false,version:8,acknowledged:true}),referrer='http://test/catalogo.html'){
  const events={},navigationEvents={},documentEvents={},calls=[],body=new Element(),moves=[];
  const location={href:'http://test/limpeza.html',origin:'http://test'};
  const history={state:null,pushState:state=>{history.state=state;moves.push('push');},go:n=>moves.push(n)};
  const document={body,referrer,createElement:()=>new Element(),addEventListener:(name,fn)=>documentEvents[name]=fn};
  const window={addEventListener:(name,fn)=>events[name]=fn,dispatchEvent:event=>events[event.type]?.(event),navigation:{addEventListener:(name,fn)=>navigationEvents[name]=fn}};
  const fetch=async(url,options={})=>{const data=options.body?JSON.parse(options.body):undefined;calls.push({url,data});const result=await handle(url,data);return {ok:result.httpStatus?false:true,json:async()=>result};};
  vm.runInNewContext(script,{window,document,location,history,fetch,URL,CustomEvent:class{constructor(type,options){this.type=type;this.detail=options.detail;}}});
  return {window,events,navigationEvents,documentEvents,calls,body,location,moves};
}
const button=(dialog,label)=>dialog.children.flatMap(child=>child.children).find(child=>child.textContent===label)||dialog.children.find(child=>child.textContent===label);

test('save-and-leave explicitly saves the latest optimistic version before allowing navigation',async()=>{
  const state=setup();state.window.cleanupGuard.update({dirty:true,version:7});const leave=state.window.cleanupBeforeLeave();
  const dialog=state.body.children.at(-1);await button(dialog,'Salvar e sair').click();assert.equal(await leave,true);
  assert.deepEqual(state.calls,[{url:'/api/cleanups/session/save',data:{version:7}}]);assert.equal(state.window.cleanupGuard.isDirty(),false);
});
test('discard-and-leave calls discard instead of save',async()=>{
  const state=setup();state.window.cleanupGuard.update({dirty:true,version:3});const leave=state.window.cleanupBeforeLeave();await button(state.body.children.at(-1),'Sair sem salvar').click();
  assert.equal(await leave,true);assert.equal(state.calls[0].url,'/api/cleanups/session/discard');assert.equal(state.calls.length,1);
});
test('cancel preserves pending changes and performs no persistence operation',async()=>{
  const state=setup();state.window.cleanupGuard.update({dirty:true,version:3});const leave=state.window.cleanupBeforeLeave();await button(state.body.children.at(-1),'Continuar revisando').click();
  assert.equal(await leave,false);assert.equal(state.calls.length,0);assert.equal(state.window.cleanupGuard.isDirty(),true);
});
test('failed save keeps the exit dialog open and provides a retry',async()=>{
  const state=setup(()=>({httpStatus:409,error:'A base mudou.'}));state.window.cleanupGuard.update({dirty:true,version:3});const leave=state.window.cleanupBeforeLeave();
  const dialog=state.body.children.at(-1);await button(dialog,'Salvar e sair').click();assert.equal(dialog.open,true);assert.match(dialog.children[2].textContent,/A base mudou/);assert.equal(button(dialog,'Salvar e sair').disabled,false);
  await button(dialog,'Continuar revisando').click();assert.equal(await leave,false);
});
test('nonzero identical counts require acknowledgement without saving SQL',async()=>{
  for(const count of [1,4]){
    const state=setup(()=>({dirty:count>0,version:2,acknowledged:true}));const acknowledgement=state.window.cleanupGuard.acknowledge({automatic_removed:count,dirty:count>0,version:1,acknowledged:false});
    const dialog=state.body.children.at(-1);assert.match(dialog.children[1].textContent,new RegExp(`${count} registro`));assert.match(dialog.children[1].textContent,/só serão gravadas/);
    await button(dialog,'Entendi · iniciar revisão').click();assert.equal((await acknowledgement).acknowledged,true);assert.equal(state.calls[0].url,'/api/cleanups/session/acknowledge');assert.equal(state.calls.length,1);
  }
});
test('zero identical removals starts review without a popup or database save',async()=>{
  const state=setup(()=>({dirty:false,version:2,acknowledged:true}));
  const result=await state.window.cleanupGuard.acknowledge({automatic_removed:0,dirty:false,version:1,acknowledged:false});
  assert.equal(result.acknowledged,true);assert.equal(state.body.children.length,0);
  assert.equal(state.calls.length,1);assert.equal(state.calls[0].url,'/api/cleanups/session/acknowledge');
  assert.equal(state.window.cleanupGuard.isDirty(),false);
});

test('external link waits for a save choice while internal review navigation retains the pending queue',async()=>{
  const state=setup();state.window.cleanupGuard.update({dirty:true,version:2});let prevented=false;
  const link={href:'http://test/catalogo.html',hasAttribute:()=>false};const action=state.documentEvents.click({target:{closest:()=>link},preventDefault(){prevented=true;}});
  assert.equal(prevented,true);assert.equal(state.location.href,'http://test/limpeza.html');await button(state.body.children.at(-1),'Continuar revisando').click();await action;
  assert.equal(state.location.href,'http://test/limpeza.html');
  const dialogs=state.body.children.length;link.href='http://test/revisao.html?selected=a';await state.documentEvents.click({target:{closest:()=>link},preventDefault(){throw Error('Internal navigation should not be blocked');}});
  assert.equal(state.body.children.length,dialogs);assert.equal(state.calls.length,0);
});
test('browser back to an internal guard entry is allowed; leaving offers save and cancel restores position',async()=>{
  const state=setup();state.window.cleanupGuard.update({dirty:true,version:2});await state.events.popstate({state:{cleanupNavigationGuard:true}});assert.equal(state.body.children.length,0);
  const goingBack=state.events.popstate({state:null});assert.equal(state.body.children.length,1);await button(state.body.children.at(-1),'Continuar revisando').click();await goingBack;assert.equal(state.moves.filter(value=>value===-2).length,0);
});
test('same-URL browser history navigation cannot bypass the pending-change prompt',async()=>{
  const state=setup();state.window.cleanupGuard.update({dirty:true,version:2});
  state.navigationEvents.navigate({navigationType:'traverse',destination:{url:'http://test/limpeza.html'}});
  const leaving=state.events.popstate({state:null});assert.equal(state.body.children.length,1);
  await button(state.body.children.at(-1),'Continuar revisando').click();await leaving;
  let warned=false;state.events.beforeunload({preventDefault(){warned=true;}});assert.equal(warned,true);
});
test('same-page anchor does not suppress a later native unload warning',async()=>{
  const state=setup();state.window.cleanupGuard.update({dirty:true,version:2});
  const link={href:'http://test/limpeza.html#top',hasAttribute:()=>false};
  await state.documentEvents.click({target:{closest:()=>link}});
  let warned=false;state.events.beforeunload({preventDefault(){warned=true;}});assert.equal(warned,true);
});
test('closing or refreshing with dirty data uses the native warning and never tries to save during unload',()=>{
  const state=setup();state.window.cleanupGuard.update({dirty:true,version:2});let prevented=false;const event={preventDefault(){prevented=true;}};state.events.beforeunload(event);
  assert.equal(prevented,true);assert.equal(event.returnValue,'');assert.equal(state.calls.length,0);
});
