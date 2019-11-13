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
package com.alibaba.druid.wall;

import static com.alibaba.druid.util.JdbcSqlStatUtils.get;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * 被拦截的sql 的统计信息  如果每次传入的参数不同 那么应该是算作不同的sql吗???
 */
public class WallSqlStat {

    /**
     * 该sql的执行次数
     */
    private volatile long                            executeCount;
    /**
     * 该sql的错误次数
     */
    private volatile long                            executeErrorCount;
    /**
     * 结果行数
     */
    private volatile long                            fetchRowCount;
    /**
     * 更新次数
     */
    private volatile long                            updateCount;

    // 这里字段都通过 CAS 进行更新
    final static AtomicLongFieldUpdater<WallSqlStat> executeCountUpdater      = AtomicLongFieldUpdater.newUpdater(WallSqlStat.class,
                                                                                                                  "executeCount");
    final static AtomicLongFieldUpdater<WallSqlStat> executeErrorCountUpdater = AtomicLongFieldUpdater.newUpdater(WallSqlStat.class,
                                                                                                                  "executeErrorCount");

    final static AtomicLongFieldUpdater<WallSqlStat> fetchRowCountUpdater     = AtomicLongFieldUpdater.newUpdater(WallSqlStat.class,
                                                                                                                  "fetchRowCount");
    final static AtomicLongFieldUpdater<WallSqlStat> updateCountUpdater       = AtomicLongFieldUpdater.newUpdater(WallSqlStat.class,
                                                                                                                  "updateCount");
    /**
     * 以表 和 表为单位的统计信息构成的映射
     */
    private final Map<String, WallSqlTableStat>      tableStats;

    /**
     * 维护的异常信息
     */
    private final List<Violation>                    violations;

    /**
     * 对应的方法名 和调用次数
     */
    private final Map<String, WallSqlFunctionStat>   functionStats;

    /**
     * 是否发生了语法错误
     */
    private final boolean                            syntaxError;

    /**
     * 样本
     */
    private String                                   sample;

    /**
     * 本sql 的hash值
     */
    private long                                     sqlHash;

    /**
     * 初始化 统计对象
     * @param tableStats
     * @param functionStats
     * @param syntaxError
     */
    public WallSqlStat(Map<String, WallSqlTableStat> tableStats, Map<String, WallSqlFunctionStat> functionStats,
                       boolean syntaxError){
        // violations 默认是一个空容器
        this(tableStats, functionStats, Collections.<Violation> emptyList(), syntaxError);
    }

    public WallSqlStat(Map<String, WallSqlTableStat> tableStats, Map<String, WallSqlFunctionStat> functionStats,
                       List<Violation> violations, boolean syntaxError){
        this.violations = violations;
        this.tableStats = tableStats;
        this.functionStats = functionStats;
        this.syntaxError = syntaxError;
    }

    public long getSqlHash() {
        return sqlHash;
    }

    public void setSqlHash(long sqlHash) {
        this.sqlHash = sqlHash;
    }

    public String getSample() {
        return sample;
    }

    public void setSample(String sample) {
        this.sample = sample;
    }

    public long incrementAndGetExecuteCount() {
        return executeCountUpdater.incrementAndGet(this);
    }

    public long incrementAndGetExecuteErrorCount() {
        return executeErrorCountUpdater.incrementAndGet(this);
    }

    public long getExecuteCount() {
        return executeCount;
    }

    public long getExecuteErrorCount() {
        return executeErrorCount;
    }

    public long addAndFetchRowCount(long delta) {
        return fetchRowCountUpdater.addAndGet(this, delta);
    }

    public long getEffectRowCount() {
        return fetchRowCount;
    }

    public long getUpdateCount() {
        return updateCount;
    }

    public void addUpdateCount(long delta) {
        updateCountUpdater.addAndGet(this, delta);
    }

    public Map<String, WallSqlTableStat> getTableStats() {
        return tableStats;
    }

    public Map<String, WallSqlFunctionStat> getFunctionStats() {
        return functionStats;
    }

    public List<Violation> getViolations() {
        return violations;
    }

    public boolean isSyntaxError() {
        return syntaxError;
    }

    // 上面都是一些简单的set/get/increment

    /**
     * 获取统计信息
     * @param reset  是否重置
     * @return
     */
    public WallSqlStatValue getStatValue(boolean reset) {
        final WallSqlStatValue statValue = new WallSqlStatValue();

        // 将数值转移到 statValue 中
        statValue.setExecuteCount(get(this, executeCountUpdater, reset));
        statValue.setExecuteErrorCount(get(this, executeErrorCountUpdater, reset));
        statValue.setFetchRowCount(get(this, fetchRowCountUpdater, reset));
        statValue.setUpdateCount(get(this, updateCountUpdater, reset));
        statValue.setSyntaxError(this.syntaxError);
        statValue.setSqlSample(sample);
        if (violations.size() > 0) {
            // 这里只设置一个 message
            String violationMessage = violations.get(0).getMessage();
            statValue.setViolationMessage(violationMessage);
        }

        return statValue;
    }
}
