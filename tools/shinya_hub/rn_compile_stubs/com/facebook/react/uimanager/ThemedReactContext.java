package com.facebook.react.uimanager;
/** Compile only; runtime uses the original ReactContext implementation. */
public class ThemedReactContext extends android.content.ContextWrapper {
    public ThemedReactContext(android.content.Context context){super(context);}
    public android.app.Activity getCurrentActivity(){return null;}
    public <T extends com.facebook.react.bridge.JavaScriptModule> T getJSModule(Class<T> cls){return null;}
}
