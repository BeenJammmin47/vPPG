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
}
