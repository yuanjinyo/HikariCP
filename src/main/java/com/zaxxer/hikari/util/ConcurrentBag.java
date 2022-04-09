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
package com.zaxxer.hikari.util;

import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.zaxxer.hikari.util.ClockSource.currentTime;
import static com.zaxxer.hikari.util.ClockSource.elapsedNanos;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.*;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.locks.LockSupport.parkNanos;

/**
 * This is a specialized concurrent bag that achieves superior performance
 * to LinkedBlockingQueue and LinkedTransferQueue for the purposes of a
 * connection pool.  It uses ThreadLocal storage when possible to avoid
 * locks, but resorts to scanning a common collection if there are no
 * available items in the ThreadLocal list.  Not-in-use items in the
 * ThreadLocal lists can be "stolen" when the borrowing thread has none
 * of its own.  It is a "lock-less" implementation using a specialized
 * AbstractQueuedLongSynchronizer to manage cross-thread signaling.
 *
 * Note that items that are "borrowed" from the bag are not actually
 * removed from any collection, so garbage collection will not occur
 * even if the reference is abandoned.  Thus care must be taken to
 * "requite" borrowed objects otherwise a memory leak will result.  Only
 * the "remove" method can completely remove an object from the bag.
 *
 * @author Brett Wooldridge
 *
 * @param <T> the templated type to store in the bag
 */
public class ConcurrentBag<T extends IConcurrentBagEntry> implements AutoCloseable
{
   /**
    * Logger日志
    */
   private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBag.class);
   /**
    * sharedList存放共享元素
    * 写时复制ArrayList 线程安全
    * 适用场景:读多写少
    * 缺点：
    *    写时复制机制,在进行写操作时，内存中同时有2个对象(旧对象与新对象)的内存,可能造成频繁的Yong GC与Full GC
    *    只能保证数据的最终一致性,而不能保证数据的实时一致性
    */
   private final CopyOnWriteArrayList<T> sharedList;
   /**
    * 是否使用弱引用 默认false
    */
   private final boolean weakThreadLocals;
   /**
    * 本线程本地缓存
    */
   private final ThreadLocal<List<Object>> threadList;
   /**
    * 添加元素的监听器，在HikariPool中实现，用于请求创建新连接
    */
   private final IBagStateListener listener;
   /**
    * 当前等待获取元素的线程数
    */
   private final AtomicInteger waiters;
   /**
    * ConcurrentBag是否处于关闭状态
    */
   private volatile boolean closed;
   /**
    * 阻塞式一进一出队列(接力)
    */
   private final SynchronousQueue<T> handoffQueue;

   /**
    * 定义连接的状态和操作
    */
   public interface IConcurrentBagEntry
   {
      //空闲状态
      int STATE_NOT_IN_USE = 0;
      //连接在使用
      int STATE_IN_USE = 1;
      //remove的时候先标记为被移除
      int STATE_REMOVED = -1;
      //被预留状态，不可用但是可以移除
      //主要在检查线程中，要对连接进行softEvictConnection，确保能从not-in-use转到 reserved
      int STATE_RESERVED = -2;

      boolean compareAndSet(int expectState, int newState);
      void setState(int newState);
      int getState();
   }

   public interface IBagStateListener
   {
      void addBagItem(int waiting);
   }

   /**
    * Construct a ConcurrentBag with the specified listener.
    *
    * @param listener the IBagStateListener to attach to this bag
    */
   public ConcurrentBag(final IBagStateListener listener)
   {
      //HikariPool对象
      this.listener = listener;
      //是否使用弱引用 默认false
      this.weakThreadLocals = useWeakThreadLocals();
      //构建同步队列
      this.handoffQueue = new SynchronousQueue<>(true);
      //AtomicInteger 0
      this.waiters = new AtomicInteger();
      //CopyOnWriteArrayList
      this.sharedList = new CopyOnWriteArrayList<>();
      if (weakThreadLocals) {
         this.threadList = ThreadLocal.withInitial(() -> new ArrayList<>(16));
      }
      else {
         this.threadList = ThreadLocal.withInitial(() -> new FastList<>(IConcurrentBagEntry.class, 16));
      }
   }

   /**
    * The method will borrow a BagEntry from the bag, blocking for the
    * specified timeout if none are available.
    * 该方法将从包中借用一个BagEntry，如果没有可用的，则阻塞指定的超时
    * @param timeout how long to wait before giving up, in units of unit
    * @param timeUnit a <code>TimeUnit</code> determining how to interpret the timeout parameter
    * @return a borrowed instance from the bag or null if a timeout occurs
    * @throws InterruptedException if interrupted while waiting
    */
   public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException
   {
      //1.首先尝试线程本地列表中获取连接列表,这里的List通常为FastList
      final var list = threadList.get();
      //倒着遍历,最后面的是最近使用过的,说明刚还的,还没有被别的线程拿去使用
      for (int i = list.size() - 1; i >= 0; i--) {
         final var entry = list.remove(i);
         @SuppressWarnings("unchecked")
         final T bagEntry = weakThreadLocals ? ((WeakReference<T>) entry).get() : (T) entry;
         //如果能够获取连接且该连接的状态成功从STATE_NOT_IN_USE置为STATE_IN_USE
         if (bagEntry != null && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
            return bagEntry;
         }
      }

      // Otherwise, scan the shared list ... then poll the handoff queue
      // 2.如果ThreadList中没有可用的链接，则尝试从共享集合中获取链接
      final int waiting = waiters.incrementAndGet();
      try {
         //遍历
         for (T bagEntry : sharedList) {
            //连接的状态成功从STATE_NOT_IN_USE置为STATE_IN_USE
            if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
               // If we may have stolen another waiter's connection, request another bag add.
               // 我们可能抢到了别人的连接，通过监听器添加连接
               if (waiting > 1) {
                  //通知监听器添加连接
                  listener.addBagItem(waiting - 1);
               }
               return bagEntry;
            }
         }
         //共享列表无可用连接,通知监听器添加连接
         listener.addBagItem(waiting);
         //进入超时等待循环
         timeout = timeUnit.toNanos(timeout);
         do {
            final var start = currentTime();
            //同步队列获取连接对象
            final T bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
            //超时返回NULL对象 || 获取可用的连接
            if (bagEntry == null || bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
               return bagEntry;
            }

            timeout -= elapsedNanos(start);
         } while (timeout > 10_000);

         return null;
      }
      finally {
         //扣减等待计数
         waiters.decrementAndGet();
      }
   }

   /**
    * This method will return a borrowed object to the bag.  Objects
    * that are borrowed from the bag but never "requited" will result
    * in a memory leak.
    * 此方法会将借用的对象返回到ConcurrentBag中
    * @param bagEntry the value to return to the bag
    * @throws NullPointerException if value is null
    * @throws IllegalStateException if the bagEntry was not borrowed from the bag
    */
   public void requite(final T bagEntry)
   {
      //设置状态 - 空闲状态
      bagEntry.setState(STATE_NOT_IN_USE);
      //当前等待获取元素的线程数 > 0
      for (var i = 0; waiters.get() > 0; i++) {
         //bagEntry不为空闲状态 || 同步队列添加成功
         if (bagEntry.getState() != STATE_NOT_IN_USE || handoffQueue.offer(bagEntry)) {
            return;
         }
         else if ((i & 0xff) == 0xff) {
            parkNanos(MICROSECONDS.toNanos(10));
         }
         else {
            Thread.yield();
         }
      }
      //存到threadList 最多49个
      final var threadLocalList = threadList.get();
      if (threadLocalList.size() < 50) {
         threadLocalList.add(weakThreadLocals ? new WeakReference<>(bagEntry) : bagEntry);
      }
   }

   /**
    * Add a new object to the bag for others to borrow.
    * 将新对象添加到包中，供其他人借用
    * @param bagEntry an object to add to the bag
    */
   public void add(final T bagEntry)
   {
      if (closed) {
         LOGGER.info("ConcurrentBag has been closed, ignoring add()");
         throw new IllegalStateException("ConcurrentBag has been closed, ignoring add()");
      }
      //add 共享列表中
      sharedList.add(bagEntry);

      // spin until a thread takes it or none are waiting
      //如果有线程等待 && bagEntry状态未使用 && bagEntry添加到handoffQueue队列失败 线程yield
      while (waiters.get() > 0 && bagEntry.getState() == STATE_NOT_IN_USE && !handoffQueue.offer(bagEntry)) {
         //避免长期占用
         Thread.yield();
      }
   }

   /**
    * Remove a value from the bag.  This method should only be called
    * with objects obtained by <code>borrow(long, TimeUnit)</code> or <code>reserve(T)</code>
    * 从ConcurrentBag中删除一个值
    * @param bagEntry the value to remove
    * @return true if the entry was removed, false otherwise
    * @throws IllegalStateException if an attempt is made to remove an object
    *         from the bag that was not borrowed or reserved first
    */
   public boolean remove(final T bagEntry)
   {
      //尝试从ConcurrentBag中取出未借用或保留的物品
      if (!bagEntry.compareAndSet(STATE_IN_USE, STATE_REMOVED) && !bagEntry.compareAndSet(STATE_RESERVED, STATE_REMOVED) && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}", bagEntry);
         return false;
      }
      //共享元素中进行移除
      final boolean removed = sharedList.remove(bagEntry);
      if (!removed && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry);
      }
      //本线程本地缓存移除
      threadList.get().remove(bagEntry);

      return removed;
   }

   /**
    * Close the bag to further adds.
    */
   @Override
   public void close()
   {
      closed = true;
   }

   /**
    * This method provides a "snapshot" in time of the BagEntry
    * items in the bag in the specified state.  It does not "lock"
    * or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in list before performing any action on them.
    *
    * @param state one of the {@link IConcurrentBagEntry} states
    * @return a possibly empty list of objects having the state specified
    */
   public List<T> values(final int state)
   {
      final var list = sharedList.stream().filter(e -> e.getState() == state).collect(Collectors.toList());
      Collections.reverse(list);
      return list;
   }

   /**
    * This method provides a "snapshot" in time of the bag items.  It
    * does not "lock" or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in the list, or understand the concurrency implications of
    * modifying items, before performing any action on them.
    *
    * @return a possibly empty list of (all) bag items
    */
   @SuppressWarnings("unchecked")
   public List<T> values()
   {
      return (List<T>) sharedList.clone();
   }

   /**
    * The method is used to make an item in the bag "unavailable" for
    * borrowing.  It is primarily used when wanting to operate on items
    * returned by the <code>values(int)</code> method.  Items that are
    * reserved can be removed from the bag via <code>remove(T)</code>
    * without the need to unreserve them.  Items that are not removed
    * from the bag can be make available for borrowing again by calling
    * the <code>unreserve(T)</code> method.
    *
    * @param bagEntry the item to reserve
    * @return true if the item was able to be reserved, false otherwise
    */
   public boolean reserve(final T bagEntry)
   {
      return bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
   }

   /**
    * This method is used to make an item reserved via <code>reserve(T)</code>
    * available again for borrowing.
    *
    * @param bagEntry the item to unreserve
    */
   @SuppressWarnings("SpellCheckingInspection")
   public void unreserve(final T bagEntry)
   {
      if (bagEntry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
         // spin until a thread takes it or none are waiting
         while (waiters.get() > 0 && !handoffQueue.offer(bagEntry)) {
            Thread.yield();
         }
      }
      else {
         LOGGER.warn("Attempt to relinquish an object to the bag that was not reserved: {}", bagEntry);
      }
   }

   /**
    * Get the number of threads pending (waiting) for an item from the
    * bag to become available.
    *
    * @return the number of threads waiting for items from the bag
    */
   public int getWaitingThreadCount()
   {
      return waiters.get();
   }

   /**
    * Get a count of the number of items in the specified state at the time of this call.
    *
    * @param state the state of the items to count
    * @return a count of how many items in the bag are in the specified state
    */
   public int getCount(final int state)
   {
      var count = 0;
      for (var e : sharedList) {
         if (e.getState() == state) {
            count++;
         }
      }
      return count;
   }

   public int[] getStateCounts()
   {
      final var states = new int[6];
      for (var e : sharedList) {
         ++states[e.getState()];
      }
      states[4] = sharedList.size();
      states[5] = waiters.get();

      return states;
   }

   /**
    * Get the total number of items in the bag.
    *
    * @return the number of items in the bag
    */
   public int size()
   {
      return sharedList.size();
   }

   public void dumpState()
   {
      sharedList.forEach(entry -> LOGGER.info(entry.toString()));
   }

   /**
    * Determine whether to use WeakReferences based on whether there is a
    * custom ClassLoader implementation sitting between this class and the
    * System ClassLoader.
    * 是否使用弱引用
    * @return true if we should use WeakReferences in our ThreadLocals, false otherwise
    */
   private boolean useWeakThreadLocals()
   {
      try {
         if (System.getProperty("com.zaxxer.hikari.useWeakReferences") != null) {   // undocumented manual override of WeakReference behavior
            return Boolean.getBoolean("com.zaxxer.hikari.useWeakReferences");
         }

         return getClass().getClassLoader() != ClassLoader.getSystemClassLoader();
      }
      catch (SecurityException se) {
         return true;
      }
   }
}
