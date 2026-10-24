package com.labprobe.shinyahub;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
public final class HubDiagnosisViewManager extends SimpleViewManager<HubDiagnosisView> {
    @Override public String getName(){return "HubDiagnosisSurface";}
    @Override protected HubDiagnosisView createViewInstance(ThemedReactContext context){return new HubDiagnosisView(context);}
    @ReactProp(name="projectId") public void setProject(HubDiagnosisView view,String id){view.setProject(id);}
    @Override public void onDropViewInstance(HubDiagnosisView view){view.dispose();super.onDropViewInstance(view);}
}
