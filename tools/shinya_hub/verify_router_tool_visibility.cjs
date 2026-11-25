const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('tools/shinya_hub/assets/hub-ui/router-tools.html','utf8');
const source=html.match(/<script>([\s\S]*?)<\/script>/)[1];
function fixture(){const nodes=new Map(),requests=[];let closes=0;const document={addEventListener(){},body:{setAttribute(){},removeAttribute(){}},getElementById(id){if(!nodes.has(id))nodes.set(id,{addEventListener(){},innerHTML:'',textContent:'',style:{},classList:{add(){},remove(){}},querySelector(){return {style:{}}}});return nodes.get(id);},querySelectorAll(){return []}};const c=vm.createContext({document,window:{addEventListener(){}},HubRouter:{request(id,body){requests.push({id,...JSON.parse(body)})}},setTimeout(){},clearTimeout(){},console});vm.runInContext(source,c);return {c,nodes,requests,run:s=>vm.runInContext(s,c)};}
let n=0;const check=(a,b)=>{assert.deepStrictEqual(a,b);n++};
for(const value of [false,0,'0','false',true,1,'1','true',null,undefined]){const f=fixture();f.c.feature=value;f.run('state.caps={ddns:feature,firewall:feature};buildTabs();render()');const supported=[true,1,'1','true'].includes(value);check(f.nodes.get('tabs').innerHTML.includes('固件 DDNS'),supported);check(f.run("sectionAllowed('firewall')"),supported);check(f.run("sectionAllowed('ddns')"),true);}
{
 const f=fixture();f.run("state.section='nativeDdns';state.caps={ddns:true};applyCapabilities({features:{ddns:false,firewall:false}});render()");check(f.run('state.section'),'ddns');check(f.nodes.get('tabs').innerHTML.includes('固件 DDNS'),false);check(f.nodes.get('modal').innerHTML,'');
 f.run("state.section='firewall';state.kind='firewall';render();editor();mutate('delete','old-id');refresh()");check(f.requests.length,0);check(f.nodes.get('add').hidden,true);
 f.run("state.caps={};render()");check(f.nodes.get('content').innerHTML,'');check(f.nodes.get('content').innerHTML.includes('不支持'),false);
}
{
 const f=fixture();f.run("window.start('ddns',{})");check(f.requests.map(x=>x.section),['ddns','capabilities']);
}
// Production script must parse completely and unknown/error paths never overwrite proved caps with false.
check(source.includes("state.caps.firewall!==false"),false);
console.log('PASS '+n+' router-tools capability display/action checks; no network');
