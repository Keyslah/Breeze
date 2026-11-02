package com.aaron.breeze;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class RecordStorageQueueTest {
    @Test public void queuedMetadataAndCloseCannotOvertakeBlockedCommit()throws Exception{
        RecordStorageQueue queue=new RecordStorageQueue(Runnable::run);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        List<String> events=Collections.synchronizedList(new ArrayList<>());
        try{
            assertTrue(queue.submit(()->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("gate timeout");events.add("commit");return 1;},value->events.add("record complete"),error->events.add("error")));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTrue(queue.submit(()->{events.add("read committed cursor");return 1;},value->events.add("retire "+value),error->events.add("error")));
            queue.close(()->{events.add("close");closed.countDown();});
            assertTrue(events.isEmpty());
            assertFalse(queue.submit(()->2,unused->events.add("unexpected"),error->events.add("error")));
            release.countDown();assertTrue(closed.await(5,TimeUnit.SECONDS));
            assertEquals(List.of("commit","record complete","read committed cursor","retire 1","close"),events);
        }finally{release.countDown();queue.close(()->{});}
    }

    @Test public void failedWriteHasNoSuccessCallbackAndLaterCursorCheckStillRuns()throws Exception{
        RecordStorageQueue queue=new RecordStorageQueue(Runnable::run);
        CountDownLatch closed=new CountDownLatch(1);List<String> events=Collections.synchronizedList(new ArrayList<>());
        try{
            queue.submit(()->{throw new IllegalArgumentException("bad CRC");},unused->events.add("incorrect success"),error->events.add(error.getMessage()));
            queue.submit(()->0,value->events.add("durable cursor "+value),error->events.add("unexpected error"));
            queue.close(closed::countDown);assertTrue(closed.await(5,TimeUnit.SECONDS));
            assertEquals(List.of("bad CRC","durable cursor 0"),events);
        }finally{queue.close(()->{});}
    }
}
