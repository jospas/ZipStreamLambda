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
import com.amazonaws.services.s3.model.ObjectMetadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A processor running on its own thread which polls a
 * linked blocking queue and puts objects into S3 when data is available
 */
public class ZipProcessor implements Runnable
{
    private final int threadId;
    private final AmazonS3 s3;
    private final LinkedBlockingQueue<ZipEntryData> queue;
    private volatile boolean stopping = false;
    private int fileCount = 0;
    private long bytesProcessed = 0L;

    /**
     * CReates a new processor
     * @param threadId the thread index
     * @param s3 the S3 client
     * @param queue the queue to poll
     */
    public ZipProcessor(int threadId, AmazonS3 s3, LinkedBlockingQueue<ZipEntryData> queue)
    {
        this.threadId = threadId;
        this.s3 = s3;
        this.queue = queue;
    }

    /**
     * Read messages form the queue blocking briefly when no data is available
     * and cleanly exiting when requested and there si no data on the queue
     * to process.
     */
    @Override
    public void run()
    {
        while (true)
        {
            try
            {
                ZipEntryData data = queue.poll(100, TimeUnit.MILLISECONDS);

                if (data == null && stopping)
                {
                    break;
                }

                if (data != null)
                {
                    InputStream is = new ByteArrayInputStream(data.getData());
                    ObjectMetadata meta = new ObjectMetadata();
                    meta.setContentLength(data.getData().length);
                    meta.setContentType(data.getMimeType());
                    s3.putObject(data.getBucket(), data.getKey(), is, meta);
                    is.close();

                    fileCount++;
                    bytesProcessed += data.getData().length;
                }

            }
            catch (InterruptedException e)
            {

            }
            catch (IOException io)
            {
                throw new RuntimeException("Failed to process file", io);
            }
        }

        System.out.println(String.format("Thread: %d complete, processed: %d files with total size: %s",
                threadId, fileCount, humanReadableByteCount(bytesProcessed)));
    }

    /**
     * Mark the flag that indicates we should stop when there is no more data
     * on the queue to process
     */
    public void pleaseStop()
    {
        this.stopping = true;
    }

    /**
     * Create a human readable binary byte measurement
     * @param bytes the byte amount
     * @return a formatted string for the requested bytes
     */
    public static String humanReadableByteCount(long bytes)
    {
        int unit = 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        char pre = "KMGTPE".charAt(exp-1);
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
}
