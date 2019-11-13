/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.sql.parser;

import java.nio.charset.Charset;

/**
 * 符号表
 * @author wenshao[szujobs@hotmail.com]
 */
public class SymbolTable {
    /**
     * 默认字符集
     */
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /**
     * 判断当前java 版本是否是 1.6
     */
    private static final boolean JVM_16;

    static {
        String version = null;
        try {
            // 该属性是 jvm 自动设置的  对应jdk 的版本
            version = System.getProperty("java.specification.version");
        } catch (Throwable error) {
            // skip
        }
        JVM_16 = "1.6".equals(version);
    }

    /**
     * 单例对象
     */
    public static SymbolTable global = new SymbolTable(32768);

    /**
     * 符号表中每个对象 被包裹成 Entry
     */
    private final Entry[] entries;
    /**
     * 掩码 与 hash 配合 用来寻找 数组下标
     */
    private final int      indexMask;

    /**
     * 初始化 符号表
     * @param tableSize
     */
    public SymbolTable(int tableSize){
        this.indexMask = tableSize - 1;
        this.entries = new Entry[tableSize];
    }

    /**
     * 将某个符号插入到 符号表中
     * @param buffer   某个文本
     * @param offset   该符号针对该文本的起始偏移量
     * @param len     该符号的长度
     * @param hash   该符号对应的hash值
     * @return
     */
    public String addSymbol(String buffer, int offset, int len, long hash) {
        // 通过hash 值定位到 数组下标
        final int bucket = ((int) hash) & indexMask;

        Entry entry = entries[bucket];
        if (entry != null) {
            // 代表hash 刚好相同 那么直接返回结果
            if (hash == entry.hash) {
                return entry.value;
            }

            // 否则 截获字符串后 返回
            String str = JVM_16
                    ? subString(buffer, offset, len)
                    : buffer.substring(offset, offset + len);

            return str;
        }

        // 当 符号表数组还没有设置该结果时  将结果填充到 数组中
        String str = JVM_16
                ? subString(buffer, offset, len)
                : buffer.substring(offset, offset + len);
        entry = new Entry(hash, len, str);
        entries[bucket] = entry;
        return str;
    }

    /**
     * 如果数据存在于 byte[] 中
     * @param buffer
     * @param offset
     * @param len
     * @param hash
     * @return
     */
    public String addSymbol(byte[] buffer, int offset, int len, long hash) {
        final int bucket = ((int) hash) & indexMask;

        Entry entry = entries[bucket];
        if (entry != null) {
            if (hash == entry.hash) {
                return entry.value;
            }

            // 从数组中抽取对应的数据 组合成string
            String str = subString(buffer, offset, len);

            return str;
        }

        String str = subString(buffer, offset, len);
        entry = new Entry(hash, len, str);
        entries[bucket] = entry;
        return str;
    }

    public String addSymbol(String symbol, long hash) {
        final int bucket = ((int) hash) & indexMask;

        Entry entry = entries[bucket];
        if (entry != null) {
            if (hash == entry.hash) {
                return entry.value;
            }

            return symbol;
        }

        entry = new Entry(hash, symbol.length(), symbol);
        entries[bucket] = entry;
        return symbol;
    }

    /**
     * 通过hash 值找到某个符号数据
     * @param hash
     * @return
     */
    public String findSymbol(long hash) {
        final int bucket = ((int) hash) & indexMask;
        Entry entry = entries[bucket];
        if (entry != null && entry.hash == hash) {
            return entry.value;
        }
        return null;
    }

    private static String subString(String src, int offset, int len) {
        char[] chars = new char[len];
        src.getChars(offset, offset + len, chars, 0);
        return new String(chars);
    }

    private static String subString(byte[] bytes, int from, int len) {
        byte[] strBytes = new byte[len];
        System.arraycopy(bytes, from, strBytes, 0, len);
        return new String(strBytes, UTF8);
    }

    /**
     * 每个符号被封装成该对象
     */
    private static class Entry {
        /**
         * 对应的hash只
         */
        public final long hash;
        /**
         * 该符号长度
         */
        public final int len;
        /**
         * 对应的具体值
         */
        public final String value;

        public Entry(long hash, int len, String value) {
            this.hash = hash;
            this.len = len;
            this.value = value;
        }
    }
}