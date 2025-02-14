/*
 * Copyright (C) 2013 Brett Wooldridge
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

package com.zaxxer.hikari.util;

import java.util.Locale;
import java.util.concurrent.*;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 *
 * @author Brett Wooldridge
 */
public final class UtilityElf
{
   private UtilityElf()
   {
      // non-constructable
   }

   /**
    *
    * @return null if string is null or empty
   */
   public static String getNullIfEmpty(final String text)
   {
      return text == null ? null : text.trim().isEmpty() ? null : text.trim();
   }

   /**
    * Sleep and suppress InterruptedException (but re-signal it).
    *
    * @param millis the number of milliseconds to sleep
    */
   public static void quietlySleep(final long millis)
   {
      try {
         Thread.sleep(millis);
      }
      catch (InterruptedException e) {
         //抑制中断异常
         currentThread().interrupt();
      }
   }

   /**
    * 检查对象是否为给定类型的实例，在未加载该类时不会引发异常
    * Checks whether an object is an instance of given type without throwing exception when the class is not loaded.
    * @param obj the object to check
    * @param className String class
    * @return true if object is assignable from the type, false otherwise or when the class cannot be loaded
    */
   public static boolean safeIsAssignableFrom(Object obj, String className) {
      try {
         var clazz = Class.forName(className);
         return clazz.isAssignableFrom(obj.getClass());
      } catch (ClassNotFoundException ignored) {
         return false;
      }
   }

   /**
    * Create and instance of the specified class using the constructor matching the specified
    * arguments.
    * 使用与指定参数匹配的构造函数创建指定类的实例
    * @param <T> the class type
    * @param className the name of the class to instantiate
    * @param clazz a class to cast the result as
    * @param args arguments to a constructor
    * @return an instance of the specified class
    */
   public static <T> T createInstance(final String className, final Class<T> clazz, final Object... args)
   {
      if (className == null) {
         return null;
      }

      try {
         var loaded = UtilityElf.class.getClassLoader().loadClass(className);
         if (args.length == 0) {
            return clazz.cast(loaded.getDeclaredConstructor().newInstance());
         }

         var argClasses = new Class<?>[args.length];
         for (int i = 0; i < args.length; i++) {
            argClasses[i] = args[i].getClass();
         }
         var constructor = loaded.getConstructor(argClasses);
         return clazz.cast(constructor.newInstance(args));
      }
      catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   /**
    * Create a ThreadPoolExecutor.
    *
    * @param queueSize the queue size
    * @param threadName the thread name
    * @param threadFactory an optional ThreadFactory
    * @param policy the RejectedExecutionHandler policy
    * @return a ThreadPoolExecutor
    */
   public static ThreadPoolExecutor createThreadPoolExecutor(final int queueSize, final String threadName, ThreadFactory threadFactory, final RejectedExecutionHandler policy)
   {
      if (threadFactory == null) {
         threadFactory = new DefaultThreadFactory(threadName, true);
      }
      //构建一个queueSize的阻塞队列
      var queue = new LinkedBlockingQueue<Runnable>(queueSize);
      //核心数1 最大值1，存活时间5s
      var executor = new ThreadPoolExecutor(1 /*core*/, 1 /*max*/, 5 /*keepalive*/, SECONDS, queue, threadFactory, policy);
      //允许核心线程超时
      executor.allowCoreThreadTimeOut(true);
      return executor;
   }

   /**
    * Create a ThreadPoolExecutor.
    * 创建一个线程池执行器
    * @param queue the BlockingQueue to use
    * @param threadName the thread name
    * @param threadFactory an optional ThreadFactory
    * @param policy the RejectedExecutionHandler policy
    * @return a ThreadPoolExecutor
    */
   public static ThreadPoolExecutor createThreadPoolExecutor(final BlockingQueue<Runnable> queue, final String threadName, ThreadFactory threadFactory, final RejectedExecutionHandler policy)
   {
      if (threadFactory == null) {
         threadFactory = new DefaultThreadFactory(threadName, true);
      }
      //核心1，最大值1，存活时间5s
      var executor = new ThreadPoolExecutor(1 /*core*/, 1 /*max*/, 5 /*keepalive*/, SECONDS, queue, threadFactory, policy);
      //允许核心线程超时
      executor.allowCoreThreadTimeOut(true);
      return executor;
   }

   // ***********************************************************************
   //                       Misc. public methods
   // ***********************************************************************

   /**
    * Get the int value of a transaction isolation level by name.
    * 按名称获取事务隔离级别的int值
    * @param transactionIsolationName the name of the transaction isolation level
    * @return the int value of the isolation level or -1
    */
   public static int getTransactionIsolation(final String transactionIsolationName)
   {
      if (transactionIsolationName != null) {
         try {
            //字符串大写 匹配返回枚举
            final var upperCaseIsolationLevelName = transactionIsolationName.toUpperCase(Locale.ENGLISH);
            return IsolationLevel.valueOf(upperCaseIsolationLevelName).getLevelId();
         } catch (IllegalArgumentException e) {
            // legacy support for passing an integer version of the isolation level
            try {
               //字符串转换int匹配返回
               final var level = Integer.parseInt(transactionIsolationName);
               for (var iso : IsolationLevel.values()) {
                  if (iso.getLevelId() == level) {
                     return iso.getLevelId();
                  }
               }

               throw new IllegalArgumentException("Invalid transaction isolation value: " + transactionIsolationName);
            }
            catch (NumberFormatException nfe) {
               throw new IllegalArgumentException("Invalid transaction isolation value: " + transactionIsolationName, nfe);
            }
         }
      }

      return -1;
   }

   /**
    * 默认线程工厂
    */
   public static final class DefaultThreadFactory implements ThreadFactory {

      private final String threadName;
      private final boolean daemon;

      public DefaultThreadFactory(String threadName, boolean daemon) {
         this.threadName = threadName;
         this.daemon = daemon;
      }

      @Override
      @SuppressWarnings("NullableProblems")
      public Thread newThread(Runnable r) {
         var thread = new Thread(r, threadName);
         thread.setDaemon(daemon);
         return thread;
      }
   }
}
