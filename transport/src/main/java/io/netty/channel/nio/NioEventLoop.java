/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a {@link Selector} and so does
 * the multi-plexing of these in the event loop.
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * 是否禁用 SelectionKey 的优化，默认禁用优化
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
        SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    /**
     * 少于该 N 值，不开启空轮询重建新的 Selector 对象的功能
     */
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;

    /**
     * NIO Selector 空轮询该 N 次后，重建新的 Selector 对象，默认512
     */
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {

        /**
         * 返回当前 Channel 新增的 IO 就绪事件的数量
         * @return
         * @throws Exception
         */
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}. 包装过的，优化过的selector
     */
    private Selector selector;

    /**
     * 原始的selector
     */
    private Selector unwrappedSelector;

    /**
     * 注册的 SelectionKey 集合。Netty 自己实现，经过优化。
     */
    private SelectedSelectionKeySet selectedKeys;

    /**
     * SelectorProvider 对象，用于创建 Selector 对象
     */
    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should break out of its selection process. In our
     * case we use a timeout for the select method and the select method will block for that time unless waken up.
     *
     * 唤醒标记。因为唤醒方法 {@link Selector#wakeup()} 开销比较大，通过该标识，减少调用。
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    /**
     * select 策略
     */
    private final SelectStrategy selectStrategy;

    /**
     * 处理 Channel 的就绪的 IO 事件，占处理任务的总时间的比例
     */
    private volatile int ioRatio = 50;

    /*
     * 取消 SelectionKey 的数量
     */
    private int cancelledKeys;

    /**
     * 是否需要再次 select Selector 对象
     */
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
        SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        provider = selectorProvider;

        /**
         * 创建 Selector 对象
         */
        final SelectorTuple selectorTuple = openSelector();
        // 一个selector和一个NioEventLoop做唯一的绑定
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    private static final class SelectorTuple {
        // java原生的NIO selector
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    // 创建selector
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            // 调用原生的SelectorProvider，开启selector
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        // 禁用 SelectionKey 的优化，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector。
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        // 如果开启key_set优化
        // 获得SelectImpl
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                        "sun.nio.ch.SelectorImpl",
                        false,
                        PlatformDependent.getSystemClassLoader());           // 成功，则返回该类
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        // 获得 SelectorImpl 类失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;

        // 创建 SelectedSelectionKeySet 对象
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 通过反射获取selectedKeys字段
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    // 通过反射获取publicSelectedKeys字段
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                            PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 的 Field 中
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;  // 失败，则返回该异常
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        // 如果设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }

        // 设置 SelectedSelectionKeySet 对象到 selectedKeys 中
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
            new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
            : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector} of
     * this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will be
     * executed by this event loop when the {@link SelectableChannel} is ready.
     *
     * 注册 Java NIO Channel ( 不一定是通过 Netty 创建的 Channel )到 Selector 上，相当于说，也注册到了 EventLoop 上。
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is {@code
     * 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work around the
     * infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key : oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    @Override
    protected void run() {
        for (; ; ) {
            try {
                try {
                    switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                        // 默认是线下，不存在该情况
                        case SelectStrategy.CONTINUE:
                            continue;

                        case SelectStrategy.BUSY_WAIT:
                            // fall-through to SELECT since the busy-wait is not supported with NIO

                        case SelectStrategy.SELECT:
                            // 重置 wakenUp 标记为 false
                            // 选择( 查询 )任务，轮询IO事件
                            select(wakenUp.getAndSet(false));

                            // 'wakenUp.compareAndSet(false, true)' is always evaluated
                            // before calling 'selector.wakeup()' to reduce the wake-up
                            // overhead. (Selector.wakeup() is an expensive operation.)
                            //
                            // However, there is a race condition in this approach.
                            // The race condition is triggered when 'wakenUp' is set to
                            // true too early.
                            //
                            // 'wakenUp' is set to true too early if:
                            // 1) Selector is waken up between 'wakenUp.set(false)' and
                            //    'selector.select(...)'. (BAD)
                            // 2) Selector is waken up between 'selector.select(...)' and
                            //    'if (wakenUp.get()) { ... }'. (OK)
                            //
                            // In the first case, 'wakenUp' is set to true and the
                            // following 'selector.select(...)' will wake up immediately.
                            // Until 'wakenUp' is set to false again in the next round,
                            // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                            // any attempt to wake up the Selector will fail, too, causing
                            // the following 'selector.select(...)' call to block
                            // unnecessarily.
                            //
                            // To fix this problem, we wake up the selector again if wakenUp
                            // is true immediately after selector.select(...).
                            // It is inefficient in that it wakes up the selector for both
                            // the first case (BAD - wake-up required) and the second case
                            // (OK - no wake-up required).

                            /**
                             * 1. 在 wakenUp.getAndSet(false) 和 #select(boolean oldWakeUp) 之间，在标识 wakeUp 设置为 false 时，
                             * 在 #select(boolean oldWakeUp) 方法中，正在调用 Selector#select(...) 方法，处于阻塞中。
                             * 2. 此时，有另外的线程调用了 #wakeup() 方法，会将标记 wakeUp 设置为 true ，并唤醒 Selector#select(...)
                             * 方法的阻塞等待。
                             * 3. 标识 wakeUp 为 true ，所以再有另外的线程调用 #wakeup() 方法，都无法唤醒 Selector#select(...) 。
                             * 为什么呢？因为 #wakeup() 的 CAS 修改 false => true 会失败，导致无法调用 Selector#wakeup() 方法。
                             * 解决方式：所以在 #select(boolean oldWakeUp) 执行完后，增加了4行来解决。
                             */

                            if (wakenUp.get()) {
                                selector.wakeup();
                            }
                            // fall through
                        default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    handleLoopException(e);
                    continue;
                }

                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;

                // 不限制时间处理io事件
                if (ioRatio == 100) {
                    try {
                        // 处理 Channel 感兴趣的就绪 IO 事件
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // 运行所有普通任务和定时任务，不限制时间
                        // Ensure we always run tasks.
                        runAllTasks();
                    }
                } else {
                    final long ioStartTime = System.nanoTime();
                    try {
                        // 处理 Channel 感兴趣的就绪 IO 事件
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        final long ioTime = System.nanoTime() - ioStartTime;
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                if (isShuttingDown()) {
                    closeAll();
                    // 确认已经关闭
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        // 防止可能导致过度CPU消耗的连续即时故障。
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        // 不为空，采用优化后的selectedKeys
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    /**
     * 原生的selectedKeys是一个set集合
     *
     * @param selectedKeys
     */
    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (; ; ) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            final Object a = k.attachment();

            // 处理一个 Channel 就绪的 IO 事件
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                // 使用 NioTask 处理一个 Channel 就绪的 IO 事件
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            // 获得就绪的 IO 事件的 ops
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                // 移除对OP_CONNECT 感兴趣
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                // 完成连接
                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
//            先处理处理OP_WRITE事件，这样我们可以写一些数据，然后清空内存
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
//                写入数据
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            // readyOps == 0 是对 JDK Bug 的处理，防止空的死循环
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
//            发生异常，关闭channel
            unsafe.close(unsafe.voidPromise());
        }
    }

    /**
     * NIOTask： 自定义Nio事件的处理接口，该类并未被实现过，且在项目中基本不会使用
     *
     * @param k
     * @param task
     */
    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
                case 0:
                    k.cancel();
                    invokeChannelUnregistered(task, k, null);
                    break;
                case 1:
                    if (!k.isValid()) { // Cancelled by channelReady()
                        invokeChannelUnregistered(task, k, null);
                    }
                    break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k : keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch : channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        try {
            /**
             * TODO 不理解
             * 该方法会使用我们对 Selector 的唤醒，所以需要进行复原。
             */
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            /*
             * 注意，如果有其它线程调用了 #wakeup() 方法，
             * 但当前没有线程阻塞在 #select() 方法上，下个调用 #select() 方法的线程会立即被唤醒
             */
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    private void select(boolean oldWakenUp) throws IOException {
        // 获得使用的 Selector 对象，不需要每次访问使用 volatile 修饰的 selector 属性。
        Selector selector = this.selector;
        try {
            // select 计算select空轮询的次数，用于空轮询检测
            int selectCnt = 0;
            long currentTimeNanos = System.nanoTime();
            // 设置deadline
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);

            for (; ; ) {
                // 计算本次 select 的超时时长，单位：毫秒。
                // + 500000L 是为了四舍五入
                // / 1000000L 是为了纳秒转为毫秒
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                // 如果超时时长，则结束 select
                if (timeoutMillis <= 0) {
                    // 首次select，执行一次selectNow
                    if (selectCnt == 0) {
                        // 非阻塞select
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                // 若有新的任务加入
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    selector.selectNow();
                    // 重置计数器
                    selectCnt = 1;
                    break;
                }

                // 阻塞 select ，查询 Channel 是否有就绪的 IO 事件
                int selectedKeys = selector.select(timeoutMillis);
                selectCnt++;

                // 结束 select ，如果满足下面任一一个条件
                if (selectedKeys != 0     // 轮询到IO事件
                    || oldWakenUp         // 已经被唤醒
                    || wakenUp.get()      // 被唤醒
                    || hasTasks()         // 定时任务里边有任务
                    || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }

                // 线程被打断。一般情况下不会出现，出现基本是 bug ，或者错误使用。
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                            "Thread.currentThread().interrupt() was called. Use " +
                            "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                long time = System.nanoTime();
                // 符合 select 超时条件，重置 selectCnt 为 1
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                    selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // 说明没有阻塞立即返回，则可能是触发空轮询bug
                    // 不符合 select 超时的提交，若 select 次数到达重建 Selector 对象的上限，进行重建，很有可能触发了空轮询的bug
                    // The code exists in an extra method to ensure the method is not too big to inline as this
                    // branch is not very likely to get hit very frequently.
                    selector = selectRebuildSelector(selectCnt);
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                        selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                    selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        // The selector returned prematurely many times in a row.
        // Rebuild the selector to work around the problem.
        logger.warn(
            "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
            selectCnt, selector);

        // 重建selector对象
        rebuildSelector();
        Selector selector = this.selector;

        // Select again to populate selectedKeys.
        // 立即select一次，非阻塞
        selector.selectNow();
        return selector;
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
