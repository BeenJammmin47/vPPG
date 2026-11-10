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

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.common.util.concurrent.ListenableFuture;

public class MainActivity extends AppCompatActivity {

    // Loads the C++ library once when this class is first used.
    static {
        System.loadLibrary("ece420_vppg");
    }

    // JNI function implemented in C++.
    public native int testNative();

    // Views we need to touch from code (found by their android:id in the XML layout).
    private PreviewView previewView; // camera feed
    private TextView permissionText; // get camera permission
    private SwitchMaterial flashlightSwitch; // flash switch
    private SwitchMaterial exposureSwitch; // exposure switch

    // The bound camera. Null until camera is ready.
    private Camera camera;

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

    // Starts CameraX and binds the back camera to this activity's lifecycle.
    // This is the Java form of Compose's DisposableEffect camera setup.
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(
                        this,                                 // the activity is the LifecycleOwner
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview);

                // Now that the camera exists, enable the controls (the switches
                // started disabled in the XML, matching the Compose `enabled = ...`).
                flashlightSwitch.setEnabled(camera.getCameraInfo().hasFlashUnit());
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
}
