/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.SQLExceptionOverride;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import com.zaxxer.hikari.util.DriverDataSource;
import com.zaxxer.hikari.util.PropertyElf;
import com.zaxxer.hikari.util.UtilityElf;
import com.zaxxer.hikari.util.UtilityElf.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static com.zaxxer.hikari.pool.ProxyConnection.*;
import static com.zaxxer.hikari.util.ClockSource.*;
import static com.zaxxer.hikari.util.UtilityElf.createInstance;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

abstract class PoolBase
{
   /**
    * Logger日志
    */
   private final Logger logger = LoggerFactory.getLogger(PoolBase.class);
   /**
    * Hikari配置
    */
   public final HikariConfig config;
   /**
    * 指标追踪
    */
   IMetricsTrackerDelegate metricsTracker;
   /**
    * 连接池名称 来源:HikariConfig
    */
   protected final String poolName;
   /**
    * 默认目录名称 来源:HikariConfig
    */
   volatile String catalog;
   /**
    * 最新连接异常信息
    */
   final AtomicReference<Exception> lastConnectionFailure;
   /**
    * 连接超时 来源:HikariConfig
    */
   long connectionTimeout;
   /**
    * 验证超时 来源:HikariConfig
    */
   long validationTimeout;
   /**
    * 异常处理实例 通过HikariConfig.exceptionOverrideClassName创建实例
    */
   SQLExceptionOverride exceptionOverride;
   /**
    * 重置
    */
   private static final String[] RESET_STATES = {"readOnly", "autoCommit", "isolation", "catalog", "netTimeout", "schema"};
   /**
    * 未初始化
    */
   private static final int UNINITIALIZED = -1;
   /**
    * TRUE、FALSE
    */
   private static final int TRUE = 1;
   private static final int FALSE = 0;
   /**
    * 网络超时
    */
   private int networkTimeout;
   /**
    * 是否支持网络超时
    */
   private int isNetworkTimeoutSupported;
   /**
    * 是否支持查询超时
    */
   private int isQueryTimeoutSupported;
   /**
    * 默认事务隔离级别
    */
   private int defaultTransactionIsolation;
   /**
    * 事务隔离级别 来源:HikariConfig.transactionIsolationName匹配枚举
    */
   private int transactionIsolation;
   /**
    * 网络超时执行器
    */
   private Executor netTimeoutExecutor;
   /**
    * 数据源
    */
   private DataSource dataSource;
   /**
    * 主题名称 来源:HikariConfig
    */
   private final String schema;
   /**
    * 是否只读 来源:HikariConfig
    */
   private final boolean isReadOnly;
   /**
    * 是否自动提交事务 来源:HikariConfig
    */
   private final boolean isAutoCommit;
   /**
    * 是否使用JDBC4验证
    * connectionTestQuery不为空,则为true,反之为false
    */
   private final boolean isUseJdbc4Validation;
   /**
    * 是否隔离内部查询 来源:HikariConfig
    */
   private final boolean isIsolateInternalQueries;
   /**
    * 是否验证检查
    */
   private volatile boolean isValidChecked;

   PoolBase(final HikariConfig config)
   {

      this.config = config;

      this.networkTimeout = UNINITIALIZED;
      this.catalog = config.getCatalog();
      this.schema = config.getSchema();
      this.isReadOnly = config.isReadOnly();
      this.isAutoCommit = config.isAutoCommit();
      //创建异常实例
      this.exceptionOverride = UtilityElf.createInstance(config.getExceptionOverrideClassName(), SQLExceptionOverride.class);
      //获取事务隔离级别Level值
      this.transactionIsolation = UtilityElf.getTransactionIsolation(config.getTransactionIsolation());

      this.isQueryTimeoutSupported = UNINITIALIZED;
      this.isNetworkTimeoutSupported = UNINITIALIZED;
      this.isUseJdbc4Validation = config.getConnectionTestQuery() == null;
      this.isIsolateInternalQueries = config.isIsolateInternalQueries();

      this.poolName = config.getPoolName();
      this.connectionTimeout = config.getConnectionTimeout();
      this.validationTimeout = config.getValidationTimeout();
      //构建一个原子引用
      this.lastConnectionFailure = new AtomicReference<>();
      //初始化数据源
      initializeDataSource();
   }

   /** {@inheritDoc} */
   @Override
   public String toString()
   {
      return poolName;
   }

   abstract void recycle(final PoolEntry poolEntry);

   // ***********************************************************************
   //                           JDBC methods
   // ***********************************************************************

   void quietlyCloseConnection(final Connection connection, final String closureReason)
   {
      //不为空
      if (connection != null) {
         try {
            logger.debug("{} - Closing connection {}: {}", poolName, connection, closureReason);

            // continue with the close even if setNetworkTimeout() throws
            try (connection) {
               //设置网络超时时间15s,进行close操作,try-resources语法自动close,真正关闭底层连接
               setNetworkTimeout(connection, SECONDS.toMillis(15));
            } catch (SQLException e) {
               //异常忽略
            }
         }
         catch (Exception e) {
            logger.debug("{} - Closing connection {} failed", poolName, connection, e);
         }
      }
   }

   /**
    * 连接是否死亡
    * @param connection
    * @return
    */
   boolean isConnectionDead(final Connection connection)
   {
      try {
         try {
            //设置网络超时时间为验证超时时间
            setNetworkTimeout(connection, validationTimeout);
            //验证时间转换ms -》 s
            final var validationSeconds = (int) Math.max(1000L, validationTimeout) / 1000;
            //是否使用JDBC4验证
            if (isUseJdbc4Validation) {
               //执行驱动程序的isValid
               return !connection.isValid(validationSeconds);
            }
            //如果connectionTestQuery配置了就会执行这下面的逻辑
            try (var statement = connection.createStatement()) {
               //是否支持网络超时
               if (isNetworkTimeoutSupported != TRUE) {
                  setQueryTimeout(statement, validationSeconds);
               }
               //执行SQL
               statement.execute(config.getConnectionTestQuery());
            }
         }
         finally {
            //验证结束,网络超时时间重新设置回原来的值
            setNetworkTimeout(connection, networkTimeout);
            //符合条件进行回滚
            if (isIsolateInternalQueries && !isAutoCommit) {
               connection.rollback();
            }
         }

         return false;
      }
      catch (Exception e) {
         lastConnectionFailure.set(e);
         logger.warn("{} - Failed to validate connection {} ({}). Possibly consider using a shorter maxLifetime value.",
                     poolName, connection, e.getMessage());
         return true;
      }
   }

   Exception getLastConnectionFailure()
   {
      return lastConnectionFailure.get();
   }

   public DataSource getUnwrappedDataSource()
   {
      return dataSource;
   }

   // ***********************************************************************
   //                         PoolEntry methods
   // ***********************************************************************

   PoolEntry newPoolEntry() throws Exception
   {
      return new PoolEntry(newConnection(), this, isReadOnly, isAutoCommit);
   }

   void resetConnectionState(final Connection connection, final ProxyConnection proxyConnection, final int dirtyBits) throws SQLException
   {
      int resetBits = 0;

      if ((dirtyBits & DIRTY_BIT_READONLY) != 0 && proxyConnection.getReadOnlyState() != isReadOnly) {
         connection.setReadOnly(isReadOnly);
         resetBits |= DIRTY_BIT_READONLY;
      }

      if ((dirtyBits & DIRTY_BIT_AUTOCOMMIT) != 0 && proxyConnection.getAutoCommitState() != isAutoCommit) {
         connection.setAutoCommit(isAutoCommit);
         resetBits |= DIRTY_BIT_AUTOCOMMIT;
      }

      if ((dirtyBits & DIRTY_BIT_ISOLATION) != 0 && proxyConnection.getTransactionIsolationState() != transactionIsolation) {
         connection.setTransactionIsolation(transactionIsolation);
         resetBits |= DIRTY_BIT_ISOLATION;
      }

      if ((dirtyBits & DIRTY_BIT_CATALOG) != 0 && catalog != null && !catalog.equals(proxyConnection.getCatalogState())) {
         connection.setCatalog(catalog);
         resetBits |= DIRTY_BIT_CATALOG;
      }

      if ((dirtyBits & DIRTY_BIT_NETTIMEOUT) != 0 && proxyConnection.getNetworkTimeoutState() != networkTimeout) {
         setNetworkTimeout(connection, networkTimeout);
         resetBits |= DIRTY_BIT_NETTIMEOUT;
      }

      if ((dirtyBits & DIRTY_BIT_SCHEMA) != 0 && schema != null && !schema.equals(proxyConnection.getSchemaState())) {
         connection.setSchema(schema);
         resetBits |= DIRTY_BIT_SCHEMA;
      }

      if (resetBits != 0 && logger.isDebugEnabled()) {
         logger.debug("{} - Reset ({}) on connection {}", poolName, stringFromResetBits(resetBits), connection);
      }
   }

   void shutdownNetworkTimeoutExecutor()
   {
      if (netTimeoutExecutor instanceof ThreadPoolExecutor) {
         ((ThreadPoolExecutor) netTimeoutExecutor).shutdownNow();
      }
   }

   long getLoginTimeout()
   {
      try {
         return (dataSource != null) ? dataSource.getLoginTimeout() : SECONDS.toSeconds(5);
      } catch (SQLException e) {
         return SECONDS.toSeconds(5);
      }
   }

   // ***********************************************************************
   //                       JMX methods
   // ***********************************************************************

   /**
    * Register MBeans for HikariConfig and HikariPool.
    * 为HikariConfig和HikariPool注册MBean
    * @param hikariPool a HikariPool instance
    */
   void handleMBeans(final HikariPool hikariPool, final boolean register)
   {
      if (!config.isRegisterMbeans()) {
         return;
      }

      try {
         final var mBeanServer = ManagementFactory.getPlatformMBeanServer();

         ObjectName beanConfigName, beanPoolName;
         if ("true".equals(System.getProperty("hikaricp.jmx.register2.0"))) {
             beanConfigName = new ObjectName("com.zaxxer.hikari:type=PoolConfig,name=" + poolName);
             beanPoolName = new ObjectName("com.zaxxer.hikari:type=Pool,name=" + poolName);
         } else {
            beanConfigName = new ObjectName("com.zaxxer.hikari:type=PoolConfig (" + poolName + ")");
            beanPoolName = new ObjectName("com.zaxxer.hikari:type=Pool (" + poolName + ")");
         }
         if (register) {
            if (!mBeanServer.isRegistered(beanConfigName)) {
               mBeanServer.registerMBean(config, beanConfigName);
               mBeanServer.registerMBean(hikariPool, beanPoolName);
            } else {
               logger.error("{} - JMX name ({}) is already registered.", poolName, poolName);
            }
         }
         else if (mBeanServer.isRegistered(beanConfigName)) {
            mBeanServer.unregisterMBean(beanConfigName);
            mBeanServer.unregisterMBean(beanPoolName);
         }
      }
      catch (Exception e) {
         logger.warn("{} - Failed to {} management beans.", poolName, (register ? "register" : "unregister"), e);
      }
   }

   // ***********************************************************************
   //                          Private methods
   // ***********************************************************************

   /**
    * 创建/初始化基础数据源
    */
   private void initializeDataSource()
   {
      //组装基本信息
      final var jdbcUrl = config.getJdbcUrl();
      final var username = config.getUsername();
      final var password = config.getPassword();
      final var dsClassName = config.getDataSourceClassName();
      final var driverClassName = config.getDriverClassName();
      final var dataSourceJNDI = config.getDataSourceJNDI();
      final var dataSourceProperties = config.getDataSourceProperties();

      var ds = config.getDataSource();
      //优先级别 从左往右逐渐减低[dataSourceClassName、jdbcUrl、dataSourceJndiName]
      if (dsClassName != null && ds == null) {
         //创建实例 配置属性
         ds = createInstance(dsClassName, DataSource.class);
         PropertyElf.setTargetFromProperties(ds, dataSourceProperties);
      }
      else if (jdbcUrl != null && ds == null) {
         //DriverDataSource
         ds = new DriverDataSource(jdbcUrl, driverClassName, dataSourceProperties, username, password);
      }
      else if (dataSourceJNDI != null && ds == null) {
         try {
            var ic = new InitialContext();
            ds = (DataSource) ic.lookup(dataSourceJNDI);
         } catch (NamingException e) {
            throw new PoolInitializationException(e);
         }
      }

      if (ds != null) {
         //配置登录超时时间
         setLoginTimeout(ds);
         //创建网络超时执行器
         createNetworkTimeoutExecutor(ds, dsClassName, jdbcUrl);
      }

      this.dataSource = ds;
   }

   /**
    * Obtain connection from data source.
    * 从数据源获取连接
    * @return a Connection connection
    */
   private Connection newConnection() throws Exception
   {
      final var start = currentTime();

      Connection connection = null;
      try {
         var username = config.getUsername();
         var password = config.getPassword();
         //获取connection对象
         connection = (username == null) ? dataSource.getConnection() : dataSource.getConnection(username, password);
         if (connection == null) {
            throw new SQLTransientConnectionException("DataSource returned null unexpectedly");
         }
         //设置连接初始状态
         setupConnection(connection);
         lastConnectionFailure.set(null);
         return connection;
      }
      catch (Exception e) {
         if (connection != null) {
            //关闭连接
            quietlyCloseConnection(connection, "(Failed to create/setup connection)");
         }
         else if (getLastConnectionFailure() == null) {
            logger.debug("{} - Failed to create/setup connection: {}", poolName, e.getMessage());
         }

         lastConnectionFailure.set(e);
         throw e;
      }
      finally {
         // tracker will be null during failFast check
         if (metricsTracker != null) {
            metricsTracker.recordConnectionCreated(elapsedMillis(start));
         }
      }
   }

   /**
    * Setup a connection initial state.
    * 设置连接初始状态
    * @param connection a Connection
    * @throws ConnectionSetupException thrown if any exception is encountered
    */
   private void setupConnection(final Connection connection) throws ConnectionSetupException
   {
      try {
         if (networkTimeout == UNINITIALIZED) {
            networkTimeout = getAndSetNetworkTimeout(connection, validationTimeout);
         }
         else {
            setNetworkTimeout(connection, validationTimeout);
         }

         if (connection.isReadOnly() != isReadOnly) {
            connection.setReadOnly(isReadOnly);
         }

         if (connection.getAutoCommit() != isAutoCommit) {
            connection.setAutoCommit(isAutoCommit);
         }

         checkDriverSupport(connection);

         if (transactionIsolation != defaultTransactionIsolation) {
            connection.setTransactionIsolation(transactionIsolation);
         }

         if (catalog != null) {
            connection.setCatalog(catalog);
         }

         if (schema != null) {
            connection.setSchema(schema);
         }

         executeSql(connection, config.getConnectionInitSql(), true);

         setNetworkTimeout(connection, networkTimeout);
      }
      catch (SQLException e) {
         throw new ConnectionSetupException(e);
      }
   }

   /**
    * Execute isValid() or connection test query.
    *
    * @param connection a Connection to check
    */
   private void checkDriverSupport(final Connection connection) throws SQLException
   {
      if (!isValidChecked) {
         checkValidationSupport(connection);
         checkDefaultIsolation(connection);

         isValidChecked = true;
      }
   }

   /**
    * Check whether Connection.isValid() is supported, or that the user has test query configured.
    *
    * @param connection a Connection to check
    * @throws SQLException rethrown from the driver
    */
   private void checkValidationSupport(final Connection connection) throws SQLException
   {
      try {
         if (isUseJdbc4Validation) {
            connection.isValid(1);
         }
         else {
            executeSql(connection, config.getConnectionTestQuery(), false);
         }
      }
      catch (Exception | AbstractMethodError e) {
         logger.error("{} - Failed to execute{} connection test query ({}).", poolName, (isUseJdbc4Validation ? " isValid() for connection, configure" : ""), e.getMessage());
         throw e;
      }
   }

   /**
    * Check the default transaction isolation of the Connection.
    *
    * @param connection a Connection to check
    * @throws SQLException rethrown from the driver
    */
   private void checkDefaultIsolation(final Connection connection) throws SQLException
   {
      try {
         defaultTransactionIsolation = connection.getTransactionIsolation();
         if (transactionIsolation == -1) {
            transactionIsolation = defaultTransactionIsolation;
         }
      }
      catch (SQLException e) {
         logger.warn("{} - Default transaction isolation level detection failed ({}).", poolName, e.getMessage());
         if (e.getSQLState() != null && !e.getSQLState().startsWith("08")) {
            throw e;
         }
      }
   }

   /**
    * Set the query timeout, if it is supported by the driver.
    *
    * @param statement a statement to set the query timeout on
    * @param timeoutSec the number of seconds before timeout
    */
   private void setQueryTimeout(final Statement statement, final int timeoutSec)
   {
      if (isQueryTimeoutSupported != FALSE) {
         try {
            statement.setQueryTimeout(timeoutSec);
            isQueryTimeoutSupported = TRUE;
         }
         catch (Exception e) {
            if (isQueryTimeoutSupported == UNINITIALIZED) {
               isQueryTimeoutSupported = FALSE;
               logger.info("{} - Failed to set query timeout for statement. ({})", poolName, e.getMessage());
            }
         }
      }
   }

   /**
    * Set the network timeout, if <code>isUseNetworkTimeout</code> is <code>true</code> and the
    * driver supports it.  Return the pre-existing value of the network timeout.
    *
    * @param connection the connection to set the network timeout on
    * @param timeoutMs the number of milliseconds before timeout
    * @return the pre-existing network timeout value
    */
   private int getAndSetNetworkTimeout(final Connection connection, final long timeoutMs)
   {
      if (isNetworkTimeoutSupported != FALSE) {
         try {
            final var originalTimeout = connection.getNetworkTimeout();
            connection.setNetworkTimeout(netTimeoutExecutor, (int) timeoutMs);
            isNetworkTimeoutSupported = TRUE;
            return originalTimeout;
         }
         catch (Exception | AbstractMethodError e) {
            if (isNetworkTimeoutSupported == UNINITIALIZED) {
               isNetworkTimeoutSupported = FALSE;

               logger.info("{} - Driver does not support get/set network timeout for connections. ({})", poolName, e.getMessage());
               if (validationTimeout < SECONDS.toMillis(1)) {
                  logger.warn("{} - A validationTimeout of less than 1 second cannot be honored on drivers without setNetworkTimeout() support.", poolName);
               }
               else if (validationTimeout % SECONDS.toMillis(1) != 0) {
                  logger.warn("{} - A validationTimeout with fractional second granularity cannot be honored on drivers without setNetworkTimeout() support.", poolName);
               }
            }
         }
      }

      return 0;
   }

   /**
    * Set the network timeout, if <code>isUseNetworkTimeout</code> is <code>true</code> and the
    * driver supports it.
    *
    * @param connection the connection to set the network timeout on
    * @param timeoutMs the number of milliseconds before timeout
    * @throws SQLException throw if the connection.setNetworkTimeout() call throws
    */
   private void setNetworkTimeout(final Connection connection, final long timeoutMs) throws SQLException
   {
      if (isNetworkTimeoutSupported == TRUE) {
         connection.setNetworkTimeout(netTimeoutExecutor, (int) timeoutMs);
      }
   }

   /**
    * Execute the user-specified init SQL.
    *
    * @param connection the connection to initialize
    * @param sql the SQL to execute
    * @param isCommit whether to commit the SQL after execution or not
    * @throws SQLException throws if the init SQL execution fails
    */
   private void executeSql(final Connection connection, final String sql, final boolean isCommit) throws SQLException
   {
      if (sql != null) {
         try (var statement = connection.createStatement()) {
            // connection was created a few milliseconds before, so set query timeout is omitted (we assume it will succeed)
            statement.execute(sql);
         }

         if (isIsolateInternalQueries && !isAutoCommit) {
            if (isCommit) {
               connection.commit();
            }
            else {
               connection.rollback();
            }
         }
      }
   }

   /**
    * 创建网络超时执行器
    * @param dataSource
    * @param dsClassName
    * @param jdbcUrl
    */
   private void createNetworkTimeoutExecutor(final DataSource dataSource, final String dsClassName, final String jdbcUrl)
   {
      // Temporary hack for MySQL issue: http://bugs.mysql.com/bug.php?id=75615
      if ((dsClassName != null && dsClassName.contains("Mysql")) ||
          (jdbcUrl != null && jdbcUrl.contains("mysql")) ||
          (dataSource != null && dataSource.getClass().getName().contains("Mysql"))) {
         netTimeoutExecutor = new SynchronousExecutor();
      }
      else {
         ThreadFactory threadFactory = config.getThreadFactory();
         threadFactory = threadFactory != null ? threadFactory : new DefaultThreadFactory(poolName + " network timeout executor", true);
         ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool(threadFactory);
         executor.setKeepAliveTime(15, SECONDS);
         executor.allowCoreThreadTimeOut(true);
         netTimeoutExecutor = executor;
      }
   }

   /**
    * Set the loginTimeout on the specified DataSource.
    * 在指定的数据源上设置loginTimeout
    * @param dataSource the DataSource
    */
   private void setLoginTimeout(final DataSource dataSource)
   {
      if (connectionTimeout != Integer.MAX_VALUE) {
         try {
            dataSource.setLoginTimeout(Math.max(1, (int) MILLISECONDS.toSeconds(500L + connectionTimeout)));
         }
         catch (Exception e) {
            logger.info("{} - Failed to set login timeout for data source. ({})", poolName, e.getMessage());
         }
      }
   }

   /**
    * This will create a string for debug logging. Given a set of "reset bits", this
    * method will return a concatenated string, for example:
    *
    * Input : 0b00110
    * Output: "autoCommit, isolation"
    *
    * @param bits a set of "reset bits"
    * @return a string of which states were reset
    */
   private String stringFromResetBits(final int bits)
   {
      final var sb = new StringBuilder();
      for (int ndx = 0; ndx < RESET_STATES.length; ndx++) {
         if ( (bits & (0b1 << ndx)) != 0) {
            sb.append(RESET_STATES[ndx]).append(", ");
         }
      }

      sb.setLength(sb.length() - 2);  // trim trailing comma
      return sb.toString();
   }

   // ***********************************************************************
   //                      Private Static Classes
   // ***********************************************************************

   static class ConnectionSetupException extends Exception
   {
      private static final long serialVersionUID = 929872118275916521L;

      ConnectionSetupException(Throwable t)
      {
         super(t);
      }
   }

   /**
    * Special executor used only to work around a MySQL issue that has not been addressed.
    * MySQL issue: http://bugs.mysql.com/bug.php?id=75615
    */
   private static class SynchronousExecutor implements Executor
   {
      /** {@inheritDoc} */
      @Override
      @SuppressWarnings("NullableProblems")
      public void execute(Runnable command)
      {
         try {
            command.run();
         }
         catch (Exception t) {
            LoggerFactory.getLogger(PoolBase.class).debug("Failed to execute: {}", command, t);
         }
      }
   }

   interface IMetricsTrackerDelegate extends AutoCloseable
   {
      default void recordConnectionUsage(PoolEntry poolEntry) {}

      default void recordConnectionCreated(long connectionCreatedMillis) {}

      default void recordBorrowTimeoutStats(long startTime) {}

      default void recordBorrowStats(final PoolEntry poolEntry, final long startTime) {}

      default void recordConnectionTimeout() {}

      @Override
      default void close() {}
   }

   /**
    * A class that delegates to a MetricsTracker implementation.  The use of a delegate
    * allows us to use the NopMetricsTrackerDelegate when metrics are disabled, which in
    * turn allows the JIT to completely optimize away to callsites to record metrics.
    */
   static class MetricsTrackerDelegate implements IMetricsTrackerDelegate
   {
      final IMetricsTracker tracker;

      MetricsTrackerDelegate(IMetricsTracker tracker)
      {
         this.tracker = tracker;
      }

      @Override
      public void recordConnectionUsage(final PoolEntry poolEntry)
      {
         tracker.recordConnectionUsageMillis(poolEntry.getMillisSinceBorrowed());
      }

      @Override
      public void recordConnectionCreated(long connectionCreatedMillis)
      {
         tracker.recordConnectionCreatedMillis(connectionCreatedMillis);
      }

      @Override
      public void recordBorrowTimeoutStats(long startTime)
      {
         tracker.recordConnectionAcquiredNanos(elapsedNanos(startTime));
      }

      @Override
      public void recordBorrowStats(final PoolEntry poolEntry, final long startTime)
      {
         final var now = currentTime();
         poolEntry.lastBorrowed = now;
         tracker.recordConnectionAcquiredNanos(elapsedNanos(startTime, now));
      }

      @Override
      public void recordConnectionTimeout() {
         tracker.recordConnectionTimeout();
      }

      @Override
      public void close()
      {
         tracker.close();
      }
   }

   /**
    * A no-op implementation of the IMetricsTrackerDelegate that is used when metrics capture is
    * disabled.
    */
   static final class NopMetricsTrackerDelegate implements IMetricsTrackerDelegate {}
}
