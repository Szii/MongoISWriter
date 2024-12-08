/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mongoiswriter.Service;


import com.mongoiswriter.Configuration.DataSourceConfig;
import com.mongoiswriter.Configuration.MongoConfig;
import com.mongoiswriter.Enum.ExtracterType;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketException;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

/**
 *
 * @author brune
 */
@Service
@EnableAsync
public  class DocumentFetcher {
    
     
   private final MongoConfig mongoConfig;
   private final DataSourceConfig dataSourceConfig;
    
    private final JsonExtracterUtil jsonExtracter;
    public DocumentFetcher(JsonExtracterUtil jsonExtracter,MongoConfig mongoConfig, DataSourceConfig dataSourceConfig){
        this.jsonExtracter = jsonExtracter;
        this.mongoConfig= mongoConfig;
        this.dataSourceConfig = dataSourceConfig;
    }
    
    public  void fetchDocuments() throws MalformedURLException, SocketException{
          
        jsonExtracter.extractFromAddressToMongo(dataSourceConfig.URL_AKTY_ZNENI,mongoConfig.MONGO_COLLECTION_AKTY_ZNENI,ExtracterType.PRAVNI_AKT);
       // jsonExtracter.extractFromAddressToMongo(dataSourceConfig.URL_TERMINY_POPIS, mongoConfig.MONGO_COLLECTION_TERMINY_POPIS,ExtracterType.TERMIN_DEFINICE);
       // jsonExtracter.extractFromAddressToMongo(dataSourceConfig.URL_TERMINY_BASE, mongoConfig.MONGO_COLLECTION_TERMINY_BASE,ExtracterType.TERMIN_NAZEV);
       // jsonExtracter.extractFromAddressToMongo(dataSourceConfig.URL_TERMINY_VAZBA,  mongoConfig.MONGO_COLLECTION_TERMINY_VAZBA ,ExtracterType.TERMIN_VAZBA);
        //jsonExtracter.extractFromAddressToMongo(DATA_FRAGMENT_URL,  SOURCE_FRAGMENT_COLLECTION_NAME ,ExtracterType.NONE);
    }
    
}
