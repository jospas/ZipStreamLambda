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

/**
 * A data object that defines the data and path for a zip file entry
 * to be written back into Amazon S3.
 */
public class ZipEntryData
{
    private final String bucket;
    private final String key;
    private final String mimeType;
    private final byte [] data;

    public ZipEntryData(String bucket, String key, String mimeType, byte [] data)
    {
        this.bucket = bucket;
        this.key = key;
        this.mimeType = mimeType;
        this.data = data;
    }

    public String getBucket()
    {
        return bucket;
    }

    public String getKey()
    {
        return key;
    }

    public String getMimeType()
    {
        return mimeType;
    }

    public byte[] getData()
    {
        return data;
    }
}
