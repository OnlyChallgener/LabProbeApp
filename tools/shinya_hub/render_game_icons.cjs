// Code-native vector icons in the existing grey shortcut style.
const fs=require('fs');
const {chromium}=require('C:/Users/admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{
 const root='tools/shinya_hub/assets/hub-ui';
 const bolts=fs.readFileSync('tools/shinya_hub/assets/stun-gold-bolts.svg','utf8')
  .replaceAll('#ffe6a1','#ccd2dc').replaceAll('#ffc864','#a9b2bf').replaceAll('#eaa02f','#818c9d')
  .replaceAll('#ffe7b0','#c3cbd7').replaceAll('#f1b447','#8e99ab').replaceAll('#fff0c9','#edf1f6');
 const box='<rect width="96" height="96" rx="22" fill="#293442"/>';
 const uu='<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 96 96">'+box+'<g fill="none" stroke="#aab1bc" stroke-width="5" stroke-linecap="round"><path d="M48 25v22"/><path d="M37 27a22 22 0 1 0 22 0"/><path d="M34 19a31 31 0 1 0 28 0"/></g></svg>';
 const store='<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 96 96">'+box+'<path d="M29 30h38l6 41H23Z" fill="#abb2be"/><path d="M40 29v8a8 8 0 0 0 16 0v-8" fill="none" stroke="#293442" stroke-width="5"/></svg>';
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try{
  for(const [name,svg] of [['g-s',bolts],['g-u',uu],['g-a',store]]){
   fs.writeFileSync(root+'/'+name+'.svg',svg);
   const page=await browser.newPage({viewport:{width:97,height:96},deviceScaleFactor:2});
   await page.setContent('<style>html,body{margin:0;background:transparent}</style>'+svg);
   await page.screenshot({path:root+'/'+name+'.png',omitBackground:true});await page.close();
  }
 }finally{await browser.close();}
})();
