/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2019 All Rights Reserved.
 */
package com.ht;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * @author ht210178
 * @version : Test, v0.1 2019年07月04日 16:03 ht210178 Exp $
 */
public class Test {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
//        String vmName = SystemPropertyUtil.get("java.vm.name");
//        System.out.println(vmName);
//        testSoftReference();
        testVirtualReference();
    }

    public static void testFuture() throws ExecutionException, InterruptedException {
        final DefaultEventExecutor executor = new DefaultEventExecutor();
        Promise<Integer> promise = executor.newPromise();
        promise.addListener(new GenericFutureListener<Future<? super Integer>>() {
            public void operationComplete(Future<? super Integer> future) throws Exception {
                System.out.println("promise is finish");
            }
        });
        System.out.println("after promise");
        promise.trySuccess(10);
        System.out.println("after set promise");
        System.out.println(promise.get());
    }

    public static void testNewSet() {
        // create map
        Map<String, Boolean> map = new HashMap<>(16);

        // create a set from map
        Set<String> set = Collections.newSetFromMap(map);

        map.put("go", false);
        // add values in set
        set.add("Java");
        set.add("C");
        set.add("C++");

        // set and map values are
        System.out.println("Set is: " + set);
        System.out.println("Map is: " + map);
    }

    public static void testStrongReference() throws InterruptedException {
        //定义一个数组，给它分配2M内存,这个数组是拥有一个强引用b的，所以猜测即便是要发生OOM了,GC还是不会回收它
        byte[] b = new byte[2 * 1024 * 1024];

        //定义一个ArrayList<Object>对象，我们不断扩大它的空间，这样总的堆空间就会越来越少
        //我们来看看是否会为了扩list而将b指向的对象所用的空间回收（在java中，所有数组都是Object的子类）
        ArrayList<Object> list = new ArrayList<>(0);
        //不断的增加这个数组列表的空间，直到发生OOM错误
        for (int i = 1; i < 1000; i++) {
            if (i % 100 == 0) {
                System.out.println("目前i为" + i + ":调用一次GC");
                System.gc();
                Thread.sleep(1000);
                if (b == null) {
                    System.out.println("b指向的内存空间已经被回收了！");
                } else {
                    System.out.println("b指向的内存空间没有被回收");
                }
            }
            list.ensureCapacity(i * 1000);
        }
    }

    public static void testSoftReference() throws InterruptedException {
        //定义一个数组，给它分配2M内存
        byte[] b = new byte[2*1024*1024];


        //定义一个引用队列
        ReferenceQueue<byte[]> refQueue = new ReferenceQueue<byte[]>();
        //定义一个软引用对象，并保存b引用
        SoftReference<byte[]> sref = new SoftReference(b, refQueue);

        //关键：在之前new byte[2*1024*1024];有两个引用指向它，一个强引用b,和我们定义的软引用sref
        //现在我们将强引用置空，看看有什么变化
        b = null;

        //定义一个ArrayList<Object>对象，我们不断扩大它的空间，这样总的堆空间就会越来越少
        //我们来看看是否会为了扩list而将b指向的对象所用的空间回收，并且可以看看回收的位置在哪里
        ArrayList<Object> list =  new ArrayList<>(0);
        //不断的增加这个数组列表的空间，直到发生OOM错误
        for(int i=1;i<1000;i++){
            if(i%100==0){
                System.out.println("目前i为"+i+":调用一次GC");
                System.gc();
                Thread.sleep(1000);
                if(sref.get()==null){
                    System.out.println("b指向的内存空间已经被回收了！");
                }else{
                    System.out.println("由于软引用的存在，b的内类空间还没有被回收");
                }
                System.out.println(refQueue.poll()==null?"refQueue为空":"refQueue不为空");
            }
            list.ensureCapacity(i*1000);
        }
    }

    public static void testWeakReference() throws InterruptedException {
        //定义一个数组，给它分配2M内存
        byte[] b = new byte[2 * 1024 * 1024];

        //定义一个引用队列
        ReferenceQueue<byte[]> refQueue = new ReferenceQueue<byte[]>();
        //定义一个弱引用对象，并保存b引用
        WeakReference<byte[]> wref = new WeakReference<byte[]>(b,refQueue);

        //关键：在之前new byte[2*1024*1024];有两个引用指向它，一个强引用b,和我们定义的弱引用wref
        //现在我们将强引用置空，看看有什么变化
        b = null;

        //先看是否能从弱引用获得对象的引用
        if(wref.get()!=null){
            System.out.println("还可以通过弱引用访问b指向的对象的空间\n\n");
        }

        //定义一个ArrayList<Object>对象，我们不断扩大它的空间，这样总的堆空间就会越来越少
        //我们来看看是否会为了扩list而将b指向的对象所用的空间回收，并且可以看看回收的位置在哪里
        ArrayList<Object> list =  new ArrayList<>(0);
        //不断的增加这个数组列表的空间，直到发生OOM错误
        for(int i=1;i<1000;i++){
            if(i%100==0){
                System.out.println("目前i为"+i+":调用一次GC");
                System.gc();
                Thread.sleep(1000);
                if(wref.get()==null){
                    System.out.println("b指向的内存空间已经被回收了,因为弱引用并不能延迟对该对象的GC！");
                    System.out.println(refQueue.poll()==null ? "refQueue为空" : "refQueue不为空");
                }
            }
            list.ensureCapacity(i*1000);
        }
    }

    public static void testVirtualReference() throws InterruptedException {
        //定义一个数组，给它分配2M内存
        byte[] b = new byte[2*1024*1024];
        b[0] = 21;


        //定义一个引用队列
        ReferenceQueue<byte[]> refQueue = new ReferenceQueue<>();
        //定义一个虚引用对象，并保存b引用
        PhantomReference<byte[]> pref = new PhantomReference<>(b,refQueue);

        //关键：在之前new byte[2*1024*1024];有两个引用指向它，一个强引用b,和我们定义的虚引用pref
        //现在我们将强引用置空，看看有什么变化
        b = null;


        //先看在无强引用的情况下是否能从虚引用获得对象的引用
        //注：在软引用和弱引用中是可以获得的
        if(pref.get()!=null){
            System.out.println("还可以通过虚引用访问b指向的对象的空间\n\n");
        }else{
            System.out.println("无法通过虚引用访问b指向的对象的空间\n\n");
        }

        //定义一个ArrayList<Object>对象，我们不断扩大它的空间，这样总的堆空间就会越来越少
        //我们来看看是否会为了扩list而将b指向的对象所用的空间回收，并且可以看看回收的位置在哪里
        ArrayList<Object> list =  new ArrayList<>(0);
        //不断的增加这个数组列表的空间，直到发生OOM错误
        for(int i=1;i<500;i++){
            if(i%100==0){
                System.out.println("目前i为"+i+":调用一次GC");
                System.gc();
                Thread.sleep(1000);
                if(pref.get()==null){
                    System.out.println("b指向的内存空间已经被回收了,因为虚引用并不能延迟对该对象的GC！");
                }
                //注意，此时pRef所保存的引用指向的空间，并没有被GC回收，在我们显式地调用refQueue.poll返回pRef之后
                //当GC第二次发现虚引用，而此时JVM将pRef插入到refQueue会插入失败，此时GC才会对obj进行回收
                //注：本段注释来自：https://blog.csdn.net/aitangyong/article/details/39453365
                if(refQueue.poll()!=null) {
                    System.out.println("由于回收了虚引用保存的引用指向的内存空间，所以将虚引用放入到了引用列表中，我们可以因此来做一些事情" +
                                       "比如现在，我们知道虚引用保存的引用指向的内存空间已经挂了");
                }
                //当这个队列不为空后，我们知道pRef保存的引用指向的空间已经被回收了，我们可以因此来做一些事情
            }
            list.ensureCapacity(i*1000);
        }
    }
}