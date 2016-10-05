package com.amosyuen.videorecorder.video;


import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Build.VERSION_CODES;
import android.support.annotation.ColorInt;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.Pair;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import com.amosyuen.videorecorder.util.RecorderListener;
import com.amosyuen.videorecorder.util.VideoUtil;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static android.R.attr.width;

/**
 * View that records only video frames (no audio).
 */
public class VideoFrameRecorderView extends SurfaceView implements
        SurfaceHolder.Callback, Camera.PreviewCallback, View.OnAttachStateChangeListener {

    protected static final String LOG_TAG = "VideoFrameRecorderView";

    // User specified params
    protected VideoFrameRecorderParams mParams;
    protected VideoFrameRecorderInterface mVideoRecorder;
    protected RecorderListener mRecorderListener;

    // Params
    protected DeviceOrientationEventListener mOrientationListener;
    /** The estimated time in nanoseconds between frames */
    protected long mFrameTimeNanos;
    protected int mCameraId;
    protected Camera mCamera;
    protected Camera.Size mPreviewSize;
    protected ImageSize mScaledPreviewSize;
    protected ImageSize mScaledTargetSize;
    @ColorInt
    protected int mBlackColor;
    protected volatile OpenCameraTask mOpenCameraTask;

    // Recording state
    protected volatile boolean mIsRunning;
    protected volatile boolean mIsRecording;
    protected volatile boolean mIsPortrait;
    protected volatile int mDeviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    /** Recording length in nanoseconds */
    protected volatile long mRecordingLengthNanos;
    /** System time in nanoseconds when the recording was last updated */
    protected volatile long mLastUpdateTimeNanos;

    public VideoFrameRecorderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VideoFrameRecorderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @RequiresApi(api = VERSION_CODES.LOLLIPOP)
    public VideoFrameRecorderView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    protected void init() {
        mOrientationListener = new DeviceOrientationEventListener(getContext());

        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        mBlackColor = ContextCompat.getColor(getContext(), android.R.color.black);

        addOnAttachStateChangeListener(this);
        setWillNotDraw(false);
    }

    public RecorderListener getRecorderListener() {
        return mRecorderListener;
    }

    public void setRecorderListener(RecorderListener recorderListener) {
        this.mRecorderListener = recorderListener;
    }

    @Nullable
    public VideoFrameRecorderParams getParams() {
        return mParams;
    }

    @Nullable
    public VideoFrameRecorderInterface getVideoRecorder() {
        return mVideoRecorder;
    }

    @Nullable
    public Camera getCamera() {
        return mCamera;
    }

    @Nullable
    public Camera.Size getPreviewSize() {
        return mPreviewSize;
    }

    /**
     * Opens a camera with the specified facing if not already open.
     * @return Whether the camera open task was started or is done.
     */
    public boolean openCamera(
            VideoFrameRecorderParams params, VideoFrameRecorderInterface videoRecorder) {
        Log.d(LOG_TAG, "Open camera params " + params);
        if (mParams != null && mParams.equals(params) && mCamera != null) {
            return true;
        }
        if (mOpenCameraTask == null) {
            closeCamera();
            Log.d(LOG_TAG, "Open camera");
            mParams = params;
            mFrameTimeNanos = TimeUnit.SECONDS.toNanos(1L) / mParams.getVideoFrameRate();
            Log.d(LOG_TAG, "Set params " + mParams);
            mVideoRecorder = videoRecorder;
            mIsRunning = true;
            if (mRecorderListener != null) {
                mRecorderListener.onRecorderInitializing(this);
            }
            mOpenCameraTask = new OpenCameraTask();
            mOpenCameraTask.execute();
            return true;
        }
        return false;
    }

    public void closeCamera() {
        stopRecording();
        if (mCamera != null) {
            Log.d(LOG_TAG, "Close camera");
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            mVideoRecorder = null;
            invalidate();
        }
    }

    public boolean isRunning() {
        return mCamera != null;
    }

    public boolean isRecording() {
        return mIsRecording;
    }

    public void startRecording() {
        if (!mIsRecording) {
            Log.d(LOG_TAG, "Start recording with orientation " + mDeviceOrientation);
            mIsRecording = true;
            mIsPortrait = mDeviceOrientation == 0 || mDeviceOrientation == 180;
            mLastUpdateTimeNanos = 0;
            if (mRecorderListener != null) {
                mRecorderListener.onRecorderStart(this);
            }
        }
    }

    public void stopRecording() {
        if (mIsRecording) {
            Log.d(LOG_TAG, "Stop recording");
            mIsRecording = false;
            if (mRecorderListener != null) {
                mRecorderListener.onRecorderStop(this);
            }
        }
    }

    public void clearData() {
        mRecordingLengthNanos = 0;
        mLastUpdateTimeNanos = 0;
        if (mVideoRecorder != null) {
            mVideoRecorder.clear();
        }
    }

    public long getRecordingLengthNanos() {
        return mRecordingLengthNanos;
    }

    public long getLastUpdateTimeNanos() {
        return mLastUpdateTimeNanos;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        updateSurfaceLayout();
    }

    private void updateSurfaceLayout() {
        Log.v(LOG_TAG, "updateSurfaceLayout");
        ImageSize parentSize;
        if (getParent() instanceof View) {
            View parent = (View) getParent();
            parentSize = new ImageSize(parent.getWidth(), parent.getHeight());
        } else {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            parentSize = new ImageSize(metrics.widthPixels, metrics.heightPixels);
        }
        if (!parentSize.areDimensionsDefined()) {
            return;
        }

        ImageSize targetSize = mParams == null
                ? new ImageSize()
                : new ImageSize(mParams.getVideoWidth(), mParams.getVideoHeight());
        // If there is no preview size calculate it from the targetSize or the parentSize
        ImageSize previewSize = mPreviewSize == null
                ? new ImageSize(parentSize.width, targetSize.areDimensionsDefined()
                        ? (int)(parentSize.width / targetSize.getAspectRatio()) : parentSize.height)
                : new ImageSize(mPreviewSize.width, mPreviewSize.height);
        if (targetSize.isAtLeastOneDimensionDefined()) {
            targetSize.calculateUndefinedDimensions(previewSize);
            previewSize.scale(targetSize, mParams.getVideoScaleType(), mParams.canUpscaleVideo());
        } else {
            targetSize = previewSize.clone();
        }
        Log.v(LOG_TAG, String.format("Parent %s", parentSize));
        Log.v(LOG_TAG, String.format("Target %s", targetSize));
        Log.v(LOG_TAG, String.format("Preview %s", previewSize));

        // Scale the target to fit within the parent view
        mScaledTargetSize = targetSize.clone();
        mScaledTargetSize.scaleToFit(parentSize, true);
        // Scale the previewSize by the same amount that target size was scaled
        mScaledPreviewSize = previewSize;
        mScaledPreviewSize.width =
                mScaledPreviewSize.width * mScaledTargetSize.width / targetSize.width;
        mScaledPreviewSize.height =
                mScaledPreviewSize.height * mScaledTargetSize.height / targetSize.height;
        Log.v(LOG_TAG, String.format("Scaled Target %s", mScaledTargetSize));
        Log.v(LOG_TAG, String.format("Scaled Preview %s", mScaledPreviewSize));

        setMeasuredDimension(mScaledPreviewSize.width, mScaledPreviewSize.height);
        invalidate();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.v(LOG_TAG, "Surface created");
        if (mCamera != null) {
            setCameraPreviewDisplay();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.v(LOG_TAG, "Surface changed");
        if (mCamera != null) {
            setupCameraSurfaceParams();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v(LOG_TAG, "Surface destroyed");
        if (mCamera != null) {
            mCamera.stopPreview();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (mScaledTargetSize != null) {
            // Clip the drawable bounds to the target size
            float targetMarginX = Math.max(0, 0.5f * (canvas.getWidth() - mScaledTargetSize.width));
            float targetMarginY = Math.max(0, 0.5f * (canvas.getHeight() - mScaledTargetSize.height));
            canvas.clipRect(
                    targetMarginX,
                    targetMarginY,
                    targetMarginX + mScaledTargetSize.width,
                    targetMarginY + mScaledTargetSize.height);
        }

        canvas.drawColor(mBlackColor);

        if (mCamera != null) {
            super.draw(canvas);
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        try {
            if (mIsRecording) {
                updateTimestampNanos();

                VideoFrameData frameData = new VideoFrameData(
                        data, mRecordingLengthNanos, mIsPortrait,
                        mParams.getVideoCameraFacing() == Camera.CameraInfo.CAMERA_FACING_FRONT);
                mVideoRecorder.recordFrame(frameData);
                if (mRecorderListener != null) {
                    mRecorderListener.onRecorderFrameRecorded(this, mRecordingLengthNanos);
                }
            }
            if (mCamera != null) {
                mCamera.addCallbackBuffer(mVideoRecorder.getFrameBuffer());
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error recording frame", e);
            if (mRecorderListener != null) {
                mRecorderListener.onRecorderError(this, e);
            }
        }
    }

    protected void updateTimestampNanos() {
        long currNanos = System.nanoTime();
        if (mLastUpdateTimeNanos == 0) {
            if (mRecordingLengthNanos > 0) {
                mRecordingLengthNanos += mFrameTimeNanos;
            }
        } else {
            mRecordingLengthNanos += currNanos - mLastUpdateTimeNanos;
        }
        mLastUpdateTimeNanos = currNanos;
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        mOrientationListener.enable();
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        mOrientationListener.disable();
        closeCamera();
    }

    protected void setCameraPreviewDisplay() {
        try {
            mCamera.setPreviewDisplay(getHolder());
        } catch (IOException exception) {
            Log.e(LOG_TAG, "Error setting camera preview display", exception);
            if (mRecorderListener != null) {
                mRecorderListener.onRecorderError(this, exception);
            }
        }
    }

    protected void setupCameraSurfaceParams() {
        try {
            mCamera.stopPreview();

            // Set preview size and framerate
            Camera.Size size = VideoUtil.getBestResolution(
                    mCamera, new ImageSize(mParams.getVideoWidth(), mParams.getVideoHeight()));
            // If the preview size changed, request a layout, which will resize the view to the
            // preview aspect ratio {
            mPreviewSize = size;
            Log.v(LOG_TAG, "Camera preview width="
                    + mPreviewSize.width + " height=" + mPreviewSize.height);
            updateSurfaceLayout();
            Camera.Parameters cameraParameters = mCamera.getParameters();
            cameraParameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
            cameraParameters.setPreviewFrameRate(mParams.getVideoFrameRate());

            // Camera focus mode
            List<String> focusModes = cameraParameters.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
            }

            // Initialize buffer
            int frameSizeBytes = mPreviewSize.width * mPreviewSize.height
                    * ImageFormat.getBitsPerPixel(cameraParameters.getPreviewFormat()) / 8;
            mVideoRecorder.initializeFrameBuffer(frameSizeBytes);
            mCamera.setPreviewCallbackWithBuffer(this);
            mCamera.addCallbackBuffer(mVideoRecorder.getFrameBuffer());

            // Display orientation
            int orientationDegrees =
                    VideoUtil.determineDisplayOrientation(getContext(), mCameraId);
            Log.v(LOG_TAG, "Camera set orientation to " + orientationDegrees);
            mCamera.setDisplayOrientation(orientationDegrees);

            mCamera.setParameters(cameraParameters);
            mCamera.startPreview();
            mCamera.autoFocus(null); // Must call after starting preview

            Log.v(LOG_TAG, "Camera initialized");
            if (mRecorderListener != null) {
                mRecorderListener.onRecorderReady(this);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error setting camera surface params", e);
            if (mRecorderListener != null) {
                mRecorderListener.onRecorderError(this, e);
            }
        }
    }

    protected class DeviceOrientationEventListener
            extends OrientationEventListener {

        public DeviceOrientationEventListener(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            // We keep the last known orientation. So if the user first orient
            // the camera then point the camera to floor or sky, we still have
            // the correct orientation.
            if (orientation == ORIENTATION_UNKNOWN) {
                return;
            }
            mDeviceOrientation = VideoUtil.roundOrientation(orientation, mDeviceOrientation);
        }
    }

    protected class OpenCameraTask extends AsyncTask<Void, Void, Pair<Integer, Camera>> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected Pair<Integer, Camera> doInBackground(Void[] params) {
            try {
                int cameraId = 0;
                int numberOfCameras = Camera.getNumberOfCameras();
                Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                for (int i = 0; i < numberOfCameras; i++) {
                    Camera.getCameraInfo(i, cameraInfo);
                    if (cameraInfo.facing == mParams.getVideoCameraFacing()) {
                        cameraId = i;
                    }
                }

                Camera camera;
                if (cameraId >= 0) {
                    camera = Camera.open(cameraId);
                } else {
                    camera = Camera.open();
                }
                return Pair.create(cameraId, camera);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error opening camera", e);
                if (mRecorderListener != null) {
                    mRecorderListener.onRecorderError(this, e);
                }
                return null;
            }
        }

        @Override
        protected void onPostExecute(Pair<Integer, Camera> result) {
            // Set the variables on the UI thread
            if (result != null) {
                mCameraId = result.first;
                mCamera = result.second;
                if (!getHolder().isCreating()) {
                    setCameraPreviewDisplay();
                    setupCameraSurfaceParams();
                }
            }
            mOpenCameraTask = null;
        }
    }
}