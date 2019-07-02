package com.kaj.highfpscamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private final static String TAG = "HIGH_FPS_CAMERA";
    List<String> mCameraList;
    int mCameraIdx = 0;
    CameraManager mCameraManager;
    CameraDevice mCameraDevice;
    Size imageDimension;
    TextureView mPreview;
    Range fps;

    protected CameraConstrainedHighSpeedCaptureSession cameraCaptureSessions;
    protected CaptureRequest.Builder captureRequestBuilder;

    /**
     * The required permissions
     */
    private final static String[] PERMISSIONS = {
            Manifest.permission.CAMERA
    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    private Handler mBackgroundHandler;

    private List<Surface> mSurfaces;

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            // This method is called when the camera is opened.
            mCameraDevice = cameraDevice;

            SurfaceTexture texture = mPreview.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            mSurfaces = Arrays.asList(surface);

            try {
                captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                captureRequestBuilder.addTarget(surface);

                cameraDevice.createConstrainedHighSpeedCaptureSession(mSurfaces, new CameraConstrainedHighSpeedCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        // The camera is already closed
                        if (null == mCameraDevice) {
                            return;
                        }

                        cameraCaptureSessions = (CameraConstrainedHighSpeedCaptureSession)cameraCaptureSession;
                        updatePreview();
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        Log.e(TAG, "Failed to configure camera.");
                    }
                }, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            Log.e(TAG, "Received camera device error: " + error);
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mCameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        mPreview = findViewById(R.id.preview);
    }

    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

        if (mPreview.isAvailable()) {
            openCamera();
        } else {
            mPreview.setSurfaceTextureListener(textureListener);
        }

        for (String permission : PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(PERMISSIONS, 0);
                return;
            }
        }

        mCameraList = getCameras();
        if(mCameraList.isEmpty()){
            Toast.makeText(this, "Cannot find a compatible camera", Toast.LENGTH_LONG).show();
            return;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                finish(); // Fatal error, kill the app
            }
        }
    }

    @Override
    protected void onDestroy(){
        stopBackgroundThread();
        mCameraDevice.close();

        super.onDestroy();
    }

    /**
     * Starts a background thread and its Handler.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its Handler
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    private List<String> getCameras(){
        List<String> cameraList = new ArrayList<>();
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing.equals(CameraCharacteristics.LENS_FACING_BACK)) {
                    CameraCharacteristics cc = mCameraManager.getCameraCharacteristics(cameraId);
                    for(int capability: cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)){
                        if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO){
                            cameraList.add(cameraId);
                        }
                    }
                }
            }
        } catch (CameraAccessException | NullPointerException e) {
            e.printStackTrace();
        }
        return cameraList;
    }

    private void openCamera(){
        try {
            String cameraId = mCameraList.get(mCameraIdx);
            CameraCharacteristics cc = mCameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getHighSpeedVideoSizes()[0];
            fps = map.getHighSpeedVideoFpsRangesFor(imageDimension)[0];

            mCameraManager.openCamera(cameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException | SecurityException e){
            e.printStackTrace();
        }
    }

    protected void updatePreview() {
        if(null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
        try {
            cameraCaptureSessions.setRepeatingBurst( cameraCaptureSessions.createHighSpeedRequestList(captureRequestBuilder.build()), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
}
