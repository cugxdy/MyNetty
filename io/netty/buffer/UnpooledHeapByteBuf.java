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

import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Big endian Java heap buffer implementation. It is recommended to use
 * {@link UnpooledByteBufAllocator#heapBuffer(int, int)}, {@link Unpooled#buffer(int)} and
 * {@link Unpooled#wrappedBuffer(byte[])} instead of calling the constructor explicitly.
 */
public class UnpooledHeapByteBuf extends AbstractReferenceCountedByteBuf {

	
	// 用于UnpooledHeapByteBuf的内存分配
    private final ByteBufAllocator alloc;
    // byte数组作为缓冲区
    byte[] array;
    // 用于实现Netty ByteBuf到JDK NIO ByteBuffer的转换
    private ByteBuffer tmpNioBuf;

    /**
     * Creates a new heap buffer with a newly allocated byte array.
     *
     * @param initialCapacity the initial capacity of the underlying byte array
     * @param maxCapacity the max capacity of the underlying byte array
     */
    public UnpooledHeapByteBuf(ByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
        super(maxCapacity);

        checkNotNull(alloc, "alloc");

        // 初始容量 <= MAX容量
        if (initialCapacity > maxCapacity) {
            throw new IllegalArgumentException(String.format(
                    "initialCapacity(%d) > maxCapacity(%d)", initialCapacity, maxCapacity));
        }

        // 设置内存分配器
        this.alloc = alloc;
        setArray(allocateArray(initialCapacity));
        // 设置读写索引
        setIndex(0, 0);
    }

    /**
     * Creates a new heap buffer with an existing byte array.
     *
     * @param initialArray the initial underlying byte array
     * @param maxCapacity the max capacity of the underlying byte array
     */
    protected UnpooledHeapByteBuf(ByteBufAllocator alloc, byte[] initialArray, int maxCapacity) {
        super(maxCapacity);
        
        // 判断输入参数是否为空
        checkNotNull(alloc, "alloc");
        checkNotNull(initialArray, "initialArray");

        if (initialArray.length > maxCapacity) {
            throw new IllegalArgumentException(String.format(
                    "initialCapacity(%d) > maxCapacity(%d)", initialArray.length, maxCapacity));
        }

        // 设置内存分配器
        this.alloc = alloc;
        setArray(initialArray);
        // 设置读写索引
        setIndex(0, initialArray.length);
    }

    // 创建初始长度为initialCapacity的字节数组
    byte[] allocateArray(int initialCapacity) {
    	// 创建新的字节缓冲区数组
        return new byte[initialCapacity];
    }

    void freeArray(byte[] array) {
        // NOOP
    }

    // 设置array与tmpNioBuf字段
    private void setArray(byte[] initialArray) {
        array = initialArray;
        // JDK版本的Bytebuffer
        tmpNioBuf = null;
    }

    @Override// 返回内存分配器
    public ByteBufAllocator alloc() {
        return alloc;
    }

    @Override// 返回内存插入的顺序
    public ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override// 基于堆内存实现的ByteBuf,它会返回false
    public boolean isDirect() {
        return false;
    }

    @Override// 返回数组大小(容量)
    public int capacity() {
        ensureAccessible();
        return array.length;
    }

    @Override// 扩容时(设置capacity)
    public ByteBuf capacity(int newCapacity) {
    	// 检验newCapacity合法性
        checkNewCapacity(newCapacity);

        int oldCapacity = array.length;
        byte[] oldArray = array;
        
        if (newCapacity > oldCapacity) {
        	// 创建新的缓冲区字节数组
            byte[] newArray = allocateArray(newCapacity);
            // 内存复制,将旧的字节数组复制到新创建的字节数组中
            System.arraycopy(oldArray, 0, newArray, 0, oldArray.length);
            // 设置字节数组缓冲区
            setArray(newArray);
            // 释放旧的字节数组
            freeArray(oldArray);
        } else if (newCapacity < oldCapacity) {
            byte[] newArray = allocateArray(newCapacity);
            // 获取读索引
            int readerIndex = readerIndex();
            if (readerIndex < newCapacity) {
            	// 获取写索引
                int writerIndex = writerIndex();
                if (writerIndex > newCapacity) {
                    writerIndex(writerIndex = newCapacity);
                }
                // 将缓冲区中的readerIndex  - (writerIndex - readerIndex)字节复制到新的字节数组中
                System.arraycopy(oldArray, readerIndex, newArray, readerIndex, writerIndex - readerIndex);
            } else {
                setIndex(newCapacity, newCapacity);
            }
            setArray(newArray);
            freeArray(oldArray);
        }
        return this;
    }

    @Override// 是否基于字节数组实现
    public boolean hasArray() {
        return true;
    }

    @Override // 返回字节数组
    public byte[] array() {
        ensureAccessible();
        return array;
    }

    @Override// 数组偏移量为0
    public int arrayOffset() {
        return 0;
    }

    @Override// 判断是否存在内存地址
    public boolean hasMemoryAddress() {
        return false;
    }

    @Override // 抛出UnsupportedOperationException异常
    public long memoryAddress() {
        throw new UnsupportedOperationException();
    }

    @Override// 将该ByteBuf对象中指定区间的数据存入至ByteBuf对象中
    public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.capacity());
        if (dst.hasMemoryAddress()) { // direct
        	// 内存复制复制
            PlatformDependent.copyMemory(array, index, dst.memoryAddress() + dstIndex, length);
        } else if (dst.hasArray()) { // heap
        	// 调用System.arraycopy()方法
            getBytes(index, dst.array(), dst.arrayOffset() + dstIndex, length);
        } else {
            dst.setBytes(dstIndex, array, index, length);
        }
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.length);
        // public static void arraycopy(Object src, int srcPos, Object dest, int destPos, int length)
        // src:源数组;
        // srcPos:源数组要复制的起始位置;
        // dest:目的数组;
        // destPos:目的数组放置的起始位置;
        // length:复制的长度;
        // 注意：src 和 dest都必须是同类型或者可以进行转换类型的数组．
        // 值传递:一维数组不存在引用
        System.arraycopy(array, index, dst, dstIndex, length);
        return this;
    }

    @Override// 将指定区间中的数据存入dst中
    public ByteBuf getBytes(int index, ByteBuffer dst) {
        checkIndex(index, dst.remaining());
        dst.put(array, index, dst.remaining());
        return this;
    }

    @Override // 将array中的指定区间数据存入OutputStream对象中
    public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        ensureAccessible();
        // 将array写入OutputStream中
        out.write(array, index, length);
        return this;
    }

    @Override // 将从index为开始位置的长度为length的数据写入到 GatheringByteChannel中
    public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        ensureAccessible();
        return getBytes(index, out, length, false);
    }

    // 将从index为开始位置的长度为length的数据写入到 GatheringByteChannel中
    private int getBytes(int index, GatheringByteChannel out, int length, boolean internal) throws IOException {
        ensureAccessible();
        ByteBuffer tmpBuf;
        // 创建ByteBuffer对象
        if (internal) {
            tmpBuf = internalNioBuffer();
        } else {
            tmpBuf = ByteBuffer.wrap(array);
        }
        // 将index至index + length中的字节写入到GatheringByteChannel中
        return out.write((ByteBuffer) tmpBuf.clear().position(index).limit(index + length));
    }

    @Override // 将该ByteBuf对象中的length长度数据写入到GatheringByteChannel中
    public int readBytes(GatheringByteChannel out, int length) throws IOException {
    	// 可读字节数检验
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, length, true);
        // 设置可读字节索引
        readerIndex += readBytes;
        return readBytes;
    }

    @Override// 将该ByteBuf对象中以index开头的长度为length的数据写入到src(ByteBuf)对象中
    public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.capacity());
        if (src.hasMemoryAddress()) {
            PlatformDependent.copyMemory(src.memoryAddress() + srcIndex, array, index, length);
        } else  if (src.hasArray()) {
        	// 将src中字节设置到array字节数组中
            setBytes(index, src.array(), src.arrayOffset() + srcIndex, length);
        } else {
            src.getBytes(srcIndex, array, index, length);
        }
        return this;
    }

    @Override// 将array数组中的数据写入到src数组中
    public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
    	// 检验输入字段是否正确
        checkSrcIndex(index, length, srcIndex, src.length);
        // 将src起始位置为srcIndex,长度为length的字节数组复制到以索引为index的array字节数组中
        System.arraycopy(src, srcIndex, array, index, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuffer src) {
        ensureAccessible();
        // 将ByteBuffer中的字节复制到array字节数组中
        src.get(array, index, src.remaining());
        return this;
    }

    @Override// 从in(InputStream)中读取长度为length的数据写入到该ByteBuf对象中
    public int setBytes(int index, InputStream in, int length) throws IOException {
        ensureAccessible();
        // 从输入流InputStream中复制字节数组至array中去
        return in.read(array, index, length);
    }

    @Override// 从in(ScatteringByteChannel)中读取长度为length的数据写入到该ByteBuf对象中
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        ensureAccessible();
        try {
        	// 从Socket中读取字节数组到缓冲区java.nio.ByteBuffer中，它的起始位position为index,limit为index + length
            return in.read((ByteBuffer) internalNioBuffer().clear().position(index).limit(index + length));
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public int nioBufferCount() {
        return 1;
    }

    @Override // 将字节数组array添加至ByteBuffer中
    public ByteBuffer nioBuffer(int index, int length) {
        ensureAccessible();
        return ByteBuffer.wrap(array, index, length).slice();
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        return new ByteBuffer[] { nioBuffer(index, length) };
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        checkIndex(index, length);
        return (ByteBuffer) internalNioBuffer().clear().position(index).limit(index + length);
    }

    @Override// 获取指定index上的byte数据(1字节)
    public byte getByte(int index) {
        ensureAccessible();
        return _getByte(index);
    }

    @Override
    protected byte _getByte(int index) {
    	// 获取指定位置的字节
        return HeapByteBufUtil.getByte(array, index);
    }

    @Override// 获取指定index上的short数据(2字节)
    public short getShort(int index) {
        ensureAccessible();
        return _getShort(index);
    }

    @Override
    protected short _getShort(int index) {
    	// 从array中index获取Short字节
        return HeapByteBufUtil.getShort(array, index);
    }

    @Override// 获取指定index上的medium数据(3字节)
    public int getUnsignedMedium(int index) {
        ensureAccessible();
        return _getUnsignedMedium(index);
    }

    @Override
    protected int _getUnsignedMedium(int index) {
        return HeapByteBufUtil.getUnsignedMedium(array, index);
    }

    @Override// 获取指定index上的int数据(4字节)
    public int getInt(int index) {
        ensureAccessible();
        return _getInt(index);
    }

    @Override
    protected int _getInt(int index) {
    	// 获取int类型的字节数
        return HeapByteBufUtil.getInt(array, index);
    }

    @Override// 获取指定index上的long数据(8字节)
    public long getLong(int index) {
        ensureAccessible();
        return _getLong(index);
    }

    @Override
    protected long _getLong(int index) {
    	// 获取Long类型的
        return HeapByteBufUtil.getLong(array, index);
    }

    @Override // 设置指定index上的byte数据(1字节)
    public ByteBuf setByte(int index, int value) {
        ensureAccessible();
        _setByte(index, value);
        return this;
    }

    @Override
    protected void _setByte(int index, int value) {
    	// 在指定位置index添加value
        HeapByteBufUtil.setByte(array, index, value);
    }

    @Override// 设置指定index上的short数据(2字节)
    public ByteBuf setShort(int index, int value) {
        ensureAccessible();
        _setShort(index, value);
        return this;
    }

    @Override
    protected void _setShort(int index, int value) {
        HeapByteBufUtil.setShort(array, index, value);
    }

    @Override// 设置指定index上的medium数据(3字节)
    public ByteBuf setMedium(int index, int   value) {
        ensureAccessible();
        _setMedium(index, value);
        return this;
    }

    @Override
    protected void _setMedium(int index, int value) {
        HeapByteBufUtil.setMedium(array, index, value);
    }

    @Override// 设置指定index上的int数据(4字节)
    public ByteBuf setInt(int index, int   value) {
        ensureAccessible();
        _setInt(index, value);
        return this;
    }

    @Override
    protected void _setInt(int index, int value) {
        HeapByteBufUtil.setInt(array, index, value);
    }

    @Override// 设置指定index上的long数据(8字节)
    public ByteBuf setLong(int index, long  value) {
        ensureAccessible();
        _setLong(index, value);
        return this;
    }

    @Override
    protected void _setLong(int index, long value) {
        HeapByteBufUtil.setLong(array, index, value);
    }

    @Override // 复制并返回UnpooledHeapByteBuf对象
    public ByteBuf copy(int index, int length) {
        checkIndex(index, length);
        byte[] copiedArray = new byte[length];
        // 内存复制函数
        System.arraycopy(array, index, copiedArray, 0, length);
        // 创建UnpooledHeapByteBuf对象
        return new UnpooledHeapByteBuf(alloc(), copiedArray, maxCapacity());
    }

    // 创建ByteBuffer对象
    private ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
        	// 将字节数组array转换成tmpNioBuf中去
            this.tmpNioBuf = tmpNioBuf = ByteBuffer.wrap(array);
        }
        return tmpNioBuf;
    }

    @Override
    protected void deallocate() {
        freeArray(array);
        array = null;
    }

    @Override
    public ByteBuf unwrap() {
        return null;
    }
}
