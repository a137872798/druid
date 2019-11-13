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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.util.FnvHash;

/**
 * 插入列数的缓存
 */
public class InsertColumnsCache {
    /**
     * 全局范围的缓存
     */
    public static InsertColumnsCache global;

    static {
        // 初始化缓存对象
        global = new InsertColumnsCache(8192);
    }

    /**
     * key 为hash值  value 为存储的实体
     */
    public ConcurrentMap<Long, Entry> cache = new ConcurrentHashMap<Long, Entry>();

    private final Entry[]   buckets;
    private final int       indexMask;

    /**
     * 该对象实际上就是一个ConMap
     * @param tableSize
     */
    public InsertColumnsCache(int tableSize){
        this.indexMask = tableSize - 1;
        this.buckets = new Entry[tableSize];
    }

    /**
     * 没有使用链表结构 如果在单层没有找到就返回null
     * @param hashCode64
     * @return
     */
    public final Entry get(long hashCode64) {
        final int bucket = ((int) hashCode64) & indexMask;
        for (Entry entry = buckets[bucket]; entry != null; entry = entry.next) {
            if (hashCode64 == entry.hashCode64) {
                return entry;
            }
        }

        return null;
    }

    /**
     * 缓存 插入列信息
     * @param hashCode64
     * @param columnsString
     * @param columnsFormattedString
     * @param columns
     * @return
     */
    public boolean put(long hashCode64, String columnsString, String columnsFormattedString, List<SQLExpr> columns) {
        final int bucket = ((int) hashCode64) & indexMask;

        for (Entry entry = buckets[bucket]; entry != null; entry = entry.next) {
            if (hashCode64 == entry.hashCode64) {
                return true;
            }
        }

        Entry entry = new Entry(hashCode64, columnsString, columnsFormattedString, columns, buckets[bucket]);
        buckets[bucket] = entry;  // 并发是处理时会可能导致缓存丢失，但不影响正确性

        return false;
    }

    /**
     * 每个实体代表一个 InsertColumn
     */
    public final static class Entry {
        /**
         * 该对象对应的hash值
         */
        public final long hashCode64;
        /**
         * 该列内容
         */
        public final String columnsString;
        /**
         * 格式化后的信息
         */
        public final String columnsFormattedString;
        /**
         * 格式化后信息的hash值
         */
        public final long columnsFormattedStringHash;
        /**
         * 每一列
         */
        public final List<SQLExpr> columns;
        public final Entry next;

        public Entry(long hashCode64, String columnsString, String columnsFormattedString, List<SQLExpr> columns, Entry next) {
            this.hashCode64 = hashCode64;
            this.columnsString = columnsString;
            this.columnsFormattedString = columnsFormattedString;
            this.columnsFormattedStringHash = FnvHash.fnv1a_64_lower(columnsFormattedString);
            this.columns = columns;
            this.next = next;
        }
    }
}
