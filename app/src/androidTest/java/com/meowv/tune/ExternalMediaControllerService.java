package com.meowv.tune;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.view.KeyEvent;

/** Runs under the test APK's UID, outside the instrumented app process. */
public final class ExternalMediaControllerService extends Service {
    private MediaBrowser browser;
    private final Messenger requests = new Messenger(new Handler(Looper.getMainLooper(), this::probe));

    @Override
    public IBinder onBind(Intent intent) {
        return requests.getBinder();
    }

    @Override
    public void onDestroy() {
        if (browser != null) browser.disconnect();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private boolean probe(Message request) {
        ComponentName service = request.getData().getParcelable("service");
        Messenger recipient = request.replyTo;
        if (browser != null) browser.disconnect();
        browser = new MediaBrowser(this, service, new MediaBrowser.ConnectionCallback() {
            @Override
            public void onConnected() {
                MediaController controller = new MediaController(ExternalMediaControllerService.this, browser.getSessionToken());
                Bundle result = new Bundle();
                result.putString("package", getPackageName());
                result.putInt("uid", Process.myUid());
                result.putInt("pid", Process.myPid());
                result.putParcelable("metadata", controller.getMetadata());
                result.putParcelable("state", controller.getPlaybackState());
                controller.getTransportControls().skipToNext();
                controller.getTransportControls().pause();
                controller.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
                reply(recipient, result);
            }

            @Override
            public void onConnectionFailed() {
                Bundle result = new Bundle();
                result.putString("error", "Framework MediaBrowser was refused");
                reply(recipient, result);
            }
        }, null);
        browser.connect();
        return true;
    }

    private static void reply(Messenger recipient, Bundle result) {
        Message response = Message.obtain();
        response.setData(result);
        try {
            recipient.send(response);
        } catch (RemoteException ignored) {
            // The instrumentation disconnected before the probe completed.
        }
    }
}
