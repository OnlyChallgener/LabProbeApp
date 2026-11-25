package com.facebook.react.modules.core;
public interface PermissionAwareActivity {
    void requestPermissions(String[] permissions,int requestCode,PermissionListener listener);
}
