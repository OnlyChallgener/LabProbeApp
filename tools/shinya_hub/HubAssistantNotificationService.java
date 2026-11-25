package com.labprobe.shinyahub;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Compatibility tombstone for old installs. Reception belongs to the live app process. */
public final class HubAssistantNotificationService extends Service {
    @Override public int onStartCommand(Intent intent,int flags,int startId){stopSelf();return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
}
