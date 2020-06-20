package org.easydarwin.easyrtsplive.fragments;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.support.annotation.IntRange;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewPropertyAnimatorListenerAdapter;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import org.easydarwin.easyrtmp.push.EasyRTMP;
import org.easydarwin.easyrtmp.push.InitCallback;
import org.easydarwin.easyrtmp.push.Pusher;
import org.easydarwin.easyrtsplive.BuildConfig;
import org.easydarwin.easyrtsplive.R;
import org.easydarwin.easyrtsplive.data.YUVQueue;
import org.easydarwin.easyrtmp.push.MediaStream;
import org.easydarwin.easyrtsplive.util.FileUtil;
import org.easydarwin.easyrtsplive.views.AngleView;
import org.easydarwin.encode.ClippableVideoConsumer;
import org.easydarwin.encode.HWConsumer;
import org.easydarwin.encode.VideoConsumer;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.video.Client;
import org.easydarwin.video.EasyPlayerClient2;
import org.easydarwin.video.VideoCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import uk.copywitchshame.senab.photoview.gestures.PhotoViewAttacher;

/**
 * 播放器Fragment
 */
public class YUVComposeFragment extends Fragment implements PhotoViewAttacher.OnMatrixChangedListener, EasyPlayerClient2.I420DataCallback, MediaStream.PreviewFrameCallback {
    protected static final String TAG = YUVComposeFragment.class.getSimpleName();

    public static final String KEY = BuildConfig.RTSP_KEY;

    public static final String ARG_PARAM_CAMERA = "paramCamera";
    public static final String ARG_PARAM_RTMP_URL = "paramRTMPUrl";
    public static final String ARG_PARAM_URL1 = "paramUrl1";
    public static final String ARG_PARAM_URL2 = "paramUrl2";
    public static final String ARG_PARAM_RR = "paramRR";
    public static final String ARG_TRANSPORT_MODE = "ARG_TRANSPORT_MODE";
    public static final String ARG_SEND_OPTION = "ARG_SEND_OPTION";

    public static final int RESULT_REND_START = 1;
    public static final int RESULT_REND_VIDEO_DISPLAY = 2;
    public static final int RESULT_REND_STOP = -1;

    // 等比例,最大化区域显示,不裁剪
    public static final int ASPECT_RATIO_INSIDE = 1;
    // 等比例,裁剪,裁剪区域可以通过拖拽展示\隐藏
    public static final int ASPECT_RATIO_CROPS_MATRIX = 2;
    // 等比例,最大区域显示,裁剪
    public static final int ASPECT_RATIO_CENTER_CROPS = 3;
    // 拉伸显示,铺满全屏
    public static final int FILL_WINDOW = 4;

    private int mRatioType = ASPECT_RATIO_INSIDE;

    private ResultReceiver mRR;// ResultReceiver是一个用来接收其他进程回调结果的通用接口

    protected EasyPlayerClient2 mStreamRender1;
    protected EasyPlayerClient2 mStreamRender2;
    protected ResultReceiver mResultReceiver;

    protected String mUrl1;
    protected String mUrl2;
    protected String rtmpUrl;
    private boolean isCamera = false;
    protected int mType;// 0或1表示TCP，2表示UDP
    protected int sendOption;
    private boolean isPause = false;

    protected int mWidth;
    protected int mHeight;

    protected View.OnLayoutChangeListener listener;

    private PhotoViewAttacher mAttacher;
    private AngleView mAngleView;
    private ImageView mRenderCover;
    private ImageView mTakePictureThumb;// 显示抓拍的图片
    protected TextureView mSurfaceView;
    protected TextureView mNativeSurfaceView;
    private SurfaceTexture mSurfaceTexture;
    protected ImageView cover;

    private MediaScannerConnection mScanner;

    private AsyncTask<Void, Void, Bitmap> mLoadingPictureThumbTask;

    private OnDoubleTapListener doubleTapListener;

    // 抓拍后隐藏thumb的task
    private final Runnable mAnimationHiddenTakePictureThumbTask = new Runnable() {
        @Override
        public void run() {
            ViewCompat.animate(mTakePictureThumb).scaleX(0.0f).scaleY(0.0f).setListener(new ViewPropertyAnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(View view) {
                    super.onAnimationEnd(view);
                    view.setVisibility(View.INVISIBLE);
                }
            });
        }
    };

    public static YUVComposeFragment newInstance(String rtmpUrl, String url1, String url2, int transportMode, int sendOption, boolean isCamera, ResultReceiver rr) {
        YUVComposeFragment fragment = new YUVComposeFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM_RTMP_URL, rtmpUrl);
        args.putString(ARG_PARAM_URL1, url1);
        args.putString(ARG_PARAM_URL2, url2);
        args.putBoolean(ARG_PARAM_CAMERA, isCamera);
        args.putInt(ARG_TRANSPORT_MODE, transportMode);
        args.putInt(ARG_SEND_OPTION, sendOption);
        args.putParcelable(ARG_PARAM_RR, rr);
        fragment.setArguments(args);
        return fragment;
    }

    /* ======================== life cycle ======================== */

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null) {
            rtmpUrl = getArguments().getString(ARG_PARAM_RTMP_URL);
            mUrl1 = getArguments().getString(ARG_PARAM_URL1);
            mUrl2 = getArguments().getString(ARG_PARAM_URL2);
            isCamera = getArguments().getBoolean(ARG_PARAM_CAMERA);
            mType = getArguments().getInt(ARG_TRANSPORT_MODE);
            sendOption = getArguments().getInt(ARG_SEND_OPTION);
            mRR = getArguments().getParcelable(ARG_PARAM_RR);

            if (!TextUtils.isEmpty(mUrl2)) {
                isCamera = false;
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, final ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_yuv_compose, container, false);
        cover = (ImageView) view.findViewById(R.id.surface_cover);

        // 推流
        final Button pushBtn = view.findViewById(R.id.start_or_stop_push);
        pushBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isPushStream) {
                    try {
                        startStream(rtmpUrl, null);
                        pushBtn.setText("停止推流");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // 停止录像
                } else {
                    stopStream();
                    pushBtn.setText("开启推流");
                }
            }
        });

        // 录像
        final Button recordBtn = view.findViewById(R.id.start_or_stop_record);
        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int permissionCheck = ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                    return;
                }

                if (mStreamRender1 != null) {
                    if (mStreamRender1.isRecording()) {
                        mStreamRender1.stopRecord();
                        recordBtn.setText("开启录像");
                    } else {
                        mStreamRender1.startRecord(FileUtil.getMovieName(mUrl1).getPath());
                        recordBtn.setText("停止录像");
                    }
                }

                //  停止推流
            }
        });

        return view;
    }

    @Override
    public void onViewCreated(final View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mSurfaceView = (TextureView) view.findViewById(R.id.surface_view);
        mSurfaceView.setOpaque(false);
        mSurfaceView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (mSurfaceTexture != null) {
                    mSurfaceView.setSurfaceTexture(mSurfaceTexture);
                } else {
                    startRending(surface);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                if (mAttacher != null) {
                    mAttacher.update();
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                mSurfaceTexture = surface;
                return false;

//                stopRending();
//                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });

        mNativeSurfaceView = (TextureView) view.findViewById(R.id.native_surface_view);
        mNativeSurfaceView.setOpaque(false);

        if (isCamera) {
            mNativeSurfaceView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    goonWithAvailableTexture(surface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {

                }
            });
        } else {
            mNativeSurfaceView.setVisibility(View.GONE);
        }

        mAngleView = (AngleView) getView().findViewById(R.id.render_angle_view);
        mRenderCover = (ImageView) getView().findViewById(R.id.surface_cover);
        mTakePictureThumb = (ImageView) getView().findViewById(R.id.live_video_snap_thumb);

        mResultReceiver = new ResultReceiver(new Handler()) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);

                Activity activity = getActivity();

                if (activity == null)
                    return;

                if (resultCode == EasyPlayerClient2.RESULT_VIDEO_DISPLAYED) {
                    if (resultData != null) {
                        int videoDecodeType = resultData.getInt(EasyPlayerClient2.KEY_VIDEO_DECODE_TYPE, 0);
                        Log.i(TAG, "视频解码方式:" + (videoDecodeType == 0 ? "软解码" : "硬解码"));
                    }

                    onVideoDisplayed();
                } else if (resultCode == EasyPlayerClient2.RESULT_VIDEO_SIZE) {
                    mWidth = resultData.getInt(EasyPlayerClient2.EXTRA_VIDEO_WIDTH);
                    mHeight = resultData.getInt(EasyPlayerClient2.EXTRA_VIDEO_HEIGHT);

                    onVideoSizeChange();
                } else if (resultCode == EasyPlayerClient2.RESULT_TIMEOUT) {
                    new AlertDialog.Builder(getActivity()).setMessage("试播时间到").setTitle("SORRY").setPositiveButton(android.R.string.ok, null).show();
                } else if (resultCode == EasyPlayerClient2.RESULT_UNSUPPORTED_AUDIO) {
                    new AlertDialog.Builder(getActivity()).setMessage("音频格式不支持").setTitle("SORRY").setPositiveButton(android.R.string.ok, null).show();
                } else if (resultCode == EasyPlayerClient2.RESULT_UNSUPPORTED_VIDEO) {
                    new AlertDialog.Builder(getActivity()).setMessage("视频格式不支持").setTitle("SORRY").setPositiveButton(android.R.string.ok, null).show();
                } else if (resultCode == EasyPlayerClient2.RESULT_EVENT) {
                    int errorCode = resultData.getInt("errorcode");
//                    if (errorCode != 0) {
//                        stopRending();
//                    }

//                    if (activity instanceof PlayActivity) {
//                        int state = resultData.getInt("state");
//                        String msg = resultData.getString("event-msg");
//                        ((PlayActivity) activity).onEvent(YUVComposeFragment.this, state, errorCode, msg);
//                    }
                } else if (resultCode == EasyPlayerClient2.RESULT_RECORD_BEGIN) {
//                    if (activity instanceof PlayActivity)
//                        ((PlayActivity)activity).onRecordState(1);
                } else if (resultCode == EasyPlayerClient2.RESULT_RECORD_END) {
//                    if (activity instanceof PlayActivity)
//                        ((PlayActivity)activity).onRecordState(-1);
                }
            }
        };

        listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                Log.d(TAG, String.format("onLayoutChange left:%d,top:%d,right:%d,bottom:%d->oldLeft:%d,oldTop:%d,oldRight:%d,oldBottom:%d", left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom));

                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    onVideoSizeChange();
                }
            }
        };

        ViewGroup parent = (ViewGroup) view.getParent();
        parent.addOnLayoutChangeListener(listener);

        GestureDetector.SimpleOnGestureListener sgl = new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (doubleTapListener != null)
                    doubleTapListener.onDoubleTab(YUVComposeFragment.this);

                return super.onDoubleTap(e);
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (doubleTapListener != null)
                    doubleTapListener.onSingleTab(YUVComposeFragment.this);

                return super.onSingleTapUp(e);
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        };

        final GestureDetector gd = new GestureDetector(getContext(), sgl);

        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gd.onTouchEvent(event);
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();

        isPause = true;
        onVideoDisplayed();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopMediaStream();
        stopThread();
        stopRending();
        stopStream();
    }

    @Override
    public void onDestroyView() {
        ViewGroup parent = (ViewGroup) getView().getParent();
        parent.removeOnLayoutChangeListener(listener);
        super.onDestroyView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);

        if (hidden) {
            // stopThread
//            stopRending();

            if (mStreamRender1 != null) {
                mStreamRender1.pause();
            }
            if (mStreamRender2 != null) {
                mStreamRender2.pause();
            }
        } else {
            if (mStreamRender1 != null) {
                mStreamRender1.resume();
            }
            if (mStreamRender2 != null) {
                mStreamRender2.resume();
            }
        }
    }

    /* ======================== private method ======================== */

    private void onVideoDisplayed() {
        View view = getView();
        Log.i(TAG, String.format("VIDEO DISPLAYED!!!!%d*%d", mWidth, mHeight));
        view.findViewById(android.R.id.progress).setVisibility(View.GONE);

//        mSurfaceView.post(new Runnable() {
//            @Override
//            public void run() {
//                if (mWidth != 0 && mHeight != 0) {
//                    Bitmap e = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
//                    mSurfaceView.getBitmap(e);
//
//                    File f = FileUtil.getSnapFile(mUrl1);
//                    saveBitmapInFile(f.getPath(), e);
//                    e.recycle();
//                }
//            }
//        });

        cover.setVisibility(View.GONE);
        sendResult(RESULT_REND_VIDEO_DISPLAY, null);
    }

    // 开始渲染
    protected void startRending(SurfaceTexture surface) {
        startRending1(surface);

        if (!TextUtils.isEmpty(mUrl2)) {
            startRending2(surface);
        }
    }

    private void startRending1(SurfaceTexture surface) {
        mStreamRender1 = new EasyPlayerClient2(getContext(), KEY, new Surface(surface), mResultReceiver, this, 1);

        try {
            mStreamRender1.start(mUrl1,
                    mType < 2 ? Client.TRANSTYPE_TCP : Client.TRANSTYPE_UDP,
                    sendOption,
                    Client.EASY_SDK_VIDEO_FRAME_FLAG | Client.EASY_SDK_AUDIO_FRAME_FLAG,
                    "",
                    "");
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        sendResult(RESULT_REND_START, null);
    }

    private void startRending2(SurfaceTexture surface) {
        mStreamRender2 = new EasyPlayerClient2(getContext(), KEY, null, mResultReceiver, this, 2);
        mStreamRender2.setAudioEnable(false);

        try {
            mStreamRender2.start(mUrl2,
                    mType < 2 ? Client.TRANSTYPE_TCP : Client.TRANSTYPE_UDP,
                    sendOption,
                    Client.EASY_SDK_VIDEO_FRAME_FLAG | Client.EASY_SDK_AUDIO_FRAME_FLAG,
                    "",
                    "");
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        sendResult(RESULT_REND_START, null);
    }

    // 停止渲染
    private void stopRending() {
        if (mStreamRender1 != null) {
            sendResult(RESULT_REND_STOP, null);
            mStreamRender1.stop();
            mStreamRender1 = null;
        }

        if (mStreamRender2 != null) {
            mStreamRender2.stop();
            mStreamRender2 = null;
        }
    }

    // 抓拍
//    public void takePicture(final String path) {
//        try {
//            if (mWidth <= 0 || mHeight <= 0) {
//                return;
//            }
//
//            Bitmap bitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
//            mSurfaceView.getBitmap(bitmap);
//            saveBitmapInFile(path, bitmap);
//            bitmap.recycle();
//
//            mRenderCover.setImageDrawable(new ColorDrawable(getResources().getColor(android.R.color.white)));
//            mRenderCover.setVisibility(View.VISIBLE);
//            mRenderCover.setAlpha(1.0f);
//
//            ViewCompat.animate(mRenderCover).cancel();
//            ViewCompat.animate(mRenderCover).alpha(0.3f).setListener(new ViewPropertyAnimatorListenerAdapter() {
//                @Override
//                public void onAnimationEnd(View view) {
//                    super.onAnimationEnd(view);
//                    mRenderCover.setVisibility(View.GONE);
//                }
//            });
//
//            if (mLoadingPictureThumbTask != null)
//                mLoadingPictureThumbTask.cancel(true);
//
//            final int w = mTakePictureThumb.getWidth();
//            final int h = mTakePictureThumb.getHeight();
//
//            mLoadingPictureThumbTask = new AsyncTask<Void, Void, Bitmap>() {
//                final WeakReference<ImageView> mImageViewRef = new WeakReference<>(mTakePictureThumb);
//                final String mPath = path;
//
//                @Override
//                protected Bitmap doInBackground(Void... params) {
//                    return decodeSampledBitmapFromResource(mPath, w, h);
//                }
//
//                @Override
//                protected void onPostExecute(Bitmap bitmap) {
//                    super.onPostExecute(bitmap);
//
//                    if (isCancelled()) {
//                        bitmap.recycle();
//                        return;
//                    }
//
//                    ImageView iv = mImageViewRef.get();
//
//                    if (iv == null)
//                        return;
//
//                    iv.setImageBitmap(bitmap);
//                    iv.setVisibility(View.VISIBLE);
//                    iv.removeCallbacks(mAnimationHiddenTakePictureThumbTask);
//                    iv.clearAnimation();
//
//                    ViewCompat.animate(iv).scaleX(1.0f).scaleY(1.0f).setListener(new ViewPropertyAnimatorListenerAdapter() {
//                        @Override
//                        public void onAnimationEnd(View view) {
//                            super.onAnimationEnd(view);
//                            view.postOnAnimationDelayed(mAnimationHiddenTakePictureThumbTask, 4000);
//                        }
//                    });
//
//                    iv.setTag(mPath);
//                }
//            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
//        } catch (OutOfMemoryError error) {
//            error.printStackTrace();
//        } catch (IllegalStateException e) {
//            e.printStackTrace();
//        }
//    }

//    public static Bitmap decodeSampledBitmapFromResource(String path, int reqWidth, int reqHeight) {
//        // First decode with inJustDecodeBounds=true to check dimensions
//        final BitmapFactory.Options options = new BitmapFactory.Options();
//        options.inJustDecodeBounds = true;
//        BitmapFactory.decodeFile(path, options);
//
//        // Calculate inSampleSize
//        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
//
//        // Decode bitmap with inSampleSize set
//        options.inJustDecodeBounds = false;
//        return BitmapFactory.decodeFile(path, options);
//    }

//    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
//        // Raw height and width of image
//        final int height = options.outHeight;
//        final int width = options.outWidth;
//        int inSampleSize = 1;
//
//        if (height > reqHeight || width > reqWidth) {
//            final int halfHeight = height / 2;
//            final int halfWidth = width / 2;
//
//            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
//            // height and width larger than the requested height and width.
//            while ((halfHeight / inSampleSize) > reqHeight && (halfWidth / inSampleSize) > reqWidth) {
//                inSampleSize *= 2;
//            }
//        }
//
//        return inSampleSize;
//    }
//
//    private void saveBitmapInFile(final String path, Bitmap bitmap) {
//        FileOutputStream fos = null;
//
//        try {
//            fos = new FileOutputStream(path);
//            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
//
//            if (mScanner == null) {
//                MediaScannerConnection connection = new MediaScannerConnection(getContext(), new MediaScannerConnection.MediaScannerConnectionClient() {
//                    public void onMediaScannerConnected() {
//                        mScanner.scanFile(path, null /* mimeType */);
//                    }
//
//                    public void onScanCompleted(String path1, Uri uri) {
//                        if (path1.equals(path)) {
//                            mScanner.disconnect();
//                            mScanner = null;
//                        }
//                    }
//                });
//
//                try {
//                    connection.connect();
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//
//                mScanner = connection;
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        } catch (OutOfMemoryError error) {
//            error.printStackTrace();
//        } finally {
//            if (fos != null) {
//                try {
//                    fos.close();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            }
//        }
//    }

    // 进入全屏模式
    public void enterFullscreen() {
        setScaleType(FILL_WINDOW);
    }

    // 退出全屏模式
    public void quiteFullscreen() {
        setScaleType(ASPECT_RATIO_CROPS_MATRIX);
    }

    private void onVideoSizeChange() {
        Log.i(TAG, String.format("RESULT_VIDEO_SIZE RECEIVED :%d*%d", mWidth, mHeight));

        if (mWidth == 0 || mHeight == 0)
            return;

        if (mAttacher != null) {
            mAttacher.cleanup();
            mAttacher = null;
        }

        if (mRatioType == ASPECT_RATIO_CROPS_MATRIX) {
            ViewGroup parent = (ViewGroup) getView().getParent();
            parent.addOnLayoutChangeListener(listener);
            fixPlayerRatio(getView(), parent.getWidth(), parent.getHeight());

            mSurfaceView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            mSurfaceView.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;

            mAttacher = new PhotoViewAttacher(mSurfaceView, mWidth, mHeight);
            mAttacher.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return false;
                }
            });

            mAttacher.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mAttacher.setOnMatrixChangeListener(YUVComposeFragment.this);
            mAttacher.update();

            mAngleView.setVisibility(View.VISIBLE);
        } else {
            mSurfaceView.setTransform(new Matrix());
            mAngleView.setVisibility(View.GONE);
//            int viewWidth = mSurfaceView.getWidth();
//            int viewHeight = mSurfaceView.getHeight();
            float ratioView = getView().getWidth() * 1.0f / getView().getHeight();
            float ratio = mWidth * 1.0f / mHeight;

            switch (mRatioType) {
                case ASPECT_RATIO_INSIDE: {
                    if (ratioView - ratio < 0) {    // 屏幕比视频的宽高比更小.表示视频是过于宽屏了.
                        // 宽为基准.
                        mSurfaceView.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                        mSurfaceView.getLayoutParams().height = (int) (getView().getWidth() / ratio + 0.5f);
                    } else {                        // 视频是竖屏了.
                        mSurfaceView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                        mSurfaceView.getLayoutParams().width = (int) (getView().getHeight() * ratio + 0.5f);
                    }
                }
                break;
                case ASPECT_RATIO_CENTER_CROPS: {
                    // 以更短的为基准
                    if (ratioView - ratio < 0) {    // 屏幕比视频的宽高比更小.表示视频是过于宽屏了.
                        // 宽为基准.
                        mSurfaceView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                        mSurfaceView.getLayoutParams().width = (int) (getView().getHeight() * ratio + 0.5f);
                    } else {                        // 视频是竖屏了.
                        mSurfaceView.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                        mSurfaceView.getLayoutParams().height = (int) (getView().getWidth() / ratio + 0.5f);
                    }
                }
                break;
                case FILL_WINDOW: {
                    mSurfaceView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                    mSurfaceView.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                }
                break;
            }
        }

        mSurfaceView.requestLayout();
    }

    protected void sendResult(int resultCode, Bundle resultData) {
        if (mRR != null)
            mRR.send(resultCode, resultData);
    }

    /* ======================== MediaStream ======================== */

    private MediaStream ms;

    /*
     * 初始化MediaStream
     * */
    private void goonWithAvailableTexture(SurfaceTexture surface) {
        ms = new MediaStream(getContext(), surface, this);

        ms.setDisplayRotationDegree(getDisplayRotationDegree());
        ms.createCamera();
        ms.startPreview();
    }

    private void stopMediaStream() {
        if (ms != null) {
            ms.stopPreview();
            ms.release();
            ms = null;
        }
    }

    // 屏幕的角度
    private int getDisplayRotationDegree() {
        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;

        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break; // Natural orientation
            case Surface.ROTATION_90:
                degrees = 90;
                break; // Landscape left
            case Surface.ROTATION_180:
                degrees = 180;
                break;// Upside down
            case Surface.ROTATION_270:
                degrees = 270;
                break;// Landscape right
        }

        return degrees;
    }

    /* ======================== OnMatrixChangedListener ======================== */

    @Override
    public void onMatrixChanged(Matrix matrix, RectF rect) {
        float maxMovement = (rect.width() - mSurfaceView.getWidth());
        float middle = mSurfaceView.getWidth() * 0.5f + mSurfaceView.getLeft();
        float currentMiddle = rect.width() * 0.5f + rect.left;
        mAngleView.setCurrentProgress(-(int) ((currentMiddle - middle) * 100 / maxMovement));
    }

    /* ======================== get/set ======================== */

    public interface OnDoubleTapListener {
        void onDoubleTab(YUVComposeFragment f);

        void onSingleTab(YUVComposeFragment f);
    }

//    public boolean isAudioEnable() {
//        return mStreamRender != null && mStreamRender.isAudioEnable();
//    }

    public void setScaleType(@IntRange(from = ASPECT_RATIO_INSIDE, to = FILL_WINDOW) int type) {
        mRatioType = type;

        if (mWidth != 0 && mHeight != 0) {
            onVideoSizeChange();
        }
    }

    public void setOnDoubleTapListener(OnDoubleTapListener listener) {
        this.doubleTapListener = listener;
    }

    public void setTransType(int transType) {
        this.mType = transType;
    }

    public void setResultReceiver(ResultReceiver rr) {
        mRR = rr;
    }

    public void setSelected(boolean selected) {
        mSurfaceView.animate().scaleX(selected ? 0.9f : 1.0f);
        mSurfaceView.animate().scaleY(selected ? 0.9f : 1.0f);
        mSurfaceView.animate().alpha(selected ? 0.7f : 1.0f);
    }

    // 高度固定，宽度可更改
    protected void fixPlayerRatio(View renderView, int maxWidth, int maxHeight) {
//        fixPlayerRatio(renderView, maxWidth, maxHeight, mWidth, mHeight);
    }

    protected void fixPlayerRatio(View renderView, int widthSize, int heightSize, int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }

        float aspectRatio = width * 1.0f / height;

        if (widthSize > heightSize * aspectRatio) {
            height = heightSize;
            width = (int) (height * aspectRatio);
        } else {
            width = widthSize;
            height = (int) (width / aspectRatio);
        }

        renderView.getLayoutParams().width = width;
        renderView.getLayoutParams().height = height;
        renderView.requestLayout();
    }

    public static class ReverseInterpolator extends AccelerateDecelerateInterpolator {
        @Override
        public float getInterpolation(float paramFloat) {
            return super.getInterpolation(1.0f - paramFloat);
        }
    }

    protected boolean isLandscape() {
        return getActivity().getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || getActivity().getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
    }

    /* ======================== I420DataCallback ======================== */

    private Pusher mEasyPusher;
    private VideoConsumer mVC;
    private volatile Thread yuvThread;

    private YUVQueue queue1 = new YUVQueue();
    private YUVQueue queue2 = new YUVQueue();
    private YUVQueue cameraQueue = new YUVQueue();

    private int w1, h1;
    private int w2, h2;
    private int cameraW, cameraH;

    boolean isPushStream = false;       // 是否要推送数据

    /// 开始推流
    public void startStream(String url, InitCallback callback) throws IOException {
        try {
//            if (SPUtil.getEnableVideo(EasyApplication.getEasyApplication()))
            mEasyPusher.initPush(url, getContext(), callback);
//            else
//                mEasyPusher.initPush(url, getContext(), callback, ~0);

            isPushStream = true;
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new IOException(ex.getMessage());
        }
    }

    // 停止推流
    public void stopStream() {
        if (mEasyPusher != null) {
            mEasyPusher.stop();
            isPushStream = false;
        }

        // 关闭视频编码器
        if (mVC != null) {
            mVC.onVideoStop();
            Log.i(TAG, "Stop VC");
        }
    }

    private void initEncode() {
        MediaStream.CodecInfo info = new MediaStream.CodecInfo();
        ArrayList<MediaStream.CodecInfo> infos = MediaStream.listEncoders(MediaFormat.MIMETYPE_VIDEO_AVC);
        if (!infos.isEmpty()) {
            MediaStream.CodecInfo ci = infos.get(0);
            info.mName = ci.mName;
            info.mColorFormat = ci.mColorFormat;
        }

        mEasyPusher = new EasyRTMP(EasyRTMP.VIDEO_CODEC_H264, BuildConfig.RTMP_KEY);

        HWConsumer hw = new HWConsumer(getContext(),
                MediaFormat.MIMETYPE_VIDEO_AVC,
                mEasyPusher,
                10000,
                info.mName,
                info.mColorFormat,
                mStreamRender1);
        mVC = new ClippableVideoConsumer(getContext(),
                hw,
                w1,
                h1);
        mVC.onVideoStart(w1, h1);
    }

    private void startYUVCompose() {
        yuvThread = new Thread("YUV_COMPOSE") {

            @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
            @Override
            public void run() {
                while (yuvThread != null) {
                    try {
                        // 第一个流的yuv
                        ByteBuffer buffer1 = queue1.take();
                        byte[] in1 = new byte[w1 * h1 * 3 / 2];
                        buffer1.clear();
                        buffer1.get(in1);

                        if (!TextUtils.isEmpty(mUrl2)) {
                            // 第二个流的yuv
                            byte[] in2 = new byte[w2 * h2 * 3 / 2];
                            ByteBuffer buffer2 = queue2.take();
                            buffer2.clear();
                            buffer2.get(in2);

//                            // TODO 压缩
//                            int w = w2 / 3, h = h2 / 3;
//                            byte[] i420_buffer2 = new byte[w * h * 3 / 2];
//                            JNIUtil.I420Scale(in2, i420_buffer2, w2, h2, w, h, 0);
//                            VideoCodec.composeYUV(in1, w1, h1, i420_buffer2, w, h);// w1 - w2, h1 - h2

                            VideoCodec.composeYUV(in1, w1, h1, in2, w2, h2);// w1 - w2, h1 - h2
                        } else if (isCamera) {
                            byte[] cameraBuffer = new byte[cameraW * cameraH * 3 / 2];
                            ByteBuffer buffer2 = cameraQueue.take();
                            buffer2.clear();
                            buffer2.get(cameraBuffer);

                            VideoCodec.composeYUV(in1, w1, h1, cameraBuffer, cameraW, cameraH);// w1 - cameraW, h1 - cameraH
                        }

                        ByteBuffer tmp = ByteBuffer.allocateDirect(w1 * h1 * 3 / 2);
                        tmp.clear();
                        tmp.put(in1);

                        if (!isPause) {
                            mStreamRender1.show(tmp, w1, h1);
                        }

                        mVC.onVideo(in1, 0);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };

        yuvThread.start();
    }

    public void stopThread() {
        Thread t = yuvThread;
        yuvThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onAACData(Client.FrameInfo frameInfo, int index) {
        if (isPushStream && (index == 1)) {
            mEasyPusher.push(frameInfo.buffer, frameInfo.offset, frameInfo.length, 0, EasyRTMP.FrameType.FRAME_TYPE_AUDIO);
        }
    }

    @Override
    public void onI420Data(ByteBuffer buffer, int index, int w, int h) {
        if (index == 1) {
            try {
                queue1.put(buffer);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            w1 = w;
            h1 = h;
        } else {
            try {
                queue2.put(buffer);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            w2 = w;
            h2 = h;
        }

        if (yuvThread == null) {
            initEncode();
            startYUVCompose();
        }
    }

    @Override
    public void onCameraI420Data(ByteBuffer buffer, int w, int h) {
        try {
            if (isCamera) {
                cameraQueue.put(buffer);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        cameraW = w;
        cameraH = h;
    }
}
