package com.labprobe.shinyahub;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import java.util.*;
public final class HubAuthViewManager extends SimpleViewManager<HubAuthView> {
    private final String mode;
    public HubAuthViewManager(String mode){this.mode=mode;}
    @Override public String getName(){return mode.equals("login")?"HubLoginSurface":"HubAccountSurface";}
    @Override protected HubAuthView createViewInstance(ThemedReactContext c){return new HubAuthView(c,mode);}
    @ReactProp(name="logining") public void setBusy(HubAuthView v,boolean busy){v.setBusy(busy);}
    @ReactProp(name="name") public void setName(HubAuthView v,String name){v.setName(name);}
    @Override public Map<String,Object> getExportedCustomDirectEventTypeConstants(){
        Map<String,Object> out=new HashMap<>();
        Map<String,Object> change=new HashMap<>(),submit=new HashMap<>();
        change.put("registrationName","onChange");submit.put("registrationName","onSubmitEditing");
        out.put("topHubChange",change);out.put("topHubSubmit",submit);return out;
    }
    @Override public void onDropViewInstance(HubAuthView v){v.dispose();super.onDropViewInstance(v);}
}
