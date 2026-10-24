package com.facebook.react.uimanager.events;
import com.facebook.react.bridge.WritableMap;
public interface RCTEventEmitter extends com.facebook.react.bridge.JavaScriptModule { void receiveEvent(int tag,String event,WritableMap value); }
