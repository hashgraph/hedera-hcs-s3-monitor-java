package com.hedera.aws.lambda.document.tracking;

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
import com.amazonaws.services.s3.model.ObjectMetadata;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import javax.xml.bind.DatatypeConverter;
import org.apache.commons.codec.binary.Hex;




public class SaveDocumentHCSHandler implements RequestHandler<S3Event, String> 

{

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
            String md5 = DatatypeConverter.printHexBinary(Md5Utils.computeMD5Hash(is)).toUpperCase();
            
            ConsensusMessageSubmitTransaction setMessage = new ConsensusMessageSubmitTransaction()
                    .setTransactionId(transactionId)
                    .setTopicId(TOPIC_ID)
                    .setMessage(String.format("Bucket Name:%s  File Path: %s  File MD5: %s", bucketName, srcFileKey, md5));

            TransactionId execute = setMessage.execute(client);
            execute.getReceipt(client);

            
            receipt = transactionId.getReceipt(client);
            for (int i = 0; i <= 100; i++) {
                if (receipt.status.toString().endsWith("SUCCESS")) {
                    // create a text file copy
                    s3Client.putObject(
                            bucketName, 
                            srcFileKey.replace("tracked-docs", "tracked-docs-log")+".hcs.txt", 
                            String.format(
                                      "Filename: %s\n"
                                    + "MD5 checksum: %s\n" 
                                    + "Transaction-ID: %s\n"
                                    + "Topic:%s \n"
                                    + "Sequence no:%s \n"
                                    + "Running hash: %s \n"
                                    + (System.getenv("NETWORK").equalsIgnoreCase("MAINNET") ? "Link: https://ledger.hashlog.io/tx/%s" :"Link: https://ledger-testnet.hashlog.io/tx/%s") 
                            , srcFileKey
                            , md5
                            , transactionId.toString()
                            , TOPIC_ID
                            , receipt.getConsensusTopicSequenceNumber()
                            , Hex.encodeHexString(receipt.getConsensusTopicRunningHash())
                            , transactionId.toString()
                            )
                    );
                    // create a json copy
                    s3Client.putObject(
                            bucketName, 
                            srcFileKey.replace("tracked-docs", "tracked-docs-log")+".hcs.json", 
                            String.format(
                                      "{\n\tfilename:\"%s\",\n"
                                    + "\tmd5:\"%s\",\n" 
                                    + "\ttransaction-id:\"%s\",\n"
                                    + "\ttopic:\"%s\",\n"
                                    + "\tsequence-no:\"%s\",\n"
                                    + "\trunning-hash:\"%s\",\n"
                                    + (System.getenv("NETWORK").equalsIgnoreCase("MAINNET") ? "\tlink:\"https://ledger.hashlog.io/tx/%s\"" :"\tLink:\"https://ledger-testnet.hashlog.io/tx/%s\"") 
                                    +"\n}"
                            , srcFileKey
                            , md5
                            , transactionId.toString()
                            , TOPIC_ID
                            , receipt.getConsensusTopicSequenceNumber()
                            , Hex.encodeHexString(receipt.getConsensusTopicRunningHash())
                            , transactionId.toString()
                            )
                    );
                    // create a html file copy
                    ObjectMetadata metadata = new ObjectMetadata();
                    metadata.setContentType("text/html");
                    s3Client.putObject(
                            bucketName, 
                            
                            srcFileKey.replace("tracked-docs", "tracked-docs-log")+".hcs.html", 
                            new ByteArrayInputStream(
                                this.getHTMLTemplate()
                                    .replace("{0}", srcFileKey)
                                    .replace("{1}", md5)
                                    .replace("{2}", transactionId.toString())
                                    .replace("{3}", TOPIC_ID.toString())
                                    .replace("{4}", receipt.getConsensusTopicSequenceNumber()+"")
                                    .replace("{5}", Hex.encodeHexString(receipt.getConsensusTopicRunningHash()))
                                    .replace("{6}", (System.getenv("NETWORK").equalsIgnoreCase("MAINNET") ? "https://ledger.hashlog.io/tx/"+transactionId.toString() :"https://ledger-testnet.hashlog.io/tx/"+transactionId.toString())  )
                                    .getBytes()
                            ),
                            metadata
                            
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
            d.printStackTrace();
            throw new RuntimeException("Exception, comms error");
        } catch (IOException ex) {
            System.out.println("Exception: Can not read file to MD5");
            ex.printStackTrace();
            throw new RuntimeException("Exception: Can not read file to MD5");
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

    private String getHTMLTemplate(){
        return "<!DOCTYPE html>\n<html><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
        "<style>\n" +
        ".title {\n" +
        "white-space: nowrap;\n" +
        "text-transform: uppercase;\n" +
        "text-align: right;\n" +
        "font-family: Styrene A Web, Helvetica Neue, sans-serif;\n" +
        "font-feature-settings: normal;\n" +
        "font-kerning: auto;\n" +
        "font-language-override: normal;\n" +
        "font-optical-sizing: auto;\n" +
        "font-size: 14px;\n" +
        "font-size-adjust: none;\n" +
        "font-stretch: 100%;\n" +
        "font-style: normal;\n" +
        "font-variant: normal;\n" +
        "font-variant-alternates: normal;\n" +
        "font-variant-caps: normal;\n" +
        "font-variant-east-asian: normal;\n" +
        "font-variant-ligatures: normal;\n" +
        "font-variant-numeric: normal;\n" +
        "font-variant-position: normal;\n" +
        "font-variation-settings: normal;\n" +
        "font-weight: 400;\n" +
        "letter-spacing: 2.8px;\n" +
        "line-height: 18.2px;\n" +
        "color:white;\n" +
        "padding: 44px;\n" +
        "}\n" +
        "\n" +
        ".data {\n" +
        "color:white;\n" +
        "text-align: center;\n" +
        "font-family: Styrene A Web, Helvetica Neue, sans-serif;\n" +
        "font-feature-settings: normal;\n" +
        "font-kerning: auto;\n" +
        "font-language-override: normal;\n" +
        "font-optical-sizing: auto;\n" +
        "font-size: 14px;\n" +
        "font-size-adjust: none;\n" +
        "font-stretch: 100%;\n" +
        "font-style: normal;\n" +
        "font-variant: normal;\n" +
        "font-variant-alternates: normal;\n" +
        "font-variant-caps: normal;\n" +
        "font-variant-east-asian: normal;\n" +
        "font-variant-ligatures: normal;\n" +
        "font-variant-numeric: normal;\n" +
        "font-variant-position: normal;\n" +
        "font-variation-settings: normal;\n" +
        "font-weight: 400;\n" +
        "letter-spacing: 2.8px;\n" +
        "line-height: 18.2px;\n" +
        "padding: 44px;\n" +
        "}\n" +
        "\n" +
        "td{\n" +
        "border-top:   1pt dotted white;\n" +
        "border-right: 1px dotted white;\n" +
        "}\n" +
        "\n" +
        "\n" +
        "\n" +
        "</style>\n" +
        "<body style=\"background: #222;\">\n" +
        "	<div style=\"overflow-x:auto;\"><table>\n" +
        "		<tr><td class=\"title\">Filename</td><td class=\"data\">{0}</td></tr>\n" +
        "		<tr><td class=\"title\">MD5 hash</td><td class=\"data\">{1}</td></tr>\n" +
        "		<tr><td class=\"title\">Transaction-ID</td> <td class=\"data\">{2}</td></tr>\n" +
        "		<tr><td class=\"title\">Topic</td> <td class=\"data\">{3}</td></tr> \n" +
        "		<tr><td class=\"title\">Sequence no</td><td class=\"data\">{4}</td></tr> \n" +
        "		<tr><td class=\"title\">Running hash</td><td class=\"data\" style=\"word-wrap: all; word-break:break-all; \">{5}</td></tr>\n" +
        "		<tr><td class=\"title\">Link</td><td class=\"data\"><a href=\"{6}\">{6}</a></td></tr>\n" +
        "	</table></div>\n" +
        "</body>\n" +
        "</html>";
    }
    
}
