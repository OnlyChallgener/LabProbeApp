package com.facebook.react.bridge;
public interface ActivityEventListener {
 void onActivityResult(android.app.Activity activity,int requestCode,int resultCode,android.content.Intent data);
 void onNewIntent(android.content.Intent intent);
}
