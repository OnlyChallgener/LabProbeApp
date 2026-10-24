// Inspect the authorized Android WebView through its ADB debug socket.
const fs=require('fs');
const {chromium}=require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{
 const browser=await chromium.connectOverCDP('http://127.0.0.1:19225');
 try {
  const pages=browser.contexts().flatMap(c=>c.pages());
  const page=pages.find(p=>p.url().includes('channelView'));
  if(!page)throw Error('No authorized channelView WebView');
  const data=await page.evaluate(async()=>{
   try{return {value:await window.HubScanRequest('read')}}catch(e){return {error:e.message}}
  });
  fs.writeFileSync('test/_analysis/oct13-official-game/live-scan-bridge.json',JSON.stringify(data));
  const summary=await page.evaluate(()=>{
   const vms=[...document.querySelectorAll('*')].map(n=>n.__vue__).filter(v=>v&&typeof v.getLastScanResult==='function');
   return vms.slice(0,3).map(v=>({scanning:v.scanning,loading:v.loading,success:v.successScan,sn:v.sn,projectId:v.projectId}));
  });
  console.log(JSON.stringify({bridgeError:data.error,code:data.value?.code,keys:Object.keys(data.value?.data||{}),scanTime:data.value?.data?.scanTime,summary}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
