package com.example.ece420_vppg;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import android.util.Range;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.ByteBuffer;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.common.util.concurrent.ListenableFuture;


public class MainActivity extends AppCompatActivity {

    // Loads the C++ library once when this class is first used.
    // Same as the Kotlin `companion object { init { System.loadLibrary(...) } }`.
    static {
        System.loadLibrary("ece420_vppg");
    }

    // JNI function implemented in C++. Kotlin wrote `external fun testNative(): Int`.
    public native int testNative();
    public native void addSample(double intensity);

    // Views we need to touch from code (found by their android:id in the XML layout).
    private PreviewView previewView;
    private TextView permissionText;
    private TextView fpsText;
    private SwitchMaterial flashlightSwitch;
    private SwitchMaterial exposureSwitch;

    // The bound camera. Null until CameraX finishes starting up.
    private Camera camera;

    // Background thread for frame processing
    private ExecutorService cameraExecutor;

    // Variabls for measuring FPS
    private int frameCount = 0;
    private long startTime = 0;

    private IntensityPlotView intensityPlot;

    // Handles the camera-permission pop-up. This replaces Compose's
    // rememberLauncherForActivityResult. The lambda runs when the user answers.
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        showCameraUi(granted);
                        if (granted) {
                            startCamera();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        cameraExecutor = Executors.newSingleThreadExecutor();
        setContentView(R.layout.activity_main);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);   // inflate the XML UI

        // Call into the C++ code, exactly like the Kotlin version did.
        int bpm = testNative();
        Log.d("vPPG", "Native C++ BPM: " + bpm);

        // Grab the views from the layout.
        previewView = findViewById(R.id.previewView);
        permissionText = findViewById(R.id.permissionText);
        flashlightSwitch = findViewById(R.id.flashlightSwitch);
        exposureSwitch = findViewById(R.id.exposureSwitch);
        fpsText = findViewById(R.id.fpsText);
        intensityPlot = findViewById(R.id.intensityPlot);

        // Flashlight toggle (Compose: Switch onCheckedChange -> enableTorch).
        flashlightSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            if (camera != null) {
                camera.getCameraControl().enableTorch(isChecked);
            }
        });

        // Exposure-lock toggle.
        exposureSwitch.setOnCheckedChangeListener((button, isChecked) -> setExposureLock(isChecked));

        // Ask for the camera permission if we don't have it yet.
        if (hasCameraPermission()) {
            showCameraUi(true);
            startCamera();
        } else {
            showCameraUi(false);
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // Shows the live preview when permission is granted, otherwise the message.
    private void showCameraUi(boolean granted) {
        previewView.setVisibility(granted ? View.VISIBLE : View.GONE);
        permissionText.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    private void startCamera() {

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                // Camera preview
                Preview preview = new Preview.Builder()
                        // target 30 FPS
                        .setTargetFrameRate(new Range<>(30, 30))
                        .build();

                preview.setSurfaceProvider(
                        previewView.getSurfaceProvider()
                );

                // Camera frame analysis
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                                // Keeps preview response by dropping older frames if needed
                                // Does not guarantee 30 FPS
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .setOutputImageFormat(
                                // CameraX provides info in YUV format, request it in RGBA instead
                                // Bytes 0,1,2,3: alpha, red, green, blue
                                ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )
                        .build();

                // Analyze each incoming frame
                imageAnalysis.setAnalyzer(cameraExecutor, image -> {

                    try {
                        processFrame(image);
                    } finally {
                        // Release frame so CameraX can provide the next one
                        image.close();
                    }

                });

                // Bind preview and analysis to camera
                cameraProvider.unbindAll();

                camera = cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                );

                // Enable camera controls
                flashlightSwitch.setEnabled(
                        camera.getCameraInfo().hasFlashUnit()
                );

                exposureSwitch.setEnabled(true);

            } catch (Exception e) {
                Log.e("vPPG", "Camera initialization failed", e);
            }

        }, ContextCompat.getMainExecutor(this));
    }

    // Locks/unlocks auto-exposure via the Camera2 interop layer.
    // @OptIn is Java's equivalent of Kotlin's @OptIn(ExperimentalCamera2Interop::class).
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void setExposureLock(boolean locked) {
        if (camera == null) {
            return;
        }
        CaptureRequestOptions options = new CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
                .build();
        Camera2CameraControl.from(camera.getCameraControl())
                .setCaptureRequestOptions(options);
    }

    private void processFrame(ImageProxy image) {

        // call ROI extraction
        extractROI(image);

        // Get image dimensions
        int width = image.getWidth();
        int height = image.getHeight();

        // Get frame timestamp
        long timestamp = image.getImageInfo().getTimestamp();

        if (startTime == 0) {
            startTime = timestamp;
        }

        frameCount++;

        // Convert nanoseconds to seconds
        double elapsedTime = (timestamp - startTime) / 1e9;

        // Update FPS approximately once per second
        if (elapsedTime >= 1.0) {

            double fps = (frameCount - 1) / elapsedTime;

            Log.d("vPPG",
                    "Frame Size: " + width + "x" + height
                            + " | FPS: " + fps
            );

            // Update UI on main thread
            runOnUiThread(() -> {
                fpsText.setText(
                        String.format(java.util.Locale.US, "FPS: %.1f", fps)
                );
            });

            // Reset FPS counter
            frameCount = 0;
            startTime = 0;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    private void extractROI(ImageProxy image) {

        // get ROI dimension
        int width = image.getWidth();
        int height = image.getHeight();

        // define 20x20 ROI
        int roiSize = 20;

        // relative ROI positions
        float[][] positions = {
                {0.50f, 0.50f}, // ROI 1, center
                {0.15f, 0.15f}, // ROI 2, top-left
                {0.85f, 0.15f}, // ROI 3, top-right
                {0.15f, 0.85f}, // ROI 4, bottom-left
                {0.85f, 0.85f}  // ROI 5, bottom-right
        };

        // array holds one weighted average for each ROI
        double[] averages = new double[5];

        // get image RGBA data
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();

        // get memory layout
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        // process each ROI
        for (int i = 0; i < 5; i++){

            // calculate centers and start/end locations
            int centerX = Math.round(positions[i][0] * width);
            int centerY = Math.round(positions[i][1] * height);
            int xStart = centerX - roiSize / 2;
            int yStart = centerY - roiSize / 2;
            int xEnd = xStart + roiSize;
            int yEnd = yStart + roiSize;

            // initialize sums. not using blue since we are not using blue in our weightedAvg
            double redSum = 0;
            double greenSum = 0;

            // calculate the average in this ROI
            for (int y = yStart; y < yEnd; y++){
                for (int x = xStart; x < xEnd; x++){

                    // find the pixel in the ROI
                    int pixelOffset = y * rowStride + x * pixelStride;

                    // RGBA byte ordering
                    int r = buffer.get(pixelOffset) & 0xFF;
                    int g = buffer.get(pixelOffset + 1) & 0xFF;

                    redSum += r;
                    greenSum += g;
                }
            }

            // get total ROI pixel size
            int pixels = roiSize * roiSize;

            // calculate averages
            double redAvg = redSum / pixels;
            double greenAvg = greenSum / pixels;

            // write weighted average onto averages array
            // research paper we based this off of uses 0.67R + 0.33G weights
            averages[i] = 0.67 * redAvg + 0.33 * greenAvg;
        }

        // send all the averages to the plot
        runOnUiThread(() -> {
            intensityPlot.addSample(averages);
        });

        // Right now C++ implementation only uses center ROI
        // CHANGE THIS LATER TO FULL 5 ROI INPUT ONCE CENTER ROI FUNCTIONALITY WORKS
        addSample(averages[0]);
    }
}
