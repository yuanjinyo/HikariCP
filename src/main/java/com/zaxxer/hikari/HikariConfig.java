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

package com.zaxxer.hikari;

import com.codahale.metrics.health.HealthCheckRegistry;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.util.PropertyElf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.security.AccessControlException;
import java.sql.Connection;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;

import static com.zaxxer.hikari.util.UtilityElf.getNullIfEmpty;
import static com.zaxxer.hikari.util.UtilityElf.safeIsAssignableFrom;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * HikariConfig配置类
 */
@SuppressWarnings({"SameParameterValue", "unused"})
public class HikariConfig implements HikariConfigMXBean {
   /**
    * 日志输出
    */
   private static final Logger LOGGER = LoggerFactory.getLogger(HikariConfig.class);
   /**
    * 字符集
    */
   private static final char[] ID_CHARACTERS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
   /**
    * 连接超时 默认30s
    */
   private static final long CONNECTION_TIMEOUT = SECONDS.toMillis(30);
   /**
    * 校验超时 默认5s
    */
   private static final long VALIDATION_TIMEOUT = SECONDS.toMillis(5);
   /**
    * 超时时间最小值限制
    */
   private static final long SOFT_TIMEOUT_FLOOR = Long.getLong("com.zaxxer.hikari.timeoutMs.floor", 250L);
   /**
    * 空闲超时时间 默认10s
    */
   private static final long IDLE_TIMEOUT = MINUTES.toMillis(10);
   /**
    * 最大存活时间 30s
    */
   private static final long MAX_LIFETIME = MINUTES.toMillis(30);
   /**
    * 默认保持连接时间 默认0
    */
   private static final long DEFAULT_KEEPALIVE_TIME = 0L;
   /**
    * 默认池大小 默认10
    */
   private static final int DEFAULT_POOL_SIZE = 10;
   /**
    * 单元测试 默认false
    */
   private static boolean unitTest = false;

   // Properties changeable at runtime through the HikariConfigMXBean
   //可通过HikariConfigMXBean在运行时更改的属性
   /**
    * 设置默认目录名称
    * 注意:只有在连接被逐出后池被挂起时，才应更改此值
    */
   private volatile String catalog;
   /**
    * 连接超时:获取客户端等待池连接的最大毫秒数。
    * 如果在连接不可用的情况下超过了这个时间，javax将抛出SQLException
    *             异常来源javax.sql.DataSource.getConnection()
    */
   private volatile long connectionTimeout;
   /**
    * 校验超时:池等待连接验证为活动连接的最大毫秒数
    */
   private volatile long validationTimeout;
   /**
    * 空闲超时:允许连接在池中处于空闲状态的最长毫秒数。
    * 连接是否因空闲而失效，最大变化为+30秒，平均变化为+15秒。在此超时之前，连接将永远不会以空闲状态退出。
    * 值为0表示从不从池中删除空闲连接。
    */
   private volatile long idleTimeout;
   /**
    * 泄漏检测阈值:记录指示可能存在连接泄漏的消息之前，连接可以离开池的时间
    * 值为0表示禁用泄漏检测。
    */
   private volatile long leakDetectionThreshold;
   /**
    * 最大存活时间:池中连接的最大生存期
    * 当连接达到此超时时，即使最近使用过，它也将从池中退出。正在使用的连接永远不会失效，只有在空闲时才会被删除。
    */
   private volatile long maxLifetime;
   /**
    * 最大连接数:HikariCP将在池中保留的最大连接数,包括空闲连接和正在使用的连接。
    * 基本上，这个值将决定到数据库后端的最大实际连接数
    * 当池达到此大小且没有空闲连接可用时，对getConnection()的调用将在超时前阻塞长达${connectionTimeout}毫秒的时间
    */
   private volatile int maxPoolSize;
   /**
    * 最小空闲连接数:HikariCP尝试在池中维护的最小空闲连接数,包括空闲连接和正在使用的连接。
    * 如果空闲连接降至该值以下，HikariCP将尽最大努力快速高效地恢复它们。
    */
   private volatile int minIdle;
   /**
    * 数据源用户名:数据源的默认用户名
    * 用于getConnection(username, password)方法调用
    */
   private volatile String username;
   /**
    * 数据源密码:获取数据源的默认密码
    * 用于getConnection(username, password)方法调用
    */
   private volatile String password;

   // Properties NOT changeable at runtime
   // 运行时不可更改的属性
   /**
    * 初始化失败超时:池初始化失败前的毫秒数
    */
   private long initializationFailTimeout;
   /**
    * 初始化连接SQL:将在所有新连接上执行，在将其添加到池中之前创建
    */
   private String connectionInitSql;
   /**
    * 测试连接查询SQL:执行的SQL查询，以测试连接的有效性
    */
   private String connectionTestQuery;
   /**
    * 数据源完全限定类名
    */
   private String dataSourceClassName;
   /**
    * 数据源JndiName
    */
   private String dataSourceJndiName;
   /**
    * 驱动器ClassName
    */
   private String driverClassName;
   /**
    * 异常重写ClassName
    */
   private String exceptionOverrideClassName;
   /**
    * JDBC URL
    */
   private String jdbcUrl;
   /**
    * 池名称
    */
   private String poolName;
   /**
    * 主题
    */
   private String schema;
   /**
    * 事务隔离名称
    */
   private String transactionIsolationName;
   /**
    * 事务自动提交:池中连接的默认自动提交行为
    */
   private boolean isAutoCommit;
   /**
    * 是否只读
    */
   private boolean isReadOnly;
   /**
    * 是否隔离内部查询
    */
   private boolean isIsolateInternalQueries;
   /**
    * 是否注册JMX功能
    */
   private boolean isRegisterMbeans;
   /**
    * 池暂停行为（允许或不允许）
    */
   private boolean isAllowPoolSuspension;
   /**
    * 数据源
    */
   private DataSource dataSource;
   /**
    * 数据源配置
    */
   private Properties dataSourceProperties;
   /**
    * 线程工厂
    */
   private ThreadFactory threadFactory;
   /**
    * 定时执行Service
    */
   private ScheduledExecutorService scheduledExecutor;
   /**
    * 指标跟踪工厂
    */
   private MetricsTrackerFactory metricsTrackerFactory;
   /**
    * JMX注册
    */
   private Object metricRegistry;
   /**
    * 健康检查注册
    */
   private Object healthCheckRegistry;
   /**
    * 健康检查配置
    */
   private Properties healthCheckProperties;
   /**
    * 存活时间
    */
   private long keepaliveTime;
   /**
    * 是否被密封:一旦启动，池的配置将被密封。使用HikariConfigMXBean进行运行时更改
    *
    */
   private volatile boolean sealed;

   /**
    * 默认构造器
    */
   public HikariConfig()
   {
      //默认值赋值
      dataSourceProperties = new Properties();
      healthCheckProperties = new Properties();

      minIdle = -1;
      maxPoolSize = -1;
      maxLifetime = MAX_LIFETIME;
      connectionTimeout = CONNECTION_TIMEOUT;
      validationTimeout = VALIDATION_TIMEOUT;
      idleTimeout = IDLE_TIMEOUT;
      initializationFailTimeout = 1;
      isAutoCommit = true;
      keepaliveTime = DEFAULT_KEEPALIVE_TIME;
      //JRE System获取属性，如果不为空进行加载配置
      var systemProp = System.getProperty("hikaricp.configurationFile");
      if (systemProp != null) {
         //加载配置
         loadProperties(systemProp);
      }
   }

   /**
    * Construct a HikariConfig from the specified properties object.
    * 有参构造
    * @param properties the name of the property file
    */
   public HikariConfig(Properties properties)
   {
      //调用无参构造
      this();
      //通过配置文件设置Target属性
      PropertyElf.setTargetFromProperties(this, properties);
   }

   /**
    * Construct a HikariConfig from the specified property file name.  <code>propertyFileName</code>
    * will first be treated as a path in the file-system, and if that fails the
    * Class.getResourceAsStream(propertyFileName) will be tried.
    *
    * 从指定的属性文件名构造HikariConfig
    * 将首先被视为文件系统中的一个路径，如果该路径失败，则该类将失败
    * 将尝试getResourceAsStream
    * @param propertyFileName the name of the property file
    */
   public HikariConfig(String propertyFileName)
   {
      //调用无参构造
      this();
      ////加载配置
      loadProperties(propertyFileName);
   }

   // ***********************************************************************
   //                       HikariConfigMXBean methods
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public String getCatalog()
   {
      return catalog;
   }

   /** {@inheritDoc} */
   @Override
   public void setCatalog(String catalog)
   {
      this.catalog = catalog;
   }


   /** {@inheritDoc} */
   @Override
   public long getConnectionTimeout()
   {
      return connectionTimeout;
   }

   /** {@inheritDoc} */
   @Override
   public void setConnectionTimeout(long connectionTimeoutMs)
   {
      //connectionTimeoutMs等于0取Integer最大值,小于超时时间最小值 则取 超时时间最小值
      if (connectionTimeoutMs == 0) {
         this.connectionTimeout = Integer.MAX_VALUE;
      }
      else if (connectionTimeoutMs < SOFT_TIMEOUT_FLOOR) {
         throw new IllegalArgumentException("connectionTimeout cannot be less than " + SOFT_TIMEOUT_FLOOR + "ms");
      }
      else {
         this.connectionTimeout = connectionTimeoutMs;
      }
   }

   /** {@inheritDoc} */
   @Override
   public long getIdleTimeout()
   {
      return idleTimeout;
   }

   /** {@inheritDoc} */
   @Override
   public void setIdleTimeout(long idleTimeoutMs)
   {
      //空闲超时时间不能小于0
      if (idleTimeoutMs < 0) {
         throw new IllegalArgumentException("idleTimeout cannot be negative");
      }
      this.idleTimeout = idleTimeoutMs;
   }

   /** {@inheritDoc} */
   @Override
   public long getLeakDetectionThreshold()
   {
      return leakDetectionThreshold;
   }

   /** {@inheritDoc} */
   @Override
   public void setLeakDetectionThreshold(long leakDetectionThresholdMs)
   {
      this.leakDetectionThreshold = leakDetectionThresholdMs;
   }

   /** {@inheritDoc} */
   @Override
   public long getMaxLifetime()
   {
      return maxLifetime;
   }

   /** {@inheritDoc} */
   @Override
   public void setMaxLifetime(long maxLifetimeMs)
   {
      this.maxLifetime = maxLifetimeMs;
   }

   /** {@inheritDoc} */
   @Override
   public int getMaximumPoolSize()
   {
      return maxPoolSize;
   }

   /** {@inheritDoc} */
   @Override
   public void setMaximumPoolSize(int maxPoolSize)
   {
      //最大池数量不能小于1
      if (maxPoolSize < 1) {
         throw new IllegalArgumentException("maxPoolSize cannot be less than 1");
      }
      this.maxPoolSize = maxPoolSize;
   }

   /** {@inheritDoc} */
   @Override
   public int getMinimumIdle()
   {
      return minIdle;
   }

   /** {@inheritDoc} */
   @Override
   public void setMinimumIdle(int minIdle)
   {
      //最小空闲不能小于0
      if (minIdle < 0) {
         throw new IllegalArgumentException("minimumIdle cannot be negative");
      }
      this.minIdle = minIdle;
   }

   /**
    * Get the default password to use for DataSource.getConnection(username, password) calls.
    * @return the password
    */
   public String getPassword()
   {
      return password;
   }

   /**
    * Set the default password to use for DataSource.getConnection(username, password) calls.
    * @param password the password
    */
   @Override
   public void setPassword(String password)
   {
      this.password = password;
   }

   /**
    * Get the default username used for DataSource.getConnection(username, password) calls.
    *
    * @return the username
    */
   public String getUsername()
   {
      return username;
   }

   /**
    * Set the default username used for DataSource.getConnection(username, password) calls.
    *
    * @param username the username
    */
   @Override
   public void setUsername(String username)
   {
      this.username = username;
   }

   /** {@inheritDoc} */
   @Override
   public long getValidationTimeout()
   {
      return validationTimeout;
   }

   /** {@inheritDoc} */
   @Override
   public void setValidationTimeout(long validationTimeoutMs)
   {
      //校验超时小于超时时间最小值
      if (validationTimeoutMs < SOFT_TIMEOUT_FLOOR) {
         throw new IllegalArgumentException("validationTimeout cannot be less than " + SOFT_TIMEOUT_FLOOR + "ms");
      }

      this.validationTimeout = validationTimeoutMs;
   }

   // ***********************************************************************
   //                     All other configuration methods
   // ***********************************************************************

   /**
    * Get the SQL query to be executed to test the validity of connections.
    * 获取要执行的SQL查询，以测试连接的有效性
    * @return the SQL query string, or null
    */
   public String getConnectionTestQuery()
   {
      return connectionTestQuery;
   }

   /**
    * Set the SQL query to be executed to test the validity of connections. Using
    * the JDBC4 <code>Connection.isValid()</code> method to test connection validity can
    * be more efficient on some databases and is recommended.
    * 设置要执行的SQL查询以测试连接的有效性。使用JDBC4 Connection.isValid() 测试连接有效性的方法在某些数据库上可能更有效，建议使用。
    * @param connectionTestQuery a SQL query string
    */
   public void setConnectionTestQuery(String connectionTestQuery)
   {
      //校验是否被密封
      checkIfSealed();
      this.connectionTestQuery = connectionTestQuery;
   }

   /**
    * Get the SQL string that will be executed on all new connections when they are
    * created, before they are added to the pool.
    * 获取SQL字符串，该字符串将在所有新连接上执行，在将其添加到池中之前创建。
    * @return the SQL to execute on new connections, or null
    */
   public String getConnectionInitSql()
   {
      return connectionInitSql;
   }

   /**
    * Set the SQL string that will be executed on all new connections when they are
    * created, before they are added to the pool.  If this query fails, it will be
    * treated as a failed connection attempt.
    * 设置创建新连接时，在将其添加到池之前，将在所有新连接上执行的SQL字符串。如果此查询失败，将被视为连接尝试失败。
    * @param connectionInitSql the SQL to execute on new connections
    */
   public void setConnectionInitSql(String connectionInitSql)
   {
      //校验是否被密封
      checkIfSealed();
      this.connectionInitSql = connectionInitSql;
   }

   /**
    * Get the {@link DataSource} that has been explicitly specified to be wrapped by the
    * pool.
    * 获取已显式指定由池包装的数据源
    * @return the {@link DataSource} instance, or null
    */
   public DataSource getDataSource()
   {
      return dataSource;
   }

   /**
    * Set a {@link DataSource} for the pool to explicitly wrap.  This setter is not
    * available through property file based initialization.
    * 显式设置由池包装的数据源，这在基于属性文件的初始化中不可用
    * @param dataSource a specific {@link DataSource} to be wrapped by the pool
    */
   public void setDataSource(DataSource dataSource)
   {
      //校验是否被密封
      checkIfSealed();
      this.dataSource = dataSource;
   }

   /**
    * Get the name of the JDBC {@link DataSource} class used to create Connections.
    * 获取用于创建连接的JDBC数据源类的名称。
    * @return the fully qualified name of the JDBC {@link DataSource} class
    */
   public String getDataSourceClassName()
   {
      return dataSourceClassName;
   }

   /**
    * Set the fully qualified class name of the JDBC {@link DataSource} that will be used create Connections.
    * 设置将用于创建连接的JDBC数据源的完全限定类名。
    * @param className the fully qualified name of the JDBC {@link DataSource} class
    */
   public void setDataSourceClassName(String className)
   {
      //校验是否被密封
      checkIfSealed();
      this.dataSourceClassName = className;
   }

   /**
    * Add a property (name/value pair) that will be used to configure the {@link DataSource}/{@link java.sql.Driver}.
    *
    * In the case of a {@link DataSource}, the property names will be translated to Java setters following the Java Bean
    * naming convention.  For example, the property {@code cachePrepStmts} will translate into {@code setCachePrepStmts()}
    * with the {@code value} passed as a parameter.
    *
    * In the case of a {@link java.sql.Driver}, the property will be added to a {@link Properties} instance that will
    * be passed to the driver during {@link java.sql.Driver#connect(String, Properties)} calls.
    * 添加一个属性(name/value)，这将用于配置DataSource/java.sql.Driver
    *
    * 在DataSource的情况下,属性名将被转换为Java Bean后面的Java setter命名约定，例如，属性cacheprepstms将转换为setcacheprepstms()并将value作为参数传递
    *
    * 在 java.sql.Driver的情况下，属性将被添加到Properties实例，该实例将在java.sql.driver#connect（String，Properties）调用过程中传递给驱动程序。
    *
    * @param propertyName the name of the property
    * @param value the value to be used by the DataSource/Driver
    */
   public void addDataSourceProperty(String propertyName, Object value)
   {
      //校验是否被密封
      checkIfSealed();
      dataSourceProperties.put(propertyName, value);
   }

   public String getDataSourceJNDI()
   {
      return this.dataSourceJndiName;
   }

   public void setDataSourceJNDI(String jndiDataSource)
   {
      //校验是否被密封
      checkIfSealed();
      this.dataSourceJndiName = jndiDataSource;
   }

   public Properties getDataSourceProperties()
   {
      return dataSourceProperties;
   }

   public void setDataSourceProperties(Properties dsProperties)
   {
      //校验是否被密封
      checkIfSealed();
      dataSourceProperties.putAll(dsProperties);
   }

   public String getDriverClassName()
   {
      return driverClassName;
   }

   public void setDriverClassName(String driverClassName)
   {
      //校验是否被密封
      checkIfSealed();

      var driverClass = attemptFromContextLoader(driverClassName);
      try {
         if (driverClass == null) {
            driverClass = this.getClass().getClassLoader().loadClass(driverClassName);
            LOGGER.debug("Driver class {} found in the HikariConfig class classloader {}", driverClassName, this.getClass().getClassLoader());
         }
      } catch (ClassNotFoundException e) {
         LOGGER.error("Failed to load driver class {} from HikariConfig class classloader {}", driverClassName, this.getClass().getClassLoader());
      }

      if (driverClass == null) {
         throw new RuntimeException("Failed to load driver class " + driverClassName + " in either of HikariConfig class loader or Thread context classloader");
      }

      try {
         driverClass.getConstructor().newInstance();
         this.driverClassName = driverClassName;
      }
      catch (Exception e) {
         throw new RuntimeException("Failed to instantiate class " + driverClassName, e);
      }
   }

   public String getJdbcUrl()
   {
      return jdbcUrl;
   }

   public void setJdbcUrl(String jdbcUrl)
   {
      //校验是否被密封
      checkIfSealed();
      this.jdbcUrl = jdbcUrl;
   }

   /**
    * Get the default auto-commit behavior of connections in the pool.
    * 获取池中连接的默认自动提交行为
    * @return the default auto-commit behavior of connections
    */
   public boolean isAutoCommit()
   {
      return isAutoCommit;
   }

   /**
    * Set the default auto-commit behavior of connections in the pool.
    * 设置池中连接的默认自动提交行为
    * @param isAutoCommit the desired auto-commit default for connections
    */
   public void setAutoCommit(boolean isAutoCommit)
   {
      //校验是否被密封
      checkIfSealed();
      this.isAutoCommit = isAutoCommit;
   }

   /**
    * Get the pool suspension behavior (allowed or disallowed).
    * 获取池暂停行为（允许或不允许）
    * @return the pool suspension behavior
    */
   public boolean isAllowPoolSuspension()
   {
      return isAllowPoolSuspension;
   }

   /**
    * Set whether or not pool suspension is allowed.  There is a performance
    * impact when pool suspension is enabled.  Unless you need it (for a
    * redundancy system for example) do not enable it.
    * 设置是否允许暂停游泳池
    * 启用池暂停时会影响性能
    * 除非需要（例如，对于冗余系统），否则不要启用它。
    * @param isAllowPoolSuspension the desired pool suspension allowance
    */
   public void setAllowPoolSuspension(boolean isAllowPoolSuspension)
   {
      //校验是否被密封
      checkIfSealed();
      this.isAllowPoolSuspension = isAllowPoolSuspension;
   }

   /**
    * Get the pool initialization failure timeout.  See {@code #setInitializationFailTimeout(long)}
    * for details.
    * 获取池初始化失败超时
    * @return the number of milliseconds before the pool initialization fails
    * @see HikariConfig#setInitializationFailTimeout(long)
    */
   public long getInitializationFailTimeout()
   {
      return initializationFailTimeout;
   }

   /**
    * Set the pool initialization failure timeout.  This setting applies to pool
    * initialization when {@link HikariDataSource} is constructed with a {@link HikariConfig},
    * or when {@link HikariDataSource} is constructed using the no-arg constructor
    * and {@link HikariDataSource#getConnection()} is called.
    * 设置池初始化失败超时。
    * 此设置适用于池使用HikariDataSource构造HikariConfig时初始化，
    * 或者当使用no-arg构造函数构造HikariDataSource时
    * 然后调用HikariDataSource#getConnection()方法
    *
    * <ul>
    *   <li>
    *       Any value greater than zero will be treated as a timeout for pool initialization.
    *       The calling thread will be blocked from continuing until a successful connection
    *       to the database, or until the timeout is reached.  If the timeout is reached, then
    *       a {@code PoolInitializationException} will be thrown.
    *       任何大于零的值都将被视为池初始化的超时
    *       在成功连接之前，调用线程将被阻止继续到数据库，或直到达到超时。
    *       如果达到超时，则将抛出PoolInitializationException
    *   </li>
    *   <li>
    *       A value of zero will <i>not</i>  prevent the pool from starting in the
    *       case that a connection cannot be obtained. However, upon start the pool will
    *       attempt to obtain a connection and validate that the {@code connectionTestQuery}
    *       and {@code connectionInitSql} are valid.  If those validations fail, an exception
    *       will be thrown.  If a connection cannot be obtained, the validation is skipped
    *       and the the pool will start and continue to try to obtain connections in the
    *       background.  This can mean that callers to {@code DataSource#getConnection()} may
    *       encounter exceptions.
    *       在无法获得连接的情况下，零值不会阻止池启动
    *       然而，在启动时，池将尝试获取连接并验证connectionTestQuery和connectionInitSql是有效的。
    *       如果这些验证失败，将引发异常
    *       如果无法获得连接，则跳过验证，池将启动并继续尝试在后台获取连接
    *       这可能意味着DataSource#getConnection()的调用方可能会遇到例外清空
    *
    *   </li>
    *   <li>
    *       A value less than zero will bypass any connection attempt and validation during
    *       startup, and therefore the pool will start immediately.  The pool will continue to
    *       try to obtain connections in the background. This can mean that callers to
    *       {@code DataSource#getConnection()} may encounter exceptions.
    *       小于零的值将绕过启动期间的任何连接尝试和验证，因此池将立即启动。
    *       池将继续尝试在后台获取连接
    *       这可能意味着DataSource#getConnection()调用方可能会遇到异常
    *   </li>
    * </ul>
    * Note that if this timeout value is greater than or equal to zero (0), and therefore an
    * initial connection validation is performed, this timeout does not override the
    * {@code connectionTimeout} or {@code validationTimeout}; they will be honored before this
    * timeout is applied.  The default value is one millisecond.
    * 请注意，如果此超时值大于或等于零（0），则执行初始连接验证时，此超时不会覆盖connectionTimeout或validationTimeout,在应用此超时之前，他们将得到尊重。
    * 默认值为1毫秒。
    * @param initializationFailTimeout the number of milliseconds before the
    *        pool initialization fails, or 0 to validate connection setup but continue with
    *        pool start, or less than zero to skip all initialization checks and start the
    *        pool without delay.
    *        池初始化失败前的毫秒数，0表示验证连接设置但继续池启动，小于0表示跳过所有初始化检查并立即启动池。
    */
   public void setInitializationFailTimeout(long initializationFailTimeout)
   {
      //校验是否被密封
      checkIfSealed();
      this.initializationFailTimeout = initializationFailTimeout;
   }

   /**
    * Determine whether internal pool queries, principally aliveness checks, will be isolated in their own transaction
    * via {@link Connection#rollback()}.  Defaults to {@code false}.
    *
    * @return {@code true} if internal pool queries are isolated, {@code false} if not
    */
   public boolean isIsolateInternalQueries()
   {
      return isIsolateInternalQueries;
   }

   /**
    * Configure whether internal pool queries, principally aliveness checks, will be isolated in their own transaction
    * via {@link Connection#rollback()}.  Defaults to {@code false}.
    *
    * @param isolate {@code true} if internal pool queries should be isolated, {@code false} if not
    */
   public void setIsolateInternalQueries(boolean isolate)
   {
      //校验是否被密封
      checkIfSealed();
      this.isIsolateInternalQueries = isolate;
   }

   public MetricsTrackerFactory getMetricsTrackerFactory()
   {
      return metricsTrackerFactory;
   }

   public void setMetricsTrackerFactory(MetricsTrackerFactory metricsTrackerFactory)
   {
      if (metricRegistry != null) {
         throw new IllegalStateException("cannot use setMetricsTrackerFactory() and setMetricRegistry() together");
      }

      this.metricsTrackerFactory = metricsTrackerFactory;
   }

   /**
    * Get the MetricRegistry instance to use for registration of metrics used by HikariCP.  Default is {@code null}.
    *
    * @return the MetricRegistry instance that will be used
    */
   public Object getMetricRegistry()
   {
      return metricRegistry;
   }

   /**
    * Set a MetricRegistry instance to use for registration of metrics used by HikariCP.
    *
    * @param metricRegistry the MetricRegistry instance to use
    */
   public void setMetricRegistry(Object metricRegistry)
   {
      if (metricsTrackerFactory != null) {
         throw new IllegalStateException("cannot use setMetricRegistry() and setMetricsTrackerFactory() together");
      }

      if (metricRegistry != null) {
         metricRegistry = getObjectOrPerformJndiLookup(metricRegistry);

         if (!safeIsAssignableFrom(metricRegistry, "com.codahale.metrics.MetricRegistry")
             && !(safeIsAssignableFrom(metricRegistry, "io.micrometer.core.instrument.MeterRegistry"))) {
            throw new IllegalArgumentException("Class must be instance of com.codahale.metrics.MetricRegistry or io.micrometer.core.instrument.MeterRegistry");
         }
      }

      this.metricRegistry = metricRegistry;
   }

   /**
    * Get the HealthCheckRegistry that will be used for registration of health checks by HikariCP.  Currently only
    * Codahale/DropWizard is supported for health checks.
    *
    * @return the HealthCheckRegistry instance that will be used
    */
   public Object getHealthCheckRegistry()
   {
      return healthCheckRegistry;
   }

   /**
    * Set the HealthCheckRegistry that will be used for registration of health checks by HikariCP.  Currently only
    * Codahale/DropWizard is supported for health checks.  Default is {@code null}.
    *
    * @param healthCheckRegistry the HealthCheckRegistry to be used
    */
   public void setHealthCheckRegistry(Object healthCheckRegistry)
   {
      checkIfSealed();

      if (healthCheckRegistry != null) {
         healthCheckRegistry = getObjectOrPerformJndiLookup(healthCheckRegistry);

         if (!(healthCheckRegistry instanceof HealthCheckRegistry)) {
            throw new IllegalArgumentException("Class must be an instance of com.codahale.metrics.health.HealthCheckRegistry");
         }
      }

      this.healthCheckRegistry = healthCheckRegistry;
   }

   public Properties getHealthCheckProperties()
   {
      return healthCheckProperties;
   }

   public void setHealthCheckProperties(Properties healthCheckProperties)
   {
      checkIfSealed();
      this.healthCheckProperties.putAll(healthCheckProperties);
   }

   public void addHealthCheckProperty(String key, String value)
   {
      checkIfSealed();
      healthCheckProperties.setProperty(key, value);
   }

   /**
    * This property controls the keepalive interval for a connection in the pool. An in-use connection will never be
    * tested by the keepalive thread, only when it is idle will it be tested.
    *
    * @return the interval in which connections will be tested for aliveness, thus keeping them alive by the act of checking. Value is in milliseconds, default is 0 (disabled).
    */
   public long getKeepaliveTime() {
      return keepaliveTime;
   }

   /**
    * This property controls the keepalive interval for a connection in the pool. An in-use connection will never be
    * tested by the keepalive thread, only when it is idle will it be tested.
    *
    * @param keepaliveTimeMs the interval in which connections will be tested for aliveness, thus keeping them alive by the act of checking. Value is in milliseconds, default is 0 (disabled).
    */
   public void setKeepaliveTime(long keepaliveTimeMs) {
      this.keepaliveTime = keepaliveTimeMs;
   }

   /**
    * Determine whether the Connections in the pool are in read-only mode.
    *
    * @return {@code true} if the Connections in the pool are read-only, {@code false} if not
    */
   public boolean isReadOnly()
   {
      return isReadOnly;
   }

   /**
    * Configures the Connections to be added to the pool as read-only Connections.
    *
    * @param readOnly {@code true} if the Connections in the pool are read-only, {@code false} if not
    */
   public void setReadOnly(boolean readOnly)
   {
      checkIfSealed();
      this.isReadOnly = readOnly;
   }

   /**
    * Determine whether HikariCP will self-register {@link HikariConfigMXBean} and {@link HikariPoolMXBean} instances
    * in JMX.
    *
    * @return {@code true} if HikariCP will register MXBeans, {@code false} if it will not
    */
   public boolean isRegisterMbeans()
   {
      return isRegisterMbeans;
   }

   /**
    * Configures whether HikariCP self-registers the {@link HikariConfigMXBean} and {@link HikariPoolMXBean} in JMX.
    *
    * @param register {@code true} if HikariCP should register MXBeans, {@code false} if it should not
    */
   public void setRegisterMbeans(boolean register)
   {
      checkIfSealed();
      this.isRegisterMbeans = register;
   }

   /** {@inheritDoc} */
   @Override
   public String getPoolName()
   {
      return poolName;
   }

   /**
    * Set the name of the connection pool.  This is primarily used for the MBean
    * to uniquely identify the pool configuration.
    *
    * @param poolName the name of the connection pool to use
    */
   public void setPoolName(String poolName)
   {
      checkIfSealed();
      this.poolName = poolName;
   }

   /**
    * Get the ScheduledExecutorService used for housekeeping.
    *
    * @return the executor
    */
   public ScheduledExecutorService getScheduledExecutor()
   {
      return scheduledExecutor;
   }

   /**
    * Set the ScheduledExecutorService used for housekeeping.
    *
    * @param executor the ScheduledExecutorService
    */
   public void setScheduledExecutor(ScheduledExecutorService executor)
   {
      checkIfSealed();
      this.scheduledExecutor = executor;
   }

   public String getTransactionIsolation()
   {
      return transactionIsolationName;
   }

   /**
    * Get the default schema name to be set on connections.
    *
    * @return the default schema name
    */
   public String getSchema()
   {
      return schema;
   }

   /**
    * Set the default schema name to be set on connections.
    *
    * @param schema the name of the default schema
    */
   public void setSchema(String schema)
   {
      checkIfSealed();
      this.schema = schema;
   }

   /**
    * Get the user supplied SQLExceptionOverride class name.
    *
    * @return the user supplied SQLExceptionOverride class name
    * @see SQLExceptionOverride
    */
   public String getExceptionOverrideClassName()
   {
      return this.exceptionOverrideClassName;
   }

   /**
    * Set the user supplied SQLExceptionOverride class name.
    *
    * @param exceptionOverrideClassName the user supplied SQLExceptionOverride class name
    * @see SQLExceptionOverride
    */
   public void setExceptionOverrideClassName(String exceptionOverrideClassName)
   {
      checkIfSealed();

      var overrideClass = attemptFromContextLoader(exceptionOverrideClassName);
      try {
         if (overrideClass == null) {
            overrideClass = this.getClass().getClassLoader().loadClass(exceptionOverrideClassName);
            LOGGER.debug("SQLExceptionOverride class {} found in the HikariConfig class classloader {}", exceptionOverrideClassName, this.getClass().getClassLoader());
         }
      } catch (ClassNotFoundException e) {
         LOGGER.error("Failed to load SQLExceptionOverride class {} from HikariConfig class classloader {}", exceptionOverrideClassName, this.getClass().getClassLoader());
      }

      if (overrideClass == null) {
         throw new RuntimeException("Failed to load SQLExceptionOverride class " + exceptionOverrideClassName + " in either of HikariConfig class loader or Thread context classloader");
      }

      try {
         overrideClass.getConstructor().newInstance();
         this.exceptionOverrideClassName = exceptionOverrideClassName;
      }
      catch (Exception e) {
         throw new RuntimeException("Failed to instantiate class " + exceptionOverrideClassName, e);
      }
   }

   /**
    * Set the default transaction isolation level.  The specified value is the
    * constant name from the <code>Connection</code> class, eg.
    * <code>TRANSACTION_REPEATABLE_READ</code>.
    *
    * @param isolationLevel the name of the isolation level
    */
   public void setTransactionIsolation(String isolationLevel)
   {
      checkIfSealed();
      this.transactionIsolationName = isolationLevel;
   }

   /**
    * Get the thread factory used to create threads.
    *
    * @return the thread factory (may be null, in which case the default thread factory is used)
    */
   public ThreadFactory getThreadFactory()
   {
      return threadFactory;
   }

   /**
    * Set the thread factory to be used to create threads.
    *
    * @param threadFactory the thread factory (setting to null causes the default thread factory to be used)
    */
   public void setThreadFactory(ThreadFactory threadFactory)
   {
      checkIfSealed();
      this.threadFactory = threadFactory;
   }

   void seal()
   {
      this.sealed = true;
   }

   /**
    * Copies the state of {@code this} into {@code other}.
    *
    * @param other Other {@link HikariConfig} to copy the state to.
    */
   public void copyStateTo(HikariConfig other)
   {
      for (var field : HikariConfig.class.getDeclaredFields()) {
         if (!Modifier.isFinal(field.getModifiers())) {
            field.setAccessible(true);
            try {
               field.set(other, field.get(this));
            }
            catch (Exception e) {
               throw new RuntimeException("Failed to copy HikariConfig state: " + e.getMessage(), e);
            }
         }
      }

      other.sealed = false;
   }

   // ***********************************************************************
   //                          Private methods
   // ***********************************************************************

   private Class<?> attemptFromContextLoader(final String driverClassName) {
      final var threadContextClassLoader = Thread.currentThread().getContextClassLoader();
      if (threadContextClassLoader != null) {
         try {
            final var driverClass = threadContextClassLoader.loadClass(driverClassName);
            LOGGER.debug("Driver class {} found in Thread context class loader {}", driverClassName, threadContextClassLoader);
            return driverClass;
         } catch (ClassNotFoundException e) {
            LOGGER.debug("Driver class {} not found in Thread context class loader {}, trying classloader {}",
               driverClassName, threadContextClassLoader, this.getClass().getClassLoader());
         }
      }

      return null;
   }

   @SuppressWarnings("StatementWithEmptyBody")
   public void validate()
   {
      if (poolName == null) {
         poolName = generatePoolName();
      }
      else if (isRegisterMbeans && poolName.contains(":")) {
         throw new IllegalArgumentException("poolName cannot contain ':' when used with JMX");
      }

      // treat empty property as null
      //noinspection NonAtomicOperationOnVolatileField
      catalog = getNullIfEmpty(catalog);
      connectionInitSql = getNullIfEmpty(connectionInitSql);
      connectionTestQuery = getNullIfEmpty(connectionTestQuery);
      transactionIsolationName = getNullIfEmpty(transactionIsolationName);
      dataSourceClassName = getNullIfEmpty(dataSourceClassName);
      dataSourceJndiName = getNullIfEmpty(dataSourceJndiName);
      driverClassName = getNullIfEmpty(driverClassName);
      jdbcUrl = getNullIfEmpty(jdbcUrl);

      // Check Data Source Options
      if (dataSource != null) {
         if (dataSourceClassName != null) {
            LOGGER.warn("{} - using dataSource and ignoring dataSourceClassName.", poolName);
         }
      }
      else if (dataSourceClassName != null) {
         if (driverClassName != null) {
            LOGGER.error("{} - cannot use driverClassName and dataSourceClassName together.", poolName);
            // NOTE: This exception text is referenced by a Spring Boot FailureAnalyzer, it should not be
            // changed without first notifying the Spring Boot developers.
            throw new IllegalStateException("cannot use driverClassName and dataSourceClassName together.");
         }
         else if (jdbcUrl != null) {
            LOGGER.warn("{} - using dataSourceClassName and ignoring jdbcUrl.", poolName);
         }
      }
      else if (jdbcUrl != null || dataSourceJndiName != null) {
         // ok
      }
      else if (driverClassName != null) {
         LOGGER.error("{} - jdbcUrl is required with driverClassName.", poolName);
         throw new IllegalArgumentException("jdbcUrl is required with driverClassName.");
      }
      else {
         LOGGER.error("{} - dataSource or dataSourceClassName or jdbcUrl is required.", poolName);
         throw new IllegalArgumentException("dataSource or dataSourceClassName or jdbcUrl is required.");
      }

      validateNumerics();

      if (LOGGER.isDebugEnabled() || unitTest) {
         logConfiguration();
      }
   }

   private void validateNumerics()
   {
      if (maxLifetime != 0 && maxLifetime < SECONDS.toMillis(30)) {
         LOGGER.warn("{} - maxLifetime is less than 30000ms, setting to default {}ms.", poolName, MAX_LIFETIME);
         maxLifetime = MAX_LIFETIME;
      }

      // keepalive time must larger then 30 seconds
      if (keepaliveTime != 0 && keepaliveTime < SECONDS.toMillis(30)) {
         LOGGER.warn("{} - keepaliveTime is less than 30000ms, disabling it.", poolName);
         keepaliveTime = DEFAULT_KEEPALIVE_TIME;
      }

      // keepalive time must be less than maxLifetime (if maxLifetime is enabled)
      if (keepaliveTime != 0 && maxLifetime != 0 && keepaliveTime >= maxLifetime) {
         LOGGER.warn("{} - keepaliveTime is greater than or equal to maxLifetime, disabling it.", poolName);
         keepaliveTime = DEFAULT_KEEPALIVE_TIME;
      }

      if (leakDetectionThreshold > 0 && !unitTest) {
         if (leakDetectionThreshold < SECONDS.toMillis(2) || (leakDetectionThreshold > maxLifetime && maxLifetime > 0)) {
            LOGGER.warn("{} - leakDetectionThreshold is less than 2000ms or more than maxLifetime, disabling it.", poolName);
            leakDetectionThreshold = 0;
         }
      }

      if (connectionTimeout < SOFT_TIMEOUT_FLOOR) {
         LOGGER.warn("{} - connectionTimeout is less than {}ms, setting to {}ms.", poolName, SOFT_TIMEOUT_FLOOR, CONNECTION_TIMEOUT);
         connectionTimeout = CONNECTION_TIMEOUT;
      }

      if (validationTimeout < SOFT_TIMEOUT_FLOOR) {
         LOGGER.warn("{} - validationTimeout is less than {}ms, setting to {}ms.", poolName, SOFT_TIMEOUT_FLOOR, VALIDATION_TIMEOUT);
         validationTimeout = VALIDATION_TIMEOUT;
      }

      if (maxPoolSize < 1) {
         maxPoolSize = DEFAULT_POOL_SIZE;
      }

      if (minIdle < 0 || minIdle > maxPoolSize) {
         minIdle = maxPoolSize;
      }

      if (idleTimeout + SECONDS.toMillis(1) > maxLifetime && maxLifetime > 0 && minIdle < maxPoolSize) {
         LOGGER.warn("{} - idleTimeout is close to or more than maxLifetime, disabling it.", poolName);
         idleTimeout = 0;
      }
      else if (idleTimeout != 0 && idleTimeout < SECONDS.toMillis(10) && minIdle < maxPoolSize) {
         LOGGER.warn("{} - idleTimeout is less than 10000ms, setting to default {}ms.", poolName, IDLE_TIMEOUT);
         idleTimeout = IDLE_TIMEOUT;
      }
      else  if (idleTimeout != IDLE_TIMEOUT && idleTimeout != 0 && minIdle == maxPoolSize) {
         LOGGER.warn("{} - idleTimeout has been set but has no effect because the pool is operating as a fixed size pool.", poolName);
      }
   }

   /**
    * 检查池是否密封
    */
   private void checkIfSealed()
   {
      if (sealed) throw new IllegalStateException("The configuration of the pool is sealed once started. Use HikariConfigMXBean for runtime changes.");
   }

   private void logConfiguration()
   {
      LOGGER.debug("{} - configuration:", poolName);
      final var propertyNames = new TreeSet<>(PropertyElf.getPropertyNames(HikariConfig.class));
      for (var prop : propertyNames) {
         try {
            var value = PropertyElf.getProperty(prop, this);
            if ("dataSourceProperties".equals(prop)) {
               var dsProps = PropertyElf.copyProperties(dataSourceProperties);
               dsProps.setProperty("password", "<masked>");
               value = dsProps;
            }

            if ("initializationFailTimeout".equals(prop) && initializationFailTimeout == Long.MAX_VALUE) {
               value = "infinite";
            }
            else if ("transactionIsolation".equals(prop) && transactionIsolationName == null) {
               value = "default";
            }
            else if (prop.matches("scheduledExecutorService|threadFactory") && value == null) {
               value = "internal";
            }
            else if (prop.contains("jdbcUrl") && value instanceof String) {
               value = ((String)value).replaceAll("([?&;]password=)[^&#;]*(.*)", "$1<masked>$2");
            }
            else if (prop.contains("password")) {
               value = "<masked>";
            }
            else if (value instanceof String) {
               value = "\"" + value + "\""; // quote to see lead/trailing spaces is any
            }
            else if (value == null) {
               value = "none";
            }
            LOGGER.debug("{}{}", (prop + "................................................").substring(0, 32), value);
         }
         catch (Exception e) {
            // continue
         }
      }
   }

   /**
    * 加载配置
    * @param propertyFileName 配置文件路径
    */
   private void loadProperties(String propertyFileName)
   {
      //new File
      final var propFile = new File(propertyFileName);
      //是否为文件 ? FileInputStream : getResourceAsStream
      try (final var is = propFile.isFile() ? new FileInputStream(propFile) : this.getClass().getResourceAsStream(propertyFileName)) {
         if (is != null) {
            //读取配置文件
            var props = new Properties();
            props.load(is);
            //通过配置文件设置Target属性
            PropertyElf.setTargetFromProperties(this, props);
         }
         else {
            throw new IllegalArgumentException("Cannot find property file: " + propertyFileName);
         }
      }
      catch (IOException io) {
         throw new RuntimeException("Failed to read property file", io);
      }
   }

   private String generatePoolName()
   {
      final var prefix = "HikariPool-";
      try {
         // Pool number is global to the VM to avoid overlapping pool numbers in classloader scoped environments
         synchronized (System.getProperties()) {
            final var next = String.valueOf(Integer.getInteger("com.zaxxer.hikari.pool_number", 0) + 1);
            System.setProperty("com.zaxxer.hikari.pool_number", next);
            return prefix + next;
         }
      } catch (AccessControlException e) {
         // The SecurityManager didn't allow us to read/write system properties
         // so just generate a random pool number instead
         final var random = ThreadLocalRandom.current();
         final var buf = new StringBuilder(prefix);

         for (var i = 0; i < 4; i++) {
            buf.append(ID_CHARACTERS[random.nextInt(62)]);
         }

         LOGGER.info("assigned random pool name '{}' (security manager prevented access to system properties)", buf);

         return buf.toString();
      }
   }

   private Object getObjectOrPerformJndiLookup(Object object)
   {
      if (object instanceof String) {
         try {
            var initCtx = new InitialContext();
            return initCtx.lookup((String) object);
         }
         catch (NamingException e) {
            throw new IllegalArgumentException(e);
         }
      }
      return object;
   }
}
