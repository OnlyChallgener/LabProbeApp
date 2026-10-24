"""Anchored compact menus, long-press firewall ordering, subtle flowing lines."""
from pathlib import Path
p=Path(__file__).with_name('assets')/'hub-ui/router-tools.html';s=p.read_text(encoding='utf8')
def swap(old,new):
 global s
 assert s.count(old)==1,old[:100]
 s=s.replace(old,new)
css='''
.card:before{width:125px;height:76px;right:-10px;top:auto;bottom:10px;opacity:.65;background:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 136 82' fill='none' stroke='%23dce8ef' stroke-width='1.1'%3E%3Cpath d='M-4 66C18 67 21 40 53 42S72 71 109 53 143 52 152 60M14 83c18-26 39-8 56-21s16-37 40-39 34 14 43 12M64 8c-14 3-13 21 1 25s29-7 47 0 13 23 30 24'/%3E%3C/svg%3E") center/contain no-repeat}.menu{width:156px;max-width:156px;border-radius:18px;position:fixed;box-shadow:0 6px 24px #29324b20}.menu button{padding:13px 16px;min-height:44px}.overlay.anchored{background:transparent;display:block;padding:0}.drag-card{position:fixed!important;z-index:20;pointer-events:none;box-shadow:0 12px 30px #27334b38;transform:scale(1.025);opacity:.97}.drag-slot{border:1px dashed #bce6e6;border-radius:24px;background:#e9f5f7;margin-bottom:14px}.dragging{user-select:none;-webkit-user-select:none}.card{touch-action:pan-y}
'''
swap('[hidden]{display:none!important}',css+'\n[hidden]{display:none!important}')
swap("if(state.busy)return;const b=ev.target.closest('[data-action]');", "if(state.busy||Date.now()<suppressClickUntil)return;const b=ev.target.closest('[data-action]');")
swap("if(b.dataset.action==='menu')menu(r);", "if(b.dataset.action==='menu')menu(r,b);")
start=s.index("${section==='firewall'?`<div class=\"order\"");end=s.index('</section>`;',start)
s=s[:start]+s[end:]
swap('function menu(r){','function menu(r,anchor){')
swap(".join(''),true);$('modal').onclick=async ev=>", ".join(''),true);anchorMenu(anchor);$('modal').onclick=async ev=>")
anchor='function identifier(r)'
code='''function anchorMenu(anchor){if(!anchor)return;const menu=$('modal').querySelector('.menu'),overlay=$('modal').querySelector('.overlay');overlay.classList.add('anchored');const rect=anchor.getBoundingClientRect(),height=menu.getBoundingClientRect().height,left=Math.max(12,Math.min(innerWidth-168,rect.right-156)),below=rect.bottom+5;menu.style.left=left+'px';menu.style.top=Math.max(12,below+height<innerHeight-12?below:rect.top-height-5)+'px';}
let drag=null,press=null,suppressClickUntil=0,scrollFrame=0;
function stopPress(){if(press){clearTimeout(press.timer);press=null;}}
function finishDrag(cancel=false){stopPress();if(!drag)return;const d=drag;drag=null;cancelAnimationFrame(scrollFrame);d.slot.parentNode.insertBefore(d.card,d.slot);d.slot.remove();d.card.classList.remove('drag-card');d.card.removeAttribute('style');document.body.classList.remove('dragging');suppressClickUntil=Date.now()+400;const ids=[...$('content').querySelectorAll('[data-edit-row]')].map(c=>d.ids[Number(c.dataset.editRow)]);if(!cancel&&ids.some((id,i)=>id!==d.ids[i]))mutate('reorder','',{scope:d.scope,uuids:ids});else render();}
function dragMove(ev){if(!drag){if(press&&Math.hypot(ev.clientX-press.x,ev.clientY-press.y)>8)stopPress();return;}ev.preventDefault();drag.y=ev.clientY;drag.card.style.top=(ev.clientY-drag.offsetY)+'px';const cards=[...$('content').querySelectorAll('.card[data-edit-row]')];let before=null;for(const card of cards){const box=card.getBoundingClientRect();if(ev.clientY<box.top+box.height/2){before=card;break;}}$('content').insertBefore(drag.slot,before);}
$('content').addEventListener('pointerdown',ev=>{if(state.section!=='firewall'||state.busy||ev.target.closest('button,.address'))return;const card=ev.target.closest('[data-edit-row]');if(!card)return;stopPress();press={x:ev.clientX,y:ev.clientY,timer:setTimeout(()=>{const rect=card.getBoundingClientRect(),slot=document.createElement('div');slot.className='drag-slot';slot.style.height=rect.height+'px';card.parentNode.insertBefore(slot,card);document.body.appendChild(card);card.classList.add('drag-card');Object.assign(card.style,{left:rect.left+'px',top:rect.top+'px',width:rect.width+'px',margin:'0'});drag={card,slot,offsetY:press.y-rect.top,y:press.y,ids:firewallRows().map(r=>r.uuid),scope:state.direction+'_'+state.version};document.body.classList.add('dragging');suppressClickUntil=Date.now()+1000;press=null;const autoscroll=()=>{if(!drag)return;const step=drag.y<100?-7:drag.y>innerHeight-100?7:0;if(step)window.scrollBy(0,step);scrollFrame=requestAnimationFrame(autoscroll);};scrollFrame=requestAnimationFrame(autoscroll);},420)};});
document.addEventListener('pointermove',dragMove,{passive:false});document.addEventListener('pointerup',()=>finishDrag());document.addEventListener('pointercancel',()=>finishDrag(true));document.addEventListener('contextmenu',ev=>{if(drag||press)ev.preventDefault();});
'''
swap(anchor,code+anchor)
p.write_text(s,encoding='utf8');print('Anchored menus and drag ordering updated')
