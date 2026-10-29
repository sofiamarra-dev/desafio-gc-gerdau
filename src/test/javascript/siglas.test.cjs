const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const dir=process.env.SIGLAS_STATIC_DIR||path.resolve(__dirname,'../../main/resources/static');
const html=fs.readFileSync(path.join(dir,'siglas.html'),'utf8'),script=fs.readFileSync(path.join(dir,'siglas.js'),'utf8');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
class Element{
 constructor(tag,id){this.tag=tag;this.id=id;this.value='';this.checked=false;this.children=[];this.listeners={};this.hidden=false;this.disabled=false;}
 append(...children){this.children.push(...children);}
 replaceChildren(...children){this.children=children;}
 setAttribute(name,value){this[name]=value;}
 addEventListener(event,fn){this.listeners[event]=fn;}
 reportValidity(){return true;}
 focus(){}
 async fire(event){await this.listeners[event]?.({preventDefault(){}});await flush();}
}
async function setup(fail=false){
 const els={};for(const match of html.matchAll(/<([\w-]+)[^>]*\bid="([^"]+)"[^>]*>/g))els[match[2]]=new Element(match[1],match[2]);
 const entries=[{code:'HE',meaning:'hora extra',variants:['horas extras'],active:true,version:1}];
 const calls=[];
 const walk=e=>[e,...e.children.flatMap(walk)];
 for(const el of Object.values(els))el.querySelectorAll=selector=>selector==='input, textarea'?Object.values(els).filter(e=>['input','textarea'].includes(e.tag)&&e.id.startsWith('entry-')||e.id.startsWith('correction-')&&['input','textarea'].includes(e.tag)):walk(el).filter(e=>e.tag==='button');
 const document={getElementById:id=>{assert.ok(els[id],id);return els[id];},createElement:tag=>new Element(tag),querySelectorAll:()=>[]};
 const fetch=async(url,options={})=>{
  const body=options.body?JSON.parse(options.body):null;calls.push({url,method:options.method,body});
  if(options.method==='POST'&&url.endsWith('/corrections')){
   if(fail)return{ok:false,status:409,json:async()=>({error:'Versão alterada em outra tela'})};
   Object.assign(entries[0],{meaning:body.meaning,variants:body.variants,version:2});
  }
  return {ok:true,json:async()=>({entries:JSON.parse(JSON.stringify(entries))})};
 };
 vm.runInNewContext(script,{document,fetch,window:{addEventListener(){}},confirm:()=>true});
 await flush();return{els,calls};
}
test('correction unlocks meaning but keeps code and active state protected',async()=>{
 const {els:e,calls}=await setup();
 assert.equal(e['entry-meaning'].readOnly,true);
 await e['start-correction'].fire('click');
 assert.equal(e['entry-meaning'].readOnly,false);assert.equal(e['entry-code'].readOnly,true);assert.equal(e['entry-active'].disabled,true);
 e['entry-meaning'].value='hora extra corrigida';
 await e['entry-form'].fire('submit');
 assert.equal(calls.filter(c=>c.method==='POST').length,0);
 e['correction-reason'].value='Corrigido após verificar o significado';e['correction-confirmed'].checked=true;
 await e['entry-form'].fire('submit');
 const sent=calls.find(c=>c.method==='POST');
 assert.equal(sent.url,'/api/abbreviations/HE/corrections');assert.equal(sent.body.version,1);assert.equal(sent.body.confirmed,true);
 assert.equal(sent.body.meaning,'hora extra corrigida');assert.equal('active' in sent.body,false);
 assert.equal(e['entry-meaning'].readOnly,true);assert.equal(e['correction-fields'].hidden,true);
});
test('version conflict preserves correction draft and reason for review',async()=>{
 const {els:e}=await setup(true);await e['start-correction'].fire('click');
 e['entry-meaning'].value='novo significado';e['correction-reason'].value='Motivo da correção confirmada';e['correction-confirmed'].checked=true;
 await e['entry-form'].fire('submit');
 assert.equal(e['entry-meaning'].value,'novo significado');assert.equal(e['correction-reason'].value,'Motivo da correção confirmada');
 assert.match(e['form-message'].textContent,/Versão alterada/);assert.equal(e['entry-active'].disabled,true);
});
