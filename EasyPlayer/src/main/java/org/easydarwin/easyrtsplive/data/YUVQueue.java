package org.easydarwin.easyrtsplive.data;

import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class YUVQueue extends PriorityQueue<ByteBuffer> {
    private static final String TAG = YUVQueue.class.getSimpleName();

    public static final int CAPACITY = 50;
    public static final int INITIAL_CAPACITY = 50;

    public YUVQueue() {
        super(INITIAL_CAPACITY, new Comparator<ByteBuffer>() {

            @Override
            public int compare(ByteBuffer t0, ByteBuffer t1) {
                return 1;
            }
        });
    }

    final ReentrantLock lock = new ReentrantLock();
    final Condition notFull = lock.newCondition();
    final Condition notVideo = lock.newCondition();

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

    public void put(ByteBuffer buffer) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            int size;
            while ((size = super.size()) == CAPACITY) {
                Log.v(TAG, "queue full:" + CAPACITY);
                notFull.await();
            }

            offer(buffer);

            notVideo.signal();
        } finally {
            lock.unlock();
        }
    }

    public ByteBuffer take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                ByteBuffer buffer = peek();
                if (buffer == null) {
                    notVideo.await();
                } else {
                    remove();
                    notFull.signal();
                    return buffer;
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
