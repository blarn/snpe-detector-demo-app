package com.qualcomm.qti.snpedetector;

import android.Manifest;
import android.arch.lifecycle.Lifecycle;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.qualcomm.qti.snpedetector.helpers.CameraPreviewHelper;
import com.qualcomm.qti.snpedetector.helpers.NV21ConversionHelper;
import com.qualcomm.qti.snpedetector.helpers.SNPEHelper;
import com.qualcomm.qti.snpedetector.helpers.TimeStat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

import io.fotoapparat.parameter.Resolution;
import io.fotoapparat.preview.Frame;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

/**
 * Setup:
 * 1. Load NN model on SNPE, get input resolution
 * 2. Check that you have the Camera permissions, or ask for them
 * 3. Configure camera for the closest input resolution
 * 4. Start preview feed between onResume and onPause
 * <p>
 * For every frame (in a separate thread):
 * 1. nv21->(rgba)Bitmap
 * 2. Counter-rotate the Bitmap and scale it to the model input size
 * 3. Perform the inference on the Bitmap
 * 3.1 Bitmap to RGBA[] byte array
 * 3.2 RGBA[] to SNPE BGR[] FloatTensor (300x300x3)
 * 3.3 SNPE Inference -> Output Tensors(3)
 * 3.4 Output Tensors -> Boxes
 * 4. Copy the boxes locally for rendering
 * <p>
 * When Boxes are updated:
 * 1. update box rendering in the Overlay
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    public static final String LOGTAG = "SNPEDetector";

    private Button buttonTest;

    private SNPEHelper mSnpeHelper;
    private CameraPreviewHelper mCameraPreviewHelper;
    private NV21ConversionHelper mNV21ConversionHelper;

    private OverlayRenderer mOverlayRenderer;
    private boolean mNetworkLoaded;
    private int mNV21FrameRotation;
    private boolean mInferenceSkipped;
    private Bitmap mNV21PreviewBitmap;
    private Bitmap mModelInputBitmap;
    private Canvas mModelInputCanvas;
    private Paint mModelBitmapPaint;
    private final TimeStat mTimer = new TimeStat();
    private final TimeStat mTimer2 = new TimeStat();
    private final TimeStat mTimer3 = new TimeStat();
    private ArrayList<ObjCounter> counterList = ObjCounter.createCounters();
    private long prevTime;
    private ArrayMap<Integer, String> classificationMap;

    private double confLevel = 0.7;
    private int state = 0;

    public ArrayList<Box> boxes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // populate UI
        setContentView(R.layout.activity_main);

        buttonTest = (Button) findViewById(R.id.button1);
        buttonTest.setOnClickListener(this);
        buttonTest.setTag(1);
        buttonTest.setText("Switch to Finder");


        mOverlayRenderer = findViewById(R.id.overlayRenderer);
        //((SeekBar) findViewById(R.id.thresholdBar)).setOnSeekBarChangeListener(mThresholdListener);

        classificationMap = makeClassMap();
        prevTime = System.currentTimeMillis();
    }

    public void promptSpeechInput() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say Something");

        try {
            startActivityForResult(i, 100);

        } catch (ActivityNotFoundException a) {
            Toast.makeText(getApplicationContext(),
                    "sorry",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case 100: {
                if (resultCode == RESULT_OK && null != data) {
                    ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    String speechInput = result.get(0);

                    if(!classificationMap.containsValue(speechInput)) {
                        mOverlayRenderer.accessibilityOutput(speechInput+" is not a known category");
                        //go back to mode A
                        //buttonTest.setText("Switch to Finder");
                        //buttonTest.setTag(1);
                        return;
                    }

                    prevTime = System.currentTimeMillis();
                    int val = 0;
                    for(int i = 0; i<5; i++) {
                        do {
                            val = modeB(speechInput);
                            if (val != 0) {
                                //found
                                mOverlayRenderer.accessibilityOutput(speechInput+" found!");
                                break;
                            }
                        } while (System.currentTimeMillis() - prevTime < 2000);
                        if(val != 0) break;
                        mOverlayRenderer.accessibilityOutput("not finding " + speechInput + ", try moving camera");
                    }

                    if(val == 0) {
                        //timed out
                        mOverlayRenderer.accessibilityOutput(speechInput+" not found");
                    }

                    //go back to mode A
                    //buttonTest.setText("Switch to Finder");
                    //buttonTest.setTag(1);
                }
                break;
            }

        }
    }


    public void onClick(View vi) {
        if (vi == buttonTest) {
            final int status = (Integer) vi.getTag();
            if (status == 1) {
                buttonTest.setText("Switch to general");
                buttonTest.setTag(0);
                promptSpeechInput();
            } else {
                buttonTest.setText("Switch to Finder");
                buttonTest.setTag(1);
            }
        }
    }


    // this function makes sure we load the network the first time it's required - we could do it
    // in the onCreate, but this will block the first paint of the UI, leaving a gray box
    private void ensureNetCreated() {
        if (mSnpeHelper == null) {
            // load the neural network for object detection with SNPE
            mSnpeHelper = new SNPEHelper(getApplication());
            mTimer.startInterval();
            mNetworkLoaded = mSnpeHelper.loadMobileNetSSDFromAssets();
            mTimer.stopInterval("net_load", 1, false);

            // update the text in the text box
            runOnUiThread(mUpdateTopLabelTask);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        createCameraPreviewHelper();
    }

    private final CameraPreviewHelper.Callbacks mCameraPreviewCallbacks = new CameraPreviewHelper.Callbacks() {
        @Override
        public Resolution selectPreviewResolution(Iterable<Resolution> resolutions) {
            // defer net creation to this moment, so the UI has time to be flushed
            ensureNetCreated();

            // This function selects the resolution (amongst the set of possible 'preview'
            // resolutions) which is closest to the input resolution of the model (but not smaller)
            final int fallbackSize = 300; // if the input is not reliable, just assume some size;
            final int targetWidth = mNetworkLoaded ? mSnpeHelper.getInputTensorWidth() : fallbackSize;
            final int targetHeight = mNetworkLoaded ? mSnpeHelper.getInputTensorHeight() : fallbackSize;
            io.fotoapparat.parameter.Resolution preferred = null;
            double preferredScore = 0;
            for (Resolution resolution : resolutions) {
                if (resolution.width < targetWidth || resolution.height < targetHeight)
                    continue;
                double score = Math.pow(targetWidth - resolution.width, 2) + Math.pow(targetHeight - resolution.height, 2);
                if (preferred == null || score < preferredScore) {
                    preferred = resolution;
                    preferredScore = score;
                }
            }
            return preferred;
        }

        @Override
        public void onCameraPreviewFrame(Frame frame) {
            // NOTE: This is executed on a different thread - don't update UI from this!
            // NOTE: frame.image in NV21 format (1.5 bytes per pixel) - often rotated (e.g. 270),
            // different frame.size.width (ex. 1600) and frame.size.height (ex. 1200) than the model
            // input.

            // skip processing if the neural net is not loaded - nothing to do with this Frame
            if (!mNetworkLoaded)
                return;
            // skip processing if the app is paused or stopped (one frame may still be pending)
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                return;

            // [2ms] convert NV21-format preview frame -> RGBA Bitmap (same size)
            mTimer2.startInterval();
            mTimer.startInterval();
            mNV21FrameRotation = frame.getRotation();
            mNV21PreviewBitmap = mNV21ConversionHelper.convert(frame.getImage(), frame.getSize().width, frame.getSize().height);
            mTimer.stopInterval("nv21conv", 10, false);

            // [2.5ms] down-scale and rotate (counter to sensor orientation) the RGBA Bitmap to the model input size
            mTimer.startInterval();
            final int inputWidth = mSnpeHelper.getInputTensorWidth();
            final int inputHeight = mSnpeHelper.getInputTensorHeight();
            // allocate the object only on the first time
            if (mModelInputBitmap == null || mModelInputBitmap.getWidth() != inputWidth || mModelInputBitmap.getHeight() != inputHeight) {
                // create ARGB8888 bitmap and canvas, with the right size
                mModelInputBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
                mModelInputCanvas = new Canvas(mModelInputBitmap);

                // compute the roto-scaling matrix (preview image -> screen image) and apply it to
                // the canvas. this includes translation for 'letterboxing', i.e. the image will
                // have black bands to the left and right if it's a portrait picture
                final Matrix mtx = new Matrix();
                final int previewWidth = mNV21PreviewBitmap.getWidth();
                final int previewHeight = mNV21PreviewBitmap.getHeight();
                final float scaleWidth = ((float) inputWidth) / previewWidth;
                final float scaleHeight = ((float) inputHeight) / previewHeight;
                final float frameScale = Math.min(scaleWidth, scaleHeight); // centerInside
                //final float frameScale = Math.max(scaleWidth, scaleHeight); // centerCrop
                final float dx = inputWidth - (previewWidth * frameScale);
                final float dy = inputHeight - (previewHeight * frameScale);
                mtx.postScale(frameScale, frameScale);
                mtx.postTranslate(dx / 2, dy / 2);
                if (frame.getRotation() != 0) {
                    mtx.postTranslate(-inputWidth / 2, -inputHeight / 2);
                    mtx.postRotate(-frame.getRotation());
                    mtx.postTranslate(inputWidth / 2, inputHeight / 2);
                }
                mModelInputCanvas.setMatrix(mtx);

                // create the "Paint", to set the antialiasing option
                mModelBitmapPaint = new Paint();
                mModelBitmapPaint.setFilterBitmap(true);

                // happens only the first time
                Log.d(LOGTAG, "Reallocated Input Bitmap (down-scaled and rotated)");
            }
            mModelInputCanvas.drawColor(Color.BLACK);
            mModelInputCanvas.drawBitmap(mNV21PreviewBitmap, 0, 0, mModelBitmapPaint);
            mTimer.stopInterval("scalerot", 10, false);

            // [2-45ms] give the bitmap to SNPE for inference
            mTimer.startInterval();
            boxes = mSnpeHelper.mobileNetSSDInference(mModelInputBitmap);
            mInferenceSkipped = boxes == null;
            mTimer.stopInterval("detect", 10, false);

            // deep copy the results so we can draw the current set while guessing the next set
            mOverlayRenderer.setBoxesFromAnotherThread(boxes);

            if ((int) buttonTest.getTag() == 1) {
                //mode A
                for (Box box : boxes) {
                    if (box.type_id == 0 || box.type_score < confLevel) continue;
                    counterList.get(box.type_id).count++;
                }

                if (System.currentTimeMillis() - prevTime > 3000) {
                    ObjCounter maxObj = Collections.max(counterList);
                    mOverlayRenderer.accessibilityOutput("there is a " + (String) (classificationMap.get((Integer) (maxObj.index)))+" in front of you");

                    counterList = ObjCounter.createCounters();
                    prevTime = System.currentTimeMillis();
                }
            }

            // done, schedule a UI update
            mTimer2.stopInterval("frame", 10, false);
            mTimer2.tick("cam", 10);
            runOnUiThread(mUpdateTopLabelTask);
        }
    };



    private SeekBar.OnSeekBarChangeListener mThresholdListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            mOverlayRenderer.setNextBoxScoreThreshold(progress / 100f);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    };

    @AfterPermissionGranted(0x123)
    private void createCameraPreviewHelper() {
        // ensure we have Camera permissions before proceeding
        final String[] requiredPerms = {Manifest.permission.CAMERA};
        if (EasyPermissions.hasPermissions(this, requiredPerms)) {
            // create the camera helper and nv21 conversion helpers here
            if (mCameraPreviewHelper == null) {
                mNV21ConversionHelper = new NV21ConversionHelper(this);
                mCameraPreviewHelper = new CameraPreviewHelper(this, findViewById(R.id.camera_view), mCameraPreviewCallbacks, true);
                getLifecycle().addObserver(mCameraPreviewHelper);
            }
        } else
            EasyPermissions.requestPermissions(this, "Please surrender the Camera",
                    0x123, requiredPerms);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    // task to refresh the label at the top of the screen with current information
    private Runnable mUpdateTopLabelTask = () -> {
        String txt;
        if (mNetworkLoaded) {
            txt = "SNPE: " + mSnpeHelper.getSNPEVersion() + " - " + mSnpeHelper.getRuntimeCoreName() +
                    " (" + (int) mTimer.getAverageInterval("net_load") + "ms)";
            if (mModelInputBitmap != null)
                txt += " - Input Tensor: " + mModelInputBitmap.getWidth() + "x" + mModelInputBitmap.getHeight() + "x3";
            txt += "\n";
            final float cameraFps = mTimer2.getAverageTickFrequency("cam");
            if (mNV21PreviewBitmap != null)
                txt += "Closest Preview Size: " + mNV21PreviewBitmap.getWidth() + "x" + mNV21PreviewBitmap.getHeight() +
                        ", rot: " + mNV21FrameRotation + (cameraFps > 0 ? ", fps: " + Math.round(cameraFps) : "") + "\n";
            if (mInferenceSkipped)
                txt += "** BLACK INPUT - SKIPPING INFERENCE **\n";
        } else
            txt = "NETWORK NOT LOADED\n";
        txt += "Total time: " + mTimer2.getAverageInterval("frame") + "ms " +
                "(nv21: " + mTimer.getAverageInterval("nv21conv") +
                ", scale: " + mTimer.getAverageInterval("scalerot") +
                ", detect: " + mTimer.getAverageInterval("detect") + ")\n";
        //txt += "Thresh: " + mOverlayRenderer.getBoxScoreThreshold();
        ((TextView) findViewById(R.id.text)).setText(" ");
    };

    public int modeB(String input){
        for(Box box : boxes){
            if(box.type_name.equals(input) && box.type_score>0.6){
                return 1;
            }
        }
        return 0;
    }

    private ArrayMap<Integer, String> makeClassMap() {
        ArrayMap<Integer, String> mCocoMap = new ArrayMap<>();
            mCocoMap.put(1, "person");
            mCocoMap.put(2, "bicycle");
            mCocoMap.put(3, "car");
            mCocoMap.put(4, "motorcycle");
            mCocoMap.put(5, "airplane");
            mCocoMap.put(6, "bus");
            mCocoMap.put(7, "train");
            mCocoMap.put(8, "truck");
            mCocoMap.put(9, "boat");
            mCocoMap.put(10, "traffic light");
            mCocoMap.put(11, "fire hydrant");
            mCocoMap.put(13, "stop sign");
            mCocoMap.put(14, "parking meter");
            mCocoMap.put(15, "bench");
            mCocoMap.put(16, "bird");
            mCocoMap.put(17, "cat");
            mCocoMap.put(18, "dog");
            mCocoMap.put(19, "horse");
            mCocoMap.put(20, "sheep");
            mCocoMap.put(21, "cow");
            mCocoMap.put(22, "elephant");
            mCocoMap.put(23, "bear");
            mCocoMap.put(24, "zebra");
            mCocoMap.put(25, "giraffe");
            mCocoMap.put(27, "backpack");
            mCocoMap.put(28, "umbrella");
            mCocoMap.put(31, "handbag");
            mCocoMap.put(32, "tie");
            mCocoMap.put(33, "suitcase");
            mCocoMap.put(34, "frisbee");
            mCocoMap.put(35, "skis");
            mCocoMap.put(36, "snowboard");
            mCocoMap.put(37, "sports ball");
            mCocoMap.put(38, "kite");
            mCocoMap.put(39, "baseball bat");
            mCocoMap.put(40, "baseball glove");
            mCocoMap.put(41, "skateboard");
            mCocoMap.put(42, "surfboard");
            mCocoMap.put(43, "tennis racket");
            mCocoMap.put(44, "bottle");
            mCocoMap.put(46, "wine glass");
            mCocoMap.put(47, "cup");
            mCocoMap.put(48, "fork");
            mCocoMap.put(49, "knife");
            mCocoMap.put(50, "spoon");
            mCocoMap.put(51, "bowl");
            mCocoMap.put(52, "banana");
            mCocoMap.put(53, "apple");
            mCocoMap.put(54, "sandwich");
            mCocoMap.put(55, "orange");
            mCocoMap.put(56, "broccoli");
            mCocoMap.put(57, "carrot");
            mCocoMap.put(58, "hot dog");
            mCocoMap.put(59, "pizza");
            mCocoMap.put(60, "donut");
            mCocoMap.put(61, "cake");
            mCocoMap.put(62, "chair");
            mCocoMap.put(63, "couch");
            mCocoMap.put(64, "potted plant");
            mCocoMap.put(65, "bed");
            mCocoMap.put(67, "dining table");
            mCocoMap.put(70, "toilet");
            mCocoMap.put(72, "tv");
            mCocoMap.put(73, "laptop");
            mCocoMap.put(74, "mouse");
            mCocoMap.put(75, "remote");
            mCocoMap.put(76, "keyboard");
            mCocoMap.put(77, "cell phone");
            mCocoMap.put(78, "microwave");
            mCocoMap.put(79, "oven");
            mCocoMap.put(80, "toaster");
            mCocoMap.put(81, "sink");
            mCocoMap.put(82, "refrigerator");
            mCocoMap.put(84, "book");
            mCocoMap.put(85, "clock");
            mCocoMap.put(86, "vase");
            mCocoMap.put(87, "scissors");
            mCocoMap.put(88, "teddy bear");
            mCocoMap.put(89, "hair drier");
            mCocoMap.put(90, "toothbrush");
        return mCocoMap;
    };
}