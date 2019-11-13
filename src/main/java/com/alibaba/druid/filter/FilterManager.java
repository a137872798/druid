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
package com.alibaba.druid.filter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.JdbcUtils;
import com.alibaba.druid.util.Utils;

/**
 * 过滤器管理器  实际上是一个 SPI 加载器
 */
public class FilterManager {

    private final static Log                               LOG      = LogFactory.getLog(FilterManager.class);

    /**
     * 通过一个并发容器来管理所有filter
     * 比如  default,com.alibaba.druid.filter.stat.StatFilter
     *       slf4j,com.alibaba.druid.filter.logging.Slf4jLogFilter
     */
    private static final ConcurrentHashMap<String, String> aliasMap = new ConcurrentHashMap<String, String>(16, 0.75f, 1);

    static {
        try {
            // 加载 key:value 的关系
            Properties filterProperties = loadFilterConfig();
            for (Map.Entry<Object, Object> entry : filterProperties.entrySet()) {
                String key = (String) entry.getKey();
                if (key.startsWith("druid.filters.")) {
                    String name = key.substring("druid.filters.".length());
                    aliasMap.put(name, (String) entry.getValue());
                }
            }
        } catch (Throwable e) {
            LOG.error("load filter config error", e);
        }
    }

    /**
     * 通过拦截器别名找到对应类的全限定名
     * @param alias
     * @return
     */
    public static final String getFilter(String alias) {
        if (alias == null) {
            return null;
        }

        String filter = aliasMap.get(alias);

        if (filter == null && alias.length() < 128) {
            filter = alias;
        }

        return filter;
    }

    /**
     * 加载过滤器的相关属性
     * @return
     * @throws IOException
     */
    public static Properties loadFilterConfig() throws IOException {
        Properties filterProperties = new Properties();

        // 尝试使用各种加载器 去加载配置信息  类加载器这块还需要理解下
        loadFilterConfig(filterProperties, ClassLoader.getSystemClassLoader());
        loadFilterConfig(filterProperties, FilterManager.class.getClassLoader());
        loadFilterConfig(filterProperties, Thread.currentThread().getContextClassLoader());
        loadFilterConfig(filterProperties, FilterManager.class.getClassLoader());

        return filterProperties;
    }

    /**
     * 加载 druid 拦截器相关属性
     * @param filterProperties  抽取出来的属性会保存到 prop 中
     * @param classLoader  用于读取属性的类加载器
     * @throws IOException
     */
    private static void loadFilterConfig(Properties filterProperties, ClassLoader classLoader) throws IOException {
        if (classLoader == null) {
            return;
        }
        
        for (Enumeration<URL> e = classLoader.getResources("META-INF/druid-filter.properties"); e.hasMoreElements();) {
            URL url = e.nextElement();

            Properties property = new Properties();

            InputStream is = null;
            try {
                is = url.openStream();
                property.load(is);
            } finally {
                JdbcUtils.close(is);
            }

            filterProperties.putAll(property);
        }
    }

    /**
     * 首先通过filterName 找到对应的拦截器 之后添加到 filters 中
     * @param filters
     * @param filterName  该name 可能是 别名 也可能是全限定名
     * @throws SQLException
     */
    public static void loadFilter(List<Filter> filters, String filterName) throws SQLException {
        if (filterName.length() == 0) {
            return;
        }

        // 找到对应filter 的全限定名
        String filterClassNames = getFilter(filterName);

        // 代表是别名 并且找到了 实现类名
        if (filterClassNames != null) {
            // 可能有些拦截器 别名对应多个类
            for (String filterClassName : filterClassNames.split(",")) {
                // 如果在这组拦截器中已经存在了 目标拦截器 就跳过
                if (existsFilter(filters, filterClassName)) {
                    continue;
                }

                Class<?> filterClass = Utils.loadClass(filterClassName);

                if (filterClass == null) {
                    LOG.error("load filter error, filter not found : " + filterClassName);
                    continue;
                }

                Filter filter;

                try {
                    filter = (Filter) filterClass.newInstance();
                } catch (ClassCastException e) {
                    LOG.error("load filter error.", e);
                    continue;
                } catch (InstantiationException e) {
                    throw new SQLException("load managed jdbc driver event listener error. " + filterName, e);
                } catch (IllegalAccessException e) {
                    throw new SQLException("load managed jdbc driver event listener error. " + filterName, e);
                } catch (RuntimeException e) {
                    throw new SQLException("load managed jdbc driver event listener error. " + filterName, e);
                }

                filters.add(filter);
            }

            return;
        }

        // 进入这里代表  filterName 本身就是全限定名 已经存在就不处理 否则初始化并添加到list 中
        if (existsFilter(filters, filterName)) {
            return;
        }

        Class<?> filterClass = Utils.loadClass(filterName);
        if (filterClass == null) {
            LOG.error("load filter error, filter not found : " + filterName);
            return;
        }

        try {
            Filter filter = (Filter) filterClass.newInstance();
            filters.add(filter);
        } catch (Exception e) {
            throw new SQLException("load managed jdbc driver event listener error. " + filterName, e);
        }
    }

    /**
     * 从一组 过滤器中 判断是否有包含指定的过滤器
     * @param filterList
     * @param filterClassName
     * @return
     */
    private static boolean existsFilter(List<Filter> filterList, String filterClassName) {
        for (Filter filter : filterList) {
            // 获取全限定名
            String itemFilterClassName = filter.getClass().getName();
            // 名字匹配返回true
            if (itemFilterClassName.equalsIgnoreCase(filterClassName)) {
                return true;
            }
        }
        return false;
    }
}
