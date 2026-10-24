// Exercise the packaged original UI with networking blocked and a real saved
// scan response. Opening history must never issue a new scan request.
const fs=require('fs'),path=require('path'),assert=require('assert');
const {chromium}=require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{
const root=path.resolve('tools/shinya_hub/assets/hub-ui'),core=path.join(root,'scan-core');
const assets=JSON.parse(fs.readFileSync(path.join(core,'assets.json'),'utf8'));
const scan=JSON.parse(fs.readFileSync(process.argv[2]||'test/_analysis/oct12-signal-tools/official-h5/scan-18850348.json','utf8')).data;
assert(scan&&scan.scanTime&&scan.channel24Detail);
const html=fs.readFileSync(path.join(root,'official-scan.html'),'utf8').replace('__HUB_PROJECT__','"18850348"').replace('__HUB_SERIAL__','"G1TD8RX039025"');
const browser=await chromium.launch({channel:'chrome',headless:true});
try{
 const page=await browser.newPage({viewport:{width:393,height:790},deviceScaleFactor:2});
 const errors=[],missing=[],actions=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.exposeFunction('recordScan',action=>actions.push(action));
 await page.addInitScript(({scan})=>{
   window.HubScan={request(id,action){window.recordScan(action);setTimeout(()=>window.receive(id,{ok:true,data:scan}),20);}};
 },{scan});
 await page.route('**/*',async route=>{
  const url=route.request().url().split(/[?#]/)[0];
  if(url==='https://hub-ui.invalid/app-h5/')return route.fulfill({contentType:'text/html',body:html});
  const file=assets[url];
  if(file){const type=file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.png')?'image/png':'application/octet-stream';
   return route.fulfill({contentType:type,body:fs.readFileSync(path.join(core,file)),headers:{'Access-Control-Allow-Origin':'https://hub-ui.invalid'}});}
  missing.push(url);return route.abort();
 });
 await page.goto('https://hub-ui.invalid/app-h5/');
 await page.getByText('看周边干扰',{exact:true}).waitFor();
 await page.locator('.ssid-item-name').first().waitFor();
 assert.deepEqual(actions,['read']);
 await page.screenshot({path:'test/_analysis/oct13-official-game/local-original-scan.png',fullPage:true});
 await page.locator('#ssid-list-content .icon-arrow').click();
 await page.locator('.custom-popup').waitFor({state:'visible'});
 await page.screenshot({path:'test/_analysis/oct13-official-game/local-original-ssid-popup.png'});
 assert.equal(await page.locator('.custom-popup .ssid-item').count()>2,true);
 await page.locator('.van-overlay').click({position:{x:10,y:10}});
 await page.getByText('看干扰指数',{exact:true}).click();
 await page.screenshot({path:'test/_analysis/oct13-official-game/local-original-index.png'});
 assert.deepEqual(actions,['read']);assert.deepEqual(errors,[]);assert.deepEqual(missing,[]);
 console.log(JSON.stringify({historyActions:actions,errors,missing,originalCssUnchanged:fs.readFileSync(path.join(core,'63.40c8d62e.css')).equals(fs.readFileSync('test/_analysis/oct12-signal-tools/official-h5/63.40c8d62e.css'))}));
}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
