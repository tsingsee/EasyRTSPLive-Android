package org.easydarwin.easyrtsplive.fragments;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.Toast;

import org.easydarwin.easyrtmp.push.EasyRTMP;
import org.easydarwin.easyrtmp.push.InitCallback;
import org.easydarwin.easyrtmp.push.Pusher;
import org.easydarwin.easyrtsplive.BuildConfig;
import org.easydarwin.easyrtsplive.R;
import org.easydarwin.easyrtsplive.data.YUVQueue;
import org.easydarwin.easyrtsplive.push.MediaStream;
import org.easydarwin.easyrtsplive.util.FileUtil;
import org.easydarwin.encode.ClippableVideoConsumer;
import org.easydarwin.encode.HWConsumer;
import org.easydarwin.encode.VideoConsumer;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.video.Client;
import org.easydarwin.video.EasyPlayerClient;
import org.easydarwin.video.EasyPlayerClient2;
import org.easydarwin.video.VideoCodec;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * 播放器Fragment
 */
public class YUVComposeFragment extends Fragment implements EasyPlayerClient2.I420DataCallback, MediaStream.PreviewFrameCallback {
    private static final String TAG = YUVComposeFragment.class.getSimpleName();

    public static final String KEY = BuildConfig.RTSP_KEY;

    public static final String ARG_PARAM_CAMERA = "paramCamera";
    public static final String ARG_PARAM_RTMP_URL = "paramRTMPUrl";
    public static final String ARG_PARAM_URL1 = "paramUrl1";
    public static final String ARG_PARAM_URL2 = "paramUrl2";
    public static final String ARG_TRANSPORT_MODE = "ARG_TRANSPORT_MODE";
    public static final String ARG_SEND_OPTION = "ARG_SEND_OPTION";

    // 等比例,最大化区域显示,不裁剪
    public static final int ASPECT_RATIO_INSIDE = 1;
    // 等比例,裁剪,裁剪区域可以通过拖拽展示\隐藏
    public static final int ASPECT_RATIO_CROPS_MATRIX = 2;
    // 等比例,最大区域显示,裁剪
    public static final int ASPECT_RATIO_CENTER_CROPS = 3;
    // 拉伸显示,铺满全屏
    public static final int FILL_WINDOW = 4;

    private int mRatioType = ASPECT_RATIO_INSIDE;

    private EasyPlayerClient2 mStreamRender1;
    private EasyPlayerClient2 mStreamRender2;
    private EasyPlayerClient mLiveStreamRender1;
    private EasyPlayerClient mLiveStreamRender2;

    private String mUrl1;
    private String mUrl2;
    private String rtmpUrl;
    private int mType;// 0或1表示TCP，2表示UDP
    private int sendOption;
    private boolean isCamera = false;
    private boolean isPause = false;

    private TextureView mCombineSurfaceView;
    private TextureView mSurfaceView1;
    private TextureView mSurfaceView2;
    private TextureView mNativeSurfaceView;

    public static YUVComposeFragment newInstance(String rtmpUrl, String url1, String url2, int transportMode, int sendOption, boolean isCamera) {
        YUVComposeFragment fragment = new YUVComposeFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM_RTMP_URL, rtmpUrl);
        args.putString(ARG_PARAM_URL1, url1);
        args.putString(ARG_PARAM_URL2, url2);
        args.putBoolean(ARG_PARAM_CAMERA, isCamera);
        args.putInt(ARG_TRANSPORT_MODE, transportMode);
        args.putInt(ARG_SEND_OPTION, sendOption);
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
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, final ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_yuv_compose, container, false);

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

//                // TODO
//                ms.switchCamera(MediaStream.CAMERA_FACING_BACK_UVC);
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
                        File f = new File(FileUtil.getMoviePath(mUrl1));
                        f.mkdirs();

                        mStreamRender1.startRecord(FileUtil.getMovieName(mUrl1).getPath());
                        recordBtn.setText("停止录像");
                    }
                }
            }
        });

        return view;
    }

    @Override
    public void onViewCreated(final View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initSurfaceView(view);
        initNativeSurfaceView(view);
        initSurfaceView1(view);
        initSurfaceView2(view);
    }

    @Override
    public void onPause() {
        super.onPause();

        isPause = true;
        onVideoDisplayed();

        // TODO 打开 会死锁，这是为啥
//        stopMediaStream();
        stopRending();
        stopThread();
        stopStream();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);

        if (hidden) {
            // stopThread

            if (mStreamRender1 != null) {
                mStreamRender1.pause();
            }
            if (mStreamRender2 != null) {
                mStreamRender2.pause();
            }
            if (mLiveStreamRender1 != null) {
                mLiveStreamRender1.pause();
            }
            if (mLiveStreamRender2 != null) {
                mLiveStreamRender2.pause();
            }
        } else {
            if (mStreamRender1 != null) {
                mStreamRender1.resume();
            }
            if (mStreamRender2 != null) {
                mStreamRender2.resume();
            }
            if (mLiveStreamRender1 != null) {
                mLiveStreamRender1.resume();
            }
            if (mLiveStreamRender2 != null) {
                mLiveStreamRender2.resume();
            }
        }
    }

    /* ======================== SurfaceView ======================== */

    private void initSurfaceView(View view) {
        mCombineSurfaceView = (TextureView) view.findViewById(R.id.surface_view);
        mCombineSurfaceView.setOpaque(false);
        mCombineSurfaceView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                startRending(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    private void initNativeSurfaceView(View view) {
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
        }
    }

    private void initSurfaceView1(View view) {
        mSurfaceView1 = (TextureView) view.findViewById(R.id.surface_view1);
        mSurfaceView1.setOpaque(false);
        mSurfaceView1.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                ResultReceiver mResultReceiver = new ResultReceiver(new Handler()) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        super.onReceiveResult(resultCode, resultData);

                        if (resultCode == EasyPlayerClient.RESULT_VIDEO_SIZE) {
                            int w = resultData.getInt(EasyPlayerClient.EXTRA_VIDEO_WIDTH);
                            int h = resultData.getInt(EasyPlayerClient.EXTRA_VIDEO_HEIGHT);

                            onVideoSizeChange(mSurfaceView1, w, h);
                        }
                    }
                };

                mLiveStreamRender1 = new EasyPlayerClient(getContext(), KEY, new Surface(surface), mResultReceiver);

                try {
                    mLiveStreamRender1.start(mUrl1,
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
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    private void initSurfaceView2(View view) {
        mSurfaceView2 = (TextureView) view.findViewById(R.id.surface_view2);
        mSurfaceView2.setOpaque(false);

        if (TextUtils.isEmpty(mUrl2)) {
            return;
        }

        mSurfaceView2.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                ResultReceiver mResultReceiver = new ResultReceiver(new Handler()) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        super.onReceiveResult(resultCode, resultData);

                        if (resultCode == EasyPlayerClient.RESULT_VIDEO_SIZE) {
                            int w = resultData.getInt(EasyPlayerClient.EXTRA_VIDEO_WIDTH);
                            int h = resultData.getInt(EasyPlayerClient.EXTRA_VIDEO_HEIGHT);

                            onVideoSizeChange(mSurfaceView2, w, h);
                        }
                    }
                };

                mLiveStreamRender2 = new EasyPlayerClient(getContext(), KEY, new Surface(surface), mResultReceiver);

                try {
                    mLiveStreamRender2.start(mUrl2,
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
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    private void onVideoDisplayed() {
        View view = getView();
        view.findViewById(android.R.id.progress).setVisibility(View.GONE);
    }

    // 开始渲染
    protected void startRending(SurfaceTexture surface) {
        startRending1(surface);

        if (!TextUtils.isEmpty(mUrl2)) {
            startRending2(surface);
        }
    }

    private void startRending1(SurfaceTexture surface) {
        ResultReceiver mResultReceiver = new ResultReceiver(new Handler()) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);

                if (resultCode == EasyPlayerClient2.RESULT_VIDEO_SIZE) {
                    int w = resultData.getInt(EasyPlayerClient2.EXTRA_VIDEO_WIDTH);
                    int h = resultData.getInt(EasyPlayerClient2.EXTRA_VIDEO_HEIGHT);

                    onVideoSizeChange(mCombineSurfaceView, w, h);
                    onVideoDisplayed();
                }
            }
        };

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
    }

    private void startRending2(SurfaceTexture surface) {
        mStreamRender2 = new EasyPlayerClient2(getContext(), KEY, null, null, this, 2);
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
    }

    // 停止渲染
    private void stopRending() {
        if (mStreamRender1 != null) {
            mStreamRender1.stop();
            mStreamRender1 = null;
        }

        if (mStreamRender2 != null) {
            mStreamRender2.stop();
            mStreamRender2 = null;
        }

        if (mLiveStreamRender1 != null) {
            mLiveStreamRender1.stop();
            mLiveStreamRender1 = null;
        }

        if (mLiveStreamRender2 != null) {
            mLiveStreamRender2.stop();
            mLiveStreamRender2 = null;
        }
    }

    private void onVideoSizeChange(TextureView textureView, int w, int h) {
        if (w == 0 || h == 0)
            return;

        if (mRatioType == ASPECT_RATIO_CROPS_MATRIX) {
            textureView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            textureView.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            textureView.setTransform(new Matrix());
            float ratioView = getView().getWidth() * 1.0f / getView().getHeight();
            float ratio = w * 1.0f / h;

            switch (mRatioType) {
                case ASPECT_RATIO_INSIDE: {
                    if (ratioView - ratio < 0) {    // 屏幕比视频的宽高比更小.表示视频是过于宽屏了.
                        // 宽为基准.
                        textureView.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                        textureView.getLayoutParams().height = (int) (getView().getWidth() / ratio + 0.5f);
                    } else {                        // 视频是竖屏了.
                        textureView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                        textureView.getLayoutParams().width = (int) (getView().getHeight() * ratio + 0.5f);
                    }
                }
                break;
                case ASPECT_RATIO_CENTER_CROPS: {
                    // 以更短的为基准
                    if (ratioView - ratio < 0) {    // 屏幕比视频的宽高比更小.表示视频是过于宽屏了.
                        // 宽为基准.
                        textureView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                        textureView.getLayoutParams().width = (int) (getView().getHeight() * ratio + 0.5f);
                    } else {                        // 视频是竖屏了.
                        textureView.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                        textureView.getLayoutParams().height = (int) (getView().getWidth() / ratio + 0.5f);
                    }
                }
                break;
                case FILL_WINDOW: {
                    textureView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                    textureView.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                }
                break;
            }
        }

        textureView.requestLayout();
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

    /* ======================== get/set ======================== */

//    public boolean isAudioEnable() {
//        return mStreamRender != null && mStreamRender.isAudioEnable();
//    }

//    public void setScaleType(@IntRange(from = ASPECT_RATIO_INSIDE, to = FILL_WINDOW) int type) {
//        mRatioType = type;
//
//        if (mWidth != 0 && mHeight != 0) {
//            onVideoSizeChange(mCombineSurfaceView);
//            onVideoSizeChange(mSurfaceView1);
//            onVideoSizeChange(mSurfaceView2);
//        }
//    }

    public void setTransType(int transType) {
        this.mType = transType;
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
                        byte[] in1 = new byte[w1 * h1 * 3 / 2];

                        ByteBuffer buffer1 = queue1.take();
                        buffer1.clear();
                        if (buffer1.remaining() < in1.length) {
                            continue;
                        }

                        buffer1.get(in1);

                        if (!TextUtils.isEmpty(mUrl2) && queue2.size() > 0) {
                            // 第二个流的yuv
                            ByteBuffer buffer2 = queue2.take();
                            buffer2.clear();
                            if (buffer2.remaining() >= w2 * h2 * 3 / 2) {
                                byte[] in2 = new byte[w2 * h2 * 3 / 2];
                                buffer2.get(in2);

                                if (w2 > 360) {
                                    // 压缩分辨率
                                    int w = 640, h = (int) ((1.0 * w * h2) / (1.0 * w2));
                                    byte[] i420_buffer2 = new byte[w * h * 3 / 2];
                                    JNIUtil.I420Scale(in2, i420_buffer2, w2, h2, w, h, 0);
                                    VideoCodec.composeYUV(in1, w1, h1, i420_buffer2, w, h);// w1 - w2, h1 - h2
                                } else {
                                    VideoCodec.composeYUV(in1, w1, h1, in2, w2, h2);// w1 - w2, h1 - h2
                                }
                            }
                        } else if (isCamera && cameraQueue.size() > 0) {
                            ByteBuffer buffer2 = cameraQueue.take();
                            buffer2.clear();
                            if (buffer2.remaining() >= cameraW * cameraH * 3 / 2) {
                                byte[] cameraBuffer = new byte[cameraW * cameraH * 3 / 2];
                                buffer2.get(cameraBuffer);
                                VideoCodec.composeYUV(in1, w1, h1, cameraBuffer, cameraW, cameraH);// w1 - cameraW, h1 - cameraH
                            }
                        }

                        ByteBuffer tmp = ByteBuffer.allocateDirect(w1 * h1 * 3 / 2);
                        tmp.clear();
                        tmp.put(in1);

                        if (!isPause) {
                            mStreamRender1.show(tmp, w1, h1);
                        }

                        if (mVC != null) {
                            mVC.onVideo(in1, 0);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (Exception e) {
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
            Log.e(TAG, "frameInfo.length >>> " + frameInfo.length);
            mEasyPusher.push(frameInfo.buffer, frameInfo.offset, frameInfo.length, 0, EasyRTMP.FrameType.FRAME_TYPE_AUDIO);
        }
    }

    @Override
    public void onI420Data(ByteBuffer buffer, int index, int w, int h) {
        if (index == 1) {
            try {
                queue1.put(buffer);
                Log.e(TAG, "queue1 size == " + queue1.size());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            w1 = w;
            h1 = h;

            if (mVC == null) {
                initEncode();
            }
        } else {
            try {
                queue2.put(buffer);
                Log.e(TAG, "queue2 size == " + queue2.size());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            w2 = w;
            h2 = h;
        }

        if (yuvThread == null) {
            startYUVCompose();
        }
    }

    @Override
    public void onCameraI420Data(ByteBuffer buffer, int w, int h) {
        try {
            if (isCamera) {
                cameraQueue.put(buffer);
                Log.e(TAG, "cameraQueue size == " + cameraQueue.size());
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        cameraW = w;
        cameraH = h;
    }
}
