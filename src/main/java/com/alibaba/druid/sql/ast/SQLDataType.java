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
package com.alibaba.druid.sql.ast;

import java.util.List;

/**
 * 数据类型
 */
public interface SQLDataType extends SQLObject {

    /**
     * 获取该类型的名称
     * @return
     */
    String getName();

    /**
     * 名称对应的hash值
     * @return
     */
    long nameHashCode64();

    /**
     * 设置名称
     * @param name
     */
    void setName(String name);

    /**
     * 获取参数信息
     * @return 返回一组sql 表达式
     */
    List<SQLExpr> getArguments();

    /**
     * 是否包含时区信息
     * @return
     */
    Boolean getWithTimeZone();

    /**
     * 设置时区信息
     * @param value
     */
    void  setWithTimeZone(Boolean value);

    /**
     * 是否包含本地时区
     * @return
     */
    boolean isWithLocalTimeZone();
    void setWithLocalTimeZone(boolean value);

    SQLDataType clone();

    void setDbType(String dbType);
    String getDbType();

    /**
     * 数据类型  通过声明接口的方式来创建常量吗
     */
    interface Constants {
        String CHAR = "CHAR";
        String NCHAR = "NCHAR";
        String VARCHAR = "VARCHAR";
        String DATE = "DATE";
        String TIMESTAMP = "TIMESTAMP";
        String XML = "XML";

        String DECIMAL = "DECIMAL";
        String NUMBER = "NUMBER";
        String REAL = "REAL";
        String DOUBLE_PRECISION = "DOUBLE PRECISION";

        String TINYINT = "TINYINT";
        String SMALLINT = "SMALLINT";
        String INT = "INT";
        String BIGINT = "BIGINT";
        String TEXT = "TEXT";
        String BYTEA = "BYTEA";
        String BOOLEAN = "BOOLEAN";
    }
}
