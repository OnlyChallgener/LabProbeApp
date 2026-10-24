// Verify the packaged report page with every external request blocked.
const fs=require('fs'),path=require('path'),assert=require('assert');
const {chromium}=require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{
 const root=path.resolve('tools/shinya_hub/assets/hub-ui'),core=path.join(root,'scan-core');
 const assets=JSON.parse(fs.readFileSync(path.join(core,'assets.json'),'utf8'));
 const html=fs.readFileSync(path.join(root,'game-reports.html'),'utf8').replace('__HUB_PROJECT__','"1"').replace('__HUB_SERIAL__','"G1TD8RX039025"');
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:393,height:790},deviceScaleFactor:2});
  const errors=[],missing=[],requests=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.exposeFunction('recordGame',r=>requests.push(r));
  await page.addInitScript(()=>{
   window.HubGame={request(id,raw){const r=JSON.parse(raw);window.recordGame(r);
    const data=r.resource.endsWith('getStaList2')?{dataList:[]}:[];
    setTimeout(()=>window.receive(id,{ok:true,data}),10);
   }};
  });
  await page.route('**/*',async route=>{
   const url=route.request().url().split(/[?#]/)[0];
   if(url==='https://hub-ui.invalid/app-h5/')return route.fulfill({contentType:'text/html',body:html});
   const file=assets[url];
   if(file)return route.fulfill({contentType:file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.png')?'image/png':'application/octet-stream',body:fs.readFileSync(path.join(core,file)),headers:{'Access-Control-Allow-Origin':'https://hub-ui.invalid'}});
   missing.push(url);return route.abort();
  });
  await page.goto('https://hub-ui.invalid/app-h5/');
  await page.waitForFunction(()=>document.body.innerText.includes('游戏'));
  await page.waitForTimeout(500);
  await page.screenshot({path:'test/_analysis/oct13-official-game/local-game-report-empty.png',fullPage:true});
  assert(requests.some(r=>r.resource.endsWith('getGameAccelerateHistoryNew')));
  assert.deepEqual(errors,[]);assert.deepEqual(missing,[]);
  console.log(JSON.stringify({requests,errors,missing,text:await page.locator('body').innerText()}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
