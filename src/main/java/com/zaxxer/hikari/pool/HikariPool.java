/*
 * Copyright (C) 2013,2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.pool;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import com.zaxxer.hikari.metrics.dropwizard.CodahaleHealthChecker;
import com.zaxxer.hikari.metrics.dropwizard.CodahaleMetricsTrackerFactory;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import com.zaxxer.hikari.util.ConcurrentBag;
import com.zaxxer.hikari.util.ConcurrentBag.IBagStateListener;
import com.zaxxer.hikari.util.SuspendResumeLock;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static com.zaxxer.hikari.util.ClockSource.*;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_NOT_IN_USE;
import static com.zaxxer.hikari.util.UtilityElf.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * This is the primary connection pool class that provides the basic
 * pooling behavior for HikariCP.
 *
 * @author Brett Wooldridge
 */
public final class HikariPool extends PoolBase implements HikariPoolMXBean, IBagStateListener
{
   /**
    * Logger日志
    */
   private final Logger logger = LoggerFactory.getLogger(HikariPool.class);
   /**
    * 定义HikariPool状态
    * POOL_NORMAL - 正常
    * POOL_SUSPENDED - 挂起
    * POOL_SHUTDOWN - 关闭
    */
   public static final int POOL_NORMAL = 0;
   public static final int POOL_SUSPENDED = 1;
   public static final int POOL_SHUTDOWN = 2;
   /**
    * HikariPool状态
    */
   public volatile int poolState;
   /**
    * 活动间隔时间500ms
    */
   private final long aliveBypassWindowMs = Long.getLong("com.zaxxer.hikari.aliveBypassWindowMs", MILLISECONDS.toMillis(500));
   /**
    * 延迟任务间隔30s
    */
   private final long housekeepingPeriodMs = Long.getLong("com.zaxxer.hikari.housekeeping.periodMs", SECONDS.toMillis(30));
   /**
    * 驱逐提示语
    */
   private static final String EVICTED_CONNECTION_MESSAGE = "(connection was evicted)";
   /**
    * 死亡提示语
    */
   private static final String DEAD_CONNECTION_MESSAGE = "(connection is dead)";
   /**
    * 池连接创建者
    */
   private final PoolEntryCreator poolEntryCreator = new PoolEntryCreator();
   /**
    * 补充池连接创建者
    */
   private final PoolEntryCreator postFillPoolEntryCreator = new PoolEntryCreator("After adding ");
   /**
    * 添加连接队列深度
    */
   private final AtomicInteger addConnectionQueueDepth = new AtomicInteger();
   /**
    * 添加新连接的线程池
    */
   private final ThreadPoolExecutor addConnectionExecutor;
   /**
    * 关闭底层连接的线程池
    */
   private final ThreadPoolExecutor closeConnectionExecutor;
   /**
    * ConcurrentBag并发类
    */
   private final ConcurrentBag<PoolEntry> connectionBag;
   /**
    * 连接泄露告警任务工厂
    */
   private final ProxyLeakTaskFactory leakTaskFactory;
   /**
    * 根据是否允许挂起连接池, 初始化锁
    */
   private final SuspendResumeLock suspendResumeLock;
   /**
    * 延迟线程池(用于执行检测连接泄露、关闭生存时间到期的连接、回收空闲连接、检测时间回拨)
    */
   private final ScheduledExecutorService houseKeepingExecutorService;
   /**
    * 保持最少的空闲连接
    */
   private ScheduledFuture<?> houseKeeperTask;

   /**
    * Construct a HikariPool with the specified configuration.
    *
    * @param config a HikariConfig instance
    */
   public HikariPool(final HikariConfig config)
   {
      //父类构造器
      super(config);
      //构建一个connectionBag用于保存连接, connectionBag是连接池的核心
      this.connectionBag = new ConcurrentBag<>(this);
      //根据是否允许挂起连接池, 初始化锁
      this.suspendResumeLock = config.isAllowPoolSuspension() ? new SuspendResumeLock() : SuspendResumeLock.FAUX_LOCK;
      //延迟线程池(用于执行检测连接泄露、关闭生存时间到期的连接、回收空闲连接、检测时间回拨)
      this.houseKeepingExecutorService = initializeHouseKeepingExecutorService();
      //快速失败
      checkFailFast();

      if (config.getMetricsTrackerFactory() != null) {
         setMetricsTrackerFactory(config.getMetricsTrackerFactory());
      }
      else {
         setMetricRegistry(config.getMetricRegistry());
      }
      //配置健康检测
      setHealthCheckRegistry(config.getHealthCheckRegistry());
      //为HikariConfig和HikariPool注册 JMX 相关的 MBean
      handleMBeans(this, true);
      //线程工厂
      ThreadFactory threadFactory = config.getThreadFactory();
      //连接池最大值
      final int maxPoolSize = config.getMaximumPoolSize();
      //构建一个阻塞队列
      LinkedBlockingQueue<Runnable> addConnectionQueue = new LinkedBlockingQueue<>(16);
      //执行添加新连接的线程池
      this.addConnectionExecutor = createThreadPoolExecutor(addConnectionQueue, poolName + " connection adder", threadFactory, new CustomDiscardPolicy());
      //执行关闭底层连接的线程池(队列任务抛弃策略会不断重试)
      this.closeConnectionExecutor = createThreadPoolExecutor(maxPoolSize, poolName + " connection closer", threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());
      //构建连接泄露告警任务工厂
      this.leakTaskFactory = new ProxyLeakTaskFactory(config.getLeakDetectionThreshold(), houseKeepingExecutorService);
      //创建定时任务(第一次100毫秒后执行,后续均30s走一次)，用来保持池内最少的空闲闲置连接
      this.houseKeeperTask = houseKeepingExecutorService.scheduleWithFixedDelay(new HouseKeeper(), 100L, housekeepingPeriodMs, MILLISECONDS);
      //堵塞直至填满 && 初始化失败超时 > 1ms
      if (Boolean.getBoolean("com.zaxxer.hikari.blockUntilFilled") && config.getInitializationFailTimeout() > 1) {
         //配置最大值、核心数
         addConnectionExecutor.setMaximumPoolSize(Math.min(16, Runtime.getRuntime().availableProcessors()));
         addConnectionExecutor.setCorePoolSize(Math.min(16, Runtime.getRuntime().availableProcessors()));

         final long startTime = currentTime();
         //条件成立进入睡眠
         while (elapsedMillis(startTime) < config.getInitializationFailTimeout() && getTotalConnections() < config.getMinimumIdle()) {
            quietlySleep(MILLISECONDS.toMillis(100));
         }
         //配置最大值、核心数
         addConnectionExecutor.setCorePoolSize(1);
         addConnectionExecutor.setMaximumPoolSize(1);
      }
   }

   /**
    * Get a connection from the pool, or timeout after connectionTimeout milliseconds.
    * 从池中获取连接，或在指定的毫秒数后超时
    * @return a java.sql.Connection instance
    * @throws SQLException thrown if a timeout occurs trying to obtain a connection
    */
   public Connection getConnection() throws SQLException
   {
      return getConnection(connectionTimeout);
   }

   /**
    * Get a connection from the pool, or timeout after the specified number of milliseconds.
    * 从池中获取连接，或在指定的毫秒数后超时
    * @param hardTimeout the maximum time to wait for a connection from the pool
    * @return a java.sql.Connection instance
    * @throws SQLException thrown if a timeout occurs trying to obtain a connection
    */
   public Connection getConnection(final long hardTimeout) throws SQLException
   {
      //获取连接的时候申请令牌, 主要是为了连接池挂起的时候, 控制用户不能获取连接
      //当连接池挂起的时候, Semaphore的 10000 个令牌都会被占用, 此处就会一直阻塞线程等待令牌
      suspendResumeLock.acquire();
      //记录获取连接的开始时间, 用于超时判断
      final var startTime = currentTime();

      try {
         //超时时间
         var timeout = hardTimeout;
         do {
            //从连接池获取连接, 超时时间timeout
            var poolEntry = connectionBag.borrow(timeout, MILLISECONDS);
            //borrow方法在超时的时候才会返回 null
            if (poolEntry == null) {
               break;
            }

            final var now = currentTime();
            //连接是否已被标记移除 || (最后访问时间 - now的毫秒差大于500毫秒 && 连接是否已死亡)
            if (poolEntry.isMarkedEvicted() || (elapsedMillis(poolEntry.lastAccessed, now) > aliveBypassWindowMs && isConnectionDead(poolEntry.connection))) {
               //关闭连接
               closeConnection(poolEntry, poolEntry.isMarkedEvicted() ? EVICTED_CONNECTION_MESSAGE : DEAD_CONNECTION_MESSAGE);
               //剩余超时时间
               timeout = hardTimeout - elapsedMillis(startTime);
            }
            else {
               //记录连接借用
               metricsTracker.recordBorrowStats(poolEntry, startTime);
               //创建ProxyConnection, ProxyConnection是Connection的包装, 同时也创建一个泄露检测的定时任务
               return poolEntry.createProxyConnection(leakTaskFactory.schedule(poolEntry));
            }
         } while (timeout > 0L);
         //记录连接超时 抛出创建超时异常
         metricsTracker.recordBorrowTimeoutStats(startTime);
         throw createTimeoutException(startTime);
      }
      catch (InterruptedException e) {
         //连接获取期间中断
         Thread.currentThread().interrupt();
         throw new SQLException(poolName + " - Interrupted during connection acquisition", e);
      }
      finally {
         //释放锁
         suspendResumeLock.release();
      }
   }

   /**
    * Shutdown the pool, closing all idle connections and aborting or closing
    * active connections.
    *
    * @throws InterruptedException thrown if the thread is interrupted during shutdown
    */
   public synchronized void shutdown() throws InterruptedException
   {
      try {
         //关闭
         poolState = POOL_SHUTDOWN;

         if (addConnectionExecutor == null) { // pool never started
            return;
         }

         logPoolState("Before shutdown ");
         //取消延迟任务
         if (houseKeeperTask != null) {
            houseKeeperTask.cancel(false);
            houseKeeperTask = null;
         }
         //软驱逐所有空闲连接
         softEvictConnections();
         //关闭线程池
         addConnectionExecutor.shutdown();
         //等待线程池关闭
         if (!addConnectionExecutor.awaitTermination(getLoginTimeout(), SECONDS)) {
            logger.warn("Timed-out waiting for add connection executor to shutdown");
         }
         //延迟线程池销毁
         destroyHouseKeepingExecutorService();
         //并发包close
         connectionBag.close();

         //构建线程池-处理剩余连接
         final var assassinExecutor = createThreadPoolExecutor(config.getMaximumPoolSize(), poolName + " connection assassinator",
                                                                           config.getThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy());
         try {
            final var start = currentTime();
            do {
               //尝试中止或关闭 所有的活动连接
               abortActiveConnections(assassinExecutor);
               //再次软驱逐所有空闲连接,执行abortActiveConnections时有些活动连接已经执行完变为未使用
               softEvictConnections();
            } while (getTotalConnections() > 0 && elapsedMillis(start) < SECONDS.toMillis(10));
         }
         finally {
            //关闭线程池
            assassinExecutor.shutdown();
            //等待线程池关闭
            if (!assassinExecutor.awaitTermination(10L, SECONDS)) {
               logger.warn("Timed-out waiting for connection assassin to shutdown");
            }
         }
         //关闭网络超时执行器
         shutdownNetworkTimeoutExecutor();
         //关闭底层连接的线程池关闭
         closeConnectionExecutor.shutdown();
         //等待线程池关闭
         if (!closeConnectionExecutor.awaitTermination(10L, SECONDS)) {
            logger.warn("Timed-out waiting for close connection executor to shutdown");
         }
      }
      finally {
         logPoolState("After shutdown ");
         //解除注册
         handleMBeans(this, false);
         //监控关闭
         metricsTracker.close();
      }
   }

   /**
    * Evict a Connection from the pool.
    * 从池中驱逐连接
    * @param connection the Connection to evict (actually a {@link ProxyConnection})
    */
   public void evictConnection(Connection connection)
   {
      var proxyConnection = (ProxyConnection) connection;
      proxyConnection.cancelLeakTask();

      try {
         //软驱逐
         softEvictConnection(proxyConnection.getPoolEntry(), "(connection evicted by user)", !connection.isClosed() /* owner */);
      }
      catch (SQLException e) {
         // unreachable in HikariCP, but we're still forced to catch it
      }
   }

   /**
    * Set a metrics registry to be used when registering metrics collectors.  The HikariDataSource prevents this
    * method from being called more than once.
    * 设置注册度量收集器时要使用的度量注册表
    * @param metricRegistry the metrics registry instance to use
    */
   @SuppressWarnings("PackageAccessibility")
   public void setMetricRegistry(Object metricRegistry)
   {
      if (metricRegistry != null && safeIsAssignableFrom(metricRegistry, "com.codahale.metrics.MetricRegistry")) {
         setMetricsTrackerFactory(new CodahaleMetricsTrackerFactory((MetricRegistry) metricRegistry));
      }
      else if (metricRegistry != null && safeIsAssignableFrom(metricRegistry, "io.micrometer.core.instrument.MeterRegistry")) {
         setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory((MeterRegistry) metricRegistry));
      }
      else {
         setMetricsTrackerFactory(null);
      }
   }

   /**
    * Set the MetricsTrackerFactory to be used to create the IMetricsTracker instance used by the pool.
    * 设置MetricsTrackerFactory，用于创建池使用的IMetricsTracker实例。
    * @param metricsTrackerFactory an instance of a class that subclasses MetricsTrackerFactory
    */
   public void setMetricsTrackerFactory(MetricsTrackerFactory metricsTrackerFactory)
   {
      if (metricsTrackerFactory != null) {
         this.metricsTracker = new MetricsTrackerDelegate(metricsTrackerFactory.create(config.getPoolName(), getPoolStats()));
      }
      else {
         //空实现
         this.metricsTracker = new NopMetricsTrackerDelegate();
      }
   }

   /**
    * Set the health check registry to be used when registering health checks.  Currently only Codahale health
    * checks are supported.
    * 设置注册健康检查时要使用的健康检查注册表。目前只支持Codahale健康检查
    * @param healthCheckRegistry the health check registry instance to use
    */
   public void setHealthCheckRegistry(Object healthCheckRegistry)
   {
      if (healthCheckRegistry != null) {
         CodahaleHealthChecker.registerHealthChecks(this, config, (HealthCheckRegistry) healthCheckRegistry);
      }
   }

   // ***********************************************************************
   //                        IBagStateListener callback
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public void addBagItem(final int waiting)
   {
      //添加连接队列深度
      final int queueDepth = addConnectionQueueDepth.get();
      //计算需要add的数量
      final int countToAdd = waiting - queueDepth;
      if (countToAdd >= 0) {
         //自增1
         addConnectionQueueDepth.incrementAndGet();
         //提交任务
         addConnectionExecutor.submit(poolEntryCreator);
      }
      else {
         logger.debug("{} - Add connection elided, waiting={}, adders pending/running={}", poolName, waiting, queueDepth);
      }
   }

   // ***********************************************************************
   //                        HikariPoolMBean methods
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public int getActiveConnections()
   {
      return connectionBag.getCount(STATE_IN_USE);
   }

   /** {@inheritDoc} */
   @Override
   public int getIdleConnections()
   {
      return connectionBag.getCount(STATE_NOT_IN_USE);
   }

   /** {@inheritDoc} */
   @Override
   public int getTotalConnections()
   {
      return connectionBag.size();
   }

   /** {@inheritDoc} */
   @Override
   public int getThreadsAwaitingConnection()
   {
      return connectionBag.getWaitingThreadCount();
   }

   /** {@inheritDoc} */
   @Override
   public void softEvictConnections()
   {
      connectionBag.values().forEach(poolEntry -> softEvictConnection(poolEntry, "(connection evicted)", false /* not owner */));
   }

   /** {@inheritDoc} */
   @Override
   public synchronized void suspendPool()
   {
      if (suspendResumeLock == SuspendResumeLock.FAUX_LOCK) {
         throw new IllegalStateException(poolName + " - is not suspendable");
      }
      else if (poolState != POOL_SUSPENDED) {
         suspendResumeLock.suspend();
         poolState = POOL_SUSPENDED;
      }
   }

   /** {@inheritDoc} */
   @Override
   public synchronized void resumePool()
   {
      //恢复  挂起-》正常
      if (poolState == POOL_SUSPENDED) {
         poolState = POOL_NORMAL;
         fillPool(false);
         //释放锁
         suspendResumeLock.resume();
      }
   }

   // ***********************************************************************
   //                           Package methods
   // ***********************************************************************

   /**
    * Log the current pool state at debug level.
    *
    * @param prefix an optional prefix to prepend the log message
    */
   void logPoolState(String... prefix)
   {
      if (logger.isDebugEnabled()) {
         logger.debug("{} - {}stats (total={}, active={}, idle={}, waiting={})",
                      poolName, (prefix.length > 0 ? prefix[0] : ""),
                      getTotalConnections(), getActiveConnections(), getIdleConnections(), getThreadsAwaitingConnection());
      }
   }

   /**
    * Recycle PoolEntry (add back to the pool)
    *
    * @param poolEntry the PoolEntry to recycle
    */
   @Override
   void recycle(final PoolEntry poolEntry)
   {
      metricsTracker.recordConnectionUsage(poolEntry);

      connectionBag.requite(poolEntry);
   }

   /**
    * Permanently close the real (underlying) connection (eat any exception).
    * 永久关闭真实（底层）连接（任何异常）
    * @param poolEntry poolEntry having the connection to close
    * @param closureReason reason to close
    */
   void closeConnection(final PoolEntry poolEntry, final String closureReason)
   {
      //从ConcurrentBag中删除
      if (connectionBag.remove(poolEntry)) {
         //close
         final var connection = poolEntry.close();
         //线程池执行关闭连接任务quietlyCloseConnection
         closeConnectionExecutor.execute(() -> {
            quietlyCloseConnection(connection, closureReason);
            //连接池状态是否正常
            if (poolState == POOL_NORMAL) {
               //触发扩充连接,是否需要补充连接
               fillPool(false);
            }
         });
      }
   }

   @SuppressWarnings("unused")
   int[] getPoolStateCounts()
   {
      return connectionBag.getStateCounts();
   }


   // ***********************************************************************
   //                           Private methods
   // ***********************************************************************

   /**
    * 构建一个新的池对象(连接)，如果配置了maxLifetime，则创建一个与maxLifetime时间相差2.5%的未来寿命结束任务，以确保池中没有大量连接死亡
    * Creating new poolEntry.  If maxLifetime is configured, create a future End-of-life task with 2.5% variance from
    * the maxLifetime time to ensure there is no massive die-off of Connections in the pool.
    */
   private PoolEntry createPoolEntry()
   {
      try {
         //构建基本的poolEntry
         final var poolEntry = newPoolEntry();
         //最大存活时间
         final var maxLifetime = config.getMaxLifetime();
         if (maxLifetime > 0) {
            //与maxLifetime时间相差2.5%
            final var variance = maxLifetime > 10_000 ? ThreadLocalRandom.current().nextLong( maxLifetime / 40 ) : 0;
            final var lifetime = maxLifetime - variance;
            //延迟任务:连接到最大生命周期后主动回收
            poolEntry.setFutureEol(houseKeepingExecutorService.schedule(new MaxLifetimeTask(poolEntry), lifetime, MILLISECONDS));
         }

         final long keepaliveTime = config.getKeepaliveTime();
         if (keepaliveTime > 0) {
            //与keepaliveTime时间相差10%
            final var variance = ThreadLocalRandom.current().nextLong(keepaliveTime / 10);
            final var heartbeatTime = keepaliveTime - variance;
            //延迟任务：存活时间
            poolEntry.setKeepalive(houseKeepingExecutorService.scheduleWithFixedDelay(new KeepaliveTask(poolEntry), heartbeatTime, heartbeatTime, MILLISECONDS));
         }

         return poolEntry;
      }
      catch (ConnectionSetupException e) {
         if (poolState == POOL_NORMAL) { // we check POOL_NORMAL to avoid a flood of messages if shutdown() is running concurrently
            logger.error("{} - Error thrown while acquiring connection from data source", poolName, e.getCause());
            lastConnectionFailure.set(e);
         }
      }
      catch (Exception e) {
         if (poolState == POOL_NORMAL) { // we check POOL_NORMAL to avoid a flood of messages if shutdown() is running concurrently
            logger.debug("{} - Cannot acquire connection from data source", poolName, e);
         }
      }

      return null;
   }

   /**
    * 尽量保持最少的连接
    * Fill pool up from current idle connections (as they are perceived at the point of execution) to minimumIdle connections.
    */
   private synchronized void fillPool(final boolean isAfterAdd)
   {
      //添加连接队列深度
      final var queueDepth = addConnectionQueueDepth.get();
      //计算需要add的数量
      final var countToAdd = connectionBag.getWaitingThreadCount() - queueDepth;
      //当前连接数<最大连接数 && (当前空闲连接数<最小空闲连接数 || 等待获取元素的线程数 > 当前空闲连接数)
      final var shouldAdd =
            getTotalConnections() < config.getMaximumPoolSize() &&
               (getIdleConnections() < config.getMinimumIdle() || countToAdd > getIdleConnections());
      //
      if (shouldAdd) {
         //自增1
         addConnectionQueueDepth.incrementAndGet();
         //提交任务
         addConnectionExecutor.submit(isAfterAdd ? postFillPoolEntryCreator : poolEntryCreator);
      }
      else if (isAfterAdd) {
         logger.debug("{} - Fill pool skipped, pool has sufficient level or currently being filled (queueDepth={}).", poolName, queueDepth);
      }
   }

   /**
    * Attempt to abort or close active connections.
    * 尝试中止或关闭活动连接
    * @param assassinExecutor the ExecutorService to pass to Connection.abort()
    */
   private void abortActiveConnections(final ExecutorService assassinExecutor)
   {
      for (var poolEntry : connectionBag.values(STATE_IN_USE)) {
         //poolEntry close 返回 connection对象
         Connection connection = poolEntry.close();
         try {
            //connection中止
            connection.abort(assassinExecutor);
         }
         catch (Throwable e) {
            //中止失败,真正关闭底层连接
            quietlyCloseConnection(connection, "(connection aborted during shutdown)");
         }
         finally {
            //connectionBag中移除
            connectionBag.remove(poolEntry);
         }
      }
   }

   /**
    * If initializationFailFast is configured, check that we have DB connectivity.
    * 如果配置了initializationFailFast，请检查数据库连接是否正常
    * @throws PoolInitializationException if fails to create or validate connection
    * @see HikariConfig#setInitializationFailTimeout(long)
    */
   private void checkFailFast()
   {
      final var initializationTimeout = config.getInitializationFailTimeout();
      if (initializationTimeout < 0) {
         return;
      }

      final var startTime = currentTime();
      do {
         //构建一个新的池对象(连接)
         final var poolEntry = createPoolEntry();
         if (poolEntry != null) {
            if (config.getMinimumIdle() > 0) {
               //将新对象添加到包中
               connectionBag.add(poolEntry);
               logger.info("{} - Added connection {}", poolName, poolEntry.connection);
            }
            else {
               //关闭底层连接
               quietlyCloseConnection(poolEntry.close(), "(initialization check complete and minimumIdle is zero)");
            }

            return;
         }

         if (getLastConnectionFailure() instanceof ConnectionSetupException) {
            throwPoolInitializationException(getLastConnectionFailure().getCause());
         }

         quietlySleep(SECONDS.toMillis(1));
      } while (elapsedMillis(startTime) < initializationTimeout);

      if (initializationTimeout > 0) {
         throwPoolInitializationException(getLastConnectionFailure());
      }
   }

   /**
    * Log the Throwable that caused pool initialization to fail, and then throw a PoolInitializationException with
    * that cause attached.
    *
    * @param t the Throwable that caused the pool to fail to initialize (possibly null)
    */
   private void throwPoolInitializationException(Throwable t)
   {
      logger.error("{} - Exception during pool initialization.", poolName, t);
      destroyHouseKeepingExecutorService();
      throw new PoolInitializationException(t);
   }

   /**
    * "Soft" evict a Connection (/PoolEntry) from the pool.  If this method is being called by the user directly
    * through {@link com.zaxxer.hikari.HikariDataSource#evictConnection(Connection)} then {@code owner} is {@code true}.
    *
    * If the caller is the owner, or if the Connection is idle (i.e. can be "reserved" in the {@link ConcurrentBag}),
    * then we can close the connection immediately.  Otherwise, we leave it "marked" for eviction so that it is evicted
    * the next time someone tries to acquire it from the pool.
    *
    * @param poolEntry the PoolEntry (/Connection) to "soft" evict from the pool
    * @param reason the reason that the connection is being evicted
    * @param owner true if the caller is the owner of the connection, false otherwise
    * @return true if the connection was evicted (closed), false if it was merely marked for eviction
    */
   private boolean softEvictConnection(final PoolEntry poolEntry, final String reason, final boolean owner)
   {
      poolEntry.markEvicted();
      if (owner || connectionBag.reserve(poolEntry)) {
         closeConnection(poolEntry, reason);
         return true;
      }

      return false;
   }

   /**
    * Create/initialize the Housekeeping service {@link ScheduledExecutorService}.  If the user specified an Executor
    * to be used in the {@link HikariConfig}, then we use that.  If no Executor was specified (typical), then create
    * an Executor and configure it.
    * 创建/初始化Housekeeping service，如果用户指定了要在HikariConfig中使用的执行器那么我们就使用它。如果未指定执行器（典型），则创建一个执行器并对其进行配置。
    * @return either the user specified {@link ScheduledExecutorService}, or the one we created
    */
   private ScheduledExecutorService initializeHouseKeepingExecutorService()
   {
      //用户未指定
      if (config.getScheduledExecutor() == null) {
         //线程工厂
         final var threadFactory = Optional.ofNullable(config.getThreadFactory()).orElseGet(() -> new DefaultThreadFactory(poolName + " housekeeper", true));
         //ScheduledThreadPoolExecutor
         final var executor = new ScheduledThreadPoolExecutor(1, threadFactory, new ThreadPoolExecutor.DiscardPolicy());
         executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
         executor.setRemoveOnCancelPolicy(true);
         return executor;
      }
      else {
         return config.getScheduledExecutor();
      }
   }

   /**
    * Destroy (/shutdown) the Housekeeping service Executor, if it was the one that we created.
    */
   private void destroyHouseKeepingExecutorService()
   {
      if (config.getScheduledExecutor() == null) {
         houseKeepingExecutorService.shutdownNow();
      }
   }

   /**
    * Create a PoolStats instance that will be used by metrics tracking, with a pollable resolution of 1 second.
    *
    * @return a PoolStats instance
    */
   private PoolStats getPoolStats()
   {
      return new PoolStats(SECONDS.toMillis(1)) {
         @Override
         protected void update() {
            this.pendingThreads = HikariPool.this.getThreadsAwaitingConnection();
            this.idleConnections = HikariPool.this.getIdleConnections();
            this.totalConnections = HikariPool.this.getTotalConnections();
            this.activeConnections = HikariPool.this.getActiveConnections();
            this.maxConnections = config.getMaximumPoolSize();
            this.minConnections = config.getMinimumIdle();
         }
      };
   }

   /**
    * Create a timeout exception (specifically, {@link SQLTransientConnectionException}) to be thrown, because a
    * timeout occurred when trying to acquire a Connection from the pool.  If there was an underlying cause for the
    * timeout, e.g. a SQLException thrown by the driver while trying to create a new Connection, then use the
    * SQL State from that exception as our own and additionally set that exception as the "next" SQLException inside
    * of our exception.
    *
    * As a side-effect, log the timeout failure at DEBUG, and record the timeout failure in the metrics tracker.
    *
    * @param startTime the start time (timestamp) of the acquisition attempt
    * @return a SQLException to be thrown from {@link #getConnection()}
    */
   private SQLException createTimeoutException(long startTime)
   {
      logPoolState("Timeout failure ");
      metricsTracker.recordConnectionTimeout();

      String sqlState = null;
      final var originalException = getLastConnectionFailure();
      if (originalException instanceof SQLException) {
         sqlState = ((SQLException) originalException).getSQLState();
      }
      final var connectionException = new SQLTransientConnectionException(poolName + " - Connection is not available, request timed out after " + elapsedMillis(startTime) + "ms.", sqlState, originalException);
      if (originalException instanceof SQLException) {
         connectionException.setNextException((SQLException) originalException);
      }

      return connectionException;
   }


   // ***********************************************************************
   //                      Non-anonymous Inner-classes
   // ***********************************************************************

   /**
    * Creating and adding poolEntries (connections) to the pool.
    */
   private final class PoolEntryCreator implements Callable<Boolean>
   {
      /**
       * 日志前缀
       */
      private final String loggingPrefix;

      PoolEntryCreator()
      {
         this(null);
      }

      PoolEntryCreator(String loggingPrefix)
      {
         this.loggingPrefix = loggingPrefix;
      }

      @Override
      public Boolean call()
      {
         var backoffMs = 10L;
         var added = false;
         try {
            //判断是否需要创建新连接
            while (shouldContinueCreating()) {
               //构建一个新的池对象
               final var poolEntry = createPoolEntry();
               if (poolEntry != null) {
                  added = true;
                  backoffMs = 10L;
                  connectionBag.add(poolEntry);
                  logger.debug("{} - Added connection {}", poolName, poolEntry.connection);
               } else {
                  //无法从数据库获取连接，休眠并重试
                  backoffMs = Math.min(SECONDS.toMillis(5), backoffMs * 2);
                  if (loggingPrefix != null)
                     logger.debug("{} - Connection add failed, sleeping with backoff: {}ms", poolName, backoffMs);
               }
               //睡眠
               quietlySleep(backoffMs);
            }
         }
         finally {
            //自减1
            addConnectionQueueDepth.decrementAndGet();
            if (added && loggingPrefix != null) logPoolState(loggingPrefix);
         }

         // HikariPool挂起、关闭或者到达最大连接数
         return Boolean.FALSE;
      }

      /**
       * We only create connections if we need another idle connection or have threads still waiting
       * for a new connection.  Otherwise we bail out of the request to create.
       *
       * @return true if we should create a connection, false if the need has disappeared
       */
      private synchronized boolean shouldContinueCreating() {
         //HikariPool State正常 && 当前连接数<最大连接数 && (当前空闲连接数<最小空闲连接数 || 等待获取元素的线程数 > 当前空闲连接数)
         return poolState == POOL_NORMAL && getTotalConnections() < config.getMaximumPoolSize() &&
            (getIdleConnections() < config.getMinimumIdle() || connectionBag.getWaitingThreadCount() > getIdleConnections());
      }
   }

   /**
    * The house keeping task to retire and maintain minimum idle connections.
    * 保持最少的空闲连接
    */
   private final class HouseKeeper implements Runnable
   {
      /**
       * 上次执行的时间戳
       */
      private volatile long previous = plusMillis(currentTime(), -housekeepingPeriodMs);
      /**
       * PoolBase类中 被volatile修饰的字段进行原子更新
       */
      @SuppressWarnings("AtomicFieldUpdaterNotStaticFinal")
      private final AtomicReferenceFieldUpdater<PoolBase, String> catalogUpdater = AtomicReferenceFieldUpdater.newUpdater(PoolBase.class, String.class, "catalog");

      @Override
      public void run()
      {
         try {
            //刷新值，以防它们通过MBean更改
            connectionTimeout = config.getConnectionTimeout();
            validationTimeout = config.getValidationTimeout();
            leakTaskFactory.updateLeakDetectionThreshold(config.getLeakDetectionThreshold());

            if (config.getCatalog() != null && !config.getCatalog().equals(catalog)) {
               catalogUpdater.set(HikariPool.this, config.getCatalog());
            }

            final var idleTimeout = config.getIdleTimeout();
            final var now = currentTime();

            // Detect retrograde time, allowing +128ms as per NTP spec.
            //时钟倒退,根据NTP规范检测逆行时间，允许+128ms。
            if (plusMillis(now, 128) < plusMillis(previous, housekeepingPeriodMs)) {
               logger.warn("{} - Retrograde clock change detected (housekeeper delta={}), soft-evicting connections from pool.",
                           poolName, elapsedDisplayString(previous, now));
               previous = now;
               //软驱逐所有空闲连接
               softEvictConnections();
               return;
            }
            //无需逐出时钟前进，这只会加速连接失效
            else if (now > plusMillis(previous, (3 * housekeepingPeriodMs) / 2)) {
               // No point evicting for forward clock motion, this merely accelerates connection retirement anyway
               logger.warn("{} - Thread starvation or clock leap detected (housekeeper delta={}).", poolName, elapsedDisplayString(previous, now));
            }

            previous = now;

            var afterPrefix = "Pool ";
            //空闲超时>0 && 最小空闲连接数 < 最大连接数
            if (idleTimeout > 0L && config.getMinimumIdle() < config.getMaximumPoolSize()) {
               logPoolState("Before cleanup ");
               afterPrefix = "After cleanup  ";
               //空闲状态连接数
               final var notInUse = connectionBag.values(STATE_NOT_IN_USE);
               //计算要移除的连接数量
               var toRemove = notInUse.size() - config.getMinimumIdle();
               for (PoolEntry entry : notInUse) {
                  if (toRemove > 0 && elapsedMillis(entry.lastAccessed, now) > idleTimeout && connectionBag.reserve(entry)) {
                     //关闭底层连接 toRemove--
                     closeConnection(entry, "(connection has passed idleTimeout)");
                     toRemove--;
                  }
               }
            }

            logPoolState(afterPrefix);
            //尽量保持最少的连接
            fillPool(true); // Try to maintain minimum connections
         }
         catch (Exception e) {
            logger.error("Unexpected exception in housekeeping task", e);
         }
      }
   }

   private class CustomDiscardPolicy implements RejectedExecutionHandler
   {
      @Override
      public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
         addConnectionQueueDepth.decrementAndGet();
      }
   }

   private final class MaxLifetimeTask implements Runnable
   {
      private final PoolEntry poolEntry;

      MaxLifetimeTask(final PoolEntry poolEntry)
      {
         this.poolEntry = poolEntry;
      }

      public void run()
      {
         if (softEvictConnection(poolEntry, "(connection has passed maxLifetime)", false /* not owner */)) {
            addBagItem(connectionBag.getWaitingThreadCount());
         }
      }
   }

   private final class KeepaliveTask implements Runnable
   {
      private final PoolEntry poolEntry;

      KeepaliveTask(final PoolEntry poolEntry)
      {
         this.poolEntry = poolEntry;
      }

      public void run()
      {
         if (connectionBag.reserve(poolEntry)) {
            if (isConnectionDead(poolEntry.connection)) {
               softEvictConnection(poolEntry, DEAD_CONNECTION_MESSAGE, true);
               addBagItem(connectionBag.getWaitingThreadCount());
            }
            else {
               connectionBag.unreserve(poolEntry);
               logger.debug("{} - keepalive: connection {} is alive", poolName, poolEntry.connection);
            }
         }
      }
   }

   public static class PoolInitializationException extends RuntimeException
   {
      private static final long serialVersionUID = 929872118275916520L;

      /**
       * Construct an exception, possibly wrapping the provided Throwable as the cause.
       * @param t the Throwable to wrap
       */
      public PoolInitializationException(Throwable t)
      {
         super("Failed to initialize pool: " + t.getMessage(), t);
      }
   }
}
