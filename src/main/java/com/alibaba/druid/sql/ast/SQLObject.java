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
import java.util.Map;

import com.alibaba.druid.sql.visitor.SQLASTVisitor;

/**
 * 在druid 中每个sql 都被抽象成对象
 */
public interface SQLObject {
    /**
     * 传入一个观察者对象
     * @param visitor
     */
    void                accept(SQLASTVisitor visitor);

    /**
     * 拷贝一个对象副本
     * @return
     */
    SQLObject           clone();

    /**
     * 获取该对象的父类
     * @return
     */
    SQLObject           getParent();

    /**
     * 设置父对象
     * @param parent
     */
    void                setParent(SQLObject parent);

    /**
     * 获取属性信息
     * @return
     */
    Map<String, Object> getAttributes();

    /**
     * 判断是否包含某个属性
     * @param name
     * @return
     */
    boolean             containsAttribute(String name);

    /**
     * 获取对应属性
     * @param name
     * @return
     */
    Object              getAttribute(String name);

    /**
     * 存储对应属性
     * @param name
     * @param value
     */
    void                putAttribute(String name, Object value);

    /**
     * 返回属性集合 跟getAttributes 的区别是???
     * @return
     */
    Map<String, Object> getAttributesDirect();

    /**
     * 将内容输出到 StringBuf 中
     * @param buf
     */
    void                output(StringBuffer buf);

    /**
     * 在前面追加上注释信息
     * @param comment
     */
    void                addBeforeComment(String comment);

    /**
     * 追加一组注释信息
     * @param comments
     */
    void                addBeforeComment(List<String> comments);

    /**
     * 获取前置注释信息
     * @return
     */
    List<String>        getBeforeCommentsDirect();
    void                addAfterComment(String comment);
    void                addAfterComment(List<String> comments);
    List<String>        getAfterCommentsDirect();
    boolean             hasBeforeComment();
    boolean             hasAfterComment();
}
