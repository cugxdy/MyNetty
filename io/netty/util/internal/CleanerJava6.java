/*
* Copyright 2014 The Netty Project
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;


/**
 * Allows to free direct {@link ByteBuffer} by using Cleaner. This is encapsulated in an extra class to be able
 * to use {@link PlatformDependent0} on Android without problems.
 *
 * For more details see <a href="https://github.com/netty/netty/issues/2604">#2604</a>.
 */
final class CleanerJava6 implements Cleaner {
    private static final long CLEANER_FIELD_OFFSET;
    private static final Method CLEAN_METHOD;

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(CleanerJava6.class);

    static {
        long fieldOffset = -1;
        Method clean = null;
        Throwable error = null;
        if (PlatformDependent0.hasUnsafe()) {
            ByteBuffer direct = ByteBuffer.allocateDirect(1);
            try {
            	// 获取ByteBuffer对象的cleaner属性
                Field cleanerField = direct.getClass().getDeclaredField("cleaner");
                // 属性偏移量
                fieldOffset = PlatformDependent0.objectFieldOffset(cleanerField);
                // 获取Cleaner对象
                Object cleaner = PlatformDependent0.getObject(direct, fieldOffset);
                clean = cleaner.getClass().getDeclaredMethod("clean");
                // Cleaner.clean()方法
                clean.invoke(cleaner);
            } catch (Throwable t) {
                // We don't have ByteBuffer.cleaner().
                fieldOffset = -1;
                clean = null;
                error = t;
            }
        } else {
        	// 抛出UnsupportedOperationException异常
            error = new UnsupportedOperationException("sun.misc.Unsafe unavailable");
        }
        if (error == null) {
            logger.debug("java.nio.ByteBuffer.cleaner(): available");
        } else {
            logger.debug("java.nio.ByteBuffer.cleaner(): unavailable", error);
        }
        // 设置相应属性值
        CLEANER_FIELD_OFFSET = fieldOffset;
        CLEAN_METHOD = clean;
    }

    // 判断是否支持DirectByteBuffer中的Cleaner属性值
    static boolean isSupported() {
        return CLEANER_FIELD_OFFSET != -1;
    }

    @Override // 释放DirectByteBuffer内存
    public void freeDirectBuffer(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            return;
        }
        try {
        	// 调用JDK中DirectByteBuffer对象中的Cleaner属性释放内存
            Object cleaner = PlatformDependent0.getObject(buffer, CLEANER_FIELD_OFFSET);
            if (cleaner != null) {
                CLEAN_METHOD.invoke(cleaner);
            }
        } catch (Throwable cause) {
            PlatformDependent0.throwException(cause);
        }
    }
}
