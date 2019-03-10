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

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.event.S3EventNotification;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import javax.activation.MimetypesFileTypeMap;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A Lambda that listens for S3 events and unzips files back into S3.
 * Because zip data can be read from S3 faster than it can be written
 * when there are large numbers of files in the zip file this process
 * uses a queue of zip fragments to write and multiple threads to write them.
 */
@SuppressWarnings("unused")
public class ZipStreamLambda implements RequestHandler<S3Event, String>
{
    private static AmazonS3 s3 = null;

    /**
     * Fired by an S3 event, unzips a file in memory and pumps back to S3
     * @param s3Event the event form S3
     * @param context the context of the Lambda execution
     */
    @Override
    public String handleRequest(S3Event s3Event, Context context)
    {
        if (s3 == null)
        {
            s3 = AmazonS3ClientBuilder.standard().build();
        }

        /*
         * Data buffer used when reading from the stream
         */
        byte [] buffer = new byte[1024];

        /*
         * Total files in all zip files
         */
        int totalFiles = 0;

        /*
         * Number of files written after filtering
         */
        int filesWritten = 0;

        /*
         * Total bytes read
         */
        long totalBytesRead = 0L;

        /*
         * Total bytes queued
         */
        long totalBytesQueued = 0L;

        /*
         * Read configuration parameters from the environment
         */
        Set<String> validExtensions = getFileNameExtensions();
        String outputBucket = getOutputBucketName();
        String outputKeyPrefix = getOutputKeyPrefix();
        String outputS3Path = String.format("s3://%s/%s", outputBucket, outputKeyPrefix);

        int threadCount = getThreadCount(10);
        int queueLength = getQueueLength(20);

        /*
         * Queue that can write multiple S3 object concurrently, blocking when full
         * to protect against out of memory errors
         */
        ZipStreamQueue queue = new ZipStreamQueue(threadCount, queueLength, s3);

        /*
         * Reusable output stream for reading content for Zip entries
         */
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try
        {
            /*
             * Process every S3 notification (there is usually only ever one)
             */
            for (S3EventNotification.S3EventNotificationRecord record: s3Event.getRecords())
            {
                String srcBucket = record.getS3().getBucket().getName();

                String srcKey = record.getS3().getObject().getKey().replace('+', ' ');
                srcKey = URLDecoder.decode(srcKey, "UTF-8");

                String inputExtension = FilenameUtils.getExtension(srcKey.toLowerCase());

                if (!StringUtils.equals(inputExtension, "zip"))
                {
                    String message = String.format("Non-zip file detected, skipping: s3://%s/%s", srcBucket, srcKey);
                    System.out.println(message);
                    return message;
                }

                String inputS3Path = String.format("s3://%s/%s", srcBucket, srcKey);

                System.out.println(String.format("Extracting zip file from: %s to: %s", inputS3Path, outputS3Path));

                S3Object s3Object = s3.getObject(new GetObjectRequest(srcBucket, srcKey));
                S3ObjectInputStream s3InputStream = s3Object.getObjectContent();
                ZipInputStream zipStream = new ZipInputStream(s3InputStream);
                ZipEntry entry = zipStream.getNextEntry();

                String outputPath = FilenameUtils.getName(srcKey);
                outputPath = FilenameUtils.removeExtension(outputPath);
                outputPath += "/";

                MimetypesFileTypeMap fileTypeMap = new MimetypesFileTypeMap();

                while (entry != null)
                {
                    if (totalFiles % 1000 == 0)
                    {
                        System.out.println(String.format("Queued: %d of current total: %d files queued: %s of total: %s",
                                filesWritten,
                                totalFiles,
                                ZipProcessor.humanReadableByteCount(totalBytesQueued),
                                ZipProcessor.humanReadableByteCount(totalBytesRead)));
                    }

                    totalFiles++;
                    totalBytesRead += entry.getSize();

                    String fileName = entry.getName();

                    /*
                     * If file extension filtering is enabled skip files that don't match
                     * one of the valid extensions grab the next entry and continue
                     */
                    if (!validExtensions.isEmpty() && !validExtensions.contains(FilenameUtils.getExtension(fileName.toLowerCase())))
                    {
                        entry = zipStream.getNextEntry();
                        continue;
                    }

                    totalBytesQueued += entry.getSize();

                    /*
                     * Grab the mime type of the file for S3 metadata
                     */
                    String mimeType = fileTypeMap.getContentType(fileName);

                    /*
                     * Compute the output path for this file
                     */
                    String outputKey = outputKeyPrefix + outputPath + fileName;

                    int bytesRead;
                    outputStream.reset();

                    /*
                     * Read entry byte data from S3 stream
                     */
                    while ((bytesRead = zipStream.read(buffer)) > 0)
                    {
                        outputStream.write(buffer, 0, bytesRead);
                    }

                    /*
                     * Create a data object that holds the byte payload and where to save it in S3
                     */
                    ZipEntryData data = new ZipEntryData(outputBucket, outputKey, mimeType, outputStream.toByteArray());

                    /*
                     * Queue the data on the queue blocking if the queue is full
                     */
                    queue.put(data);

                    filesWritten++;

                    /*
                     * Read the next entry
                     */
                    entry = zipStream.getNextEntry();
                }

                zipStream.closeEntry();

                /*
                 * Drain S3 Stream
                 */
                while (s3InputStream.read(buffer) > 0)
                {
                    // Discard to end of stream
                }

                zipStream.close();
            }

            /*
             * Ask the queue to stop when no more data is available
             */
            queue.pleaseStop();

            /*
             * Wait for all threads to exit
             */
            queue.waitForShutdown();

            System.out.println(String.format("Successfully unzipped: %d out of total files: %d wrote: %s of total: %s",
                    filesWritten, totalFiles,
                    ZipProcessor.humanReadableByteCount(totalBytesQueued),
                    ZipProcessor.humanReadableByteCount(totalBytesRead)));

            return String.format("Successfully extracted: %d files from zip file", filesWritten);
        }
        catch (Throwable  t)
        {
            System.out.println("Got failure: " + t.toString());
            throw new RuntimeException(t);
        }
    }

    /**
     * Fetches the maximum length of the queue from the environment variable: QUEUE_LENGTH
     * Zip entry reads beyond this will block on queue addition to prevent out of memory
     * exceptions, this parameter needs to be tuned with the expected maximum zip file entry
     * and the total memory allocated to the Lambda function
     * @param defaultQueueLength the default queue length, a good value is 20
     * @return the length of the queue of zip fragments waiting to be written to S3
     */
    private int getQueueLength(int defaultQueueLength)
    {
        String queueLengthString = System.getenv("QUEUE_LENGTH");

        if (StringUtils.isBlank(queueLengthString))
        {
            System.out.println("Using default queue length count: " + defaultQueueLength);
            return defaultQueueLength;
        }

        try
        {
            int threadCount = Integer.parseInt(queueLengthString);
            System.out.println("Using queue length: " + threadCount);
            return threadCount;
        }
        catch (NumberFormatException n)
        {
            System.out.println("Invalid queue length: [" + queueLengthString +
                    "] using default queue length: " + defaultQueueLength);
            return defaultQueueLength;
        }
    }

    /**
     * Fetches the thread count from the environment variable THREAD_COUNT
     * defaulting to defaultThreadCount. This parameter needs to be tuned with the
     * maximum memory allocated to the Lambda as low memory Lambdas get time slices
     * of a single vCPU. It should be approximately half the queue length to ensure
     * adequate read ahead is performed to keep all threads busy.
     * @param defaultThreadCount the default thread count, a good value is 10
     * @return the number of threads to use to write files to S3
     */
    private int getThreadCount(int defaultThreadCount)
    {
        String threadCountString = System.getenv("THREAD_COUNT");

        if (StringUtils.isBlank(threadCountString))
        {
            System.out.println("Using default thread count: " + defaultThreadCount);
            return defaultThreadCount;
        }

        try
        {
            int threadCount = Integer.parseInt(threadCountString);
            System.out.println("Using thread count: " + threadCount);
            return threadCount;
        }
        catch (NumberFormatException n)
        {
            System.out.println("Invalid thread count: [" + threadCountString +
                    "] using default thread count: " + defaultThreadCount);
            return defaultThreadCount;
        }
    }

    /**
     * Fetch the output key prefix from the environment
     * variable OUTPUT_KEY_PREFIX
     * A trailing slash is always appended here if one is not configured.
     * @return the output key prefix
     */
    private String getOutputKeyPrefix()
    {
        String outputKeyPrefix = System.getenv("OUTPUT_KEY_PREFIX");

        if (StringUtils.isBlank(outputKeyPrefix))
        {
            throw new RuntimeException("Environment variable OUTPUT_BUCKET must be set");
        }

        String trimmed = outputKeyPrefix.trim();

        if (!trimmed.endsWith("/"))
        {
            trimmed += "/";
        }

        return trimmed;
    }

    /**
     * Fetch the output bucket name from the environment
     * variable OUTPUT_BUCKET_NAME
     * @return the output bucket name
     */
    private String getOutputBucketName()
    {
        String outputBucketName = System.getenv("OUTPUT_BUCKET_NAME");

        if (StringUtils.isBlank(outputBucketName))
        {
            throw new RuntimeException("Environment variable OUTPUT_BUCKET_NAME must be set");
        }

        return outputBucketName.trim();
    }

    /**
     * Fetches a set of case-insensitive valid file name extensions (no dot) as a
     * comma separated list reading from the environment variable: FILE_EXTENSIONS
     * @return a set of file name extensions (no dot) or an empty set if filtering is disabled
     */
    private Set<String> getFileNameExtensions()
    {
        Set<String> extensions = new TreeSet<>();
        String extensionsString = System.getenv("FILE_EXTENSIONS");

        if (StringUtils.isNotBlank(extensionsString) && !extensionsString.isEmpty())
        {
            String [] splits = extensionsString.split(",");

            for (String split: splits)
            {
                extensions.add(split.toLowerCase().trim());
            }
        }

        if (!extensions.isEmpty())
        {
            System.out.println("Filtering ZIP contents for file name extensions: [" + StringUtils.join(extensions, ", ") + "]");
        }
        else
        {
            System.out.println("Filtering for file name extensions is disabled, extracting all files from zip");
        }

        return extensions;
    }
}

