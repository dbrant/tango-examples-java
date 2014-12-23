/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.projecttango.pointcloudjava;

import java.io.FileInputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;

import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Main Activity class for the Point Cloud Sample. Handles the connection to the
 * {@link Tango} service and propagation of Tango XyzIj data to OpenGL and
 * Layout views. OpenGL rendering logic is delegated to the {@link PCRenderer}
 * class.
 */
public class JPointCloud extends ActionBarActivity implements SurfaceHolder.Callback {

    private static final String TAG = JPointCloud.class.getSimpleName();
    private static int SECS_TO_MILLI = 1000;
    private Tango mTango;
    private TangoConfig mConfig;
    boolean haveMotionPermission = false;
    boolean haveAdfPermission = false;

    private PCRenderer mRenderer;
    private GLSurfaceView mGLView;

    private Surface mSurface;
    SurfaceView cameraSurfaceView;
    SurfaceHolder cameraSurfaceHolder;

    private TextView mTangoEventTextView;
    private TextView mPointCountTextView;
    private TextView mFrequencyTextView;

    private Button startButton;
    private Button realtimeButton;

    private float mXyIjPreviousTimeStamp;
    private float mCurrentTimeStamp;
    private boolean mIsTangoServiceConnected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jpoint_cloud);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        final Toolbar toolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle("");

        mTangoEventTextView = (TextView) findViewById(R.id.tangoevent);
        mPointCountTextView = (TextView) findViewById(R.id.pointCount);
        mFrequencyTextView = (TextView) findViewById(R.id.frameDelta);

        startButton = (Button) findViewById(R.id.start_accumulate_button);
        startButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mRenderer.getState() != PCRenderer.STATE_ACCUMULATING) {
                    mRenderer.setState(PCRenderer.STATE_ACCUMULATING);
                    startButton.setText(getString(R.string.stop));
                } else {
                    mRenderer.setState(PCRenderer.STATE_STOPPED);
                    startButton.setText(getString(R.string.start));
                }
            }
        });
        realtimeButton = (Button) findViewById(R.id.start_realtime_button);
        realtimeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mRenderer.getState() != PCRenderer.STATE_REALTIME) {
                    mRenderer.setState(PCRenderer.STATE_REALTIME);
                }
            }
        });

        cameraSurfaceView = (SurfaceView) findViewById(R.id.cameraView);
        cameraSurfaceView.setZOrderOnTop(true);
        cameraSurfaceView.setZOrderMediaOverlay(true);
        cameraSurfaceHolder = cameraSurfaceView.getHolder();
        cameraSurfaceHolder.addCallback(this);

        mTango = new Tango(this);
        mConfig = mTango.getConfig(TangoConfig.CONFIG_TYPE_CURRENT);
        mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_LEARNINGMODE, true);
        mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_MOTIONTRACKING, true);
        mConfig.putBoolean(TangoConfig.KEY_BOOLEAN_AUTORECOVERY, true);

        mRenderer = new PCRenderer();
        mGLView = (GLSurfaceView) findViewById(R.id.gl_surface_view);
        mGLView.setEGLContextClientVersion(2);
        mGLView.setRenderer(mRenderer);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        mIsTangoServiceConnected = false;
    }

    private void showError(int resId, Throwable e) {
        showError(getString(resId), e);
    }

    private void showError(String errorStr, Throwable e) {
        Toast.makeText(getApplicationContext(), errorStr, Toast.LENGTH_SHORT).show();
        e.printStackTrace();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            mTango.disconnect();
            mIsTangoServiceConnected = false;
        } catch (TangoErrorException e) {
            showError(R.string.TangoError, e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mIsTangoServiceConnected) {
            startActivityForResult(
                    Tango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_MOTION_TRACKING),
                    Tango.TANGO_INTENT_ACTIVITYCODE);
            startActivityForResult(
                    Tango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_ADF_LOAD_SAVE),
                    Tango.TANGO_INTENT_ACTIVITYCODE + 1);
        }
        Log.i(TAG, "onResumed");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Check which request we're responding to
        if (requestCode == Tango.TANGO_INTENT_ACTIVITYCODE) {
            haveMotionPermission = true;
        } else if (requestCode == Tango.TANGO_INTENT_ACTIVITYCODE + 1) {
            haveAdfPermission = true;
        }
        if (haveMotionPermission && haveAdfPermission) {
            Log.i(TAG, "Triggered");
            // Make sure the request was successful
            if (resultCode == RESULT_CANCELED) {
                Toast.makeText(this, R.string.motiontrackingpermission, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            try {
                setTangoListeners();
            } catch (TangoErrorException e) {
                showError(R.string.TangoError, e);
            } catch (SecurityException e) {
                showError(R.string.motiontrackingpermission, e);
            }
            try {
                if (mSurface != null && mSurface.isValid()) {
                    mTango.connectSurface(0, mSurface);
                }
                mTango.connect(mConfig);
                mIsTangoServiceConnected = true;
            } catch (TangoOutOfDateException e) {
                showError(R.string.TangoOutOfDateException, e);
            } catch (TangoErrorException e) {
                showError(R.string.TangoError, e);
            }
            SetUpExtrinsics();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_save_data)
                .setEnabled(mRenderer.getState() == PCRenderer.STATE_STOPPED);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            case R.id.menu_save_data:
                Toast.makeText(getApplicationContext(), getString(R.string.saving_data),
                        Toast.LENGTH_SHORT).show();
                mRenderer.saveData(this, new PCRenderer.SaveDataListener() {
                    @Override
                    public void onDataSaved(String fileName) {
                        Toast.makeText(getApplicationContext(), getString(R.string.save_success)
                                        + fileName, Toast.LENGTH_SHORT).show();
                    }
                    @Override
                    public void onSaveFailed(final Throwable e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showError(e.getMessage(), e);
                            }
                        });
                    }
                });
                return true;
            case R.id.menu_first_person:
                mRenderer.setFirstPersonView();
                return true;
            case R.id.menu_third_person:
                mRenderer.setThirdPersonView();
                return true;
            default:
                break;
        }
        // Handle other action bar items...
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mRenderer.onTouchEvent(event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurface = holder.getSurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mSurface = holder.getSurface();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed...");
        mTango.disconnectSurface(0);
    }

    private void SetUpExtrinsics() {
        // Set device to imu matrix in Model Matrix Calculator.
        TangoPoseData device2IMUPose = new TangoPoseData();
        TangoCoordinateFramePair framePair = new TangoCoordinateFramePair();
        framePair.baseFrame = TangoPoseData.COORDINATE_FRAME_IMU;
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_DEVICE;
        try {
            device2IMUPose = mTango.getPoseAtTime(0.0, framePair);
        } catch (TangoErrorException e) {
            showError(R.string.TangoError, e);
        }
        mRenderer.getModelMatCalculator().SetDevice2IMUMatrix(
                device2IMUPose.getTranslationAsFloats(),
                device2IMUPose.getRotationAsFloats());

        // Set color camera to imu matrix in Model Matrix Calculator.
        TangoPoseData color2IMUPose = new TangoPoseData();

        framePair.baseFrame = TangoPoseData.COORDINATE_FRAME_IMU;
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR;
        try {
            color2IMUPose = mTango.getPoseAtTime(0.0, framePair);
        } catch (TangoErrorException e) {
            showError(R.string.TangoError, e);
        }
        mRenderer.getModelMatCalculator().SetColorCamera2IMUMatrix(
                color2IMUPose.getTranslationAsFloats(),
                color2IMUPose.getRotationAsFloats());
    }

    private void setTangoListeners() {
        // Configure the Tango coordinate frame pair
        final ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        // Listen for new Tango data
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {

            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                if (pose.baseFrame == TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE) {

                } else if (pose.baseFrame == TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION) {
                    mRenderer.getModelMatCalculator().updateModelMatrix(
                            pose.getTranslationAsFloats(),
                            pose.getRotationAsFloats());
                    mRenderer.updateViewMatrix();
                    mGLView.requestRender();
                }
            }

            @Override
            public void onXyzIjAvailable(final TangoXyzIjData xyzIj) {
                mCurrentTimeStamp = (float) xyzIj.timestamp;
                final float frameDelta = (mCurrentTimeStamp - mXyIjPreviousTimeStamp)
                        * SECS_TO_MILLI;
                mXyIjPreviousTimeStamp = mCurrentTimeStamp;
                byte[] buffer = new byte[xyzIj.xyzCount * 3 * 4];
                FileInputStream fileStream = new FileInputStream(
                        xyzIj.xyzParcelFileDescriptor.getFileDescriptor());
                try {
                    fileStream.read(buffer, xyzIj.xyzParcelFileDescriptorOffset, buffer.length);
                    fileStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {

                    TangoPoseData pointCloudPose = mTango.getPoseAtTime(
                            mCurrentTimeStamp, framePairs.get(0));

                    mRenderer.addPointCloud(buffer, xyzIj.xyzCount,
                            pointCloudPose.getTranslationAsFloats(),
                            pointCloudPose.getRotationAsFloats());

                } catch (TangoErrorException e) {
                    showError(R.string.TangoError, e);
                } catch (TangoInvalidException e) {
                    showError(R.string.TangoError, e);
                }

                // Must run UI changes on the UI thread. Running in the Tango
                // service thread will result in an error.
                runOnUiThread(new Runnable() {
                    DecimalFormat threeDec = new DecimalFormat("0.000");

                    @Override
                    public void run() {
                        // Display number of points in the point cloud
                        mPointCountTextView.setText(Integer.toString(mRenderer.getPointCount()));
                        mFrequencyTextView.setText("" + threeDec.format(frameDelta));
                    }
                });
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mTangoEventTextView.setText(event.eventKey + ": " + event.eventValue);
                    }
                });
            }
        });
    }
}
