/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.geogit.storage.bdbje;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterators.partition;
import static com.sleepycat.je.OperationStatus.NOTFOUND;
import static com.sleepycat.je.OperationStatus.SUCCESS;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.geogit.api.ObjectId;
import org.geogit.api.RevObject;
import org.geogit.repository.RepositoryConnectionException;
import org.geogit.storage.AbstractObjectDatabase;
import org.geogit.storage.BulkOpListener;
import org.geogit.storage.ConfigDatabase;
import org.geogit.storage.ObjectDatabase;
import org.geogit.storage.ObjectReader;
import org.geogit.storage.ObjectSerializingFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.UnmodifiableIterator;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.ning.compress.lzf.LZFInputStream;
import com.sleepycat.je.CacheMode;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Environment;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;

/**
 * 
 */
public class JEObjectDatabase extends AbstractObjectDatabase implements ObjectDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger(JEObjectDatabase.class);

    private static final int SYNC_BYTES_LIMIT = 512 * 1024 * 1024;

    @Nullable
    private ExecutorService dbSyncService;

    private ExecutorService writerService;

    /**
     * The default number of objects bulk operations are partitioned into
     * 
     * @see #getAll(Iterable, BulkOpListener)
     * @see #putAll(Iterator, BulkOpListener)
     * @see #deleteAll(Iterator, BulkOpListener)
     */
    private static final Integer DEFAULT_BULK_PARTITIONING = 10 * 1000;

    private static final String BULK_PARTITIONING_CONFIG_KEY = "bdbje.bulkpartition";

    private EnvironmentBuilder envProvider;

    /**
     * Lazily loaded, do not access it directly but through {@link #getEnvironment()}
     */
    protected Environment env;

    protected Database objectDb;

    private ConfigDatabase configDB;

    @Inject
    public JEObjectDatabase(final ConfigDatabase configDB,
            final ObjectSerializingFactory serialFactory, final EnvironmentBuilder envProvider) {
        super(serialFactory);
        this.configDB = configDB;
        this.envProvider = envProvider;
    }

    public JEObjectDatabase(final ObjectSerializingFactory serialFactory, final Environment env,
            final ConfigDatabase configDb) {
        super(serialFactory);
        this.env = env;
        this.configDB = configDb;
    }

    /**
     * @return the env
     */
    private synchronized Environment getEnvironment() {
        if (env == null) {
            env = envProvider.setRelativePath("objects").get();
        }
        return env;
    }

    @Override
    public synchronized void close() {
        if (env == null) {
            LOGGER.trace("Database already closed.");
            return;
        }

        final File envHome = env.getHome();
        LOGGER.debug("Closing object database at {}", envHome);
        writerService.shutdownNow();
        objectDb.close();
        objectDb = null;
        if (dbSyncService != null) {
            dbSyncService.shutdownNow();
        }
        LOGGER.trace("ObjectDatabase closed. Closing environment...");

        env.sync();
        env.cleanLog();
        env.close();
        env = null;
        LOGGER.debug("Database {} closed.", envHome);
    }

    @Override
    public boolean isOpen() {
        return objectDb != null;
    }

    @Override
    public synchronized void open() {
        if (isOpen()) {
            LOGGER.trace("Environment {} already open", env.getHome());
            return;
        }
        Environment environment = getEnvironment();
        LOGGER.debug("Opening ObjectDatabase at {}", env.getHome());

        this.objectDb = createDatabase(environment);

        int nWriterThreads = 1;
        writerService = Executors.newFixedThreadPool(nWriterThreads, new ThreadFactoryBuilder()
                .setNameFormat("BDBJE-" + env.getHome().getName() + "-WRITE-THREAD-%d").build());
        if (!objectDb.getConfig().getTransactional()) {
            dbSyncService = Executors.newFixedThreadPool(nWriterThreads, new ThreadFactoryBuilder()
                    .setNameFormat("BDBJE-" + env.getHome().getName() + "-SYNC-THREAD-%d").build());
        }

        LOGGER.debug("Object database opened at {}. Transactional: {}", environment.getHome(),
                objectDb.getConfig().getTransactional());
    }

    protected Database createDatabase(Environment environment) {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        boolean transactional = getEnvironment().getConfig().getTransactional();

        dbConfig.setTransactional(transactional);
        dbConfig.setDeferredWrite(!transactional);
        dbConfig.setCacheMode(CacheMode.MAKE_COLD);
        dbConfig.setKeyPrefixing(false);// can result in a slightly smaller db size

        Database database = environment.openDatabase(null, "ObjectDatabase", dbConfig);
        return database;
    }

    @Override
    protected List<ObjectId> lookUpInternal(final byte[] partialId) {

        DatabaseEntry key;
        {
            byte[] keyData = partialId.clone();
            key = new DatabaseEntry(keyData);
        }

        DatabaseEntry data = new DatabaseEntry();
        data.setPartial(0, 0, true);// do not retrieve data

        List<ObjectId> matches;

        CursorConfig cursorConfig = new CursorConfig();
        cursorConfig.setReadUncommitted(true);

        Transaction transaction = null;
        Cursor cursor = objectDb.openCursor(transaction, cursorConfig);
        try {
            // position cursor at the first closest key to the one looked up
            OperationStatus status = cursor.getSearchKeyRange(key, data, LockMode.READ_UNCOMMITTED);
            if (SUCCESS.equals(status)) {
                matches = new ArrayList<ObjectId>(2);
                final byte[] compKey = new byte[partialId.length];
                while (SUCCESS.equals(status)) {
                    byte[] keyData = key.getData();
                    System.arraycopy(keyData, 0, compKey, 0, compKey.length);
                    if (Arrays.equals(partialId, compKey)) {
                        matches.add(new ObjectId(keyData));
                    } else {
                        break;
                    }
                    status = cursor.getNext(key, data, LockMode.READ_UNCOMMITTED);
                }
            } else {
                matches = Collections.emptyList();
            }
            return matches;
        } finally {
            cursor.close();
        }
    }

    /**
     * @see org.geogit.storage.ObjectDatabase#exists(org.geogit.api.ObjectId)
     */
    @Override
    public boolean exists(final ObjectId id) {
        Preconditions.checkNotNull(id, "id");

        DatabaseEntry key = new DatabaseEntry(id.getRawValue());
        DatabaseEntry data = new DatabaseEntry();
        // tell db not to retrieve data
        data.setPartial(0, 0, true);

        final LockMode lockMode = LockMode.READ_UNCOMMITTED;
        Transaction transaction = null;
        OperationStatus status = objectDb.get(transaction, key, data, lockMode);
        return SUCCESS == status;
    }

    @Override
    protected InputStream getRawInternal(final ObjectId id, final boolean failIfNotFound) {
        Preconditions.checkNotNull(id, "id");
        DatabaseEntry key = new DatabaseEntry(id.getRawValue());
        DatabaseEntry data = new DatabaseEntry();

        final LockMode lockMode = LockMode.READ_UNCOMMITTED;
        Transaction transaction = null;
        OperationStatus operationStatus = objectDb.get(transaction, key, data, lockMode);
        if (NOTFOUND.equals(operationStatus)) {
            if (failIfNotFound) {
                throw new IllegalArgumentException("Object does not exist: " + id.toString());
            }
            return null;
        }
        final byte[] cData = data.getData();

        return new ByteArrayInputStream(cData);
    }

    @Override
    public void putAll(final Iterator<? extends RevObject> objects, final BulkOpListener listener) {
        checkNotNull(objects);
        checkNotNull(listener);

        if (!objects.hasNext()) {
            return;
        }

        final int buffSize = 8 * 1024;
        BulkInsert task = new BulkInsert(objects, listener, buffSize);

        try {
            Integer insertedCount = task.run();
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private class BulkInsert {

        private BulkOpListener listener;

        private int buffSize;

        private Iterator<? extends RevObject> objects;

        public BulkInsert(final Iterator<? extends RevObject> objects,
                final BulkOpListener listener, final int buffSize) {
            this.objects = objects;
            this.listener = listener;
            this.buffSize = buffSize;
        }

        public Integer run() throws Exception {
            int count = 0;
            List<Future<Void>> pendingWrites = new ArrayList<Future<Void>>();
            try {
                InternalByteArrayOutputStream out = new InternalByteArrayOutputStream(this.buffSize);
                TreeMap<ObjectId, int[]> offsets = Maps.newTreeMap(ObjectId.NATURAL_ORDER);

                int objectsInBuffer = 0;
                while (true) {
                    if (!serializeNextObject(offsets, out)) {
                        break;
                    }
                    count++;
                    objectsInBuffer++;
                    if (out.size() >= buffSize) {
                        Future<Void> future = insertSortedObjects(offsets, out);
                        // future.get();
                        // out.reset();
                        // offsets.clear();
                        out = new InternalByteArrayOutputStream(this.buffSize);
                        offsets = Maps.newTreeMap(ObjectId.NATURAL_ORDER);
                        pendingWrites.add(future);
                        if (pendingWrites.size() == 10) {
                            waitForWrites(pendingWrites);
                        }

                        LOGGER.debug("Inserted {} objects with a byte buffer of {} KB",
                                objectsInBuffer, (out.size() / 1024));
                        objectsInBuffer = 0;
                        out.reset();
                    }
                }
                if (!offsets.isEmpty()) {
                    Future<Void> future = insertSortedObjects(offsets, out);
                    pendingWrites.add(future);
                    LOGGER.debug("Inserted {} objects with a byte buffer of {} KB",
                            objectsInBuffer, (out.size() / 1024));
                }
                waitForWrites(pendingWrites);
            } catch (Exception e) {
                LOGGER.error("Error inserting objects: " + e.getMessage(), e);
                throw e;
            } finally {
                pendingWrites.clear();
                pendingWrites = null;
            }
            return count;
        }

        private void waitForWrites(List<Future<Void>> pendingWrites) throws InterruptedException,
                ExecutionException {
            if (pendingWrites.isEmpty()) {
                return;
            }

            for (Future<Void> pendingWrite : pendingWrites) {
                if (!pendingWrite.isDone()) {
                    pendingWrite.get();
                }
            }

            pendingWrites.clear();
        }

        private Future<Void> insertSortedObjects(TreeMap<ObjectId, int[]> offsets,
                InternalByteArrayOutputStream buffer) throws Exception {

            return writerService.submit(new InsertTask(offsets, buffer, listener));
        }

        private boolean serializeNextObject(TreeMap<ObjectId, int[]> offsets,
                InternalByteArrayOutputStream out) {
            if (!objects.hasNext()) {
                return false;
            }
            RevObject o = objects.next();
            int offset = out.size();
            writeObject(o, out);
            int size = out.size() - offset;
            offsets.put(o.getId(), new int[] { offset, size });
            return true;
        }

    }

    private AtomicInteger bytesWritten = new AtomicInteger();

    private class InsertTask implements Callable<Void> {

        private TreeMap<ObjectId, int[]> offsets;

        private InternalByteArrayOutputStream buffer;

        private BulkOpListener listener;

        public InsertTask(TreeMap<ObjectId, int[]> offsets, InternalByteArrayOutputStream buffer,
                BulkOpListener listener) {
            this.offsets = offsets;
            this.buffer = buffer;
            this.listener = listener;
        }

        @Override
        public Void call() throws Exception {

            Transaction transaction = newTransaction();

            final int numObjects = offsets.size();
            try {
                final int bufferBytes = buffer.size();
                DatabaseEntry key = new DatabaseEntry(new byte[ObjectId.NUM_BYTES]);
                final byte[] rawData = buffer.bytes();

                for (Iterator<Map.Entry<ObjectId, int[]>> it = offsets.entrySet().iterator(); it
                        .hasNext();) {
                    Entry<ObjectId, int[]> e = it.next();
                    it.remove();
                    final ObjectId objectId = e.getKey();
                    int offset = e.getValue()[0];
                    int size = e.getValue()[1];

                    objectId.getRawValue(key.getData());
                    DatabaseEntry data = new DatabaseEntry(rawData, offset, size);

                    OperationStatus status = objectDb.putNoOverwrite(transaction, key, data);
                    if (OperationStatus.SUCCESS.equals(status)) {
                        listener.inserted(objectId, size);
                    } else if (OperationStatus.KEYEXIST.equals(status)) {
                        listener.found(objectId, null);
                    }

                }
                final boolean transactional = objectDb.getConfig().getTransactional();
                if (transactional) {
                    commit(transaction);
                    LOGGER.trace("Committed {} inserts to {}", numObjects, objectDb
                            .getEnvironment().getHome());
                } else {
                    int totalWritten;
                    synchronized (bytesWritten) {
                        totalWritten = bytesWritten.addAndGet(bufferBytes);
                    }
                    if (totalWritten >= SYNC_BYTES_LIMIT) {
                        writerService.execute(new FlushLogTask(bytesWritten, objectDb));
                    }
                }
            } catch (Exception e) {
                abort(transaction);
                throw e;
            } finally {
                offsets = null;
                buffer = null;
            }
            return null;
        }

    }

    private class FlushLogTask implements Runnable {

        private Environment env;

        private volatile AtomicInteger bytesWritten;

        private Database objectDb;

        public FlushLogTask(AtomicInteger bytesWritten, Database objectDb) {
            this.bytesWritten = bytesWritten;
            this.objectDb = objectDb;
            this.env = objectDb.getEnvironment();
        }

        @Override
        public void run() {
            boolean doSync = false;
            final int buffSize;
            synchronized (bytesWritten) {
                buffSize = bytesWritten.get();
                if (buffSize >= SYNC_BYTES_LIMIT) {
                    doSync = true;
                    bytesWritten.set(0);
                }
            }
            if (doSync) {
                Preconditions.checkState(dbSyncService != null,
                        "DB Sync executor service is null, but the database is non transactional.");
                dbSyncService.execute(new Runnable() {

                    @Override
                    public void run() {
                        Stopwatch sw = new Stopwatch().start();
                        if (objectDb.getConfig().getDeferredWrite()) {
                            objectDb.sync();
                            env.evictMemory();
                            env.cleanLog();
                            // env.sync();
                        } else {
                            env.flushLog(false);
                        }
                        LOGGER.debug("flushed db log after {} bytes in {}", buffSize, sw.stop());
                    }
                });
            }

        }
    }

    @Override
    protected boolean putInternal(final ObjectId id, final byte[] rawData) {
        final Transaction transaction = newTransaction();

        final OperationStatus status;
        try {
            status = putInternal(id, rawData, transaction);
            commit(transaction);
        } catch (RuntimeException e) {
            abort(transaction);
            throw e;
        }
        final boolean didntExist = SUCCESS.equals(status);

        return didntExist;
    }

    private OperationStatus putInternal(final ObjectId id, final byte[] rawData,
            Transaction transaction) {
        OperationStatus status;
        final byte[] rawKey = id.getRawValue();
        DatabaseEntry key = new DatabaseEntry(rawKey);
        DatabaseEntry data = new DatabaseEntry(rawData);

        status = objectDb.putNoOverwrite(transaction, key, data);
        return status;
    }

    @Override
    public boolean delete(final ObjectId id) {
        final byte[] rawKey = id.getRawValue();
        final DatabaseEntry key = new DatabaseEntry(rawKey);

        final Transaction transaction = newTransaction();

        final OperationStatus status;
        try {
            status = objectDb.delete(transaction, key);
            commit(transaction);
        } catch (RuntimeException e) {
            abort(transaction);
            throw e;
        }
        return SUCCESS.equals(status);
    }

    private void abort(@Nullable Transaction transaction) {
        if (transaction != null) {
            try {
                transaction.abort();
            } catch (Exception e) {
                LOGGER.error("Error aborting transaction", e);
            }
        }
    }

    private void commit(@Nullable Transaction transaction) {
        if (transaction != null) {
            try {
                transaction.commit();
            } catch (Exception e) {
                LOGGER.error("Error committing transaction", e);
            }
        }
    }

    @Override
    public long deleteAll(Iterator<ObjectId> ids, final BulkOpListener listener) {

        long count = 0;

        UnmodifiableIterator<List<ObjectId>> partition = partition(ids, getBulkPartitionSize());

        final DatabaseEntry data = new DatabaseEntry();
        data.setPartial(0, 0, true);// do not retrieve data

        while (partition.hasNext()) {
            List<ObjectId> nextIds = Lists.newArrayList(partition.next());
            Collections.sort(nextIds);

            final Transaction transaction = newTransaction();

            CursorConfig cconfig = new CursorConfig();
            final Cursor cursor = objectDb.openCursor(transaction, cconfig);

            try {
                DatabaseEntry key = new DatabaseEntry(new byte[ObjectId.NUM_BYTES]);
                for (ObjectId id : nextIds) {
                    // copy id to key object without allocating new byte[]
                    id.getRawValue(key.getData());

                    OperationStatus status = cursor.getSearchKey(key, data, LockMode.DEFAULT);
                    if (OperationStatus.SUCCESS.equals(status)) {
                        OperationStatus delete = cursor.delete();
                        if (OperationStatus.SUCCESS.equals(delete)) {
                            listener.deleted(id);
                            count++;
                        } else {
                            listener.notFound(id);
                        }
                    } else {
                        listener.notFound(id);
                    }
                }
                cursor.close();
            } catch (Exception e) {
                cursor.close();
                abort(transaction);
                Throwables.propagate(e);
            }
            commit(transaction);
        }
        return count;
    }

    @Override
    public void configure() throws RepositoryConnectionException {
        RepositoryConnectionException.StorageType.OBJECT.configure(configDB, "bdbje", "0.1");
    }

    @Override
    public void checkConfig() throws RepositoryConnectionException {
        RepositoryConnectionException.StorageType.OBJECT.verify(configDB, "bdbje", "0.1");
    }

    @Override
    public Iterator<RevObject> getAll(final Iterable<ObjectId> ids, final BulkOpListener listener) {
        Preconditions.checkNotNull(ids, "ids");

        return new CursorRevObjectIterator(ids.iterator(), listener);
    }

    private class CursorRevObjectIterator extends AbstractIterator<RevObject> implements Closeable {

        private final ObjectReader<RevObject> reader = serializationFactory.createObjectReader();

        @Nullable
        private Transaction transaction;

        private Cursor cursor;

        private BulkOpListener listener;

        private UnmodifiableIterator<List<ObjectId>> unsortedIds;

        private Iterator<ObjectId> sortedIds;

        /**
         * Uses a transaction to open a read only cursor for it to work when called from a different
         * threads than the one it was created at. The transaction is aborted at {@link #close()}
         */
        public CursorRevObjectIterator(final Iterator<ObjectId> objectIds,
                final BulkOpListener listener) {

            this.unsortedIds = Iterators.partition(objectIds, getBulkPartitionSize());
            this.sortedIds = Iterators.emptyIterator();

            this.listener = listener;
            CursorConfig cursorConfig = new CursorConfig();
            cursorConfig.setReadUncommitted(true);
            transaction = getOrCreateTransaction();
            this.cursor = objectDb.openCursor(transaction, cursorConfig);
        }

        private Transaction getOrCreateTransaction() {
            final boolean transactional = objectDb.getConfig().getTransactional();
            if (!transactional) {
                return null;
            }
            TransactionConfig config = new TransactionConfig();
            config.setReadUncommitted(true);
            Transaction t = env.beginTransaction(null, config);
            return t;
        }

        @Override
        protected RevObject computeNext() {
            if (!sortedIds.hasNext()) {
                if (unsortedIds.hasNext()) {
                    List<ObjectId> unsorted = unsortedIds.next();
                    List<ObjectId> sorted = ObjectId.NATURAL_ORDER.sortedCopy(unsorted);
                    this.sortedIds = sorted.iterator();
                } else {
                    close();
                    return endOfData();
                }
            }
            try {

                byte[] keyBuff = new byte[ObjectId.NUM_BYTES];
                DatabaseEntry key = new DatabaseEntry(keyBuff);

                RevObject found = null;
                while (sortedIds.hasNext() && found == null) {
                    ObjectId id = sortedIds.next();
                    id.getRawValue(keyBuff);
                    key.setData(keyBuff);

                    DatabaseEntry data = new DatabaseEntry();
                    // lookup data for the next key
                    OperationStatus status;
                    status = cursor.getSearchKey(key, data, LockMode.READ_UNCOMMITTED);
                    if (SUCCESS.equals(status)) {
                        InputStream rawData;
                        rawData = new LZFInputStream(new ByteArrayInputStream(data.getData()));
                        found = reader.read(id, rawData);
                        listener.found(found.getId(), data.getSize());
                    } else {
                        listener.notFound(id);
                    }
                }
                if (found == null) {
                    return computeNext();
                }
                return found;
            } catch (Exception e) {
                try {
                    throw Throwables.propagate(e);
                } finally {
                    close();
                }
            }
        }

        @Override
        public void close() {
            sortedIds = null;
            Cursor cursor = this.cursor;
            this.cursor = null;
            if (cursor != null) {
                cursor.close();
            }
            if (transaction != null) {
                transaction.abort();
                transaction = null;
            }
        }
    }

    private int getBulkPartitionSize() {
        Optional<Integer> configuredSize = configDB
                .get(BULK_PARTITIONING_CONFIG_KEY, Integer.class);
        return configuredSize.or(DEFAULT_BULK_PARTITIONING).intValue();
    }

    @Nullable
    private Transaction newTransaction() {
        final boolean transactional = objectDb.getConfig().getTransactional();
        if (transactional) {
            TransactionConfig txConfig = new TransactionConfig();
            txConfig.setReadUncommitted(true);
            txConfig.setDurability(Durability.COMMIT_NO_SYNC);
            Transaction transaction = env.beginTransaction(null, txConfig);
            return transaction;
        }
        return null;
    }
}
