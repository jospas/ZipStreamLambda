## Expanding large ZIP files from S3 using AWS Lambda

Proof of concept low memory streaming processor for extracting
large ZIP files from Amazon S3, using AWS Lambda, exploding filtered contents back into S3.

### Introduction

This project aims to demonstrate how to use Java streams to extract large filtered ZIP files from S3 as fast as possible, using minimal memory and multiple threads from within a Lambda function.

There is a balance between the amount of memory allocated to a Lambda function and the amount of vCPU and network available to the function. Current tests indicate that a minimum of 1G should be allocated to the function to achieve high network throughput and multithreading capabilities.

### Implementation

The system reads ZIP file fragments from an S3 input stream and buffers the raw bytes of these on a LinkedBlockingQueue. A pool of threads is used to push fragments back to S3, and the reader thread will block if the buffer queue is full.

ZIP file contents can optionally be filtered by file name extension, if an extension filter is not provided all ZIP file contents will be extracted.

Thread count and queue size are parameters than can be tuned at deployment time but the assumption here is that the ZIP file to be processed contains many small files not several huge files.

If you have large ZIP files containing large files, you may need to reduce the queue length and increase the memory available to the function.

The input file name minus the extension is appended to OutputKeyPrefix parameter to determine the prefix for output files in the S3 bucket provided in OutputBucket.

### Getting Started

The system uses Apache Maven and is easily configured in IntelliJ by importing
the Maven pom.xml as a new project.

### Building

Build either from within IntelliJ or use Maven:

```bash
mvn package
```

### Tunable Parameters

There are several tunable parameters most with sane defaults (all parameter names are suffixed with 'Parameter')

| Parameter | Default | Validation | Description |
| --- | --- | --- | --- |
| InputBucket | None | Required | The input S3 bucket to listen for ZIP file object creation and process. |
| InputKeyPrefix | "input/" | Required | The input S3 key prefix to listen for ZIP file creation under. |
| OutputBucket | None | Required | The output S3 bucket to write expanded ZIP file contents into. |
| OutputKeyPrefix | "output/" | Required | A prefix key to use to write extracted ZIP file contents into. |
| ThreadCount | 10 | [1 - 20] | The umber of worker threads to use to process the ZIP file entry queue and write to S3. |
| QueueLength | 20 | [1-40] | The length of the blocking queue to hold ZIP file entries before flushing to S3. |
| FileExtensions | "xml" | Optional | Comma separated list of file extensions. If provided, ZIP file contents will be filtered using these case insensitive extensions. If not provided all files are extracted. |

### Installing

Use the AWS CLI to prepare your AWS SAM template (note do not provide a trailing slash for s3-prefix). You will need a bucket in the deployment region to land the deployment assets:

```bash
aws cloudformation package \
    --template-file zipstreamlambda.yaml \
    --output-template-file zipstreamlambda-output.yaml \
    --s3-bucket <s3 bucket> \
    --s3-prefix <s3 key prefix> \
    --profile <profile name> \
    --force-upload
```

For example:

```bash
aws cloudformation package \
    --template-file zipstreamlambda.yaml \
    --output-template-file zipstreamlambda-output.yaml \
    --s3-bucket aws-extractor-ap-southeast-2 \
    --s3-prefix apps/zipstreamlambda/deploy \
    --profile <profile name> \
    --force-upload
```

The deploy your packaged AWS CloudFormation stack with:

```bash
aws cloudformation deploy \
    --template-file zipstreamlambda-output.yaml \
    --profile <profile name> \
    --capabilities CAPABILITY_NAMED_IAM \
    --region <aws region> \
    --stack-name <stack name> \
    --parameter-overrides \
    	InputBucketParameter=<input bucket name> \
    	OutputBucketParameter=<output bucket name>
```

For example:

```bash
aws cloudformation deploy \
    --template-file zipstreamlambda-output.yaml \
    --profile <profile name> \
    --capabilities CAPABILITY_NAMED_IAM \
    --region ap-southeast-2 \
    --stack-name zipstreamlambda \
    --parameter-overrides \
    	InputBucketParameter=aws-zipstreamlambda-input \
    	OutputBucketParameter=aws-zipstreamlambda-output
```

Note: this needs --capabilities CAPABILITY_NAMED_IAM as it creates a role for AWS Lambda.

### Uninstalling

Using the AWS Console, identify and delete the deployed AWS CloudFormation stack.

Or using the AWS CLI:

```bash
aws cloudformation delete-stack \
    --stack-name <stack name> \
    --profile <profile name> \
    --region <aws region>
```

For example:

```bash
aws cloudformation delete-stack \
    --stack-name zipstreamlambda \
    --profile <profile name> \
    --region ap-southeast-2
```

Additionally remove deployed code bundles from S3.

### Authors

**Josh Passenger** AWS Solutions Architect - [jospas@amazon.com](mailto:jospas@amazon.com)

### License

This project is licensed under the Apache 2 License - see the [LICENSE.txt](LICENSE.txt) file for details

### Warranty

No warranty is provided or implied with this software, 
it is provided as a Proof of Concept (POC) example and will require additional error checking code and testing.
