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

import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;

import static io.netty.util.internal.MathUtil.isOutOfBounds;

/**
 * A skeletal implementation of a buffer.
 */
// ByteBuf抽象显现类
public abstract class AbstractByteBuf extends ByteBuf {
	
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractByteBuf.class);
    private static final String PROP_MODE = "io.netty.buffer.bytebuf.checkAccessible";
    // 安全检查,默认为true
    private static final boolean checkAccessible;

    static {
    	// 设置属性
        checkAccessible = SystemPropertyUtil.getBoolean(PROP_MODE, true);
        if (logger.isDebugEnabled()) {
            logger.debug("-D{}: {}", PROP_MODE, checkAccessible);
        }
    }

    // 记录引用计数对象被调用时候的方法调用栈
    static final ResourceLeakDetector<ByteBuf> leakDetector =
            ResourceLeakDetectorFactory.instance().newResourceLeakDetector(ByteBuf.class);
    
    // 读索引
    int readerIndex;
    // 写索引
    int writerIndex;
    
    // reset操作时所用读索引
    private int markedReaderIndex;
    // reset操作时所用写索引
    private int markedWriterIndex;

    // MAX容量
    private int maxCapacity;

    // ByteBuf对象包裹
    private SwappedByteBuf swappedBuf;

    // 创建AbstractByteBuf对象并设置MAX容量
    protected AbstractByteBuf(int maxCapacity) {
        if (maxCapacity < 0) {
            throw new IllegalArgumentException("maxCapacity: " + maxCapacity + " (expected: >= 0)");
        }
        // 设置MAX容量
        this.maxCapacity = maxCapacity;
    }

    @Override // 返回容量
    public int maxCapacity() {
        return maxCapacity;
    }

    // 设置MAX容量
    protected final void maxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    @Override // 获取读索引
    public int readerIndex() {
        return readerIndex;
    }

    
    @Override // 设置读索引
    public ByteBuf readerIndex(int readerIndex) {
    	// 检验输入参数:  输入参数必须大于0且小于当前的写索引
        if (readerIndex < 0 || readerIndex > writerIndex) {
        	// 抛出异常IndexOutOfBoundsException
            throw new IndexOutOfBoundsException(String.format(
                    "readerIndex: %d (expected: 0 <= readerIndex <= writerIndex(%d))", readerIndex, writerIndex));
        }
        // 设置读索引
        this.readerIndex = readerIndex;
        return this;
    }

    @Override// 获取写索引
    public int writerIndex() {
        return writerIndex;
    }

    @Override// 更新缓冲区的写索引
    public ByteBuf writerIndex(int writerIndex) {
    	// 检验当前输入读索引是否符合1、是否大于读索引2、是否大于缓冲区容量
        if (writerIndex < readerIndex || writerIndex > capacity()) {
            throw new IndexOutOfBoundsException(String.format(
                    "writerIndex: %d (expected: readerIndex(%d) <= writerIndex <= capacity(%d))",
                    writerIndex, readerIndex, capacity()));
        }
        
        this.writerIndex = writerIndex;
        return this;
    }

    @Override
    public ByteBuf setIndex(int readerIndex, int writerIndex) {
    	// 检验输入参数是否符合规则
        if (readerIndex < 0 || readerIndex > writerIndex || writerIndex > capacity()) {
            throw new IndexOutOfBoundsException(String.format(
                    "readerIndex: %d, writerIndex: %d (expected: 0 <= readerIndex <= writerIndex <= capacity(%d))",
                    readerIndex, writerIndex, capacity()));
        }
        // 设置读写索引
        setIndex0(readerIndex, writerIndex);
        return this;
    }

    // 清空ByteBuf对象
    @Override // 将读写索引设置为0
    public ByteBuf clear() {
        readerIndex = writerIndex = 0;
        return this;
    }

    @Override// 是否可读
    public boolean isReadable() {
        return writerIndex > readerIndex;
    }

    @Override // 是否可读numBytes个字节
    public boolean isReadable(int numBytes) {
        return writerIndex - readerIndex >= numBytes;
    }

    @Override// 是否可写
    public boolean isWritable() {
        return capacity() > writerIndex;
    }

    @Override // 是否可写numBytes个字节
    public boolean isWritable(int numBytes) {
        return capacity() - writerIndex >= numBytes;
    }

    @Override// 可读字节数
    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    @Override// 可写字节数
    public int writableBytes() {
        return capacity() - writerIndex;
    }

    @Override// MAX可写字节数
    public int maxWritableBytes() {
        return maxCapacity() - writerIndex;
    }

    @Override// 记录当前读索引 
    public ByteBuf markReaderIndex() {
        markedReaderIndex = readerIndex;
        return this;
    }

    @Override// 重新设置读索引
    public ByteBuf resetReaderIndex() {
        readerIndex(markedReaderIndex);
        return this;
    }

    @Override// 记录当前写索引 
    public ByteBuf markWriterIndex() {
        markedWriterIndex = writerIndex;
        return this;
    }

    @Override// 重新设置读索引
    public ByteBuf resetWriterIndex() {
        writerIndex = markedWriterIndex;
        return this;
    }

    
    @Override // 删除已读的字节数
    public ByteBuf discardReadBytes() {
        ensureAccessible();
        // 如果为0则说明没有可重用的缓冲区
        if (readerIndex == 0) {
            return this;
        }

        // 如果存在可读缓存区
        if (readerIndex != writerIndex) {
        	// 将可读缓冲区的字节数组复制到缓冲区的起始位置
            setBytes(0, this, readerIndex, writerIndex - readerIndex);
            // 设置写索引
            writerIndex -= readerIndex;
            adjustMarkers(readerIndex);
            readerIndex = 0;
        } else {
        	// 没有可读的字节数组,将读写索引设置为0即可完成缓冲区的重用。
            adjustMarkers(readerIndex);
            writerIndex = readerIndex = 0;
        }
        return this;
    }

    @Override// 删除部分已读的字节数
    public ByteBuf discardSomeReadBytes() {
        ensureAccessible();
        if (readerIndex == 0) {
            return this;
        }

        // 当readerIndex = writerIndex时
        // 将mark与readerIndex、writerInde索引均置为0
        if (readerIndex == writerIndex) {
            adjustMarkers(readerIndex);
            writerIndex = readerIndex = 0;
            return this;
        }

        // 当已读字节数大于容量的一半时
        // 将已读字节数进行删除操作
        if (readerIndex >= capacity() >>> 1) {
            setBytes(0, this, readerIndex, writerIndex - readerIndex);
            writerIndex -= readerIndex;
            adjustMarkers(readerIndex);
            readerIndex = 0;
        }
        return this;
    }

    // 调整mark索引号
    protected final void adjustMarkers(int decrement) {
        int markedReaderIndex = this.markedReaderIndex;
        if (markedReaderIndex <= decrement) {
        	
        	// 设置markedReaderIndex为0
            this.markedReaderIndex = 0;
            
            // 更新markWriter索引号
            int markedWriterIndex = this.markedWriterIndex;
            if (markedWriterIndex <= decrement) {
                this.markedWriterIndex = 0;
            } else {
            	// 设置markedWriterIndex
                this.markedWriterIndex = markedWriterIndex - decrement;
            }
        } else {
        	// 更新mark标记号
            this.markedReaderIndex = markedReaderIndex - decrement;
            markedWriterIndex -= decrement;
        }
    }

    @Override// 确保最小可写字节数
    public ByteBuf ensureWritable(int minWritableBytes) {
    	// 检验minWritableBytes合法性
        if (minWritableBytes < 0) {
            throw new IllegalArgumentException(String.format(
                    "minWritableBytes: %d (expected: >= 0)", minWritableBytes));
        }
        ensureWritable0(minWritableBytes);
        return this;
    }

    // 确保最小可写数
    // 如果内存不够的话,需要进行内存扩容操作
    final void ensureWritable0(int minWritableBytes) {
        ensureAccessible();
        // 当前可写空间满足条件
        if (minWritableBytes <= writableBytes()) {
            return;
        }

        // minWritableBytes将会导致超出最大内存限制
        if (minWritableBytes > maxCapacity - writerIndex) {
            throw new IndexOutOfBoundsException(String.format(
                    "writerIndex(%d) + minWritableBytes(%d) exceeds maxCapacity(%d): %s",
                    writerIndex, minWritableBytes, maxCapacity, this));
        }

        // Normalize the current capacity to the power of 2.
        // 计算扩容后的容量
        int newCapacity = calculateNewCapacity(writerIndex + minWritableBytes);

        // Adjust to the new capacity.
        // 调整最新容量
        capacity(newCapacity);
    }

    @Override
    public int ensureWritable(int minWritableBytes, boolean force) {
        ensureAccessible();
        // 输入参数合法性
        if (minWritableBytes < 0) {
            throw new IllegalArgumentException(String.format(
                    "minWritableBytes: %d (expected: >= 0)", minWritableBytes));
        }

        // 当前剩余内存足够
        if (minWritableBytes <= writableBytes()) {
            return 0;
        }

        final int maxCapacity = maxCapacity();
        final int writerIndex = writerIndex();
        // 当前内存不足,并且已达到最大允许容量,即扩容满足不了需求
        if (minWritableBytes > maxCapacity - writerIndex) {
            if (!force || capacity() == maxCapacity) {
                return 1;
            }

            // 设置为最大容量
            capacity(maxCapacity);
            return 3;
        }

        // Normalize the current capacity to the power of 2.
        // 计算新的所需容量
        int newCapacity = calculateNewCapacity(writerIndex + minWritableBytes);

        // Adjust to the new capacity.
        // 设置容量
        capacity(newCapacity);
        return 2;
    }

    // 计算所需内存
    private int calculateNewCapacity(int minNewCapacity) {
        final int maxCapacity = this.maxCapacity;
        // 默认门限为4MB
        final int threshold = 1048576 * 4; // 4 MiB page

        // 如果刚好为4MB,直接就返回
        if (minNewCapacity == threshold) {
            return threshold;
        }

        // If over threshold, do not double but just increase by threshold.
        // 如果最小内存大小大于4m时,需要每次递增4M内存
        // 在这个时候每次递增内存,以4M为基数
        if (minNewCapacity > threshold) {
        	// newCapacity为小于minNewCapacity的最大4M的倍数
            int newCapacity = minNewCapacity / threshold * threshold;
            
            // 所需空间大于MAX容量时
            if (newCapacity > maxCapacity - threshold) {
            	// 使用maxCapacity作为缓冲区容量
                newCapacity = maxCapacity;
            } else {
            	// 以步进的方式,即每次增加4MB
                newCapacity += threshold;
            }
            return newCapacity;
        }

        // Not over threshold. Double up to 4 MiB, starting from 64.
        int newCapacity = 64;
        while (newCapacity < minNewCapacity) {
        	// 在64的基础上进行倍增操作 (*2)
            newCapacity <<= 1;
        }

        // 取两者中的最小值(不允许超过newCapacity【最大容量值】)
        return Math.min(newCapacity, maxCapacity);
    }

    @Override// SwappedByteBuf对象
    public ByteBuf order(ByteOrder endianness) {
    	// 空指针异常
        if (endianness == null) {
            throw new NullPointerException("endianness");
        }
        if (endianness == order()) {
            return this;
        }

        SwappedByteBuf swappedBuf = this.swappedBuf;
        if (swappedBuf == null) {
        	// 创建SwappedByteBuf对象
            this.swappedBuf = swappedBuf = newSwappedByteBuf();
        }
        return swappedBuf;
    }

    /**
     * Creates a new {@link SwappedByteBuf} for this {@link ByteBuf} instance.
     */
    // 创建SwappedByteBuf对象
    protected SwappedByteBuf newSwappedByteBuf() {
        return new SwappedByteBuf(this);
    }

    @Override// 获取index位置上的字节
    public byte getByte(int index) {
        checkIndex(index);
        return _getByte(index);
    }

    // 获取指定index上的byte数据(模板模式-1字节)
    protected abstract byte _getByte(int index);

    @Override// 获取index位置上的boolean类型
    public boolean getBoolean(int index) {
        return getByte(index) != 0;
    }

    @Override// 获取index上无符号短整型数据(1字节)
    public short getUnsignedByte(int index) {
    	// &FF:是用于高位1字节
        return (short) (getByte(index) & 0xFF);
    }

    @Override// 获取index开始的2字节
    public short getShort(int index) {
        checkIndex(index, 2);
        return _getShort(index);
    }

    // 获取指定位置上的short数据(模板模式-2字节)
    protected abstract short _getShort(int index);

    @Override// 获取无符号类型short数据
    public int getUnsignedShort(int index) {
        return getShort(index) & 0xFFFF;
    }

    @Override// 获取无符号类型medium数据
    public int getUnsignedMedium(int index) {
        checkIndex(index, 3);
        return _getUnsignedMedium(index);
    }

    // 获取指定位置上的medium数据(模板模式-3字节)
    protected abstract int _getUnsignedMedium(int index);

    @Override // 获取medium数据
    public int getMedium(int index) {
        int value = getUnsignedMedium(index);
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override// 获取int类型数据
    public int getInt(int index) {
        checkIndex(index, 4);
        return _getInt(index);
    }

    // 获取指定位置上的int数据(模板模式-4字节)
    protected abstract int _getInt(int index);

    @Override// 获取无符号类型long数据
    public long getUnsignedInt(int index) {
        return getInt(index) & 0xFFFFFFFFL;
    }

    @Override// 获取long型数据
    public long getLong(int index) {
        checkIndex(index, 8);
        return _getLong(index);
    }

    // 获取指定位置上的long数据(模板模式-8字节)
    protected abstract long _getLong(int index);

    @Override// 获取指定位置上的char数据(模板模式-2字节)
    public char getChar(int index) {
        return (char) getShort(index);
    }

    @Override// 获取float类型数据
    public float getFloat(int index) {
    	// 其实是获取4字节数据转换成float形式数据
        return Float.intBitsToFloat(getInt(index));
    }

    @Override// 获取double类型数据
    public double getDouble(int index) {
    	// 其实是获取8字节数据转换成double形式数据
        return Double.longBitsToDouble(getLong(index));
    }

    @Override// 在该ByteBuf中从index获取dst.length长度的字节数组
    public ByteBuf getBytes(int index, byte[] dst) {
        getBytes(index, dst, 0, dst.length);
        return this;
    }

    @Override// 在该ByteBuf中从index获取dst可写长度的字节数组写入到dst中
    public ByteBuf getBytes(int index, ByteBuf dst) {
        getBytes(index, dst, dst.writableBytes());
        return this;
    }

    @Override// 在该ByteBuf中从index获取length长度的字节数组写入到dst中
    public ByteBuf getBytes(int index, ByteBuf dst, int length) {
        getBytes(index, dst, dst.writerIndex(), length);
        dst.writerIndex(dst.writerIndex() + length);
        return this;
    }

    @Override// 设置某个index位置上的数据(int - value - 4字节)
    public ByteBuf setByte(int index, int value) {
        checkIndex(index);
        _setByte(index, value);
        return this;
    }

    // 设置指定位置index上的int数据(模板模式-4字节)
    protected abstract void _setByte(int index, int value);

    @Override// 设置指定位置index上的boolean数据
    public ByteBuf setBoolean(int index, boolean value) {
        setByte(index, value? 1 : 0);
        return this;
    }

    @Override// 设置某个index位置上的数据(short - value - 2字节)
    public ByteBuf setShort(int index, int value) {
        checkIndex(index, 2);
        _setShort(index, value);
        return this;
    }

    // 设置指定位置index上的short数据(模板模式-2字节)
    protected abstract void _setShort(int index, int value);

    @Override// 设置某个index位置上的数据(char - value - 2字节)
    public ByteBuf setChar(int index, int value) {
        setShort(index, value);
        return this;
    }

    @Override// 设置某个index位置上的数据(medium - value - 3字节)
    public ByteBuf setMedium(int index, int value) {
        checkIndex(index, 3);
        _setMedium(index, value);
        return this;
    }
    
    // 设置指定位置index上的medium数据(模板模式-3字节)
    protected abstract void _setMedium(int index, int value);

    @Override// 设置某个index位置上的数据(int - value - 4字节)
    public ByteBuf setInt(int index, int value) {
        checkIndex(index, 4);
        _setInt(index, value);
        return this;
    }

    // 设置指定位置index上的int数据(模板模式-4字节)
    protected abstract void _setInt(int index, int value);

    @Override// 设置某个index位置上的数据(float - value - 4字节)
    public ByteBuf setFloat(int index, float value) {
        setInt(index, Float.floatToRawIntBits(value));
        return this;
    }

    @Override// 设置某个index位置上的数据(long - value - 8字节)
    public ByteBuf setLong(int index, long value) {
        checkIndex(index, 8);
        _setLong(index, value);
        return this;
    }

    // 设置指定位置index上的long数据(模板模式-8字节)
    protected abstract void _setLong(int index, long value);

    @Override// 设置某个index位置上的数据(double - value - 8字节)
    public ByteBuf setDouble(int index, double value) {
        setLong(index, Double.doubleToRawLongBits(value));
        return this;
    }

    @Override// 设置该byteBuf对象中从index开始的长度为src.length的数据(从src中获取数据来源)
    public ByteBuf setBytes(int index, byte[] src) {
        setBytes(index, src, 0, src.length);
        return this;
    }

    @Override// 将src对象中可读的字节数写入该ByteBuf对象中以index为开始位置的数组中
    public ByteBuf setBytes(int index, ByteBuf src) {
        setBytes(index, src, src.readableBytes());
        return this;
    }

    @Override// 将src对象中length长度可读数据写入该ByteBuf对象中以index为开始位置的数组中
    public ByteBuf setBytes(int index, ByteBuf src, int length) {
        checkIndex(index, length);
        if (src == null) {
            throw new NullPointerException("src");
        }
        // 判断可读字节数是否已经满足
        if (length > src.readableBytes()) {
            throw new IndexOutOfBoundsException(String.format(
                    "length(%d) exceeds src.readableBytes(%d) where src is: %s", length, src.readableBytes(), src));
        }

        setBytes(index, src, src.readerIndex(), length);
        src.readerIndex(src.readerIndex() + length);
        return this;
    }

    @Override// 将ByteBuf中的数据设置为0
    public ByteBuf setZero(int index, int length) {
        if (length == 0) {
            return this;
        }

        checkIndex(index, length);

        // 除以8的整数部分
        int nLong = length >>> 3;
        // 除以8的余数部分
        int nBytes = length & 7;
        
        for (int i = nLong; i > 0; i --) {
        	// 将8位字节设置为0
            _setLong(index, 0);
            index += 8;
        }
        if (nBytes == 4) {
        	// 当余数为4,将int类型设置0
            _setInt(index, 0);
            // Not need to update the index as we not will use it after this.
        } else if (nBytes < 4) {
        	// 当余数小于4时
            for (int i = nBytes; i > 0; i --) {
            	// 对每个byte设置为0
                _setByte(index, (byte) 0);
                index ++;
            }
        } else {
        	// 先将4个字节设置为0
            _setInt(index, 0);
            index += 4;
            // 将其余的位按byte设置为0
            for (int i = nBytes - 4; i > 0; i --) {
                _setByte(index, (byte) 0);
                index ++;
            }
        }
        return this;
    }

    @Override// 从该ByteBuf中读取一个字节的数据(byte)
    public byte readByte() {
        checkReadableBytes0(1);
        int i = readerIndex;
        byte b = _getByte(i);
        readerIndex = i + 1;
        return b;
    }

    @Override// 从该ByteBuf中读取一个字节的数据并判断是否为0返回boolean类型
    public boolean readBoolean() {
        return readByte() != 0;
    }

    @Override// 返回无符号short类型数据
    public short readUnsignedByte() {
        return (short) (readByte() & 0xFF);
    }

    @Override// 从该ByteBuf中读取两个个字节的数据(short)
    public short readShort() {
        checkReadableBytes0(2);
        short v = _getShort(readerIndex);
        readerIndex += 2;
        return v;
    }

    @Override// 返回无符号short类型数据
    public int readUnsignedShort() {
        return readShort() & 0xFFFF;
    }

    @Override// 从该ByteBuf中读取三个字节的数据(medium)
    public int readMedium() {
        int value = readUnsignedMedium();
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override// 从该ByteBuf中读取三个字节的数据(无符号medium)
    public int readUnsignedMedium() {
        checkReadableBytes0(3);
        int v = _getUnsignedMedium(readerIndex);
        readerIndex += 3;
        return v;
    }

    @Override// 从该ByteBuf中读取四个字节的数据(int)
    public int readInt() {
        checkReadableBytes0(4);
        int v = _getInt(readerIndex);
        readerIndex += 4;
        return v;
    }

    @Override// 从该ByteBuf中读取四个字节的数据(无符号int)
    public long readUnsignedInt() {
        return readInt() & 0xFFFFFFFFL;
    }

    @Override// 从该ByteBuf中读取八个字节的数据(long)
    public long readLong() {
        checkReadableBytes0(8);
        long v = _getLong(readerIndex);
        readerIndex += 8;
        return v;
    }

    @Override// 从该ByteBuf中读取二个字节的数据(char)
    public char readChar() {
        return (char) readShort();
    }

    @Override// 从该ByteBuf中读取四个字节的数据(float)
    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    @Override// 从该ByteBuf中读取八个字节的数据(double)
    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    @Override// 从this对象中读取长度length的数据存入新创建的ByteBuf对象中
    public ByteBuf readBytes(int length) {
        checkReadableBytes(length);
        if (length == 0) {
            return Unpooled.EMPTY_BUFFER;
        }

        ByteBuf buf = alloc().buffer(length, maxCapacity);
        buf.writeBytes(this, readerIndex, length);
        readerIndex += length;
        return buf;
    }

    @Override
    public ByteBuf readSlice(int length) {
        checkReadableBytes(length);
        ByteBuf slice = slice(readerIndex, length);
        readerIndex += length;
        return slice;
    }

    @Override// 从this对象中读取length长度的字节数据存入以dstIndex开始的dst数组中
    public ByteBuf readBytes(byte[] dst, int dstIndex, int length) {
        checkReadableBytes(length);
        getBytes(readerIndex, dst, dstIndex, length);
        readerIndex += length;
        return this;
    }

    @Override// 从this中读取length长度的数据存入字节数组dst中
    public ByteBuf readBytes(byte[] dst) {
        readBytes(dst, 0, dst.length);
        return this;
    }

    @Override// 从this中读取dst可写字节数的数据存入dst对象中
    public ByteBuf readBytes(ByteBuf dst) {
        readBytes(dst, dst.writableBytes());
        return this;
    }

    @Override// 从this中读取length可写字节数的数据存入dst对象中
    public ByteBuf readBytes(ByteBuf dst, int length) {
        if (length > dst.writableBytes()) {
            throw new IndexOutOfBoundsException(String.format(
                    "length(%d) exceeds dst.writableBytes(%d) where dst is: %s", length, dst.writableBytes(), dst));
        }
        readBytes(dst, dst.writerIndex(), length);
        dst.writerIndex(dst.writerIndex() + length);
        return this;
    }

    @Override// 从this中读取length可写字节数的数据存入dst对象以dstIndex开始的数组中
    public ByteBuf readBytes(ByteBuf dst, int dstIndex, int length) {
        checkReadableBytes(length);
        getBytes(readerIndex, dst, dstIndex, length);
        readerIndex += length;
        return this;
    }

    @Override// 从this中读取length字节数的数据存入dst对象中
    public ByteBuf readBytes(ByteBuffer dst) {
        int length = dst.remaining();
        checkReadableBytes(length);
        getBytes(readerIndex, dst);
        readerIndex += length;
        return this;
    }

    @Override// 从this对象将长度为length的数据写入到out(GatheringByteChannel)对象中
    public int readBytes(GatheringByteChannel out, int length)
            throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, length);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override// 从this对象将长度为length的数据写入到out(OutputStream)对象中
    public ByteBuf readBytes(OutputStream out, int length) throws IOException {
        checkReadableBytes(length);
        getBytes(readerIndex, out, length);
        readerIndex += length;
        return this;
    }

    @Override// 在this对象中跳过指定length的字节数据
    public ByteBuf skipBytes(int length) {
        checkReadableBytes(length);
        // 设置可读索引
        readerIndex += length;
        return this;
    }

    @Override// 向this对象中写入boolean类型数据
    public ByteBuf writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
        return this;
    }

    @Override// 向this对象中写入byte类型数据
    public ByteBuf writeByte(int value) {
        ensureWritable0(1);
        _setByte(writerIndex++, value);
        return this;
    }

    @Override// 向this对象中写入short类型数据
    public ByteBuf writeShort(int value) {
        ensureWritable0(2);
        _setShort(writerIndex, value);
        writerIndex += 2;
        return this;
    }

    @Override// 向this对象中写入medium类型数据
    public ByteBuf writeMedium(int value) {
        ensureWritable0(3);
        _setMedium(writerIndex, value);
        writerIndex += 3;
        return this;
    }

    @Override// 向this对象中写入int类型数据
    public ByteBuf writeInt(int value) {
        ensureWritable0(4);
        _setInt(writerIndex, value);
        writerIndex += 4;
        return this;
    }

    @Override// 向this对象中写入long类型数据
    public ByteBuf writeLong(long value) {
        ensureWritable0(8);
        _setLong(writerIndex, value);
        writerIndex += 8;
        return this;
    }

    @Override// 向this对象中写入char类型数据
    public ByteBuf writeChar(int value) {
        writeShort(value);
        return this;
    }

    @Override// 向this对象中写入float类型数据
    public ByteBuf writeFloat(float value) {
        writeInt(Float.floatToRawIntBits(value));
        return this;
    }

    @Override// 向this对象中写入double类型数据
    public ByteBuf writeDouble(double value) {
        writeLong(Double.doubleToRawLongBits(value));
        return this;
    }

    @Override// 向this对象中写入src中以srcIndex为开始位置的长度length的数据
    public ByteBuf writeBytes(byte[] src, int srcIndex, int length) {
        ensureWritable(length);
        setBytes(writerIndex, src, srcIndex, length);
        writerIndex += length;
        return this;
    }

    @Override// 向this对象中写入src字节数组
    public ByteBuf writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
        return this;
    }

    @Override// 向this对象中写入src对象中可读字节数
    public ByteBuf writeBytes(ByteBuf src) {
        writeBytes(src, src.readableBytes());
        return this;
    }

    @Override// 向this对象中写入src对象中length长度可读字节数
    public ByteBuf writeBytes(ByteBuf src, int length) {
        if (length > src.readableBytes()) {
            throw new IndexOutOfBoundsException(String.format(
                    "length(%d) exceeds src.readableBytes(%d) where src is: %s", length, src.readableBytes(), src));
        }
        writeBytes(src, src.readerIndex(), length);
        src.readerIndex(src.readerIndex() + length);
        return this;
    }

    @Override// 向this对象中写入src对象中以srcIndex为开始位置的length长度可读字节数
    public ByteBuf writeBytes(ByteBuf src, int srcIndex, int length) {
        ensureWritable(length);
        setBytes(writerIndex, src, srcIndex, length);
        writerIndex += length;
        return this;
    }

    @Override// 向this对象中写入src对象中remaining字节数
    public ByteBuf writeBytes(ByteBuffer src) {
        int length = src.remaining();
        ensureWritable0(length);
        setBytes(writerIndex, src);
        writerIndex += length;
        return this;
    }

    @Override// 向this对象中写入in(InputStream)中长度length字节数
    public int writeBytes(InputStream in, int length)
            throws IOException {
        ensureWritable(length);
        int writtenBytes = setBytes(writerIndex, in, length);
        if (writtenBytes > 0) {
            writerIndex += writtenBytes;
        }
        return writtenBytes;
    }

    @Override // 向this对象中写入in(ScatteringByteChannel)中长度length字节数
    public int writeBytes(ScatteringByteChannel in, int length) throws IOException {
    	// 验证输入参数
        ensureWritable(length);
        // 返回读入的字节数
        int writtenBytes = setBytes(writerIndex, in, length);
        if (writtenBytes > 0) {
        	// 更新读索引
            writerIndex += writtenBytes;
        }
        return writtenBytes;
    }

    @Override
    public ByteBuf writeZero(int length) {
        if (length == 0) {
            return this;
        }

        ensureWritable(length);
        int wIndex = writerIndex;
        checkIndex0(wIndex, length);

        int nLong = length >>> 3;
        int nBytes = length & 7;
        for (int i = nLong; i > 0; i --) {
            _setLong(wIndex, 0);
            wIndex += 8;
        }
        if (nBytes == 4) {
            _setInt(wIndex, 0);
            wIndex += 4;
        } else if (nBytes < 4) {
            for (int i = nBytes; i > 0; i --) {
                _setByte(wIndex, (byte) 0);
                wIndex++;
            }
        } else {
            _setInt(wIndex, 0);
            wIndex += 4;
            for (int i = nBytes - 4; i > 0; i --) {
                _setByte(wIndex, (byte) 0);
                wIndex++;
            }
        }
        writerIndex = wIndex;
        return this;
    }

    @Override
    public ByteBuf copy() {
        return copy(readerIndex, readableBytes());
    }

    @Override// 复制对象
    public ByteBuf duplicate() {
        return new DuplicatedAbstractByteBuf(this);
    }

    @Override
    public ByteBuf slice() {
        return slice(readerIndex, readableBytes());
    }

    @Override// 创建SlicedAbstractByteBuf对象
    public ByteBuf slice(int index, int length) {
        return new SlicedAbstractByteBuf(this, index, length);
    }

    @Override// 返回ByteBuffer对象
    public ByteBuffer nioBuffer() {
        return nioBuffer(readerIndex, readableBytes());
    }

    @Override// 返回ByteBuffer对象数组
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(readerIndex, readableBytes());
    }

    @Override
    public String toString(Charset charset) {
        return toString(readerIndex, readableBytes(), charset);
    }

    @Override
    public String toString(int index, int length, Charset charset) {
        return ByteBufUtil.decodeString(this, index, length, charset);
    }

    @Override
    public int indexOf(int fromIndex, int toIndex, byte value) {
        return ByteBufUtil.indexOf(this, fromIndex, toIndex, value);
    }

    @Override
    public int bytesBefore(byte value) {
        return bytesBefore(readerIndex(), readableBytes(), value);
    }

    @Override
    public int bytesBefore(int length, byte value) {
        checkReadableBytes(length);
        return bytesBefore(readerIndex(), length, value);
    }

    @Override
    public int bytesBefore(int index, int length, byte value) {
        int endIndex = indexOf(index, index + length, value);
        if (endIndex < 0) {
            return -1;
        }
        return endIndex - index;
    }

    @Override
    public int forEachByte(ByteBufProcessor processor) {
        ensureAccessible();
        try {
            return forEachByteAsc0(readerIndex, writerIndex, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    @Override
    public int forEachByte(int index, int length, ByteBufProcessor processor) {
        checkIndex(index, length);
        try {
            return forEachByteAsc0(index, index + length, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    private int forEachByteAsc0(int start, int end, ByteBufProcessor processor) throws Exception {
        for (; start < end; ++start) {
            if (!processor.process(_getByte(start))) {
                return start;
            }
        }

        return -1;
    }

    @Override
    public int forEachByteDesc(ByteBufProcessor processor) {
        ensureAccessible();
        try {
            return forEachByteDesc0(writerIndex - 1, readerIndex, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    @Override
    public int forEachByteDesc(int index, int length, ByteBufProcessor processor) {
        checkIndex(index, length);
        try {
            return forEachByteDesc0(index + length - 1, index, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    private int forEachByteDesc0(int rStart, final int rEnd, ByteBufProcessor processor) throws Exception {
        for (; rStart >= rEnd; --rStart) {
            if (!processor.process(_getByte(rStart))) {
                return rStart;
            }
        }
        return -1;
    }

    @Override// 获取哈希码
    public int hashCode() {
        return ByteBufUtil.hashCode(this);
    }

    @Override// 比较两者相等
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof ByteBuf) {
            return ByteBufUtil.equals(this, (ByteBuf) o);
        }
        return false;
    }

    @Override// 比较大小
    public int compareTo(ByteBuf that) {
        return ByteBufUtil.compare(this, that);
    }

    @Override// 返回String对象
    public String toString() {
        if (refCnt() == 0) {
            return StringUtil.simpleClassName(this) + "(freed)";
        }

        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append("(ridx: ").append(readerIndex)
            .append(", widx: ").append(writerIndex)
            .append(", cap: ").append(capacity());
        if (maxCapacity != Integer.MAX_VALUE) {
            buf.append('/').append(maxCapacity);
        }

        ByteBuf unwrapped = unwrap();
        if (unwrapped != null) {
            buf.append(", unwrapped: ").append(unwrapped);
        }
        buf.append(')');
        return buf.toString();
    }

    // 判断指定索引号是否存在ByteBuf中
    protected final void checkIndex(int index) {
        checkIndex(index, 1);
    }

    // 判断指定长度的数据索引是否存在ByteBuf中
    protected final void checkIndex(int index, int fieldLength) {
        ensureAccessible();
        checkIndex0(index, fieldLength);
    }

    final void checkIndex0(int index, int fieldLength) {
        if (isOutOfBounds(index, fieldLength, capacity())) {
            throw new IndexOutOfBoundsException(String.format(
                    "index: %d, length: %d (expected: range(0, %d))", index, fieldLength, capacity()));
        }
    }

    // 检查源对象索引
    protected final void checkSrcIndex(int index, int length, int srcIndex, int srcCapacity) {
        checkIndex(index, length);
        if (isOutOfBounds(srcIndex, length, srcCapacity)) {
            throw new IndexOutOfBoundsException(String.format(
                    "srcIndex: %d, length: %d (expected: range(0, %d))", srcIndex, length, srcCapacity));
        }
    }

    // 检查目标对象索引
    protected final void checkDstIndex(int index, int length, int dstIndex, int dstCapacity) {
        checkIndex(index, length);
        if (isOutOfBounds(dstIndex, length, dstCapacity)) {
            throw new IndexOutOfBoundsException(String.format(
                    "dstIndex: %d, length: %d (expected: range(0, %d))", dstIndex, length, dstCapacity));
        }
    }

    /**
     * Throws an {@link IndexOutOfBoundsException} if the current
     * {@linkplain #readableBytes() readable bytes} of this buffer is less
     * than the specified value.
     */
    protected final void checkReadableBytes(int minimumReadableBytes) {
    	// 当minimumReadableBytes为负数时,抛出IllegalArgumentException异常！
        if (minimumReadableBytes < 0) {
            throw new IllegalArgumentException("minimumReadableBytes: " + minimumReadableBytes + " (expected: >= 0)");
        }
        checkReadableBytes0(minimumReadableBytes);
    }

    // 检车新容量是否有效
    protected final void checkNewCapacity(int newCapacity) {
        ensureAccessible();
        // 检验newCapacity合法性
        if (newCapacity < 0 || newCapacity > maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity + " (expected: 0-" + maxCapacity() + ')');
        }
    }

    private void checkReadableBytes0(int minimumReadableBytes) {
        ensureAccessible();
        // 判断跳过的长度是否大于当前缓存区可读的字节数长度
        if (readerIndex > writerIndex - minimumReadableBytes) {
        	// 抛出异常IndexOutOfBoundsException
            throw new IndexOutOfBoundsException(String.format(
                    "readerIndex(%d) + length(%d) exceeds writerIndex(%d): %s",
                    readerIndex, minimumReadableBytes, writerIndex, this));
        }
    }

    /**
     * Should be called by every method that tries to access the buffers content to check
     * if the buffer was released before.
     */
    // 当引用计数为0时,抛出IllegalReferenceCountException异常
    protected final void ensureAccessible() {
    	// 是否可获得的并且引用计数是否为0
        if (checkAccessible && refCnt() == 0) {
            throw new IllegalReferenceCountException(0);
        }
    }

    // 设置读写索引
    final void setIndex0(int readerIndex, int writerIndex) {
        this.readerIndex = readerIndex;
        this.writerIndex = writerIndex;
    }

    // 使得markedReaderIndex与markedWriterIndex失效！
    final void discardMarks() {
        markedReaderIndex = markedWriterIndex = 0;
    }
}
