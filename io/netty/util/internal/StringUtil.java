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

import java.io.IOException;

import static io.netty.util.internal.ObjectUtil.*;

/**
 * String utility class.
 */
public final class StringUtil {

    public static final String NEWLINE = SystemPropertyUtil.get("line.separator", "\n");
    public static final char DOUBLE_QUOTE = '\"';
    public static final char COMMA = ',';
    public static final char LINE_FEED = '\n';
    public static final char CARRIAGE_RETURN = '\r';
    public static final String EMPTY_STRING = "";
    private static final String[] BYTE2HEX_PAD = new String[256];
    private static final String[] BYTE2HEX_NOPAD = new String[256];

    /**
     * 2 - Quote character at beginning and end.
     * 5 - Extra allowance for anticipated escape characters that may be added.
     */
    private static final int CSV_NUMBER_ESCAPE_CHARACTERS = 2 + 5;
    private static final char PACKAGE_SEPARATOR_CHAR = '.';

    static {
        // Generate the lookup table that converts a byte into a 2-digit hexadecimal integer.
        int i;
        for (i = 0; i < 10; i ++) {
            BYTE2HEX_PAD[i] = "0" + i;
            BYTE2HEX_NOPAD[i] = String.valueOf(i);
        }
        for (; i < 16; i ++) {
            char c = (char) ('a' + i - 10);
            BYTE2HEX_PAD[i] = "0" + c;
            BYTE2HEX_NOPAD[i] = String.valueOf(c);
        }
        for (; i < BYTE2HEX_PAD.length; i ++) {
            String str = Integer.toHexString(i);
            BYTE2HEX_PAD[i] = str;
            BYTE2HEX_NOPAD[i] = str;
        }
    }

    private StringUtil() {
        // Unused.
    }

    /**
     * Get the item after one char delim if the delim is found (else null).
     * This operation is a simplified and optimized
     * version of {@link String#split(String, int)}.
     */
    public static String substringAfter(String value, char delim) {
        int pos = value.indexOf(delim);
        if (pos >= 0) {
            return value.substring(pos + 1);
        }
        return null;
    }

    /**
     * Checks if two strings have the same suffix of specified length
     *
     * @param s            string
     * @param p            string
     * @param len length of the common suffix
     * @return true if both s and p are not null and both have the same suffix. Otherwise - false
     */
    public static boolean commonSuffixOfLength(String s, String p, int len) {
        return s != null && p != null && len >= 0 && s.regionMatches(s.length() - len, p, p.length() - len, len);
    }

    /**
     * Converts the specified byte value into a 2-digit hexadecimal integer.
     */
    public static String byteToHexStringPadded(int value) {
        return BYTE2HEX_PAD[value & 0xff];
    }

    /**
     * Converts the specified byte value into a 2-digit hexadecimal integer and appends it to the specified buffer.
     */
    public static <T extends Appendable> T byteToHexStringPadded(T buf, int value) {
        try {
            buf.append(byteToHexStringPadded(value));
        } catch (IOException e) {
            PlatformDependent.throwException(e);
        }
        return buf;
    }

    /**
     * Converts the specified byte array into a hexadecimal value.
     */
    public static String toHexStringPadded(byte[] src) {
        return toHexStringPadded(src, 0, src.length);
    }

    /**
     * Converts the specified byte array into a hexadecimal value.
     */
    public static String toHexStringPadded(byte[] src, int offset, int length) {
        return toHexStringPadded(new StringBuilder(length << 1), src, offset, length).toString();
    }

    /**
     * Converts the specified byte array into a hexadecimal value and appends it to the specified buffer.
     */
    public static <T extends Appendable> T toHexStringPadded(T dst, byte[] src) {
        return toHexStringPadded(dst, src, 0, src.length);
    }

    /**
     * Converts the specified byte array into a hexadecimal value and appends it to the specified buffer.
     */
    public static <T extends Appendable> T toHexStringPadded(T dst, byte[] src, int offset, int length) {
        final int end = offset + length;
        for (int i = offset; i < end; i ++) {
            byteToHexStringPadded(dst, src[i]);
        }
        return dst;
    }

    /**
     * Converts the specified byte value into a hexadecimal integer.
     */
    public static String byteToHexString(int value) {
        return BYTE2HEX_NOPAD[value & 0xff];
    }

    /**
     * Converts the specified byte value into a hexadecimal integer and appends it to the specified buffer.
     */
    public static <T extends Appendable> T byteToHexString(T buf, int value) {
        try {
            buf.append(byteToHexString(value));
        } catch (IOException e) {
            PlatformDependent.throwException(e);
        }
        return buf;
    }

    /**
     * Converts the specified byte array into a hexadecimal value.
     */
    public static String toHexString(byte[] src) {
        return toHexString(src, 0, src.length);
    }

    /**
     * Converts the specified byte array into a hexadecimal value.
     */
    public static String toHexString(byte[] src, int offset, int length) {
        return toHexString(new StringBuilder(length << 1), src, offset, length).toString();
    }

    /**
     * Converts the specified byte array into a hexadecimal value and appends it to the specified buffer.
     */
    public static <T extends Appendable> T toHexString(T dst, byte[] src) {
        return toHexString(dst, src, 0, src.length);
    }

    /**
     * Converts the specified byte array into a hexadecimal value and appends it to the specified buffer.
     */
    public static <T extends Appendable> T toHexString(T dst, byte[] src, int offset, int length) {
        assert length >= 0;
        if (length == 0) {
            return dst;
        }

        final int end = offset + length;
        final int endMinusOne = end - 1;
        int i;

        // Skip preceding zeroes.
        for (i = offset; i < endMinusOne; i ++) {
            if (src[i] != 0) {
                break;
            }
        }

        byteToHexString(dst, src[i ++]);
        int remaining = end - i;
        toHexStringPadded(dst, src, i, remaining);

        return dst;
    }

    /**
     * Helper to decode half of a hexadecimal number from a string.
     * @param c The ASCII character of the hexadecimal number to decode.
     * Must be in the range {@code [0-9a-fA-F]}.
     * @return The hexadecimal value represented in the ASCII character
     * given, or {@code -1} if the character is invalid.
     */
    public static int decodeHexNibble(final char c) {
        // Character.digit() is not used here, as it addresses a larger
        // set of characters (both ASCII and full-width latin letters).
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 0xA;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 0xA;
        }
        return -1;
    }

    /**
     * Decode a 2-digit hex byte from within a string.
     */
    public static byte decodeHexByte(CharSequence s, int pos) {
        int hi = decodeHexNibble(s.charAt(pos));
        int lo = decodeHexNibble(s.charAt(pos + 1));
        if (hi == -1 || lo == -1) {
            throw new IllegalArgumentException(String.format(
                    "invalid hex byte '%s' at index %d of '%s'", s.subSequence(pos, pos + 2), pos, s));
        }
        return (byte) ((hi << 4) + lo);
    }

    /**
     * Decodes part of a string with <a href="http://en.wikipedia.org/wiki/Hex_dump">hex dump</a>
     *
     * @param hexDump a {@link CharSequence} which contains the hex dump
     * @param fromIndex start of hex dump in {@code hexDump}
     * @param length hex string length
     */
    public static byte[] decodeHexDump(CharSequence hexDump, int fromIndex, int length) {
        if (length < 0 || (length & 1) != 0) {
            throw new IllegalArgumentException("length: " + length);
        }
        if (length == 0) {
            return EmptyArrays.EMPTY_BYTES;
        }
        byte[] bytes = new byte[length >>> 1];
        for (int i = 0; i < length; i += 2) {
            bytes[i >>> 1] = decodeHexByte(hexDump, fromIndex + i);
        }
        return bytes;
    }

    /**
     * Decodes a <a href="http://en.wikipedia.org/wiki/Hex_dump">hex dump</a>
     */
    public static byte[] decodeHexDump(CharSequence hexDump) {
        return decodeHexDump(hexDump, 0, hexDump.length());
    }

    /**
     * The shortcut to {@link #simpleClassName(Class) simpleClassName(o.getClass())}.
     */
    public static String simpleClassName(Object o) {
        if (o == null) {
            return "null_object";
        } else {
            return simpleClassName(o.getClass());
        }
    }

    /**
     * Generates a simplified name from a {@link Class}.  Similar to {@link Class#getSimpleName()}, but it works fine
     * with anonymous classes.
     */
    // 获取类名称
    public static String simpleClassName(Class<?> clazz) {
    	// 获取Class类名称
        String className = ObjectUtil.checkNotNull(clazz, "clazz").getName();
        // 判断className是否存在'.'字符
        final int lastDotIdx = className.lastIndexOf(PACKAGE_SEPARATOR_CHAR);
        if (lastDotIdx > -1) {
        	// 截取相应的字符串
            return className.substring(lastDotIdx + 1);
        }
        // 返回className
        return className;
    }

    /**
     * Escapes the specified value, if necessary according to
     * <a href="https://tools.ietf.org/html/rfc4180#section-2">RFC-4180</a>.
     *
     * @param value The value which will be escaped according to
     *              <a href="https://tools.ietf.org/html/rfc4180#section-2">RFC-4180</a>
     * @return {@link CharSequence} the escaped value if necessary, or the value unchanged
     */
    public static CharSequence escapeCsv(CharSequence value) {
        int length = checkNotNull(value, "value").length();
        if (length == 0) {
            return value;
        }
        int last = length - 1;
        boolean quoted = isDoubleQuote(value.charAt(0)) && isDoubleQuote(value.charAt(last)) && length != 1;
        boolean foundSpecialCharacter = false;
        boolean escapedDoubleQuote = false;
        StringBuilder escaped = new StringBuilder(length + CSV_NUMBER_ESCAPE_CHARACTERS).append(DOUBLE_QUOTE);
        for (int i = 0; i < length; i++) {
            char current = value.charAt(i);
            switch (current) {
                case DOUBLE_QUOTE:
                    if (i == 0 || i == last) {
                        if (!quoted) {
                            escaped.append(DOUBLE_QUOTE);
                        } else {
                            continue;
                        }
                    } else {
                        boolean isNextCharDoubleQuote = isDoubleQuote(value.charAt(i + 1));
                        if (!isDoubleQuote(value.charAt(i - 1)) &&
                                (!isNextCharDoubleQuote || i + 1 == last)) {
                            escaped.append(DOUBLE_QUOTE);
                            escapedDoubleQuote = true;
                        }
                        break;
                    }
                case LINE_FEED:
                case CARRIAGE_RETURN:
                case COMMA:
                    foundSpecialCharacter = true;
            }
            escaped.append(current);
        }
        return escapedDoubleQuote || foundSpecialCharacter && !quoted ?
                escaped.append(DOUBLE_QUOTE) : value;
    }

    /**
     * Unescapes the specified escaped CSV field, if necessary according to
     * <a href="https://tools.ietf.org/html/rfc4180#section-2">RFC-4180</a>.
     *
     * @param value The escaped CSV field which will be unescaped according to
     *              <a href="https://tools.ietf.org/html/rfc4180#section-2">RFC-4180</a>
     * @return {@link CharSequence} the unescaped value if necessary, or the value unchanged
     */
    public static CharSequence unescapeCsv(CharSequence value) {
        int length = checkNotNull(value, "value").length();
        if (length == 0) {
            return value;
        }
        int last = length - 1;
        boolean quoted = isDoubleQuote(value.charAt(0)) && isDoubleQuote(value.charAt(last)) && length != 1;
        if (!quoted) {
            validateCsvFormat(value);
            return value;
        }
        StringBuilder unescaped = InternalThreadLocalMap.get().stringBuilder();
        for (int i = 1; i < last; i++) {
            char current = value.charAt(i);
            if (current == DOUBLE_QUOTE) {
                if (isDoubleQuote(value.charAt(i + 1)) && (i + 1) != last) {
                    // Followed by a double-quote but not the last character
                    // Just skip the next double-quote
                    i++;
                } else {
                    // Not followed by a double-quote or the following double-quote is the last character
                    throw newInvalidEscapedCsvFieldException(value, i);
                }
            }
            unescaped.append(current);
        }
        return unescaped.toString();
    }

    /**
     * Validate if {@code value} is a valid csv field without double-quotes.
     *
     * @throws IllegalArgumentException if {@code value} needs to be encoded with double-quotes.
     */
    private static void validateCsvFormat(CharSequence value) {
        int length = value.length();
        for (int i = 0; i < length; i++) {
            switch (value.charAt(i)) {
                case DOUBLE_QUOTE:
                case LINE_FEED:
                case CARRIAGE_RETURN:
                case COMMA:
                    // If value contains any special character, it should be enclosed with double-quotes
                    throw newInvalidEscapedCsvFieldException(value, i);
                default:
            }
        }
    }

    private static IllegalArgumentException newInvalidEscapedCsvFieldException(CharSequence value, int index) {
        return new IllegalArgumentException("invalid escaped CSV field: " + value + " index: " + index);
    }

    /**
     * Get the length of a string, {@code null} input is considered {@code 0} length.
     */
    // 获取字符串长度
    public static int length(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * Determine if a string is {@code null} or {@link String#isEmpty()} returns {@code true}.
     */
    // 判断字符串是否为空
    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * Determine if {@code c} lies within the range of values defined for
     * <a href="http://unicode.org/glossary/#surrogate_code_point">Surrogate Code Point</a>.
     * @param c the character to check.
     * @return {@code true} if {@code c} lies within the range of values defined for
     * <a href="http://unicode.org/glossary/#surrogate_code_point">Surrogate Code Point</a>. {@code false} otherwise.
     */
    public static boolean isSurrogate(char c) {
        return c >= '\uD800' && c <= '\uDFFF';
    }

    private static boolean isDoubleQuote(char c) {
        return c == DOUBLE_QUOTE;
    }
}
