// Execute the real page script with a minimal DOM; no server or user database.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync('src/main/resources/web/index.html', 'utf8');
const elements = new Map();
function element() {
  return {value:'', hidden:false, checked:false, disabled:false, children:[], dataset:{},
    classList:{add(){},remove(){},toggle(){}}, addEventListener(){}, focus(){},reset(){},
    appendChild(x){this.children.push(x);}, contains(){return false;},querySelector(){return element();}};
}
const document = {getElementById(id){if(!elements.has(id)) elements.set(id,element());return elements.get(id);},
  createElement:element, addEventListener(){},querySelectorAll(){return [];}};
let result = {ok:false,out:'[错误] 拒绝保存'};
let sent = [];
const context = vm.createContext({document, console, Date, setTimeout(){return 1;},clearTimeout(){},
  fetch:async (path, opts) => { if(path==='/api/cmd') {sent.push(opts.body);return {json:async()=>result};}
    return {json:async()=>[]}; }});
vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],context);
const tick = () => new Promise(resolve=>setImmediate(resolve));
(async()=>{
  await tick();
  vm.runInContext('startNew()',context);
  elements.get('f_title').value='未保存的中文';
  elements.get('form').onsubmit({preventDefault(){}});
  await tick();
  assert.equal(elements.get('editor').hidden,false);
  assert.equal(elements.get('f_title').value,'未保存的中文');
  result={ok:true,out:'[OK]'};
  elements.get('form').onsubmit({preventDefault(){}});await tick();
  assert.equal(elements.get('editor').hidden,true);
  context.task={title:'旧任务',due:Date.now()-86400000,priority:1,note:'备注',hidden:false};
  vm.runInContext('startEdit(task)',context);
  elements.get('f_note').value='修改后的备注';
  elements.get('form').onsubmit({preventDefault(){}});await tick();
  assert.equal(sent.at(-1),'set 旧任务 -n 修改后的备注');
  vm.runInContext('startEdit(task)',context);
  const before=sent.length;
  elements.get('f_due').value='';
  elements.get('form').onsubmit({preventDefault(){}});await tick();
  assert.equal(sent.length,before);
  assert.equal(elements.get('editor').hidden,false);
  console.log('PASS: web save failure preserves draft; success closes; unchanged overdue time omitted; unsupported clear rejected');
})().catch(e=>{console.error(e);process.exitCode=1;});
