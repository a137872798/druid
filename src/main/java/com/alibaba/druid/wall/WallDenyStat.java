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

import java.util.Date;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * 统计 否定相关信息
 */
public class WallDenyStat {

    /**
     * 否定次数
     */
    private volatile long                             denyCount;

    /**
     * 上一次否定时间
     */
    private volatile long                             lastDenyTimeMillis;

    /**
     * 重置次数
     */
    private volatile long                             resetCount;

    final static AtomicLongFieldUpdater<WallDenyStat> denyCountUpdater  = AtomicLongFieldUpdater.newUpdater(WallDenyStat.class,
                                                                                                            "denyCount");

    final static AtomicLongFieldUpdater<WallDenyStat> resetCountUpdater = AtomicLongFieldUpdater.newUpdater(WallDenyStat.class,
                                                                                                            "resetCount");

    /**
     * 增加否定次数
     * @return
     */
    public long incrementAndGetDenyCount() {
        lastDenyTimeMillis = System.currentTimeMillis();
        return denyCountUpdater.incrementAndGet(this);
    }

    public long getDenyCount() {
        return denyCount;
    }

    public Date getLastDenyTime() {
        if (lastDenyTimeMillis <= 0) {
            return null;
        }
        return new Date(lastDenyTimeMillis);
    }

    /**
     * 重置denyCount 同时 增加 resetCount
     */
    public void reset() {
        lastDenyTimeMillis = 0;
        denyCount = 0;
        resetCountUpdater.incrementAndGet(this);
    }

    public long getResetCount() {
        return this.resetCount;
    }

}
