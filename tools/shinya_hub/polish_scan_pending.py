from pathlib import Path

p=Path('tools/shinya_hub/assets/hub-ui/router-scan.html');s=p.read_text(encoding='utf8')
at=s.index('</style>')
css='''
html{-webkit-text-size-adjust:100%;text-size-adjust:100%}body{font-size:13px}.stamp{display:flex;align-items:center;justify-content:flex-end;gap:6px;font-size:11px;line-height:1.2;padding:2px 0 5px;min-height:28px}.stamp button{display:flex;align-items:center;justify-content:center;width:28px;height:28px;padding:4px}.stamp svg{width:18px;height:18px;display:block}.tabs{gap:8px}.tabs button{font-size:14px;padding:8px 0 12px}.tabs .active:after{bottom:3px;width:38px}.bands button{font-size:13px;padding:10px 0 12px}.chips{gap:8px;margin:4px 0 12px;padding:6px 8px;border-radius:12px}.chip{display:flex;align-items:center;justify-content:center;gap:5px;font-size:12px;padding:6px 7px;min-height:30px}.chip .ssid{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.chip b{flex-shrink:0;margin-left:0;width:17px;height:17px}.chip b span{font-size:10px}.choose{flex-shrink:0;width:22px;height:30px}.choose:before{font-size:18px}.units{font-size:11px}.axis{font-size:10px;row-gap:3px;margin-bottom:12px;grid-template-columns:36px repeat(var(--columns),1fr)}.wifi{gap:8px;padding:9px 0}.router-icon,.router-icon img{width:45px;height:43px}.router-icon .brand{width:20px;height:20px}.labels{font-size:10px;gap:4px;flex-wrap:nowrap;line-height:1.4;white-space:nowrap}.labels .maker{max-width:70px;overflow:hidden;text-overflow:ellipsis}.tag{padding:0 3px;flex-shrink:0}.name{font-size:13px;margin:3px 0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.mac{font-size:10px}.level{font-size:13px;min-width:48px}.level small{font-size:10px;margin-top:3px}
.signal-mask{position:fixed;inset:0;background:#0009;z-index:10;display:flex;align-items:flex-end;padding:0 14px max(10px,env(safe-area-inset-bottom))}.signal-sheet{width:100%;background:#151d29;border-radius:16px 16px 0 0;padding:12px;max-height:55vh;display:flex;flex-direction:column}.signal-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:9px 10px;overflow-y:auto;min-height:0;padding:0 0 12px}.signal-grid .chip{width:100%;flex:none;text-align:center;font-size:12px;min-height:32px;opacity:.5}.signal-grid .chip.active{opacity:1;background:color-mix(in srgb,currentColor 14%,transparent)}.signal-actions{display:flex;gap:12px;padding-top:8px;flex-shrink:0}.signal-actions button{flex:1;min-height:36px;border:1px solid var(--teal);border-radius:24px;color:var(--teal);font-size:13px}.signal-actions .all{background:var(--teal);color:#fff}
'''
s=s[:at]+css+s[at:]
s=s.replace('<button id="rescan" aria-label="重新扫描">↻</button>','<button id="rescan" aria-label="重新扫描"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="M20 7V3m0 4h-4M20 7a8 8 0 1 0 1 8"/></svg></button>')
s=s.replace("selected:'',busy:false","selected:null,busy:false").replace("state.selected='';render();","state.selected=null;closeSignals();render();")
start=s.index(" $('chips').hidden=");end=s.index(" $('units').textContent=",start)
s=s[:start]+''' $('chips').hidden=state.view!=='ssid'||!list.length;$('chips').innerHTML=chips.map((r,i)=>`<button class="chip" style="color:${colors[i]}" data-ssid="${esc(r.ssid)}"><span class="ssid">${esc(r.ssid||'隐藏信号')}</span><b><span>${list.filter(v=>v.ssid===r.ssid).length}</span></b></button>`).join('')+'<button class="choose" aria-label="选择 Wi-Fi 信号"></button>';
 $('chips').onclick=e=>{const b=e.target.closest('[data-ssid]');if(b){toggleSignal(b.dataset.ssid);draw();}else if(e.target.closest('.choose'))openSignals();};
'''+s[end:]
s=s.replace("${r.isSelfSsid?'ruijie':esc(r.manufacturer||'未知厂商')}<span", "<span class=\"maker\">${r.isSelfSsid?'ruijie':esc(r.manufacturer||'未知厂商')}</span><span")
s=s.replace("c.globalAlpha=state.selected&&r.ssid!==state.selected ? .2 : 1;", "if(state.selected!==null&&!state.selected.has(r.ssid))return;c.globalAlpha=1;")
at=s.index('function draw()')
s=s[:at]+'''function closeSignals(){document.querySelector('.signal-mask')?.remove();}
function toggleSignal(ssid){if(state.selected===null)state.selected=new Set(rows().map(r=>r.ssid));state.selected.has(ssid)?state.selected.delete(ssid):state.selected.add(ssid);}
function openSignals(){closeSignals();const mask=document.createElement('div');mask.className='signal-mask';mask.innerHTML='<section class="signal-sheet"><div class="signal-grid"></div><div class="signal-actions"><button class="none">全部取消</button><button class="all">全部选择</button></div></section>';document.body.append(mask);const values=[...new Set(rows().map(r=>r.ssid))];function fill(){mask.querySelector('.signal-grid').innerHTML=values.map((ssid,i)=>`<button class="chip ${state.selected===null||state.selected.has(ssid)?'active':''}" style="color:${colors[i%colors.length]}" data-index="${i}"><span class="ssid">${esc(ssid||'隐藏信号')}</span><b><span>${rows().filter(r=>r.ssid===ssid).length}</span></b></button>`).join('');}fill();mask.onclick=e=>{if(e.target===mask){closeSignals();return;}const b=e.target.closest('[data-index]');if(b){toggleSignal(values[Number(b.dataset.index)]);fill();draw();}if(e.target.closest('.none')){state.selected=new Set();fill();draw();}if(e.target.closest('.all')){state.selected=null;fill();draw();}};}
'''+s[at:]
p.write_text(s,encoding='utf8')
p=Path('tools/shinya_hub/assets/hub-ui/router-tools.html');s=p.read_text(encoding='utf8')
s=s.replace('<span class="refresh-icon" aria-hidden="true">↻</span>', '<svg class="refresh-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="M20 7V3m0 4h-4M20 7a8 8 0 1 0 1 8"/></svg>')
s=s.replace('</style>','html{-webkit-text-size-adjust:100%;text-size-adjust:100%}.refresh-icon{display:block;flex-shrink:0}#refresh{line-height:normal}</style>')
p.write_text(s,encoding='utf8')
p=Path('tools/shinya_hub/HubPages.java');s=p.read_text(encoding='utf8')
s=s.replace('TextView name=text(a,title,20,','TextView name=text(a,title,16,')
s=s.replace('settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);','settings.setJavaScriptEnabled(true);settings.setTextZoom(100);settings.setDomStorageEnabled(false);')
s=s.replace('LinearLayout page=col(a);page.setBackgroundColor(dark?0xff121c24:BG);','''LinearLayout page=col(a);page.setBackgroundColor(dark?0xff121c24:BG);
        page.setOnApplyWindowInsetsListener((v,insets)->{int[] xy=new int[2];page.getLocationOnScreen(xy);int top=insets.getSystemWindowInsetTop();int resource=a.getResources().getIdentifier("status_bar_height","dimen","android");if(resource>0)top=Math.max(top,a.getResources().getDimensionPixelSize(resource));int pad=Math.max(0,top-xy[1]);if(page.getPaddingTop()!=pad)page.setPadding(0,pad,0,0);return insets;});''')
s=s.replace('w.setStatusBarColor(dark?0xff121c24:BG);return page;','w.setStatusBarColor(dark?0xff121c24:BG);w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);w.getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);page.requestApplyInsets();return page;')
p.write_text(s,encoding='utf8')
p=Path('tools/shinya_hub/HubToolsView.java');s=p.read_text(encoding='utf8')
s=s.replace('int[] xy=new int[2];getLocationOnScreen(xy);int screenHeight=', 'int resource=getResources().getIdentifier("status_bar_height","dimen","android");if(resource>0)top=Math.max(top,getResources().getDimensionPixelSize(resource));int[] xy=new int[2];getLocationOnScreen(xy);int screenHeight=')
s=s.replace('screenHeight-bottom)));}', 'screenHeight-bottom)));relayout();}')
p.write_text(s,encoding='utf8')
print('Scan proportions, two-column multi-select, vector refresh and system-bar insets updated')
