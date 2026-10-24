from pathlib import Path

p=Path('tools/shinya_hub/HubAuthView.java')
s=p.read_text(encoding='utf8')
s=s.replace('private boolean busy,active=false,editing=false;', 'private boolean busy,active=false,editing=false,layoutQueued=false;')
start=s.index('    @Override public void requestLayout()')
end=s.index('    private void loginPage()',start)
s=s[:start]+'''    @Override public void requestLayout(){super.requestLayout();if(layoutQueued)return;layoutQueued=true;post(()->{try{if(getWidth()>0&&getHeight()>0){measure(MeasureSpec.makeMeasureSpec(getWidth(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(getHeight(),MeasureSpec.EXACTLY));layout(getLeft(),getTop(),getRight(),getBottom());}}finally{layoutQueued=false;}});}
'''+s[end:]
s=s.replace('String username=prefs.getString("login_account","");try{username=HubToolSession.current().account;}catch(Exception ignored){}', 'String username=prefs.getString("login_account","");')
start=s.index('        TextView projectLabel=text("关联项目"')
end=s.index('        official.addView(text("官方账号",13',start)
s=s[:start]+'''        TextView projectChoice=text("自动匹配该账号下的已有项目",13,MUTED);official.addView(projectChoice);gap(official,10);TextView binding=text("正在读取账号状态…",13,MUTED);official.addView(binding);gap(official,12);
'''+s[end:]
start=s.index('        try{owner[0]=HubToolSession.current();')
end=s.index('        bind.setOnClickListener',start)
s=s[:start]+'''        bind.setEnabled(false);
        dialog.setContentView(page);dialog.show();Window initialWindow=dialog.getWindow();if(initialWindow!=null){initialWindow.setLayout(-1,-1);initialWindow.setStatusBarColor(Color.WHITE);initialWindow.setNavigationBarColor(BG);initialWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        worker.execute(()->{try{HubToolSession scope=HubToolSession.current();owner[0]=scope;if(!scope.base.startsWith("https://")){ui.post(()->{if(dialog.isShowing())binding.setText("绑定官方账号请使用 HTTPS 连接 HUB");});return;}JSONArray rows=scope.request("apMonitor/getProjectList2",new JSONObject()).getJSONArray("dataList");for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);JSONArray aps=p.optJSONArray("apList");if(aps==null||aps.length()==0)continue;JSONObject main=aps.getJSONObject(0);for(int j=0;j<aps.length();j++)if(aps.getJSONObject(j).optBoolean("isMain"))main=aps.getJSONObject(j);projects.add(new JSONObject().put("projectId",p.getString("projectId")).put("sn",main.getString("sn")));}ui.post(()->{if(!dialog.isShowing())return;if(projects.isEmpty()){binding.setText("HUB 中尚未添加路由器项目");return;}selected[0]=projects.get(0);bind.setEnabled(true);status[0].run();});}catch(Exception e){ui.post(()->{if(dialog.isShowing())binding.setText("登录 HUB 后，可在这里绑定官方账号");});}});
'''+s[end:]
s=s.replace('binding.setText("官方账号已绑定");toast("已验证并绑定");','binding.setText("官方账号已绑定，已自动匹配项目");toast("已验证并绑定");')
s=s.replace('v.optString("account"):"尚未绑定官方账号"','v.optString("account")+" · "+v.optInt("projectCount",1)+" 个项目":"尚未绑定官方账号"')
old='dialog.setOnDismissListener(d->cloudPassword.setText(""));dialog.setContentView(page);dialog.show();Window window=dialog.getWindow();if(window!=null){window.setLayout(-1,-1);window.setStatusBarColor(Color.WHITE);window.setNavigationBarColor(BG);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}'
assert old in s
s=s.replace(old,'dialog.setOnDismissListener(d->cloudPassword.setText(""));')
s=s.replace('card.addView(box(fields[i],true,null))','card.addView(compactBox(fields[i],true))')
p.write_text(s,encoding='utf8')
print('Account pages now open before session reads; layout requests coalesced; cloud projects auto matched')
