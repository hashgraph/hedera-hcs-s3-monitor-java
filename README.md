# Build the code

Build the java code by invoking `mvn install` in the directory where `pom.xml` is located and note the path to the resulting `.jar` file; it will be generated in `target\aws-lambda-document-tracking-1.0-SNAPSHOT.jar`

You will need to have a hedera topic id to install  lambda function. If you don't have one the resulting jar contains a convenience function to generate one in you system console. On non windows machines invoke 

```mvn exec:java -Dexec.mainClass="com.hedera.aws.lambda.document.tracking.CreateTopic"```

on windows machines invoke 

```mvn exec:java -D"exec.mainClass"="com.hedera.aws.lambda.document.tracking.CreateTopic"```

and follow the instructions in the prompt. 

Throughout this documentation, we need to be consistent with the AWS region eg: `us-east-2`.  

# Set up a symmetric encryption key

Before uploading your code, you need to create a encryption key to pass the HH private key securely into the application. For this, go to [https://us-east-2.console.aws.amazon.com/kms/home](https://us-east-2.console.aws.amazon.com/kms/home)
 (note the `us-east-2` region in the URL and substitute appropriately). Create a new `symmetric` key and name it. Once created, click on the key alias and note  the key `ARN`. It should look similar to this format: `arn:aws:kms:us-east-2:xxxxxxxxx:key/xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxx`

# Create a user role
You will need a role that has access to the `s3 bucket`.
Head over to [https://console.aws.amazon.com/iam/home#/roles/](https://console.aws.amazon.com/iam/home#/roles/) and select a role; click on the role name and click on `Attach Policies`.  

Attach `AmazonS3FullAccess` to give full access to that role

> :warning: **Make sure you know what these policies do on a production environment**: Be very careful here!

# Create a lambda function

 Head over to [https://us-east-2.console.aws.amazon.com/lambda/](https://us-east-2.console.aws.amazon.com/lambda/) make sure the region matches  and create a new lambda function with name `hcsOnS3FileUpload`. 

In the *Function code* section select `Upload a zip or jar file` with  `Runtime: Java11`. In the `Handler` field type `com.hedera.aws.lambda.document.tracking.SaveDocumentHCSHandler` Then upload `target\aws-lambda-document-tracking-1.0-SNAPSHOT.jar`

In the *environment variables* section click on `Manage Environment Variables`
and add

```
- OPERATOR_ID     | 0.0.12345    # your HH Account ID
- OPERATOR_KEY    | 302ea12...   # your HH private key
- TOPIC_ID        | 0.0.23456    # your HH topic
- NETWORK         | TESTNET      # or MAINNET
```

Then in *Encryption configuration* tick `Enable helpers for encryption in transit`. This will add new buttons next to the environment variables you have just created: click `Encrypt` on the `OPERATOR_KEY` variable only and then paste your ARN key from the section above into the resulting popup. While the popup is still open, expand the tree item: `Execution role policy` and copy the resulting javascript - you will need it later.

Save the popup and make sure that the environment variable has been encrypted in the resulting screen. 

In the card *Basic Settings* increase the `timeout` to `25 seconds` - on average decrypting sending and receiving feedback may take up 20 seconds. 
Make sure you select "Use a customer master key" in the radio menu and paste the kms ARN you created in the step above. It should look similar to this `arn:aws:kms:us-east-2:7xxxxxx:key/xxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxx`

In the same card, select the role you defined in the previous section. There will be a link that will allow you to edit that role. Click on it and click through this sequence: `Attach policies > Create Policy > Json` On the resulting JSon editor you will see

```{
{
    "Version": "2012-10-17",
    "Statement": []
} 
```

Paste the policy that you copied into your clip-board by replacing the above ;  review, you can ignore the warning,  and save it.

Come back to the lambda function and save it if you have not done so already.

# Create an s3 bucket and enable tracking

 Head over to [https://s3.console.aws.amazon.com/s3/home](https://s3.console.aws.amazon.com/s3/home) and create or select the bucket where HCS tracking will take place. Make sure the region matches with the region of the lambda function and decryption key. Add two folders to the bucket and name the first one 

 ```tracked-docs``` 

 and the second one 

 ```tracked-docs-log``` . 

 Click `Properties` of the bucket  (second tab at the top of the screen) and locate the `Events` card and click the [+] symbol to `add notification`. Tick the `All objects create events` and type `tracked-docs/` in the prefix section. Select `lambda function` in the *Send To* section and the resulting drop-down should list `hcsOnS3FileUpload`. 

# Testing

You can send test events from within the lambda function administration console by configuring a test-event.  Paste the following script in the test event configurator whre you substiture `us-east-2`, `my-bucket-name` and `test.pdf` with values from your own s3 bucket:
```
{
  "Records": [
    {
      "eventVersion": "2.0",
      "eventSource": "aws:s3",
      "awsRegion": "us-east-2",
      "eventTime": "1970-01-01T00:00:00.000Z",
      "eventName": "ObjectCreated:Put",
      "userIdentity": {
        "principalId": "EXAMPLE"
      },
      "requestParameters": {
        "sourceIPAddress": "127.0.0.1"
      },
      "responseElements": {
        "x-amz-request-id": "EXAMPLE123456789",
        "x-amz-id-2": "EXAMPLE123/5678abcdefghijklambdaisawesome/mnopqrstuvwxyzABCDEFGH"
      },
      "s3": {
        "s3SchemaVersion": "1.0",
        "configurationId": "testConfigRule",
        "bucket": {
          "name": "hh-nik-east",         
          "ownerIdentity": {
            "principalId": "EXAMPLE"
          },
          "arn": "arn:aws:s3:::my-bucket-name"
        },
        "object": {
          "key": "tracked-docs/test.pdf",
          "size": 1024,
          "eTag": "0123456789abcdef0123456789abcdef",
          "sequencer": "0A1B2C3D4E5F678901"
        }
      }
    }
  ]
}
```

## Tracking documents

Upload a file in the `tracked-docs` folder and the HCS output will appear in `tracked-docs-log`. For instance if you upload `abc.pdf` the log folder will contain a text file named `abc.pdf.hcs.txt`. You can access that log file via the object url ```https://[bucket-name].s3.[region].amazonaws.com/tracked-docs-log/abc.pdf.hcs.txt```

Additionally, you will also find a file named `abc.pdf.hcs.json` and one named `abc.pdf.hcs.html`. The latter is a HTML formatted output of the same data.   

If required, edit the permissions of both folders in `s3` so that the public can have read access to the contents. 

You can serve the html file directly from amazon s3 by clicking on the properties tab of the bucket and enable static file web serving under the relevant card. 

If nothing shows up after the timeout period (25 seconds) then some error might have occurred; delete the file and retry. 
