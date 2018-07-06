package com.qualcomm.qti.snpedetector.helpers;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.Context;
import android.util.Log;

import com.qualcomm.qti.snpedetector.MainActivity;

import io.fotoapparat.Fotoapparat;
import io.fotoapparat.parameter.Resolution;
import io.fotoapparat.parameter.ScaleType;
import io.fotoapparat.preview.Frame;
import io.fotoapparat.view.CameraView;

import static io.fotoapparat.log.LoggersKt.logcat;
import static io.fotoapparat.selector.FocusModeSelectorsKt.autoFocus;
import static io.fotoapparat.selector.FocusModeSelectorsKt.continuousFocusPicture;
import static io.fotoapparat.selector.FocusModeSelectorsKt.fixed;
import static io.fotoapparat.selector.LensPositionSelectorsKt.back;
import static io.fotoapparat.selector.PreviewFpsRangeSelectorsKt.highestFps;
import static io.fotoapparat.selector.ResolutionSelectorsKt.highestResolution;
import static io.fotoapparat.selector.SelectorsKt.firstAvailable;

public class CameraPreviewHelper implements LifecycleObserver {

    private final Fotoapparat mCamera;

    public interface Callbacks {
        // implement this to select which Camera Preview Feed resolution to use
        Resolution selectPreviewResolution(Iterable<Resolution> resolutions);

        // implement this to receive each frame; which you'll have to adjust for colorspace/resolution/rotation
        void onCameraPreviewFrame(Frame frame);
    }

    @SuppressWarnings("unchecked")
    public CameraPreviewHelper(Context context, CameraView cameraView, Callbacks callbacks, boolean letterbox) {
        mCamera = Fotoapparat
                .with(context)
                .into(cameraView)
                .lensPosition(back())
                // the following doesn't change the preview bytes, only the 'zoom' of the displayed texture
                .previewScaleType(letterbox ? ScaleType.CenterInside : ScaleType.CenterCrop)
                .previewFpsRange(highestFps())
                .previewResolution(callbacks::selectPreviewResolution)
                .photoResolution(highestResolution())
                .focusMode(firstAvailable(continuousFocusPicture(), autoFocus(), fixed()))
                .frameProcessor(callbacks::onCameraPreviewFrame)
                .logger(logcat())
                .cameraErrorCallback(e -> {
                    Log.e(MainActivity.LOGTAG, "Error with the camera: " + e);
                })
                .build();
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private void startPreview() {
        Log.e(MainActivity.LOGTAG, "Start camera");
        mCamera.start();
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private void stopPreview() {
        Log.e(MainActivity.LOGTAG, "Stop camera");
        mCamera.stop();
    }
}
