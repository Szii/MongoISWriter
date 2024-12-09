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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.InsertOneResult;
import com.mongoiswriter.Configuration.MongoConfig;
import com.mongoiswriter.Enum.ExtracterType;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
       private final MongoConfig mongoConfig;
       
       private Set<Integer> uniqueIds;
              
       public JsonExtracterUtil(MongoDatabase database,MongoUtils mongoUtils,StringParser stringParser,ObjectMapper objectMapper, MongoConfig mongoConfig){
           this.database = database;
           this.mongoUtils = mongoUtils;
           this.stringParser = stringParser;
           this.objectMapper = objectMapper;
           this.mongoConfig = mongoConfig;
       }

    public void extractFromAddressToMongo(String sourceURL, String collectionName, ExtracterType extracterType)
            throws MalformedURLException, SocketException, IOException {
        JsonFactory jsonFactory = new JsonFactory();
        mongoUtils.createCollection(collectionName);
        MongoCollection<Document> collection = database.getCollection(collectionName);

        // Set write concern to something less strict if acceptable
        collection = collection.withWriteConcern(WriteConcern.ACKNOWLEDGED);

        uniqueIds = mongoUtils.getListOfUniqueZnenDokumentIDsForCollection(
            mongoUtils.getMongoCollection(mongoConfig.MONGO_COLLECTION_AKTY_FINAL)
        );

        URL url = new URL(sourceURL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(6000000); 
        connection.setReadTimeout(60000000); 
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Connection", "keep-alive");
        connection.setRequestProperty("Range", "bytes=0-");

        try (InputStream urlInputStream = connection.getInputStream();
             BufferedInputStream bufferedInputStream = new BufferedInputStream(urlInputStream, 100 * 1024);
             GZIPInputStream gzipInputStream = new GZIPInputStream(bufferedInputStream);
             JsonParser jsonParser = jsonFactory.createParser(gzipInputStream)) {

            JsonToken token = jsonParser.nextToken();

            if (token == JsonToken.START_OBJECT) {
                processRootObject(jsonParser, collection, extracterType);
            } else {
                throw new IOException("Expected data to start with an Object");
            }

            // Create indexes AFTER data insertion for better performance
            if (extracterType == ExtracterType.PRAVNI_AKT_VAZBA) {
                collection.createIndex(Indexes.ascending("znění-cíl-dokument-id"));
                collection.createIndex(Indexes.ascending("znění-fragment-zdroj.znění-dokument-id"));
            } else if (extracterType == ExtracterType.PRAVNI_AKT) {
                collection.createIndex(Indexes.ascending("znění-dokument-id"));
            }

            System.out.println("Source size: " + mongoUtils.getCollectionSize(collectionName));
            System.out.println("Import completed successfully.");
        } catch (IOException e) {
            System.out.println("Source size: " + mongoUtils.getCollectionSize(collectionName));
            System.err.println("IOException occurred: " + e.getMessage());
            mongoUtils.setProcessing(false);
            e.printStackTrace();
            throw new SocketException();
        } catch (MongoException me) {
            System.err.println("MongoException occurred: " + me.getMessage());
            me.printStackTrace();
        }
    }

    private void processRootObject(JsonParser jsonParser, MongoCollection<Document> collection, ExtracterType extracterType) throws IOException {
        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = jsonParser.getCurrentName();
            jsonParser.nextToken(); // Move to the value

            if ("položky".equals(fieldName) && jsonParser.currentToken() == JsonToken.START_ARRAY) {
                processItemsArray(jsonParser, collection, extracterType);
            } else {
                jsonParser.skipChildren();
            }
        }
    }

    private void processItemsArray(JsonParser jsonParser, MongoCollection<Document> collection, ExtracterType extracterType) throws IOException {
        int processedNumber = 0;
        int batchSize = 1000; // adjust as needed
        List<Document> batch = new ArrayList<>();

        while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
            if (jsonParser.currentToken() == JsonToken.START_OBJECT) {
                Document doc = parseSingleDocument(jsonParser, objectMapper, extracterType, uniqueIds);
                if (doc != null) {
                    batch.add(doc);
                }

                processedNumber++;

                // Insert batch every 1000 documents (or a size you choose)
                if (batch.size() >= batchSize) {
                    collection.insertMany(batch);
                    batch.clear();
                    System.out.println("Inserted " + processedNumber + " documents so far...");
                }
            } else {
                jsonParser.skipChildren();
            }
        }

        // Insert any remaining documents
        if (!batch.isEmpty()) {
            collection.insertMany(batch);
            System.out.println("Final insert of remaining " + batch.size() + " documents.");
        }

        System.out.println("Processed total documents: " + processedNumber);
    }
    
    
    

    private Document parseSingleDocument(JsonParser jsonParser, ObjectMapper objectMapper, ExtracterType extracterType, Set<Integer> uniqueIds) throws IOException {
       // Read the JSON object into a tree model
       JsonNode jsonNode = objectMapper.readTree(jsonParser);

       // Filtering logic based on ExtracterType
       if (extracterType == ExtracterType.TERMIN_NAZEV) {
           // Moved index creation outside
       }

       if (extracterType == ExtracterType.TERMIN_VAZBA) {
           // Moved index creation outside
       }

       if (extracterType == ExtracterType.PRAVNI_AKT) {
           JsonNode zneniDatumUcinostiDoNode = jsonNode.get("znění-datum-účinnosti-do");
           JsonNode zneniDatumUcinostiOdNode = jsonNode.get("znění-datum-účinnosti-od");
           JsonNode metadataDatumZruseniNode = jsonNode.get("metadata-datum-zrušení");
           LocalDate currentDate = LocalDate.now();

           if (zneniDatumUcinostiOdNode != null) {
               LocalDate zneniDatumUcinostiOd = LocalDate.parse(zneniDatumUcinostiOdNode.asText(), formatter);
               if (zneniDatumUcinostiOd.isAfter(currentDate)) {
                   // Skip this document
                   return null;
               }
           }

           if (zneniDatumUcinostiDoNode != null && !zneniDatumUcinostiDoNode.isNull()) {
               LocalDate zneniDatumUcinostiDo = LocalDate.parse(zneniDatumUcinostiDoNode.asText(), formatter);
               if (zneniDatumUcinostiDo.isBefore(currentDate)) {
                   // Skip this document
                   return null;
               }
           }

           if (metadataDatumZruseniNode != null && !metadataDatumZruseniNode.isNull()) {
               LocalDate metadataDatumZruseni = LocalDate.parse(metadataDatumZruseniNode.asText(), formatter);
               if (metadataDatumZruseni.isBefore(currentDate)) {
                   // Skip this document
                   return null;
               }
           }

           // Additional PRAVNI_AKT logic if needed...

       } else if (extracterType == ExtracterType.TERMIN_DEFINICE) {
           // Moved index creation outside
           LocalDate currentDate = LocalDate.now();
           JsonNode terminPlatnostDoNode = jsonNode.get("definice-termínu-platnost-vazby-do");
           // Filtering logic if needed...
       }

       if (extracterType == ExtracterType.PRAVNI_AKT_VAZBA) {
           JsonNode zneniCilDokumentID = jsonNode.get("znění-cíl-dokument-id");
           JsonNode firstFragment = jsonNode.path("znění-fragment-cíl").path(0);
           JsonNode firstZneniDokumentID = firstFragment.path("znění-dokument-id");
           
           
           // If both IDs match, skip
           if (zneniCilDokumentID.asInt() == firstZneniDokumentID.asInt()) {
               return null;
           }

           // Ensure both IDs are in uniqueIds
           if (!uniqueIds.contains(zneniCilDokumentID.asInt()) || !uniqueIds.contains(firstZneniDokumentID.asInt())) {
               // Skip
               return null;
           }
       }

       // Convert the JSON object to a BSON Document
       String jsonString = objectMapper.writeValueAsString(jsonNode);
       Document document = Document.parse(jsonString);

       if (extracterType == ExtracterType.TERMIN_VAZBA) {
           // Extract and return a specialized document
           Document vazba = new Document();
           int terminID;
           int terminDefiniceID;
           try {
               terminID = stringParser.extractIdAfterLastSlashAsInt(getValueFromField(document, "cvs-termín", "iri"));
               terminDefiniceID = stringParser.extractIdAfterLastSlashAsInt(getValueFromField(document, "cvs-definice-termínu", "iri"));
           } catch (Exception ex) {
               return null; // Skip if we can’t extract IDs
           }
           vazba.append("termín-id", terminID);
           vazba.append("definice-termínu-id", terminDefiniceID);
           return vazba;
       }

       return document; // Return the prepared document
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



