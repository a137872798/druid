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

import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.util.FnvHash;
import com.alibaba.druid.util.StringUtils;

/**
 * sql 解析器   sharding-sphere 应该也有类似的实现
 */
public class SQLParser {
    /**
     * 词法解析器
     */
    protected final Lexer lexer;
    /**
     * 数据库类型
     */
    protected String      dbType;

    /**
     * 通过sql 语句初始化解析器对象
     * @param sql
     * @param dbType  传入db类型是因为 不同的 db 词法方面会有些微不同  允许dbType 为空 那么都按默认的词法进行解析
     */
    public SQLParser(String sql, String dbType){
        this(new Lexer(sql, null, dbType), dbType);
        // 一旦 lexer 对象被初始化 就会调用nextToken 获取第一个关键字信息
        this.lexer.nextToken();
    }

    public SQLParser(String sql){
        this(sql, null);
    }

    public SQLParser(Lexer lexer){
        this(lexer, null);
    }

    public SQLParser(Lexer lexer, String dbType){
        this.lexer = lexer;
        this.dbType = dbType;
    }

    public final Lexer getLexer() {
        return lexer;
    }

    public String getDbType() {
        return dbType;
    }

    /**
     * 当前解析到的位置是否与 传入文本一致
     * @param text
     * @return
     */
    protected boolean identifierEquals(String text) {
        return lexer.identifierEquals(text);
    }

    protected void acceptIdentifier(String text) {
        // 如果相同就继续解析下一个token
        if (lexer.identifierEquals(text)) {
            lexer.nextToken();
        } else {
            // 否则设置异常信息
            setErrorEndPos(lexer.pos());
            // 抛出异常
            throw new ParserException("syntax error, expect " + text + ", actual " + lexer.token + ", " + lexer.info());
        }
    }

    /**
     * 获取表别名
     * @return
     */
    protected String tableAlias() {
        return tableAlias(false);
    }

    /**
     * 获取表别名
     * @param must  是否必要
     * @return
     */
    protected String tableAlias(boolean must) {
        final Token token = lexer.token;
        // 这些类型不能返回
        if (token == Token.CONNECT
                || token == Token.START
                || token == Token.SELECT
                || token == Token.FROM
                || token == Token.WHERE) {
            if (must) {
                throw new ParserException("illegal alias. " + lexer.info());
            }
            return null;
        }

        // 如果是 IDENTIFIER 类型
        if (token == Token.IDENTIFIER) {
            // 获取当前解析出来的字符串
            String ident = lexer.stringVal;
            // 获取当前hash值
            long hash = lexer.hash_lower;
            // 如果忽略名字引用  `` '' "" 这种
            if (isEnabled(SQLParserFeature.IgnoreNameQuotes) && ident.length() > 1) {
                // 移除掉名字引用的部分
                ident = StringUtils.removeNameQuotes(ident);
            }

            // hash值必须合法
            if (hash == FnvHash.Constants.START
                    || hash == FnvHash.Constants.CONNECT
                    || hash == FnvHash.Constants.NATURAL
                    || hash == FnvHash.Constants.CROSS
                    || hash == FnvHash.Constants.OFFSET
                    || hash == FnvHash.Constants.LIMIT) {
                if (must) {
                    throw new ParserException("illegal alias. " + lexer.info());
                }

                // 当没有设置必须返回时 尝试着生成
                // 获取当前保存点
                Lexer.SavePoint mark = lexer.mark();
                // 解析下一个token
                lexer.nextToken();
                switch (lexer.token) {
                    case EOF:
                    case COMMA:
                    case WHERE:
                    case INNER:
                        // 以上情况 允许返回 字符串
                        return ident;
                    default:
                        // 恢复 lexer 的指针
                        lexer.reset(mark);
                        break;
                }

                return null;
            }

            // 代表 hash不属于上面描述的那些
            if (!must) {
                // 如果是 model 模式
                if (hash == FnvHash.Constants.MODEL) {
                    // 记录当前位置 并获取下一个token
                    Lexer.SavePoint mark = lexer.mark();
                    lexer.nextToken();
                    // 这些不满足
                    if (lexer.token == Token.PARTITION
                            || lexer.token == Token.UNION
                            || lexer.identifierEquals(FnvHash.Constants.DIMENSION)
                            || lexer.identifierEquals(FnvHash.Constants.IGNORE)
                            || lexer.identifierEquals(FnvHash.Constants.KEEP)) {
                        lexer.reset(mark);
                        return null;
                    }
                    // 注意能返回 别名的时候 lexer的指针没有回退
                    return ident;
                    // 如果当前hash 是window 类型
                } else if (hash == FnvHash.Constants.WINDOW) {
                    Lexer.SavePoint mark = lexer.mark();
                    lexer.nextToken();
                    if (lexer.token == Token.IDENTIFIER) {
                        lexer.reset(mark);
                        return null;
                    }
                    return ident;
                    // 套路类似 就是如果 hash 是某种形式 然后 下一个token是某种形式 就认为可以返回 别名
                } else if (hash == FnvHash.Constants.DISTRIBUTE
                        || hash == FnvHash.Constants.SORT
                        || hash == FnvHash.Constants.CLUSTER
                ) {
                    Lexer.SavePoint mark = lexer.mark();
                    lexer.nextToken();
                    if (lexer.token == Token.BY) {
                        lexer.reset(mark);
                        return null;
                    }
                    return ident;
                }
            }
        }

        // 以上类型 hash 都没有匹配成功时  通过as 方法寻找别名
        return this.as();
    }

    /**
     * 寻找 AS 后面的字符
     * @return
     */
    protected String as() {
        String alias = null;

        final Token token = lexer.token;

        // 如果是 , 直接返回
        if (token == Token.COMMA) {
            return null;
        }

        // 如果匹配上 AS 那么 后面的应该就是表名
        if (token == Token.AS) {
            lexer.nextToken();
            alias = lexer.stringVal();
            lexer.nextToken();

            if (alias != null) {
                // 如果是 .   那么 应该是  AS  xxx.yyy
                while (lexer.token == Token.DOT) {
                    lexer.nextToken();
                    alias += ('.' + lexer.token.name());
                    lexer.nextToken();
                }

                return alias;
            }

            // 如果是 AS (   返回null
            if (lexer.token == Token.LPAREN) {
                return null;
            }

            // 其余情况 解析异常
            throw new ParserException("Error : " + lexer.info());
        }

        // 如果当前token 代表别名
        if (lexer.token == Token.LITERAL_ALIAS) {
            // 获取别名
            alias = lexer.stringVal();
            // 解析下个数据
            lexer.nextToken();
        // 如果是特征数值
        } else if (lexer.token == Token.IDENTIFIER) {
            alias = lexer.stringVal();
            lexer.nextToken();
        } else if (lexer.token == Token.LITERAL_CHARS) {
            alias = "'" + lexer.stringVal() + "'";
            lexer.nextToken();
        } else {
            switch (lexer.token) {
                case CASE:
                case USER:
                case LOB:
                case END:
                case DEFERRED:
                case OUTER:
                case DO:
                case STORE:
                case MOD:
                    alias = lexer.stringVal();
                    lexer.nextToken();
                    break;
                default:
                    break;
            }
        }

        // 这里已经是下一个token 了
        switch (lexer.token) {
            case KEY:
            case INTERVAL:
            case CONSTRAINT:
                // 将别名替换成这个
                alias = lexer.token.name();
                lexer.nextToken();
                return alias;
            default:
                break;
        }

        return alias;
    }

    /**
     * 这个别名是什么???
     * @return
     */
    protected String alias() {
        String alias = null;
        if (lexer.token == Token.LITERAL_ALIAS) {
            alias = lexer.stringVal();
            lexer.nextToken();
        } else if (lexer.token == Token.IDENTIFIER) {
            alias = lexer.stringVal();
            lexer.nextToken();
        } else if (lexer.token == Token.LITERAL_CHARS) {
            alias = "'" + lexer.stringVal() + "'";
            lexer.nextToken();
        } else {
            switch (lexer.token) {
                case KEY:
                case INDEX:
                case CASE:
                case MODEL:
                case PCTFREE:
                case INITRANS:
                case MAXTRANS:
                case SEGMENT:
                case CREATION:
                case IMMEDIATE:
                case DEFERRED:
                case STORAGE:
                case NEXT:
                case MINEXTENTS:
                case MAXEXTENTS:
                case MAXSIZE:
                case PCTINCREASE:
                case FLASH_CACHE:
                case CELL_FLASH_CACHE:
                case NONE:
                case LOB:
                case STORE:
                case ROW:
                case CHUNK:
                case CACHE:
                case NOCACHE:
                case LOGGING:
                case NOCOMPRESS:
                case KEEP_DUPLICATES:
                case EXCEPTIONS:
                case PURGE:
                case INITIALLY:
                case END:
                case COMMENT:
                case ENABLE:
                case DISABLE:
                case SEQUENCE:
                case USER:
                case ANALYZE:
                case OPTIMIZE:
                case GRANT:
                case REVOKE:
                case FULL:
                case TO:
                case NEW:
                case INTERVAL:
                case LOCK:
                case LIMIT:
                case IDENTIFIED:
                case PASSWORD:
                case BINARY:
                case WINDOW:
                case OFFSET:
                case SHARE:
                case START:
                case CONNECT:
                case MATCHED:
                case ERRORS:
                case REJECT:
                case UNLIMITED:
                case BEGIN:
                case EXCLUSIVE:
                case MODE:
                case ADVISE:
                case TYPE:
                case CLOSE:
                case OPEN:
                    alias = lexer.stringVal();
                    lexer.nextToken();
                    return alias;
                case QUES:
                    alias = "?";
                    lexer.nextToken();
                default:
                    break;
            }
        }
        return alias;
    }

    /**
     * 打印异常信息  该方法不细看
     * @param token
     */
    protected void printError(Token token) {
        String arround;
        if (lexer.mark >= 0 && (lexer.text.length() > lexer.mark + 30)) {
            if (lexer.mark - 5 > 0) {
                arround = lexer.text.substring(lexer.mark - 5, lexer.mark + 30);
            } else {
                arround = lexer.text.substring(lexer.mark, lexer.mark + 30);
            }

        } else if (lexer.mark >= 0) {
            if (lexer.mark - 5 > 0) {
                arround = lexer.text.substring(lexer.mark - 5);
            } else {
                arround = lexer.text.substring(lexer.mark);
            }
        } else {
            arround = lexer.text;
        }

        // throw new
        // ParserException("syntax error, error arround:'"+arround+"',expect "
        // + token + ", actual " + lexer.token + " "
        // + lexer.stringVal() + ", pos " + this.lexer.pos());
        throw new ParserException("syntax error, error in :'" + arround + "', expect " + token + ", actual "
                                  + lexer.token + " " + lexer.info());
    }

    /**
     * 接受某个token
     * @param token
     */
    public void accept(Token token) {
        // 当token 匹配的情况往下走
        if (lexer.token == token) {
            lexer.nextToken();
        } else {
            setErrorEndPos(lexer.pos());
            printError(token);
        }
    }

    /**
     * 接受某个int
     * @return
     */
    public int acceptInteger() {
        // 首先确保当前解析出来的token 是int 类型 返回int 并解析下一个token
        if (lexer.token == Token.LITERAL_INT) {
            int intVal = ((Integer) lexer.integerValue()).intValue();
            lexer.nextToken();
            return intVal;
        } else {
            throw new ParserException("syntax error, expect int, actual " + lexer.token + " "
                    + lexer.info());
        }
    }

    /**
     * 判断当前token 是否匹配传入的token 不匹配抛出异常
     * @param token
     */
    public void match(Token token) {
        if (lexer.token != token) {
            throw new ParserException("syntax error, expect " + token + ", actual " + lexer.token + " "
                                      + lexer.info());
        }
    }

    /**
     * 解析发生异常的指针
     */
    private int errorEndPos = -1;

    /**
     * 设置异常信息
     * @param errPos  代表出现异常的光标
     */
    protected void setErrorEndPos(int errPos) {
        // 这里是更新异常光标 (取最大的???)
        if (errPos > errorEndPos) {
            errorEndPos = errPos;
        }
    }

    /**
     * @param feature
     * @param state  代表追加特性还是去除特性
     */
    public void config(SQLParserFeature feature, boolean state) {
        this.lexer.config(feature, state);
    }

    /**
     * 当前词法解析器是否含有某个特性
     * @param feature
     * @return
     */
    public final boolean isEnabled(SQLParserFeature feature) {
        return lexer.isEnabled(feature);
    }

    /**
     * 创建一个会话对象
     * @return
     */
    protected SQLCreateTableStatement newCreateStatement() {
        return new SQLCreateTableStatement(getDbType());
    }
}
