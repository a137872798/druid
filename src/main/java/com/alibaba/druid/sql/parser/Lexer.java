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

import static com.alibaba.druid.sql.parser.CharTypes.isFirstIdentifierChar;
import static com.alibaba.druid.sql.parser.CharTypes.isIdentifierChar;
import static com.alibaba.druid.sql.parser.CharTypes.isWhitespace;
import static com.alibaba.druid.sql.parser.LayoutCharacters.EOI;
import static com.alibaba.druid.sql.parser.SQLParserFeature.KeepComments;
import static com.alibaba.druid.sql.parser.SQLParserFeature.OptimizedForParameterized;
import static com.alibaba.druid.sql.parser.SQLParserFeature.SkipComments;
import static com.alibaba.druid.sql.parser.Token.COLONCOLON;
import static com.alibaba.druid.sql.parser.Token.COLONEQ;
import static com.alibaba.druid.sql.parser.Token.COMMA;
import static com.alibaba.druid.sql.parser.Token.DOT;
import static com.alibaba.druid.sql.parser.Token.EOF;
import static com.alibaba.druid.sql.parser.Token.EQ;
import static com.alibaba.druid.sql.parser.Token.ERROR;
import static com.alibaba.druid.sql.parser.Token.LBRACE;
import static com.alibaba.druid.sql.parser.Token.LBRACKET;
import static com.alibaba.druid.sql.parser.Token.LITERAL_ALIAS;
import static com.alibaba.druid.sql.parser.Token.LITERAL_CHARS;
import static com.alibaba.druid.sql.parser.Token.LPAREN;
import static com.alibaba.druid.sql.parser.Token.RBRACE;
import static com.alibaba.druid.sql.parser.Token.RBRACKET;
import static com.alibaba.druid.sql.parser.Token.RPAREN;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.alibaba.druid.sql.ast.expr.SQLNumberExpr;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlLexer;
import com.alibaba.druid.util.FnvHash;
import com.alibaba.druid.util.JdbcConstants;
import com.alibaba.druid.util.StringUtils;

/**
 * 词法解析器
 * @author wenshao [szujobs@hotmail.com]
 */
public class Lexer {

    /**
     * 该词法解析器 包含一个 全局范围的 符号表 (符号表内部是将一些字符包装成 entry后的数组)
     */
    protected static SymbolTable symbols_l2 = new SymbolTable(512);

    /**
     * 该词法解析器的 特性
     */
    protected int          features       = 0; //SQLParserFeature.of(SQLParserFeature.EnableSQLBinaryOpExprGroup);

    /**
     * 被解析的文本对象
     */
    public    final String text;
    /**
     * 当前指针
     */
    protected int          pos;
    /**
     * 标记 应该是用于记录某个 下标 便于之后还原的
     */
    protected int          mark;

    /**
     * 当前指针对应的 char
     */
    protected char         ch;

    /**
     * 一组字符
     */
    protected char[]       buf;
    /**
     * 该字符数组的下标
     */
    protected int          bufPos;

    /**
     * db 对应的一些原语  比如 SELECT  DELETE INSERT 等
     */
    protected Token        token;

    /**
     * 对应一个 map  比如  key: SELECT   value: Token.SELECT (枚举对象)
     */
    protected Keywords     keywords        = Keywords.DEFAULT_KEYWORDS;

    protected String       stringVal;
    /**
     * hash 低位数值
     */
    protected long         hash_lower; // fnv1a_64
    protected long         hash;

    /**
     * 当前文本中的注释数量
     */
    protected int            commentCount = 0;
    /**
     * 如果需要保存注释话 保存在该容器中
     */
    protected List<String>   comments     = null;
    /**
     * 是否要跳过评论
     */
    protected boolean        skipComment  = true;
    /**
     * 保存点对象
     */
    private SavePoint        savePoint    = null;

    /*
     * anti sql injection   不允许注释内容 是为了防止sql 注入
     */
    private boolean          allowComment = true;
    /**
     * 参数下标从-1 开始
     */
    private int              varIndex     = -1;
    /**
     * 注释处理器  传入一个非注释的 token 以及注释文本内容
     */
    protected CommentHandler commentHandler;
    /**
     * 是否到评论末尾
     */
    protected boolean        endOfComment = false;
    /**
     * 是否保留注释
     */
    protected boolean        keepComments = false;
    protected int            line         = 0;
    protected int            lines        = 0;
    /**
     * db 类型
     */
    protected String         dbType;

    /**
     * 是否优化参数化
     */
    protected boolean        optimizedForParameterized = false;

    /**
     * 起始点
     */
    private int startPos;
    private int posLine;
    private int posColumn;

    /**
     * 输入一串字符 进行词法解析 默认没有携带 评论处理器
     * @param input
     */
    public Lexer(String input){
        this(input, null);
    }
    
    public Lexer(String input, CommentHandler commentHandler){
        // 默认支持跳过评论
        this(input, true);
        this.commentHandler = commentHandler;
    }

    /**
     * 通过sql语句生成 词法解析器对象
     * @param input
     * @param commentHandler
     * @param dbType
     */
    public Lexer(String input, CommentHandler commentHandler, String dbType){
        this(input, true);
        this.commentHandler = commentHandler;
        this.dbType = dbType;

        // 如果db类型是 sqlite 就更改对应的关键字  默认情况 keywords 是DEFAULT_KEYWORDS
        if (JdbcConstants.SQLITE.equals(dbType)) {
            this.keywords = Keywords.SQLITE_KEYWORDS;
        }
    }

    /**
     * 默认情况是跳过评论的
     * @param input
     * @param skipComment
     */
    public Lexer(String input, boolean skipComment){
        this.skipComment = skipComment;

        this.text = input;
        // 起始下标为0
        this.pos = 0;
        // 起始下标对应的 字符
        ch = charAt(pos);
    }

    /**
     * 从 char[] 中抽取 字符串来初始化
     * @param input
     * @param inputLength
     * @param skipComment
     */
    public Lexer(char[] input, int inputLength, boolean skipComment){
        this(new String(input, 0, inputLength), skipComment);
    }

    public boolean isKeepComments() {
        return keepComments;
    }
    
    public void setKeepComments(boolean keepComments) {
        this.keepComments = keepComments;
    }

    public CommentHandler getCommentHandler() {
        return commentHandler;
    }

    public void setCommentHandler(CommentHandler commentHandler) {
        this.commentHandler = commentHandler;
    }

    /**
     * 获取下标对应的字符
     * @param index
     * @return
     */
    public final char charAt(int index) {
        if (index >= text.length()) {
            return EOI;
        }

        // 获取对应的字符
        return text.charAt(index);
    }

    public final String addSymbol() {
        // 截取部分字符串
        return subString(mark, bufPos);
    }

    public final String subString(int offset, int count) {
        return text.substring(offset, offset + count);
    }

    public final char[] sub_chars(int offset, int count) {
        char[] chars = new char[count];
        // 将 text 截取部分 char字符 并存放到数组中
        text.getChars(offset, offset + count, chars, 0);
        return chars;
    }

    /**
     * 初始化 buffer
     * @param size
     */
    protected void initBuff(int size) {
        if (buf == null) {
            if (size < 32) {
                buf = new char[32];
            } else {
                buf = new char[size + 32];
            }
            // 需要扩容
        } else if (buf.length < size) {
            // 该方法 会创建一个新的大小为size的数组 再将数据复制到新数组后返回
            buf = Arrays.copyOf(buf, size);
        }
    }

    /**
     * 从 text中指定偏移量 并将结果设置到 dest 数组中
     * @param srcPos
     * @param dest
     * @param destPos
     * @param length
     */
    public void arraycopy(int srcPos, char[] dest, int destPos, int length) {
        text.getChars(srcPos, srcPos + length, dest, destPos);
    }

    public boolean isAllowComment() {
        return allowComment;
    }

    public void setAllowComment(boolean allowComment) {
        this.allowComment = allowComment;
    }

    public int nextVarIndex() {
        return ++varIndex;
    }

    /**
     * 保存点
     */
    public static class SavePoint {
        int   bp;
        int   sp;
        int   np;
        /**
         * 保存点对应的 字符
         */
        char  ch;
        /**
         * 该保存点对应的 hash值
         */
        long hash;
        /**
         * 该保存点对应的 hash低位值
         */
        long hash_lower;
        /**
         * 保存点一定能定位到某个token吗???
         */
        public Token token;
        String stringVal;
    }

    /**
     * 获取本词法解析器的全部关键字
     * @return
     */
    public Keywords getkeywords() {
        return keywords;
    }

    /**
     * 标记当前位置
     * @return
     */
    public SavePoint mark() {
        SavePoint savePoint = new SavePoint();
        savePoint.bp = pos;
        savePoint.sp = bufPos;
        savePoint.np = mark;
        savePoint.ch = ch;
        savePoint.token = token;
        savePoint.stringVal = stringVal;
        savePoint.hash = hash;
        savePoint.hash_lower = hash_lower;
        return this.savePoint = savePoint;
    }

    /**
     * 回到保存点  这里可以指定point 对象
     * @param savePoint
     */
    public void reset(SavePoint savePoint) {
        this.pos = savePoint.bp;
        this.bufPos = savePoint.sp;
        this.mark = savePoint.np;
        this.ch = savePoint.ch;
        this.token = savePoint.token;
        this.stringVal = savePoint.stringVal;
        this.hash = savePoint.hash;
        this.hash_lower = savePoint.hash_lower;
    }

    public void reset() {
        this.reset(this.savePoint);
    }

    public void reset(int pos) {
        this.pos = pos;
        this.ch = charAt(pos);
    }


    /**
     * 往后扫描一个 char
     */
    protected final void scanChar() {
        ch = charAt(++pos);
    }

    /**
     * 回退一个 char
     */
    protected void unscan() {
        ch = charAt(--pos);
    }

    /**
     * 判断是否读取到末尾
     * @return
     */
    public boolean isEOF() {
        return pos >= text.length();
    }

    /**
     * Report an error at the given position using the provided arguments.
     * 这里没有使用到传入的参数
     */
    protected void lexError(String key, Object... args) {
        token = ERROR;
    }

    /**
     * Return the current token, set by nextToken().
     * 返回当前token
     */
    public final Token token() {
        return token;
    }

    public final String getDbType() {
        return this.dbType;
    }

    /**
     * 生成info 信息
     * @return
     */
    public String info() {
        int line = 1;
        int column = 1;
        // 从文本开始到起始点的位置   每进一位 就代表 到了下一列
        // 只是初始化 lexer 对象 startPos 不会被设置 需要配合其他方法
        for (int i = 0; i < startPos; ++i, column++) {
            char ch = text.charAt(i);
            // 遇到换行符  将当前列重置成1 同时 行数+1
            if (ch == '\n') {
                column = 1;
                line++;
            }
        }

        // 设置当前 pos 所在的行列
        this.posLine = line;
        this.posColumn = column;

        // 打印当前行列信息
        StringBuilder buf = new StringBuilder();
        buf
                .append("pos ")
                .append(pos)
                .append(", line ")
                .append(line)
                .append(", column ")
                .append(column)
                .append(", token ")
                .append(token);

        if (token == Token.IDENTIFIER || token == Token.LITERAL_ALIAS || token == Token.LITERAL_CHARS) {
            buf.append(" ").append(stringVal);
        }

        return buf.toString();
    }

    /**
     * 获取下一个token
     */
    public final void nextTokenComma() {

        // 这里就是罗列各种匹配的可能性 如果是token 设置后退出方法 否则就往下扫描

        // 如果当前字符是 空字符 更新指针 和 ch 到下一个字符
        if (ch == ' ') {
            scanChar();
        }

        // 如果当前字符是 逗号
        if (ch == ',' || ch == '，') {
            // 更新字符 和指针 同时代表找到了token
            scanChar();
            // COMMA 代表 逗号
            token = COMMA;
            return;
        }

        // 如果是右括号
        if (ch == ')' || ch == '）') {
            scanChar();
            token = RPAREN;
            return;
        }

        // 如果是点
        if (ch == '.') {
            scanChar();
            token = DOT;
            return;
        }

        // 如果同时匹配了 AS
        if (ch == 'a' || ch == 'A') {
            char ch_next = charAt(pos + 1);
            if (ch_next == 's' || ch_next == 'S') {
                // 这里确保S 后面没有其他字符
                char ch_next_2 = charAt(pos + 2);
                if (ch_next_2 == ' ') {
                    pos += 2;
                    // 前进2格后 字符为 " "
                    ch = ' ';
                    token = Token.AS;
                    stringVal = "AS";
                    return;
                }
            }
        }

        // 其余情况会继续往下匹配   也就是一次可能会出现的所有结果在这里做了拆分
        nextToken();
    }

    public final void nextTokenCommaValue() {
        if (ch == ' ') {
            scanChar();
        }

        if (ch == ',' || ch == '，') {
            scanChar();
            token = COMMA;
            return;
        }

        if (ch == ')' || ch == '）') {
            scanChar();
            // 代表右括弧
            token = RPAREN;
            return;
        }

        if (ch == '.') {
            scanChar();
            token = DOT;
            return;
        }

        if (ch == 'a' || ch == 'A') {
            char ch_next = charAt(pos + 1);
            if (ch_next == 's' || ch_next == 'S') {
                char ch_next_2 = charAt(pos + 2);
                if (ch_next_2 == ' ') {
                    pos += 2;
                    ch = ' ';
                    token = Token.AS;
                    stringVal = "AS";
                    return;
                }
            }
        }

        nextTokenValue();
    }

    public final void nextTokenEq() {
        if (ch == ' ') {
            scanChar();
        }

        if (ch == '=') {
            scanChar();
            token = EQ;
            return;
        }

        if (ch == '.') {
            scanChar();
            token = DOT;
            return;
        }

        if (ch == 'a' || ch == 'A') {
            char ch_next = charAt(pos + 1);
            if (ch_next == 's' || ch_next == 'S') {
                char ch_next_2 = charAt(pos + 2);
                if (ch_next_2 == ' ') {
                    pos += 2;
                    ch = ' ';
                    token = Token.AS;
                    stringVal = "AS";
                    return;
                }
            }
        }

        nextToken();
    }

    public final void nextTokenLParen() {
        if (ch == ' ') {
            scanChar();
        }

        if (ch == '(' || ch == '（') {
            scanChar();
            token = LPAREN;
            return;
        }
        nextToken();
    }

    public final void nextTokenValue() {
        this.startPos = pos;
        if (ch == ' ') {
            scanChar();
        }

        if (ch == '\'') {
            bufPos = 0;
            scanString();
            return;
        }

        if (ch == '"') {
            bufPos = 0;
            scanString2_d();
            return;
        }

        if (ch == '0') {
            bufPos = 0;
            if (charAt(pos + 1) == 'x') {
                scanChar();
                scanChar();
                scanHexaDecimal();
            } else {
                scanNumber();
            }
            return;
        }

        if (ch > '0' && ch <= '9') {
            bufPos = 0;
            scanNumber();
            return;
        }

        if (ch == '?') {
            scanChar();
            token = Token.QUES;
            return;
        }

        if (ch == 'n' || ch == 'N') {
            char c1 = 0, c2, c3, c4;
            if (pos + 4 < text.length()
                    && ((c1 = text.charAt(pos + 1)) == 'u' || c1 == 'U')
                    && ((c2 = text.charAt(pos + 2)) == 'l' || c2 == 'L')
                    && ((c3 = text.charAt(pos + 3)) == 'l' || c3 == 'L')
                    && (isWhitespace(c4 = text.charAt(pos + 4)) || c4 == ',' || c4 == ')')) {
                pos += 4;
                ch = c4;
                token = Token.NULL;
                stringVal = "NULL";
                return;
            }

            if (c1 == '\'') {
                bufPos = 0;
                ++pos;
                ch = '\'';
                scanString();
                token = Token.LITERAL_NCHARS;
                return;
            }
        }

        if (ch == ')') {
            scanChar();
            token = Token.RPAREN;
            return;
        }

        if (isFirstIdentifierChar(ch)) {
            scanIdentifier();
            return;
        }

        nextToken();
    }

    public final void nextTokenBy() {
        while (ch == ' ') {
            scanChar();
        }

        if (ch == 'b' || ch == 'B') {
            char ch_next = charAt(pos + 1);
            if (ch_next == 'y' || ch_next == 'Y') {
                char ch_next_2 = charAt(pos + 2);
                if (ch_next_2 == ' ') {
                    pos += 2;
                    ch = ' ';
                    token = Token.BY;
                    stringVal = "BY";
                    return;
                }
            }
        }

        nextToken();
    }

    public final void nextTokenNotOrNull() {
        while (ch == ' ') {
            scanChar();
        }


        if ((ch == 'n' || ch == 'N') && pos + 3 < text.length()) {
            char c1 = text.charAt(pos + 1);
            char c2 = text.charAt(pos + 2);
            char c3 = text.charAt(pos + 3);

            if ((c1 == 'o' || c1 == 'O')
                    && (c2 == 't' || c2 == 'T')
                    && isWhitespace(c3)) {
                pos += 3;
                ch = c3;
                token = Token.NOT;
                stringVal = "NOT";
                return;
            }

            char c4;
            if (pos + 4 < text.length()
                    && (c1 == 'u' || c1 == 'U')
                    && (c2 == 'l' || c2 == 'L')
                    && (c3 == 'l' || c3 == 'L')
                    && isWhitespace(c4 = text.charAt(pos + 4))) {
                pos += 4;
                ch = c4;
                token = Token.NULL;
                stringVal = "NULL";
                return;
            }
        }

        nextToken();
    }

    public final void nextTokenIdent() {
        while (ch == ' ') {
            scanChar();
        }

        if (isFirstIdentifierChar(ch)) {
            scanIdentifier();
            return;
        }

        if (ch == ')') {
            scanChar();
            token = RPAREN;
            return;
        }

        nextToken();
    }

    /**
     * 继续匹配其他token  其他token 可能都是多字符了 就需要一种能够快捷匹配的方式 所以该方法被独立出来
     */
    public final void nextToken() {
        startPos = pos;
        bufPos = 0;
        // 先清空评论
        if (comments != null && comments.size() > 0) {
            comments = null;
        }


        this.lines = 0;
        // 获取起始行
        int startLine = line;
        
        for (;;) {
            // 判断该字符是否是空格  或者 '\n' 或是 无意义的字符
            if (isWhitespace(ch)) {
                if (ch == '\n') {
                    // 遇到换行符 增加行数
                    line++;
                    // 代表获取下个token时经过了多少行
                    lines = line - startLine;
                }
                // " " 或者换行符 对于token 都没有意义 所以进入下次循环
                ch = charAt(++pos);
                continue;
            }

            // 如果是 ${ 代表下个数据是一个参数  注意 如果发现了${  而没有发现 }  会抛出解析错误
            if (ch == '$' && charAt(pos + 1) == '{') {
                scanVariable();
                return;
            }

            // 代表不是 逗号和空格 的其他字符
            if (isFirstIdentifierChar(ch)) {
                // 如果是 (  扫描下一个字符
                if (ch == '（') {
                    scanChar();
                    // 代表左括弧
                    token = LPAREN;
                    return;
                // 右括弧
                } else if (ch == '）') {
                    scanChar();
                    token = RPAREN;
                    return;
                }

                // 如果当前字符是 n  且下一个是 \'  这种情况先不考虑
                if (ch == 'N' || ch == 'n') {
                    if (charAt(pos + 1) == '\'') {
                        ++pos;
                        ch = '\'';
                        // 扫描string
                        scanString();
                        token = Token.LITERAL_NCHARS;
                        return;
                    }
                }

                // 这里暂时还没明白是想解析什么样的格式
                scanIdentifier();
                return;
            }

            // 其余情况
            switch (ch) {
                // 如果是 16进制数 继续往下扫描
                case '0':
                    if (charAt(pos + 1) == 'x') {
                        scanChar();
                        scanChar();
                        // 扫描16进制
                        scanHexaDecimal();
                    } else {
                        // 扫描普通的数字
                        scanNumber();
                    }
                    return;
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    scanNumber();
                    return;
                // 遇到逗号继续往下
                case ',':
                case '，':
                    scanChar();
                    token = COMMA;
                    return;
                case '(':
                case '（':
                    scanChar();
                    token = LPAREN;
                    return;
                case ')':
                case '）':
                    scanChar();
                    token = RPAREN;
                    return;
                // 扫描中括号
                case '[':
                    // 就是往下 扫描一位 同时修改 token 为 LBRACKET
                    scanLBracket();
                    return;
                case ']':
                    scanChar();
                    token = RBRACKET;
                    return;
                case '{':
                    scanChar();
                    token = LBRACE;
                    return;
                case '}':
                    scanChar();
                    token = RBRACE;
                    return;
                    // 遇到冒号的情况
                case ':':
                    scanChar();
                    // 如果下一个是等号 继续往下
                    if (ch == '=') {
                        scanChar();
                        token = COLONEQ;
                        // 代表2个冒号
                    } else if (ch == ':') {
                        scanChar();
                        token = COLONCOLON;
                    } else {
                        // 回退一位
                        unscan();
                        // 扫描占位符
                        scanVariable();
                    }
                    return;
                case '#':
                    // 在本类中等同于scanVar
                    scanSharp();
                    // 这里应该是 某个db 相关的 lexer特殊实现
                    if ((token == Token.LINE_COMMENT || token == Token.MULTI_LINE_COMMENT) && skipComment) {
                        bufPos = 0;
                        continue;
                    }
                    return;
                case '.':
                    // 遇到 . 时往下扫描
                    scanChar();
                    // 如果是数字  且 前面也是数字
                    if (isDigit(ch) && !isFirstIdentifierChar(charAt(pos - 2))) {
                        unscan();
                        // 扫描数字
                        scanNumber();
                        return;
                    } else if (ch == '.') {
                        scanChar();
                        if (ch == '.') {
                            scanChar();
                            // 代表扫描到3个点
                            token = Token.DOTDOTDOT;
                        } else {
                            // 扫描到2个点
                            token = Token.DOTDOT;
                        }
                    } else {
                        token = Token.DOT;
                    }
                    return;
                case '\'':
                    scanString();
                    return;
                case '\"':
                    scanAlias();
                    return;
                // 往下前进一步
                case '*':
                    scanChar();
                    token = Token.STAR;
                    return;
                case '?':
                    scanChar();
                    // POSTGRESQL 相关的先忽略
                    if (ch == '?' && JdbcConstants.POSTGRESQL.equals(dbType)) {
                        scanChar();
                        if (ch == '|') {
                            scanChar();
                            token = Token.QUESBAR;
                        } else {
                            token = Token.QUESQUES;
                        }
                    } else if (ch == '|' && JdbcConstants.POSTGRESQL.equals(dbType)) {
                        scanChar();
                        if (ch == '|') {
                            unscan();
                            token = Token.QUES;
                        } else {
                            token = Token.QUESBAR;
                        }
                    } else if (ch == '&' && JdbcConstants.POSTGRESQL.equals(dbType)) {
                        scanChar();
                        token = Token.QUESAMP;
                    } else {
                        // 设置成 QUES
                        token = Token.QUES;
                    }
                    return;
                case ';':
                    scanChar();
                    token = Token.SEMI;
                    return;
                // ` 的情况应该在上面处理过了
                case '`':
                    throw new ParserException("TODO. " + info()); // TODO
                case '@':
                    // 扫描变量下标
                    scanVariable_at();
                    return;
                case '-':
                    // -- 连续2个 - 代表出现了评论
                    if (charAt(pos +1) == '-') {
                        // 单行注释
                        scanComment();
                        // 如果需要跳过注释  忽略这段文本
                        if ((token == Token.LINE_COMMENT || token == Token.MULTI_LINE_COMMENT) && skipComment) {
                            bufPos = 0;
                            continue;
                        }
                    } else {
                        // 解析操作符
                        scanOperator();
                    }
                    return;
                // 扫描注释
                case '/':
                    int nextChar = charAt(pos + 1);
                    if (nextChar == '/' || nextChar == '*') {
                        scanComment();
                        if ((token == Token.LINE_COMMENT || token == Token.MULTI_LINE_COMMENT) && skipComment) {
                            bufPos = 0;
                            continue;
                        }
                    } else {
                        token = Token.SLASH;
                        scanChar();
                    }
                    return;
                default:
                    // 如果是字母的话
                    if (Character.isLetter(ch)) {
                        scanIdentifier();
                        return;
                    }

                    // 如果是操作符
                    if (isOperator(ch)) {
                        scanOperator();
                        return;
                    }

                    // 代表null
                    if (ch == '\\' && charAt(pos + 1) == 'N'
                            && JdbcConstants.MYSQL.equals(dbType)) {
                        scanChar();
                        scanChar();
                        token = Token.NULL;
                        return;
                    }

                    // QS_TODO ?
                    if (isEOF()) { // JLS
                        token = EOF;
                    } else {
                        lexError("illegal.char", String.valueOf((int) ch));
                        scanChar();
                    }

                    return;
            }
        }

    }

    protected void scanLBracket() {
        scanChar();
        token = LBRACKET;
    }

    /**
     * 扫描操作符
     */
    private final void scanOperator() {
        switch (ch) {
            case '+':
                scanChar();
                token = Token.PLUS;
                break;
                // -    ->     ->>
            case '-':
                scanChar();
                if (ch == '>') {
                    scanChar();
                    if (ch == '>') {
                        scanChar();
                        token = Token.SUBGTGT;
                    } else {
                        token = Token.SUBGT;
                    }
                } else {
                    token = Token.SUB;    
                }
                break;
            case '*':
                scanChar();
                token = Token.STAR;
                break;
            case '/':
                scanChar();
                token = Token.SLASH;
                break;
            case '&':
                scanChar();
                if (ch == '&') {
                    scanChar();
                    token = Token.AMPAMP;
                } else {
                    token = Token.AMP;
                }
                break;
            case '|':
                scanChar();
                if (ch == '|') {
                    scanChar();
                    if (ch == '/') {
                        scanChar();
                        token = Token.BARBARSLASH; 
                    } else {
                        token = Token.BARBAR;
                    }
                } else if (ch == '/') {
                    scanChar();
                    token = Token.BARSLASH;
                } else {
                    token = Token.BAR;
                }
                break;
            case '^':
                scanChar();
                if (ch == '=') {
                    scanChar();
                    token = Token.CARETEQ;
                } else {
                    token = Token.CARET;
                }
                break;
            case '%':
                scanChar();
                token = Token.PERCENT;
                break;
            case '=':
                scanChar();
                if (ch == '=') {
                    scanChar();
                    token = Token.EQEQ;
                } else if (ch == '>') {
                    scanChar();
                    token = Token.EQGT;
                } else {
                    token = Token.EQ;
                }
                break;
            case '>':
                scanChar();
                if (ch == '=') {
                    scanChar();
                    token = Token.GTEQ;
                } else if (ch == '>') {
                    scanChar();
                    token = Token.GTGT;
                } else {
                    token = Token.GT;
                }
                break;
            case '<':
                scanChar();
                if (ch == '=') {
                    scanChar();
                    if (ch == '>') {
                        token = Token.LTEQGT;
                        scanChar();
                    } else {
                        token = Token.LTEQ;
                    }
                } else if (ch == '>') {
                    scanChar();
                    token = Token.LTGT;
                } else if (ch == '<') {
                    scanChar();
                    token = Token.LTLT;
                } else if (ch == '@') {
                    scanChar();
                    token = Token.LT_MONKEYS_AT;
                } else if (ch == '-' && charAt(pos + 1) == '>') {
                    scanChar();
                    scanChar();
                    token = Token.LT_SUB_GT;
                } else {
                    if (ch == ' ') {
                        char c1 = charAt(pos + 1);
                        if (c1 == '=') {
                            scanChar();
                            scanChar();
                            if (ch == '>') {
                                token = Token.LTEQGT;
                                scanChar();
                            } else {
                                token = Token.LTEQ;
                            }
                        } else if (c1 == '>') {
                            scanChar();
                            scanChar();
                            token = Token.LTGT;
                        } else if (c1 == '<') {
                            scanChar();
                            scanChar();
                            token = Token.LTLT;
                        } else if (c1 == '@') {
                            scanChar();
                            scanChar();
                            token = Token.LT_MONKEYS_AT;
                        } else if (c1 == '-' && charAt(pos + 2) == '>') {
                            scanChar();
                            scanChar();
                            scanChar();
                            token = Token.LT_SUB_GT;
                        } else {
                            token = Token.LT;
                        }
                    } else {
                        token = Token.LT;
                    }
                }
                break;
            case '!':
                scanChar();
                while (isWhitespace(ch)) {
                    scanChar();
                }
                if (ch == '=') {
                    scanChar();
                    token = Token.BANGEQ;
                } else if (ch == '>') {
                    scanChar();
                    token = Token.BANGGT;
                } else if (ch == '<') {
                    scanChar();
                    token = Token.BANGLT;
                } else if (ch == '!') {
                    scanChar();
                    token = Token.BANGBANG; // postsql
                } else if (ch == '~') {
                    scanChar();
                    if (ch == '*') {
                        scanChar();
                        token = Token.BANG_TILDE_STAR; // postsql
                    } else {
                        token = Token.BANG_TILDE; // postsql
                    }
                } else {
                    token = Token.BANG;
                }
                break;
            case '?':
                scanChar();
                token = Token.QUES;
                break;
            case '~':
                scanChar();
                if (ch == '*') {
                    scanChar();
                    token = Token.TILDE_STAR;
                } else if (ch == '=') {
                    scanChar();
                    token = Token.TILDE_EQ; // postsql
                } else {
                    token = Token.TILDE;
                }
                break;
            default:
                throw new ParserException("TODO. " + info());
        }
    }

    /**
     * 扫描字符串
     */
    protected void scanString() {
        mark = pos;
        boolean hasSpecial = false;
        // 记录上一个token
        Token preToken = this.token;

        for (;;) {
            // 如果已经扫描到了末尾
            if (isEOF()) {
                // 将token 设置成 ERROR
                lexError("unclosed.str.lit");
                return;
            }

            // 扫描下一个字符
            ch = charAt(++pos);

            if (ch == '\'') {
                // 扫描下一个字符
                scanChar();
                if (ch != '\'') {
                    // 代表扫描到 '\''
                    token = LITERAL_CHARS;
                    break;
                } else {
                    if (!hasSpecial) {
                        // 按照指定大小构建buffer  或者 扩建一个buffer
                        initBuff(bufPos);
                        // 从mark 开始到复制 bufPos 的长度到buffer 中
                        arraycopy(mark + 1, buf, 0, bufPos);
                        hasSpecial = true;
                    }
                    putChar('\'');
                    continue;
                }
            }

            // 确保此时 buffer 还没有被创建 在没有扫描到 \ 的情况下 往前进
            if (!hasSpecial) {
                bufPos++;
                continue;
            }

            if (bufPos == buf.length) {
                putChar(ch);
            } else {
                buf[bufPos++] = ch;
            }
        }

        if (!hasSpecial) {
            if (preToken == Token.AS) {
                stringVal = subString(mark, bufPos + 2);
            } else {
                stringVal = subString(mark + 1, bufPos);
            }
        } else {
            stringVal = new String(buf, 0, bufPos);
        }
    }
    
    protected final void scanString2() {
        {
            boolean hasSpecial = false;
            int startIndex = pos + 1;
            int endIndex = -1; // text.indexOf('\'', startIndex);
            for (int i = startIndex; i < text.length(); ++i) {
                final char ch = text.charAt(i);
                if (ch == '\\') {
                    hasSpecial = true;
                    continue;
                }
                if (ch == '\'') {
                    endIndex = i;
                    break;
                }
            }

            if (endIndex == -1) {
                throw new ParserException("unclosed str. " + info());
            }

            String stringVal;
            if (token == Token.AS) {
                stringVal = subString(pos, endIndex + 1 - pos);
            } else {
                stringVal = subString(startIndex, endIndex - startIndex);
            }
            // hasSpecial = stringVal.indexOf('\\') != -1;

            if (!hasSpecial) {
                this.stringVal = stringVal;
                int pos = endIndex + 1;
                char ch = charAt(pos);
                if (ch != '\'') {
                    this.pos = pos;
                    this.ch = ch;
                    token = LITERAL_CHARS;
                    return;
                }
            }
        }

        mark = pos;
        boolean hasSpecial = false;
        for (;;) {
            if (isEOF()) {
                lexError("unclosed.str.lit");
                return;
            }

            ch = charAt(++pos);

            if (ch == '\\') {
                scanChar();
                if (!hasSpecial) {
                    initBuff(bufPos);
                    arraycopy(mark + 1, buf, 0, bufPos);
                    hasSpecial = true;
                }

                switch (ch) {
                    case '0':
                        putChar('\0');
                        break;
                    case '\'':
                        putChar('\'');
                        break;
                    case '"':
                        putChar('"');
                        break;
                    case 'b':
                        putChar('\b');
                        break;
                    case 'n':
                        putChar('\n');
                        break;
                    case 'r':
                        putChar('\r');
                        break;
                    case 't':
                        putChar('\t');
                        break;
                    case '\\':
                        putChar('\\');
                        break;
                    case '_':
                        if(JdbcConstants.MYSQL.equals(dbType)) {
                            putChar('\\');
                        }
                        putChar('_');
                        break;
                    case 'Z':
                        putChar((char) 0x1A); // ctrl + Z
                        break;
                    case '%':
                        putChar('\\');
                        putChar(ch);
                        break;
                    default:
                        putChar(ch);
                        break;
                }

                continue;
            }
            if (ch == '\'') {
                scanChar();
                if (ch != '\'') {
                    token = LITERAL_CHARS;
                    break;
                } else {
                    if (!hasSpecial) {
                        initBuff(bufPos);
                        arraycopy(mark + 1, buf, 0, bufPos);
                        hasSpecial = true;
                    }
                    putChar('\'');
                    continue;
                }
            }

            if (!hasSpecial) {
                bufPos++;
                continue;
            }

            if (bufPos == buf.length) {
                putChar(ch);
            } else {
                buf[bufPos++] = ch;
            }
        }

        if (!hasSpecial) {
            stringVal = subString(mark + 1, bufPos);
        } else {
            stringVal = new String(buf, 0, bufPos);
        }
    }

    protected final void scanString2_d() {
        {
            boolean hasSpecial = false;
            int startIndex = pos + 1;
            int endIndex = -1; // text.indexOf('\'', startIndex);
            for (int i = startIndex; i < text.length(); ++i) {
                final char ch = text.charAt(i);
                if (ch == '\\') {
                    hasSpecial = true;
                    continue;
                }
                if (ch == '"') {
                    endIndex = i;
                    break;
                }
            }

            if (endIndex == -1) {
                throw new ParserException("unclosed str. " + info());
            }

            String stringVal;
            if (token == Token.AS) {
                stringVal = subString(pos, endIndex + 1 - pos);
            } else {
                stringVal = subString(startIndex, endIndex - startIndex);
            }
            // hasSpecial = stringVal.indexOf('\\') != -1;

            if (!hasSpecial) {
                this.stringVal = stringVal;
                int pos = endIndex + 1;
                char ch = charAt(pos);
                if (ch != '\'') {
                    this.pos = pos;
                    this.ch = ch;
                    token = LITERAL_CHARS;
                    return;
                }
            }
        }

        mark = pos;
        boolean hasSpecial = false;
        for (;;) {
            if (isEOF()) {
                lexError("unclosed.str.lit");
                return;
            }

            ch = charAt(++pos);

            if (ch == '\\') {
                scanChar();
                if (!hasSpecial) {
                    initBuff(bufPos);
                    arraycopy(mark + 1, buf, 0, bufPos);
                    hasSpecial = true;
                }


                switch (ch) {
                    case '0':
                        putChar('\0');
                        break;
                    case '\'':
                        putChar('\'');
                        break;
                    case '"':
                        putChar('"');
                        break;
                    case 'b':
                        putChar('\b');
                        break;
                    case 'n':
                        putChar('\n');
                        break;
                    case 'r':
                        putChar('\r');
                        break;
                    case 't':
                        putChar('\t');
                        break;
                    case '\\':
                        putChar('\\');
                        break;
                    case 'Z':
                        putChar((char) 0x1A); // ctrl + Z
                        break;
                    case '%':
                        if(JdbcConstants.MYSQL.equals(dbType)) {
                            putChar('\\');
                        }
                        putChar('%');
                        break;
                    case '_':
                        if(JdbcConstants.MYSQL.equals(dbType)) {
                            putChar('\\');
                        }
                        putChar('_');
                        break;
                    default:
                        putChar(ch);
                        break;
                }

                continue;
            }
            if (ch == '"') {
                scanChar();
                if (ch != '"') {
                    token = LITERAL_CHARS;
                    break;
                } else {
                    if (!hasSpecial) {
                        initBuff(bufPos);
                        arraycopy(mark + 1, buf, 0, bufPos);
                        hasSpecial = true;
                    }
                    putChar('"');
                    continue;
                }
            }

            if (!hasSpecial) {
                bufPos++;
                continue;
            }

            if (bufPos == buf.length) {
                putChar(ch);
            } else {
                buf[bufPos++] = ch;
            }
        }

        if (!hasSpecial) {
            stringVal = subString(mark + 1, bufPos);
        } else {
            stringVal = new String(buf, 0, bufPos);
        }
    }

    /**
     * 扫描别名  这里不太明白 等debug时再理解
     */
    protected final void scanAlias() {
        final char quote = ch;
        {
            boolean hasSpecial = false;
            int startIndex = pos + 1;
            int endIndex = -1; // text.indexOf('\'', startIndex);
            // 注意这个循环体中 pos 始终没有变化 因为拉取数据 要通过pos
            for (int i = startIndex; i < text.length(); ++i) {
                final char ch = text.charAt(i);
                // 从起点位置出发 找到 \\
                if (ch == '\\') {
                    hasSpecial = true;
                    continue;
                }
                // 代表往后扫描 遇到了相同的 字符
                if (ch == quote) {
                    if (i + 1 < text.length()) {
                        char ch_next = charAt(i + 1);
                        // 在此基础上又扫描到相同的字符 标识hasSpecial = true
                        if (ch_next == quote) {
                            hasSpecial = true;
                            i++;
                            continue;
                        }
                    }

                    // 这里代表没有找到连续2个相同的字符 就直接退出了
                    endIndex = i;
                    break;
                }
            }

            if (endIndex == -1) {
                throw new ParserException("unclosed str. " + info());
            }

            // 截取上面确定的字符
            String stringVal = subString(pos, endIndex + 1 - pos);
            // hasSpecial = stringVal.indexOf('\\') != -1;

            // 没有出现过连续2个相同的字符
            if (!hasSpecial) {
                this.stringVal = stringVal;
                int pos = endIndex + 1;
                char ch = charAt(pos);
                if (ch != '\'') {
                    this.pos = pos;
                    this.ch = ch;
                    token = LITERAL_ALIAS;
                    return;
                }
            }
        }

        // 代表出现了2个连续相同的字符

        mark = pos;
        // 初始化buffer
        initBuff(bufPos);
        //putChar(ch);

        // 将当前字符 添加到buffer 中
        putChar(ch);
        for (;;) {
            if (isEOF()) {
                lexError("unclosed.str.lit");
                return;
            }

            // 上面的操作都没有修改 pos 也就是进入到这里时 前2个数据是重复的
            ch = charAt(++pos);

            // 如果 \\ 开头  (当遇到\\ 开头时 即使下面的数据不是重复的 也会进入到这里)
            if (ch == '\\') {
                scanChar();

                switch (ch) {
                    case '0':
                        // 0 只补了一个 \
                        putChar('\0');
                        break;
                    case '\'':
                        // 代表出现了相同的情况
                        if (ch == quote) {
                            putChar('\\');
                        }
                        putChar('\'');
                        break;
                    case '"':
                        if (ch == quote) {
                            putChar('\\');
                        }
                        putChar('"');
                        break;
                    // 这里是增加转义符吗???
                    case 'b':
                        putChar('\b');
                        break;
                    case 'n':
                        putChar('\n');
                        break;
                    case 'r':
                        putChar('\r');
                        break;
                    case 't':
                        putChar('\t');
                        break;
                    case '\\':
                        putChar('\\');
                        putChar('\\');
                        break;
                    case 'Z':
                        putChar((char) 0x1A); // ctrl + Z
                        break;
                    default:
                        putChar(ch);
                        break;
                }

                continue;
            }

            // 没有 \\ 开头的情况
            if (ch == quote) {
                char ch_next = charAt(pos + 1);

                if (ch_next == quote) {
                    putChar('\\');
                    putChar(ch);
                    scanChar();
                    continue;
                }

                putChar(ch);
                scanChar();
                token = LITERAL_ALIAS;
                break;
            }

            if (bufPos == buf.length) {
                putChar(ch);
            } else {
                buf[bufPos++] = ch;
            }
        }

        stringVal = new String(buf, 0, bufPos);
    }
    
    public void scanSharp() {
        scanVariable();
    }

    /**
     * 当扫描文本一些自定义参数后进入该方法
     */
    public void scanVariable() {
        // 确保是一下几种情况
        if (ch != ':' && ch != '#' && ch != '$') {
            throw new ParserException("illegal variable. " + info());
        }

        // 记录当前指针
        mark = pos;
        // bufPos 代表 一个占位符的 指针  比如长度为 ${size}
        bufPos = 1;
        char ch;

        // 获取下个字符
        final char c1 = charAt(pos + 1);
        // 这种db 先不看
        if (c1 == '>' && JdbcConstants.POSTGRESQL.equalsIgnoreCase(dbType)) {
            pos += 2;
            token = Token.MONKEYS_AT_GT;
            this.ch = charAt(++pos);
            return;
            // 对应 ${    #{  的情况
        } else if (c1 == '{') {
            pos++;
            bufPos++;

            for (;;) {
                ch = charAt(++pos);

                // 直到扫描到末尾 退出循环
                if (ch == '}') {
                    break;
                }

                // 每扫描一个字符 下标+1
                bufPos++;
                continue;
            }

            // 这里做一下校验
            if (ch != '}') {
                throw new ParserException("syntax error. " + info());
            }
            ++pos;
            bufPos++;

            // 获取 } 后面的字符
            this.ch = charAt(pos);

            // 从mark 开始 截取 bufferPos 的长度 也就是 截取 ${size}
            stringVal = addSymbol();
            // 代表是一个变量
            token = Token.VARIANT;
            return;
        }

        for (;;) {
            ch = charAt(++pos);

            if (!isIdentifierChar(ch)) {
                break;
            }

            bufPos++;
            continue;
        }

        this.ch = charAt(pos);

        stringVal = addSymbol();
        token = Token.VARIANT;
    }

    /**
     * 扫描变量的下标
     */
    protected void scanVariable_at() {
        // 必须以 @ 开头
        if (ch != '@') {
            throw new ParserException("illegal variable. " + info());
        }

        mark = pos;
        bufPos = 1;
        char ch;

        final char c1 = charAt(pos + 1);
        // 允许出现 @@
        if (c1 == '@') {
            ++pos;
            bufPos++;
        }

        for (;;) {
            ch = charAt(++pos);

            if (!isIdentifierChar(ch)) {
                break;
            }

            bufPos++;
            continue;
        }

        this.ch = charAt(pos);

        stringVal = addSymbol();
        token = Token.VARIANT;
    }

    /**
     * 扫描评论  这个评论应该是注释的意思 吧
     */
    public void scanComment() {
        if (!allowComment) {
            throw new NotAllowCommentException();
        }

        //       //  或者 --
        if ((ch == '/' && charAt(pos + 1) == '/')
                || (ch == '-' && charAt(pos + 1) == '-')) {
            // 扫描单行注释
            scanSingleLineComment();
        } else if (ch == '/' && charAt(pos + 1) == '*') {
            scanMultiLineComment();
        } else {
            throw new IllegalStateException();
        }
    }

    private void scanMultiLineComment() {
        Token lastToken = this.token;
        
        scanChar();
        scanChar();
        mark = pos;
        bufPos = 0;

        // 多行注释 就不需要检查换行符  而是检查  */
        for (;;) {
            if (ch == '*' && charAt(pos + 1) == '/') {
                scanChar();
                scanChar();
                break;
            }
            
			// multiline comment结束符错误
			if (ch == EOI) {
				throw new ParserException("unterminated /* comment. " + info());
			}
            scanChar();
            bufPos++;
        }

        // 基本同 scanSingleLineComment

        stringVal = subString(mark, bufPos);
        token = Token.MULTI_LINE_COMMENT;
        commentCount++;
        if (keepComments) {
            addComment(stringVal);
        }

        // 使用注释处理器 去处理注释内容
        if (commentHandler != null && commentHandler.handle(lastToken, stringVal)) {
            return;
        }

        // 如果不允许注释 内容 或者该注释内容不安全 抛出异常
        if (!isAllowComment() && !isSafeComment(stringVal)) {
            throw new NotAllowCommentException();
        }
    }

    /**
     * 扫描单行注释
     */
    private void scanSingleLineComment() {
        Token lastToken = this.token;
        
        scanChar();
        scanChar();
        mark = pos;
        bufPos = 0;

        for (;;) {
            // 当没有遇到 换行符时不断往下扫描

            // 回车符
            if (ch == '\r') {
                // 当遇到 \r\n 时 增加一行
                if (charAt(pos + 1) == '\n') {
                    line++;
                    // 扫描一个后退出
                    scanChar();
                    break;
                }
                bufPos++;
                break;
            }

            // 换行符
            if (ch == '\n') {
                line++;
                scanChar();
                break;
            }
            
			// single line comment结束符错误
            // 解析失败
			if (ch == EOI) {
				throw new ParserException("syntax error at end of input. " + info());
			}

            scanChar();
            bufPos++;
        }

        // 获取注释的内容
        stringVal = subString(mark, bufPos);
        token = Token.LINE_COMMENT;
        // 注释的行数增加
        commentCount++;
        // 如果需要记录注释内容
        if (keepComments) {
            addComment(stringVal);
        }

        // 如果存在注释处理器 将上一个token 以及注释内容传入
        if (commentHandler != null && commentHandler.handle(lastToken, stringVal)) {
            return;
        }
        
        if (!isAllowComment() && !isSafeComment(stringVal)) {
            throw new NotAllowCommentException();
        }
    }

    /**
     * 扫描 标识符
     */
    public void scanIdentifier() {
        this.hash_lower = 0;
        this.hash = 0;

        // 获取当前扫描的字符
        final char first = ch;

        if (ch == '`') {
            // 标记当前位置 以及重置 bufferPos
            mark = pos;
            bufPos = 1;
            char ch;

            int startPos = pos + 1;
            // 寻找下一个 "`"
            int quoteIndex = text.indexOf('`', startPos);
            // 没有找到匹配的另一半 抛出异常
            if (quoteIndex == -1) {
                throw new ParserException("illegal identifier. " + info());
            }

            hash_lower = 0xcbf29ce484222325L;
            hash = 0xcbf29ce484222325L;

            // 扫描2个 "`" 间的数据
            for (int i = startPos; i < quoteIndex; ++i) {
                ch = text.charAt(i);

                // 计算hash值
                hash_lower ^= ((ch >= 'A' && ch <= 'Z') ? (ch + 32) : ch);
                hash_lower *= 0x100000001b3L;

                hash ^= ch;
                hash *= 0x100000001b3L;
            }

            // 将 2个"`"之间的数据插入到 符号表中
            stringVal = MySqlLexer.quoteTable.addSymbol(text, pos, quoteIndex + 1 - pos, hash);
            //stringVal = text.substring(mark, pos);
            pos = quoteIndex + 1;
            // 指向下一个 字符
            this.ch = charAt(pos);
            // 这里更新了token  这里不断的改变 token 可以却没有地方记录 是不是有什么地方做处理了
            token = Token.IDENTIFIER;
            return;
        }

        // 非 " " 和 ","  的其他字符
        final boolean firstFlag = isFirstIdentifierChar(first);
        if (!firstFlag) {
            throw new ParserException("illegal identifier. " + info());
        }

        hash_lower = 0xcbf29ce484222325L;
        hash = 0xcbf29ce484222325L;

        hash_lower ^= ((ch >= 'A' && ch <= 'Z') ? (ch + 32) : ch);
        hash_lower *= 0x100000001b3L;

        hash ^= ch;
        hash *= 0x100000001b3L;

        mark = pos;
        bufPos = 1;
        char ch;
        for (;;) {
            ch = charAt(++pos);

            // 当发现 " " "," ")" 后返回false
            if (!isIdentifierChar(ch)) {
                break;
            }

            hash_lower ^= ((ch >= 'A' && ch <= 'Z') ? (ch + 32) : ch);
            hash_lower *= 0x100000001b3L;

            hash ^= ch;
            hash *= 0x100000001b3L;

            bufPos++;
            continue;
        }

        this.ch = charAt(pos);

        // 代表直接退出了
        if (bufPos == 1) {
            token = Token.IDENTIFIER;
            stringVal = CharTypes.valueOf(first);
            // 如果没有在缓存中找到 就调用toString
            if (stringVal == null) {
                stringVal = Character.toString(first);
            }
            return;
        }

        // 通过hash 找到对应的token 因为计算hash的方式是一致的所以可以匹配到
        Token tok = keywords.getKeyword(hash_lower);
        if (tok != null) {
            token = tok;
            // 如果是 IDENTIFIER 添加到符号表里
            if (token == Token.IDENTIFIER) {
                stringVal = SymbolTable.global.addSymbol(text, mark, bufPos, hash);
            } else {
                stringVal = null;
            }
        } else {
            // 没有找到token 默认也是使用 这个  啥意思???
            token = Token.IDENTIFIER;
            stringVal = SymbolTable.global.addSymbol(text, mark, bufPos, hash);
        }
    }

    /**
     * 扫描数字
     */
    public void scanNumber() {
        mark = pos;

        // 如果是负数继续往后扫描
        if (ch == '-') {
            bufPos++;
            ch = charAt(++pos);
        }

        for (;;) {
            // 当数字在 0 和 9 之间 增加bufPos
            if (ch >= '0' && ch <= '9') {
                bufPos++;
            } else {
                break;
            }
            // 每扫描到一个字符 就更新 ch
            ch = charAt(++pos);
        }

        // 判断是否是浮点数
        boolean isDouble = false;

        // 比如 9.3 那么 扫描完9 后会退出循环
        if (ch == '.') {
            // 代表连续出现2个 '.'
            if (charAt(pos + 1) == '.') {
                // 退出循环
                token = Token.LITERAL_INT;
                return;
            }
            bufPos++;
            ch = charAt(++pos);
            isDouble = true;

            // 确定是浮点数后 重新开始扫描
            for (;;) {
                if (ch >= '0' && ch <= '9') {
                    bufPos++;
                } else {
                    break;
                }
                ch = charAt(++pos);
            }
        }

        // 如果是科学计数法
        if (ch == 'e' || ch == 'E') {
            bufPos++;
            ch = charAt(++pos);

            if (ch == '+' || ch == '-') {
                bufPos++;
                ch = charAt(++pos);
            }

            for (;;) {
                if (ch >= '0' && ch <= '9') {
                    bufPos++;
                } else {
                    break;
                }
                ch = charAt(++pos);
            }

            isDouble = true;
        }

        // 返回浮点数 或者 整数
        if (isDouble) {
            token = Token.LITERAL_FLOAT;
        } else {
            token = Token.LITERAL_INT;
        }
    }

    /**
     * 扫描16进制
     */
    public void scanHexaDecimal() {
        mark = pos;

        // 如果是 负数 往下扫描  比如 -0x123...
        if (ch == '-') {
            bufPos++;
            ch = charAt(++pos);
        }

        for (;;) {
            // 如果当前字符是16进制数 继续往下
            if (CharTypes.isHex(ch)) {
                bufPos++;
            } else {
                break;
            }
            ch = charAt(++pos);
        }

        // 当前扫描到16进制数
        token = Token.LITERAL_HEX;
    }

    public String hexString() {
        return subString(mark, bufPos);
    }

    public final boolean isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    /**
     * Append a character to sbuf.
     */
    protected final void putChar(char ch) {
        if (bufPos == buf.length) {
            char[] newsbuf = new char[buf.length * 2];
            System.arraycopy(buf, 0, newsbuf, 0, buf.length);
            buf = newsbuf;
        }
        buf[bufPos++] = ch;
    }

    /**
     * Return the current token's position: a 0-based offset from beginning of the raw input stream (before unicode
     * translation)
     */
    public final int pos() {
        return pos;
    }

    /**
     * The value of a literal token, recorded as a string. For integers, leading 0x and 'l' suffixes are suppressed.
     */
    public final String stringVal() {
        if (stringVal == null) {
            stringVal = subString(mark, bufPos);
        }
        return stringVal;
    }

    private final void stringVal(StringBuffer out) {
        if (stringVal != null) {
            out.append(stringVal);
            return;
        }

        out.append(text, mark, mark + bufPos);
    }

    /**
     * 当前解析到的位置 所对应的数据Token是 IDENTIFIER 且数据与传入数据一致
     * @param text
     * @return
     */
    public final boolean identifierEquals(String text) {
        if (token != Token.IDENTIFIER) {
            return false;
        }

        if (stringVal == null) {
            stringVal = subString(mark, bufPos);
        }
        return text.equalsIgnoreCase(stringVal);
    }

    public final boolean identifierEquals(long hash_lower) {
        if (token != Token.IDENTIFIER) {
            return false;
        }

        if (this.hash_lower == 0) {
            if (stringVal == null) {
                stringVal = subString(mark, bufPos);
            }
            this.hash_lower = FnvHash.fnv1a_64_lower(stringVal);
        }
        return this.hash_lower == hash_lower;
    }

    public final long hash_lower() {
        if (this.hash_lower == 0) {
            if (stringVal == null) {
                stringVal = subString(mark, bufPos);
            }
            this.hash_lower = FnvHash.fnv1a_64_lower(stringVal);
        }
        return hash_lower;
    }
    
    public final List<String> readAndResetComments() {
        List<String> comments = this.comments;
        
        this.comments = null;
        
        return comments;
    }

    private boolean isOperator(char ch) {
        switch (ch) {
            case '!':
            case '%':
            case '&':
            case '*':
            case '+':
            case '-':
            case '<':
            case '=':
            case '>':
            case '^':
            case '|':
            case '~':
            case ';':
                return true;
            default:
                return false;
        }
    }

    private static final long  MULTMIN_RADIX_TEN   = Long.MIN_VALUE / 10;
    private static final long  N_MULTMAX_RADIX_TEN = -Long.MAX_VALUE / 10;

    private final static int[] digits              = new int[(int) '9' + 1];

    static {
        for (int i = '0'; i <= '9'; ++i) {
            digits[i] = i - '0';
        }
    }

    // QS_TODO negative number is invisible for lexer
    public Number integerValue() {
        long result = 0;
        boolean negative = false;
        int i = mark, max = mark + bufPos;
        long limit;
        long multmin;
        int digit;

        if (charAt(mark) == '-') {
            negative = true;
            limit = Long.MIN_VALUE;
            i++;
        } else {
            limit = -Long.MAX_VALUE;
        }
        multmin = negative ? MULTMIN_RADIX_TEN : N_MULTMAX_RADIX_TEN;
        if (i < max) {
            digit = charAt(i++) - '0';
            result = -digit;
        }
        while (i < max) {
            // Accumulating negatively avoids surprises near MAX_VALUE
            digit = charAt(i++) - '0';
            if (result < multmin) {
                return new BigInteger(numberString());
            }
            result *= 10;
            if (result < limit + digit) {
                return new BigInteger(numberString());
            }
            result -= digit;
        }

        if (negative) {
            if (i > mark + 1) {
                if (result >= Integer.MIN_VALUE) {
                    return (int) result;
                }
                return result;
            } else { /* Only got "-" */
                throw new NumberFormatException(numberString());
            }
        } else {
            result = -result;
            if (result <= Integer.MAX_VALUE) {
                return (int) result;
            }
            return result;
        }
    }

    public int bp() {
        return this.pos;
    }

    public char current() {
        return this.ch;
    }

    public void reset(int mark, char markChar, Token token) {
        this.pos = mark;
        this.ch = markChar;
        this.token = token;
    }

    public final String numberString() {
        return subString(mark, bufPos);
    }

    public BigDecimal decimalValue() {
        char[] value = sub_chars(mark, bufPos);
        if (!StringUtils.isNumber(value)){
            throw new ParserException(value+" is not a number! " + info());
        }
        return new BigDecimal(value);
    }

    public SQLNumberExpr numberExpr() {
        char[] value = sub_chars(mark, bufPos);
        if (!StringUtils.isNumber(value)){
            throw new ParserException(value+" is not a number! " + info());
        }

        return new SQLNumberExpr(value);
    }

    public SQLNumberExpr numberExpr(boolean negate) {
        char[] value = sub_chars(mark, bufPos);
        if (!StringUtils.isNumber(value)){
            throw new ParserException(value+" is not a number! " + info());
        }

        if (negate) {
            char[] chars = new char[value.length + 1];
            chars[0] = '-';
            System.arraycopy(value, 0, chars, 1, value.length);
            return new SQLNumberExpr(chars);
        } else {
            return new SQLNumberExpr(value);
        }
    }

    /**
     * 注释处理器
     */
    public interface CommentHandler {
        boolean handle(Token lastToken, String comment);
    }

    public boolean hasComment() {
        return comments != null;
    }

    public int getCommentCount() {
        return commentCount;
    }
    
    public void skipToEOF() {
        pos = text.length();
        this.token = Token.EOF;
    }

    public boolean isEndOfComment() {
        return endOfComment;
    }

    /**
     * 该注释内容是否安全  如果包含了sql关键字等 就认为是不安全的注释内容
     * @param comment
     * @return
     */
    protected boolean isSafeComment(String comment) {
        if (comment == null) {
            return true;
        }
        comment = comment.toLowerCase();
        if (comment.indexOf("select") != -1 //
            || comment.indexOf("delete") != -1 //
            || comment.indexOf("insert") != -1 //
            || comment.indexOf("update") != -1 //
            || comment.indexOf("into") != -1 //
            || comment.indexOf("where") != -1 //
            || comment.indexOf("or") != -1 //
            || comment.indexOf("and") != -1 //
            || comment.indexOf("union") != -1 //
            || comment.indexOf('\'') != -1 //
            || comment.indexOf('=') != -1 //
            || comment.indexOf('>') != -1 //
            || comment.indexOf('<') != -1 //
            || comment.indexOf('&') != -1 //
            || comment.indexOf('|') != -1 //
            || comment.indexOf('^') != -1 //
        ) {
            return false;
        }
        return true;
    }

    /**
     * 保存注释信息
     * @param comment
     */
    protected void addComment(String comment) {
        if (comments == null) {
            comments = new ArrayList<String>(2);
        }
        comments.add(stringVal);
    }
    
    public int getLine() {
        return line;
    }

    public void computeRowAndColumn() {
        int line = 1;
        int column = 1;
        for (int i = 0; i < pos; ++i) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                column = 1;
                line++;
            }
        }

        this.posLine = line;
        this.posColumn = posColumn;
    }

    public int getPosLine() {
        return posLine;
    }

    public int getPosColumn() {
        return posColumn;
    }

    /**
     * 为 lexer 配置某种特性
     * @param feature
     * @param state  代表是要追加特性还是去除特性
     */
    public void config(SQLParserFeature feature, boolean state) {
        features = SQLParserFeature.config(features, feature, state);

        if (feature == OptimizedForParameterized) {
            optimizedForParameterized = state;
        } else if (feature == KeepComments) {
            this.keepComments = state;
        } else if (feature == SkipComments) {
            this.skipComment = state;
        }
    }

    public final boolean isEnabled(SQLParserFeature feature) {
        return SQLParserFeature.isEnabled(this.features, feature);
    }

    public static String parameterize(String sql, String dbType) {
        Lexer lexer = SQLParserUtils.createLexer(sql, dbType);
        lexer.optimizedForParameterized = true; // optimized

        lexer.nextToken();

        StringBuffer buf = new StringBuffer();

        for_:
        for (;;) {
            Token token = lexer.token;
            switch (token) {
                case LITERAL_ALIAS:
                case LITERAL_FLOAT:
                case LITERAL_CHARS:
                case LITERAL_INT:
                case LITERAL_NCHARS:
                case LITERAL_HEX:
                case VARIANT:
                    if (buf.length() != 0) {
                        buf.append(' ');
                    }
                    buf.append('?');
                    break;
                case COMMA:
                    buf.append(',');
                    break;
                case EQ:
                    buf.append('=');
                    break;
                case EOF:
                    break for_;
                case ERROR:
                    return sql;
                case SELECT:
                    buf.append("SELECT");
                    break;
                case UPDATE:
                    buf.append("UPDATE");
                    break;
                default:
                    if (buf.length() != 0) {
                        buf.append(' ');
                    }
                    lexer.stringVal(buf);
                    break;
            }

            lexer.nextToken();
        }

        return buf.toString();
    }

    public String getSource() {
        return text;
    }
}
