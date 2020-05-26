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
import com.hedera.hashgraph.sdk.mirror.MirrorClient;
import com.hedera.hashgraph.sdk.mirror.MirrorConsensusTopicQuery;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class SaveDocumentHCSHandler  implements RequestHandler<S3Event, String> {
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
        for (String k : System.getenv().keySet()){
            System.out.println(k + ":" + System.getenv(k));
        }
        
        // Grab the OPERATOR_ID and OPERATOR_KEY et al from lambda env
        AccountId OPERATOR_ID = AccountId.fromString(System.getenv("OPERATOR_ID"));
        // operator key is encrypted
        Ed25519PrivateKey OPERATOR_KEY = Ed25519PrivateKey.fromString(this.decryptedOperatorKey());
        ConsensusTopicId TOPIC_ID = ConsensusTopicId.fromString(System.getenv("TOPIC_ID"));
        MirrorClient mirrorClient = new MirrorClient(System.getenv("MIRROR_CLIENT"));
        // Build Hedera client
        Client client = System.getenv("NETWORK").equalsIgnoreCase("MAINNET") ? Client.forMainnet() : Client.forTestnet();
        // Set the operator account ID and operator private key
        client.setOperator(OPERATOR_ID, OPERATOR_KEY);
        
        new MirrorConsensusTopicQuery()
            .setTopicId(TOPIC_ID)
            .subscribe(mirrorClient, 
                    resp -> {
                        String messageAsString = new String(resp.message, StandardCharsets.UTF_8);
                        System.out.println(resp.consensusTimestamp + " received topic message: " + messageAsString);
                    },
                    // On gRPC error, print the stack trace
                    Throwable::printStackTrace
        );
        
        try{
            TransactionReceipt receipt = null;
            do {
                System.out.println("Sending to HCS  ...");
                TransactionId transactionId = new TransactionId(OPERATOR_ID);
                ConsensusMessageSubmitTransaction setMessage = new ConsensusMessageSubmitTransaction()
                        .setTransactionId(transactionId)
                        .setTopicId(TOPIC_ID)
                        .setMessage(String.format( "Bucket Name:%s File Path: %s", bucketName, filePath));
                TransactionId execute = setMessage.execute(client);
                receipt = execute.getReceipt(client,Duration.ofSeconds(12));  

            } while (!receipt.status.toString().endsWith("OK") &&  !receipt.status.toString().endsWith("SUCCESS"));
        } catch (HederaNetworkException | HederaStatusException e){
            System.out.println("Comms error");
        }
        return null;
    }
    
    // pull the encrypted OPERATOR_KEY and decrypt. 
     private  String decryptedOperatorKey() {
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
