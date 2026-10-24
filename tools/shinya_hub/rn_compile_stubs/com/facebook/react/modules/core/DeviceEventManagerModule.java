package com.facebook.react.modules.core;
public class DeviceEventManagerModule {
    public interface RCTDeviceEventEmitter extends com.facebook.react.bridge.JavaScriptModule {
        void emit(String eventName, Object data);
    }
}
