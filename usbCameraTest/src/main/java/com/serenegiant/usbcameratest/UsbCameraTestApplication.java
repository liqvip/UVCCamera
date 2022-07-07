package com.serenegiant.usbcameratest;

import android.app.Application;

/**
 * @author: Little Bei
 * @Date: 2022/7/6
 */
public class UsbCameraTestApplication extends Application {
    private static final String TAG = "UsbCameraTestApplicatio";
    private static UsbCameraTestApplication application;
    @Override
    public void onCreate() {
        super.onCreate();
        application = this;
    }

    public static UsbCameraTestApplication getApplication() {
        return application;
    }
}
