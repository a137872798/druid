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
package com.alibaba.druid.sql.visitor;

import java.util.List;

/**
 * 参数化观察者   实现可打印观察者接口
 */
public interface ParameterizedVisitor extends PrintableVisitor {

    /**
     * 需要被替换的参数数量
     * @return
     */
    int getReplaceCount();

    /**
     * 增加需要被替换的参数数量
     */
    void incrementReplaceCunt();

    /**
     * 获取数据库类型
     * @return
     */
    String getDbType();

    /**
     * 设置输出参数
     * @param parameters
     */
    void setOutputParameters(List<Object> parameters);

    /**
     * 通过feature 来配置该对象
     * @param feature
     * @param state
     */
    void config(VisitorFeature feature, boolean state);
}
