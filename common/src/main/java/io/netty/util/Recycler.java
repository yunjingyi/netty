/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY = 32768; // Use 32k instances as default max capacity.

    private static final int DEFAULT_MAX_CAPACITY;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int LINK_CAPACITY;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacity = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY);
        if (maxCapacity < 0) {
            maxCapacity = DEFAULT_INITIAL_MAX_CAPACITY;
        }
        DEFAULT_MAX_CAPACITY = maxCapacity;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        LINK_CAPACITY = MathUtil.findNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacity: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacity: {}", DEFAULT_MAX_CAPACITY);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY, 256);
    }

    private final int maxCapacity;
    private final int maxSharedCapacityFactor;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacity, maxSharedCapacityFactor);
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY);
    }

    protected Recycler(int maxCapacity) {
        this(maxCapacity, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacity, int maxSharedCapacityFactor) {
        if (maxCapacity <= 0) {
            this.maxCapacity = 0;
            this.maxSharedCapacityFactor = 1;
        } else {
            this.maxCapacity = maxCapacity;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        if (maxCapacity == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        Stack<T> stack = threadLocal.get();
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> {
        void recycle(T object);
    }

    static final class DefaultHandle<T> implements Handle<T> {
        private int lastRecycledId;
        private int recycleId;

        private Stack<?> stack;
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }
            Thread thread = Thread.currentThread();
            if (thread == stack.thread) {
                stack.push(this);
                return;
            }
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            WeakOrderQueue queue = delayedRecycled.get(stack);
            if (queue == null) {
                queue = WeakOrderQueue.allocate(stack, thread);
                if (queue == null) {
                    // drop object
                    return;
                }
                delayedRecycled.put(stack, queue);
            }
            queue.add(this);
        }
    }

    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue {

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        private static final class Link extends AtomicInteger {
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            private int readIndex;
            private Link next;
        }

        // chain of data items
        private Link head, tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final WeakReference<Thread> owner;
        private final int id = ID_GENERATOR.getAndIncrement();
        private final AtomicInteger availableSharedCapacity;

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            head = tail = new Link();
            owner = new WeakReference<Thread>(thread);
            synchronized (stack) {
                next = stack.head;
                stack.head = this;
            }

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            availableSharedCapacity = stack.availableSharedCapacity;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            return reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? new WeakOrderQueue(stack, thread) : null;
        }

        private static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
            assert space >= 0;
            for (;;) {
                int available = availableSharedCapacity.get();
                if (available < space) {
                    return false;
                }
                if (availableSharedCapacity.compareAndSet(available, available - space)) {
                    return true;
                }
            }
        }

        private void reclaimSpace(int space) {
            assert space >= 0;
            availableSharedCapacity.addAndGet(space);
        }

        void add(DefaultHandle<?> handle) {
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                if (!reserveSpace(availableSharedCapacity, LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head;
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head = head = head.next;
            }

            final int srcStart = head.readIndex;
            int srcEnd = head.get();
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;

            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                    srcElems[i] = null;
                }
                dst.size = newDstSize;

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    reclaimSpace(LINK_CAPACITY);

                    this.head = head.next;
                }

                head.readIndex = srcEnd;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                super.finalize();
            } finally {
                // We need to reclaim all space that was reserved by this WeakOrderQueue so we not run out of space in
                // the stack. This is needed as we not have a good life-time control over the queue as it is used in a
                // WeakHashMap which will drop it at any time.
                Link link = head;
                while (link != null) {
                    reclaimSpace(LINK_CAPACITY);
                    link = link.next;
                }
            }
        }
    }

    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;
        final Thread thread;
        private DefaultHandle<?>[] elements;
        private final int maxCapacity;
        private int size;
        final AtomicInteger availableSharedCapacity;

        private volatile WeakOrderQueue head;
        private WeakOrderQueue cursor, prev;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor) {
            this.parent = parent;
            this.thread = thread;
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
            }
            size --;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }

        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        boolean scavengeSome() {
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            }

            boolean success = false;
            WeakOrderQueue prev = this.prev;
            do {
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }

                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }
                    if (prev != null) {
                        prev.next = next;
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            if (size >= maxCapacity) {
                // Hit the maximum capacity - drop the possibly youngest object.
                return;
            }
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
