package org.mapdb.store;

import org.jetbrains.annotations.NotNull;
import org.mapdb.DBException;
import org.mapdb.io.DataInput2;
import org.mapdb.io.DataInput2ByteArray;
import org.mapdb.io.DataOutput2;
import org.mapdb.io.DataOutput2ByteArray;
import org.mapdb.ser.Serializer;

import java.util.LinkedList;
import java.util.Queue;

public class LiStore implements Store {

    private final static int PAGE_SIZE = 1024;

    private final long[] index = new long[100_000];

    private final Queue<Integer> freeRecids = new LinkedList<>();
    private int recidTail = 1;

    private final Queue<Long> freePages = new LinkedList<>();
    private long pageTail = PAGE_SIZE;

    private final byte[] data = new byte[64*1024*1024];



    @Override
    public long preallocate() {
        int recid = allocRecid();
        index[recid] = 2L<<(7*8);
        return recid;
    }

    @Override
    public <R> void preallocatePut(long recid, @NotNull Serializer<R> serializer, @NotNull R record) {
        long indexVal = index[(int) recid];
        if(indexVal==0L)
            throw new DBException.RecordNotPreallocated();
        int recType = decompIndexValType(indexVal);
        if(recType != 2)
            throw new DBException.RecordNotPreallocated();

        long page = allocPage();
        int size = serializeToPage(record, serializer, (int) page);
        index[(int) recid] = composeIndexValSmall(size, page);
    }

    @Override
    public <R> @NotNull long put(@NotNull R record, @NotNull Serializer<R> serializer) {
        long page = allocPage();
        int size = serializeToPage(record, serializer, (int) page);

        int recid = allocRecid();

        index[recid] = composeIndexValSmall(size, page);
        return recid;
    }


    protected <R> int serializeToPage(@NotNull R record, @NotNull Serializer<R> serializer, long page) {
        DataOutput2 out = new DataOutput2ByteArray();
        serializer.serialize(out, record);
        byte[] b = out.copyBytes();
        if(b.length>PAGE_SIZE)
            throw new RuntimeException();

        System.arraycopy(b, 0, data, (int) page, b.length);
        return b.length;
    }

    private int allocRecid() {
        Integer recid = freeRecids.poll();
        if(recid == null)
            return recidTail++;
        return recid;
    }

    private long allocPage() {
        Long ret = freePages.poll();
        if(ret==null) {
            ret = pageTail;
            pageTail+=PAGE_SIZE;
        }
        return ret;
    }

    @Override
    public <R> void update(long recid, @NotNull Serializer<R> serializer, @NotNull R updatedRecord) {
        long indexVal = index[(int) recid];
        if(indexVal==0L)
            throw new DBException.RecordNotFound();
        int recType = decompIndexValType(indexVal);
        if(recType == 2)
            throw new DBException.PreallocRecordAccess();
        int size = decompIndexValSize(indexVal);
        long page = decompIndexValPage(indexVal);


        int newSize = serializeToPage(updatedRecord, serializer, page);
        index[(int) recid] = composeIndexValSmall(newSize, page);
    }

    @Override
    public void verify() {

    }

    @Override
    public void commit() {

    }

    @Override
    public void compact() {

    }

    @Override
    public boolean isThreadSafe() {
        return false;
    }

    @Override
    public <R> void updateAtomic(long recid, @NotNull Serializer<R> serializer, @NotNull Transform<R> r) {
        R rec = get(recid, serializer);
        rec = r.transform(rec);
        update(recid, serializer, rec);
    }

    @Override
    public <R> boolean compareAndUpdate(long recid, @NotNull Serializer<R> serializer, @NotNull R expectedOldRecord, @NotNull R updatedRecord) {
        R r = get(recid, serializer);
        if(!serializer.equals(r,expectedOldRecord))
            return false;
        update(recid, serializer, updatedRecord);
        return true;
    }

    @Override
    public <R> boolean compareAndDelete(long recid, @NotNull Serializer<R> serializer, @NotNull R expectedOldRecord) {
        R r = get(recid, serializer);
        if(!serializer.equals(r,expectedOldRecord))
            return false;
        delete(recid, serializer);
        return true;
    }

    @Override
    public <R> void delete(long recid, @NotNull Serializer<R> serializer) {
        long indexVal = index[(int) recid];
        if(indexVal==0L)
            throw new DBException.RecordNotFound();
        int recType = decompIndexValType(indexVal);
        if(recType == 2)
            throw new DBException.PreallocRecordAccess();
        int size = decompIndexValSize(indexVal);
        long page = decompIndexValPage(indexVal);

        index[(int) recid] = 0L;
        freeRecids.add((int) recid);
        for(int i = (int) page; i<page+PAGE_SIZE; i++){
            data[i] =0;
        }
        freePages.add(page);
    }

    @Override
    public <R> @NotNull R getAndDelete(long recid, @NotNull Serializer<R> serializer) {
        R r = get(recid, serializer);
        delete(recid, serializer);
        return r;
    }

    @Override
    public <K> @NotNull K get(long recid, @NotNull Serializer<K> ser) {
        long indexVal = index[(int) recid];
        if(indexVal==0L)
            throw new DBException.RecordNotFound();
        int recType = decompIndexValType(indexVal);
        if(recType == 2)
            throw new DBException.PreallocRecordAccess();
        int size = decompIndexValSize(indexVal);
        long page = decompIndexValPage(indexVal);

        byte[] b = new byte[size];
        System.arraycopy(data, (int) page,  b, 0, size);
        DataInput2 input = new DataInput2ByteArray(b);
        return ser.deserialize(input);
    }

    private long decompIndexValPage(long indexVal) {
        return indexVal & 0xFFFFFFFFFFL;
    }

    private int decompIndexValSize(long indexVal) {
        return (int) ((indexVal >>> (5*8)) & 0xFFFF);
    }

    private int decompIndexValType(long indexVal) {
        return (int) (indexVal >>> (7*8));
    }


    private long composeIndexValSmall(int size, long page) {
        return  (1L<< (7*8)) |
                (((long)size)<<(5*8)) |
                page;
    }

    @Override
    public void close() {

    }

    @Override
    public void getAll(@NotNull GetAllCallback callback) {
        for(int recid = 1; recid<recidTail; recid++){
            long indexVal = index[recid];
            if(indexVal==0L)
                continue;
            int recType = decompIndexValType(indexVal);
            if(recType == 2)
                continue;
            int size = decompIndexValSize(indexVal);
            long page = decompIndexValPage(indexVal);
            byte[] b = new  byte[size];
            System.arraycopy(data, (int) page, b, 0, size);
            callback.takeOne(recid, b);
        }

    }

    @Override
    public boolean isEmpty() {
        return freeRecids.size() == recidTail-1;
    }
}
