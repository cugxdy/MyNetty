/*
 * Copyright 2012 The Netty Project
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

package io.netty.buffer;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakTracker;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

/**
 * Skeletal {@link ByteBufAllocator} implementation to extend.
 */
public abstract class AbstractByteBufAllocator implements ByteBufAllocator {
    
	// ����ByteBuf����Ĭ�ϳ�ʼ������
	static final int DEFAULT_INITIAL_CAPACITY = 256;
	
	// ����ByteBuf����Ĭ���������
    static final int DEFAULT_MAX_CAPACITY = Integer.MAX_VALUE;
    
    // ����CompositeByteBuf����Ĭ�����ByteBuf������Ŀ
    static final int DEFAULT_MAX_COMPONENTS = 16;
    
    // 4M
    static final int CALCULATE_THRESHOLD = 1048576 * 4; // 4 MiB page

    static {
        ResourceLeakDetector.addExclusions(AbstractByteBufAllocator.class, "toLeakAwareBuffer");
    }

    @SuppressWarnings("incomplete-switch")
	protected static ByteBuf toLeakAwareBuffer(ByteBuf buf) {
        ResourceLeakTracker<ByteBuf> leak;
        switch (ResourceLeakDetector.getLevel()) {
            case SIMPLE:
                leak = AbstractByteBuf.leakDetector.track(buf);
                if (leak != null) {
                    buf = new SimpleLeakAwareByteBuf(buf, leak);
                }
                break;
            case ADVANCED:
            case PARANOID:
                leak = AbstractByteBuf.leakDetector.track(buf);
                if (leak != null) {
                    buf = new AdvancedLeakAwareByteBuf(buf, leak);
                }
                break;
        }
        return buf;
    }

    protected static CompositeByteBuf toLeakAwareBuffer(CompositeByteBuf buf) {
        ResourceLeakTracker<ByteBuf> leak;
        switch (ResourceLeakDetector.getLevel()) {
            case SIMPLE:
                leak = AbstractByteBuf.leakDetector.track(buf);
                if (leak != null) {
                    buf = new SimpleLeakAwareCompositeByteBuf(buf, leak);
                }
                break;
            case ADVANCED:
            case PARANOID:
                leak = AbstractByteBuf.leakDetector.track(buf);
                if (leak != null) {
                    buf = new AdvancedLeakAwareCompositeByteBuf(buf, leak);
                }
                break;
            default:
                break;
        }
        return buf;
    }

    // �Ƿ����������ڴ�,Ĭ��Ϊfalse
    private final boolean directByDefault;
    // ���ؿն���emptyBuf
    private final ByteBuf emptyBuf;

    /**
     * Instance use heap buffers by default
     */
    // ����AbstractByteBufAllocator����,ʹ�ö��ڴ�
    protected AbstractByteBufAllocator() {
        this(false);
    }

    /**
     * Create new instance
     *
     * @param preferDirect {@code true} if {@link #buffer(int)} should try to allocate a direct buffer rather than
     *                     a heap buffer
     */
    // ����
    protected AbstractByteBufAllocator(boolean preferDirect) {
        directByDefault = preferDirect && PlatformDependent.hasUnsafe();
        emptyBuf = new EmptyByteBuf(this);
    }

    @Override// ����ByteBuf����,���Ƕ����ڴ滹�Ƕ��ڴ����ݾ����ʵ����
    public ByteBuf buffer() {
        if (directByDefault) {
            return directBuffer();
        }
        return heapBuffer();
    }

    @Override
    public ByteBuf buffer(int initialCapacity) {
        if (directByDefault) {
            return directBuffer(initialCapacity);
        }
        return heapBuffer(initialCapacity);
    }

    @Override
    public ByteBuf buffer(int initialCapacity, int maxCapacity) {
        if (directByDefault) {
            return directBuffer(initialCapacity, maxCapacity);
        }
        return heapBuffer(initialCapacity, maxCapacity);
    }

    @Override
    public ByteBuf ioBuffer() {
        if (PlatformDependent.hasUnsafe()) {
            return directBuffer(DEFAULT_INITIAL_CAPACITY);
        }
        return heapBuffer(DEFAULT_INITIAL_CAPACITY);
    }

    @Override
    public ByteBuf ioBuffer(int initialCapacity) {
    	// java����ƽ̨ȷ��ʹ�õ���Direct|Heap
        if (PlatformDependent.hasUnsafe()) {
            return directBuffer(initialCapacity);
        }
        // ʹ��Heap�ڴ�
        return heapBuffer(initialCapacity);
    }

    @Override
    public ByteBuf ioBuffer(int initialCapacity, int maxCapacity) {
    	// java����ƽ̨ȷ��ʹ�õ���Direct|Heap
    	if (PlatformDependent.hasUnsafe()) {
    		// ʹ�ö����ڴ�
            return directBuffer(initialCapacity, maxCapacity);
        }
    	// ʹ�ö��ڴ�
        return heapBuffer(initialCapacity, maxCapacity);
    }

    @Override// ����Ĭ��������С��Ĭ�����������С��ByteBuf����(���ڴ�)
    public ByteBuf heapBuffer() {
        return heapBuffer(DEFAULT_INITIAL_CAPACITY, DEFAULT_MAX_CAPACITY);
    }

    @Override// ����ָ��������С��Ĭ�����������С��ByteBuf����(���ڴ�)
    public ByteBuf heapBuffer(int initialCapacity) {
        return heapBuffer(initialCapacity, DEFAULT_MAX_CAPACITY);
    }

    @Override// ����ָ��������С�����������С��ByteBuf����(���ڴ�)
    public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
        // ���������Ϊ0ʱ,������emptyBuf����
    	if (initialCapacity == 0 && maxCapacity == 0) {
            return emptyBuf;
        }
        validate(initialCapacity, maxCapacity);
        // ������չʵ��
        return newHeapBuffer(initialCapacity, maxCapacity);
    }

    @Override// ����Ĭ��������С��Ĭ�����������С��ByteBuf����(�����ڴ�)
    public ByteBuf directBuffer() {
        return directBuffer(DEFAULT_INITIAL_CAPACITY, DEFAULT_MAX_CAPACITY);
    }

    @Override// ����ָ��������С��Ĭ�����������С��ByteBuf����(�����ڴ�)
    public ByteBuf directBuffer(int initialCapacity) {
        return directBuffer(initialCapacity, DEFAULT_MAX_CAPACITY);
    }

    @Override// ����ָ��������С��ָ�����������С��ByteBuf����(�����ڴ�)
    public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
        if (initialCapacity == 0 && maxCapacity == 0) {
            return emptyBuf;
        }
        validate(initialCapacity, maxCapacity);
        return newDirectBuffer(initialCapacity, maxCapacity);
    }

    @Override
    public CompositeByteBuf compositeBuffer() {
        if (directByDefault) {
            return compositeDirectBuffer();
        }
        return compositeHeapBuffer();
    }

    @Override
    public CompositeByteBuf compositeBuffer(int maxNumComponents) {
        if (directByDefault) {
            return compositeDirectBuffer(maxNumComponents);
        }
        return compositeHeapBuffer(maxNumComponents);
    }

    @Override
    public CompositeByteBuf compositeHeapBuffer() {
        return compositeHeapBuffer(DEFAULT_MAX_COMPONENTS);
    }

    @Override
    public CompositeByteBuf compositeHeapBuffer(int maxNumComponents) {
        return toLeakAwareBuffer(new CompositeByteBuf(this, false, maxNumComponents));
    }

    @Override
    public CompositeByteBuf compositeDirectBuffer() {
        return compositeDirectBuffer(DEFAULT_MAX_COMPONENTS);
    }

    @Override
    public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
        return toLeakAwareBuffer(new CompositeByteBuf(this, true, maxNumComponents));
    }

    // ��֤��������Ƿ�Ϸ�
    private static void validate(int initialCapacity, int maxCapacity) {
    	// ��ʼ��������Ϊ����
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity: " + initialCapacity + " (expected: 0+)");
        }
        // ��ʼ����>Max����ʱ,�׳��쳣
        if (initialCapacity > maxCapacity) {
            throw new IllegalArgumentException(String.format(
                    "initialCapacity: %d (expected: not greater than maxCapacity(%d)",
                    initialCapacity, maxCapacity));
        }
    }

    /**
     * Create a heap {@link ByteBuf} with the given initialCapacity and maxCapacity.
     */
    // ����ָ��������С�����������ByteBuf����,����ʹ�ö����ڴ�
    protected abstract ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity);

    /**
     * Create a direct {@link ByteBuf} with the given initialCapacity and maxCapacity.
     */
    // ����ָ��������С�����������ByteBuf����,����ʹ�ö����ڴ�
    protected abstract ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity);

    @Override// ����String����
    public String toString() {
        return StringUtil.simpleClassName(this) + "(directByDefault: " + directByDefault + ')';
    }
}
