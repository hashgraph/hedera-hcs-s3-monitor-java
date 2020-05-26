/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.hedera.aws.lambda.document.tracking;

import com.hedera.hashgraph.sdk.Client;
import com.hedera.hashgraph.sdk.HederaStatusException;
import com.hedera.hashgraph.sdk.TransactionId;
import com.hedera.hashgraph.sdk.TransactionReceipt;
import com.hedera.hashgraph.sdk.account.AccountId;
import com.hedera.hashgraph.sdk.consensus.ConsensusMessageSubmitTransaction;
import com.hedera.hashgraph.sdk.consensus.ConsensusTopicId;
import com.hedera.hashgraph.sdk.crypto.ed25519.Ed25519PrivateKey;
import com.hedera.hashgraph.sdk.mirror.MirrorClient;
import com.hedera.hashgraph.sdk.mirror.MirrorConsensusTopicQuery;
import io.opencensus.trace.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 *
 * @author User
 */
public class MainTest {
    public static void main (String[] args) throws HederaStatusException, IOException{
        // Grab the OPERATOR_ID and OPERATOR_KEY from the .env file
        AccountId OPERATOR_ID = AccountId.fromString("0.0.20163");
        Ed25519PrivateKey OPERATOR_KEY = Ed25519PrivateKey.fromString("302e020100300506032b65700422042035c4ec39827924dfd0a9352ef77913b235c905d42c3da2757b06600b32c51b5d");
        ConsensusTopicId TOPIC_ID = ConsensusTopicId.fromString("0.0.26220");
        MirrorClient mirrorClient = new MirrorClient("hcs.testnet.mirrornode.hedera.com:5600");
        // Build Hedera testnet client
        Client client = Client.forTestnet();

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
        
         TransactionReceipt receipt = null;
        do {
            TransactionId transactionId = new TransactionId(OPERATOR_ID);
            ConsensusMessageSubmitTransaction setMessage = new ConsensusMessageSubmitTransaction()
                    .setTransactionId(transactionId)
                    .setTopicId(TOPIC_ID)
                    .setMessage("hello, HCS! ");
            TransactionId execute = setMessage.execute(client);
            receipt = execute.getReceipt(client,Duration.ofSeconds(30));  
            
        } while (!receipt.status.toString().endsWith("OK") &&  !receipt.status.toString().endsWith("SUCCESS"));
        
        System.in.read();
        
        
    }
}
