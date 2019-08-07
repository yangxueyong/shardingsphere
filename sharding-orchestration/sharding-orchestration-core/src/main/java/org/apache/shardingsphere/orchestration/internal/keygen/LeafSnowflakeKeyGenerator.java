/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.orchestration.internal.keygen;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.shardingsphere.orchestration.internal.registry.RegistryCenterServiceLoader;
import org.apache.shardingsphere.orchestration.reg.api.RegistryCenter;
import org.apache.shardingsphere.orchestration.reg.api.RegistryCenterConfiguration;
import org.apache.shardingsphere.spi.keygen.ShardingKeyGenerator;

import java.util.Calendar;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Key generator implemented by leaf snowflake algorithms.
 *
 * @author wangguangyuan
 */
public final class LeafSnowflakeKeyGenerator implements ShardingKeyGenerator {

    public static final long EPOCH;

    private static final long SEQUENCE_BITS = 12L;

    private static final long WORKER_ID_BITS = 10L;

    private static final long SEQUENCE_MASK = (1 << SEQUENCE_BITS) - 1;

    private static final long WORKER_ID_LEFT_SHIFT_BITS = SEQUENCE_BITS;

    private static final long TIMESTAMP_LEFT_SHIFT_BITS = WORKER_ID_LEFT_SHIFT_BITS + WORKER_ID_BITS;

    private static final int MAX_TOLERATE_TIME_DIFFERENCE_MILLISECONDS = 10;

    private static final String SERVICE_ID_REGULAR_PATTERN = "^((?!/).)*$";

    private static final String DEFAULT_NAMESPACE = "leaf_snowflake";

    private static final String DEFAULT_REGISTRY_CENTER = "zookeeper";

    private static final String PARENT_NODE = "/leaf_snowflake";

    private static final String TIME_NODE = "/time";

    private static final String CURRENT_MAX_WORK_ID_NODE = "/current-max-work-id";

    private static final String WORK_ID_NODE = "/work-id";

    private static final String SLANTING_BAR = "/";

    @Setter
    private static TimeService timeService = new TimeService();

    @Getter
    @Setter
    private Properties properties = new Properties();

    private RegistryCenter leafRegistryCenter;

    private byte sequenceOffset;

    private long sequence;

    private long lastMilliseconds;

    private long workId;

    private long lastUpdateTime;

    private long maxTolerateTimeDifference;

    static {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2016, Calendar.NOVEMBER, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        EPOCH = calendar.getTimeInMillis();
    }

    @Override
    public String getType() {
        return "LEAF_SNOWFLAKE";
    }

    @Override
    public synchronized Comparable<?> generateKey() {
        initializeLeafSnowflakeKeyGeneratorIfNeed();
        Comparable<?> result = getKey();
        return result;
    }

    @SneakyThrows
    private void initializeLeafSnowflakeKeyGeneratorIfNeed() {
        if (needToBeInitialized()) {
            leafRegistryCenter = initializeRegistryCenter();
            initializeTimeNodeIfNeed();
            initializeCurrentMaxWorkIdNodeIfNeed();
            workId = initializeWorkIdNodeIfNeed();
            maxTolerateTimeDifference = initializeMaxTolerateTimeDifference();
            scheduledUpdateTimeNode();
        }
    }

    private Comparable<?> getKey() {
        long currentMilliseconds = getCurrentMilliseconds();
        long sequence = getSequence(currentMilliseconds);
        Comparable<?> result = getSnowflakeId(currentMilliseconds, sequence);
        updateLastMilliseconds(currentMilliseconds);
        return result;
    }

    @SneakyThrows
    private long getCurrentMilliseconds() {
        long currentMilliseconds = timeService.getCurrentMillis();
        if (lastMilliseconds > currentMilliseconds) {
            long timeDifferenceMilliseconds = lastMilliseconds - currentMilliseconds;
            Preconditions.checkState(timeDifferenceMilliseconds < maxTolerateTimeDifference,
                    "Clock is moving backwards, last time is %d milliseconds, current time is %d milliseconds", lastMilliseconds, currentMilliseconds);
            Thread.sleep(timeDifferenceMilliseconds);
            currentMilliseconds = timeService.getCurrentMillis();
        } else if (lastMilliseconds == currentMilliseconds) {
            if (0L == ((sequence + 1) & SEQUENCE_MASK)) {
                do {
                    currentMilliseconds = timeService.getCurrentMillis();
                } while (currentMilliseconds <= lastMilliseconds);
            }
        }
        return currentMilliseconds;
    }

    private long getSequence(final long currentMilliseconds) {
        if (lastMilliseconds == currentMilliseconds) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
        } else {
            vibrateSequenceOffset();
            sequence = sequenceOffset;
        }
        return sequence;
    }

    private Comparable<?> getSnowflakeId(final long currentMilliseconds, final long sequence) {
        return ((currentMilliseconds - EPOCH) << TIMESTAMP_LEFT_SHIFT_BITS) | (workId << WORKER_ID_LEFT_SHIFT_BITS) | sequence;
    }

    private void updateLastMilliseconds(final long currentMilliseconds) {
        lastMilliseconds = currentMilliseconds;
    }

    private boolean needToBeInitialized() {
        boolean result = null == leafRegistryCenter || workId <= 0;
        return result;
    }

    private RegistryCenter initializeRegistryCenter() {
        RegistryCenterConfiguration leafConfiguration = getRegistryCenterConfiguration();
        RegistryCenter result = new RegistryCenterServiceLoader().load(leafConfiguration);
        return result;
    }

    private String getServiceId() {
        String serviceId = properties.getProperty("serviceId");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(serviceId));
        Preconditions.checkArgument(serviceId.matches(SERVICE_ID_REGULAR_PATTERN));
        String result = SLANTING_BAR + serviceId;
        return result;
    }

    @SneakyThrows
    private void initializeTimeNodeIfNeed() {
        String serviceId = getServiceId();
        if (leafRegistryCenter.isExisted(serviceId + TIME_NODE)) {
            String lastTimeInRegistryCenter = leafRegistryCenter.getDirectly(serviceId + TIME_NODE);
            long currentTime = timeService.getCurrentMillis();
            long timeDifference = currentTime - Long.parseLong(lastTimeInRegistryCenter);
            if (timeDifference < 0) {
                Preconditions.checkState(timeDifference < maxTolerateTimeDifference,
                        "Clock is moving backwards, last time is %d milliseconds, current time is %d milliseconds", lastMilliseconds, currentTime);
                Thread.sleep(timeDifference);
            }
        } else {
            leafRegistryCenter.persist(serviceId + TIME_NODE, String.valueOf(timeService.getCurrentMillis()));
        }
    }

    @SneakyThrows
    private void initializeCurrentMaxWorkIdNodeIfNeed() {
        if (!leafRegistryCenter.isExisted(PARENT_NODE + CURRENT_MAX_WORK_ID_NODE)) {
            leafRegistryCenter.persist(PARENT_NODE + CURRENT_MAX_WORK_ID_NODE, "0");
        }
    }

    @SneakyThrows
    private Long initializeWorkIdNodeIfNeed() {
        String serviceId = getServiceId();
        if (leafRegistryCenter.isExisted(serviceId + WORK_ID_NODE)) {
            String workIdInString = leafRegistryCenter.getDirectly(serviceId + WORK_ID_NODE);
            Long result = Long.parseLong(workIdInString);
            return result;
        } else {
            Long result = updateCurrentMaxWorkIdInRegisterCenter();
            leafRegistryCenter.persist(serviceId + WORK_ID_NODE, String.valueOf(result));
            return result;
        }
    }

    @SneakyThrows
    private long updateCurrentMaxWorkIdInRegisterCenter() {
        leafRegistryCenter.initLock(PARENT_NODE + CURRENT_MAX_WORK_ID_NODE);
        boolean lockIsAcquired = leafRegistryCenter.tryLock();
        Preconditions.checkState(lockIsAcquired, "Try lock fail");
        String id = leafRegistryCenter.getDirectly(PARENT_NODE + CURRENT_MAX_WORK_ID_NODE);
        long result = Long.parseLong(id);
        leafRegistryCenter.persist(PARENT_NODE + CURRENT_MAX_WORK_ID_NODE, String.valueOf(result++));
        leafRegistryCenter.tryRelease();
        return result;
    }

    @SneakyThrows
    private void scheduledUpdateTimeNode() {
        final String serviceId = getServiceId();
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable r) {
                Thread thread = new Thread(r, "schedule-upload-time");
                thread.setDaemon(true);
                return thread;
            }
        }).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                updateNewData(leafRegistryCenter, serviceId + TIME_NODE);
            }
        }, 1L, 3L, TimeUnit.SECONDS);
    }

    @SneakyThrows
    private void updateNewData(final RegistryCenter leafRegistryCenter, final String path) {
        if (System.currentTimeMillis() < lastUpdateTime) {
            return;
        }
        leafRegistryCenter.persist(path, String.valueOf(timeService.getCurrentMillis()));
        lastUpdateTime = System.currentTimeMillis();
    }

    private void vibrateSequenceOffset() {
        sequenceOffset = (byte) (~sequenceOffset & 1);
    }

    private long initializeMaxTolerateTimeDifference() {
        String maxTimeDifference = properties.getProperty("maxTimeDifference", String.valueOf(MAX_TOLERATE_TIME_DIFFERENCE_MILLISECONDS));
        long result = Long.valueOf(maxTimeDifference);
        Preconditions.checkArgument(result >= 0L && result < Long.MAX_VALUE);
        return result;
    }

    private RegistryCenterConfiguration getRegistryCenterConfiguration() {
        RegistryCenterConfiguration result = new RegistryCenterConfiguration(getRegistryCenterType(), properties);
        result.setNamespace(DEFAULT_NAMESPACE);
        result.setServerLists(getServerList());
        return result;
    }

    private String getRegistryCenterType() {
        return properties.getProperty("registryCenterType", DEFAULT_REGISTRY_CENTER);
    }

    private String getServerList() {
        String result = properties.getProperty("serverList");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(result));
        return result;
    }

}
