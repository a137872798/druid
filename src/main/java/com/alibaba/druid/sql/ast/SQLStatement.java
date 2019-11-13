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
 * 会话对象也是实现sqlObject 接口
 */
public interface SQLStatement extends SQLObject {
    /**
     * 获取db类型
     * @return
     */
    String          getDbType();
    boolean         isAfterSemi();
    void            setAfterSemi(boolean afterSemi);

    /**
     * 生成副本对象
     * @return
     */
    SQLStatement    clone();

    /**
     * 获取子对象列表
     * @return
     */
    List<SQLObject> getChildren();

    /**
     * 返回小写字符串
     * @return
     */
    String          toLowerCaseString();

    List<SQLCommentHint> getHeadHintsDirect();
    void setHeadHints(List<SQLCommentHint> headHints);
}
