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

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.projecttango.tangoutils.Renderer;
import com.projecttango.tangoutils.renderables.CameraFrustum;
import com.projecttango.tangoutils.renderables.CameraFrustumAndAxis;
import com.projecttango.tangoutils.renderables.Grid;
import com.projecttango.tangoutils.renderables.PointCloud;
import com.projecttango.tangoutils.renderables.RenderUtils;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class PCRenderer extends Renderer implements GLSurfaceView.Renderer {
    private static final String TAG = "PCRenderer";

    public static final int STATE_REALTIME = 0;
    public static final int STATE_ACCUMULATING = 1;
    public static final int STATE_STOPPED = 2;

    private PointCloud pointCloud;
    private int state;

    private Grid mGrid;
    private CameraFrustumAndAxis mCameraFrustumAndAxis;

    public synchronized void setState(int state) {
        this.state = state;
        if (state == STATE_ACCUMULATING) {
            pointCloud.clear();
        } else if (state == STATE_REALTIME) {
            pointCloud.clear();
        }
    }

    public synchronized int getState() {
        return state;
    }

    public PCRenderer() {
        state = STATE_REALTIME;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        mGrid = new Grid();
        mCameraFrustumAndAxis = new CameraFrustumAndAxis();
        pointCloud = new PointCloud();
        Matrix.setIdentityM(mViewMatrix, 0);
        Matrix.setLookAtM(mViewMatrix, 0, 5f, 5f, 5f, 0f, 0f, 0f, 0f, 1f, 0f);
        mCameraFrustumAndAxis.setModelMatrix(getModelMatCalculator().getModelMatrix());
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        mCameraAspect = (float) width / height;
        Matrix.perspectiveM(mProjectionMatrix, 0, CAMERA_FOV, mCameraAspect,
                CAMERA_NEAR, CAMERA_FAR);
    }

    @Override
    public synchronized void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        mGrid.draw(mViewMatrix, mProjectionMatrix);
        pointCloud.draw(mViewMatrix, mProjectionMatrix);
        mCameraFrustumAndAxis.draw(mViewMatrix, mProjectionMatrix);
    }

    public synchronized void addPointCloud(byte[] byteArray, int pointCount,
                                                 float[] translation, float[] rotation) {
        if (state == STATE_STOPPED) {
            return;
        }
        if (state == STATE_REALTIME) {
            pointCloud.clear();
        }
        getModelMatCalculator().updatePointCloudModelMatrix(translation, rotation);
        pointCloud.addPoints(byteArray, pointCount, getModelMatCalculator().getPointCloudModelMatrixCopy());
    }

    public int getPointCount() {
        return pointCloud.getPointCount();
    }

    public interface SaveDataListener {
        void onDataSaved(String fileName);
        void onSaveFailed(Throwable e);
    }

    public void saveData(Context context, SaveDataListener listener) {
        new SaveDataTask(context, listener).execute();
    }

    private class SaveDataTask extends AsyncTask<String, Void, String> {
        SaveDataListener listener;
        Context context;
        public SaveDataTask(Context context, SaveDataListener listener) {
            this.context = context;
            this.listener = listener;
        }
        protected String doInBackground(String... params) {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String fileName = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    + "/" + formatter.format(new Date()) + ".txt";
            Log.d(TAG, "Saving file: " + fileName);
            try {
                File f = new File(fileName);
                FileOutputStream outputStream = new FileOutputStream(f);
                OutputStreamWriter writer = new OutputStreamWriter(outputStream);
                pointCloud.writeToStream(writer);
                writer.close();
                outputStream.close();
                return fileName;
            } catch (Exception e) {
                e.printStackTrace();
                listener.onSaveFailed(e);
            }
            return null;
        }
        protected void onPostExecute(String result) {
            if (result == null) {
                return;
            }
            listener.onDataSaved(result);
        }
    }
}
