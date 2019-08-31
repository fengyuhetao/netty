/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from the input {@link ByteBuf} and create a new
 * {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a {@link DelimiterBasedFrameDecoder},
 * {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder}, or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing one with
 * {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a complete frame by checking
 * {@link ByteBuf#readableBytes()}. If there are not enough bytes for a complete frame, return without modifying the
 * reader index to allow more bytes to arrive.
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}. One
 * <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}. For example calling
 * <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which is not always the case. Use
 * <tt>in.getInt(in.readerIndex())</tt> instead.
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong> annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer is not released
 * or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)} to avoid leaking
 * memory. 将字节解码成消息
 */
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    /**
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     * 不断使用老的ByteBuf累积，如果空间不够，扩容出新的ByteBuf，继续累积
     * 默认使用
     */
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            try {
                final ByteBuf buffer;
                if (cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes() // 超过空间大小，需要扩容
                    // 引用大于1，说明用户使用了 slice().retain() 或 duplicate().retain() 使refCnt增加并且大于 1
                    // 此时扩容返回一个新的累积区ByteBuf，方便用户对老的累积区ByteBuf进行后续处理。
                    || cumulation.refCnt() > 1
                    || cumulation.isReadOnly()) {   // 只读，不可累加，所以需要改成可写
                    // Expand cumulation (by replace it) when either there is not more room in the buffer
                    // or if the refCnt is greater then 1 which may happen when the user use slice().retain() or
                    // duplicate().retain() or if its read-only.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    // 扩容返回新的ByteBuf
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                } else {
                    buffer = cumulation;
                }
                buffer.writeBytes(in);
                return buffer;
            } finally {
                // We must release in in all cases as otherwise it may produce a leak if writeBytes(...) throw
                // for whatever release (for example because of OutOfMemoryError)
                in.release();
            }
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     * 使用CompositeByteBuf，组合新输入的ByteBuf，避免内存拷贝
     * 好处是，内存零拷贝
     * 坏处是，因为维护复杂索引，所以某些使用场景下，慢于 MERGE_CUMULATOR
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            ByteBuf buffer;
            try {
                if (cumulation.refCnt() > 1) {
                    // Expand cumulation (by replace it) when the refCnt is greater then 1 which may happen when the
                    // user use slice().retain() or duplicate().retain().
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                    buffer.writeBytes(in);
                } else {
                    CompositeByteBuf composite;
                    if (cumulation instanceof CompositeByteBuf) {
                        composite = (CompositeByteBuf)cumulation;
                    } else {
                        composite = alloc.compositeBuffer(Integer.MAX_VALUE);
                        composite.addComponent(true, cumulation);
                    }
                    composite.addComponent(true, in);
                    in = null;
                    buffer = composite;
                }
                return buffer;
            } finally {
                if (in != null) {
                    // We must release if the ownership was not transferred as otherwise it may produce a leak if
                    // writeBytes(...) throw for whatever release (for example because of OutOfMemoryError).
                    in.release();
                }
            }
        }
    };

    private static final byte STATE_INIT = 0;
    private static final byte STATE_CALLING_CHILD_DECODE = 1;
    private static final byte STATE_HANDLER_REMOVED_PENDING = 2;

    // 已经累积的ByteBuf对象
    ByteBuf cumulation;
    private Cumulator cumulator = MERGE_CUMULATOR;
    /**
     * 是否每次只解码一条消息, 默认为false
     * 部分解码器为true, 例如：Socks4ClientDecoder
     */
    private boolean singleDecode;

    /**
     * 是否解码到消息，
     * true: 说明没有解码到消息
     */
    private boolean decodeWasNull;

    /**
     * 是否首次读取，也就是cumulation为空
     */
    private boolean first;
    /**
     * A bitmask where the bits are defined as
     * <ul>
     * <li>{@link #STATE_INIT}</li>
     * <li>{@link #STATE_CALLING_CHILD_DECODE}</li>
     * <li>{@link #STATE_HANDLER_REMOVED_PENDING}</li>
     * </ul>
     * 解码状态
     *
     * 0 - 初始化
     * 1 - 调用 {@link #decode(ChannelHandlerContext, ByteBuf, List)} 方法中，正在进行解码
     * 2 - 准备移除
     */
    private byte decodeState = STATE_INIT;

    /**
     * 读取释放阀值
     */
    private int discardAfterReads = 16;

    /**
     * 已读取次数。
     *
     * 在读取 {@link #discardAfterReads} 次数据后，如果无法全部解码完，则进行释放，避免 OOM
     */
    private int numReads;

    protected ByteToMessageDecoder() {
        // 校验，不可共享
        ensureNotSharable();
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)} call. This
     * may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        if (cumulator == null) {
            throw new NullPointerException("cumulator");
        }
        this.cumulator = cumulator;
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory. The
     * default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        checkPositive(discardAfterReads, "discardAfterReads");
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative buffer of this decoder. You usually do not
     * need to rely on this value to write a decoder. Use it only when you must use it at your own risk. This method is
     * a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually do not need to access the internal buffer
     * directly to write a decoder. Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // 状态处于 STATE_CALLING_CHILD_DECODE 时，标记状态为 STATE_HANDLER_REMOVED_PENDING
        if (decodeState == STATE_CALLING_CHILD_DECODE) {
            decodeState = STATE_HANDLER_REMOVED_PENDING;
            return;        // 返回！！！！结合 `#decodeRemovalReentryProtection(...)` 方法，一起看。
        }
        ByteBuf buf = cumulation;
        if (buf != null) {
            // Directly set this to null so we are sure we not access it in any other method here anymore.
            cumulation = null;
            numReads = 0;
            int readable = buf.readableBytes();
            if (readable > 0) {
                // 读取剩余字节，并释放 buf
                ByteBuf bytes = buf.readBytes(readable);
                buf.release();
                ctx.fireChannelRead(bytes);
                ctx.fireChannelReadComplete();
            } else {
                buf.release();
            }
        }
        // 执行移除逻辑
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {}

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            // 创建 CodecOutputList 对象
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                ByteBuf data = (ByteBuf)msg;
                // 判断是否首次
                first = cumulation == null;
                if (first) {
                    // 如果是首次，直接使用读取的data
                    cumulation = data;
                } else {
                    // 如果不是首次，累积到cumulation中
                    cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
                }
                // 执行解码
                callDecode(ctx, cumulation, out);
            } catch (DecoderException e) {
                throw e;
            } catch (Exception e) {
                // 包装成DecoderException抛出
                throw new DecoderException(e);
            } finally {
                // cumulation中所有数据被读取完，直接释放全部
                if (cumulation != null && !cumulation.isReadable()) {
//                    重置numReads
                    numReads = 0;
                    cumulation.release();
                    cumulation = null;
                } else if (++numReads >= discardAfterReads) { // 读取次数到达上限，释放全部
                    // We did enough reads already try to discard some bytes so we not risk to see a OOME.
                    // See https://github.com/netty/netty/issues/4275
                    numReads = 0;
                    discardSomeReadBytes();
                }

//                解码消息的数量
                int size = out.size();
//                是否解码到消息
                decodeWasNull = !out.insertSinceRecycled();
                // 触发ChannelRead事件，可能有多个消息
                fireChannelRead(ctx, out, size);
//                回收CodeOutputList对象
                out.recycle();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Get {@code numElements} out of the {@link List} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        if (msgs instanceof CodecOutputList) {
            fireChannelRead(ctx, (CodecOutputList)msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * Get {@code numElements} out of the {@link CodecOutputList} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i++) {
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
//        重置
        numReads = 0;
//        释放已读的部分
        discardSomeReadBytes();
        // 未解码到消息，并且未开启自动读取，则再次发起读取，期望读取到更多数据，以便解码到消息
        if (decodeWasNull) {
            decodeWasNull = false;
            if (!ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
        }
        // 触发 Channel ReadComplete 事件到下一个节点
        ctx.fireChannelReadComplete();
    }

    protected final void discardSomeReadBytes() {
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1 as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    // 通道处于未激活( Inactive )，解码完剩余的消息，并释放相关资源.
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

//    处理 ChannelInputShutdownEvent 事件，即 Channel 关闭读取
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            channelInputClosed(ctx, false);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) throws Exception {
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            // 当 Channel 读取关闭时，执行解码剩余消息的逻辑
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                int size = out.size();
                // 触发 Channel Read 事件到下一个节点。可能是多条消息
                fireChannelRead(ctx, out, size);
                // 如果有解码到消息，则触发 Channel ReadComplete 事件到下一个节点。
                if (size > 0) {
                    // Something was read, call fireChannelReadComplete()
                    ctx.fireChannelReadComplete();
                }
                // 如果方法调用来源是 `#channelInactive(...)` ，则触发 Channel Inactive 事件到下一个节点
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                // Recycle in all cases
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
//            执行解码
            callDecode(ctx, cumulation, out);
//            最后一次，执行解码
            decodeLast(ctx, cumulation, out);
        } else {
//            最后一次执行解码
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     *
     * @param ctx
     *            the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in
     *            the {@link ByteBuf} from which to read data
     * @param out
     *            the {@link List} to which decoded messages should be added
     */
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
//            循环读取，知道不可读
            while (in.isReadable()) {
//                记录
                int outSize = out.size();

                // out非空，说明上一次解码有解码到消息
                if (outSize > 0) {
                    // 触发 Channel Read 事件。可能是多条消息
                    fireChannelRead(ctx, out, outSize);
//                    清空
                    out.clear();

                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
//                    用户主动删除该handler,继续操作 in 是不安全的
                    if (ctx.isRemoved()) {
                        break;
                    }
                    outSize = 0;
                }

//                记录当前可读字节数
                int oldInputLength = in.readableBytes();
//                执行解码，如果Handler准备移除，在解码完成后，进行移除
                decodeRemovalReentryProtection(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                // 用户主动删除该handler,继续操作 in 是不安全的
                if (ctx.isRemoved()) {
                    break;
                }
                // 直接判断 `out.size() == 0` 比较合适。因为，如果 `outSize > 0` 那段，已经清理了 out 。
                if (outSize == out.size()) {
//                    如果未读取任何字节，结束循环
                    if (oldInputLength == in.readableBytes()) {
                        break;
                    } else {
                        continue;
                    }
                }

//                如果解码了西消息，但是可读字节数未变，抛出DecoderException,说明有问题
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(StringUtil.simpleClassName(getClass())
                        + ".decode() did not read anything but decoded a message.");
                }

                // 如果开启 singleDecode ，表示只解析一次，结束循环
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input {@link ByteBuf}
     * has nothing to read when return from this method or till nothing was read from the input {@link ByteBuf}.
     *
     * @param ctx
     *            the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in
     *            the {@link ByteBuf} from which to read data
     * @param out
     *            the {@link List} to which decoded messages should be added
     * @throws Exception
     *             is thrown if an error occurs
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input {@link ByteBuf}
     * has nothing to read when return from this method or till nothing was read from the input {@link ByteBuf}.
     *
     * @param ctx
     *            the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in
     *            the {@link ByteBuf} from which to read data
     * @param out
     *            the {@link List} to which decoded messages should be added
     * @throws Exception
     *             is thrown if an error occurs
     */
    final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
        throws Exception {
        decodeState = STATE_CALLING_CHILD_DECODE;
        try {
//            执行解码
            decode(ctx, in, out);
        } finally {
//            如果 Handler 准备移除，在解码完成后，进行移除。
//            判断是否准备移除
//          TODO:  什么时候会出现该情况呢
            boolean removePending = decodeState == STATE_HANDLER_REMOVED_PENDING;
//            设置状态
            decodeState = STATE_INIT;
            if (removePending) {
                handlerRemoved(ctx);
            }
        }
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may override
     * this for some special cleanup operation.
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decodeRemovalReentryProtection(ctx, in, out);
        }
    }

    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf cumulation, int readable) {
        // 记录老的ByteBuf
        ByteBuf oldCumulation = cumulation;
        // 分配新的ByteBuf对象
        cumulation = alloc.buffer(oldCumulation.readableBytes() + readable);
        // 将老的数据写入新的ByteBuf中
        cumulation.writeBytes(oldCumulation);
        // 释放老的ByteBuf
        oldCumulation.release();
        return cumulation;
    }

    /**
     * Cumulate {@link ByteBuf}s. 累加器，将读取到的数据累加到一起，再尝试解码，从而实现拆包
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes. The
         * implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so call
         * {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         *
         * @param alloc
         *            ByteBuf分配器
         * @param cumulation
         *            当前累加结果
         * @param in
         *            当前读取（输入） ByteBuf，如果in过大，会使用alloc进行扩容，再累加
         * @return 新的累加结果
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }
}
