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

package com.projecttango.tangoutils.renderables;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

/**
 * {@link Renderable} OpenGL showing a PointCloud obtained from Tango XyzIj
 * data. The point count can vary over as the information is updated.
 */
public class PointCloud extends Renderable {
    private static final String TAG = "PointCloud";

    private static final int MAX_POINTS = 1000000;

    private static final int COORDS_PER_VERTEX = 3;
    private static final int BYTES_PER_FLOAT = 4;
    private static final int POINT_TO_XYZ = 3;

    private FloatBuffer vertexBuffer;
    private int mPosHandle;
    private int mMVPMatrixHandle;

    private final int shaderProgram;
    private static final String sVertexShaderCode = "uniform mat4 uMVPMatrix;"
            + "attribute vec4 vPosition; void main() {gl_PointSize = 1.0;"
            + "  gl_Position = uMVPMatrix * vPosition; }";
    private static final String sFragmentShaderCode = "precision mediump float;"
            + "uniform vec4 vColor; void main() { gl_FragColor = vec4(0.8,0.8,0.8,1.0); }";

    // previous shader code:
    /*
    private static final String sVertexShaderCode = "uniform mat4 uMVPMatrix;"
            + "attribute vec4 vPosition;varying vec4 vColor;void main() {gl_PointSize = 2.0;"
            + "  gl_Position = uMVPMatrix * vPosition;  vColor = vPosition;}";
    private static final String sFragmentShaderCode = "precision mediump float;"
            + "varying vec4 vColor;void main() {  gl_FragColor = vec4(vColor);}";
    */

    private int totalPointCount = 0;

    public PointCloud() {
        int vertexShader = RenderUtils.loadShader(GLES20.GL_VERTEX_SHADER, sVertexShaderCode);
        int fragShader = RenderUtils.loadShader(GLES20.GL_FRAGMENT_SHADER, sFragmentShaderCode);
        shaderProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(shaderProgram, vertexShader);
        GLES20.glAttachShader(shaderProgram, fragShader);
        GLES20.glLinkProgram(shaderProgram);

        Matrix.setIdentityM(getModelMatrix(), 0);
        vertexBuffer = ByteBuffer.allocateDirect(MAX_POINTS * BYTES_PER_FLOAT * POINT_TO_XYZ)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public void addPoints(byte[] byteArray, int pointCount, float[] modelMatrix) {
        if (totalPointCount + pointCount > MAX_POINTS) {
            return;
        }
        float[] pointVec = new float[4];
        float[] outVec = new float[4];
        vertexBuffer.position(totalPointCount * POINT_TO_XYZ);
        FloatBuffer newArray = ByteBuffer.wrap(byteArray)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        newArray.position(0);
        for (int i = 0; i < pointCount; i++) {
            pointVec[0] = newArray.get();
            pointVec[1] = newArray.get();
            pointVec[2] = newArray.get();
            pointVec[3] = 1;
            Matrix.multiplyMV(outVec, 0, modelMatrix, 0, pointVec, 0);
            vertexBuffer.put(outVec[0]);
            vertexBuffer.put(outVec[1]);
            vertexBuffer.put(outVec[2]);
        }
        totalPointCount += pointCount;
    }

    public synchronized void clear() {
        totalPointCount = 0;
    }

    @Override
    public synchronized void draw(float[] viewMatrix, float[] projectionMatrix) {
        if (totalPointCount == 0) {
            return;
        }
        vertexBuffer.position(0);
        GLES20.glUseProgram(shaderProgram);
        updateMvpMatrix(viewMatrix, projectionMatrix);
        mPosHandle = GLES20.glGetAttribLocation(shaderProgram, "vPosition");
        GLES20.glVertexAttribPointer(mPosHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(mPosHandle);
        mMVPMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix");
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, getMvpMatrix(), 0);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, totalPointCount);
    }

    public int getPointCount() {
        return totalPointCount;
    }

    public void writeToStream(OutputStreamWriter writer) throws IOException {
        StringBuilder str = new StringBuilder();
        FloatBuffer scopeBuf = vertexBuffer.duplicate();
        scopeBuf.position(0);
        for (int i = 0; i < totalPointCount; i++) {
            str.append(scopeBuf.get());
            str.append(',');
            str.append(scopeBuf.get());
            str.append(',');
            str.append(scopeBuf.get());
            str.append('\n');
            if (i % 10000 == 0) {
                writer.write(str.toString());
                str = new StringBuilder();
            }
        }
        if (str.length() > 0) {
            writer.write(str.toString());
        }
    }

}
