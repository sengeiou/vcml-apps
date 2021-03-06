package voldemort.store.rocksdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.commons.codec.binary.Hex;
import org.apache.log4j.Logger;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import voldemort.VoldemortException;
import voldemort.store.AbstractStorageEngine;
import voldemort.store.PersistenceFailureException;
import voldemort.store.StoreBinaryFormat;
import voldemort.store.StoreUtils;
import voldemort.consistency.utils.ByteArray;
import voldemort.consistency.utils.ByteUtils;
import voldemort.utils.ClosableIterator;
import voldemort.utils.Pair;
import voldemort.utils.StripedLock;
import voldemort.consistency.versioning.ObsoleteVersionException;
import voldemort.consistency.versioning.Occurred;
import voldemort.consistency.versioning.Version;
import voldemort.consistency.versioning.Versioned;

/**
 * A StorageEngine that uses RocksDB for persistence
 * 
 * 
 */
public class RocksDbStorageEngine extends AbstractStorageEngine<ByteArray, byte[], byte[]> {

    private static final Logger logger = Logger.getLogger(RocksDbStorageEngine.class);
    private RocksDB rocksDB;
    private volatile ColumnFamilyHandle storeHandle;
    private final ColumnFamilyOptions storeOptions;
    private final StripedLock locks;
    private static final Hex hexCodec = new Hex();
    private final boolean enableReadLocks;

    // TODO Need to add stats and loggers later

    public RocksDbStorageEngine(String name,
                                RocksDB rdbInstance,
                                ColumnFamilyHandle storeHandle,
                                ColumnFamilyOptions storeOptions,
                                int lockStripes,
                                boolean enableReadLocks) {
        super(name);
        this.rocksDB = rdbInstance;
        this.storeHandle = storeHandle;
        this.storeOptions = storeOptions;
        this.locks = new StripedLock(lockStripes);
        this.enableReadLocks = enableReadLocks;
    }

    public RocksDB getRocksDB() {
        return rocksDB;
    }

    @Override
    public ClosableIterator<Pair<ByteArray, Versioned<byte[]>>> entries() {
        return new RocksdbEntriesIterator(getRocksDbIterator());
    }

    @Override
    public ClosableIterator<ByteArray> keys() {
        return new RocksdbKeysIterator(getRocksDbIterator());
    }

    protected RocksIterator getRocksDbIterator() {
        return this.getRocksDB().newIterator(storeHandle);
    }

    @Override
    public ClosableIterator<Pair<ByteArray, Versioned<byte[]>>> entries(int partitionId) {
        throw new UnsupportedOperationException("Partition based entries scan not supported for this storage type");
    }

    @Override
    public ClosableIterator<ByteArray> keys(int partitionId) {
        throw new UnsupportedOperationException("Partition based keys scan not supported for this storage type");
    }

    @Override
    public void truncate() {
        try {
            rocksDB.dropColumnFamily(storeHandle);
            storeHandle.dispose();
            storeHandle = rocksDB.createColumnFamily(new ColumnFamilyDescriptor(getName().getBytes(), storeOptions));
        } catch (RocksDBException e) {
            throw new VoldemortException("Failed to truncate DB", e);
        }
    }

    @Override
    public void close() throws VoldemortException {
        storeHandle.dispose();
        rocksDB.close();
    }

    private List<Versioned<byte[]>> getValueForKey(ByteArray key, byte[] transforms)
            throws PersistenceFailureException {
        long startTimeNs = -1;

        if(logger.isTraceEnabled())
            startTimeNs = System.nanoTime();

        List<Versioned<byte[]>> value = null;
        try {
            byte[] result = getRocksDB().get(storeHandle, key.get());
            if(result != null) {
                value = StoreBinaryFormat.fromByteArray(result);
            } else {
                return Collections.emptyList();
            }
        } catch(RocksDBException e) {
            logger.error(e);
            throw new PersistenceFailureException(e);
        } finally {
            if(logger.isTraceEnabled()) {
                logger.trace("Completed GET (" + getName() + ") from key " + key + " (keyRef: "
                             + System.identityHashCode(key) + ") in "
                             + (System.nanoTime() - startTimeNs) + " ns at "
                             + System.currentTimeMillis());
            }
        }
        return value;
    }

    @Override
    public List<Versioned<byte[]>> get(ByteArray key, byte[] transforms)
            throws PersistenceFailureException {
        StoreUtils.assertValidKey(key);

        if(enableReadLocks) {
            synchronized(this.locks.lockFor(key.get())) {
                return getValueForKey(key, transforms);
            }

        } else {
            return getValueForKey(key, transforms);
        }
    }

    @Override
    public Map<ByteArray, List<Versioned<byte[]>>> getAll(Iterable<ByteArray> keys,
                                                          Map<ByteArray, byte[]> transforms)
            throws VoldemortException {
        // TODO Does RocksDB multiget supports atomicity ?
        StoreUtils.assertValidKeys(keys);
        long startTimeNs = -1;

        if(logger.isTraceEnabled())
            startTimeNs = System.nanoTime();

        Map<ByteArray, List<Versioned<byte[]>>> results = null;

        try {
            results = StoreUtils.getAll(this, keys, transforms);
        } catch(PersistenceFailureException e) {
            logger.error(e);
            throw new PersistenceFailureException(e);
        } finally {
            if(logger.isTraceEnabled()) {
                String keyStr = "";
                for(ByteArray key: keys)
                    keyStr += key + " ";
                logger.trace("Completed GETALL (" + getName() + ") from keys " + keyStr + " in "
                             + (System.nanoTime() - startTimeNs) + " ns at "
                             + System.currentTimeMillis());
            }
        }

        return results;
    }

    @Override
    public void put(ByteArray key, Versioned<byte[]> value, byte[] transforms)
            throws PersistenceFailureException {
        StoreUtils.assertValidKey(key);

        long startTimeNs = -1;

        if(logger.isTraceEnabled())
            startTimeNs = System.nanoTime();

        synchronized(this.locks.lockFor(key.get())) {
            /*
             * Get the existing values. Make sure to "get" from the underlying
             * storage instead of using the get method described in this class.
             * Invoking the get method from this class will unnecessarily double
             * prefix the key in case of PartitionPrefixedRocksdbStorageEngine
             * and can cause unpredictable results.
             */
            List<Versioned<byte[]>> currentValues;
            try {
                byte[] result = getRocksDB().get(storeHandle, key.get());
                if(result != null) {
                    currentValues = StoreBinaryFormat.fromByteArray(result);
                } else {
                    currentValues = Collections.emptyList();
                }
            } catch(RocksDBException e) {
                logger.error(e);
                throw new PersistenceFailureException(e);
            }
            if(currentValues.size() > 0) {
                // compare vector clocks and throw out old ones, for updates
                Iterator<Versioned<byte[]>> iter = currentValues.iterator();
                while(iter.hasNext()) {
                    Versioned<byte[]> curr = iter.next();
                    Occurred occurred = value.getVersion().compare(curr.getVersion());
                    if(occurred == Occurred.BEFORE) {
                        throw new ObsoleteVersionException("Key "
                                                           + new String(hexCodec.encode(key.get()))
                                                           + " "
                                                           + value.getVersion().toString()
                                                           + " is obsolete, it is no greater than the current version of "
                                                           + curr.getVersion().toString() + ".");
                    } else if(occurred == Occurred.AFTER) {
                        iter.remove();
                    }
                }
            } else {
                // if value does not exist add the value from put request to
                // existing values
                currentValues = new ArrayList<Versioned<byte[]>>(1);
            }
            currentValues.add(value);

            try {
                getRocksDB().put(storeHandle, key.get(), StoreBinaryFormat.toByteArray(currentValues));
            } catch(RocksDBException e) {
                logger.error(e);
                throw new PersistenceFailureException(e);
            } finally {
                if(logger.isTraceEnabled()) {
                    logger.trace("Completed PUT (" + getName() + ") to key " + key + " (keyRef: "
                                 + System.identityHashCode(key) + " value " + value + " in "
                                 + (System.nanoTime() - startTimeNs) + " ns at "
                                 + System.currentTimeMillis());
                }
            }
        }
    }

    @Override
    public boolean delete(ByteArray key, Version version) throws PersistenceFailureException {

        StoreUtils.assertValidKey(key);

        long startTimeNs = -1;

        if(logger.isTraceEnabled())
            startTimeNs = System.nanoTime();

        synchronized(this.locks.lockFor(key.get())) {
            try {
                byte[] value = getRocksDB().get(storeHandle, key.get());

                if(value == null) {
                    return false;
                }

                if(version == null) {
                    // unversioned delete. Just blow away the whole thing
                    getRocksDB().remove(storeHandle, key.get());
                    return true;
                } else {
                    // versioned deletes; need to determine what to delete

                    List<Versioned<byte[]>> vals = StoreBinaryFormat.fromByteArray(value);
                    Iterator<Versioned<byte[]>> iter = vals.iterator();
                    int numVersions = vals.size();
                    int numDeletedVersions = 0;

                    // go over the versions and remove everything before the
                    // supplied version
                    while(iter.hasNext()) {
                        Versioned<byte[]> curr = iter.next();
                        Version currentVersion = curr.getVersion();
                        if(currentVersion.compare(version) == Occurred.BEFORE) {
                            iter.remove();
                            numDeletedVersions++;
                        }
                    }

                    if(numDeletedVersions < numVersions) {
                        // we still have some valid versions
                        value = StoreBinaryFormat.toByteArray(vals);
                        getRocksDB().put(storeHandle, key.get(), value);
                    } else {
                        // we have deleted all the versions; so get rid of the
                        // entry
                        // in the database
                        getRocksDB().remove(storeHandle, key.get());
                    }
                    return numDeletedVersions > 0;
                }
            } catch(RocksDBException e) {
                logger.error(e);
                throw new PersistenceFailureException(e);
            } finally {
                if(logger.isTraceEnabled()) {
                    logger.trace("Completed DELETE (" + getName() + ") of key "
                                 + ByteUtils.toHexString(key.get()) + " (keyRef: "
                                 + System.identityHashCode(key) + ") in "
                                 + (System.nanoTime() - startTimeNs) + " ns at "
                                 + System.currentTimeMillis());
                }
            }
        }
    }

    @Override
    public List<Version> getVersions(ByteArray key) {
        /*
         * getVersions is a wrapper over get and filters away the value before
         * returning the result
         */
        return StoreUtils.getVersions(get(key, null));
    }

    @Override
    public List<Versioned<byte[]>> multiVersionPut(ByteArray key, List<Versioned<byte[]>> values) {
        // TODO Implement getandLock() and putAndUnlock() and then remove this
        // method
        StoreUtils.assertValidKey(key);

        long startTimeNs = -1;

        if(logger.isTraceEnabled())
            startTimeNs = System.nanoTime();

        List<Versioned<byte[]>> currentValues = null;
        List<Versioned<byte[]>> obsoleteVals = null;

        synchronized(this.locks.lockFor(key.get())) {
            /*
             * Get the existing values. Make sure to "get" from the underlying
             * storage instead of using the get method described in this class.
             * Invoking the get method from this class will unnecessarily double
             * prefix the key in case of PartitionPrefixedRocksdbStorageEngine
             * and can cause unpredictable results.
             */
            try {
                byte[] result = getRocksDB().get(storeHandle, key.get());
                if(result != null) {
                    currentValues = StoreBinaryFormat.fromByteArray(result);
                } else {
                    currentValues = new ArrayList<Versioned<byte[]>>();
                }
            } catch(RocksDBException e) {
                logger.error(e);
                throw new PersistenceFailureException(e);
            }
            obsoleteVals = resolveAndConstructVersionsToPersist(currentValues, values);
            try {
                getRocksDB().put(storeHandle, key.get(), StoreBinaryFormat.toByteArray(currentValues));
            } catch(RocksDBException e) {
                logger.error(e);
                throw new PersistenceFailureException(e);
            } finally {
                if(logger.isTraceEnabled()) {
                    String valueStr = "";
                    for(Versioned<byte[]> val: currentValues) {
                        valueStr += val + ",";
                    }
                    logger.trace("Completed PUT (" + getName() + ") to key " + key + " (keyRef: "
                                 + System.identityHashCode(key) + " values " + valueStr + " in "
                                 + (System.nanoTime() - startTimeNs) + " ns at "
                                 + System.currentTimeMillis());
                }
            }
        }
        return obsoleteVals;
    }

    /*
     * TODO FOR BATCH MODIFICATIONS - When opening a DB, you can disable syncing
     * of data files by setting Options::disableDataSync to true. This can be
     * useful when doing bulk-loading or big idempotent operations. Once the
     * operation is finished, you can manually call sync() to flush all dirty
     * buffers to stable storage.
     * 
     * Rocksdb Java also works in a similar way - https://github.com/facebook
     * /rocksdb/blob/master/java/org/rocksdb/Options.java#L373
     * 
     * For now batch modifications is considered as a no op. Later based on
     * performance, this should be enabled
     */

    @Override
    public boolean beginBatchModifications() {
        /*
         * begin batch modifications should disable data sync and log
         */
        return false;
    }

    @Override
    public boolean endBatchModifications() {
        /*
         * end batch modifications should call sync to flush all dirty buffers
         * to storage and log
         */
        return false;
    }

    private class RocksdbKeysIterator implements ClosableIterator<ByteArray> {

        // TODO May need to identify non const methods in the inner Iterator adn
        // provide external synchronization on those if needed

        RocksIterator innerIterator;

        public RocksdbKeysIterator(RocksIterator innerIterator) {
            this.innerIterator = innerIterator;

            // Caller of the RocksIterator should seek it before the first use.
            this.innerIterator.seekToFirst();
        }

        @Override
        public boolean hasNext() {
            return this.innerIterator.isValid();
        }

        @Override
        public ByteArray next() {
            if(!this.innerIterator.isValid()) {
                throw new NoSuchElementException("Iterate to end");
            }
            byte[] keyEntry = this.innerIterator.key();
            this.innerIterator.next();
            if (RocksDbStorageEngine.this.isPartitionScanSupported()) {
                keyEntry = StoreBinaryFormat.extractKey(keyEntry);
            }
            return new ByteArray(keyEntry);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("No removal");
        }

        @Override
        public void close() {
            this.innerIterator.dispose();
        }

    }

    private class RocksdbEntriesIterator implements
            ClosableIterator<Pair<ByteArray, Versioned<byte[]>>> {

        // TODO May need to identify non const methods in the inner Iterator adn
        // provide external synchronization on those if needed

        RocksIterator innerIterator;
        private List<Pair<ByteArray, Versioned<byte[]>>> cache;

        public RocksdbEntriesIterator(RocksIterator innerIterator) {
            this.innerIterator = innerIterator;

            // Caller of the RocksIterator should seek it before the first use.
            this.innerIterator.seekToFirst();

            cache = new ArrayList<Pair<ByteArray, Versioned<byte[]>>>();
        }

        @Override
        public boolean hasNext() {
            return cache.size() > 0 || makeMore();
        }

        @Override
        public Pair<ByteArray, Versioned<byte[]>> next() {
            if(cache.size() == 0) {
                if(!makeMore()) {
                    throw new NoSuchElementException("Iterated to end.");
                }
            }
            return cache.remove(cache.size() - 1);
        }

        protected boolean makeMore() {
            if(innerIterator.isValid()) {
                byte[] keyEntry = innerIterator.key();
                byte[] valueEntry = innerIterator.value();
                innerIterator.next();
                if (RocksDbStorageEngine.this.isPartitionScanSupported()) {
                    keyEntry = StoreBinaryFormat.extractKey(keyEntry);
                }
                ByteArray key = new ByteArray(keyEntry);
                for(Versioned<byte[]> val: StoreBinaryFormat.fromByteArray(valueEntry)) {
                    cache.add(new Pair<ByteArray, Versioned<byte[]>>(key, val));
                }
                return true;
            }
            return false;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("No removal");
        }

        @Override
        public void close() {
            this.innerIterator.dispose();
        }

    }

}
