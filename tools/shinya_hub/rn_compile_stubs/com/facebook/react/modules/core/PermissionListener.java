package com.facebook.react.modules.core;
public interface PermissionListener {
    boolean onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults);
}
