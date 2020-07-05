package org.easydarwin.video;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import org.easydarwin.audio.AudioCodec;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.util.CodecSpecificDataUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar;
import static org.easydarwin.util.CodecSpecificDataUtil.AUDIO_SPECIFIC_CONFIG_SAMPLING_RATE_TABLE;
import static org.easydarwin.video.Client.TRANSTYPE_TCP;
import static org.easydarwin.video.EasyMuxer2.VIDEO_TYPE_H264;
import static org.easydarwin.video.EasyMuxer2.VIDEO_TYPE_H265;

/**
 * Created by John on 2016/3/17.
 */
public class EasyPlayerClient2 implements Client.SourceCallBack {

    /* 视频编码 */
    public static final int EASY_SDK_VIDEO_CODEC_H264 = 0x1C;       /* H264  */
    public static final int EASY_SDK_VIDEO_CODEC_H265 = 0x48323635; /*H265*/
    public static final int EASY_SDK_VIDEO_CODEC_MJPEG = 0x08;      /* MJPEG */
    public static final int EASY_SDK_VIDEO_CODEC_MPEG4 = 0x0D;      /* MPEG4 */

    /* 音频编码 */
    public static final int EASY_SDK_AUDIO_CODEC_AAC = 0x15002;     /* AAC */
    public static final int EASY_SDK_AUDIO_CODEC_G711U = 0x10006;   /* G711 ulaw*/
    public static final int EASY_SDK_AUDIO_CODEC_G711A = 0x10007;   /* G711 alaw*/
    public static final int EASY_SDK_AUDIO_CODEC_G726 = 0x1100B;    /* G726 */

    /**
     * 表示视频显示出来了
     */
    public static final int RESULT_VIDEO_DISPLAYED = 01;

    /**
     * 表示视频的解码方式
     */
    public static final String KEY_VIDEO_DECODE_TYPE = "video-decode-type";
    /**
     * 表示视频的尺寸获取到了。具体尺寸见 EXTRA_VIDEO_WIDTH、EXTRA_VIDEO_HEIGHT
     */
    public static final int RESULT_VIDEO_SIZE = 02;
    /**
     * 表示KEY的可用播放时间已用完
     */
    public static final int RESULT_TIMEOUT = 03;
    /**
     * 表示KEY的可用播放时间已用完
     */
    public static final int RESULT_EVENT = 04;
    public static final int RESULT_UNSUPPORTED_VIDEO = 05;
    public static final int RESULT_UNSUPPORTED_AUDIO = 06;
    public static final int RESULT_RECORD_BEGIN = 7;
    public static final int RESULT_RECORD_END = 8;
    /**
     * 表示第一帧数据已经收到
     */
    public static final int RESULT_FRAME_RECVED = 9;

    private static final String TAG = EasyPlayerClient2.class.getSimpleName();
    /**
     * 表示视频的宽度
     */
    public static final String EXTRA_VIDEO_WIDTH = "extra-video-width";
    /**
     * 表示视频的高度
     */
    public static final String EXTRA_VIDEO_HEIGHT = "extra-video-height";

    private static final int NAL_VPS = 32;
    private static final int NAL_SPS = 33;
    private static final int NAL_PPS = 34;

    private final String mKey;
    private Surface mSurface;
    private int mIndex;
    private final ResultReceiver mRR;

    private volatile Thread mThread, mAudioThread;
    private Client mClient;
    private boolean mAudioEnable = true;
    private volatile long mReceivedDataLength;
    private AudioTrack mAudioTrack;

    private Client.MediaInfo mMediaInfo;
    private short mHeight = 0;
    short mWidth = 0;
    private ByteBuffer mCSD0;
    private ByteBuffer mCSD1;
    private final I420DataCallback i420callback;
    private boolean mMuxerWaitingKeyVideo;
    /**
     * -1 表示暂停中，0表示正常录像中，1表示恢复中。
     */
    private int mRecordingStatus;
    private long muxerPausedMillis = 0L;
    private long mMuxerCuttingMillis = 0L;

    private static class FrameInfoQueue extends PriorityQueue<Client.FrameInfo> {
        public static final int CAPACITY = 500;
        public static final int INITIAL_CAPACITY = 300;

        public FrameInfoQueue() {
            super(INITIAL_CAPACITY, new Comparator<Client.FrameInfo>() {

                @Override
                public int compare(Client.FrameInfo frameInfo, Client.FrameInfo t1) {
                    return (int) (frameInfo.stamp - t1.stamp);
                }
            });
        }

        final ReentrantLock lock = new ReentrantLock();
        final Condition notFull = lock.newCondition();
        final Condition notVideo = lock.newCondition();
        final Condition notAudio = lock.newCondition();

        @Override
        public int size() {
            lock.lock();
            try {
                return super.size();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void clear() {
            lock.lock();
            try {
                int size = super.size();
                super.clear();
                int k = size;
                for (; k > 0 && lock.hasWaiters(notFull); k--)
                    notFull.signal();
            } finally {
                lock.unlock();
            }
        }

        public void put(Client.FrameInfo x) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                int size;
                while ((size = super.size()) == CAPACITY) {
                    Log.v(TAG, "queue full:" + CAPACITY);
                    notFull.await();
                }
                offer(x);
//                Log.d(TAG, String.format("queue size : " + size));
                // 这里是乱序的。并非只有空的queue才丢到首位。因此不能做限制 if (size == 0)
                {

                    if (x.audio) {
                        notAudio.signal();
                    } else {
                        notVideo.signal();
                    }
                }

            } finally {
                lock.unlock();
            }
        }

        public Client.FrameInfo takeVideoFrame() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (true) {
                    Client.FrameInfo x = peek();
                    if (x == null) {
                        notVideo.await();
                    } else {
                        if (!x.audio) {
                            remove();
                            notFull.signal();
                            notAudio.signal();
                            return x;
                        } else {
                            notVideo.await();
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        public Client.FrameInfo takeVideoFrame(long ms) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (true) {
                    Client.FrameInfo x = peek();
                    if (x == null) {
                        if (!notVideo.await(ms, TimeUnit.MILLISECONDS)) return null;
                    } else {
                        if (!x.audio) {
                            remove();
                            notFull.signal();
                            notAudio.signal();
                            return x;
                        } else {
                            notVideo.await();
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        public Client.FrameInfo takeAudioFrame() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (true) {
                    Client.FrameInfo x = peek();
                    if (x == null) {
                        notAudio.await();
                    } else {
                        if (x.audio) {
                            remove();
                            notFull.signal();
                            notVideo.signal();
                            return x;
                        } else {
                            notAudio.await();
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private FrameInfoQueue mQueue = new FrameInfoQueue();

    private final Context mContext;

    /**
     * 最新的视频时间戳
     */
    private volatile long mNewestStample;
    private boolean mWaitingKeyFrame;
    private boolean mTimeout;
    private boolean mNotSupportedVideoCB, mNotSupportedAudioCB;

    /**
     * 创建SDK对象
     *
     * @param context 上下文对象
     * @param key     SDK key
     * @param surface 显示视频用的surface
     */
    public EasyPlayerClient2(Context context, String key, Surface surface, ResultReceiver receiver, I420DataCallback callback, int index) {
        mSurface = surface;
        mContext = context;
        mKey = key;
        mRR = receiver;
        mIndex = index;
        i420callback = callback;
    }

    /**
     * 启动播放
     *
     * @param url
     * @param type
     * @param sendOption
     * @param mediaType
     * @param user
     * @param pwd
     * @return
     */
    public int start(final String url, int type, int sendOption, int mediaType, String user, String pwd) {
        if (url == null) {
            throw new NullPointerException("url is null");
        }

        if (type == 0)
            type = TRANSTYPE_TCP;

        mNewestStample = 0;
        mWaitingKeyFrame = PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean("waiting_i_frame", true);
        mWidth = mHeight = 0;
        mQueue.clear();
        startCodec();
        startAudio();
        mTimeout = false;
        mNotSupportedVideoCB = mNotSupportedAudioCB = false;
        mReceivedDataLength = 0;
        mClient = new Client(mContext, mKey);
        int channel = mClient.registerCallback(this);

        Log.i(TAG, mIndex + " >> " + String.format("playing url:\n%s\n", url));
        return mClient.openStream(channel, url, type, sendOption, mediaType, user, pwd);
    }

    public boolean isAudioEnable() {
        return mAudioEnable;
    }

    public void setAudioEnable(boolean enable) {
        mAudioEnable = enable;
        AudioTrack at = mAudioTrack;
        if (at != null) {
            Log.i(TAG, mIndex + " >> " + String.format("audio will be %s", enable ? "enabled" : "disabled"));
            synchronized (at) {
                if (!enable) {
                    at.pause();
                    at.flush();
                } else {
                    at.flush();
                    at.play();
                }
            }
        }
    }

    public interface I420DataCallback {
        void onI420Data(ByteBuffer buffer, int index, int w, int h);
        void onAACData(Client.FrameInfo frameInfo, int index);
    }

    public void pause() {
        mQueue.clear();
        if (mClient != null) {
            mClient.pause();
        }
        mQueue.clear();
    }

    public void resume() {
        if (mClient != null) {
            mClient.resume();
        }
    }

    /**
     * 终止播放
     */
    public void stop() {
        Thread t = mThread;
        mThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        t = mAudioThread;
        mAudioThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        stopRecord();

        mQueue.clear();
        if (mClient != null) {
            mClient.unrigisterCallback(this);
            mClient.closeStream();
            try {
                mClient.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mQueue.clear();
        mClient = null;
        mNewestStample = 0;
    }

    public long receivedDataLength() {
        return mReceivedDataLength;
    }

    private void startAudio() {
        mAudioThread = new Thread("AUDIO_CONSUMER") {

            @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                Client.FrameInfo frameInfo;
                long handle = 0;
                final AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

                AudioManager.OnAudioFocusChangeListener l = new AudioManager.OnAudioFocusChangeListener() {
                    @Override
                    public void onAudioFocusChange(int focusChange) {
                        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                            AudioTrack audioTrack = mAudioTrack;
                            if (audioTrack != null) {
                                audioTrack.setStereoVolume(1.0f, 1.0f);
                                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PAUSED) {
                                    audioTrack.flush();
                                    audioTrack.play();
                                }
                            }
                        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            AudioTrack audioTrack = mAudioTrack;
                            if (audioTrack != null) {
                                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                                    audioTrack.pause();
                                }
                            }
                        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                            AudioTrack audioTrack = mAudioTrack;
                            if (audioTrack != null) {
                                audioTrack.setStereoVolume(0.5f, 0.5f);
                            }
                        }
                    }
                };

                try {
                    int requestCode = am.requestAudioFocus(l, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                    if (requestCode != AUDIOFOCUS_REQUEST_GRANTED) {
                        return;
                    }

                    do {
                        frameInfo = mQueue.takeAudioFrame();
                        if (mMediaInfo != null)
                            break;
                    } while (true);

                    if (mAudioTrack == null) {
                        int sampleRateInHz = (int) (mMediaInfo.sample * 1.001);
                        int channelConfig = mMediaInfo.channel == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
                        int bfSize = AudioTrack.getMinBufferSize(mMediaInfo.sample, channelConfig, audioFormat) * 8;
                        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRateInHz, channelConfig, audioFormat, bfSize, AudioTrack.MODE_STREAM);
                    }

                    mAudioTrack.play();

                    handle = AudioCodec.create(frameInfo.codec, frameInfo.sample_rate, frameInfo.channels, frameInfo.bits_per_sample);

                    Log.w(TAG, mIndex + " >> " + String.format("POST VIDEO_DISPLAYED IN AUDIO THREAD!!!"));
                    ResultReceiver rr = mRR;
                    if (rr != null) rr.send(RESULT_VIDEO_DISPLAYED, null);

                    // 半秒钟的数据缓存
                    byte[] mBufferReuse = new byte[16000];
                    int[] outLen = new int[1];
                    while (mAudioThread != null) {
                        if (frameInfo == null) {
                            frameInfo = mQueue.takeAudioFrame();
                        }

                        outLen[0] = mBufferReuse.length;

                        int nRet = AudioCodec.decode(handle, frameInfo.buffer, 0, frameInfo.length, mBufferReuse, outLen);
                        if (nRet == 0) {
                            pumpPCMSample(mBufferReuse, outLen[0], frameInfo.stamp);

                            if (mAudioEnable)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    mAudioTrack.write(mBufferReuse, 0, outLen[0], AudioTrack.WRITE_NON_BLOCKING);
                                } else {
                                    mAudioTrack.write(mBufferReuse, 0, outLen[0]);
                                }
                        }

                        frameInfo = null;
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    am.abandonAudioFocus(l);
                    if (handle != 0) {
                        AudioCodec.close(handle);
                    }

                    AudioTrack track = mAudioTrack;
                    if (track != null) {
                        synchronized (track) {
                            mAudioTrack = null;
                            track.release();
                        }
                    }
                }
            }
        };

        mAudioThread.start();
    }

    private static int getXPS(byte[] data, int offset, int length, byte[] dataOut, int[] outLen, int type) {
        int i;
        int pos0;
        int pos1;
        pos0 = -1;
        length = Math.min(length, data.length);
        for (i = offset; i < length - 4; i++) {
            if ((0 == data[i]) && (0 == data[i + 1]) && (1 == data[i + 2]) && (type == (0x0F & data[i + 3]))) {
                pos0 = i;
                break;
            }
        }
        if (-1 == pos0) {
            return -1;
        }
        if (pos0 > 0 && data[pos0 - 1] == 0) { // 0 0 0 1
            pos0 = pos0 - 1;
        }
        pos1 = -1;
        for (i = pos0 + 4; i < length - 4; i++) {
            if ((0 == data[i]) && (0 == data[i + 1]) && (1 == data[i + 2])) {
                pos1 = i;
                break;
            }
        }
        if (-1 == pos1 || pos1 == 0) {
            return -2;
        }
        if (data[pos1 - 1] == 0) {
            pos1 -= 1;
        }
        if (pos1 - pos0 > outLen[0]) {
            return -3; // 输入缓冲区太小
        }
        dataOut[0] = 0;
        System.arraycopy(data, pos0, dataOut, 0, pos1 - pos0);
        // memcpy(pXPS+1, pES+pos0, pos1-pos0);
        // *pMaxXPSLen = pos1-pos0+1;
        outLen[0] = pos1 - pos0;
        return pos1;
    }

    private static byte[] getvps_sps_pps(byte[] data, int offset, int length) {
        int i = 0;
        int vps = -1, sps = -1, pps = -1;
        length = Math.min(length, data.length);
        do {
            if (vps == -1) {
                for (i = offset; i < length - 4; i++) {
                    if ((0x00 == data[i]) && (0x00 == data[i + 1]) && (0x01 == data[i + 2])) {
                        byte nal_spec = data[i + 3];
                        int nal_type = (nal_spec >> 1) & 0x03f;
                        if (nal_type == NAL_VPS) {
                            // vps found.
                            if (data[i - 1] == 0x00) {  // start with 00 00 00 01
                                vps = i - 1;
                            } else {                      // start with 00 00 01
                                vps = i;
                            }
                            break;
                        }
                    }
                }
            }
            if (sps == -1) {
                for (i = vps; i < length - 4; i++) {
                    if ((0x00 == data[i]) && (0x00 == data[i + 1]) && (0x01 == data[i + 2])) {
                        byte nal_spec = data[i + 3];
                        int nal_type = (nal_spec >> 1) & 0x03f;
                        if (nal_type == NAL_SPS) {
                            // vps found.
                            if (data[i - 1] == 0x00) {  // start with 00 00 00 01
                                sps = i - 1;
                            } else {                      // start with 00 00 01
                                sps = i;
                            }
                            break;
                        }
                    }
                }
            }
            if (pps == -1) {
                for (i = sps; i < length - 4; i++) {
                    if ((0x00 == data[i]) && (0x00 == data[i + 1]) && (0x01 == data[i + 2])) {
                        byte nal_spec = data[i + 3];
                        int nal_type = (nal_spec >> 1) & 0x03f;
                        if (nal_type == NAL_PPS) {
                            // vps found.
                            if (data[i - 1] == 0x00) {  // start with 00 00 00 01
                                pps = i - 1;
                            } else {                    // start with 00 00 01
                                pps = i;
                            }
                            break;
                        }
                    }
                }
            }
        } while (vps == -1 || sps == -1 || pps == -1);
        if (vps == -1 || sps == -1 || pps == -1) {// 没有获取成功。
            return null;
        }
        // 计算csd buffer的长度。即从vps的开始到pps的结束的一段数据
        int begin = vps;
        int end = -1;
        for (i = pps + 4; i < length - 4; i++) {
            if ((0x00 == data[i]) && (0x00 == data[i + 1]) && (0x01 == data[i + 2])) {
                if (data[i - 1] == 0x00) {  // start with 00 00 00 01
                    end = i - 1;
                } else {                    // start with 00 00 01
                    end = i;
                }
                break;
            }
        }
        if (end == -1 || end < begin) {
            return null;
        }
        // 拷贝并返回
        byte[] buf = new byte[end - begin];
        System.arraycopy(data, begin, buf, 0, buf.length);
        return buf;
    }

    private static boolean codecMatch(String mimeType, MediaCodecInfo codecInfo) {
        String[] types = codecInfo.getSupportedTypes();
        for (String type : types) {
            if (type.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }

        return false;
    }

    private static String codecName() {
        ArrayList<String> array = new ArrayList<>();
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i1 = 0; i1 < numCodecs; i1++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i1);
            if (codecInfo.isEncoder()) {
                continue;
            }

            if (codecMatch("video/avc", codecInfo)) {
                String name = codecInfo.getName();
                Log.d(TAG, String.format("decoder:%s", name));
                array.add(name);
            }
        }
//        if (array.remove("OMX.qcom.video.decoder.avc")) {
//            array.add("OMX.qcom.video.decoder.avc");
//        }
//        if (array.remove("OMX.amlogic.avc.decoder.awesome")) {
//            array.add("OMX.amlogic.avc.decoder.awesome");
//        }
        if (array.isEmpty()) {
            return "";
        }
        return array.get(0);
    }

    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    private void startCodec() {
        mThread = new Thread("VIDEO_CONSUMER2") {

            @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                MediaCodec mCodec = null;
                int mColorFormat = 0;
                VideoCodec.VideoDecoderLite mDecoder = null;

                try {
                    boolean pushBlankBuffersOnStop = true;

                    int index = 0;
                    // previous
                    long previousStampUs = 0l;
                    long lastFrameStampUs = 0l;
                    long differ = 0;
                    int realWidth = mWidth;
                    int realHeight = mHeight;
                    int sliceHeight = realHeight;

                    int frameWidth = 0;
                    int frameHeight = 0;
//
//                    long decodeBegin = 0;
//                    long current = 0;

                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                    Client.FrameInfo initFrameInfo = null;
                    Client.FrameInfo frameInfo = null;
                    while (mThread != null) {
                        if (mCodec == null && mDecoder == null) {
                            if (frameInfo == null) {
                                frameInfo = mQueue.takeVideoFrame();
                            }
                            initFrameInfo = frameInfo;

                            if (i420callback != null) {
                                final VideoCodec.VideoDecoderLite decoder = new VideoCodec.VideoDecoderLite();
                                decoder.create(mSurface, frameInfo.codec == EASY_SDK_VIDEO_CODEC_H264);
                                displayer = decoder;
                            }

                            try {
                                // 默认不用软解码
//                                if (PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean("use-sw-codec", false)) {
//                                    throw new IllegalStateException("user set sw codec");
//                                }

                                final String mime = frameInfo.codec == EASY_SDK_VIDEO_CODEC_H264 ? "video/avc" : "video/hevc";
                                MediaFormat format = MediaFormat.createVideoFormat(mime, mWidth, mHeight);
                                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
                                format.setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, pushBlankBuffersOnStop ? 1 : 0);
                                // 指定解码后的帧格式
                                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatYUV420Flexible);

                                if (mCSD0 != null) {
                                    format.setByteBuffer("csd-0", mCSD0);
                                } else {
                                    throw new InvalidParameterException("csd-0 is invalid.");
                                }

                                if (mCSD1 != null) {
                                    format.setByteBuffer("csd-1", mCSD1);
                                } else {
                                    if (frameInfo.codec == EASY_SDK_VIDEO_CODEC_H264)
                                        throw new InvalidParameterException("csd-1 is invalid.");
                                }

                                MediaCodecInfo ci = selectCodec(mime);
                                mColorFormat = CodecSpecificDataUtil.selectColorFormat(ci, mime);

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    MediaCodecInfo.CodecCapabilities capabilities = ci.getCapabilitiesForType(mime);
                                    MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
                                    boolean supported = videoCapabilities.isSizeSupported(mWidth, mHeight);
                                    Log.i(TAG, mIndex + " >> " + "media codec " + ci.getName() + (supported ? "support" : "not support") + mWidth + "*" + mHeight);
                                    if (!supported) {
                                        boolean b1 = videoCapabilities.getSupportedWidths().contains(mWidth + 0);
                                        boolean b2 = videoCapabilities.getSupportedHeights().contains(mHeight + 0);
                                        supported |= b1 && b2;
                                        if (supported) {
                                            Log.w(TAG, mIndex + " >> " + ".......................................................................");
                                        } else {
                                            throw new IllegalStateException("media codec " + ci.getName() + (supported ? "support" : "not support") + mWidth + "*" + mHeight);
                                        }
                                    }
                                }
                                Log.i(TAG, mIndex + " >> " + String.format("config codec:%s", format));

                                MediaCodec codec = MediaCodec.createByCodecName(ci.getName());
                                codec.configure(format, i420callback != null ? null : mSurface, null, 0);
                                codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                                codec.start();

                                mCodec = codec;
                            } catch (Throwable e) {
                                if (mCodec != null) mCodec.release();
                                mCodec = null;

                                Log.e(TAG, mIndex + " >> " + String.format("init codec error due to %s", e.getMessage()));
                                e.printStackTrace();

                                final VideoCodec.VideoDecoderLite decoder = new VideoCodec.VideoDecoderLite();
                                decoder.create(mSurface, frameInfo.codec == EASY_SDK_VIDEO_CODEC_H264);
                                mDecoder = decoder;
                            }

//                            previewTickUs = mTexture.getTimestamp();
//                            differ = previewTickUs - frameInfo.stamp;
//                            index = mCodec.dequeueInputBuffer(0);
//                            if (index >= 0) {
//                                ByteBuffer buffer = mCodec.getInputBuffers()[index];
//                                buffer.clear();
//                                mCSD0.clear();
//                                mCSD1.clear();
//                                buffer.put(mCSD0.array(), 0, mCSD0.remaining());
//                                buffer.put(mCSD1.array(), 0, mCSD1.remaining());
//                                mCodec.queueInputBuffer(index, 0, buffer.position(), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
//                            }
                        } else {
                            frameInfo = mQueue.takeVideoFrame(5);
                        }

                        if (frameInfo != null) {
                            Log.d(TAG, mIndex + " >> " + "video " + frameInfo.stamp + " take[" + (frameInfo.stamp - lastFrameStampUs) + "]");
                            if (frameHeight != 0 && frameWidth != 0) {
                                if (frameInfo.width != 0 && frameInfo.height != 0) {
                                    if (frameInfo.width != frameWidth || frameInfo.height != frameHeight) {
                                        frameHeight = frameInfo.height;
                                        frameWidth = frameInfo.width;

                                        if (mCodec != null) {
                                            mCodec.release();
                                            mCodec = null;
                                            continue;
                                        }
                                    }
                                }
                            }
                            frameHeight = frameInfo.height;
                            frameWidth = frameInfo.width;

                            lastFrameStampUs = frameInfo.stamp;
                        }

                        do {
                            if (mDecoder != null) {
                                if (frameInfo != null) {
                                    long decodeBegin = SystemClock.elapsedRealtime();
                                    int[] size = new int[2];

//                                mDecoder.decodeFrame(frameInfo, size);
//                                    ByteBuffer buf = mDecoder.decodeFrameYUV(frameInfo, size);
                                    ByteBuffer buf = mDecoder.decodeFrameYUV0(frameInfo, size);

                                    if (i420callback != null && buf != null) {
                                        i420callback.onI420Data(buf, mIndex, frameWidth, frameHeight);
                                    }

                                    if (buf != null) {
                                        mDecoder.releaseBuffer(buf);
                                    }

                                    long decodeSpend = SystemClock.elapsedRealtime() - decodeBegin;

                                    boolean firstFrame = previousStampUs == 0l;
                                    if (firstFrame) {
                                        Log.i(TAG, mIndex + " >> " + String.format("POST VIDEO_DISPLAYED!!!"));
                                        ResultReceiver rr = mRR;
                                        if (rr != null) {
                                            Bundle data = new Bundle();
                                            data.putInt(KEY_VIDEO_DECODE_TYPE, 0);
                                            rr.send(RESULT_VIDEO_DISPLAYED, data);
                                        }
                                    }

                                    //Log.d(TAG, mIndex + " >> " + String.format("timestamp=%d diff=%d",current, current - previousStampUs ));

                                    if (previousStampUs != 0l) {
                                        long sleepTime = frameInfo.stamp - previousStampUs - decodeSpend * 1000;
                                        if (sleepTime > 100000) {
                                            Log.w(TAG, mIndex + " >> " + "sleep time.too long:" + sleepTime);
                                            sleepTime = 100000;
                                        }

                                        if (sleepTime > 0) {
                                            sleepTime %= 100000;
                                            long cache = mNewestStample - frameInfo.stamp;
                                            sleepTime = fixSleepTime(sleepTime, cache, 50000);
                                            if (sleepTime > 0) {
                                                Thread.sleep(sleepTime / 1000);
                                            }
                                            Log.d(TAG, mIndex + " >> " + "cache:" + cache);
                                        }
                                    }

                                    previousStampUs = frameInfo.stamp;
                                }
                            } else {
                                try {
                                    do {
                                        if (frameInfo != null) {
                                            byte[] pBuf = frameInfo.buffer;
                                            index = mCodec.dequeueInputBuffer(10);
                                            if (false)
                                                throw new IllegalStateException("fake state");
                                            if (index >= 0) {
                                                ByteBuffer buffer = mCodec.getInputBuffers()[index];
                                                buffer.clear();
                                                if (pBuf.length > buffer.remaining()) {
                                                    mCodec.queueInputBuffer(index, 0, 0, frameInfo.stamp, 0);
                                                } else {
                                                    buffer.put(pBuf, frameInfo.offset, frameInfo.length);
                                                    mCodec.queueInputBuffer(index, 0, buffer.position(), frameInfo.stamp + differ, 0);
                                                }
                                                frameInfo = null;
                                            }
                                        }

                                        index = mCodec.dequeueOutputBuffer(info, 10); //
                                        switch (index) {
                                            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                                                Log.i(TAG, mIndex + " >> " + "INFO_OUTPUT_BUFFERS_CHANGED");
                                                break;
                                            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                                                MediaFormat mf = mCodec.getOutputFormat();
                                                Log.i(TAG, mIndex + " >> " + "INFO_OUTPUT_FORMAT_CHANGED ：" + mf);
                                                int width = mf.getInteger(MediaFormat.KEY_WIDTH);
                                                if (mf.containsKey("crop-left") && mf.containsKey("crop-right")) {
                                                    width = mf.getInteger("crop-right") + 1 - mf.getInteger("crop-left");
                                                }
                                                int height = mf.getInteger(MediaFormat.KEY_HEIGHT);
                                                if (mf.containsKey("crop-top") && mf.containsKey("crop-bottom")) {
                                                    height = mf.getInteger("crop-bottom") + 1 - mf.getInteger("crop-top");
                                                }
                                                realWidth = width;
                                                realHeight = height;

                                                if (mf.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) {
                                                    sliceHeight = mf.getInteger(MediaFormat.KEY_SLICE_HEIGHT);
                                                } else {
                                                    sliceHeight = realHeight;
                                                }
                                                break;
                                            case MediaCodec.INFO_TRY_AGAIN_LATER:
                                                // 输出为空
                                                break;
                                            default:
                                                // 输出队列不为空
                                                // -1表示为第一帧数据
                                                long newSleepUs = -1;
                                                boolean firstTime = previousStampUs == 0l;
                                                if (!firstTime) {
                                                    long sleepUs = (info.presentationTimeUs - previousStampUs);
                                                    if (sleepUs > 100000) {
                                                        // 时间戳异常，可能服务器丢帧了。
                                                        Log.w(TAG, mIndex + " >> " + "sleep time.too long:" + sleepUs);
                                                        sleepUs = 100000;
                                                    } else if (sleepUs < 0) {
                                                        Log.w(TAG, mIndex + " >> " + "sleep time.too short:" + sleepUs);
                                                        sleepUs = 0;
                                                    }

                                                    {
                                                        long cache = mNewestStample - lastFrameStampUs;
                                                        newSleepUs = fixSleepTime(sleepUs, cache, 100000);
                                                        // Log.d(TAG, mIndex + " >> " + String.format("sleepUs:%d,newSleepUs:%d,Cache:%d", sleepUs, newSleepUs, cache));
                                                    }
                                                }

                                                //previousStampUs = info.presentationTimeUs;
                                                ByteBuffer outputBuffer;
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                                    outputBuffer = mCodec.getOutputBuffer(index);
                                                } else {
                                                    outputBuffer = mCodec.getOutputBuffers()[index];
                                                }

                                                if (i420callback != null && outputBuffer != null) {
                                                    if (sliceHeight != realHeight) {
                                                        ByteBuffer tmp = ByteBuffer.allocateDirect(realWidth * realHeight * 3 / 2);
                                                        outputBuffer.clear();
                                                        outputBuffer.limit(realWidth * realHeight);
                                                        tmp.put(outputBuffer);

                                                        outputBuffer.clear();
                                                        outputBuffer.position(realWidth * sliceHeight);
                                                        outputBuffer.limit((realWidth * sliceHeight + realWidth * realHeight / 4));
                                                        tmp.put(outputBuffer);

                                                        outputBuffer.clear();
                                                        outputBuffer.position(realWidth * sliceHeight + realWidth * realHeight / 4);
                                                        outputBuffer.limit((realWidth * sliceHeight + realWidth * realHeight / 4 + realWidth * realHeight / 4));
                                                        tmp.put(outputBuffer);

                                                        tmp.clear();
                                                        outputBuffer = tmp;
                                                    }

                                                    if (mColorFormat == COLOR_FormatYUV420SemiPlanar
                                                            || mColorFormat == COLOR_FormatYUV420PackedSemiPlanar
                                                            || mColorFormat == COLOR_TI_FormatYUV420PackedSemiPlanar) {

                                                        byte[] in = new byte[realWidth * realHeight * 3 / 2];
                                                        outputBuffer.clear();
                                                        outputBuffer.get(in);

                                                        // yuvuv_to_yuv
                                                        JNIUtil.yuvConvert(in, realWidth, realHeight, 4);

                                                        ByteBuffer tmp = ByteBuffer.allocateDirect(realWidth * realHeight * 3 / 2);
                                                        tmp.clear();
                                                        tmp.put(in);

                                                        i420callback.onI420Data(tmp, mIndex, realWidth, realHeight);
                                                    }
                                                }

                                                if (false && Build.VERSION.SDK_INT >= 21) {
                                                    Log.d(TAG, mIndex + " >> " + String.format("releaseoutputbuffer:%d,stampUs:%d", index, previousStampUs));
                                                    mCodec.releaseOutputBuffer(index, previousStampUs);
                                                } else {
                                                    if (newSleepUs < 0) {
                                                        newSleepUs = 0;
                                                    }

                                                    Thread.sleep(newSleepUs / 1000);
                                                    mCodec.releaseOutputBuffer(index, i420callback == null);
                                                }

                                                if (firstTime) {
                                                    Log.i(TAG, mIndex + " >> " + String.format("POST VIDEO_DISPLAYED!!!"));
                                                    ResultReceiver rr = mRR;
                                                    if (rr != null) {
                                                        Bundle data = new Bundle();
                                                        data.putInt(KEY_VIDEO_DECODE_TYPE, 1);
                                                        rr.send(RESULT_VIDEO_DISPLAYED, data);
                                                    }
                                                }
                                                previousStampUs = info.presentationTimeUs;
                                        }

                                    } while (frameInfo != null || index < MediaCodec.INFO_TRY_AGAIN_LATER);
                                } catch (IllegalStateException ex) {
                                    // mediacodec error...

                                    ex.printStackTrace();

                                    Log.e(TAG, mIndex + " >> " + String.format("init codec error due to %s", ex.getMessage()));

                                    if (mCodec != null) mCodec.release();
                                    mCodec = null;

                                    if (displayer != null) displayer.close();
                                    displayer = null;

                                    final VideoCodec.VideoDecoderLite decoder = new VideoCodec.VideoDecoderLite();
                                    decoder.create(mSurface, initFrameInfo.codec == EASY_SDK_VIDEO_CODEC_H264);
                                    mDecoder = decoder;
                                    continue;
                                }

                            }
                            break;
                        } while (true);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (mCodec != null) {
//                        mCodec.stop();
                        mCodec.release();
                    }
                    if (mDecoder != null) {
                        mDecoder.close();
                    }
                    if (displayer != null) {
                        displayer.close();
                    }
                }
            }
        };
        mThread.start();
    }

    VideoCodec.VideoDecoderLite displayer = null;
    public void show(ByteBuffer tmp, int w, int h) {
        if (displayer != null && tmp.capacity() > 0) {
            displayer.decoder_decodeBuffer(tmp, w, h);
        }
    }

    private static final long fixSleepTime(long sleepTimeUs, long totalTimestampDifferUs, long delayUs) {
        if (totalTimestampDifferUs < 0l) {
            Log.w(TAG, String.format("totalTimestampDifferUs is:%d, this should not be happen.", totalTimestampDifferUs));
            totalTimestampDifferUs = 0;
        }

        double dValue = ((double) (delayUs - totalTimestampDifferUs)) / 1000000d;
        double radio = Math.exp(dValue);
        double r = sleepTimeUs * radio + 0.5f;
        Log.i(TAG, String.format("%d,%d,%d->%d", sleepTimeUs, totalTimestampDifferUs, delayUs, (int) r));
        return (long) r;
    }

    public synchronized void pauseRecord() {
        if (mRecordingStatus != -1) {
            mRecordingStatus = -1;
            muxerPausedMillis = SystemClock.elapsedRealtime();
        }
    }

    public synchronized void resumeRecord() {
        if (mRecordingStatus == -1) {
            mMuxerWaitingKeyVideo = true;
            mRecordingStatus = 1;
        }
    }

    private static int getSampleIndex(int sample) {
        for (int i = 0; i < AUDIO_SPECIFIC_CONFIG_SAMPLING_RATE_TABLE.length; i++) {
            if (sample == AUDIO_SPECIFIC_CONFIG_SAMPLING_RATE_TABLE[i]) {
                return i;
            }
        }
        return -1;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Override
    public void onSourceCallBack(int _channelId, int _channelPtr, int _frameType, Client.FrameInfo frameInfo) {
//        long begin = SystemClock.elapsedRealtime();
        try {
            onRTSPSourceCallBack1(_channelId, _channelPtr, _frameType, frameInfo);
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
//            Log.d(TAG, mIndex + " >> " + String.format("onRTSPSourceCallBack %d", SystemClock.elapsedRealtime() - begin));
        }
    }

    public void onRTSPSourceCallBack1(int _channelId, int _channelPtr, int _frameType, Client.FrameInfo frameInfo) {
        Thread.currentThread().setName("PRODUCER_THREAD");
        if (frameInfo != null) {
            mReceivedDataLength += frameInfo.length;
        }
        if (_frameType == Client.EASY_SDK_VIDEO_FRAME_FLAG) {
            //Log.d(TAG, mIndex + " >> " + String.format("receive video frame"));
            if (frameInfo.codec != EASY_SDK_VIDEO_CODEC_H264 && frameInfo.codec != EASY_SDK_VIDEO_CODEC_H265) {
                ResultReceiver rr = mRR;
                if (!mNotSupportedVideoCB && rr != null) {
                    mNotSupportedVideoCB = true;
                    rr.send(RESULT_UNSUPPORTED_VIDEO, null);
                }
                return;
            }

            if (frameInfo.width == 0 || frameInfo.height == 0) {
                return;
            }

            if (frameInfo.length >= 4) {
                if (frameInfo.buffer[0] == 0 && frameInfo.buffer[1] == 0 && frameInfo.buffer[2] == 0 && frameInfo.buffer[3] == 1) {
                    if (frameInfo.length >= 8) {
                        if (frameInfo.buffer[4] == 0 && frameInfo.buffer[5] == 0 && frameInfo.buffer[6] == 0 && frameInfo.buffer[7] == 1) {
                            frameInfo.offset += 4;
                            frameInfo.length -= 4;
                        }
                    }
                }
            }

//            int offset = frameInfo.offset;
//            byte nal_unit_type = (byte) (frameInfo.buffer[offset + 4] & (byte) 0x1F);
//            if (nal_unit_type == 7 || nal_unit_type == 5) {
//                Log.i(TAG, mIndex + " >> " + String.format("recv I frame"));
//            }

            if (frameInfo.type == 1) {
                Log.i(TAG, mIndex + " >> " + String.format("recv I frame"));
            }

            mNewestStample = frameInfo.stamp;
            frameInfo.audio = false;

            if (mWaitingKeyFrame) {
                ResultReceiver rr = mRR;
                Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_VIDEO_WIDTH, frameInfo.width);
                bundle.putInt(EXTRA_VIDEO_HEIGHT, frameInfo.height);
                mWidth = frameInfo.width;
                mHeight = frameInfo.height;
                Log.i(TAG, mIndex + " >> " + String.format("RESULT_VIDEO_SIZE:%d*%d", frameInfo.width, frameInfo.height));
                if (rr != null) rr.send(RESULT_VIDEO_SIZE, bundle);

                Log.i(TAG, mIndex + " >> " + String.format("width:%d,height:%d", mWidth, mHeight));

                if (frameInfo.codec == EASY_SDK_VIDEO_CODEC_H264) {
                    byte[] dataOut = new byte[128];
                    int[] outLen = new int[]{128};
                    int result = getXPS(frameInfo.buffer, 0, 256, dataOut, outLen, 7);
                    if (result >= 0) {
                        ByteBuffer csd0 = ByteBuffer.allocate(outLen[0]);
                        csd0.put(dataOut, 0, outLen[0]);
                        csd0.clear();
                        mCSD0 = csd0;
                        Log.i(TAG, mIndex + " >> " + String.format("CSD-0 searched"));
                    }

                    outLen[0] = 128;
                    result = getXPS(frameInfo.buffer, 0, 256, dataOut, outLen, 8);
                    if (result >= 0) {
                        ByteBuffer csd1 = ByteBuffer.allocate(outLen[0]);
                        csd1.put(dataOut, 0, outLen[0]);
                        csd1.clear();
                        mCSD1 = csd1;
                        Log.i(TAG, mIndex + " >> " + String.format("CSD-1 searched"));
                    }

                    if (false) {
                        int off = (result - frameInfo.offset);
                        frameInfo.offset += off;
                        frameInfo.length -= off;
                    }
                } else {
                    byte[] spsPps = getvps_sps_pps(frameInfo.buffer, 0, 256);
                    if (spsPps != null) {
                        mCSD0 = ByteBuffer.wrap(spsPps);
                    }
                }

                if (frameInfo.type != 1) {
                    Log.w(TAG, mIndex + " >> " + String.format("discard p frame."));
                    return;
                }
                mWaitingKeyFrame = false;
            } else {
                int width = frameInfo.width;
                int height = frameInfo.height;
                if (width != 0 && height != 0)
                    if (width != mWidth || height != mHeight) {
                        // resolution change...
                        ResultReceiver rr = mRR;
                        Bundle bundle = new Bundle();
                        bundle.putInt(EXTRA_VIDEO_WIDTH, frameInfo.width);
                        bundle.putInt(EXTRA_VIDEO_HEIGHT, frameInfo.height);
                        mWidth = frameInfo.width;
                        mHeight = frameInfo.height;
                        Log.i(TAG, String.format("RESULT_VIDEO_SIZE:%d*%d", frameInfo.width, frameInfo.height));
                        if (rr != null) rr.send(RESULT_VIDEO_SIZE, bundle);
                    }
            }
//            Log.d(TAG, mIndex + " >> " + String.format("queue size :%d", mQueue.size()));
            try {
                mQueue.put(frameInfo);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else if (_frameType == Client.EASY_SDK_AUDIO_FRAME_FLAG) {
            mNewestStample = frameInfo.stamp;
            frameInfo.audio = true;

            if (frameInfo.codec == EASY_SDK_AUDIO_CODEC_AAC) {
                if (i420callback != null) {
                    i420callback.onAACData(frameInfo, mIndex);
                }
            }

            if (frameInfo.codec != EASY_SDK_AUDIO_CODEC_AAC &&
                    frameInfo.codec != EASY_SDK_AUDIO_CODEC_G711A &&
                    frameInfo.codec != EASY_SDK_AUDIO_CODEC_G711U &&
                    frameInfo.codec != EASY_SDK_AUDIO_CODEC_G726) {
                ResultReceiver rr = mRR;
                if (!mNotSupportedAudioCB && rr != null) {
                    mNotSupportedAudioCB = true;
                    if (rr != null) {
                        rr.send(RESULT_UNSUPPORTED_AUDIO, null);
                    }
                }
                return;
            }

//            Log.d(TAG, mIndex + " >> " + String.format("queue size :%d", mQueue.size()));
            try {
                mQueue.put(frameInfo);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else if (_frameType == 0) {
            if (!mTimeout) {
                mTimeout = true;

                ResultReceiver rr = mRR;
                if (rr != null) rr.send(RESULT_TIMEOUT, null);
            }
        } else if (_frameType == Client.EASY_SDK_EVENT_FRAME_FLAG) {
            ResultReceiver rr = mRR;
            Bundle resultData = new Bundle();
            resultData.putString("event-msg", new String(frameInfo.buffer));
            if (rr != null) rr.send(RESULT_EVENT, null);
        }
    }

    @Override
    public void onMediaInfoCallBack(int _channelId, Client.MediaInfo mi) {
        mMediaInfo = mi;
        Log.i(TAG, mIndex + " >> " + String.format("MediaInfo fetchd\n%s", mi));
    }

    @Override
    public void onEvent(int channel, int err, int info) {
        ResultReceiver rr = mRR;
        Bundle resultData = new Bundle();

        switch (info) {
            case 1:
                resultData.putString("event-msg", "连接中...");
                break;
            case 2:
                resultData.putInt("errorcode", err);
                resultData.putString("event-msg", String.format("错误：%d", err));
                break;
            case 3:
                resultData.putInt("errorcode", err);
                resultData.putString("event-msg", String.format("线程退出。%d", err));
                break;
        }
        if (rr != null) rr.send(RESULT_EVENT, resultData);
    }

    /* ======================== 录像 ======================== */

    private EasyMuxer2 muxer2;
    private String mRecordingPath;

    public boolean isRecording() {
        return !TextUtils.isEmpty(mRecordingPath);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    public synchronized void startRecord(String path) {
        if (mIndex != 1 || mMediaInfo == null || mWidth == 0 || mHeight == 0 || mCSD0 == null)
            return;

        mRecordingPath = path;
        EasyMuxer2 muxer2 = new EasyMuxer2();
        mMuxerCuttingMillis = 0l;
        mRecordingStatus = 0;
        muxerPausedMillis = 0;
        ByteBuffer csd1 = this.mCSD1;
        if (csd1 == null) csd1 = ByteBuffer.allocate(0);
        byte[] extra = new byte[mCSD0.capacity() + csd1.capacity()];
        mCSD0.clear();
        csd1.clear();
        mCSD0.get(extra, 0, mCSD0.capacity());
        csd1.get(extra, mCSD0.capacity(), csd1.capacity());

        int r = muxer2.create(path,
                mMediaInfo.videoCodec == EASY_SDK_VIDEO_CODEC_H265 ? VIDEO_TYPE_H265 : VIDEO_TYPE_H264,
                mWidth, mHeight,
                extra,
                mMediaInfo.sample, mMediaInfo.channel);

        if (r != 0) {
            Log.w(TAG, mIndex + " >> " + "create muxer2:" + r);
            return;
        }

        mMuxerWaitingKeyVideo = true;
        this.muxer2 = muxer2;

        ResultReceiver rr = mRR;
        if (rr != null) {
            rr.send(RESULT_RECORD_BEGIN, null);
        }
    }

    public synchronized void stopRecord() {
        mRecordingPath = null;
        mMuxerCuttingMillis = 0l;
        mRecordingStatus = 0;
        muxerPausedMillis = 0;

        EasyMuxer2 muxer2 = this.muxer2;
        if (muxer2 == null)
            return;
        this.muxer2 = null;
        muxer2.close();

        ResultReceiver rr = mRR;
        if (rr != null) {
            rr.send(RESULT_RECORD_END, null);
        }
    }

    private synchronized void pumpPCMSample(byte[] pcm, int length, long stampUS) {
        EasyMuxer2 muxer2 = this.muxer2;
        if (muxer2 == null)
            return;

        if (mRecordingStatus < 0)
            return;

        if (mMuxerWaitingKeyVideo) {
            Log.i(TAG, mIndex + " >> " + "writeFrame ignore due to no key frame!");
            return;
        }

        long timeStampMillis = stampUS / 1000;
        timeStampMillis -= mMuxerCuttingMillis;
        timeStampMillis = Math.max(0, timeStampMillis);
        int r = muxer2.writeFrame(EasyMuxer2.AVMEDIA_TYPE_AUDIO, pcm, 0, length, timeStampMillis);
        Log.i(TAG, mIndex + " >> " + "writeFrame audio ret:" + r);
    }

    public synchronized void pumpVideoSample(Client.FrameInfo frameInfo) {
        EasyMuxer2 muxer2 = this.muxer2;
        if (muxer2 == null)
            return;

        if (mRecordingStatus < 0)
            return;

        if (mMuxerWaitingKeyVideo) {
            if (frameInfo.type == 1) {
                mMuxerWaitingKeyVideo = false;
                if (mRecordingStatus == 1) {
                    mMuxerCuttingMillis += SystemClock.elapsedRealtime() - muxerPausedMillis;
                    mRecordingStatus = 0;
                }
            }
        }

        if (mMuxerWaitingKeyVideo) {
            Log.i(TAG, mIndex + " >> " + "writeFrame ignore due to no key frame!");
            return;
        }

        if (frameInfo.type == 1) {
//            frameInfo.offset = 60;
//            frameInfo.length -= 60;
        }

        long timeStampMillis = frameInfo.stamp / 1000;
        timeStampMillis -= mMuxerCuttingMillis;
        timeStampMillis = Math.max(0, timeStampMillis);

        int r = muxer2.writeFrame(EasyMuxer2.AVMEDIA_TYPE_VIDEO,
                frameInfo.buffer,
                frameInfo.offset,
                frameInfo.length,
                timeStampMillis);
        Log.i(TAG, mIndex + " >> " + "writeFrame video ret:" + r);
    }
}