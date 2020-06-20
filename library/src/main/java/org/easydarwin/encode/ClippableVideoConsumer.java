package org.easydarwin.encode;

import android.content.Context;

import org.easydarwin.sw.JNIUtil;

public class ClippableVideoConsumer implements VideoConsumer {

    private final VideoConsumer consumer;

    private final int width;
    private final int height;

    private final Context context;

    private int originalWidth, originalHeight;
    private byte[] i420_buffer2;

    /**
     *
     * @param context   context
     * @param consumer  the consumer which will consume the clipped video.
     * @param width     clipped video width
     * @param height    clipped video height
     */
    public ClippableVideoConsumer(Context context, VideoConsumer consumer, int width, int height) {
        this.context = context;
        this.consumer = consumer;
        this.width = width;
        this.height = height;

        i420_buffer2 = new byte[width * height * 3 / 2];
    }

    @Override
    public void onVideoStart(int width, int height) {
        originalHeight = height;
        originalWidth = width;

        consumer.onVideoStart(this.width,this.height);
    }

    @Override
    public int onVideo(byte[] data, int format) {
        JNIUtil.I420Scale(data, i420_buffer2, originalWidth, originalHeight, width, height,0);

        return consumer.onVideo(i420_buffer2, format);
    }

    @Override
    public void onVideoStop() {
        consumer.onVideoStop();
    }
}
