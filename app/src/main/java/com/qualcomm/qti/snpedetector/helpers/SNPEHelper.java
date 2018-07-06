package com.qualcomm.qti.snpedetector.helpers;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import com.qualcomm.qti.snpe.FloatTensor;
import com.qualcomm.qti.snpe.NeuralNetwork;
import com.qualcomm.qti.snpe.SNPE;
import com.qualcomm.qti.snpedetector.Box;
import com.qualcomm.qti.snpedetector.MainActivity;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("SameParameterValue")
public class SNPEHelper {
    private final Application mApplication;
    private final Context mContext;

    private final BitmapToFloatArrayHelper mBitmapToFloatHelper;
    private final TimeStat mTimeStat;

    private String mSNPEVersionCached;

    // all of the following are allocated in the load function for the network
    private NeuralNetwork mNeuralNetwork;
    private String mRuntimeCoreName = "no core";
    private int[] mInputTensorShapeHWC;
    private FloatTensor mInputTensorReused;
    private Map<String, FloatTensor> mInputTensorsMap;


    public SNPEHelper(Application application) {
        mApplication = application;
        mContext = application;
        mBitmapToFloatHelper = new BitmapToFloatArrayHelper();
        mTimeStat = new TimeStat();
    }

    public String getSNPEVersion() {
        if (mSNPEVersionCached == null)
            mSNPEVersionCached = SNPE.getRuntimeVersion(mApplication);
        return mSNPEVersionCached;
    }

    public String getRuntimeCoreName() {
        return mRuntimeCoreName;
    }

    public int getInputTensorWidth() {
        return mInputTensorShapeHWC == null ? 0 : mInputTensorShapeHWC[1];
    }

    public int getInputTensorHeight() {
        return mInputTensorShapeHWC == null ? 0 : mInputTensorShapeHWC[0];
    }

    /* MobileNet-SSD Specific */

    private static final String MNETSSD_MODEL_ASSET_NAME = "tensorflow_mobilenet_ssd.dlc";
    private static final String MNETSSD_INPUT_LAYER = "Preprocessor/sub:0";
    private static final String MNETSSD_OUTPUT_LAYER = "Postprocessor/BatchMultiClassNonMaxSuppression";
    private static final String MNETSSD_OUTPUT_BOXES_1_100_4 = "Postprocessor/BatchMultiClassNonMaxSuppression_boxes";
    private static final String MNETSSD_OUTPUT_SCORES_1_1_100 = "Postprocessor/BatchMultiClassNonMaxSuppression_scores";
    private static final String MNETSSD_OUTPUT_CLASSES_1_1_100 = "Postprocessor/BatchMultiClassNonMaxSuppression_classes";
    private static final boolean MNETSSD_NEEDS_CPU_FALLBACK = true;
    private static final int MNETSSD_NUM_BOXES = 100;
    private final float mSSDOutputBoxes[] = new float[MNETSSD_NUM_BOXES * 4];
    private final float mSSDOutputClasses[] = new float[MNETSSD_NUM_BOXES];
    private final float mSSDOutputScores[] = new float[MNETSSD_NUM_BOXES];
    private final ArrayList<Box> mSSDBoxes = Box.createBoxes(MNETSSD_NUM_BOXES);

    public boolean loadMobileNetSSDFromAssets() {
        // cleanup
        disposeNeuralNetwork();

        // select core
        NeuralNetwork.Runtime selectedCore = NeuralNetwork.Runtime.GPU_FLOAT16;

        // load the network
        mNeuralNetwork = loadNetworkFromDLCAsset(mApplication, MNETSSD_MODEL_ASSET_NAME,
                selectedCore, MNETSSD_NEEDS_CPU_FALLBACK, MNETSSD_OUTPUT_LAYER);

        // if it didn't work, retry on CPU
        if (mNeuralNetwork == null) {
            complain("Error loading the DLC network on the " + selectedCore + " core. Retrying on CPU.");
            mNeuralNetwork = loadNetworkFromDLCAsset(mApplication, MNETSSD_MODEL_ASSET_NAME,
                    NeuralNetwork.Runtime.CPU, MNETSSD_NEEDS_CPU_FALLBACK, MNETSSD_OUTPUT_LAYER);
            if (mNeuralNetwork == null) {
                complain("Error also on CPU");
                return false;
            }
            complain("Loading on the CPU worked");
        }

        // cache the runtime name
        mRuntimeCoreName = mNeuralNetwork.getRuntime().toString();
        // read the input shape
        mInputTensorShapeHWC = mNeuralNetwork.getInputTensorsShapes().get(MNETSSD_INPUT_LAYER);
        // allocate the single input tensor
        mInputTensorReused = mNeuralNetwork.createFloatTensor(mInputTensorShapeHWC);
        // add it to the map of inputs, even if it's a single input
        mInputTensorsMap = new HashMap<>();
        mInputTensorsMap.put(MNETSSD_INPUT_LAYER, mInputTensorReused);
        return true;
    }

    public ArrayList<Box> mobileNetSSDInference(Bitmap modelInputBitmap) {
        // execute the inference, and get 3 tensors as outputs
        final Map<String, FloatTensor> outputs = inferenceOnBitmap(modelInputBitmap);
        if (outputs == null)
            return null;

        // convert tensors to boxes - Note: Optimized to read-all upfront
        outputs.get(MNETSSD_OUTPUT_BOXES_1_100_4).read(mSSDOutputBoxes, 0, mSSDOutputBoxes.length);
        outputs.get(MNETSSD_OUTPUT_CLASSES_1_1_100).read(mSSDOutputClasses, 0, mSSDOutputClasses.length);
        outputs.get(MNETSSD_OUTPUT_SCORES_1_1_100).read(mSSDOutputScores, 0, mSSDOutputScores.length);
        for (int i = 0; i < MNETSSD_NUM_BOXES; ++i) {
            final Box box = mSSDBoxes.get(i);
            box.top = mSSDOutputBoxes[i * 4];
            box.left = mSSDOutputBoxes[i * 4 + 1];
            box.bottom = mSSDOutputBoxes[i * 4 + 2];
            box.right = mSSDOutputBoxes[i * 4 + 3];
            box.type_id = Math.round(mSSDOutputClasses[i]);
            box.type_score = mSSDOutputScores[i];
            box.type_name = lookupMsCoco(box.type_id + 1, "???");
        }
        return mSSDBoxes;
    }


    /* Generic functions, for typical image models */

    private Map<String, FloatTensor> inferenceOnBitmap(Bitmap inputBitmap) {
        // safety check
        if (mNeuralNetwork == null || mInputTensorReused == null ||
                inputBitmap.getWidth() != getInputTensorWidth() ||
                inputBitmap.getHeight() != getInputTensorHeight()) {
            complain("No NN loaded, or image size different than tensor size");
            return null;
        }

        // [0.3ms] Bitmap to RGBA byte array (size: 300*300*4 (RGBA..))
        mBitmapToFloatHelper.bitmapToBuffer(inputBitmap);

        // [2ms] Pre-processing: Bitmap (300,300,4 ints) -> Float Input Tensor (300,300,3 floats)
        mTimeStat.startInterval();
        final float[] inputFloatsHW3 = mBitmapToFloatHelper.bufferToNormalFloatsBGR();
        if (mBitmapToFloatHelper.isFloatBufferBlack())
            return null;
        mInputTensorReused.write(inputFloatsHW3, 0, inputFloatsHW3.length, 0, 0);
        mTimeStat.stopInterval("i_tensor", 20, false);

        // [31ms on GPU16, 50ms on GPU] execute the inference
        mTimeStat.startInterval();
        final Map<String, FloatTensor> outputs = mNeuralNetwork.execute(mInputTensorsMap);
        mTimeStat.stopInterval("nn_exec ", 20, false);

        return outputs;
    }

    private static NeuralNetwork loadNetworkFromDLCAsset(
            Application application, String assetFileName, NeuralNetwork.Runtime selectedRuntime,
            boolean needsCpuFallback, String... outputLayerNames) {
        try {
            // input stream to read from the assets
            InputStream assetInputStream = application.getAssets().open(assetFileName);

            // create the neural network
            NeuralNetwork network = new SNPE.NeuralNetworkBuilder(application)
                    .setDebugEnabled(false)
                    .setOutputLayers(outputLayerNames)
                    .setModel(assetInputStream, assetInputStream.available())
                    .setPerformanceProfile(NeuralNetwork.PerformanceProfile.HIGH_PERFORMANCE)
                    .setRuntimeOrder(selectedRuntime) // Runtime.DSP, Runtime.GPU_FLOAT16, Runtime.GPU, Runtime.CPU
                    .setCpuFallbackEnabled(needsCpuFallback)
                    .build();

            // close input
            assetInputStream.close();

            // all right, network loaded
            return network;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } catch (IllegalStateException | IllegalArgumentException e2) {
            Log.e(MainActivity.LOGTAG, "SNPE could not load the network; try a different core, maybe? Trace follows:");
            e2.printStackTrace();
            return null;
        }
    }

    private void disposeNeuralNetwork() {
        if (mNeuralNetwork == null)
            return;
        mNeuralNetwork.release();
        mNeuralNetwork = null;
        mInputTensorShapeHWC = null;
        mInputTensorReused = null;
        mInputTensorsMap = null;
    }

    private void complain(String message) {
        Log.e(MainActivity.LOGTAG, message);
        // only show the message if on the main thread
        if (Looper.myLooper() == Looper.getMainLooper())
            Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
    }

    // VERBOSE COCO object map
    private Map<Integer, String> mCocoMap;

    private String lookupMsCoco(int cocoIndex, String fallback) {
        // map obtained from: https://github.com/tensorflow/models/blob/master/research/object_detection/data/mscoco_label_map.pbtxt
        // referenced by TensorFlow here: https://github.com/tensorflow/models/blob/master/research/object_detection/object_detection_tutorial.ipynb
        if (mCocoMap == null) {
            mCocoMap = new ArrayMap<>();
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
        }
        return mCocoMap.containsKey(cocoIndex) ? mCocoMap.get(cocoIndex) : fallback;
    }
}
