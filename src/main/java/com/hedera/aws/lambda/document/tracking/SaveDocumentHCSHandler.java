package com.hedera.aws.lambda.document.tracking;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import com.amazonaws.services.kms.model.DecryptRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.event.S3EventNotification;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.util.Base64;
import com.amazonaws.util.Md5Utils;
import com.hedera.hashgraph.sdk.Client;
import com.hedera.hashgraph.sdk.HederaNetworkException;
import com.hedera.hashgraph.sdk.HederaStatusException;
import com.hedera.hashgraph.sdk.TransactionId;
import com.hedera.hashgraph.sdk.TransactionReceipt;
import com.hedera.hashgraph.sdk.account.AccountId;
import com.hedera.hashgraph.sdk.consensus.ConsensusMessageSubmitTransaction;
import com.hedera.hashgraph.sdk.consensus.ConsensusTopicId;
import com.hedera.hashgraph.sdk.crypto.ed25519.Ed25519PrivateKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.asn1.cms.RecipientInfo;

public class SaveDocumentHCSHandler implements RequestHandler<S3Event, String> {

    @Override
    public String handleRequest(
            S3Event event, // track s3 events 
            Context ctx) {

        // Pull the events, we only send upload events to this lambda. Do case
        // analysis to track different event types if more types are added.
        S3EventNotification.S3EventNotificationRecord record = event.getRecords().get(0);

        String bucketName = record.getS3().getBucket().getName();
        String srcFileKey = record.getS3().getObject().getKey();
        String region = record.getAwsRegion();
        // Get some debug info 
        System.out.println("Bucket Name is " + bucketName);
        System.out.println("File Path is " + srcFileKey);
        System.out.println("The region is " + region);
        System.out.println("The environment variables are");
        // Avoid using because aws secret key may show up in log
        // for (String k : System.getenv().keySet()){
        //    System.out.println(k + ":" + System.getenv(k));
        //}

        // Grab the OPERATOR_ID and OPERATOR_KEY et al from lambda env
        AccountId OPERATOR_ID = AccountId.fromString(System.getenv("OPERATOR_ID"));
        // operator key is encrypted
        Ed25519PrivateKey OPERATOR_KEY = Ed25519PrivateKey.fromString(this.decryptedOperatorKey());
        ConsensusTopicId TOPIC_ID = ConsensusTopicId.fromString(System.getenv("TOPIC_ID"));
        // Build Hedera client
        Client client = System.getenv("NETWORK").equalsIgnoreCase("MAINNET") ? Client.forMainnet() : Client.forTestnet();
        // Set the operator account ID and operator private key
        client.setOperator(OPERATOR_ID, OPERATOR_KEY);

         // Download the docuemnt from S3 into a stream
        AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                .build();
        S3Object s3Object = s3Client.getObject(new GetObjectRequest(
                bucketName, srcFileKey));
        InputStream is = s3Object.getObjectContent();
        
        
        // Prepare an HCS message and send it to the network
        TransactionReceipt receipt = null;
        TransactionId transactionId = new TransactionId(OPERATOR_ID);

        try {
            String md5AsBase64 = Md5Utils.md5AsBase64(is);
            ConsensusMessageSubmitTransaction setMessage = new ConsensusMessageSubmitTransaction()
                    .setTransactionId(transactionId)
                    .setTopicId(TOPIC_ID)
                    .setMessage(String.format("Bucket Name:%s  File Path: %s  File MD5AsBase64: %s", bucketName, srcFileKey, md5AsBase64));

            TransactionId execute = setMessage.execute(client);
            execute.getReceipt(client);

            
            receipt = transactionId.getReceipt(client);
            for (int i = 0; i <= 100; i++) {
                if (receipt.status.toString().endsWith("SUCCESS")) {
                    s3Client.putObject(
                            bucketName, 
                            srcFileKey.replace("tracked-docs", "tracked-docs-log")+".hcs.txt", 
                            String.format(
                                    "Filename: %s\n"
                                    +  "MD5 checksum (Base64): %s\n" 
                                    + "Transaction-ID: %s\n"
                                    + "Topic:%s \n"
                                    + "Sequence no:%s \n"
                                    + "Running hash: %s \n"
                            , srcFileKey
                            , md5AsBase64
                            , transactionId.toString()
                            , TOPIC_ID
                            , receipt.getConsensusTopicSequenceNumber()
                            , Hex.encodeHexString(receipt.getConsensusTopicRunningHash())
                            
                            )
                    );
                    break;
                } else if (receipt.status.toString().endsWith("OK")) {
                    Thread.sleep(3000L);
                    receipt = transactionId.getReceipt(client);
                } else {
                    System.out.println("Error status: " + receipt.status.toString());
                    return null;
                }
            }
        } catch (HederaNetworkException | HederaStatusException | InterruptedException d) {
            System.out.println("Exception, comms error");
        } catch (IOException ex) {
            System.out.println("Exception: Can not read file to MD5");
        }

        return null;
    }

    // pull the encrypted OPERATOR_KEY and decrypt. 
    private String decryptedOperatorKey() {
        System.out.println("Decrypting key  " + System.getenv("OPERATOR_KEY"));
        byte[] encryptedKey = Base64.decode(System.getenv("OPERATOR_KEY"));
        AWSKMS client = AWSKMSClientBuilder.defaultClient();
        DecryptRequest request = new DecryptRequest()
                .withCiphertextBlob(ByteBuffer.wrap(encryptedKey));
        ByteBuffer plainTextKey = client.decrypt(request).getPlaintext();
        String decrypted = new String(plainTextKey.array(), Charset.forName("UTF-8"));
        return decrypted;
    }

}
