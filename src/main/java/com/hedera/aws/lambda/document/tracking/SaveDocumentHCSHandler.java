package com.hedera.aws.lambda.document.tracking;

import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import com.amazonaws.services.kms.model.DecryptRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.event.S3EventNotification;
import com.amazonaws.util.Base64;
import com.hedera.hashgraph.sdk.Client;
import com.hedera.hashgraph.sdk.HederaNetworkException;
import com.hedera.hashgraph.sdk.HederaStatusException;
import com.hedera.hashgraph.sdk.TransactionId;
import com.hedera.hashgraph.sdk.TransactionReceipt;
import com.hedera.hashgraph.sdk.account.AccountId;
import com.hedera.hashgraph.sdk.consensus.ConsensusMessageSubmitTransaction;
import com.hedera.hashgraph.sdk.consensus.ConsensusTopicId;
import com.hedera.hashgraph.sdk.crypto.ed25519.Ed25519PrivateKey;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class SaveDocumentHCSHandler implements RequestHandler<S3Event, String> {

    @Override
    public String handleRequest(
            S3Event event, // track s3 events 
            Context ctx) {

        // Pull the events, we only send upload events to this lambda. Do case
        // analysis to track different event types if more types are added.
        S3EventNotification.S3EventNotificationRecord record = event.getRecords().get(0);

        String bucketName = record.getS3().getBucket().getName();
        String filePath = record.getS3().getObject().getKey();

        // Get some debug info 
        System.out.println("Bucket Name is " + bucketName);
        System.out.println("File Path is " + filePath);
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

        TransactionReceipt receipt = null;
        TransactionId transactionId = new TransactionId(OPERATOR_ID);
        ConsensusMessageSubmitTransaction setMessage = new ConsensusMessageSubmitTransaction()
                .setTransactionId(transactionId)
                .setTopicId(TOPIC_ID)
                .setMessage(String.format("Bucket Name:%s File Path: %s", bucketName, filePath));

        try {
            TransactionId execute = setMessage.execute(client);
            execute.getReceipt(client);

            
            receipt = transactionId.getReceipt(client);
            for (int i = 0; i <= 100; i++) {
                if (receipt.status.toString().endsWith("SUCCESS")) {
                    break;
                } else if (receipt.status.toString().endsWith("OK")) {
                    Thread.sleep(3000L);
                    receipt = transactionId.getReceipt(client);
                } else {
                    System.out.println("Error status: " + receipt.status.toString());
                    System.exit(0);
                }
            }
        } catch (HederaNetworkException | HederaStatusException | InterruptedException d) {
            System.out.println("Exception, comms error");
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
