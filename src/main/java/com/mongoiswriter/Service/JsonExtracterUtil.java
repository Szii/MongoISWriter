/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mongoiswriter.Service;


import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.result.InsertOneResult;
import com.mongoiswriter.Enum.ExtracterType;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

/**
 *
 * @author brune
 */
@Service
@EnableAsync
public class JsonExtracterUtil {
       private static final Logger logger = LoggerFactory.getLogger(JsonExtracterUtil.class);
       public static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
       private final MongoDatabase database;
       private final MongoUtils mongoUtils;
       private final StringParser stringParser; 
       private final ObjectMapper objectMapper;
              
       public JsonExtracterUtil(MongoDatabase database,MongoUtils mongoUtils,StringParser stringParser,ObjectMapper objectMapper){
           this.database = database;
           this.mongoUtils = mongoUtils;
           this.stringParser = stringParser;
           this.objectMapper = objectMapper;
       }

    public  void extractFromAddressToMongo(String sourceURL, String collectionName,ExtracterType extracterType, int docNumber)throws MalformedURLException, SocketException {  
        JsonFactory jsonFactory = new JsonFactory();

        // Create MongoDB client
   
            // Get database and collection
            mongoUtils.createCollection(collectionName);
            MongoCollection<Document> collection = database.getCollection(collectionName);
            collection = collection.withWriteConcern(WriteConcern.MAJORITY);                  

            // Open a connection to the URL and get the InputStream
            URL url = new URL(sourceURL);
            try (InputStream urlInputStream = url.openStream();
                 BufferedInputStream bufferedInputStream = new BufferedInputStream(urlInputStream, 8192 * 100); 
                 GZIPInputStream gzipInputStream = new GZIPInputStream(bufferedInputStream);
                 JsonParser jsonParser = jsonFactory.createParser(gzipInputStream)) {

                // Start parsing the JSON content
                JsonToken token = jsonParser.nextToken();

                if (token == JsonToken.START_OBJECT) {
                    // Process the root object
                    processRootObject(jsonParser,collection,extracterType,docNumber);
                } else {
                    throw new IOException("Expected data to start with an Object");
                }
                

                System.out.println("Source size" + mongoUtils.getCollectionSize(collectionName));
                System.out.println("Import completed successfully.");

            } catch (IOException e) {
                System.out.println("Source size" + mongoUtils.getCollectionSize(collectionName));
                System.err.println("IOException occurred: " + e.getMessage());
                mongoUtils.setProcessing(false);
                e.printStackTrace();
                
                throw new SocketException();
            } catch (MongoException me) {
                System.err.println("MongoException occurred: " + me.getMessage());
                me.printStackTrace();
            }


    }

    private void processRootObject(JsonParser jsonParser, MongoCollection<Document> collection,ExtracterType extracterType,int docNumber) throws IOException {
        // Iterate over the fields of the root object
        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = jsonParser.getCurrentName();
            jsonParser.nextToken(); // Move to the value

            if ("položky".equals(fieldName) && jsonParser.currentToken() == JsonToken.START_ARRAY) {
                // Process the "položky" array
                processItemsArray(jsonParser, collection,extracterType,docNumber);
            } else {
                // Skip other fields
                jsonParser.skipChildren();
            }
        }
    }

    private  void processItemsArray(JsonParser jsonParser,MongoCollection<Document> collection,ExtracterType extracterType,int docNumber) throws IOException {
        int processedNumber = 0;
        
        while (jsonParser.nextToken() != JsonToken.END_ARRAY && ((processedNumber < docNumber) && docNumber > 0)) {
            if (jsonParser.currentToken() == JsonToken.START_OBJECT) {
                insertJsonObject(jsonParser, objectMapper, collection,extracterType);
                 System.out.println("Proccessed document number : " + processedNumber);
                 System.out.println("Max doc number is: " + docNumber);
                processedNumber++;
            } else {
                jsonParser.skipChildren();
            }

        }
    }

    private  void insertJsonObject(JsonParser jsonParser, ObjectMapper objectMapper, MongoCollection<Document> collection, ExtracterType extracterType) throws IOException {
        // Read the JSON object into a tree model
        JsonNode jsonNode = objectMapper.readTree(jsonParser);
        
        
        if(extracterType == ExtracterType.TERMIN_NAZEV){
            createIndexes(collection, "termin-id");
        }
        
       if(extracterType == ExtracterType.TERMIN_VAZBA){
            createIndexes(collection, "termin-id");
        }
        
       if(extracterType == ExtracterType.PRAVNI_AKT){
            createIndexes(collection, "znění-dokument-id");
            JsonNode zneniDatumUcinostiDoNode =  jsonNode.get("znění-datum-účinnosti-do");
            JsonNode zneniDatumUcinostiOdNode =  jsonNode.get("znění-datum-účinnosti-od");
            JsonNode metadataDatumZruseniNode =  jsonNode.get("metadata-datum-zrušení");
            LocalDate currentDate = LocalDate.parse(LocalDate.now().toString(),formatter);
            
            if(zneniDatumUcinostiOdNode != null){
                LocalDate zneniDatumUcinostiOd = LocalDate.parse(zneniDatumUcinostiOdNode.asText(),formatter);   
                     if(zneniDatumUcinostiOd.isAfter(currentDate)){
                           //  System.out.println("Skipped document due to not null 'znění-datum-účinnosti-do' field. " + zneniDatumUcinostiDoNode);
                             return;
                         }
            }
            
            if(zneniDatumUcinostiDoNode != null && !zneniDatumUcinostiDoNode.isNull()){
                          LocalDate zneniDatumUcinostiDo = LocalDate.parse(zneniDatumUcinostiDoNode.asText(),formatter);   
                         if(zneniDatumUcinostiDo.isBefore(currentDate)){
                            // System.out.println("Skipped document due to not null 'znění-datum-účinnosti-do' field. " + zneniDatumUcinostiDoNode);
                             return;
                         }

                     }
            
            if(metadataDatumZruseniNode != null && !metadataDatumZruseniNode.isNull()){
                        LocalDate metadataDatumZruseni = LocalDate.parse(metadataDatumZruseniNode.asText(),formatter); 
                          if(metadataDatumZruseni.isBefore(currentDate)){
                            //   System.out.println("Skipped document due to not null 'metadata-datum-zrušení' field." + metadataDatumZruseniNode);
                                return;
                          }
                     }                  
        }
       if(extracterType == ExtracterType.TERMIN_DEFINICE){
           createIndexes(collection, "definice-termínu-id");
           LocalDate currentDate = LocalDate.parse(LocalDate.now().toString(),formatter);
           JsonNode terminPlatnostDoNode =  jsonNode.get("definice-termínu-platnost-vazby-do");
           /*
           if(terminPlatnostDoNode != null && !terminPlatnostDoNode.isNull()){
            LocalDate terminPlatnostDoDate = LocalDate.parse(terminPlatnostDoNode.asText(),formatter);   
            if(terminPlatnostDoDate.isBefore(currentDate)){
                System.out.println("Skipped document due to not null 'znění-datum-účinnosti-do' field. " + terminPlatnostDoNode);
                return;
            }
            JsonNode terminPlatnostOdNode =  jsonNode.get("definice-termínu-platnost-vazby-od");
            if(terminPlatnostOdNode != null && !terminPlatnostOdNode.isNull()){
              LocalDate terminPlatnostOdDate = LocalDate.parse(terminPlatnostOdNode.asText(),formatter); 
            if(terminPlatnostOdDate.isAfter(currentDate)){
                System.out.println("Skipped document due to not null 'znění-datum-účinnosti-do' field. " + terminPlatnostDoNode);
                return;
            }
          }
         }
         */
       }
          

        // Convert the JSON object to a BSON Document
        String jsonString = objectMapper.writeValueAsString(jsonNode);
        Document document = Document.parse(jsonString);
        
           
        if(extracterType == ExtracterType.TERMIN_VAZBA){
            
            Document vazba = new Document();
            int terminID;
            int terminDefiniceID;
            try{
                terminID = stringParser.extractIdAfterLastSlashAsInt(getValueFromField(document,"cvs-termín", "iri"));   
                terminDefiniceID = stringParser.extractIdAfterLastSlashAsInt(getValueFromField(document,"cvs-definice-termínu", "iri"));
            }
            catch (Exception ex){
                return;
            }

            vazba.append("termín-id", terminID);
            vazba.append("definice-termínu-id", terminDefiniceID);
            InsertOneResult result = collection.insertOne(vazba);
        }
        else{
            InsertOneResult result = collection.insertOne(document);
        }

        // Insert the document into MongoDB
        
   }
    
       private static void createIndexes(MongoCollection<Document> collection,String indexName) {
        try {
            // Create an ascending index on "znění-base-id"
            collection.createIndex(Indexes.ascending(indexName));
           // logger.info("Created index on 'znění-base-id'.");
        } catch (Exception e) {
            logger.error("Error creating indexes: {}", e.getMessage());
            throw new RuntimeException("Failed to create indexes.", e);
        }
    }
       
       
   public static String getValueFromField(Document document, String fieldName, String key){
       
         List<Document> cvsTerminList = (List<Document>) document.get(fieldName);
            
            if (cvsTerminList == null || cvsTerminList.isEmpty()) {
                logger.warn("'cvs-termín' field is missing or empty for definice-termínu-id");
                return null;
            }                       
       
          Document cvsTerminDoc = cvsTerminList.get(0);
          return cvsTerminDoc.getString(key);
   }

    
}



