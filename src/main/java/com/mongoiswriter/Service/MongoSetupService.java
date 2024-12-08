/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mongoiswriter.Service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.mongodb.client.result.UpdateResult;
import com.mongoiswriter.Configuration.DataSourceConfig;
import com.mongoiswriter.Configuration.MongoConfig;
import com.mongoiswriter.Configuration.ProcessConfig;
import com.mongoiswriter.Enum.VztazenyTermin;
import com.mongoiswriter.Model.Segments;
import okhttp3.*;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketException;
import java.security.cert.CertificateException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;


@Service
@EnableAsync
public class MongoSetupService {
    private static final Logger logger = LoggerFactory.getLogger(MongoSetupService.class);
    
    private static final OkHttpClient HTTP_CLIENT = createUnsafeOkHttpClient();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    private static final ConcurrentMap<Integer, String> PDF_ID_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, String> DOCX_ID_CACHE = new ConcurrentHashMap<>();

    private static final int MAX_FETCH_ATTEMPTS = 3;
    private static final int INITIAL_DELAY_MILLIS = 500;
    
  
    private final MongoUtils mongoUtils;
    private final StringParser stringParser;
    private final SegmentsExtractionUtil segmentsExtractionUtil;
    private final DocumentFetcher documentFetcher;
    private final MongoConfig mongoConfig;
    private final ProcessConfig processingConfig;
    private final DataSourceConfig dataSourceConfig;
    
    public MongoSetupService(MongoUtils mongoUtils, StringParser stringParser, SegmentsExtractionUtil segmentsExtractionUtil, DocumentFetcher documentFetcher, MongoConfig mongoConfig, ProcessConfig processingConfig,DataSourceConfig dataSourceConfig) {
        this.mongoUtils = mongoUtils;
        this.stringParser = stringParser;
        this.segmentsExtractionUtil = segmentsExtractionUtil;
        this.documentFetcher = documentFetcher ; 
        this.mongoConfig = mongoConfig;
        this.processingConfig = processingConfig;
        this.dataSourceConfig = dataSourceConfig;
    }
    
    public void setupMongo() throws MalformedURLException, SocketException {
        
            mongoUtils.setProcessing(true);
            try{
            documentFetcher.fetchDocuments();
            }
            catch(SocketException e){
                throw new SocketException("Socket exception. Cannot open socket");
            }
            catch(MalformedURLException e){
                throw new MalformedURLException("URL exception. URL is not correct");
            }
            mongoUtils.createCollection(mongoConfig.MONGO_COLLECTION_TERMINY_FINAL);
            mongoUtils.createCollection(mongoConfig.MONGO_COLLECTION_AKTY_FINAL);
            
                     
         //   MongoCollection<Document> terminyBaseCollection = mongoUtils.getMongoCollection(mongoConfig.MONGO_COLLECTION_TERMINY_BASE);
          //  MongoCollection<Document> terminyPopisCollection = mongoUtils.getMongoCollection(mongoConfig.MONGO_COLLECTION_TERMINY_POPIS);
         //   MongoCollection<Document> terminyVazbaCollection = mongoUtils.getMongoCollection(mongoConfig.MONGO_COLLECTION_TERMINY_VAZBA);
            MongoCollection<Document> terminyProcessedCollection = mongoUtils.getMongoCollection(mongoConfig.MONGO_COLLECTION_TERMINY_FINAL);
            
            MongoCollection<Document> zneniCollection = mongoUtils.getMongoCollection(mongoConfig.MONGO_COLLECTION_AKTY_FINAL);
            MongoCollection<Document> zneniPravniAktCollection = mongoUtils.getMongoCollection(mongoConfig.MONGO_COLLECTION_AKTY_ZNENI);
    
            transferNewestDocuments(zneniPravniAktCollection,zneniCollection);
            
         //  processTerminDocuments(terminyProcessedCollection,terminyBaseCollection,terminyVazbaCollection,terminyPopisCollection); 
         
          //  processZneniDocuments(zneniCollection,zneniPravniAktCollection,terminyProcessedCollection); 
            mongoUtils.setProcessing(false);
        
    }

    
    private void processTerminDocuments(MongoCollection<Document> targetCollection, 
            MongoCollection<Document> terminBaseCollection,MongoCollection<Document> terminVazbaCollection,
            MongoCollection<Document> terminDefiniceCollection){
     ExecutorService executorService = Executors.newFixedThreadPool(processingConfig.THREAD_NUMBER);

        try{

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            try (MongoCursor<Document> cursor = terminVazbaCollection.find().iterator()) {
                while (cursor.hasNext()) {
                    Document doc = cursor.next();

                     CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                            processTerminDocument(doc, targetCollection,terminBaseCollection , terminDefiniceCollection), executorService);
                    futures.add(future);
                    
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        } catch (Exception e) {
            logger.error("An error occurred: {}", e.getMessage());
        } finally {
            executorService.shutdown();
        }
    }
    
    public void startTransfer(Set<Integer> uniqueIds,MongoCollection<Document> sourceCollection, MongoCollection<Document> targetCollection) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(processingConfig.THREAD_NUMBER);
        uniqueIds.forEach(dokumentId -> {
            executor.submit(() -> processDocument(dokumentId,sourceCollection,targetCollection));
        });
        executor.shutdown();
        executor.awaitTermination(6, TimeUnit.HOURS);
    }
        
    private void processDocument(Integer dokumentId,MongoCollection<Document> sourceCollection, MongoCollection<Document> targetCollection) {
        Document doc = sourceCollection.find(Filters.eq("znění-base-id", dokumentId))
                .sort(Sorts.descending("znění-datum-účinnosti-od"))
                .first();

        if (doc != null) {
            Integer zneniDokumentId = getInteger(doc, "znění-dokument-id");
            Integer zneniBaseId = getInteger(doc, "znění-base-id");
            String aktNazevVyhlasen = doc.getString("akt-název-vyhlášený");
            String cisEsbTypZneniPolozka = doc.getString("cis-esb-typ-znění-položka");
            String zneniDatumUcinnostiOdStr = doc.getString("znění-datum-účinnosti-od");
            String aktCitace = doc.getString("akt-citace");
            Segments sbirka = segmentsExtractionUtil.extractSegments(doc.getString("akt-iri"));

            Document typDoc = (Document) doc.get("cis-esb-podtyp-právní-akt");

            String podTypAktu = null;
            if (typDoc != null && typDoc.getString("iri") != null) {
                podTypAktu = stringParser.extractAfterLastSlashAsString(typDoc.getString("iri"));
            }
            List<VztazenyTermin> vztazeneTerminy = null;

            String pdfId = fetchIdWithRetry(zneniDokumentId, "PDF");
            String docxId = fetchIdWithRetry(zneniDokumentId, "DOCX");

            Document newDoc = createZneniDocument(zneniDokumentId, zneniBaseId, aktNazevVyhlasen,
                    cisEsbTypZneniPolozka, zneniDatumUcinnostiOdStr, pdfId, docxId,
                    sbirka.getTypSbirky(), podTypAktu, sbirka.getCisloAktu(), aktCitace, vztazeneTerminy);
        
            targetCollection.insertOne(newDoc);
        }
    }

    
     public void transferNewestDocuments(MongoCollection<Document> sourceCollection, MongoCollection<Document> targetCollection) {
        Set<Integer> uniqueIds = new HashSet<>();
        try (MongoCursor<Document> cursor = sourceCollection.find().iterator()) {
            int idCount = 0;
            while (cursor.hasNext() && (idCount <= dataSourceConfig.DOCUMENTS_NUMBER)) {
                Document doc = cursor.next();
                Integer dokumentId = doc.getInteger("znění-base-id");
                if (dokumentId != null) {
                    uniqueIds.add(dokumentId);
                    idCount++;
                }
            }
        }
        try {
            startTransfer(uniqueIds,sourceCollection,targetCollection);
        } catch (InterruptedException ex) {
            java.util.logging.Logger.getLogger(MongoSetupService.class.getName()).log(Level.SEVERE, null, ex);
        }                        
    }

        private void processTerminDocument(Document docVazba,
                                       MongoCollection<Document> targetCollection,
                                       MongoCollection<Document> terminBaseCollection, MongoCollection<Document> terminDefiniceCollection
                                        ) {
        try {
            Integer terminID = getInteger(docVazba, "termín-id");
            Integer terminDefiniceID = getInteger(docVazba, "definice-termínu-id");
            logger.info("termín-id = " + terminID);
            logger.info("definice-termínu-id = " + terminDefiniceID);
           Document terminBase = mongoUtils.findInCollection(terminBaseCollection, "termín-id", terminID);
           Document terminDefinice = mongoUtils.findInCollection(terminDefiniceCollection, "definice-termínu-id", terminDefiniceID);
          
            
            Document newDoc = createVazbaDocument(terminID, terminDefiniceID,
                    terminBase.get("termín-název").toString(),terminDefinice.get("definice-termínu-text").toString(),
                    getZneniDokumentIdsById(terminDefinice,terminDefiniceID,"definice-termínu-vazba","právní-akt-znění-fragment"));
            
            
            // Insert the new document
            targetCollection.insertOne(newDoc);
            logger.info("Inserted new document with termin-id {}.", terminID);
        } catch (Exception e) {
            logger.error("Failed to process document: {}", e.getMessage());
        }
    }
        
        
   public List<VztazenyTermin> getListOfTermsForDocument(Document doc, MongoCollection<Document> targetCollection, String keyName){
       List<VztazenyTermin> vztazeneTerminy = new ArrayList();
       FindIterable<Document> result = targetCollection.find(
                Filters.elemMatch("vztazene-dokumenty", Filters.eq("znění-dokument-id", doc.get(keyName)))
        );
       
       for (Document foundDoc : result){
           VztazenyTermin termin = new VztazenyTermin(foundDoc.get("termin-nazev").toString(),
                   stringParser.extractFormattedText(foundDoc.get("termin-definice").toString()));
           vztazeneTerminy.add(termin);
       }
       
     return vztazeneTerminy;
       
   }
        
   public List<Integer> getZneniDokumentIdsById(Document doc, Integer definiceTerminuId,String firstArrayName, String secondArrayName) {
        List<Integer> zneniDokumentIds = new ArrayList<>();
        try {
            // Define the filter to find the document
            
            
            // Extract the 'definice-termínu-vazba' field, which is an array of documents
            Document vazbaList = doc.get("definice-termínu-vazba",Document.class);
            if (vazbaList == null || vazbaList.isEmpty()) {
                logger.warn("'definice-termínu-vazba' field is missing or empty for definice-termínu-id: {}", definiceTerminuId);
                return zneniDokumentIds; // Return empty list
            }
            


            // Extract the 'právní-akt-znění-fragment' array from each 'vazba' object
            List<Document> fragmentList = vazbaList.getList("právní-akt-znění-fragment",Document.class);
             if (fragmentList == null || fragmentList.isEmpty()) {
                logger.warn("'definice-termínu-vazba' array is missing or empty for definice-termínu-id: {}", definiceTerminuId);
                return zneniDokumentIds; // Return empty list
            }
            

            // Iterate over each fragment and extract 'znění-dokument-id'
            for (Document fragment : fragmentList) {
                Integer zneniDokumentId = fragment.getInteger("znění-dokument-id");
                if (zneniDokumentId != null) {
                    zneniDokumentIds.add(zneniDokumentId);
                    logger.debug("Extracted 'znění-dokument-id': {}", zneniDokumentId);
                } else {
                    logger.warn("'znění-dokument-id' is missing in a 'právní-akt-znění-fragment' object for definice-termínu-id: {}", definiceTerminuId);
                }
            }


        logger.info("Retrieved {} 'znění-dokument-id' values for definice-termínu-id: {}", zneniDokumentIds.size(), definiceTerminuId);

        } catch (Exception e) {
            logger.error("An error occurred while retrieving 'znění-dokument-id' values: ", e);
        }
        
        return zneniDokumentIds;
    }
   
    public List<Document> createArrayContainingPairs(String parameterName, List<Integer> zneniDokumentIds) {
        List<Document> dokumentPairs = zneniDokumentIds.stream()
                    .map(id -> new Document(parameterName, id))
                    .collect(Collectors.toList());
        return dokumentPairs;
    }
    
    public static List<Document> createArrayContainingTerms(List<VztazenyTermin> terminy) {
        if (terminy == null || terminy.isEmpty()) {
            return null; 
        }
        return terminy.stream()
                .map(termin -> new Document("termin-nazev", termin.getVztazenyTerminNazev())
                                      .append("termin-popis", termin.getVztazenyTerminText()))
                .collect(Collectors.toList());
    }
    
    private String fetchIdWithRetry(Integer zneniDokumentId, String format) {
        String cachedId = format.equals("PDF") ? PDF_ID_CACHE.get(zneniDokumentId) : DOCX_ID_CACHE.get(zneniDokumentId);
        if (cachedId != null) {
            return cachedId;
        }

        int attempt = 0;
        int delayMillis = INITIAL_DELAY_MILLIS;

        while (attempt < MAX_FETCH_ATTEMPTS) {
            attempt++;
            String id = fetchDocumentId(zneniDokumentId, format);
            if (id != null) {
                return id;
            }
            logger.info("Attempt {}/{}: Waiting {} ms before retrying for znění-dokument-id {} format {}.",
                    attempt, MAX_FETCH_ATTEMPTS, delayMillis, zneniDokumentId, format);
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while waiting to retry fetching ID: {}", e.getMessage());
                break;
            }
            delayMillis *= 1; // Exponential backoff
        }

        logger.warn("Exceeded max attempts for fetching ID for znění-dokument-id {} format {}.", zneniDokumentId, format);
        return null;
    }

    private String fetchDocumentId(Integer zneniDokumentId, String format) {
        String apiUrl = String.format("https://www.e-sbirka.cz/sbr-cache/stahni/informativni-zneni/%d/%s", zneniDokumentId, format);
        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "curl/7.68.0")
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (response.isSuccessful()) {
                JsonNode responseJson = OBJECT_MAPPER.readTree(responseBody);
                JsonNode idNode = responseJson.get("id");
                if (idNode != null && !idNode.isNull()) {
                    return idNode.asText();
                } else {
                    logger.info("Document generation in progress for znění-dokument-id {} format {}.", zneniDokumentId, format);
                    return null;
                }
            } else {
                logger.warn("Failed to fetch document ID for znění-dokument-id {} format {}. Status Code: {}. Response: {}",
                        zneniDokumentId, format, response.code(), responseBody);
                return null;
            }
        } catch (IOException e) {
            logger.error("IOException while fetching document ID for znění-dokument-id {} format {}: {}",
                    zneniDokumentId, format, e.getMessage());
            return null;
        }
    }

    private Document createZneniDocument(Integer zneniDokumentId, Integer zneniBaseId, String aktNazevVyhlasen,
                                           String cisEsbTypZneniPoložka, String zneniDatumUcinnostiOdStr,
                                           String pdfId, String docxId,String typSbirky,String podTyp, String oznaceníAktu,String aktCitace,List<VztazenyTermin> terminy) {
        Document doc = new Document("znění-dokument-id", zneniDokumentId)
                .append("znění-base-id", zneniBaseId)
                .append("akt-název-vyhlášený", aktNazevVyhlasen)
                .append("akt-typ-sbírky", typSbirky)
                .append("akt-označení", oznaceníAktu)
                .append("akt-plné-označení",aktCitace)
                .append("typ-aktu", podTyp)
                .append("cis-esb-typ-znění-po", cisEsbTypZneniPoložka)
                .append("znění-datum-účinnosti-od", zneniDatumUcinnostiOdStr)
                .append("termíny-vztažené", createArrayContainingTerms(terminy))
                .append("dokument-stažení-pdf", pdfId)
                .append("dokument-stažení-docx", docxId);
                

        if (pdfId != null) {
            doc.append("odkaz-stažení-pdf", "https://www.e-sbirka.cz/souborove-sluzby/soubory/" + pdfId);
        }
        else{
             doc.append("odkaz-stažení-pdf", null);
        }
        if (docxId != null) {
            doc.append("odkaz-stažení-docx", "https://www.e-sbirka.cz/souborove-sluzby/soubory/" + docxId);
        }
        else{
           doc.append("odkaz-stažení-docx", null);  
        }

        return doc;
    }
   
     private Document createVazbaDocument(Integer terminID, Integer terminDefiniceID, String terminName, String definiceText,
                                           List<Integer> vztazeneDokumenty) {
        Document doc = new Document("termín-id", terminID)
                .append("definice_termínu-id", terminDefiniceID)
                .append("termin-nazev", terminName)
                .append("termin-definice", definiceText)
                .append("vztazene-dokumenty",createArrayContainingPairs("znění-dokument-id", vztazeneDokumenty));
               
           return doc;
    }

    private static Integer getInteger(Document doc, String key) {
        Object value = doc.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                logger.error("Invalid number format for {}: {}", key, value);
            }
        }
        return null;
    }

    private static Date parseDate(String dateString) {
        try {
            DATE_FORMAT.get().setLenient(false);
             return DATE_FORMAT.get().parse(dateString.trim());
       } catch (ParseException e) {
           logger.error("ParseException - Invalid date format: {}", dateString, e);
           return null;
       } catch (Exception e) {
           logger.error("Exception while parsing date: {}", dateString, e);
           return null;
       }
        
    }

    private static OkHttpClient createUnsafeOkHttpClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{new X509ExtendedTrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] xcs, String string, Socket socket) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] xcs, String string, Socket socket) throws CertificateException {
                }

                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] xcs, String string, SSLEngine ssle) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] xcs, String string, SSLEngine ssle) throws CertificateException {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] xcs, String string) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] xcs, String string) throws CertificateException {
                }
            }};

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .retryOnConnectionFailure(true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}