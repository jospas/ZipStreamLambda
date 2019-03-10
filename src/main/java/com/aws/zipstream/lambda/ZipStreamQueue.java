/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.aws.zipstream.lambda;

import com.amazonaws.services.s3.AmazonS3;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A queue that uses multiple threads to write to S3, blocking when full
 * to prevent out of memory situations when the writer threads can't keep
 * up with the reader therad.
 */
public class ZipStreamQueue
{
    private final LinkedBlockingQueue<ZipEntryData> queue;
    private final int threadCount;
    private final int queueLength;
    private final ArrayList<Thread> threads = new ArrayList<>();
    private final ArrayList<ZipProcessor> processors = new ArrayList<>();

    public ZipStreamQueue(int threadCount, int queueLength, AmazonS3 s3)
    {
        this.threadCount = threadCount;
        this.queueLength = queueLength;

        this.queue = new LinkedBlockingQueue<>(this.queueLength);

        for (int i = 0; i < this.threadCount; ++i)
        {
            ZipProcessor processor = new ZipProcessor(i, s3, queue);
            processors.add(processor);
            Thread thread = new Thread(processor);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
    }

    /**
     * Politely ask all processors to stop processing when the queue is empty
     */
    public void pleaseStop()
    {
        System.out.println("Queue stop requested");
        for (ZipProcessor processor: processors)
        {
            processor.pleaseStop();
        }
    }

    /**
     * Wait until all threads have stopped
     * @throws InterruptedException thrown on failure to join
     */
    public void waitForShutdown() throws InterruptedException
    {
        System.out.println("Waiting for threads to finish");
        for (Thread t: threads)
        {
            t.join();
        }
        System.out.println("All worked threads are finished");
    }

    /**
     * Puts a data item on the queue blocking if the queue is full
     * @param data the data to put
     * @throws InterruptedException thrown if interrupted while waiting
     */
    public void put(ZipEntryData data) throws InterruptedException
    {
        queue.put(data);
    }
}
