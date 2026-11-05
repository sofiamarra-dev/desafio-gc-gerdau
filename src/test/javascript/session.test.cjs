const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const script=fs.readFileSync(path.join(__dirname,'../../main/resources/static/session.js'),'utf8');
class Element {
  constructor(){this.children=[];this.events={};this.hidden=true;this.textContent='';}
  append(...children){this.children.push(...children);}
  prepend(...children){this.children.unshift(...children);}
  setAttribute(name,value){this[name]=value;}
  addEventListener(name,fn){this.events[name]=fn;}
}
async function setup(session,logoutOk=true){
  const header=new Element(),adminLink=new Element(),body=new Element(),listeners={},calls=[],redirects=[];
  const location={href:'http://test/menu.html',origin:'http://test',replace:url=>redirects.push(url)};
  const fetch=async(input,options={})=>{
    calls.push({input,options});
    return input==='/api/auth/status'?{ok:true,status:200,json:async()=>session}:{ok:logoutOk,status:logoutOk?204:500};
  };
  const document={body,createElement:()=>new Element(),querySelector:()=>header,querySelectorAll:()=>[adminLink],addEventListener:(name,fn)=>listeners[name]=fn};
  const window={fetch};
  vm.runInNewContext(script,{window,document,location,URL,Headers,fetch:(...args)=>window.fetch(...args)});
  await listeners.DOMContentLoaded();
  return {header,adminLink,calls,redirects,body};
}
test('normal account uses the header design and admin links stay hidden',async()=>{
  const {header,adminLink}=await setup({authenticated:true,email:'pessoa@gmail.com',admin:false});
  assert.equal(adminLink.hidden,true);
  const panel=header.children[0];assert.equal(panel.className,'session-user');
  assert.equal(panel.children[0].textContent,'PE');assert.equal(panel.children[1].children[0].textContent,'pessoa@gmail.com');
  assert.equal(panel.children[1].children[1].textContent,'Usuário');
});
test('admin can see admin links and logout sends CSRF before returning to login',async()=>{
  const {header,adminLink,calls,redirects}=await setup({authenticated:true,email:'admin@gmail.com',admin:true,csrfToken:'test-token',csrfHeader:'X-XSRF-TOKEN'});
  assert.equal(adminLink.hidden,false);
  await header.children[0].children[2].events.click();
  const logout=calls.find(call=>call.input==='/api/auth/logout');
  assert.equal(logout.options.method,'POST');assert.equal(logout.options.headers.get('X-XSRF-TOKEN'),'test-token');
  assert.deepEqual(redirects,['/login.html']);
});
test('logout failure preserves account identity and permits retry',async()=>{
  const {header,redirects}=await setup({authenticated:true,email:'pessoa@gmail.com',admin:false},false);
  const panel=header.children[0];await panel.children[2].events.click();
  assert.equal(panel.children[1].children[0].textContent,'pessoa@gmail.com');
  assert.match(panel.children[1].children[2].textContent,/Não foi possível sair/);
  assert.equal(panel.children[2].disabled,false);assert.equal(redirects.length,0);
});
test('expired session redirects before displaying any account details',async()=>{
  const {header,redirects}=await setup({authenticated:false});
  assert.equal(header.children.length,0);assert.deepEqual(redirects,['/login.html']);
});
