package com.hedera.aws.lambda.document.tracking;

/*-
 * ‌
 * hcs-sxc-java
 * ​
 * Copyright (C) 2019 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.hashgraph.sdk.Client;
import com.hedera.hashgraph.sdk.TransactionId;
import com.hedera.hashgraph.sdk.TransactionReceipt;
import com.hedera.hashgraph.sdk.account.AccountId;
import com.hedera.hashgraph.sdk.consensus.ConsensusTopicCreateTransaction;
import com.hedera.hashgraph.sdk.consensus.ConsensusTopicId;
import com.hedera.hashgraph.sdk.crypto.ed25519.Ed25519PrivateKey;
import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Map;

public class CreateTopic {

    public static void main(String[] args) throws Exception {
        
        Console console = System.console();
        if (console == null) {
            System.out.println("Couldn't get Console instance - this tool does not work from inside IDE consoles");
            System.exit(0);
        }
        console.printf("Is the topic for Main Net (M) or test net (T): Type M or T\n");
        
        //java.io.BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        
      
        
        
        String NETWORK = console.readLine();
        Client client = null;
        switch (NETWORK.toUpperCase()){
            case "T": client = Client.forTestnet(); break;
            case "M": client = Client.forMainnet(); break;
            default: System.out.print("Wrong input"); System.exit(0);
        }
        
        System.out.println("Type yur operator account id in the following format: 0.0.xxx ");
        String s_operatorId = console.readLine();
        AccountId operatorId = AccountId.fromString(s_operatorId);
        
        System.out.println("Paste your operator private key and press enter (input hidden): 302...");
        String s_operatorKey = new String(console.readPassword());
        Ed25519PrivateKey operatorKey = Ed25519PrivateKey.fromString(s_operatorKey.trim());
        
        
        client.setOperator(
            operatorId,
            operatorKey
        );
    
        ConsensusTopicCreateTransaction tx = new ConsensusTopicCreateTransaction();
    
        TransactionId txId = tx.execute(client);
        TransactionReceipt receipt = txId.getReceipt(client, Duration.ofSeconds(30));
        
        for (int i = 0; i<=100; i++){
            if(receipt.status.toString().endsWith("SUCCESS")) {
                break;
            } else  if (receipt.status.toString().endsWith("OK"))  {
                Thread.sleep(3000L);
                receipt = txId.getReceipt(client);
            } else {
                System.out.println("Error status: " + receipt.status.toString());
                System.exit(0);
            }
        }
        
        ConsensusTopicId consensusTopicId = receipt.getConsensusTopicId();
        
        System.out.println("Your new topic id is: "+consensusTopicId.toString());
        System.exit(0);
       
    }

   
}
