package com.facebook.react.uimanager;
/** Compile-only ABI stub, verified against RN 0.71.4 in the original APK. */
public abstract class SimpleViewManager<T extends android.view.View> {
    public abstract String getName();
    protected abstract T createViewInstance(ThemedReactContext context);
    public void onDropViewInstance(T view) { throw new UnsupportedOperationException("compile only"); }
    public java.util.Map<String,Object> getExportedCustomDirectEventTypeConstants(){return null;}
}
