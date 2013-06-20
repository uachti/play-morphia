package play.modules.morphia;

import com.greenlaw110.exception.UnexpectedIOException;
import com.greenlaw110.storage.ISObject;
import com.greenlaw110.storage.IStorageService;
import com.greenlaw110.storage.KeyGenerator;
import com.greenlaw110.storage.impl.StorageServiceBase;
import com.greenlaw110.util.*;
import play.Logger;
import play.cache.Cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

/**
 * A storage service proxy
 */
public class BlobStorageService extends StorageServiceBase implements IStorageService {

    /**
     * Define the SObject key generator. Default value is {@link KeyGenerator#BY_DATE}
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD })
    public static @interface KeyGen {
        KeyGenerator value() default KeyGenerator.BY_DATE;      
    }
    
    
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD })
    public static @interface Storage {
        String value(); 
    }
    
    private static class SJob<T> extends play.jobs.Job<T> {
        
        F.IFunc0<T> func;
        
        SJob(F.IFunc0<T> func) {
            this.func = func;
        }

        @Override
        public T doJobWithResult() throws Exception {
            return func.run();
        }
    }
    
    /**
     * Indicate whether the app is migrating data from gridfs to other storage e.g. S3 
     */
    private final boolean migrateData;

    /**
     * The real storage service
     */
    private IStorageService ss = null;
    private IStorageService gs = null;
    private String ssKey;
    private boolean putAsync = false;
    private boolean noGet = false;

    private BlobStorageService(boolean migrateData, KeyGenerator keygen, IStorageService ss, String ssKey) {
        super(keygen);
        _.NPE(keygen, ss);
        this.ss = ss;
        this.migrateData = migrateData && (!(ss instanceof GridFSStorageService));
        if (this.migrateData) {
            this.gs = new GridFSStorageService(keygen);
        }
        this.ssKey = ssKey;
    }

    public static String ssKey(KeyGenerator keygen, String storage) {
        StringBuilder sb = new StringBuilder();
        String skgen = keygen.toString();
        sb.append(S.first(skgen, 1)).append(S.last(skgen, 1));
        int hash = _.hc(storage, keygen);
        sb.append(Math.abs(hash)).append(S.first(storage, 1)).append(S.last(storage, 1));
        return sb.toString();
    }

    private static Map<String, BlobStorageService> registry = C.newMap();
    
    public static BlobStorageService valueOf(KeyGenerator keygen, String storage) {
        if (S.empty(storage)) {
            storage = MorphiaPlugin.defaultStorage;
        }
        String ssKey = ssKey(keygen, storage);
        BlobStorageService bss = registry.get(ssKey);
        if (null == bss) {
            Class<? extends IStorageService> c = MorphiaPlugin.getStorageClass(storage);
            E.invalidArgIf(null == c, "cannot find storage implementation for %s", storage);
            IStorageService ss = _.newInstance(c, keygen);
            Map<String, String> ssConf = MorphiaPlugin.getStorageConfig(storage);
            ss.configure(ssConf);
            boolean migrateData = MorphiaPlugin.migrateData;
            bss = new BlobStorageService(migrateData, keygen, ss, ssKey);
            registry.put(bss.ssKey, bss);
            bss.noGet = Boolean.parseBoolean(_.ensureGet(ssConf.get("storage." + storage + ".get.waive"), "false"));
            bss.putAsync = Boolean.parseBoolean(_.ensureGet(ssConf.get("storage." + storage + ".put.async"), "false"));
        }
        return bss;
    }

    public static BlobStorageService getService(String ssKey) {
        return registry.get(ssKey);
    }
    
    play.libs.F.Promise<ISObject> loadLater(String key) {
        return new SJob<ISObject>(IStorageService.f.get(key, ss)).now();
    }

    static void putLater(String key, ISObject sobj, IStorageService ss) {
        new SJob<Void>(IStorageService.f.put(key, sobj, ss)).now();
    }

    static void removeLater(String key, IStorageService ss) {
        new SJob<Void>(IStorageService.f.remove(key, ss)).now();
    }
    
    static void removeLater(String key, IStorageService ss, int sec) {
        new SJob<Void>(IStorageService.f.remove(key, ss)).in(sec);
    }

    @Override
    public ISObject get(String key) {
        ISObject sobj = ss.get(key);
        if (!sobj.isValid()) {
            Throwable cause = sobj.getException();
            Logger.warn(cause, "error load blob by key[%s]", key);
            if (migrateData) {
                sobj = gs.get(key);
                if (null != sobj) {
                    putLater(key, sobj, ss);
                    removeLater(key, gs, 60);
                }
            }
        }
        return sobj;
    }

    @Override
    public void put(String key, ISObject sobj) throws UnexpectedIOException {
        if (putAsync) {
            putLater(key, sobj, ss);
            long len = sobj.getLength();
            // suppose the network transfer 100K per second
            String timeout = len / (1000 * 100) + "s";
            Cache.set(key, sobj, timeout);
        } else {
            ss.put(key, sobj);
        }
    }

    @Override
    public void remove(String key) {
        removeLater(key, ss);
        if (migrateData) {
            removeLater(key, gs);
        }
    }

    @Override
    public String getUrl(String key) {
        if (migrateData) {
            ISObject sobj = gs.get(key);
            if (null != sobj) {
                return gs.getUrl(key);
            }
        }
        return ss.getUrl(key);
    }

    public String getUrl(Blob blob) {
        String key = blob.getKey();
        if (migrateData) {
            ISObject sobj = gs.get(key);
            if (null != sobj) {
                return gs.getUrl(key + "-" + ssKey);
            }
        }
        if (ss instanceof GridFSStorageService) {
            key = key + "-" + ssKey;
        }
        return ss.getUrl(key);
    }
    
    public static String getLegacyKey(String hostId, String fieldName) {
        return Model.getBlobFileName(hostId, fieldName);
    }

    public static String getLegacyKey(String key) {
        return S.str(key).after("/").before(".").get();
    }

    public String getKey(String hostId, String fieldName, Blob blob) {
        String legacy = getLegacyKey(hostId, fieldName);
        return newKey(legacy, blob);
    }
    
    public String newKey(String legacy, Blob blob) {
        String type = blob.type();
        type = S.after(type, "/");
        String key = legacy + "." + type;
        return getKey(key);
    }
}