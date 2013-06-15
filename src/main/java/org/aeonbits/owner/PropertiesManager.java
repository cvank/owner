/*
 * Copyright (c) 2013, Luigi R. Viggiano
 * All rights reserved.
 *
 * This software is distributable under the BSD license.
 * See the terms of the BSD license in the documentation provided with this software.
 */

package org.aeonbits.owner;


import org.aeonbits.owner.Config.HotReload;
import org.aeonbits.owner.Config.LoadPolicy;
import org.aeonbits.owner.Config.Sources;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import static org.aeonbits.owner.Config.LoadType;
import static org.aeonbits.owner.Config.LoadType.FIRST;
import static org.aeonbits.owner.ConfigURLStreamHandler.CLASSPATH_PROTOCOL;
import static org.aeonbits.owner.PropertiesMapper.defaults;
import static org.aeonbits.owner.Util.asString;
import static org.aeonbits.owner.Util.now;
import static org.aeonbits.owner.Util.reverse;
import static org.aeonbits.owner.Util.unsupported;

/**
 * Loads properties and manages access to properties handling concurrency.
 *
 * @author Luigi R. Viggiano
 */
class PropertiesManager implements Reloadable, Accessible, Mutable {
    private static final SystemVariablesExpander expander = new SystemVariablesExpander();
    private final Class<? extends Config> clazz;
    private final Map<?, ?>[] imports;
    private final Properties properties;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReadLock readLock = lock.readLock();
    private final WriteLock writeLock = lock.writeLock();

    private final Sources sources;
    private final LoadType loadType;
    private final HotReload hotReload;
    private final long interval;

    private volatile long lastCheckTime = 0L;
    private long lastLoadTime;
    private volatile boolean loading = false;
    private final ConfigURLStreamHandler handler;

    PropertiesManager(Class<? extends Config> clazz, Properties properties, Map<?, ?>... imports) {
        this.clazz = clazz;
        this.properties = properties;
        this.imports = imports;

        handler = new ConfigURLStreamHandler(clazz.getClassLoader(), expander);

        sources = clazz.getAnnotation(Sources.class);
        LoadPolicy loadPolicy = clazz.getAnnotation(LoadPolicy.class);
        loadType = (loadPolicy != null) ? loadPolicy.value() : FIRST;

        hotReload = clazz.getAnnotation(HotReload.class);
        interval = (hotReload != null) ? hotReload.unit().toMillis(hotReload.value()) : 0;
    }

    Properties load() {
        writeLock.lock();
        try {
            loading = true;
            defaults(properties, clazz);
            merge(properties, reverse(imports));
            Properties loadedFromFile = doLoad(handler);
            merge(properties, loadedFromFile);
            lastLoadTime = now();
            if (lastCheckTime == 0L)
                lastCheckTime = lastLoadTime;
            return properties;
        } catch (IOException e) {
            throw unsupported(e, "Properties load failed");
        } finally {
            loading = false;
            writeLock.unlock();
        }
    }

    public void reload() {
        writeLock.lock();
        try {
            clear();
            load();
        } finally {
            writeLock.unlock();
        }
    }

    Properties doLoad(ConfigURLStreamHandler handler) throws IOException {
        if (sources == null)
            return loadDefaultProperties(handler);
        else
            return loadType.load(sources, handler);
    }

    private Properties loadDefaultProperties(ConfigURLStreamHandler handler) throws IOException {
        String spec = CLASSPATH_PROTOCOL + ":" + clazz.getName().replace('.', '/') + ".properties";
        InputStream inputStream = getInputStream(new URL(null, spec, handler));
        try {
            return properties(inputStream);
        } finally {
            close(inputStream);
        }
    }

    static InputStream getInputStream(URL url) throws IOException {
        URLConnection conn = url.openConnection();
        if (conn == null)
            return null;
        return conn.getInputStream();
    }

    private static void merge(Properties results, Map<?, ?>... inputs) {
        for (Map<?, ?> input : inputs)
            results.putAll(input);
    }

    static Properties properties(InputStream stream) throws IOException {
        Properties props = new Properties();
        if (stream != null)
            props.load(stream);
        return props;
    }

    static void close(InputStream inputStream) throws IOException {
        if (inputStream != null)
            inputStream.close();
    }

    String getProperty(String key) {
        if (syncHotReload())
            checkAndReload();
        readLock.lock();
        try {
            return properties.getProperty(key);
        } finally {
            readLock.unlock();
        }
    }

    private void checkAndReload() {
        if (needsReload())
            reload();
    }

    private boolean syncHotReload() {
        return sources != null && hotReload != null;
    }

    private synchronized boolean needsReload() {
        if (loading) return false;

        long now = now();
        if (now < lastCheckTime + interval)
            return false;

        try {
            return loadType.needsReload(sources, handler, lastLoadTime);
        } finally {
            lastCheckTime = now;
        }
    }

    @Override
    public void list(PrintStream out) {
        readLock.lock();
        try {
            properties.list(out);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void list(PrintWriter out) {
        readLock.lock();
        try {
            properties.list(out);
        } finally {
            readLock.unlock();
        }
    }

    public String setProperty(String key, String value) {
        writeLock.lock();
        try {
            if (value == null) return removeProperty(key);
            return asString(properties.setProperty(key, value));
        } finally {
            writeLock.unlock();
        }
    }

    public String removeProperty(String key) {
        writeLock.lock();
        try {
            return asString(properties.remove(key));
        } finally {
            writeLock.unlock();
        }
    }

    public void clear() {
        writeLock.lock();
        try {
            properties.clear();
        } finally {
            writeLock.unlock();
        }
    }
}
