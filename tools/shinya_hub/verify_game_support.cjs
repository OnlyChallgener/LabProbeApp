const fs=require('fs'),path=require('path'),assert=require('assert');
const {chromium}=require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{
 const root=path.resolve('tools/shinya_hub/assets/hub-ui'),core=path.join(root,'scan-core');
 const assets=JSON.parse(fs.readFileSync(path.join(core,'assets.json'),'utf8'));
 const metadata=JSON.parse(fs.readFileSync('D:/Github/ShinyaHub/shinya_hub/resources/supported_games.json','utf8'));metadata.versionTs=null;
 const html=fs.readFileSync(path.join(root,'game-support.html'),'utf8').replace('__HUB_PROJECT__','"1"').replace('__HUB_SERIAL__','"G1TD8RX039025"');
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:393,height:790},deviceScaleFactor:2});
  const errors=[],missing=[],requests=[];page.on('pageerror',e=>errors.push(e.message));
  await page.exposeFunction('recordGame',r=>requests.push(r));
  await page.addInitScript(data=>{window.HubGame={request(id,raw){window.recordGame(JSON.parse(raw));setTimeout(()=>window.receive(id,{ok:true,data}),10);}};},metadata);
  await page.route('**/*',async route=>{
   const url=route.request().url().split(/[?#]/)[0];
   if(url==='https://hub-ui.invalid/app-h5/')return route.fulfill({contentType:'text/html',body:html});
   const f=assets[url];if(f)return route.fulfill({contentType:f.endsWith('.js')?'application/javascript':f.endsWith('.css')?'text/css':f.endsWith('.png')?'image/png':'application/octet-stream',body:fs.readFileSync(path.join(core,f)),headers:{'Access-Control-Allow-Origin':'https://hub-ui.invalid'}});
   missing.push(url);return route.abort();
  });
  await page.goto('https://hub-ui.invalid/app-h5/');await page.getByText('王者荣耀',{exact:true}).waitFor();
  assert.equal(await page.locator('.game-item').count(),metadata.gameList.length);
  await page.getByPlaceholder('输入游戏名搜索').fill('无畏契约');assert.equal(await page.locator('.game-item').count(),1);
  await page.getByPlaceholder('输入游戏名搜索').fill('不存在的游戏');await page.getByText('暂未找到支持的游戏',{exact:true}).waitFor();
  assert(!/许愿|反馈/.test(await page.locator('body').innerText()));
  await page.getByPlaceholder('输入游戏名搜索').fill('');
  await page.screenshot({path:'test/_analysis/oct13-official-game/game-support/support-page.png',fullPage:true});
  assert.deepEqual(errors,[]);assert.deepEqual(missing,[]);
  assert(requests.every(r=>r.resource==='/api/v1/game/accelerate/getAccelerateGameList'));
  console.log(JSON.stringify({games:metadata.gameList.length,searchVerified:true,wishesRemoved:true,requests,errors,missing}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
