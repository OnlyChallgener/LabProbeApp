"""Local proportions for added HUB account surfaces and shared compact dialogs."""
from pathlib import Path
p=Path('tools/shinya_hub/HubAuthView.java');s=p.read_text(encoding='utf8')
start=s.index('    private void accountPage()');end=s.index('    private void divider',start)
s=s[:start]+'''    private void accountPage(){
        setBackgroundColor(BG);LinearLayout page=col();page.setPadding(dp(12),dp(12),dp(12),dp(20));addView(page,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(getContext());scroll.setVerticalScrollBarEnabled(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout body=col();scroll.addView(body);
        LinearLayout card=col();card.setPadding(dp(12),dp(4),dp(12),dp(4));card.setBackground(surface(Color.WHITE,18));body.addView(card);
        LinearLayout avatar=row();TextView label=text("头像",14,MUTED);avatar.addView(label,new LinearLayout.LayoutParams(0,dp(48),1));label.setGravity(Gravity.CENTER_VERTICAL);ImageView image=new ImageView(getContext());image.setImageDrawable(getContext().getApplicationInfo().loadIcon(getContext().getPackageManager()));image.setScaleType(ImageView.ScaleType.FIT_CENTER);avatar.addView(image,new LinearLayout.LayoutParams(dp(28),dp(28)));card.addView(avatar);divider(card);
        LinearLayout name=col();name.setPadding(0,dp(10),0,dp(10));name.addView(text("名称",12,MUTED));gap(name,5);LinearLayout names=row();nameLabel=text(userName,15,INK);names.addView(nameLabel,new LinearLayout.LayoutParams(0,-2,1));names.addView(text("›",22,MUTED));name.addView(names);name.setOnClickListener(v->event("topHubChange"));card.addView(name);divider(card);
        LinearLayout identity=col();identity.setPadding(0,dp(10),0,dp(12));identity.addView(text("HUB 账号",12,MUTED));gap(identity,5);String username=prefs.getString("login_account","");try{username=HubToolSession.current().account;}catch(Exception ignored){}identity.addView(text(username,15,INK));card.addView(identity);gap(body,14);
        LinearLayout change=row();change.setPadding(dp(12),0,dp(12),0);change.setBackground(surface(Color.WHITE,18));change.addView(text("修改APP登录密码",14,INK),new LinearLayout.LayoutParams(0,-2,1));change.addView(text("›",22,MUTED));body.addView(change,new LinearLayout.LayoutParams(-1,dp(48)));change.setOnClickListener(v->passwordPage());
        gap(body,14);LinearLayout settings=row();settings.setPadding(dp(12),0,dp(12),0);settings.setBackground(surface(Color.WHITE,18));settings.addView(text("HUB 设置 / 官方账号",14,INK),new LinearLayout.LayoutParams(0,-2,1));settings.addView(text("›",22,MUTED));body.addView(settings,new LinearLayout.LayoutParams(-1,dp(48)));settings.setOnClickListener(v->settingsPage());gap(body,16);
        TextView logout=button("退出登录",()->HubDialog.show(getContext(),"提示","确定退出登录吗？","取消","确定",d->{d.dismiss();event("topHubSubmit");}));logout.setTextSize(15);page.addView(logout,new LinearLayout.LayoutParams(-1,dp(44)));
    }
'''+s[end:]
s=s.replace('bind.setEnabled(false);bind.setText("正在验证…");','bind.setEnabled(false);projectChoice.setEnabled(false);bind.setText("正在验证…");')
s=s.replace('bind.setEnabled(true);bind.setText("验证并绑定");','bind.setEnabled(true);projectChoice.setEnabled(true);bind.setText("验证并绑定");')
# The login boxes keep their established geometry. The optional cloud form is local.
s=s.replace('official.addView(box(cloudAccount,false,null))','official.addView(compactBox(cloudAccount,false))').replace('official.addView(box(cloudPassword,true,null))','official.addView(compactBox(cloudPassword,true))')
s=s.replace('    private void accountPage(){','    private LinearLayout compactBox(EditText field,boolean secret){LinearLayout box=box(field,secret,null);for(int i=0;i<box.getChildCount();i++){View child=box.getChildAt(i);LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)child.getLayoutParams();p.height=dp(48);child.setLayoutParams(p);}return box;}\n    private void accountPage(){',1)
# Subpage type hierarchy, while retaining all labels and values.
s=s.replace('text("HUB 设置",20,INK)','text("HUB 设置",18,INK)').replace('text("连接设置",17,INK)','text("连接设置",15,INK)').replace('text("官方账号",17,INK)','text("官方账号",15,INK)')
s=s.replace('text("修改登录密码",20,INK)','text("修改登录密码",18,INK)').replace('text(titles[i],17,MUTED)','text(titles[i],13,MUTED)')
p.write_text(s,encoding='utf8')
p=Path('tools/shinya_hub/HubToolsView.java');s=p.read_text(encoding='utf8')
start=s.index('        LinearLayout root=column();TextView title=text(old==null?');end=s.index('    private boolean validIpv4',start)
s=s[:start]+'''        HubDialog.show(getContext(),old==null?"新建 STUN 穿透":"编辑 STUN 穿透",form,"取消","保存",dialog->{try{if(g!=generation)throw new Exception("项目已切换，请重新打开");int n=Integer.parseInt(port.getText().toString());String address=ip.getText().toString().trim();if(n<1||n>65535||!validIpv4(address))throw new Exception("请填写有效的 IPv4 和端口");JSONObject draft=new JSONObject().put("name",name.getText().toString().trim()).put("serviceType",service.getSelectedItem()).put("transportProtocol",proto.getSelectedItem()).put("targetType","manual").put("targetIpv4",address).put("targetPort",n).put("enabled",enabled.isChecked());dialog.dismiss();mutate(old,old==null?"create":"update",draft);}catch(Exception e){toast(message(e));}});}
'''+s[end:]
start=s.index('    private void styleDialog(');end=s.index('    private void mutate(',start)
s=s[:start]+'''    private void confirm(String title,Runnable run){if(mutating){toast("配置正在保存");return;}final int g=generation;HubDialog.show(getContext(),"提示",title,"取消","确定",d->{d.dismiss();if(g==generation)run.run();else toast("项目已切换，请重新打开操作");});}
'''+s[end:]
old='AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle("最近 STUN 地址").setMessage(result).setPositiveButton("关闭",null).create();dialog.show();styleDialog(dialog);'
assert old in s;s=s.replace(old,'HubDialog.show(getContext(),"最近 STUN 地址",result,null,"关闭",null);')
s=s.replace('text(title,14,MUTED)','text(title,13,MUTED)').replace('e.setTextSize(16);','e.setTextSize(14);').replace('dp(48)));gap(parent,12)','dp(44)));gap(parent,10)')
p.write_text(s,encoding='utf8')
p=Path('tools/shinya_hub/assets/hub-ui/router-tools.html');s=p.read_text(encoding='utf8')
at=s.index('[hidden]{display:none!important}')
s=s[:at]+'''/* Content proportions match the official compact dialog, with a visible action footer. */
.sheet:not(.menu){background:linear-gradient(130deg,#d9e9fc 0%,#e8f8f7 48%,#fff 100%);border-radius:18px;max-height:calc(100dvh - 32px)}.sheet h2{font-size:17px;line-height:1.4;padding:17px 18px 12px;font-weight:500}.sheet .actions{background:transparent;padding:14px}.sheet .actions button{font-size:14px;min-height:40px;padding:9px 12px}.sheet .actions .primary{background:#ffffff66;color:var(--teal);border:1px solid var(--teal)}.form-body{padding:0 18px 2px}.field{font-size:12px;margin:9px 0}.field input,.choice{font-size:14px;min-height:42px;padding-top:10px;padding-bottom:10px}.form-note{color:#828b95}.form-error{font-size:12px;line-height:1.45}.sheet .state{font-size:13px;margin:12px 0}
'''+s[at:];p.write_text(s,encoding='utf8')
print('Applied local account proportions and compact gradient dialogs')
