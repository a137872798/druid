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

import com.alibaba.druid.sql.visitor.SQLASTOutputVisitor;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;

/**
 * 注释线索对象
 */
public class SQLCommentHint extends SQLObjectImpl implements SQLHint {

    /**
     * 注释文本
     */
    private String text;

    public SQLCommentHint(){

    }

    public SQLCommentHint(String text){

        this.text = text;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
    }

    /**
     * 当传入一个观察者时触发
     * @param visitor
     */
    protected void accept0(SQLASTVisitor visitor) {
        // 触发 观察方法
        visitor.visit(this);
        // 后置函数
        visitor.endVisit(this);
    }

    public SQLCommentHint clone() {
        return new SQLCommentHint(text);
    }

    public void output(StringBuffer buf) {
        new SQLASTOutputVisitor(buf).visit(this);
    }
}
