/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2019 All Rights Reserved.
 */
package com.ht;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.ExecutionException;

/**
 * @author ht210178
 * @version : Test, v0.1 2019年07月04日 16:03 ht210178 Exp $
 */
public class Test {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
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
}