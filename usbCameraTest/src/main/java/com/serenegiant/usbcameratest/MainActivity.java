/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.usbcameratest;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.arcsoft.face.ActiveFileInfo;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.model.ActiveDeviceInfo;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.IButtonCallback;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.IStatusCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.SimpleUVCCameraTextureView;

import java.nio.ByteBuffer;
import java.util.List;

import cn.zhut.facelib.faceserver.FaceServer;
import cn.zhut.facelib.util.ConfigUtil;
import cn.zhut.facelib.util.ErrorCodeUtil;
import cn.zhut.facelib.util.FaceRectTransformer;
import cn.zhut.facelib.util.face.constants.LivenessType;
import cn.zhut.facelib.util.face.model.FacePreviewInfo;
import cn.zhut.facelib.util.face.model.PreviewSize;
import cn.zhut.facelib.widget.FaceRectView;

public final class MainActivity extends AppCompatActivity implements CameraDialog.CameraDialogParent, View.OnClickListener {
	private static final String TAG = MainActivity.class.getSimpleName();

	private final Object mSync = new Object();
    // for accessing USB and USB camera
    private USBMonitor mUSBMonitor;
	private UVCCamera mUVCCamera;
	private SimpleUVCCameraTextureView mUVCCameraView;
	// for open&start / stop&close camera preview
	private ImageButton mCameraButton;
	private Surface mPreviewSurface;
	private Toast mToast;
	private FaceRectView faceRectView;
	private RecognizeViewModel recognizeViewModel;
	private Handler mWorkerHandler;
	private long mWorkerThreadID = -1;
	private LivenessType livenessType;
	private FaceRectTransformer rgbFaceRectTransformer;
	private int buf_sz = 4096;
	private byte[] buf = new byte[buf_sz];

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);


		mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
		
		initView();
		initData();
		bindObserver();
	}


	private void initView() {
		mCameraButton = findViewById(R.id.camera_button);
		mCameraButton.setOnClickListener(this);

		mUVCCameraView = findViewById(R.id.UVCCameraTextureView1);
		mUVCCameraView.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH / (float)UVCCamera.DEFAULT_PREVIEW_HEIGHT);

		faceRectView = findViewById(R.id.view_face_info);
	}

	private void initData() {
		recognizeViewModel = new ViewModelProvider(getViewModelStore(),
				ViewModelProvider.AndroidViewModelFactory.getInstance(getApplication())).get(RecognizeViewModel.class);

		HandlerThread handlerThread = new HandlerThread("worker-handler");
		handlerThread.start();
		mWorkerHandler = new Handler(handlerThread.getLooper());
		mWorkerThreadID = mWorkerHandler.getLooper().getThread().getId();

		// 获取活体检测类型(RGB or IR)
		String livenessTypeStr = ConfigUtil.getLivenessDetectType(this);
		if (livenessTypeStr.equals((getString(R.string.value_liveness_type_rgb)))) {
			livenessType = LivenessType.RGB;
		} else if (livenessTypeStr.equals(getString(R.string.value_liveness_type_ir))) {
			livenessType = LivenessType.IR;
		} else {
			livenessType = null;
		}
		recognizeViewModel.setLiveType(livenessType);

/*		Camera.Size previewSizeRgb = camera.getParameters().getPreviewSize();
		ViewGroup.LayoutParams layoutParams = adjustPreviewViewSize(mUVCCameraView,
				mUVCCameraView, faceRectView, previewSizeRgb, displayOrientation, 1.0f);
		rgbFaceRectTransformer = new FaceRectTransformer(previewSizeRgb.width, previewSizeRgb.height,
				layoutParams.width, layoutParams.height, displayOrientation, cameraId, isMirror,
				ConfigUtil.isDrawRgbRectHorizontalMirror(this),
				ConfigUtil.isDrawRgbRectVerticalMirror(this));
		recognizeViewModel.setRgbFaceRectTransformer(rgbFaceRectTransformer);*/

		// 人脸引擎未激活
		if(FaceEngine.getActiveFileInfo(this, new ActiveFileInfo()) != ErrorInfo.MOK){
			recognizeViewModel.activeOnline(this, ConfigUtil.getActiveKey(this), ConfigUtil.getAppId(this), ConfigUtil.getSdkKey(this));
		}

		recognizeViewModel.init();
	}

	private void bindObserver() {
		// 引擎激活结果回调
		recognizeViewModel.getActiveResult().observe(this, result -> {
			String notice;
			switch (result) {
				case ErrorInfo.MOK:
					notice = getString(R.string.active_success);
					break;
				case ErrorInfo.MERR_ASF_ALREADY_ACTIVATED:
					notice = getString(R.string.already_activated);
					break;
				case ErrorInfo.MERR_ASF_ACTIVEKEY_ACTIVEKEY_ACTIVATED:
					notice = getString(R.string.active_key_activated);
					break;
				default:
					notice = getString(R.string.active_failed, result, ErrorCodeUtil.arcFaceErrorCodeToFieldName(result));
					break;
			}
			Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
		});

		// ft,fr,fl引擎初始化状态回调
		recognizeViewModel.getFtInitCode().observe(this, ftInitCode -> {
			if (ftInitCode != ErrorInfo.MOK) {
				String error = getString(R.string.specific_engine_init_failed, "ftEngine",
						ftInitCode, ErrorCodeUtil.arcFaceErrorCodeToFieldName(ftInitCode));
				Log.i(TAG, "initEngine: " + error);
			}
		});
		recognizeViewModel.getFrInitCode().observe(this, frInitCode -> {
			if (frInitCode != ErrorInfo.MOK) {
				String error = getString(R.string.specific_engine_init_failed, "frEngine",
						frInitCode, ErrorCodeUtil.arcFaceErrorCodeToFieldName(frInitCode));
				Log.i(TAG, "initEngine: " + error);
			}
		});
		recognizeViewModel.getFlInitCode().observe(this, flInitCode -> {
			if (flInitCode != ErrorInfo.MOK) {
				String error = getString(R.string.specific_engine_init_failed, "flEngine",
						flInitCode, ErrorCodeUtil.arcFaceErrorCodeToFieldName(flInitCode));
				Log.i(TAG, "initEngine: " + error);
			}
		});
	}

	/**
	 * 调整View的宽高，使2个预览同时显示
	 *
	 * @param previewView        显示预览数据的view
	 * @param faceRectView       画框的view
	 * @param previewSize        预览大小
	 * @param displayOrientation 相机旋转角度
	 * @return 调整后的LayoutParams
	 */
	private ViewGroup.LayoutParams adjustPreviewViewSize(View rgbPreview, View previewView, FaceRectView faceRectView, Camera.Size previewSize, int displayOrientation, float scale) {
		ViewGroup.LayoutParams layoutParams = previewView.getLayoutParams();
		int measuredWidth = previewView.getMeasuredWidth();
		int measuredHeight = previewView.getMeasuredHeight();
		float ratio = ((float) previewSize.height) / (float) previewSize.width;
		if (ratio > 1) {
			ratio = 1 / ratio;
		}
		if (displayOrientation % 180 == 0) {
			layoutParams.width = measuredWidth;
			layoutParams.height = (int) (measuredWidth * ratio);
		} else {
			layoutParams.height = measuredHeight;
			layoutParams.width = (int) (measuredHeight * ratio);
		}
		if (scale < 1f) {
			ViewGroup.LayoutParams rgbParam = rgbPreview.getLayoutParams();
			layoutParams.width = (int) (rgbParam.width * scale);
			layoutParams.height = (int) (rgbParam.height * scale);
		} else {
			layoutParams.width *= scale;
			layoutParams.height *= scale;
		}

		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);

		if (layoutParams.width >= metrics.widthPixels) {
			float viewRatio = layoutParams.width / ((float) metrics.widthPixels);
			layoutParams.width /= viewRatio;
			layoutParams.height /= viewRatio;
		}
		if (layoutParams.height >= metrics.heightPixels) {
			float viewRatio = layoutParams.height / ((float) metrics.heightPixels);
			layoutParams.width /= viewRatio;
			layoutParams.height /= viewRatio;
		}

		previewView.setLayoutParams(layoutParams);
		faceRectView.setLayoutParams(layoutParams);
		return layoutParams;
	}

	@Override
	protected void onStart() {
		super.onStart();
		mUSBMonitor.register();
		synchronized (mSync) {
			if (mUVCCamera != null) {
				mUVCCamera.startPreview();
			}
		}
	}

	@Override
	protected void onStop() {
		synchronized (mSync) {
			if (mUVCCamera != null) {
				mUVCCamera.stopPreview();
			}
			if (mUSBMonitor != null) {
				mUSBMonitor.unregister();
			}
		}
		super.onStop();
	}

	@Override
	protected void onDestroy() {
	    recognizeViewModel.destroy();
		synchronized (mSync) {
			releaseCamera();
			if (mToast != null) {
				mToast.cancel();
				mToast = null;
			}
			if (mUSBMonitor != null) {
				mUSBMonitor.destroy();
				mUSBMonitor = null;
			}
		}
		mUVCCameraView = null;
		mCameraButton = null;
		super.onDestroy();
	}

	@Override
	public void onClick(View v) {
	    int id = v.getId();
	    if(id == R.id.camera_button){	// 选择摄像头
			synchronized (mSync) {
				if (mUVCCamera == null) {
					CameraDialog.showDialog(MainActivity.this);
				} else {
					releaseCamera();
				}
			}
		}
	}


	private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
		@Override
		public void onAttach(final UsbDevice device) {
			Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
			releaseCamera();
			queueEvent(new Runnable() {
				@Override
				public void run() {
					final UVCCamera camera = new UVCCamera();
					camera.open(ctrlBlock);
					camera.setStatusCallback(new IStatusCallback() {
						@Override
						public void onStatus(final int statusClass, final int event, final int selector,
											 final int statusAttribute, final ByteBuffer data) {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									final Toast toast = Toast.makeText(MainActivity.this, "onStatus(statusClass=" + statusClass
											+ "; " +
											"event=" + event + "; " +
											"selector=" + selector + "; " +
											"statusAttribute=" + statusAttribute + "; " +
											"data=...)", Toast.LENGTH_SHORT);
									synchronized (mSync) {
										if (mToast != null) {
											mToast.cancel();
										}
										toast.show();
										mToast = toast;
									}
								}
							});
						}
					});
					camera.setButtonCallback(new IButtonCallback() {
						@Override
						public void onButton(final int button, final int state) {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									final Toast toast = Toast.makeText(MainActivity.this, "onButton(button=" + button + "; " +
											"state=" + state + ")", Toast.LENGTH_SHORT);
									synchronized (mSync) {
										if (mToast != null) {
											mToast.cancel();
										}
										mToast = toast;
										toast.show();
									}
								}
							});
						}
					});
//					camera.setPreviewTexture(camera.getSurfaceTexture());
					if (mPreviewSurface != null) {
						mPreviewSurface.release();
						mPreviewSurface = null;
					}
					try {
						camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
//						camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_YUYV);
					} catch (final IllegalArgumentException e) {
						// fallback to YUV mode
						try {
							camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
						} catch (final IllegalArgumentException e1) {
							camera.destroy();
							return;
						}
					}
					Size size = camera.getPreviewSize();
					PreviewSize previewSize = new PreviewSize(size.width, size.height);
					recognizeViewModel.onRgbCameraOpened(previewSize);
					final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
					if (st != null) {
						mPreviewSurface = new Surface(st);
						camera.setPreviewDisplay(mPreviewSurface);
//						camera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_RGB565/*UVCCamera.PIXEL_FORMAT_NV21*/);
						camera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);
						camera.startPreview();
					}
					synchronized (mSync) {
						mUVCCamera = camera;
					}
				}
			}, 0);
		}

		@Override
		public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
			// XXX you should check whether the coming device equal to camera device that currently using
			releaseCamera();
		}

		@Override
		public void onDettach(final UsbDevice device) {
			Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onCancel(final UsbDevice device) {
		}
	};

	protected final synchronized void queueEvent(final Runnable task, final long delayMillis) {
		if ((task == null) || (mWorkerHandler == null)) return;
		try {
			mWorkerHandler.removeCallbacks(task);
			if (delayMillis > 0) {
				mWorkerHandler.postDelayed(task, delayMillis);
			} else if (mWorkerThreadID == Thread.currentThread().getId()) {
				task.run();
			} else {
				mWorkerHandler.post(task);
			}
		} catch (final Exception e) {
			// ignore
		}
	}

	private synchronized void releaseCamera() {
		synchronized (mSync) {
			if (mUVCCamera != null) {
				try {
					mUVCCamera.setStatusCallback(null);
					mUVCCamera.setButtonCallback(null);
					mUVCCamera.close();
					mUVCCamera.destroy();
				} catch (final Exception e) {
					//
				}
				mUVCCamera = null;
			}
			if (mPreviewSurface != null) {
				mPreviewSurface.release();
				mPreviewSurface = null;
			}
		}
	}

	/**
	 * to access from CameraDialog
	 * @return
	 */
	@Override
	public USBMonitor getUSBMonitor() {
		return mUSBMonitor;
	}

	@Override
	public void onDialogResult(boolean canceled) {
		if (canceled) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					// FIXME
				}
			});
		}
	}

	private final IFrameCallback mIFrameCallback = new IFrameCallback() {
		@Override
		public void onFrame(final ByteBuffer frame) {
			int remaining = frame.remaining();
			int limit = frame.limit();
			Log.i(TAG, "onFrame: " + remaining + ", limit: " + limit);
			final int n = frame.limit();
			if (buf_sz < n) {
				buf_sz = n;
				buf = new byte[n];
			}
			frame.get(buf, 0, n);

			faceRectView.clearFaceInfo();
			List<FacePreviewInfo> facePreviewInfoList = recognizeViewModel.onPreviewFrame(buf, true);
			// 绘制实时人脸信息
/*			if (facePreviewInfoList != null && rgbFaceRectTransformer != null) {
				drawPreviewInfo(facePreviewInfoList);
			}*/
			if (facePreviewInfoList != null) {
				drawPreviewInfo(facePreviewInfoList);
			}
			recognizeViewModel.clearLeftFace(facePreviewInfoList);
		}
	};

	/**
	 * 绘制RGB、IR画面的实时人脸信息
	 *
	 * @param facePreviewInfoList RGB画面的实时人脸信息
	 */
	private void drawPreviewInfo(List<FacePreviewInfo> facePreviewInfoList) {
/*		if (rgbFaceRectTransformer != null) {
			List<FaceRectView.DrawInfo> rgbDrawInfoList = recognizeViewModel.getDrawInfo(facePreviewInfoList, LivenessType.RGB, true);
			faceRectView.drawRealtimeFaceInfo(rgbDrawInfoList);
		}*/
		List<FaceRectView.DrawInfo> rgbDrawInfoList = recognizeViewModel.getDrawInfo(facePreviewInfoList, LivenessType.RGB, true);
		faceRectView.drawRealtimeFaceInfo(rgbDrawInfoList);
/*		if (irFaceRectTransformer != null) {
			List<FaceRectView.DrawInfo> irDrawInfoList = recognizeViewModel.getDrawInfo(facePreviewInfoList, LivenessType.IR, openRectInfoDraw);
			binding.dualCameraFaceRectViewIr.drawRealtimeFaceInfo(irDrawInfoList);
		}*/
	}


	// if you need frame data as byte array on Java side, you can use this callback method with UVCCamera#setFrameCallback
	// if you need to create Bitmap in IFrameCallback, please refer following snippet.
/*	final Bitmap bitmap = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.RGB_565);
	private final IFrameCallback mIFrameCallback = new IFrameCallback() {
		@Override
		public void onFrame(final ByteBuffer frame) {
			frame.clear();
			synchronized (bitmap) {
				bitmap.copyPixelsFromBuffer(frame);
			}
			mImageView.post(mUpdateImageTask);
		}
	};
	
	private final Runnable mUpdateImageTask = new Runnable() {
		@Override
		public void run() {
			synchronized (bitmap) {
				mImageView.setImageBitmap(bitmap);
			}
		}
	}; */
}
