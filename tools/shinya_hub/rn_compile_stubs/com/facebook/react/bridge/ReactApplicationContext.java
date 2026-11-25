package com.facebook.react.bridge;
public class ReactApplicationContext extends android.content.ContextWrapper {
    public ReactApplicationContext(){super(null);}
    public <T extends JavaScriptModule> T getJSModule(Class<T> type){return null;}
    public android.app.Activity getCurrentActivity(){return null;}
    public void addActivityEventListener(ActivityEventListener listener){}
    public void removeActivityEventListener(ActivityEventListener listener){}
    public void addLifecycleEventListener(LifecycleEventListener listener){}
    public void removeLifecycleEventListener(LifecycleEventListener listener){}
}
