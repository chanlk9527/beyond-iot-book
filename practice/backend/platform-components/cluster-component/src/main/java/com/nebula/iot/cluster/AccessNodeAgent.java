package com.nebula.iot.cluster;

import java.time.Duration;
import java.util.concurrent.*;

/** One executor per process. It executes only intent addressed to this non-reusable instance identity. */
public final class AccessNodeAgent implements AutoCloseable {
    public interface LocalAccess {
        boolean running();
        int connections();
        int sessions();
        int capacity();
        String metricsJson();
        void admit(boolean enabled);
        CompletionStage<Integer> disconnectBatch(int count);
    }
    private final JdbcNodeDirectory directory;
    private final String nodeId;
    private final String name;
    private final Duration lease;
    private final LocalAccess access;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("access-node-heartbeat").factory());
    private volatile long deadline;
    private volatile String state="STARTING";
    private volatile boolean started;
    private volatile boolean closed;
    private boolean exitRecorded;

    public AccessNodeAgent(JdbcNodeDirectory directory,String nodeId,String name,Duration lease,LocalAccess access) {
        this.directory=directory;this.nodeId=nodeId;this.name=name;this.lease=lease;this.access=access;
    }
    public String nodeId(){return nodeId;}
    public boolean serving(){return started && !closed && deadline-System.nanoTime()>0 && (state.equals("ACTIVE")||state.equals("DRAINING"));}
    public boolean admitting(){return serving() && state.equals("ACTIVE");}
    public boolean healthy(){return serving() && access.running();}

    public synchronized void start() {
        if(started || closed) throw new IllegalStateException("Node agent cannot be restarted with the same identity");
        long began=System.nanoTime();
        directory.register(nodeId,name,access.capacity());
        deadline=began+lease.minusMillis(100).toNanos();
        started=true;
        tick();
        if(!healthy()) throw new IllegalStateException("Access node failed readiness");
        executor.scheduleWithFixedDelay(this::tick,1000,1000,TimeUnit.MILLISECONDS);
    }
    private void tick() {
        if(closed || !started) return;
        try {
            if(!access.running() || deadline-System.nanoTime()<=0) throw new IllegalStateException("Node expired or listener stopped");
            long began=System.nanoTime();
            var current=directory.heartbeat(nodeId,access.connections(),access.metricsJson());
            long next=began+lease.minusMillis(100).toNanos();
            if(next-System.nanoTime()<=0) throw new IllegalStateException("Heartbeat exceeded its lease budget");
            state=current.state();deadline=next;
            access.admit(state.equals("ACTIVE"));
            if(state.equals("DRAINING")) {
                var operation=directory.activeDrain(nodeId);
                if(operation!=null) {
                    int batch=operation.deadline().isAfter(current.lastHeartbeat())?operation.batchSize():access.capacity();
                    access.disconnectBatch(batch).toCompletableFuture().get(5,TimeUnit.SECONDS);
                    directory.drainProgress(operation.operationId(),nodeId,access.sessions());
                }
            }
        } catch(Exception error) {
            // A directory error fences immediately; this identity never silently rejoins after a partition.
            state="FENCED";deadline=0;access.admit(false);
            access.disconnectBatch(access.capacity());
            System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR,"Access node fenced; restart required",error);
            closed=true;
            executor.shutdown();
        }
    }
    @Override public synchronized void close() {
        if(exitRecorded)return;
        exitRecorded=true;
        boolean registered=started;
        closed=true;state="STOPPING";deadline=0;access.admit(false);
        executor.shutdownNow();
        if(registered) {
            try {directory.offline(nodeId);directory.expireOperations();}
            catch(RuntimeException error) {System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Node exit deferred to lease expiry",error);}
        }
    }
}
