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
package io.netty.util.internal;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.queues.MpscChunkedArrayQueue;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.jctools.queues.SpscLinkedQueue;
import org.jctools.queues.atomic.MpscAtomicArrayQueue;
import org.jctools.queues.atomic.MpscGrowableAtomicArrayQueue;
import org.jctools.queues.atomic.MpscUnboundedAtomicArrayQueue;
import org.jctools.queues.atomic.SpscLinkedAtomicQueue;
import org.jctools.util.Pow2;
import org.jctools.util.UnsafeAccess;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Utility that detects various properties specific to the current runtime
 * environment, such as Java version and the availability of the
 * {@code sun.misc.Unsafe} object.
 * <p>
 * You can disable the use of {@code sun.misc.Unsafe} if you specify
 * the system property <strong>io.netty.noUnsafe</strong>.
 */
public final class PlatformDependent {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PlatformDependent.class);

    // 正则表达式
    private static final Pattern MAX_DIRECT_MEMORY_SIZE_ARG_PATTERN = Pattern.compile(
            "\\s*-XX:MaxDirectMemorySize\\s*=\\s*([0-9]+)\\s*([kKmMgG]?)\\s*$");

    // window系统
    private static final boolean IS_WINDOWS = isWindows0();
    // OS系统
    private static final boolean IS_OSX = isOsx0();

    // 超级用户
    private static final boolean MAYBE_SUPER_USER;

    // 允许使用TCP_NODELAY属性(!Android = true)
    private static final boolean CAN_ENABLE_TCP_NODELAY_BY_DEFAULT = !isAndroid();

    // Unsafe类的使用
    private static final boolean HAS_UNSAFE = hasUnsafe0();
    // 是否允许使用堆外内存
    private static final boolean DIRECT_BUFFER_PREFERRED =
            HAS_UNSAFE && !SystemPropertyUtil.getBoolean("io.netty.noPreferDirect", false);
    
    // 最大堆外内存
    private static final long MAX_DIRECT_MEMORY = maxDirectMemory0();

    private static final int MPSC_CHUNK_SIZE =  1024;
    private static final int MIN_MAX_MPSC_CAPACITY =  MPSC_CHUNK_SIZE * 2;
    private static final int MAX_ALLOWED_MPSC_CAPACITY = Pow2.MAX_POW2;

    // 数组偏移量
    private static final long ARRAY_BASE_OFFSET = arrayBaseOffset0();

    // 临时目录
    private static final File TMPDIR = tmpdir0();

    // 判断是32位还是64位
    private static final int BIT_MODE = bitMode0();
    // 处理器内核CPU型号(获取系统属性os.arch)
    private static final String NORMALIZED_ARCH = normalizeArch(SystemPropertyUtil.get("os.arch", ""));
    // 操作系统类型类型(获取系统属性os.name)
    private static final String NORMALIZED_OS = normalizeOs(SystemPropertyUtil.get("os.name", ""));

    // 32 - 4 :   64 - 8 
    private static final int ADDRESS_SIZE = addressSize0();
    private static final boolean USE_DIRECT_BUFFER_NO_CLEANER;
    // 堆外内存计数器(防止超过最大内存)
    private static final AtomicLong DIRECT_MEMORY_COUNTER;
    // 最大允许堆外内存
    private static final long DIRECT_MEMORY_LIMIT;
    
    // 随机数生成者
    private static final ThreadLocalRandomProvider RANDOM_PROVIDER;
    // DirectByteBuffer释放内存对象
    private static final Cleaner CLEANER;
    //-1
    private static final int UNINITIALIZED_ARRAY_ALLOCATION_THRESHOLD;

    public static final boolean BIG_ENDIAN_NATIVE_ORDER = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;

    private static final Cleaner NOOP = new Cleaner() {
        @Override
        public void freeDirectBuffer(ByteBuffer buffer) {
            // NOOP
        }
    };

    static {
        if (javaVersion() >= 7) {
        	// 随机数生成者
            RANDOM_PROVIDER = new ThreadLocalRandomProvider() {
                @Override
                public Random current() {
                    return java.util.concurrent.ThreadLocalRandom.current();
                }
            };
        } else {
            RANDOM_PROVIDER = new ThreadLocalRandomProvider() {
                @Override
                public Random current() {
                    return ThreadLocalRandom.current();
                }
            };
        }
        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noPreferDirect: {}", !DIRECT_BUFFER_PREFERRED);
        }

        /*
         * We do not want to log this message if unsafe is explicitly disabled. Do not remove the explicit no unsafe
         * guard.
         */
        // 表示正在运行的系统不能使用堆外内存
        if (!hasUnsafe() && !isAndroid() && !PlatformDependent0.isExplicitNoUnsafe()) {
            logger.info(
                    "Your platform does not provide complete low-level API for accessing direct buffers reliably. " +
                    "Unless explicitly requested, heap buffer will always be preferred to avoid potential system " +
                    "instability.");
        }

        // Here is how the system property is used:
        //
        // * <  0  - Don't use cleaner, and inherit max direct memory from java. In this case the
        //           "practical max direct memory" would be 2 * max memory as defined by the JDK.
        // * == 0  - Use cleaner, Netty will not enforce max memory, and instead will defer to JDK.
        // * >  0  - Don't use cleaner. This will limit Netty's total direct memory
        //           (note: that JDK's direct memory limit is independent of this).
        // 用户输入io.netty.maxDirectMemory参数
        long maxDirectMemory = SystemPropertyUtil.getLong("io.netty.maxDirectMemory", -1);

        if (maxDirectMemory == 0 || !hasUnsafe() || !PlatformDependent0.hasDirectBufferNoCleanerConstructor()) {
            // 开启堆外内存辅助内存回收对象
        	USE_DIRECT_BUFFER_NO_CLEANER = false;
            DIRECT_MEMORY_COUNTER = null;
        } else {
        	// 不开启堆外内存辅助内存回收对象
            USE_DIRECT_BUFFER_NO_CLEANER = true;
            if (maxDirectMemory < 0) {
            	// 获取最大允许堆外内存
                maxDirectMemory = maxDirectMemory0();
                if (maxDirectMemory <= 0) {
                    DIRECT_MEMORY_COUNTER = null;
                } else {
                    DIRECT_MEMORY_COUNTER = new AtomicLong();
                }
            } else {
                DIRECT_MEMORY_COUNTER = new AtomicLong();
            }
        }
        DIRECT_MEMORY_LIMIT = maxDirectMemory;
        logger.debug("-Dio.netty.maxDirectMemory: {} bytes", maxDirectMemory);

        int tryAllocateUninitializedArray =
                SystemPropertyUtil.getInt("io.netty.uninitializedArrayAllocationThreshold", 1024);
        UNINITIALIZED_ARRAY_ALLOCATION_THRESHOLD = javaVersion() >= 9 && PlatformDependent0.hasAllocateArrayMethod() ?
                tryAllocateUninitializedArray : -1;
        logger.debug("-Dio.netty.uninitializedArrayAllocationThreshold: {}", UNINITIALIZED_ARRAY_ALLOCATION_THRESHOLD);

        // 判断是否为超级用户
        MAYBE_SUPER_USER = maybeSuperUser0();

        if (!isAndroid() && hasUnsafe()) {
            // only direct to method if we are not running on android.
            // See https://github.com/netty/netty/issues/2604
            if (javaVersion() >= 9) {
                CLEANER = CleanerJava9.isSupported() ? new CleanerJava9() : NOOP;
            } else {
            	// 设置CLEANER属性值
                CLEANER = CleanerJava6.isSupported() ? new CleanerJava6() : NOOP;
            }
        } else {
            CLEANER = NOOP;
        }
    }

    // 判断是否存在-DirectByteBuffer构造器
    public static boolean hasDirectBufferNoCleanerConstructor() {
        return PlatformDependent0.hasDirectBufferNoCleanerConstructor();
    }

    // 创建字节数组
    public static byte[] allocateUninitializedArray(int size) {
        return UNINITIALIZED_ARRAY_ALLOCATION_THRESHOLD < 0 || UNINITIALIZED_ARRAY_ALLOCATION_THRESHOLD > size ?
                new byte[size] : PlatformDependent0.allocateUninitializedArray(size);
    }

    /**
     * Returns {@code true} if and only if the current platform is Android
     */
    // 判断是否为安卓系统
    public static boolean isAndroid() {
        return PlatformDependent0.isAndroid();
    }

    /**
     * Return {@code true} if the JVM is running on Windows
     */
    // 判断是否为window系统下
    public static boolean isWindows() {
        return IS_WINDOWS;
    }

    /**
     * Return {@code true} if the JVM is running on OSX / MacOS
     */
    // 判断是否运行在MacOS系统上
    public static boolean isOsx() {
        return IS_OSX;
    }

    /**
     * Return {@code true} if the current user may be a super-user. Be aware that this is just an hint and so it may
     * return false-positives.
     */
    // 判断是否为超级用户
    public static boolean maybeSuperUser() {
        return MAYBE_SUPER_USER;
    }

    /**
     * Return the version of Java under which this library is used.
     */
    // 获取java版本号
    public static int javaVersion() {
        return PlatformDependent0.javaVersion();
    }

    /**
     * Returns {@code true} if and only if it is fine to enable TCP_NODELAY socket option by default.
     */
    // 判断是否能默认开启Tcp配置参数TCP_NODELAY
    public static boolean canEnableTcpNoDelayByDefault() {
        return CAN_ENABLE_TCP_NODELAY_BY_DEFAULT;
    }

    /**
     * Return {@code true} if {@code sun.misc.Unsafe} was found on the classpath and can be used for accelerated
     * direct memory access.
     */
    // 判断是否存在Unsafe类
    public static boolean hasUnsafe() {
        return HAS_UNSAFE;
    }

    /**
     * Return the reason (if any) why {@code sun.misc.Unsafe} was not available.
     */
    // 获取为什么不能获取Unsafe的原因？
    public static Throwable getUnsafeUnavailabilityCause() {
        return PlatformDependent0.getUnsafeUnavailabilityCause();
    }

    /**
     * {@code true} if and only if the platform supports unaligned access.
     *
     * @see <a href="http://en.wikipedia.org/wiki/Segmentation_fault#Bus_error">Wikipedia on segfault</a>
     */
    // 未对齐检验
    public static boolean isUnaligned() {
        return PlatformDependent0.isUnaligned();
    }

    /**
     * Returns {@code true} if the platform has reliable low-level direct buffer access API and a user has not specified
     * {@code -Dio.netty.noPreferDirect} option.
     */
    // 当前平台允许DirectByteBuffer对象
    public static boolean directBufferPreferred() {
        return DIRECT_BUFFER_PREFERRED;
    }

    /**
     * Returns the maximum memory reserved for direct buffer allocation.
     */
    // 返回最大允许堆外内存
    public static long maxDirectMemory() {
        return MAX_DIRECT_MEMORY;
    }

    /**
     * Returns the temporary directory.
     */
    // 返回临时最大目录路径
    public static File tmpdir() {
        return TMPDIR;
    }

    /**
     * Returns the bit mode of the current VM (usually 32 or 64.)
     */
    // 判断是32位还是64位
    public static int bitMode() {
        return BIT_MODE;
    }

    /**
     * Return the address size of the OS.
     * 4 (for 32 bits systems ) and 8 (for 64 bits systems).
     */
    // 32 - 4 :   64 - 8 
    public static int addressSize() {
        return ADDRESS_SIZE;
    }

    // 分配size大小内存
    public static long allocateMemory(long size) {
        return PlatformDependent0.allocateMemory(size);
    }

    // 释放内存
    public static void freeMemory(long address) {
        PlatformDependent0.freeMemory(address);
    }

    /**
     * Raises an exception bypassing compiler checks for checked exceptions.
     */
    // 抛出Throwable异常
    public static void throwException(Throwable t) {
        if (hasUnsafe()) {
            PlatformDependent0.throwException(t);
        } else {
            PlatformDependent.<RuntimeException>throwException0(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwException0(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * Creates a new fastest {@link ConcurrentMap} implementation for the current platform.
     */
    // 创建ConcurrentHashMap对象
    public static <K, V> ConcurrentMap<K, V> newConcurrentHashMap() {
        return new ConcurrentHashMap<K, V>();
    }

    /**
     * Creates a new fastest {@link LongCounter} implementation for the current platform.
     */
    // 返回LongCounter对象
    public static LongCounter newLongCounter() {
        if (javaVersion() >= 8) {
            return new LongAdderCounter();
        } else {
            return new AtomicLongCounter();
        }
    }

    /**
     * Creates a new fastest {@link ConcurrentMap} implementation for the current platform.
     */
    // 创建ConcurrentHashMap对象
    public static <K, V> ConcurrentMap<K, V> newConcurrentHashMap(int initialCapacity) {
        return new ConcurrentHashMap<K, V>(initialCapacity);
    }

    /**
     * Creates a new fastest {@link ConcurrentMap} implementation for the current platform.
     */
    // 创建ConcurrentHashMap对象
    public static <K, V> ConcurrentMap<K, V> newConcurrentHashMap(int initialCapacity, float loadFactor) {
        return new ConcurrentHashMap<K, V>(initialCapacity, loadFactor);
    }

    /**
     * Creates a new fastest {@link ConcurrentMap} implementation for the current platform.
     */
    // 创建ConcurrentHashMap对象
    public static <K, V> ConcurrentMap<K, V> newConcurrentHashMap(
            int initialCapacity, float loadFactor, int concurrencyLevel) {
        return new ConcurrentHashMap<K, V>(initialCapacity, loadFactor, concurrencyLevel);
    }

    /**
     * Creates a new fastest {@link ConcurrentMap} implementation for the current platform.
     */
    // 创建ConcurrentHashMap对象
    public static <K, V> ConcurrentMap<K, V> newConcurrentHashMap(Map<? extends K, ? extends V> map) {
        return new ConcurrentHashMap<K, V>(map);
    }

    /**
     * Try to deallocate the specified direct {@link ByteBuffer}. Please note this method does nothing if
     * the current platform does not support this operation or the specified buffer is not a direct buffer.
     */
    // 释放堆外内存
    public static void freeDirectBuffer(ByteBuffer buffer) {
        CLEANER.freeDirectBuffer(buffer);
    }

    // 获取堆外内存地址
    public static long directBufferAddress(ByteBuffer buffer) {
        return PlatformDependent0.directBufferAddress(buffer);
    }

    // 为DirectByteBuffer对象重新分配内存
    public static ByteBuffer directBuffer(long memoryAddress, int size) {
        if (PlatformDependent0.hasDirectBufferNoCleanerConstructor()) {
            return PlatformDependent0.newDirectBuffer(memoryAddress, size);
        }
        // 抛出UnsupportedOperationException异常
        throw new UnsupportedOperationException(
                "sun.misc.Unsafe or java.nio.DirectByteBuffer.<init>(long, int) not available");
    }

    // 使用unsafe获取Int(4字节)类型数据
    public static int getInt(Object object, long fieldOffset) {
        return PlatformDependent0.getInt(object, fieldOffset);
    }

    // 使用unsafe获取byte(1字节)类型数据
    public static byte getByte(long address) {
        return PlatformDependent0.getByte(address);
    }

    // 使用unsafe获取short(2字节)类型数据
    public static short getShort(long address) {
        return PlatformDependent0.getShort(address);
    }

    // 使用unsafe获取Int(4字节)类型数据
    public static int getInt(long address) {
        return PlatformDependent0.getInt(address);
    }

    // 使用unsafe获取long(8字节)类型数据
    public static long getLong(long address) {
        return PlatformDependent0.getLong(address);
    }

    // 使用unsafe获取从index开始的byte(1字节)字节数组数据
    public static byte getByte(byte[] data, int index) {
        return PlatformDependent0.getByte(data, index);
    }

    // 使用unsafe获取从index开始的short(2字节)字节数组数据
    public static short getShort(byte[] data, int index) {
        return PlatformDependent0.getShort(data, index);
    }

    // 使用unsafe获取从index开始的int(4字节)字节数组数据
    public static int getInt(byte[] data, int index) {
        return PlatformDependent0.getInt(data, index);
    }

    // 使用unsafe获取从index开始的long(8字节)字节数组数据
    public static long getLong(byte[] data, int index) {
        return PlatformDependent0.getLong(data, index);
    }

    // 使用unsafe在address位置上设置byte(1字节)数据
    public static void putByte(long address, byte value) {
        PlatformDependent0.putByte(address, value);
    }

    // 使用unsafe在address位置上设置short(2字节)数据
    public static void putShort(long address, short value) {
        PlatformDependent0.putShort(address, value);
    }

    // 使用unsafe在address位置上设置int(4字节)数据
    public static void putInt(long address, int value) {
        PlatformDependent0.putInt(address, value);
    }

    // 使用unsafe在address位置上设置long(8字节)数据
    public static void putLong(long address, long value) {
        PlatformDependent0.putLong(address, value);
    }

    // 使用unsafe在index位置上设置byte(1字节)类型数组数据
    public static void putByte(byte[] data, int index, byte value) {
        PlatformDependent0.putByte(data, index, value);
    }

    // 使用unsafe在index位置上设置short(2字节)类型数组数据
    public static void putShort(byte[] data, int index, short value) {
        PlatformDependent0.putShort(data, index, value);
    }

    // 使用unsafe在index位置上设置int(4字节)类型数组数据
    public static void putInt(byte[] data, int index, int value) {
        PlatformDependent0.putInt(data, index, value);
    }

    // 使用unsafe在index位置上设置long(8字节)类型数组数据
    public static void putLong(byte[] data, int index, long value) {
        PlatformDependent0.putLong(data, index, value);
    }

    // 使用unsafe从源地址srcAddr复制进行length长度的数据存入srcAddr地址中
    public static void copyMemory(long srcAddr, long dstAddr, long length) {
        PlatformDependent0.copyMemory(srcAddr, dstAddr, length);
    }

    // 使用unsafe从源地址srcAddr复制进行length长度的数据存入src字节数组以srcindex为开始位置的字节数组中
    public static void copyMemory(byte[] src, int srcIndex, long dstAddr, long length) {
        PlatformDependent0.copyMemory(src, ARRAY_BASE_OFFSET + srcIndex, null, dstAddr, length);
    }

    public static void copyMemory(long srcAddr, byte[] dst, int dstIndex, long length) {
        PlatformDependent0.copyMemory(null, srcAddr, dst, ARRAY_BASE_OFFSET + dstIndex, length);
    }

    // 设置内存地址数据
    public static void setMemory(byte[] dst, int dstIndex, long bytes, byte value) {
        PlatformDependent0.setMemory(dst, ARRAY_BASE_OFFSET + dstIndex, bytes, value);
    }

    // 设置内存地址数据
    public static void setMemory(long address, long bytes, byte value) {
        PlatformDependent0.setMemory(address, bytes, value);
    }

    /**
     * Allocate a new {@link ByteBuffer} with the given {@code capacity}. {@link ByteBuffer}s allocated with
     * this method <strong>MUST</strong> be deallocated via {@link #freeDirectNoCleaner(ByteBuffer)}.
     */
    // 创建DirectByteBuffer对象
    public static ByteBuffer allocateDirectNoCleaner(int capacity) {
        assert USE_DIRECT_BUFFER_NO_CLEANER;

        incrementMemoryCounter(capacity);
        try {
        	// 创建DirectByteBuffer对象
            return PlatformDependent0.allocateDirectNoCleaner(capacity);
        } catch (Throwable e) {
            decrementMemoryCounter(capacity);
            throwException(e);
            return null;
        }
    }

    /**
     * Reallocate a new {@link ByteBuffer} with the given {@code capacity}. {@link ByteBuffer}s reallocated with
     * this method <strong>MUST</strong> be deallocated via {@link #freeDirectNoCleaner(ByteBuffer)}.
     */
    // 为DirectByteBuffer重新分配内存
    public static ByteBuffer reallocateDirectNoCleaner(ByteBuffer buffer, int capacity) {
        assert USE_DIRECT_BUFFER_NO_CLEANER;

        // 查看增量
        int len = capacity - buffer.capacity();
        incrementMemoryCounter(len);
        try {
        	// 重新分配内存
            return PlatformDependent0.reallocateDirectNoCleaner(buffer, capacity);
        } catch (Throwable e) {
            decrementMemoryCounter(len);
            throwException(e);
            return null;
        }
    }

    /**
     * This method <strong>MUST</strong> only be called for {@link ByteBuffer}s that were allocated via
     * {@link #allocateDirectNoCleaner(int)}.
     */
    // 释放DirectByteBuffer对象内存
    public static void freeDirectNoCleaner(ByteBuffer buffer) {
        assert USE_DIRECT_BUFFER_NO_CLEANER;

        int capacity = buffer.capacity();
        PlatformDependent0.freeMemory(PlatformDependent0.directBufferAddress(buffer));
        decrementMemoryCounter(capacity);
    }

    // 递增已使用directByteBuffer对象所允许的内存
    private static void incrementMemoryCounter(int capacity) {
        if (DIRECT_MEMORY_COUNTER != null) {
            for (;;) {
            	// 获取已使用内存
                long usedMemory = DIRECT_MEMORY_COUNTER.get();
                long newUsedMemory = usedMemory + capacity;
                if (newUsedMemory > DIRECT_MEMORY_LIMIT) {
                	// 当超出最大内存时,抛出OutOfDirectMemoryError异常
                    throw new OutOfDirectMemoryError("failed to allocate " + capacity
                            + " byte(s) of direct memory (used: " + usedMemory + ", max: " + DIRECT_MEMORY_LIMIT + ')');
                }
                // CAS设置值
                if (DIRECT_MEMORY_COUNTER.compareAndSet(usedMemory, newUsedMemory)) {
                    break;
                }
            }
        }
    }

    // 递减已使用内存总数
    private static void decrementMemoryCounter(int capacity) {
        if (DIRECT_MEMORY_COUNTER != null) {
            long usedMemory = DIRECT_MEMORY_COUNTER.addAndGet(-capacity);
            assert usedMemory >= 0;
        }
    }

    public static boolean useDirectBufferNoCleaner() {
        return USE_DIRECT_BUFFER_NO_CLEANER;
    }

    /**
     * Determine if a subsection of an array is zero.
     * @param bytes The byte array.
     * @param startPos The starting index (inclusive) in {@code bytes}.
     * @param length The amount of bytes to check for zero.
     * @return {@code false} if {@code bytes[startPos:startsPos+length)} contains a value other than zero.
     */
    public static boolean isZero(byte[] bytes, int startPos, int length) {
        return !hasUnsafe() || !isUnaligned() ?
                isZeroSafe(bytes, startPos, length) :
                PlatformDependent0.isZero(bytes, startPos, length);
    }

    private static final class Mpsc {
        private static final boolean USE_MPSC_CHUNKED_ARRAY_QUEUE;

        private Mpsc() {
        }

        static {
            Object unsafe = null;
            if (hasUnsafe()) {
                // jctools goes through its own process of initializing unsafe; of
                // course, this requires permissions which might not be granted to calling code, so we
                // must mark this block as privileged too
                unsafe = AccessController.doPrivileged(new PrivilegedAction<Object>() {
                    @Override
                    public Object run() {
                        // force JCTools to initialize unsafe
                        return UnsafeAccess.UNSAFE;
                    }
                });
            }

            if (unsafe == null) {
                logger.debug("org.jctools-core.MpscChunkedArrayQueue: unavailable");
                USE_MPSC_CHUNKED_ARRAY_QUEUE = false;
            } else {
                logger.debug("org.jctools-core.MpscChunkedArrayQueue: available");
                USE_MPSC_CHUNKED_ARRAY_QUEUE = true;
            }
        }

        static <T> Queue<T> newMpscQueue(final int maxCapacity) {
            // Calculate the max capacity which can not be bigger then MAX_ALLOWED_MPSC_CAPACITY.
            // This is forced by the MpscChunkedArrayQueue implementation as will try to round it
            // up to the next power of two and so will overflow otherwise.
            final int capacity = max(min(maxCapacity, MAX_ALLOWED_MPSC_CAPACITY), MIN_MAX_MPSC_CAPACITY);
            return USE_MPSC_CHUNKED_ARRAY_QUEUE ? new MpscChunkedArrayQueue<T>(MPSC_CHUNK_SIZE, capacity)
                                                : new MpscGrowableAtomicArrayQueue<T>(MPSC_CHUNK_SIZE, capacity);
        }

        static <T> Queue<T> newMpscQueue() {
            return USE_MPSC_CHUNKED_ARRAY_QUEUE ? new MpscUnboundedArrayQueue<T>(MPSC_CHUNK_SIZE)
                                                : new MpscUnboundedAtomicArrayQueue<T>(MPSC_CHUNK_SIZE);
        }
    }

    /**
     * Create a new {@link Queue} which is safe to use for multiple producers (different threads) and a single
     * consumer (one thread!).
     * @return A MPSC queue which may be unbounded.
     */
    public static <T> Queue<T> newMpscQueue() {
        return Mpsc.newMpscQueue();
    }

    /**
     * Create a new {@link Queue} which is safe to use for multiple producers (different threads) and a single
     * consumer (one thread!).
     */
    public static <T> Queue<T> newMpscQueue(final int maxCapacity) {
        return Mpsc.newMpscQueue(maxCapacity);
    }

    /**
     * Create a new {@link Queue} which is safe to use for single producer (one thread!) and a single
     * consumer (one thread!).
     */
    // 获取SpscLinkedQueue对象
    public static <T> Queue<T> newSpscQueue() {
        return hasUnsafe() ? new SpscLinkedQueue<T>() : new SpscLinkedAtomicQueue<T>();
    }

    /**
     * Create a new {@link Queue} which is safe to use for multiple producers (different threads) and a single
     * consumer (one thread!) with the given fixes {@code capacity}.
     */
    // 获取MpscArrayQueue对象
    public static <T> Queue<T> newFixedMpscQueue(int capacity) {
        return hasUnsafe() ? new MpscArrayQueue<T>(capacity) : new MpscAtomicArrayQueue<T>(capacity);
    }

    /**
     * Return the {@link ClassLoader} for the given {@link Class}.
     */
    // 获取某个类的类加载器
    public static ClassLoader getClassLoader(final Class<?> clazz) {
        return PlatformDependent0.getClassLoader(clazz);
    }

    /**
     * Return the context {@link ClassLoader} for the current {@link Thread}.
     */
    // 获取线程上下文类加载器
    public static ClassLoader getContextClassLoader() {
        return PlatformDependent0.getContextClassLoader();
    }

    /**
     * Return the system {@link ClassLoader}.
     */
    // 获取系统类加载器
    public static ClassLoader getSystemClassLoader() {
        return PlatformDependent0.getSystemClassLoader();
    }

    /**
     * Returns a new concurrent {@link Deque}.
     */
    // 获取队列
    public static <C> Deque<C> newConcurrentDeque() {
        if (javaVersion() < 7) {
            return new LinkedBlockingDeque<C>();
        } else {
            return new ConcurrentLinkedDeque<C>();
        }
    }

    /**
     * Return a {@link Random} which is not-threadsafe and so can only be used from the same thread.
     */
    // 获取随机数(线程不安全)
    public static Random threadLocalRandom() {
        return RANDOM_PROVIDER.current();
    }

    // 判断是否为windows系统
    private static boolean isWindows0() {
        boolean windows = SystemPropertyUtil.get("os.name", "").toLowerCase(Locale.US).contains("win");
        if (windows) {
            logger.debug("Platform: Windows");
        }
        return windows;
    }

    // 判断是否为MacOS系统
    private static boolean isOsx0() {
        String osname = SystemPropertyUtil.get("os.name", "").toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "");
        boolean osx = osname.startsWith("macosx") || osname.startsWith("osx");

        if (osx) {
            logger.debug("Platform: MacOS");
        }
        return osx;
    }

    // 判断是否为超级用户
    private static boolean maybeSuperUser0() {
    	// 获取系统属性
        String username = SystemPropertyUtil.get("user.name");
        if (isWindows()) {
            return "Administrator".equals(username);
        }
        // Check for root and toor as some BSDs have a toor user that is basically the same as root.
        return "root".equals(username) || "toor".equals(username);
    }

    private static boolean hasUnsafe0() {
    	// 判断是否为安卓系统 = false
        if (isAndroid()) {
            logger.debug("sun.misc.Unsafe: unavailable (Android)");
            return false;
        }

        // 用户拒绝使用Unsafe类
        if (PlatformDependent0.isExplicitNoUnsafe()) {
            return false;
        }

        try {
        	// 查看平台是否支持UnSafe类
            boolean hasUnsafe = PlatformDependent0.hasUnsafe();
            logger.debug("sun.misc.Unsafe: {}", hasUnsafe ? "available" : "unavailable");
            return hasUnsafe;
        } catch (Throwable t) {
            logger.trace("Could not determine if Unsafe is available", t);
            // Probably failed to initialize PlatformDependent0.
            return false;
        }
    }

    // 获取数组偏移量
    private static long arrayBaseOffset0() {
        if (!hasUnsafe()) {
            return -1;
        }

        return PlatformDependent0.arrayBaseOffset();
    }

    // 获取maxDirectMemory值
    private static long maxDirectMemory0() {
    	
        long maxDirectMemory = 0;
        ClassLoader systemClassLoader = null;
        try {
            // Try to get from sun.misc.VM.maxDirectMemory() which should be most accurate.
            systemClassLoader = getSystemClassLoader();
            Class<?> vmClass = Class.forName("sun.misc.VM", true, systemClassLoader);
            Method m = vmClass.getDeclaredMethod("maxDirectMemory");
            // 调用sun.misc.VM中的maxDirectMemory方法
            maxDirectMemory = ((Number) m.invoke(null)).longValue();
        } catch (Throwable ignored) {
            // Ignore
        }

        if (maxDirectMemory > 0) {
            return maxDirectMemory;
        }

        try {
            // Now try to get the JVM option (-XX:MaxDirectMemorySize) and parse it.
            // Note that we are using reflection because Android doesn't have these classes.
            Class<?> mgmtFactoryClass = Class.forName(
                    "java.lang.management.ManagementFactory", true, systemClassLoader);
            Class<?> runtimeClass = Class.forName(
                    "java.lang.management.RuntimeMXBean", true, systemClassLoader);

            // 调用java.lang.management.ManagementFactory中的getRuntimeMXBean方法
            Object runtime = mgmtFactoryClass.getDeclaredMethod("getRuntimeMXBean").invoke(null);

            
            // RuntimeMXBean.getInputArguments方法
            @SuppressWarnings("unchecked")
            List<String> vmArgs = (List<String>) runtimeClass.getDeclaredMethod("getInputArguments").invoke(runtime);
            for (int i = vmArgs.size() - 1; i >= 0; i --) {
            	// 匹配正则表达式
                Matcher m = MAX_DIRECT_MEMORY_SIZE_ARG_PATTERN.matcher(vmArgs.get(i));
                if (!m.matches()) {
                    continue;
                }

                // 获取值对象
                maxDirectMemory = Long.parseLong(m.group(1));
                switch (m.group(2).charAt(0)) { // 单位
                    case 'k': case 'K':
                        maxDirectMemory *= 1024; // K
                        break;
                    case 'm': case 'M':
                        maxDirectMemory *= 1024 * 1024; // M
                        break;
                    case 'g': case 'G':
                        maxDirectMemory *= 1024 * 1024 * 1024; // G
                        break;
                }
                break;
            }
        } catch (Throwable ignored) {
            // Ignore
        }

        if (maxDirectMemory <= 0) {
        	// 调用maxMemory()方法
            maxDirectMemory = Runtime.getRuntime().maxMemory();
            logger.debug("maxDirectMemory: {} bytes (maybe)", maxDirectMemory);
        } else {
            logger.debug("maxDirectMemory: {} bytes", maxDirectMemory);
        }

        return maxDirectMemory;
    }

    private static File tmpdir0() {
        File f;
        try {
        	// 获取用户参数io.netty.tmpdir
            f = toDirectory(SystemPropertyUtil.get("io.netty.tmpdir"));
            if (f != null) {
                logger.debug("-Dio.netty.tmpdir: {}", f);
                return f;
            }

            // 获取系统参数java.io.tmpdir
            f = toDirectory(SystemPropertyUtil.get("java.io.tmpdir"));
            if (f != null) {
                logger.debug("-Dio.netty.tmpdir: {} (java.io.tmpdir)", f);
                return f;
            }

            // This shouldn't happen, but just in case ..
            if (isWindows()) {
                f = toDirectory(System.getenv("TEMP"));
                if (f != null) {
                    logger.debug("-Dio.netty.tmpdir: {} (%TEMP%)", f);
                    return f;
                }

                String userprofile = System.getenv("USERPROFILE");
                if (userprofile != null) {
                    f = toDirectory(userprofile + "\\AppData\\Local\\Temp");
                    if (f != null) {
                        logger.debug("-Dio.netty.tmpdir: {} (%USERPROFILE%\\AppData\\Local\\Temp)", f);
                        return f;
                    }

                    f = toDirectory(userprofile + "\\Local Settings\\Temp");
                    if (f != null) {
                        logger.debug("-Dio.netty.tmpdir: {} (%USERPROFILE%\\Local Settings\\Temp)", f);
                        return f;
                    }
                }
            } else {
                f = toDirectory(System.getenv("TMPDIR"));
                if (f != null) {
                    logger.debug("-Dio.netty.tmpdir: {} ($TMPDIR)", f);
                    return f;
                }
            }
        } catch (Throwable ignored) {
            // Environment variable inaccessible
        }

        // Last resort.
        if (isWindows()) {
            f = new File("C:\\Windows\\Temp");
        } else {
            f = new File("/tmp");
        }

        logger.warn("Failed to get the temporary directory; falling back to: {}", f);
        return f;
    }

    // 获取目录的绝对路径
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static File toDirectory(String path) {
        if (path == null) {
            return null;
        }

        File f = new File(path);
        f.mkdirs();

        // 如果不是目录的话,直接就返回
        if (!f.isDirectory()) {
            return null;
        }

        try {
        	// 获取文件的绝对路径
            return f.getAbsoluteFile();
        } catch (Exception ignored) {
            return f;
        }
    }

    private static int bitMode0() {
        // Check user-specified bit mode first.
    	// 用户参数io.netty.bitMode,默认为0
        int bitMode = SystemPropertyUtil.getInt("io.netty.bitMode", 0);
        if (bitMode > 0) {
            logger.debug("-Dio.netty.bitMode: {}", bitMode);
            return bitMode;
        }

        // And then the vendor specific ones which is probably most reliable.
        // 获取系统参数sun.arch.data.model,默认为0
        bitMode = SystemPropertyUtil.getInt("sun.arch.data.model", 0);
        if (bitMode > 0) {
            logger.debug("-Dio.netty.bitMode: {} (sun.arch.data.model)", bitMode);
            return bitMode;
        }
        // 获取系统参数com.ibm.vm.bitmode,默认为0
        bitMode = SystemPropertyUtil.getInt("com.ibm.vm.bitmode", 0);
        if (bitMode > 0) {
            logger.debug("-Dio.netty.bitMode: {} (com.ibm.vm.bitmode)", bitMode);
            return bitMode;
        }

        // os.arch also gives us a good hint.
        // 获取系统属性os.arch
        String arch = SystemPropertyUtil.get("os.arch", "").toLowerCase(Locale.US).trim();
        if ("amd64".equals(arch) || "x86_64".equals(arch)) {
            bitMode = 64;
        } else if ("i386".equals(arch) || "i486".equals(arch) || "i586".equals(arch) || "i686".equals(arch)) {
            bitMode = 32;
        }

        if (bitMode > 0) {
            logger.debug("-Dio.netty.bitMode: {} (os.arch: {})", bitMode, arch);
        }

        // Last resort: guess from VM name and then fall back to most common 64-bit mode.
        // 获取系统属性java.vm.name
        String vm = SystemPropertyUtil.get("java.vm.name", "").toLowerCase(Locale.US);
        Pattern BIT_PATTERN = Pattern.compile("([1-9][0-9]+)-?bit");
        // 正则表达式匹配
        Matcher m = BIT_PATTERN.matcher(vm);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        } else {
            return 64;
        }
    }

    // 获取内存地址大小
    private static int addressSize0() {
        if (!hasUnsafe()) {
            return -1;
        }
        return PlatformDependent0.addressSize();
    }

    private static boolean isZeroSafe(byte[] bytes, int startPos, int length) {
        final int end = startPos + length;
        for (; startPos < end; ++startPos) {
            if (bytes[startPos] != 0) {
                return false;
            }
        }
        return true;
    }

    // 获取处理器内核CPU型号
    public static String normalizedArch() {
        return NORMALIZED_ARCH;
    }

    // 获取操作系统类型
    public static String normalizedOs() {
        return NORMALIZED_OS;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "");
    }

    // 获取处理器内核CPU型号
    private static String normalizeArch(String value) {
        value = normalize(value);
        if (value.matches("^(x8664|amd64|ia32e|em64t|x64)$")) {
            return "x86_64";
        }
        if (value.matches("^(x8632|x86|i[3-6]86|ia32|x32)$")) {
            return "x86_32";
        }
        if (value.matches("^(ia64|itanium64)$")) {
            return "itanium_64";
        }
        if (value.matches("^(sparc|sparc32)$")) {
            return "sparc_32";
        }
        if (value.matches("^(sparcv9|sparc64)$")) {
            return "sparc_64";
        }
        if (value.matches("^(arm|arm32)$")) {
            return "arm_32";
        }
        if ("aarch64".equals(value)) {
            return "aarch_64";
        }
        if (value.matches("^(ppc|ppc32)$")) {
            return "ppc_32";
        }
        if ("ppc64".equals(value)) {
            return "ppc_64";
        }
        if ("ppc64le".equals(value)) {
            return "ppcle_64";
        }
        if ("s390".equals(value)) {
            return "s390_32";
        }
        if ("s390x".equals(value)) {
            return "s390_64";
        }

        return "unknown";
    }

    // 获取操作系统类型
    private static String normalizeOs(String value) {
        value = normalize(value);
        if (value.startsWith("aix")) {
            return "aix";
        }
        if (value.startsWith("hpux")) {
            return "hpux";
        }
        if (value.startsWith("os400")) {
            // Avoid the names such as os4000
            if (value.length() <= 5 || !Character.isDigit(value.charAt(5))) {
                return "os400";
            }
        }
        if (value.startsWith("linux")) {
            return "linux";
        }
        if (value.startsWith("macosx") || value.startsWith("osx")) {
            return "osx";
        }
        if (value.startsWith("freebsd")) {
            return "freebsd";
        }
        if (value.startsWith("openbsd")) {
            return "openbsd";
        }
        if (value.startsWith("netbsd")) {
            return "netbsd";
        }
        if (value.startsWith("solaris") || value.startsWith("sunos")) {
            return "sunos";
        }
        if (value.startsWith("windows")) {
            return "windows";
        }

        return "unknown";
    }

    // 原子类计数器
    private static final class AtomicLongCounter extends AtomicLong implements LongCounter {
        @Override
        public void add(long delta) {
            addAndGet(delta); // + and get
        }

        @Override
        public void increment() {
            incrementAndGet(); // ++ and get
        }

        @Override
        public void decrement() {
            decrementAndGet(); // -- and get
        }

        @Override
        public long value() {
            return get(); // get
        }
    }

    private interface ThreadLocalRandomProvider {
        Random current();
    }

    private PlatformDependent() {
        // only static method supported
    }
}
