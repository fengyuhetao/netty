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

package io.netty.buffer;

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

//    Recycler处理器，用于回收对象
    private final Recycler.Handle<PooledByteBuf<T>> recyclerHandle;

//    使用 Jemalloc 算法管理内存，而 Chunk 是里面的一种内存块
    protected PoolChunk<T> chunk;

    /**
     * 从 Chunk 对象中分配的内存块所处的位置
     */
    protected long handle;

    /**
     * 内存空间。具体什么样的数据，通过子类设置泛型。具体什么样的数据，通过子类设置泛型( T )。
     * 例如：
     * 1) PooledDirectByteBuf 和 PooledUnsafeDirectByteBuf 为 ByteBuffer ；
     * 2) PooledHeapByteBuf 和 PooledUnsafeHeapByteBuf 为 byte[] 。
     */
    protected T memory;

    /**
     * {@link #memory} 开始位置
     *
     * @see #idx(int)
     */
    protected int offset;

    /**
     * 目前使用 memory 的长度( 大小 )。
     *
     * @see #capacity()
     */
    protected int length;

    /**
     * 占用 {@link #memory} 的大小
     */
    int maxLength;
    PoolThreadCache cache;

    /**
     * 临时 ByteBuffer 对象
     *
     * @see #internalNioBuffer()
     */
    ByteBuffer tmpNioBuf;

    /**
     * ByteBuf 分配器对象
     */
    private ByteBufAllocator allocator;

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Recycler.Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

//    基于 unPoolooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象
    void initUnpooled(PoolChunk<T> chunk, int length) {
        init0(chunk, null, 0, chunk.offset, length, length, null);
    }

    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        // From PoolChunk 对象
        this.chunk = chunk;
        memory = chunk.memory;
        tmpNioBuf = nioBuffer;

        allocator = chunk.arena.parent;
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     * 每次在重用 PooledByteBuf 对象时，需要调用该方法，重置属性。
     */
    final void reuse(int maxCapacity) {
        // 设置最大容量
        maxCapacity(maxCapacity);
        resetRefCnt();
        // 重置读写索引为 0
        setIndex0(0, 0);
        // 重置读写标记位为 0
        discardMarks();
    }

    /**
     * 获得容量，当前容量的值为 length 属性。
     * 但是，要注意的是，maxLength 属性，不是表示最大容量。maxCapacity 属性，才是真正表示最大容量。
     * 那么，maxLength 属性有什么用？表示占用 memory 的最大容量( 而不是 PooledByteBuf 对象的最大容量 )。
     * 在写入数据超过 maxLength 容量时，会进行扩容，但是容量的上限，为 maxCapacity 。
     */
    @Override
    public final int capacity() {
        return length;
    }

    @Override
    public int maxFastWritableBytes() {
        return Math.min(maxLength, maxCapacity()) - writerIndex;
    }

    /**
     * 调整容量大小
     */
    @Override
    public final ByteBuf capacity(int newCapacity) {
        // 校验新的容量，不能超过最大容量
        checkNewCapacity(newCapacity);

        // If the request capacity does not require reallocation, just update the length of the memory.
        // Chunk 内存，非池化
        if (chunk.unpooled) {
            // 相等，无需扩容 / 缩容
//            在 #initUnpooled(PoolChunk<T> chunk, int length) 方法中，我们可以看到，
//            maxLength 和 length 是相等的，所以大于或小于时，需要进行扩容或缩容。
            if (newCapacity == length) {
                return this;
            }
        } else {
            // 扩容
            if (newCapacity > length) {
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
//                缩容
            } else if (newCapacity < length) {
                // 大于 maxLength 的一半，缩容相对不多，无需缩容
                if (newCapacity > maxLength >>> 1) {
                    if (maxLength <= 512) {
                        // 因为 Netty SubPage 最小是 16 ，如果小于等 16 ，无法缩容。
                        if (newCapacity > maxLength - 16) {
                            length = newCapacity;
                            // 设置读写索引，避免超过最大容量
                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                            return this;
                        }
                    } else { // > 512 (i.e. >= 1024)
                        length = newCapacity;
                        // 设置读写索引，避免超过最大容量
                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                        return this;
                    }
                }
            } else {
                // 相等，无需扩容 / 缩容
                return this;
            }
        }

        // Reallocation required.
        // 重新分配新的内存空间，并将数据复制到其中。并且，释放老的内存空间。
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

//    返回字节序为 ByteOrder.BIG_ENDIAN 大端
    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

//    创建池化的 PooledDuplicatedByteBuf.newInstance 对象。
    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    /**
     * 创建池化的 PooledSlicedByteBuf 对象。
     * @return
     */
    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

//    获得临时ByteBuffer对象
//    tmpNioBuf 存在的意义，可见
//    * PooledDirectByteBuf.getBytes()
    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        // 为空，创建临时 ByteBuf 对象
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

//  当引用计数为 0 时，调用该方法，进行内存回收
    @Override
    protected final void deallocate() {
        if (handle >= 0) {
//            重置属性
            final long handle = this.handle;
            this.handle = -1;
            memory = null;
//            释放内存
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);
            tmpNioBuf = null;
            chunk = null;
//            回收对象
            recycle();
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this);
    }

//    获得指定位置在 memory 变量中的位置
    protected final int idx(int index) {
        return offset + index;
    }
}
