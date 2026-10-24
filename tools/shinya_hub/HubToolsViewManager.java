package com.labprobe.shinyahub;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

public final class HubToolsViewManager extends SimpleViewManager<HubToolsView> {
    private final String mode;
    public HubToolsViewManager(String mode){this.mode=mode;}
    @Override public String getName(){return mode.equals("beta")?"HubBetaSurface":"HubStunSurface";}
    @Override protected HubToolsView createViewInstance(ThemedReactContext context){return new HubToolsView(context,mode);}
    @ReactProp(name="sn") public void setSn(HubToolsView view,String sn){view.setSn(sn);}
    @Override public void onDropViewInstance(HubToolsView view){view.dispose();super.onDropViewInstance(view);}
}
